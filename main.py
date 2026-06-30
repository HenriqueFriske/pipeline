import os
import sys
import time
import json
import shutil
import logging
import csv
import subprocess
from typing import Any, Dict, Optional, Set

from google import genai
from google.genai import types
from pydantic import BaseModel, Field

import core.api_client
import core.defects4j_mgr
import core.ledger
import core.sonarqube_anal

# Standard personas
PERSONAS = {
    "P-1": "You are an experienced artisan baker specializing in sourdough breads and traditional pastries. You have no knowledge of software engineering, programming, or computer science.",
    "P-2": "",
    "P-3": "You are a developer.",
    "P-4": "You are a Senior Java Software Architect and Clean Code expert."
}

class RefactorResult(BaseModel):
    refactored_code: str = Field(description="The refactored Java code. MUST preserve newlines and standard indentation.")

# Lazy logger setup
_logger = None

def get_logger() -> logging.Logger:
    global _logger
    if _logger is None:
        os.makedirs("logs", exist_ok=True)
        _logger = logging.getLogger("pipeline")
        _logger.setLevel(logging.DEBUG)
        _logger.propagate = False
        if not _logger.handlers:
            file_handler = logging.FileHandler("logs/pipeline.log", encoding="utf-8")
            file_formatter = logging.Formatter('%(asctime)s - %(levelname)s - %(message)s')
            file_handler.setFormatter(file_formatter)
            _logger.addHandler(file_handler)
            
            console_handler = logging.StreamHandler(sys.stdout)
            console_handler.setLevel(logging.INFO)
            console_formatter = logging.Formatter('%(asctime)s - %(levelname)s - %(message)s')
            console_handler.setFormatter(console_formatter)
            _logger.addHandler(console_handler)
    return _logger


def ensure_sonarqube_online(sonar_mgr: core.sonarqube_anal.SonarQubeManager) -> None:
    """Checks if SonarQube is online. If offline, loops until online, emitting beep alerts."""
    logger = get_logger()
    first_offline = True
    while True:
        try:
            if sonar_mgr.ping():
                if not first_offline:
                    logger.info("SonarQube is back online.")
                break
        except Exception:
            pass
        
        if first_offline:
            logger.warning("PAUSE_SYSTEM_ERROR: SonarQube is offline. Pausing execution.")
            first_offline = False
            
        sys.stdout.write("\a")
        sys.stdout.flush()
        time.sleep(5)


def call_sonar_with_retry(sonar_mgr: core.sonarqube_anal.SonarQubeManager, func: Any, *args: Any, **kwargs: Any) -> Any:
    """Calls a SonarQubeManager function, retrying infinitely on network/server errors."""
    while True:
        ensure_sonarqube_online(sonar_mgr)
        try:
            return func(*args, **kwargs)
        except (core.sonarqube_anal.SonarQubeServerError, core.sonarqube_anal.SonarQubeConnectionError) as e:
            get_logger().error(f"SonarQube network/server error: {e}. Retrying after status check...")
            ensure_sonarqube_online(sonar_mgr)


def run_pipeline(
    settings_path: str = "config/settings.json",
    snippets_path: str = "config/snippets.json",
    ledger_path: str = "checkpoint_ledger.csv",
    personas_override: Optional[Dict[str, str]] = None,
    replicas_override: Optional[int] = None,
    refac_type_filter: Optional[str] = None
) -> None:
    logger = get_logger()
    logger.info("Starting pipeline execution.")

    # 1. Load config files
    if not os.path.exists(settings_path):
        raise FileNotFoundError(f"Settings file not found: {settings_path}")
    if not os.path.exists(snippets_path):
        raise FileNotFoundError(f"Snippets file not found: {snippets_path}")

    with open(settings_path, "r", encoding="utf-8") as f:
        settings = json.load(f)
    with open(snippets_path, "r", encoding="utf-8") as f:
        snippets = json.load(f)

    if refac_type_filter:
        snippets = [s for s in snippets if s["refatoracao_tipo"].lower() == refac_type_filter.lower()]
        logger.info(f"Filtered to {len(snippets)} snippets of type: {refac_type_filter}")

    # 2. Setup managers
    d4j_path = settings["defects4j_path"]
    if os.path.exists(d4j_path):
        d4j_path = os.path.abspath(d4j_path)
        
    sonar_path = settings["sonar_scanner_path"]
    if os.path.exists(sonar_path):
        sonar_path = os.path.abspath(sonar_path)

    sonar_mgr = core.sonarqube_anal.SonarQubeManager(
        url=settings["sonar_url"],
        token=settings["sonar_token"],
        scanner_bin=sonar_path
    )
    d4j_mgr = core.defects4j_mgr.Defects4JManager(
        d4j_bin=d4j_path,
        java_home=settings.get("java_home"),
        perl5lib=settings.get("perl5lib")
    )
    ledger_mgr = core.ledger.LedgerManager(filepath=ledger_path)

    completed_ids = ledger_mgr.get_completed_ids()
    logger.info(f"Loaded {len(completed_ids)} completed rounds from ledger.")

    # Define personas and replicas loops
    personas = personas_override if personas_override is not None else PERSONAS
    replicas_limit = 3 if replicas_override is None else replicas_override
    replicas = list(range(1, replicas_limit + 1))

    # Build full list of rounds
    rounds_metadata = []
    id_rodada = 1
    for snippet in snippets:
        for persona_key, persona_preamble in personas.items():
            for replica in replicas:
                rounds_metadata.append({
                    "id_rodada": id_rodada,
                    "snippet": snippet,
                    "persona_key": persona_key,
                    "persona_preamble": persona_preamble,
                    "replica": replica
                })
                id_rodada += 1

    total_rounds = len(rounds_metadata)
    os.makedirs("refactored_code", exist_ok=True)
    os.makedirs("logs/raw_responses", exist_ok=True)

    # --- PHASE 1: Generate all refactorings first ---
    logger.info("\n=========================================")
    logger.info("=== PHASE 1: GENERATING ALL REFACTORINGS ===")
    logger.info("=========================================")

    # Initialize Gemini client
    api_key = settings.get("api_key") or os.environ.get("GEMINI_API_KEY")
    if not api_key:
        # Check .env manually
        if os.path.exists(".env"):
            with open(".env", "r") as f:
                for line in f:
                    if line.strip().startswith("GEMINI_API_KEY="):
                        api_key = line.strip().split("=", 1)[1].strip("'\" ")
                        break
    if not api_key:
        logger.error("Error: GEMINI_API_KEY is missing.")
        sys.exit(1)
        
    client = genai.Client(api_key=api_key)

    for round_info in rounds_metadata:
        rid = round_info["id_rodada"]
        snippet = round_info["snippet"]
        persona_key = round_info["persona_key"]
        persona_preamble = round_info["persona_preamble"]
        replica = round_info["replica"]

        if rid in completed_ids:
            continue

        snippet_id = snippet["trecho"]
        refac_dir = os.path.join("refactored_code", snippet_id, persona_key, f"replica_{replica}")
        raw_dir = os.path.join("logs/raw_responses", snippet_id, persona_key, f"replica_{replica}")
        os.makedirs(refac_dir, exist_ok=True)
        os.makedirs(raw_dir, exist_ok=True)
        
        refac_file = os.path.join(refac_dir, "code.java")
        raw_json_file = os.path.join(raw_dir, "response.json")

        # Check if already generated in case we are resuming
        if os.path.exists(refac_file) and os.path.exists(raw_json_file):
            logger.info(f"Refactoring for Round {rid} already generated. Skipping API call.")
            continue

        logger.info(f"Generating Refactoring for Round {rid}/{total_rounds} (Snippet: {snippet['trecho']}, Persona: {persona_key})")

        # Checkout workspace to read source code
        temp_round_dir = os.path.join(settings["workspace_root"], f"round_refac_{rid}")
        try:
            d4j_mgr.checkout(snippet["project"], snippet["version"], temp_round_dir)
            source_file_path = os.path.join(temp_round_dir, snippet["file_path"])
            
            with open(source_file_path, "r", encoding="utf-8") as f:
                original_code = f.read()

            # Dynamic targeted prompt instruction (Option A)
            line_num = snippet.get("line", "unknown")
            ref_type = snippet.get("refatoracao_tipo", "Refactor")
            targeted_instruction = f"Refactor the following Java code to improve readability and maintainability without changing its behavior. Focus only on the method or block of code located around line {line_num} by applying '{ref_type}' refactoring. Do NOT modify the signatures of other methods, their behavior, or any other parts of the class. Return the complete refactored class."
            formatting_instruction = "\nIMPORTANT: The refactored Java code in your response MUST be properly formatted with standard indentation and newlines. Do not compress the code into a single line."
            full_instruction = targeted_instruction + formatting_instruction

            # Call Gemini 3.1 flash lite
            response = client.models.generate_content(
                model="gemini-3.1-flash-lite",
                contents=f"{full_instruction}\n\n{original_code}",
                config=types.GenerateContentConfig(
                    response_mime_type="application/json",
                    response_schema=RefactorResult,
                    system_instruction=persona_preamble or None,
                )
            )

            # Save raw response metadata and text
            raw_json_str = response.model_dump_json(indent=2)
            with open(raw_json_file, "w", encoding="utf-8") as f_raw:
                f_raw.write(raw_json_str)

            # Parse and save code
            result = response.parsed
            if result is None or not getattr(result, "refactored_code", None):
                raise ValueError("Parsed structured response is empty or invalid.")
            
            refactored_code = result.refactored_code
            with open(refac_file, "w", encoding="utf-8") as f_out:
                f_out.write(refactored_code)

            logger.info(f"Saved refactored code to: {refac_file}")

        except Exception as e:
            logger.error(f"Error generating refactoring for Round {rid}: {e}")
            # Save a dummy error marker so we don't try again
            with open(refac_file + ".error", "w", encoding="utf-8") as f_err:
                f_err.write(str(e))
        finally:
            # Cleanup temp checkout
            if os.path.exists(temp_round_dir):
                shutil.rmtree(temp_round_dir, ignore_errors=True)

        # Sleep to avoid rate limits
        time.sleep(15)

    # --- PHASE 2: Validation, Tests & SonarQube Metrics ---
    logger.info("\n=========================================")
    logger.info("=== PHASE 2: RUNNING VALIDATION & METRICS ===")
    logger.info("=========================================")

    round_times = []
    last_failure = "Nenhuma"
    scanned_pos_projects: Set[str] = set()

    for round_info in rounds_metadata:
        rid = round_info["id_rodada"]
        snippet = round_info["snippet"]
        persona_key = round_info["persona_key"]
        replica = round_info["replica"]

        if rid in completed_ids:
            continue

        round_start_time = time.time()
        logger.info(f"--- Processing Metrics for Round {rid}/{total_rounds} ---")

        snippet_id = snippet["trecho"]
        refac_dir = os.path.join("refactored_code", snippet_id, persona_key, f"replica_{replica}")
        raw_dir = os.path.join("logs/raw_responses", snippet_id, persona_key, f"replica_{replica}")
        
        refac_file = os.path.join(refac_dir, "code.java")
        raw_json_file = os.path.join(raw_dir, "response.json")
        
        status = "VALIDO"
        complexity_base = None
        complexity_pos = None
        smells_base = None
        smells_pos = None
        token_count = 0

        # Check if refactoring failed in Phase 1
        if os.path.exists(refac_file + ".error") or not os.path.exists(refac_file):
            status = "FALHA_API"
            logger.warning(f"Skipping round {rid}: Refactoring failed during Phase 1.")
            # Record failed run
            ledger_mgr.write_row({
                "id_rodada": rid,
                "trecho": snippet["trecho"],
                "refatoracao_tipo": snippet["refatoracao_tipo"],
                "persona": persona_key,
                "replica": replica,
                "status": status,
                "complexity_base": "",
                "complexity_pos": "",
                "complexity_delta": "",
                "smells_base": "",
                "smells_pos": "",
                "smells_delta": "",
                "tempo_execucao_seg": 0,
                "token_count": 0
            })
            continue

        # Load tokens from raw response
        try:
            with open(raw_json_file, "r", encoding="utf-8") as f_raw:
                resp_data = json.load(f_raw)
            usage = resp_data.get("usage_metadata", {})
            token_count = usage.get("total_token_count", 0)
        except Exception:
            token_count = 0

        round_dir = os.path.join(settings["workspace_root"], f"round_{rid}")

        try:
            # Checkout workspace
            d4j_mgr.checkout(snippet["project"], snippet["version"], round_dir)

            # Export class directories
            d4j_mgr.compile(round_dir)
            classes_dir = d4j_mgr.export_classes_dir(round_dir)
            # Correct classes directory relative to workdir (using target/classes)
            abs_classes_dir = os.path.abspath(os.path.join(round_dir, classes_dir))

            # Original file details
            java_file_path = os.path.join(round_dir, snippet["file_path"])
            abs_source_file = os.path.abspath(java_file_path)

            if not os.path.exists(abs_source_file):
                raise FileNotFoundError(f"Source file not found at {abs_source_file}")

            # Run SonarScanner baseline
            project_key_base = f"{snippet['trecho']}_P{persona_key.replace('-', '')}_R{replica}_base"
            project_name_base = f"{snippet['trecho']}_base"
            
            logger.info("Running SonarQube baseline scan.")
            call_sonar_with_retry(sonar_mgr, sonar_mgr.scan, project_key_base, project_name_base, abs_source_file, abs_classes_dir)
            
            task_ok = call_sonar_with_retry(sonar_mgr, sonar_mgr.wait_for_task, project_key_base)
            if not task_ok:
                raise core.sonarqube_anal.SonarQubeError("Baseline SonarQube task failed.")

            metrics_base = call_sonar_with_retry(sonar_mgr, sonar_mgr.get_metrics, project_key_base)
            complexity_base = metrics_base.get("complexity", 0)
            smells_base = metrics_base.get("code_smells", 0)

            # Delete temporary baseline project
            call_sonar_with_retry(sonar_mgr, sonar_mgr.delete_project, project_key_base)

            # Read and apply refactored code
            with open(refac_file, "r", encoding="utf-8") as f_ref:
                refactored_code = f_ref.read()

            with open(abs_source_file, "w", encoding="utf-8") as f:
                f.write(refactored_code)

            # Compile refactored project
            logger.info("Compiling refactored code.")
            compile_ok = d4j_mgr.compile(round_dir)
            if not compile_ok:
                status = "FALHA_COMPILACAO"
                logger.error("Compilation failed after refactoring.")
            else:
                # Run tests
                logger.info("Running test suite.")
                test_result = d4j_mgr.test(round_dir, timeout=180)
                if test_result != "PASS":
                    status = test_result  # FALHA_TESTES_COMPORTAMENTO or FALHA_TESTES_TIMEOUT
                    logger.error(f"Tests failed with result: {test_result}")
                else:
                    # Post-refactoring SonarQube analysis
                    project_key_pos = f"{snippet['trecho']}_P{persona_key.replace('-', '')}_R{replica}"
                    project_name_pos = snippet['trecho']

                    logger.info("Running post-refactoring SonarQube scan.")
                    call_sonar_with_retry(sonar_mgr, sonar_mgr.scan, project_key_pos, project_name_pos, abs_source_file, abs_classes_dir)
                    scanned_pos_projects.add(project_key_pos)

                    task_ok = call_sonar_with_retry(sonar_mgr, sonar_mgr.wait_for_task, project_key_pos)
                    if not task_ok:
                        raise core.sonarqube_anal.SonarQubeError("Post-refactoring SonarQube task failed.")

                    metrics_pos = call_sonar_with_retry(sonar_mgr, sonar_mgr.get_metrics, project_key_pos)
                    complexity_pos = metrics_pos.get("complexity", 0)
                    smells_pos = metrics_pos.get("code_smells", 0)

                    status = "VALIDO"
                    logger.info("Round completed successfully.")

        except core.sonarqube_anal.SonarQubeError as e:
            status = "FALHA_ANALISE"
            logger.error(f"SonarQube analysis error: {e}")
        except Exception as e:
            status = "ERRO_SISTEMA"
            logger.error(f"Unexpected system error during round: {e}")
        finally:
            # Record to ledger
            tempo_execucao_seg = time.time() - round_start_time
            complexity_delta = ""
            smells_delta = ""

            if complexity_base is not None and complexity_pos is not None:
                complexity_delta = complexity_pos - complexity_base
            if smells_base is not None and smells_pos is not None:
                smells_delta = smells_pos - smells_base

            row_data = {
                "id_rodada": rid,
                "trecho": snippet["trecho"],
                "refatoracao_tipo": snippet["refatoracao_tipo"],
                "persona": persona_key,
                "replica": replica,
                "status": status,
                "complexity_base": complexity_base if complexity_base is not None else "",
                "complexity_pos": complexity_pos if complexity_pos is not None else "",
                "complexity_delta": complexity_delta,
                "smells_base": smells_base if smells_base is not None else "",
                "smells_pos": smells_pos if smells_pos is not None else "",
                "smells_delta": smells_delta,
                "tempo_execucao_seg": round(tempo_execucao_seg, 2),
                "token_count": token_count
            }
            ledger_mgr.write_row(row_data)

            # Save checkpoint to text file
            with open("checkpoint.txt", "w", encoding="utf-8") as f_chk:
                f_chk.write(str(rid))

            # Keep track of durations for ETA
            round_times.append(tempo_execucao_seg)
            # Update last failure
            if status != "VALIDO":
                last_failure = f"Rodada {rid} ({status})"
            
            # Calculate ETA
            avg_time = sum(round_times) / len(round_times)
            remaining_rounds = total_rounds - rid
            eta_seconds = avg_time * remaining_rounds
            
            if remaining_rounds > 0:
                eta_h = int(eta_seconds // 3600)
                eta_m = int((eta_seconds % 3600) // 60)
                eta_s = int(eta_seconds % 60)
                eta_str = f"{eta_h:02d}h {eta_m:02d}m {eta_s:02d}s"
            else:
                eta_str = "Concluído"
                
            progress_pct = (rid / total_rounds) * 100
            
            logger.info("=========================================")
            logger.info(f"DASHBOARD PROGRESSO - Rodada {rid}/{total_rounds} ({progress_pct:.1f}%)")
            logger.info(f"Status Atual: {status} | Tempo: {tempo_execucao_seg:.2f}s")
            logger.info(f"Média p/ Rodada: {avg_time:.2f}s | ETA: {eta_str}")
            logger.info(f"Última Falha Mapeada: {last_failure}")
            logger.info("=========================================")

            # Clean up temporary round workspace
            if os.path.exists(round_dir):
                shutil.rmtree(round_dir, ignore_errors=True)

            # Every 30 rounds cleanup projects
            if rid % 30 == 0:
                logger.info(f"Cleaning up {len(scanned_pos_projects)} SonarQube projects to free database space.")
                for key in list(scanned_pos_projects):
                    call_sonar_with_retry(sonar_mgr, sonar_mgr.delete_project, key)
                scanned_pos_projects.clear()

    logger.info("Pipeline execution finished.")


if __name__ == "__main__":
    settings_arg = sys.argv[1] if len(sys.argv) > 1 else "config/settings.json"
    snippets_arg = sys.argv[2] if len(sys.argv) > 2 else "config/snippets.json"
    ledger_arg = sys.argv[3] if len(sys.argv) > 3 else None
    refac_filter = sys.argv[4] if len(sys.argv) > 4 else None

    if refac_filter is None:
        print("\n=== SELECAO DE REFATORACAO ===")
        print("1. ExtractMethod")
        print("2. ReplaceMagicNumber")
        print("3. ReplaceConditionalWithPolymorphism")
        print("4. Executar todas (Sem filtro)")
        
        while True:
            try:
                choice = input("Escolha (1-4): ").strip()
                if choice == "1":
                    refac_filter = "ExtractMethod"
                    break
                elif choice == "2":
                    refac_filter = "ReplaceMagicNumber"
                    break
                elif choice == "3":
                    refac_filter = "ReplaceConditionalWithPolymorphism"
                    break
                elif choice == "4":
                    refac_filter = None
                    break
                else:
                    print("Opcao invalida. Escolha entre 1 e 4.")
            except (KeyboardInterrupt, EOFError):
                print("\nExecucao cancelada pelo usuario.")
                sys.exit(0)

    # Set dynamic default ledger name based on selected filter
    if ledger_arg is None:
        if refac_filter:
            ledger_arg = f"checkpoint_{refac_filter.lower()}.csv"
        else:
            ledger_arg = "checkpoint_ledger.csv"

    run_pipeline(
        settings_path=settings_arg,
        snippets_path=snippets_arg,
        ledger_path=ledger_arg,
        refac_type_filter=refac_filter
    )

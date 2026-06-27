import os
import sys
import time
import json
import shutil
import logging
from typing import Any, Dict, Optional, Set

import core.api_client
import core.defects4j_mgr
import core.ledger
import core.sonarqube_anal

# Standard personas
PERSONAS = {
    "P-1": "You are an experienced artisan baker specializing in sourdough breads and traditional pastries. You have no knowledge of software engineering, programming, or computer science.",
    "P-2": "",
    "P-3": "You are a developer.",
    "P-4": "You are a Senior Java Software Architect and Clean Code expert.",
    "P-5": "You are a Senior Java Software Architect and Clean Code expert. You are fully contextualized within the project architecture, adhering to its specific design patterns, dependency structures, and development guidelines."
}

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
    replicas_override: Optional[int] = None
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

    # 2. Setup managers
    sonar_mgr = core.sonarqube_anal.SonarQubeManager(
        url=settings["sonar_url"],
        token=settings["sonar_token"],
        scanner_bin=settings["sonar_scanner_path"]
    )
    d4j_mgr = core.defects4j_mgr.Defects4JManager(
        d4j_bin=settings["defects4j_path"],
        java_home=settings.get("java_home"),
        perl5lib=settings.get("perl5lib")
    )
    ledger_mgr = core.ledger.LedgerManager(filepath=ledger_path)

    # Support resuming: get completed round IDs
    completed_ids = ledger_mgr.get_completed_ids()
    logger.info(f"Loaded {len(completed_ids)} completed rounds from ledger.")

    # Define personas and replicas loops
    personas = personas_override if personas_override is not None else PERSONAS
    replicas_limit = 5 if replicas_override is None else replicas_override
    replicas = list(range(1, replicas_limit + 1))

    id_rodada = 1
    scanned_pos_projects: Set[str] = set()
    total_rounds = len(snippets) * len(personas) * len(replicas)
    round_times = []
    last_failure = "Nenhuma"

    for snippet in snippets:
        for persona_key, persona_preamble in personas.items():
            for replica in replicas:
                if id_rodada == 3:
                    logger.info("Reached round 3 limit. Stopping as requested.")
                    return
                # Check resuming
                if id_rodada in completed_ids:
                    logger.info(f"Skipping completed round {id_rodada}")
                    id_rodada += 1
                    continue

                # Safety check before starting
                ensure_sonarqube_online(sonar_mgr)

                round_start_time = time.time()
                round_dir = os.path.join(settings["workspace_root"], f"round_{id_rodada}")
                logger.info(f"--- Starting Round {id_rodada} (Snippet: {snippet['trecho']}, Persona: {persona_key}, Replica: {replica}) ---")

                # Metrics defaults
                status = "VALIDO"
                complexity_base = None
                complexity_pos = None
                smells_base = None
                smells_pos = None
                token_count = 0

                try:
                    # Checkout workspace
                    d4j_mgr.checkout(snippet["project"], snippet["version"], round_dir)

                    # Export class directories
                    d4j_mgr.compile(round_dir)
                    classes_dir = d4j_mgr.export_classes_dir(round_dir)
                    abs_classes_dir = os.path.abspath(os.path.join(round_dir, classes_dir))

                    # Original file details
                    java_file_path = os.path.join(round_dir, snippet["file_path"])
                    abs_source_file = os.path.abspath(java_file_path)

                    if not os.path.exists(abs_source_file):
                        raise FileNotFoundError(f"Source file not found at {abs_source_file}")

                    with open(abs_source_file, "r", encoding="utf-8") as f:
                        original_code = f.read()

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

                    # Call Gemini API
                    logger.info("Calling LLM for refactoring.")
                    try:
                        refactored_raw = core.api_client.generate_refactoring(
                            persona_preamble=persona_preamble,
                            base_instruction=settings["base_instruction"],
                            code_snippet=original_code,
                            api_key=settings["api_key"]
                        )
                        token_count = len(refactored_raw.split())
                    except core.api_client.APIRateLimitError:
                        status = "FALHA_API_RATE_LIMIT"
                        logger.error("LLM call failed: APIRateLimitError")
                        continue
                    except core.api_client.APITimeoutError:
                        status = "FALHA_API_TIMEOUT"
                        logger.error("LLM call failed: APITimeoutError")
                        continue
                    except Exception as e:
                        status = "FALHA_API"
                        logger.error(f"LLM call failed with unexpected error: {e}")
                        continue

                    # Structured output already returns clean Java code.
                    refactored_code = refactored_raw

                    # Write refactored code
                    with open(abs_source_file, "w", encoding="utf-8") as f:
                        f.write(refactored_code)

                    # Compile refactored project
                    logger.info("Compiling refactored code.")
                    compile_ok = d4j_mgr.compile(round_dir)
                    if not compile_ok:
                        status = "FALHA_COMPILACAO"
                        logger.error("Compilation failed after refactoring.")
                        continue

                    # Run tests
                    logger.info("Running test suite.")
                    test_result = d4j_mgr.test(round_dir, timeout=180)
                    if test_result != "PASS":
                        status = test_result  # FALHA_TESTES_COMPORTAMENTO or FALHA_TESTES_TIMEOUT
                        logger.error(f"Tests failed with result: {test_result}")
                        continue

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
                        "id_rodada": id_rodada,
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

                    # Keep track of durations for ETA
                    round_times.append(tempo_execucao_seg)
                    # Update last failure
                    if status != "VALIDO":
                        last_failure = f"Rodada {id_rodada} ({status})"
                    
                    # Calculate ETA
                    avg_time = sum(round_times) / len(round_times)
                    remaining_rounds = total_rounds - id_rodada
                    eta_seconds = avg_time * remaining_rounds
                    
                    if remaining_rounds > 0:
                        eta_h = int(eta_seconds // 3600)
                        eta_m = int((eta_seconds % 3600) // 60)
                        eta_s = int(eta_seconds % 60)
                        eta_str = f"{eta_h:02d}h {eta_m:02d}m {eta_s:02d}s"
                    else:
                        eta_str = "Concluído"
                        
                    progress_pct = (id_rodada / total_rounds) * 100
                    
                    logger.info("=========================================")
                    logger.info(f"DASHBOARD PROGRESSO - Rodada {id_rodada}/{total_rounds} ({progress_pct:.1f}%)")
                    logger.info(f"Status Atual: {status} | Tempo: {tempo_execucao_seg:.2f}s")
                    logger.info(f"Média p/ Rodada: {avg_time:.2f}s | ETA: {eta_str}")
                    logger.info(f"Última Falha Mapeada: {last_failure}")
                    logger.info("=========================================")

                    # Clean up temporary round workspace
                    if os.path.exists(round_dir):
                        shutil.rmtree(round_dir, ignore_errors=True)

                    # Every 30 rounds cleanup projects
                    if id_rodada % 30 == 0:
                        logger.info(f"Cleaning up {len(scanned_pos_projects)} SonarQube projects to free database space.")
                        for key in list(scanned_pos_projects):
                            call_sonar_with_retry(sonar_mgr, sonar_mgr.delete_project, key)
                        scanned_pos_projects.clear()

                    # Increment round ID
                    id_rodada += 1

    logger.info("Pipeline execution finished.")


if __name__ == "__main__":
    run_pipeline()

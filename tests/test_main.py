import os
import json
import pytest
from unittest.mock import MagicMock, patch, call

from core.api_client import APIRateLimitError, APITimeoutError, GeminiAPIError
from core.sonarqube_anal import SonarQubeServerError


@pytest.fixture(autouse=True)
def _isolate_cwd(tmp_path, monkeypatch):
    # run_pipeline writes refactored_code/, checkpoint.txt and logs/ relative to
    # the CWD. chdir into the per-test tmp dir so the real project tree stays clean.
    monkeypatch.chdir(tmp_path)


def test_orchestrator_runs_complete_loop(tmp_path):
    settings = {
        "api_key": "test_key",
        "sonar_url": "http://localhost:9000",
        "sonar_token": "test_token",
        "sonar_scanner_path": "sonar-scanner",
        "defects4j_path": "defects4j",
        "java_home": None,
        "base_instruction": "Refactor:",
        "workspace_root": str(tmp_path / "workspace")
    }

    snippets = [
        {
            "trecho": "Math_1_ExtractMethod",
            "project": "Math",
            "version": "1",
            "file_path": "src/Fraction.java",
            "refatoracao_tipo": "ExtractMethod"
        }
    ]

    settings_file = tmp_path / "settings.json"
    snippets_file = tmp_path / "snippets.json"
    ledger_file = tmp_path / "ledger.csv"

    with open(settings_file, "w") as f:
        json.dump(settings, f)
    with open(snippets_file, "w") as f:
        json.dump(snippets, f)

    with patch("core.sonarqube_anal.SonarQubeManager") as mock_sonar_cls, \
         patch("core.defects4j_mgr.Defects4JManager") as mock_d4j_cls, \
         patch("core.api_client.generate_refactoring") as mock_gen_ref, \
         patch("core.ledger.LedgerManager") as mock_ledger_cls:

         mock_sonar = mock_sonar_cls.return_value
         mock_sonar.ping.return_value = True
         mock_sonar.wait_for_task.return_value = True
         mock_sonar.get_metrics.side_effect = [
             {"complexity": 10, "code_smells": 5}, # baseline
             {"complexity": 8, "code_smells": 3}   # pos
         ]

         mock_d4j = mock_d4j_cls.return_value
         mock_d4j.compile.return_value = True
         mock_d4j.test.return_value = "PASS"
         mock_d4j.export_classes_dir.return_value = "target/classes"

         # Structured output returns clean Java code directly.
         mock_gen_ref.return_value = "refactored_code"

         mock_ledger = mock_ledger_cls.return_value
         mock_ledger.get_completed_ids.return_value = set()

         original_file_path = tmp_path / "workspace" / "round_1" / "src" / "Fraction.java"
         os.makedirs(os.path.dirname(original_file_path), exist_ok=True)
         with open(original_file_path, "w") as f:
             f.write("original_code")

         from main import run_pipeline

         run_pipeline(
             settings_path=str(settings_file),
             snippets_path=str(snippets_file),
             ledger_path=str(ledger_file),
             personas_override={"P-1": "Baker Persona"},
             replicas_override=1
         )

         mock_sonar.ping.assert_called()
         mock_d4j.checkout.assert_called_once()
         mock_d4j.compile.assert_called()
         mock_d4j.export_classes_dir.assert_called_once()
         print("ACTUAL CALLS TO SCAN:", mock_sonar.scan.call_args_list)
         mock_sonar.scan.assert_any_call(
             "Math_1_ExtractMethod_PP1_R1_base",
             "Math_1_ExtractMethod_base",
             os.path.abspath(original_file_path),
             os.path.abspath(tmp_path / "workspace" / "round_1" / "target/classes")
         )
         mock_sonar.delete_project.assert_any_call("Math_1_ExtractMethod_PP1_R1_base")
         mock_gen_ref.assert_called_once_with(
             persona_preamble="Baker Persona",
             base_instruction=settings["base_instruction"],
             code_snippet="original_code",
             api_key=settings["api_key"]
         )
         mock_d4j.test.assert_called_once()

         mock_sonar.scan.assert_any_call(
             "Math_1_ExtractMethod_PP1_R1",
             "Math_1_ExtractMethod",
             os.path.abspath(original_file_path),
             os.path.abspath(tmp_path / "workspace" / "round_1" / "target/classes")
         )

         mock_ledger.write_row.assert_called_once()
         written_data = mock_ledger.write_row.call_args[0][0]
         assert written_data["id_rodada"] == 1
         assert written_data["trecho"] == "Math_1_ExtractMethod"
         assert written_data["persona"] == "P-1"
         assert written_data["replica"] == 1
         assert written_data["status"] == "VALIDO"
         assert written_data["complexity_base"] == 10
         assert written_data["complexity_pos"] == 8
         assert written_data["complexity_delta"] == -2
         assert written_data["smells_base"] == 5
         assert written_data["smells_pos"] == 3
         assert written_data["smells_delta"] == -2


def test_orchestrator_skips_completed_rounds(tmp_path):
    settings = {
        "api_key": "test_key",
        "sonar_url": "http://localhost:9000",
        "sonar_token": "test_token",
        "sonar_scanner_path": "sonar-scanner",
        "defects4j_path": "defects4j",
        "java_home": None,
        "base_instruction": "Refactor:",
        "workspace_root": str(tmp_path / "workspace")
    }
    snippets = [
        {
            "trecho": "Math_1_ExtractMethod",
            "project": "Math",
            "version": "1",
            "file_path": "src/Fraction.java",
            "refatoracao_tipo": "ExtractMethod"
        }
    ]

    settings_file = tmp_path / "settings.json"
    snippets_file = tmp_path / "snippets.json"
    ledger_file = tmp_path / "ledger.csv"

    with open(settings_file, "w") as f:
        json.dump(settings, f)
    with open(snippets_file, "w") as f:
        json.dump(snippets, f)

    with patch("core.sonarqube_anal.SonarQubeManager") as mock_sonar_cls, \
         patch("core.defects4j_mgr.Defects4JManager") as mock_d4j_cls, \
         patch("core.ledger.LedgerManager") as mock_ledger_cls:

         mock_sonar = mock_sonar_cls.return_value
         mock_sonar.ping.return_value = True

         mock_ledger = mock_ledger_cls.return_value
         mock_ledger.get_completed_ids.return_value = {1}

         from main import run_pipeline

         run_pipeline(
             settings_path=str(settings_file),
             snippets_path=str(snippets_file),
             ledger_path=str(ledger_file),
             personas_override={"P-1": "Baker Persona"},
             replicas_override=1
         )

         mock_d4j_cls.return_value.checkout.assert_not_called()
         mock_ledger.write_row.assert_not_called()


@patch("time.sleep")
@patch("sys.stdout.write")
def test_orchestrator_pauses_when_sonar_offline(mock_stdout_write, mock_sleep, tmp_path):
    settings = {
        "api_key": "test_key",
        "sonar_url": "http://localhost:9000",
        "sonar_token": "test_token",
        "sonar_scanner_path": "sonar-scanner",
        "defects4j_path": "defects4j",
        "java_home": None,
        "base_instruction": "Refactor:",
        "workspace_root": str(tmp_path / "workspace")
    }
    snippets = [
        {
            "trecho": "Math_1_ExtractMethod",
            "project": "Math",
            "version": "1",
            "file_path": "src/Fraction.java",
            "refatoracao_tipo": "ExtractMethod"
        }
    ]

    settings_file = tmp_path / "settings.json"
    snippets_file = tmp_path / "snippets.json"
    ledger_file = tmp_path / "ledger.csv"

    with open(settings_file, "w") as f:
        json.dump(settings, f)
    with open(snippets_file, "w") as f:
        json.dump(snippets, f)

    with patch("core.sonarqube_anal.SonarQubeManager") as mock_sonar_cls, \
         patch("core.defects4j_mgr.Defects4JManager") as mock_d4j_cls, \
         patch("core.ledger.LedgerManager") as mock_ledger_cls:

         mock_sonar = mock_sonar_cls.return_value
         ping_vals = [False, False]
         mock_sonar.ping.side_effect = lambda: ping_vals.pop(0) if ping_vals else True
         mock_sonar.wait_for_task.return_value = True
         mock_sonar.get_metrics.return_value = {"complexity": 5, "code_smells": 1}

         mock_d4j = mock_d4j_cls.return_value
         mock_d4j.compile.return_value = True
         mock_d4j.test.return_value = "PASS"
         mock_d4j.export_classes_dir.return_value = "target/classes"

         mock_ledger = mock_ledger_cls.return_value
         mock_ledger.get_completed_ids.return_value = set()

         original_file_path = tmp_path / "workspace" / "round_1" / "src" / "Fraction.java"
         os.makedirs(os.path.dirname(original_file_path), exist_ok=True)
         with open(original_file_path, "w") as f:
             f.write("original_code")

         with patch("core.api_client.generate_refactoring", return_value="code"):

             from main import run_pipeline
             run_pipeline(
                 settings_path=str(settings_file),
                 snippets_path=str(snippets_file),
                 ledger_path=str(ledger_file),
                 personas_override={"P-1": "Baker Persona"},
                 replicas_override=1
             )

         assert mock_sonar.ping.call_count >= 3
         mock_stdout_write.assert_any_call("\a")
         assert mock_sleep.call_count >= 2


def test_orchestrator_handles_api_errors(tmp_path):
    settings = {
        "api_key": "test_key",
        "sonar_url": "http://localhost:9000",
        "sonar_token": "test_token",
        "sonar_scanner_path": "sonar-scanner",
        "defects4j_path": "defects4j",
        "java_home": None,
        "base_instruction": "Refactor:",
        "workspace_root": str(tmp_path / "workspace")
    }
    snippets = [
        {
            "trecho": "Math_1_ExtractMethod",
            "project": "Math",
            "version": "1",
            "file_path": "src/Fraction.java",
            "refatoracao_tipo": "ExtractMethod"
        }
    ]

    settings_file = tmp_path / "settings.json"
    snippets_file = tmp_path / "snippets.json"
    ledger_file = tmp_path / "ledger.csv"

    with open(settings_file, "w") as f:
        json.dump(settings, f)
    with open(snippets_file, "w") as f:
        json.dump(snippets, f)

    # Scenario 1: APIRateLimitError
    with patch("core.sonarqube_anal.SonarQubeManager") as mock_sonar_cls, \
         patch("core.defects4j_mgr.Defects4JManager") as mock_d4j_cls, \
         patch("core.api_client.generate_refactoring") as mock_gen_ref, \
         patch("core.ledger.LedgerManager") as mock_ledger_cls:

         mock_sonar = mock_sonar_cls.return_value
         mock_sonar.ping.return_value = True
         mock_sonar.wait_for_task.return_value = True
         mock_sonar.get_metrics.return_value = {"complexity": 5, "code_smells": 1}

         mock_d4j = mock_d4j_cls.return_value
         mock_d4j.compile.return_value = True
         mock_d4j.export_classes_dir.return_value = "target/classes"

         mock_gen_ref.side_effect = APIRateLimitError("Rate limit")

         mock_ledger = mock_ledger_cls.return_value
         mock_ledger.get_completed_ids.return_value = set()

         original_file_path = tmp_path / "workspace" / "round_1" / "src" / "Fraction.java"
         os.makedirs(os.path.dirname(original_file_path), exist_ok=True)
         with open(original_file_path, "w") as f:
             f.write("original_code")

         from main import run_pipeline
         run_pipeline(
             settings_path=str(settings_file),
             snippets_path=str(snippets_file),
             ledger_path=str(ledger_file),
             personas_override={"P-1": "Baker Persona"},
             replicas_override=1
         )

         written_data = mock_ledger.write_row.call_args[0][0]
         assert written_data["status"] == "FALHA_API_RATE_LIMIT"

    # Scenario 2: APITimeoutError
    with patch("core.sonarqube_anal.SonarQubeManager") as mock_sonar_cls, \
         patch("core.defects4j_mgr.Defects4JManager") as mock_d4j_cls, \
         patch("core.api_client.generate_refactoring") as mock_gen_ref, \
         patch("core.ledger.LedgerManager") as mock_ledger_cls:

         mock_sonar = mock_sonar_cls.return_value
         mock_sonar.ping.return_value = True
         mock_sonar.wait_for_task.return_value = True
         mock_sonar.get_metrics.return_value = {"complexity": 5, "code_smells": 1}

         mock_d4j = mock_d4j_cls.return_value
         mock_d4j.compile.return_value = True
         mock_d4j.export_classes_dir.return_value = "target/classes"

         mock_gen_ref.side_effect = APITimeoutError("Timeout")

         mock_ledger = mock_ledger_cls.return_value
         mock_ledger.get_completed_ids.return_value = set()

         original_file_path = tmp_path / "workspace" / "round_1" / "src" / "Fraction.java"
         os.makedirs(os.path.dirname(original_file_path), exist_ok=True)
         with open(original_file_path, "w") as f:
             f.write("original_code")

         run_pipeline(
             settings_path=str(settings_file),
             snippets_path=str(snippets_file),
             ledger_path=str(ledger_file),
             personas_override={"P-1": "Baker Persona"},
             replicas_override=1
         )

         written_data = mock_ledger.write_row.call_args[0][0]
         assert written_data["status"] == "FALHA_API_TIMEOUT"


def test_orchestrator_handles_api_and_compile_failures(tmp_path):
    settings = {
        "api_key": "test_key",
        "sonar_url": "http://localhost:9000",
        "sonar_token": "test_token",
        "sonar_scanner_path": "sonar-scanner",
        "defects4j_path": "defects4j",
        "java_home": None,
        "base_instruction": "Refactor:",
        "workspace_root": str(tmp_path / "workspace")
    }
    snippets = [
        {
            "trecho": "Math_1_ExtractMethod",
            "project": "Math",
            "version": "1",
            "file_path": "src/Fraction.java",
            "refatoracao_tipo": "ExtractMethod"
        }
    ]

    settings_file = tmp_path / "settings.json"
    snippets_file = tmp_path / "snippets.json"
    ledger_file = tmp_path / "ledger.csv"

    with open(settings_file, "w") as f:
        json.dump(settings, f)
    with open(snippets_file, "w") as f:
        json.dump(snippets, f)

    # Scenario 1: Generic LLM failure (e.g. empty/invalid structured output) -> FALHA_API
    with patch("core.sonarqube_anal.SonarQubeManager") as mock_sonar_cls, \
         patch("core.defects4j_mgr.Defects4JManager") as mock_d4j_cls, \
         patch("core.api_client.generate_refactoring") as mock_gen_ref, \
         patch("core.ledger.LedgerManager") as mock_ledger_cls:

         mock_sonar = mock_sonar_cls.return_value
         mock_sonar.ping.return_value = True
         mock_sonar.wait_for_task.return_value = True
         mock_sonar.get_metrics.return_value = {"complexity": 5, "code_smells": 1}

         mock_d4j = mock_d4j_cls.return_value
         mock_d4j.compile.return_value = True
         mock_d4j.export_classes_dir.return_value = "target/classes"

         mock_gen_ref.side_effect = GeminiAPIError("Empty structured output")

         mock_ledger = mock_ledger_cls.return_value
         mock_ledger.get_completed_ids.return_value = set()

         original_file_path = tmp_path / "workspace" / "round_1" / "src" / "Fraction.java"
         os.makedirs(os.path.dirname(original_file_path), exist_ok=True)
         with open(original_file_path, "w") as f:
             f.write("original_code")

         from main import run_pipeline
         run_pipeline(
             settings_path=str(settings_file),
             snippets_path=str(snippets_file),
             ledger_path=str(ledger_file),
             personas_override={"P-1": "Baker Persona"},
             replicas_override=1
         )

         written_data = mock_ledger.write_row.call_args[0][0]
         assert written_data["status"] == "FALHA_API"

    # Scenario 2: Compilation Failure
    with patch("core.sonarqube_anal.SonarQubeManager") as mock_sonar_cls, \
         patch("core.defects4j_mgr.Defects4JManager") as mock_d4j_cls, \
         patch("core.api_client.generate_refactoring") as mock_gen_ref, \
         patch("core.ledger.LedgerManager") as mock_ledger_cls:

         mock_sonar = mock_sonar_cls.return_value
         mock_sonar.ping.return_value = True
         mock_sonar.wait_for_task.return_value = True
         mock_sonar.get_metrics.return_value = {"complexity": 5, "code_smells": 1}

         mock_d4j = mock_d4j_cls.return_value
         # Baseline compile succeeds, post-refactor compile fails
         mock_d4j.compile.side_effect = [True, False]
         mock_d4j.export_classes_dir.return_value = "target/classes"

         mock_gen_ref.return_value = "refactored"

         mock_ledger = mock_ledger_cls.return_value
         mock_ledger.get_completed_ids.return_value = set()

         original_file_path = tmp_path / "workspace" / "round_1" / "src" / "Fraction.java"
         os.makedirs(os.path.dirname(original_file_path), exist_ok=True)
         with open(original_file_path, "w") as f:
             f.write("original_code")

         run_pipeline(
             settings_path=str(settings_file),
             snippets_path=str(snippets_file),
             ledger_path=str(ledger_file),
             personas_override={"P-1": "Baker Persona"},
             replicas_override=1
         )

         written_data = mock_ledger.write_row.call_args[0][0]
         assert written_data["status"] == "FALHA_COMPILACAO"


def test_orchestrator_handles_test_failures(tmp_path):
    settings = {
        "api_key": "test_key",
        "sonar_url": "http://localhost:9000",
        "sonar_token": "test_token",
        "sonar_scanner_path": "sonar-scanner",
        "defects4j_path": "defects4j",
        "java_home": None,
        "base_instruction": "Refactor:",
        "workspace_root": str(tmp_path / "workspace")
    }
    snippets = [
        {
            "trecho": "Math_1_ExtractMethod",
            "project": "Math",
            "version": "1",
            "file_path": "src/Fraction.java",
            "refatoracao_tipo": "ExtractMethod"
        }
    ]

    settings_file = tmp_path / "settings.json"
    snippets_file = tmp_path / "snippets.json"
    ledger_file = tmp_path / "ledger.csv"

    with open(settings_file, "w") as f:
        json.dump(settings, f)
    with open(snippets_file, "w") as f:
        json.dump(snippets, f)

    # Scenario 1: Behavioral/regression test failure
    with patch("core.sonarqube_anal.SonarQubeManager") as mock_sonar_cls, \
         patch("core.defects4j_mgr.Defects4JManager") as mock_d4j_cls, \
         patch("core.api_client.generate_refactoring") as mock_gen_ref, \
         patch("core.ledger.LedgerManager") as mock_ledger_cls:

         mock_sonar = mock_sonar_cls.return_value
         mock_sonar.ping.return_value = True
         mock_sonar.wait_for_task.return_value = True
         mock_sonar.get_metrics.return_value = {"complexity": 5, "code_smells": 1}

         mock_d4j = mock_d4j_cls.return_value
         mock_d4j.compile.return_value = True
         mock_d4j.test.return_value = "FALHA_TESTES_COMPORTAMENTO"
         mock_d4j.export_classes_dir.return_value = "target/classes"

         mock_gen_ref.return_value = "refactored"

         mock_ledger = mock_ledger_cls.return_value
         mock_ledger.get_completed_ids.return_value = set()

         original_file_path = tmp_path / "workspace" / "round_1" / "src" / "Fraction.java"
         os.makedirs(os.path.dirname(original_file_path), exist_ok=True)
         with open(original_file_path, "w") as f:
             f.write("original_code")

         from main import run_pipeline
         run_pipeline(
             settings_path=str(settings_file),
             snippets_path=str(snippets_file),
             ledger_path=str(ledger_file),
             personas_override={"P-1": "Baker Persona"},
             replicas_override=1
         )

         written_data = mock_ledger.write_row.call_args[0][0]
         assert written_data["status"] == "FALHA_TESTES_COMPORTAMENTO"

    # Scenario 2: Test Timeout
    with patch("core.sonarqube_anal.SonarQubeManager") as mock_sonar_cls, \
         patch("core.defects4j_mgr.Defects4JManager") as mock_d4j_cls, \
         patch("core.api_client.generate_refactoring") as mock_gen_ref, \
         patch("core.ledger.LedgerManager") as mock_ledger_cls:

         mock_sonar = mock_sonar_cls.return_value
         mock_sonar.ping.return_value = True
         mock_sonar.wait_for_task.return_value = True
         mock_sonar.get_metrics.return_value = {"complexity": 5, "code_smells": 1}

         mock_d4j = mock_d4j_cls.return_value
         mock_d4j.compile.return_value = True
         mock_d4j.test.return_value = "FALHA_TESTES_TIMEOUT"
         mock_d4j.export_classes_dir.return_value = "target/classes"

         mock_gen_ref.return_value = "refactored"

         mock_ledger = mock_ledger_cls.return_value
         mock_ledger.get_completed_ids.return_value = set()

         original_file_path = tmp_path / "workspace" / "round_1" / "src" / "Fraction.java"
         os.makedirs(os.path.dirname(original_file_path), exist_ok=True)
         with open(original_file_path, "w") as f:
             f.write("original_code")

         run_pipeline(
             settings_path=str(settings_file),
             snippets_path=str(snippets_file),
             ledger_path=str(ledger_file),
             personas_override={"P-1": "Baker Persona"},
             replicas_override=1
         )

         written_data = mock_ledger.write_row.call_args[0][0]
         assert written_data["status"] == "FALHA_TESTES_TIMEOUT"

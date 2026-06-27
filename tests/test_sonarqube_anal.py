import pytest
from unittest.mock import MagicMock, Mock, patch
import subprocess
import requests
import time
from core.sonarqube_anal import (
    SonarQubeManager,
    SonarQubeError,
    SonarQubeConnectionError,
    SonarQubeServerError,
)

@patch("requests.get")
def test_ping_success(mock_get):
    mock_response = MagicMock()
    mock_response.status_code = 200
    mock_response.json.return_value = {"status": "UP"}
    mock_get.return_value = mock_response
    
    manager = SonarQubeManager(url="http://sonar.test", token="my-token")
    assert manager.ping() is True
    
    mock_get.assert_called_once_with(
        "http://sonar.test/api/system/status",
        auth=("my-token", ""),
        timeout=5
    )

@patch("requests.get")
def test_ping_failure(mock_get):
    # Case 1: Non-200 status code (404)
    mock_response = MagicMock()
    mock_response.status_code = 404
    mock_get.return_value = mock_response
    
    manager = SonarQubeManager(url="http://sonar.test", token="my-token")
    assert manager.ping() is False

@patch("subprocess.run")
def test_scan_success(mock_run):
    mock_res = MagicMock()
    mock_res.returncode = 0
    mock_run.return_value = mock_res
    
    manager = SonarQubeManager(url="http://sonar.test", token="my-token", scanner_bin="my-sonar-scanner")
    manager.scan("my_key", "my_name", "src/file.java", "build/classes")
    
    expected_args = [
        "my-sonar-scanner",
        "-Dsonar.projectKey=my_key",
        "-Dsonar.projectName=my_name",
        "-Dsonar.sources=src/file.java",
        "-Dsonar.host.url=http://sonar.test",
        "-Dsonar.java.binaries=build/classes"
    ]
    mock_run.assert_called_once()
    args, kwargs = mock_run.call_args
    assert args[0] == expected_args
    assert kwargs.get("capture_output") is True
    assert kwargs.get("timeout") == 180
    assert kwargs.get("env", {}).get("SONAR_TOKEN") == "my-token"

@patch("subprocess.run")
def test_scan_failure(mock_run):
    mock_res = MagicMock()
    mock_res.returncode = 1
    mock_res.stdout = b"some detailed stdout output"
    mock_res.stderr = b"some detailed stderr output"
    mock_run.return_value = mock_res
    
    manager = SonarQubeManager(url="http://sonar.test", token="my-token")
    with pytest.raises(RuntimeError) as exc_info:
        manager.scan("my_key", "my_name", "src/file.java", "build/classes")
    error_msg = str(exc_info.value)
    assert "sonar-scanner failed: some detailed stderr output" in error_msg
    assert "some detailed stdout output" in error_msg

@patch("requests.get")
@patch("core.sonarqube_anal.sleep")
def test_wait_for_task_success(mock_sleep, mock_get):
    resp_pending = MagicMock()
    resp_pending.status_code = 200
    resp_pending.json.return_value = {"tasks": [{"status": "PENDING"}]}
    
    resp_success = MagicMock()
    resp_success.status_code = 200
    resp_success.json.return_value = {"tasks": [{"status": "SUCCESS"}]}
    
    mock_get.side_effect = [resp_pending, resp_success]
    
    manager = SonarQubeManager(url="http://sonar.test", token="my-token")
    assert manager.wait_for_task("my_key", timeout=10) is True
    assert mock_get.call_count == 2
    mock_sleep.assert_called_with(2)

@patch("requests.get")
@patch("core.sonarqube_anal.sleep")
def test_wait_for_task_failed(mock_sleep, mock_get):
    resp_failed = MagicMock()
    resp_failed.status_code = 200
    resp_failed.json.return_value = {"tasks": [{"status": "FAILED"}]}
    
    mock_get.return_value = resp_failed
    
    manager = SonarQubeManager(url="http://sonar.test", token="my-token")
    assert manager.wait_for_task("my_key", timeout=10) is False
    assert mock_get.call_count == 1

@patch("requests.get")
@patch("core.sonarqube_anal.sleep")
@patch("core.sonarqube_anal.time")
def test_wait_for_task_timeout(mock_time, mock_sleep, mock_get):
    mock_time.side_effect = [100.0, 101.0, 103.0]
    resp_pending = MagicMock()
    resp_pending.status_code = 200
    resp_pending.json.return_value = {"tasks": [{"status": "PENDING"}]}
    mock_get.return_value = resp_pending
    
    manager = SonarQubeManager(url="http://sonar.test", token="my-token")
    assert manager.wait_for_task("my_key", timeout=2) is False

@patch("requests.get")
def test_wait_for_task_http_error(mock_get):
    mock_resp = MagicMock()
    mock_resp.status_code = 404
    mock_get.return_value = mock_resp
    
    manager = SonarQubeManager(url="http://sonar.test", token="my-token")
    with pytest.raises(SonarQubeConnectionError) as exc_info:
        manager.wait_for_task("my_key", timeout=10)
    assert "Unexpected response status" in str(exc_info.value)
    assert mock_get.call_count == 1

@patch("requests.get")
def test_wait_for_task_connection_error(mock_get):
    mock_get.side_effect = requests.RequestException("Connection refused")
    
    manager = SonarQubeManager(url="http://sonar.test", token="my-token")
    with pytest.raises(SonarQubeServerError) as exc_info:
        manager.wait_for_task("my_key", timeout=10)
    assert "Error polling task status" in str(exc_info.value)
    assert mock_get.call_count == 1

@patch("requests.get")
def test_get_metrics_success(mock_get):
    mock_resp = MagicMock()
    mock_resp.status_code = 200
    mock_resp.json.return_value = {
        "component": {
            "measures": [
                {"metric": "complexity", "value": "15"},
                {"metric": "code_smells", "value": "3"}
            ]
        }
    }
    mock_get.return_value = mock_resp
    
    manager = SonarQubeManager(url="http://sonar.test", token="my-token")
    metrics = manager.get_metrics("my_key")
    assert metrics == {"complexity": 15, "code_smells": 3}
    mock_get.assert_called_once_with(
        "http://sonar.test/api/measures/component",
        params={"component": "my_key", "metricKeys": "complexity,code_smells"},
        auth=("my-token", ""),
        timeout=10
    )

@patch("requests.get")
def test_get_metrics_missing_keys(mock_get):
    # Case 1: empty measures list
    mock_resp_empty = MagicMock()
    mock_resp_empty.status_code = 200
    mock_resp_empty.json.return_value = {
        "component": {
            "measures": []
        }
    }
    mock_get.return_value = mock_resp_empty
    manager = SonarQubeManager(url="http://sonar.test", token="my-token")
    assert manager.get_metrics("my_key") == {"complexity": 0, "code_smells": 0}
    
    # Case 2: component is present but has no measures key
    mock_resp_no_measures = MagicMock()
    mock_resp_no_measures.status_code = 200
    mock_resp_no_measures.json.return_value = {
        "component": {}
    }
    mock_get.return_value = mock_resp_no_measures
    assert manager.get_metrics("my_key") == {"complexity": 0, "code_smells": 0}

@patch("requests.get")
def test_get_metrics_failure(mock_get):
    mock_resp = MagicMock()
    mock_resp.status_code = 404
    mock_resp.text = "Not Found"
    mock_get.return_value = mock_resp
    
    manager = SonarQubeManager(url="http://sonar.test", token="my-token")
    with pytest.raises(SonarQubeConnectionError):
        manager.get_metrics("my_key")

@patch("requests.post")
def test_delete_project_success(mock_post):
    mock_resp = MagicMock()
    mock_resp.status_code = 204
    mock_post.return_value = mock_resp
    
    manager = SonarQubeManager(url="http://sonar.test", token="my-token")
    assert manager.delete_project("my_key") is True
    mock_post.assert_called_once_with(
        "http://sonar.test/api/projects/delete",
        data={"project": "my_key"},
        auth=("my-token", ""),
        timeout=10
    )

@patch("requests.post")
def test_delete_project_failure(mock_post):
    mock_resp = MagicMock()
    mock_resp.status_code = 404
    mock_post.return_value = mock_resp
    
    manager = SonarQubeManager(url="http://sonar.test", token="my-token")
    assert manager.delete_project("my_key") is False

@patch("core.sonarqube_anal._get_logger")
@patch("subprocess.run")
def test_scan_token_masking(mock_run, mock_get_logger):
    mock_res = MagicMock()
    mock_res.returncode = 0
    mock_run.return_value = mock_res
    
    mock_logger = MagicMock()
    mock_get_logger.return_value = mock_logger
    
    manager = SonarQubeManager(url="http://sonar.test", token="secret-token-123", scanner_bin="my-sonar-scanner")
    manager.scan("my_key", "my_name", "src/file.java", "build/classes")
    
    info_calls = [call[0][0] for call in mock_logger.info.call_args_list]
    log_msg = next((msg for msg in info_calls if "Running sonar-scanner command:" in msg), "")
    assert log_msg != ""
    assert "secret-token-123" not in log_msg


@patch("requests.get")
def test_get_metrics_float_values(mock_get):
    mock_resp = MagicMock()
    mock_resp.status_code = 200
    mock_resp.json.return_value = {
        "component": {
            "measures": [
                {"metric": "complexity", "value": "15.0"},
                {"metric": "code_smells", "value": "3.8"}
            ]
        }
    }
    mock_get.return_value = mock_resp
    
    manager = SonarQubeManager(url="http://sonar.test", token="my-token")
    metrics = manager.get_metrics("my_key")
    assert metrics == {"complexity": 15, "code_smells": 3}


@patch("requests.get")
def test_ping_server_error(mock_get):
    # Case 1: 500 Server Error
    mock_response = MagicMock()
    mock_response.status_code = 500
    mock_get.return_value = mock_response
    manager = SonarQubeManager(url="http://sonar.test", token="my-token")
    with pytest.raises(SonarQubeServerError):
        manager.ping()

    # Case 2: Connection / RequestException
    mock_get.side_effect = requests.RequestException("Connection error")
    with pytest.raises(SonarQubeServerError):
        manager.ping()


@patch("requests.get")
def test_ping_json_status(mock_get):
    # Case 1: Status 200 but status is UP
    mock_response = MagicMock()
    mock_response.status_code = 200
    mock_response.json.return_value = {"status": "UP"}
    mock_get.return_value = mock_response
    manager = SonarQubeManager(url="http://sonar.test", token="my-token")
    assert manager.ping() is True

    # Case 2: Status 200 but status is not UP (e.g., DOWN)
    mock_response = MagicMock()
    mock_response.status_code = 200
    mock_response.json.return_value = {"status": "DOWN"}
    mock_get.return_value = mock_response
    assert manager.ping() is False

    # Case 3: Status 200 but status is missing
    mock_response = MagicMock()
    mock_response.status_code = 200
    mock_response.json.return_value = {}
    mock_get.return_value = mock_response
    assert manager.ping() is False


@patch("requests.get")
def test_wait_for_task_server_error(mock_get):
    # Case 1: 500 Server Error
    mock_response = MagicMock()
    mock_response.status_code = 500
    mock_get.return_value = mock_response
    manager = SonarQubeManager(url="http://sonar.test", token="my-token")
    with pytest.raises(SonarQubeServerError):
        manager.wait_for_task("my_key", timeout=10)

    # Case 2: Connection Error
    mock_get.side_effect = requests.RequestException("Connection error")
    with pytest.raises(SonarQubeServerError):
        manager.wait_for_task("my_key", timeout=10)


@patch("requests.get")
def test_get_metrics_server_error(mock_get):
    # Case 1: 500 Server Error
    mock_response = MagicMock()
    mock_response.status_code = 500
    mock_get.return_value = mock_response
    manager = SonarQubeManager(url="http://sonar.test", token="my-token")
    with pytest.raises(SonarQubeServerError):
        manager.get_metrics("my_key")

    # Case 2: Connection Error
    mock_get.side_effect = requests.RequestException("Connection error")
    with pytest.raises(SonarQubeServerError):
        manager.get_metrics("my_key")


@patch("requests.post")
def test_delete_project_server_error(mock_post):
    # Case 1: 500 Server Error
    mock_response = MagicMock()
    mock_response.status_code = 500
    mock_post.return_value = mock_response
    manager = SonarQubeManager(url="http://sonar.test", token="my-token")
    with pytest.raises(SonarQubeServerError):
        manager.delete_project("my_key")

    # Case 2: Connection Error
    mock_post.side_effect = requests.RequestException("Connection error")
    with pytest.raises(SonarQubeServerError):
        manager.delete_project("my_key")


@patch("subprocess.run")
def test_scan_env_token_passing(mock_run):
    mock_res = MagicMock()
    mock_res.returncode = 0
    mock_run.return_value = mock_res
    
    manager = SonarQubeManager(url="http://sonar.test", token="my-token", scanner_bin="my-sonar-scanner")
    with patch.dict("os.environ", {"SOME_VAR": "value"}):
        manager.scan("my_key", "my_name", "src/file.java", "build/classes")
    
    mock_run.assert_called_once()
    args, kwargs = mock_run.call_args
    # Verify token is not in CLI args
    for arg in args[0]:
        assert "my-token" not in arg
        assert "-Dsonar.token" not in arg
    
    # Verify environment variable contains SONAR_TOKEN
    passed_env = kwargs.get("env", {})
    assert passed_env.get("SONAR_TOKEN") == "my-token"
    # Verify other env vars are preserved
    assert passed_env.get("SOME_VAR") == "value"


def _issue(rule, component, line, message="msg"):
    return {"rule": rule, "component": component, "line": line, "message": message, "effort": "5min"}


def test_search_issues_single_page_strips_prefix():
    from core.sonarqube_anal import SonarQubeManager
    mgr = SonarQubeManager(url="http://localhost:9000", token="t")
    resp = Mock()
    resp.status_code = 200
    resp.json.return_value = {
        "total": 2,
        "issues": [
            _issue("java:S109", "finder_Lang_1:src/main/java/A.java", 12),
            _issue("java:S3776", "finder_Lang_1:src/main/java/B.java", 30,
                   "Refactor this method to reduce its Cognitive Complexity from 27 to the 15 allowed."),
        ],
    }
    with patch("requests.get", return_value=resp):
        out = mgr.search_issues("finder_Lang_1", ["java:S109", "java:S3776"])
    assert len(out) == 2
    assert out[0] == {"rule": "java:S109", "file_path": "src/main/java/A.java",
                      "line": 12, "message": "msg", "effort": "5min"}
    assert out[1]["file_path"] == "src/main/java/B.java"


def test_search_issues_paginates():
    from core.sonarqube_anal import SonarQubeManager
    mgr = SonarQubeManager(url="http://localhost:9000", token="t")
    page1 = Mock(status_code=200)
    page1.json.return_value = {"total": 600, "issues": [_issue("java:S109", "k:f.java", i) for i in range(500)]}
    page2 = Mock(status_code=200)
    page2.json.return_value = {"total": 600, "issues": [_issue("java:S109", "k:f.java", i) for i in range(100)]}
    with patch("requests.get", side_effect=[page1, page2]) as mock_get:
        out = mgr.search_issues("k", ["java:S109"])
    assert len(out) == 600
    assert mock_get.call_count == 2


def test_search_issues_empty():
    from core.sonarqube_anal import SonarQubeManager
    mgr = SonarQubeManager(url="http://localhost:9000", token="t")
    resp = Mock(status_code=200)
    resp.json.return_value = {"total": 0, "issues": []}
    with patch("requests.get", return_value=resp):
        assert mgr.search_issues("k", ["java:S109"]) == []


def test_search_issues_server_error():
    from core.sonarqube_anal import SonarQubeManager, SonarQubeServerError
    mgr = SonarQubeManager(url="http://localhost:9000", token="t")
    resp = Mock(status_code=503, text="down")
    with patch("requests.get", return_value=resp):
        with pytest.raises(SonarQubeServerError):
            mgr.search_issues("k", ["java:S109"])


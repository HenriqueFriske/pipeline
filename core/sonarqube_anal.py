import os
from time import time, sleep
import subprocess
import logging
import requests
import threading

class SonarQubeError(Exception):
    """Base exception for all SonarQube errors."""
    pass

class SonarQubeConnectionError(SonarQubeError):
    """Exception raised when connecting to SonarQube fails."""
    pass

class SonarQubeServerError(SonarQubeError):
    """Exception raised when SonarQube server is offline or returns 5xx."""
    pass

# Lazy logger initialization
_logger = None
_logger_lock = threading.Lock()

def _get_logger() -> logging.Logger:
    global _logger
    with _logger_lock:
        if _logger is None:
            os.makedirs("logs", exist_ok=True)
            _logger = logging.getLogger("sonarqube")
            _logger.setLevel(logging.DEBUG)
            _logger.propagate = False
            if not _logger.handlers:
                file_handler = logging.FileHandler("logs/sonarqube.log", encoding="utf-8")
                formatter = logging.Formatter('%(asctime)s - %(levelname)s - %(message)s')
                file_handler.setFormatter(formatter)
                _logger.addHandler(file_handler)
    return _logger

class SonarQubeManager:
    def __init__(self, url: str = "http://localhost:9000", token: str = "", scanner_bin: str = "sonar-scanner"):
        self.url = url.rstrip("/")
        self.token = token
        self.scanner_bin = scanner_bin
        _get_logger().info(f"Initialized SonarQubeManager with url={self.url}")

    def ping(self) -> bool:
        url = f"{self.url}/api/system/status"
        logger = _get_logger()
        logger.info(f"Pinging SonarQube at {url}")
        try:
            response = requests.get(url, auth=(self.token, ""), timeout=5)
            logger.debug(f"Ping response status code: {response.status_code}")
            if response.status_code >= 500:
                raise SonarQubeServerError(f"SonarQube server returned 5xx error: {response.status_code}")
            if response.status_code != 200:
                return False
            try:
                return response.json().get("status") == "UP"
            except Exception:
                return False
        except requests.RequestException as e:
            logger.error(f"Ping failed: {e}")
            raise SonarQubeServerError(f"Connection failed: {e}") from e

    def scan(self, project_key: str, project_name: str, source_file: str, classes_dir: str):
        cmd = [
            self.scanner_bin,
            f"-Dsonar.projectKey={project_key}",
            f"-Dsonar.projectName={project_name}",
            f"-Dsonar.sources={source_file}",
            f"-Dsonar.host.url={self.url}",
            f"-Dsonar.java.binaries={classes_dir}"
        ]
        logger = _get_logger()
        cmd_for_log = [
            "-Dsonar.token=******" if arg.startswith("-Dsonar.token=") else arg
            for arg in cmd
        ]
        logger.info(f"Running sonar-scanner command: {' '.join(cmd_for_log)}")
        try:
            env = os.environ.copy()
            env["SONAR_TOKEN"] = self.token
            res = subprocess.run(cmd, env=env, capture_output=True, timeout=180)
            logger.debug(f"sonar-scanner exit code: {res.returncode}")
            if res.returncode != 0:
                stdout_str = res.stdout.decode('utf-8', errors='replace')
                stderr_str = res.stderr.decode('utf-8', errors='replace')
                logger.error(f"sonar-scanner failed. stdout: {stdout_str}\nstderr: {stderr_str}")
                raise RuntimeError(f"sonar-scanner failed: {stderr_str}\nstdout: {stdout_str}")
        except Exception as e:
            if not isinstance(e, RuntimeError):
                logger.error(f"Error running sonar-scanner: {e}")
                raise RuntimeError(f"Failed to execute sonar-scanner: {e}") from e
            raise

    def wait_for_task(self, project_key: str, timeout: int = 30) -> bool:
        url = f"{self.url}/api/ce/activity"
        params = {"component": project_key}
        logger = _get_logger()
        logger.info(f"Waiting for CE task for component {project_key} (timeout={timeout}s)")
        start_time = time()
        while True:
            try:
                response = requests.get(url, params=params, auth=(self.token, ""), timeout=10)
            except requests.RequestException as e:
                logger.error(f"Error polling task status: {e}")
                raise SonarQubeServerError(f"Error polling task status: {e}") from e

            logger.debug(f"wait_for_task GET status: {response.status_code}")
            if response.status_code >= 500:
                raise SonarQubeServerError(f"SonarQube server returned 5xx error: {response.status_code}")
            if response.status_code != 200:
                logger.warning(f"Unexpected response status in wait_for_task: {response.status_code}")
                raise SonarQubeConnectionError(f"Unexpected response status in wait_for_task: {response.status_code}")

            try:
                data = response.json()
            except Exception as e:
                logger.error(f"Error parsing task status JSON: {e}")
                raise SonarQubeError(f"Error parsing task status JSON: {e}") from e

            tasks = data.get("tasks", [])
            if tasks:
                status = tasks[0].get("status")
                logger.debug(f"Current task status: {status}")
                if status == "SUCCESS":
                    return True
                elif status in ("FAILED", "CANCELED"):
                    return False
            else:
                logger.debug("No tasks found for component yet")

            elapsed = time() - start_time
            if elapsed >= timeout:
                logger.warning("wait_for_task timed out")
                break
            
            sleep_time = min(2.0, timeout - elapsed)
            if sleep_time <= 0:
                break
            sleep(sleep_time)
        return False

    def get_metrics(self, project_key: str) -> dict:
        url = f"{self.url}/api/measures/component"
        params = {
            "component": project_key,
            "metricKeys": "complexity,code_smells"
        }
        logger = _get_logger()
        logger.info(f"Retrieving metrics for component {project_key}")
        try:
            try:
                response = requests.get(url, params=params, auth=(self.token, ""), timeout=10)
            except requests.RequestException as e:
                raise SonarQubeServerError(f"Error retrieving metrics: {e}") from e

            logger.debug(f"get_metrics response status code: {response.status_code}")
            if response.status_code >= 500:
                raise SonarQubeServerError(f"SonarQube server returned 5xx error: {response.status_code}")
            if response.status_code != 200:
                raise SonarQubeConnectionError(f"Failed to get metrics: HTTP {response.status_code} - {response.text}")
            
            data = response.json()
            component = data.get("component", {})
            measures = component.get("measures", [])
            
            metrics_dict = {"complexity": 0, "code_smells": 0}
            for m in measures:
                metric_name = m.get("metric")
                val_str = m.get("value")
                if metric_name in metrics_dict and val_str is not None:
                    try:
                        metrics_dict[metric_name] = int(float(val_str))
                    except ValueError as e:
                        logger.error(f"Failed to parse metric {metric_name} value '{val_str}': {e}")
            logger.debug(f"Parsed metrics: {metrics_dict}")
            return metrics_dict
        except Exception as e:
            logger.error(f"Error retrieving metrics: {e}")
            if not isinstance(e, (SonarQubeServerError, SonarQubeConnectionError, SonarQubeError)):
                raise SonarQubeError(f"Failed to retrieve metrics: {e}") from e
            raise

    def search_issues(self, component_key: str, rules: list, page_size: int = 500, max_pages: int = 20) -> list:
        url = f"{self.url}/api/issues/search"
        logger = _get_logger()
        rules_param = ",".join(rules)
        logger.info(f"Searching issues for {component_key} rules={rules_param}")
        results = []
        page = 1
        while page <= max_pages:
            params = {"componentKeys": component_key, "rules": rules_param, "ps": page_size, "p": page}
            try:
                response = requests.get(url, params=params, auth=(self.token, ""), timeout=30)
            except requests.RequestException as e:
                raise SonarQubeServerError(f"Error searching issues: {e}") from e

            if response.status_code >= 500:
                raise SonarQubeServerError(f"SonarQube server returned 5xx error: {response.status_code}")
            if response.status_code != 200:
                raise SonarQubeConnectionError(f"Failed to search issues: HTTP {response.status_code} - {response.text}")

            try:
                data = response.json()
            except Exception as e:
                raise SonarQubeError(f"Error parsing issues JSON: {e}") from e

            issues = data.get("issues", [])
            for issue in issues:
                component = issue.get("component", "")
                file_path = component.split(":", 1)[1] if ":" in component else component
                results.append({
                    "rule": issue.get("rule"),
                    "file_path": file_path,
                    "line": issue.get("line"),
                    "message": issue.get("message", ""),
                    "effort": issue.get("effort"),
                })

            total = data.get("total", 0)
            if not issues or page * page_size >= total:
                break
            page += 1
        return results

    def delete_project(self, project_key: str) -> bool:
        url = f"{self.url}/api/projects/delete"
        data = {"project": project_key}
        logger = _get_logger()
        logger.info(f"Deleting project {project_key}")
        try:
            try:
                response = requests.post(url, data=data, auth=(self.token, ""), timeout=10)
            except requests.RequestException as e:
                raise SonarQubeServerError(f"Delete project failed due to connection error: {e}") from e

            logger.debug(f"delete_project status code: {response.status_code}")
            if response.status_code >= 500:
                raise SonarQubeServerError(f"SonarQube server returned 5xx error: {response.status_code}")
            return response.status_code in (200, 204)
        except Exception as e:
            logger.error(f"Delete project failed: {e}")
            if isinstance(e, SonarQubeServerError):
                raise
            return False

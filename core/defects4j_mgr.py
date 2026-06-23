import os
import signal
import subprocess
import shutil
import logging
import threading

# Lazy logger initialization
_logger = None
_logger_lock = threading.Lock()

def _get_logger() -> logging.Logger:
    global _logger
    with _logger_lock:
        if _logger is None:
            os.makedirs("logs", exist_ok=True)
            _logger = logging.getLogger("defects4j")
            _logger.setLevel(logging.DEBUG)
            _logger.propagate = False
            if not _logger.handlers:
                file_handler = logging.FileHandler("logs/defects4j.log", encoding="utf-8")
                formatter = logging.Formatter('%(asctime)s - %(levelname)s - %(message)s')
                file_handler.setFormatter(formatter)
                _logger.addHandler(file_handler)
    return _logger

class Defects4JManager:
    def __init__(self, d4j_bin: str = "defects4j", java_home: str | None = None):
        self.d4j_bin = d4j_bin
        self.java_home = java_home

    def _get_env(self) -> dict:
        env = os.environ.copy()
        if self.java_home:
            java_home_abs = os.path.abspath(self.java_home)
            env["JAVA_HOME"] = java_home_abs
            path = env.get("PATH", "")
            java_bin = os.path.join(java_home_abs, "bin")
            if path:
                env["PATH"] = f"{java_bin}{os.pathsep}{path}"
            else:
                env["PATH"] = java_bin
        return env

    def checkout(self, project: str, version: str, workspace_path: str):
        if os.path.exists(workspace_path):
            try:
                shutil.rmtree(workspace_path)
            except Exception:
                pass

        cmd = [self.d4j_bin, "checkout", "-p", project, "-v", f"{version}f", "-w", workspace_path]
        logger = _get_logger()
        logger.info(f"Running command: {' '.join(cmd)}")
        res = subprocess.run(cmd, env=self._get_env(), capture_output=True)
        logger.debug(f"Command stdout: {res.stdout.decode('utf-8', errors='replace')}")
        logger.debug(f"Command stderr: {res.stderr.decode('utf-8', errors='replace')}")
        if res.returncode != 0:
            raise RuntimeError(f"defects4j checkout failed with exit code {res.returncode}")

    def compile(self, workspace_path: str) -> bool:
        cmd = [self.d4j_bin, "compile"]
        logger = _get_logger()
        logger.info(f"Running command: {' '.join(cmd)} in {workspace_path}")
        res = subprocess.run(cmd, cwd=workspace_path, env=self._get_env(), capture_output=True)
        logger.debug(f"Command stdout: {res.stdout.decode('utf-8', errors='replace')}")
        logger.debug(f"Command stderr: {res.stderr.decode('utf-8', errors='replace')}")
        return res.returncode == 0

    def test(self, workspace_path: str, timeout: int = 180) -> str:
        cmd = [self.d4j_bin, "test"]
        env = self._get_env()
        logger = _get_logger()
        logger.info(f"Running command: {' '.join(cmd)} in {workspace_path}")

        proc = subprocess.Popen(
            cmd,
            cwd=workspace_path,
            env=env,
            start_new_session=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE
        )

        try:
            stdout_bytes, stderr_bytes = proc.communicate(timeout=timeout)
            stdout = stdout_bytes.decode('utf-8', errors='replace')
            stderr = stderr_bytes.decode('utf-8', errors='replace')
            logger.debug(f"Command stdout: {stdout}")
            logger.debug(f"Command stderr: {stderr}")
        except subprocess.TimeoutExpired as e:
            try:
                os.killpg(os.getpgid(proc.pid), signal.SIGKILL)
            except OSError:
                pass
            proc.wait()
            stdout = e.stdout.decode('utf-8', errors='replace') if e.stdout else ""
            stderr = e.stderr.decode('utf-8', errors='replace') if e.stderr else ""
            logger.warning(f"Command timed out after {timeout} seconds.")
            logger.debug(f"Command stdout (partial): {stdout}")
            logger.debug(f"Command stderr (partial): {stderr}")
            return "FALHA_TESTES_TIMEOUT"

        if proc.returncode == 0:
            return "PASS"
        else:
            return "FALHA_TESTES_COMPORTAMENTO"

    def export_classes_dir(self, workspace_path: str) -> str:
        cmd = [self.d4j_bin, "export", "-p", "dir.bin.classes"]
        logger = _get_logger()
        logger.info(f"Running command: {' '.join(cmd)} in {workspace_path}")
        try:
            res = subprocess.run(cmd, cwd=workspace_path, env=self._get_env(), capture_output=True)
            stdout = res.stdout.decode('utf-8', errors='replace')
            stderr = res.stderr.decode('utf-8', errors='replace')
            logger.debug(f"Command stdout: {stdout}")
            logger.debug(f"Command stderr: {stderr}")
            if res.returncode == 0:
                return stdout.strip()
        except Exception as e:
            logger.error(f"Error executing export: {e}")
        return "target/classes"

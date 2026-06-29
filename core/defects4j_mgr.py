from __future__ import annotations

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
    def __init__(self, d4j_bin: str = "defects4j", java_home: str | None = None, perl5lib: str | None = None):
        self.d4j_bin = d4j_bin
        self.java_home = java_home
        self.perl5lib = perl5lib

    def _get_env(self) -> dict:
        env = os.environ.copy()

        # Optionally prepend a local perl5 lib (where defects4j's Perl deps live).
        # Configured per-machine via settings ("perl5lib"); no hardcoded path so
        # it stays portable across machines.
        if self.perl5lib:
            existing = env.get("PERL5LIB", "")
            env["PERL5LIB"] = f"{self.perl5lib}{os.pathsep}{existing}" if existing else self.perl5lib

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
                for root, dirs, files in os.walk(workspace_path):
                    for d in dirs:
                        try: os.chmod(os.path.join(root, d), 0o777)
                        except: pass
                    for f in files:
                        try: os.chmod(os.path.join(root, f), 0o777)
                        except: pass
                shutil.rmtree(workspace_path)
            except Exception:
                if "defects4j.bat" in self.d4j_bin or "defects4j.sh" in self.d4j_bin:
                    try:
                        proj_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
                        rel = os.path.relpath(os.path.abspath(workspace_path), proj_root)
                        container_path = "/app/" + rel.replace("\\", "/")
                        subprocess.run(["docker", "exec", "-u", "root", "defects4j-container", "rm", "-rf", container_path], capture_output=True)
                    except Exception:
                        pass

        cmd = [self.d4j_bin, "checkout", "-p", project, "-v", f"{version}f", "-w", workspace_path]
        logger = _get_logger()
        logger.info(f"Running command: {' '.join(cmd)}")
        res = subprocess.run(cmd, env=self._get_env(), capture_output=True)
        logger.debug(f"Command stdout: {res.stdout.decode('utf-8', errors='replace')}")
        logger.debug(f"Command stderr: {res.stderr.decode('utf-8', errors='replace')}")
        if res.returncode != 0:
            git_dir = os.path.join(workspace_path, ".git")
            if os.path.exists(git_dir):
                logger.warning("Checkout returned non-zero. Attempting recovery via manual git clean and checkout.")
                try:
                    subprocess.run(["git", "clean", "-fdx"], cwd=workspace_path, capture_output=True)
                    target_branch = f"D4J_{project}_{version}_FIXED_VERSION"
                    cres = subprocess.run(["git", "checkout", "-f", target_branch], cwd=workspace_path, capture_output=True)
                    if cres.returncode == 0:
                        logger.info(f"Successfully recovered checkout for {project} {version}")
                        return
                except Exception as e:
                    logger.error(f"Fallback checkout failed: {e}")
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
                if hasattr(os, "killpg") and hasattr(os, "getpgid"):
                    os.killpg(os.getpgid(proc.pid), signal.SIGKILL)
                else:
                    proc.kill()
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

    def export_source_dir(self, workspace_path: str) -> str:
        cmd = [self.d4j_bin, "export", "-p", "dir.src.classes"]
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
        return "src/main/java"

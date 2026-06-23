import os
import signal
import subprocess
import shutil
import pytest
from unittest.mock import MagicMock, patch

from core.defects4j_mgr import Defects4JManager

def test_init_default():
    mgr = Defects4JManager()
    assert mgr.d4j_bin == "defects4j"
    assert mgr.java_home is None

def test_init_custom():
    mgr = Defects4JManager(d4j_bin="/usr/local/bin/defects4j", java_home="/path/to/java")
    assert mgr.d4j_bin == "/usr/local/bin/defects4j"
    assert mgr.java_home == "/path/to/java"

def test_get_env_no_java_home(monkeypatch):
    monkeypatch.delenv("JAVA_HOME", raising=False)
    monkeypatch.setenv("PATH", f"/usr/bin{os.pathsep}/bin")
    mgr = Defects4JManager()
    env = mgr._get_env()
    assert env.get("PATH") == f"/usr/bin{os.pathsep}/bin"
    assert "JAVA_HOME" not in env

def test_get_env_with_java_home(monkeypatch):
    monkeypatch.setenv("PATH", f"/usr/bin{os.pathsep}/bin")
    mgr = Defects4JManager(java_home="/path/to/java")
    env = mgr._get_env()
    assert env.get("JAVA_HOME") == "/path/to/java"
    assert env.get("PATH") == f"/path/to/java/bin{os.pathsep}/usr/bin{os.pathsep}/bin"

def test_get_env_relative_java_home(monkeypatch):
    monkeypatch.setenv("PATH", f"/usr/bin{os.pathsep}/bin")
    mgr = Defects4JManager(java_home="my_java")
    env = mgr._get_env()
    abs_java_home = os.path.abspath("my_java")
    assert env.get("JAVA_HOME") == abs_java_home
    assert env.get("PATH") == f"{abs_java_home}/bin{os.pathsep}/usr/bin{os.pathsep}/bin"

def test_get_env_with_java_home_empty_path(monkeypatch):
    monkeypatch.delenv("PATH", raising=False)
    mgr = Defects4JManager(java_home="/path/to/java")
    env = mgr._get_env()
    assert env.get("JAVA_HOME") == "/path/to/java"
    assert env.get("PATH") == "/path/to/java/bin"

@patch("core.defects4j_mgr._get_logger")
@patch("shutil.rmtree")
@patch("os.path.exists")
@patch("subprocess.run")
def test_checkout_success(mock_run, mock_exists, mock_rmtree, mock_get_logger):
    mock_exists.return_value = True
    mock_run.return_value = MagicMock(returncode=0, stdout=b"out", stderr=b"err")
    mock_logger = MagicMock()
    mock_get_logger.return_value = mock_logger

    mgr = Defects4JManager()
    mgr.checkout("Lang", "1", "/workspace")

    mock_exists.assert_called_once_with("/workspace")
    mock_rmtree.assert_called_once_with("/workspace")
    mock_run.assert_called_once()
    args, kwargs = mock_run.call_args
    assert args[0] == ["defects4j", "checkout", "-p", "Lang", "-v", "1f", "-w", "/workspace"]
    assert "env" in kwargs
    # check quiet output / capture_output
    assert kwargs.get("capture_output") is True
    # check logger
    mock_logger.info.assert_called_once()
    mock_logger.debug.assert_any_call("Command stdout: out")
    mock_logger.debug.assert_any_call("Command stderr: err")

@patch("core.defects4j_mgr._get_logger")
@patch("shutil.rmtree")
@patch("os.path.exists")
@patch("subprocess.run")
def test_checkout_rmtree_exception(mock_run, mock_exists, mock_rmtree, mock_get_logger):
    mock_exists.return_value = True
    mock_rmtree.side_effect = OSError("permission denied")
    mock_run.return_value = MagicMock(returncode=0, stdout=b"", stderr=b"")
    mock_get_logger.return_value = MagicMock()

    mgr = Defects4JManager()
    # Should not raise exception because shutil.rmtree is wrapped in a try-except
    mgr.checkout("Lang", "1", "/workspace")
    mock_rmtree.assert_called_once_with("/workspace")
    mock_run.assert_called_once()

@patch("shutil.rmtree")
@patch("os.path.exists")
@patch("subprocess.run")
def test_checkout_failure(mock_run, mock_exists, mock_rmtree):
    mock_exists.return_value = False
    mock_run.return_value = MagicMock(returncode=1, stdout=b"", stderr=b"")

    mgr = Defects4JManager()
    with pytest.raises(RuntimeError) as excinfo:
        mgr.checkout("Lang", "1", "/workspace")
    
    assert "checkout failed" in str(excinfo.value).lower()
    mock_rmtree.assert_not_called()

@patch("core.defects4j_mgr._get_logger")
@patch("subprocess.run")
def test_compile_success(mock_run, mock_get_logger):
    mock_run.return_value = MagicMock(returncode=0, stdout=b"compiled", stderr=b"")
    mock_logger = MagicMock()
    mock_get_logger.return_value = mock_logger

    mgr = Defects4JManager()
    result = mgr.compile("/workspace")

    assert result is True
    mock_run.assert_called_once()
    args, kwargs = mock_run.call_args
    assert args[0] == ["defects4j", "compile"]
    assert kwargs.get("cwd") == "/workspace"
    assert kwargs.get("capture_output") is True
    mock_logger.info.assert_called_once()
    mock_logger.debug.assert_any_call("Command stdout: compiled")

@patch("subprocess.run")
def test_compile_failure(mock_run):
    mock_run.return_value = MagicMock(returncode=1, stdout=b"", stderr=b"")

    mgr = Defects4JManager()
    result = mgr.compile("/workspace")

    assert result is False

@patch("core.defects4j_mgr._get_logger")
@patch("subprocess.Popen")
def test_test_pass(mock_popen, mock_get_logger):
    mock_proc = MagicMock()
    mock_proc.communicate.return_value = (b"PASS", b"")
    mock_proc.returncode = 0
    mock_popen.return_value = mock_proc
    mock_logger = MagicMock()
    mock_get_logger.return_value = mock_logger

    mgr = Defects4JManager()
    result = mgr.test("/workspace")

    assert result == "PASS"
    mock_popen.assert_called_once()
    args, kwargs = mock_popen.call_args
    assert args[0] == ["defects4j", "test"]
    assert kwargs.get("cwd") == "/workspace"
    assert kwargs.get("start_new_session") is True
    assert "preexec_fn" not in kwargs
    mock_logger.info.assert_called_once()
    mock_logger.debug.assert_any_call("Command stdout: PASS")

@patch("core.defects4j_mgr._get_logger")
@patch("subprocess.Popen")
def test_test_fail_comportamento(mock_popen, mock_get_logger):
    mock_proc = MagicMock()
    mock_proc.communicate.return_value = (b"FAIL", b"")
    mock_proc.returncode = 1
    mock_popen.return_value = mock_proc
    mock_get_logger.return_value = MagicMock()

    mgr = Defects4JManager()
    result = mgr.test("/workspace")

    assert result == "FALHA_TESTES_COMPORTAMENTO"

@patch("core.defects4j_mgr._get_logger")
@patch("os.killpg")
@patch("os.getpgid")
@patch("subprocess.Popen")
def test_test_timeout(mock_popen, mock_getpgid, mock_killpg, mock_get_logger):
    mock_proc = MagicMock()
    mock_proc.pid = 9999
    # simulate timeout on communicate
    mock_proc.communicate.side_effect = subprocess.TimeoutExpired(cmd="defects4j test", timeout=180, output=b"partial_out", stderr=b"partial_err")
    mock_popen.return_value = mock_proc
    mock_getpgid.return_value = 8888
    mock_logger = MagicMock()
    mock_get_logger.return_value = mock_logger

    mgr = Defects4JManager()
    result = mgr.test("/workspace", timeout=180)

    assert result == "FALHA_TESTES_TIMEOUT"
    mock_getpgid.assert_called_once_with(9999)
    mock_killpg.assert_called_once_with(8888, signal.SIGKILL)
    # verify zombie process cleanup: proc.wait should be called to reap zombie process
    mock_proc.wait.assert_called_once()
    # verify logging on timeout
    mock_logger.warning.assert_called_once()
    mock_logger.debug.assert_any_call("Command stdout (partial): partial_out")
    mock_logger.debug.assert_any_call("Command stderr (partial): partial_err")

@patch("core.defects4j_mgr._get_logger")
@patch("subprocess.run")
def test_export_classes_dir_success(mock_run, mock_get_logger):
    mock_run.return_value = MagicMock(returncode=0, stdout=b"  build/classes  \n", stderr=b"")
    mock_logger = MagicMock()
    mock_get_logger.return_value = mock_logger

    mgr = Defects4JManager()
    result = mgr.export_classes_dir("/workspace")

    assert result == "build/classes"
    mock_run.assert_called_once()
    args, kwargs = mock_run.call_args
    assert args[0] == ["defects4j", "export", "-p", "dir.bin.classes"]
    assert kwargs.get("cwd") == "/workspace"
    assert kwargs.get("capture_output") is True
    mock_logger.info.assert_called_once()
    mock_logger.debug.assert_any_call("Command stdout:   build/classes  \n")

@patch("subprocess.run")
def test_export_classes_dir_failure(mock_run):
    # If exit code is non-zero, it should return fallback "target/classes"
    mock_run.return_value = MagicMock(returncode=1, stdout=b"", stderr=b"error")

    mgr = Defects4JManager()
    result = mgr.export_classes_dir("/workspace")

    assert result == "target/classes"

@patch("subprocess.run")
def test_export_classes_dir_exception(mock_run):
    # If run raises an exception, it should return fallback "target/classes"
    mock_run.side_effect = OSError("command not found")

    mgr = Defects4JManager()
    result = mgr.export_classes_dir("/workspace")

    assert result == "target/classes"

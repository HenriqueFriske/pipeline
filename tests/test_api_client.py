import pytest
import httpx
from unittest.mock import patch, Mock, MagicMock
from google.genai import errors, types

from core.api_client import (
    generate_refactoring,
    GeminiAPIError,
    APIRateLimitError,
    APITimeoutError,
    RefactorResult,
    MODEL,
)


def _mock_client_returning(parsed):
    """Fake genai.Client whose generate_content returns a response exposing .parsed."""
    client = MagicMock()
    response = Mock()
    response.parsed = parsed
    client.models.generate_content.return_value = response
    return client


def _mock_client_raising(exc):
    client = MagicMock()
    client.models.generate_content.side_effect = exc
    return client


def test_empty_api_key_raises():
    with pytest.raises(RuntimeError) as exc:
        generate_refactoring("persona", "instr", "code", api_key="")
    assert "API_KEY" in str(exc.value)


def test_returns_refactored_code_from_structured_response():
    client = _mock_client_returning(RefactorResult(refactored_code="public class Refactored {}"))
    with patch("core.api_client.genai.Client", return_value=client):
        result = generate_refactoring(
            "You are a dev.", "Refactor this", "public class Original {}", api_key="KEY"
        )
    assert result == "public class Refactored {}"


def test_passes_model_and_thinking_config():
    client = _mock_client_returning(RefactorResult(refactored_code="X {}"))
    with patch("core.api_client.genai.Client", return_value=client) as mock_client_cls:
        generate_refactoring(
            "You are a dev.", "Refactor this", "class O {}", api_key="KEY"
        )

    mock_client_cls.assert_called_once_with(api_key="KEY")
    client.models.generate_content.assert_called_once()
    _, kwargs = client.models.generate_content.call_args
    assert kwargs["model"] == MODEL == "gemini-3.5-flash"
    config = kwargs["config"]
    # Gemini 3.x: sampling params omitted; reasoning controlled via thinking_level.
    assert config.temperature is None
    assert config.thinking_config.thinking_level == types.ThinkingLevel.HIGH
    assert config.response_mime_type == "application/json"
    assert config.response_schema is RefactorResult
    assert config.system_instruction == "You are a dev."


def test_contents_combine_instruction_and_snippet():
    client = _mock_client_returning(RefactorResult(refactored_code="X {}"))
    with patch("core.api_client.genai.Client", return_value=client):
        generate_refactoring(
            "persona", "Refactor this code", "public class Original {}", api_key="KEY"
        )
    _, kwargs = client.models.generate_content.call_args
    assert kwargs["contents"] == "Refactor this code\n\npublic class Original {}"


def test_no_system_instruction_when_preamble_empty():
    client = _mock_client_returning(RefactorResult(refactored_code="X {}"))
    with patch("core.api_client.genai.Client", return_value=client):
        generate_refactoring("", "Refactor this", "class O {}", api_key="KEY")
    _, kwargs = client.models.generate_content.call_args
    assert kwargs["config"].system_instruction is None


def test_empty_parsed_raises_gemini_error():
    client = _mock_client_returning(None)
    with patch("core.api_client.genai.Client", return_value=client):
        with pytest.raises(GeminiAPIError) as exc:
            generate_refactoring("persona", "instr", "code", api_key="KEY")
    assert "parse" in str(exc.value).lower()
    client.models.generate_content.assert_called_once()


def test_empty_refactored_code_raises_gemini_error():
    client = _mock_client_returning(RefactorResult(refactored_code=""))
    with patch("core.api_client.genai.Client", return_value=client):
        with pytest.raises(GeminiAPIError):
            generate_refactoring("persona", "instr", "code", api_key="KEY")


def test_terminal_client_error_400():
    err = errors.ClientError(400, {"error": {"message": "bad request", "code": 400}})
    client = _mock_client_raising(err)
    with patch("core.api_client.genai.Client", return_value=client):
        with pytest.raises(GeminiAPIError) as exc:
            generate_refactoring("persona", "instr", "code", api_key="KEY")
    assert "terminal status code 400" in str(exc.value)
    client.models.generate_content.assert_called_once()


def test_retry_on_500_then_raises():
    err = errors.ServerError(500, {"error": {"message": "boom", "code": 500}})
    client = _mock_client_raising(err)
    with patch("core.api_client.genai.Client", return_value=client), \
         patch("core.api_client.time.sleep") as mock_sleep:
        with pytest.raises(GeminiAPIError) as exc:
            generate_refactoring("persona", "instr", "code", api_key="KEY")
    assert "status code 500 after 5 attempts" in str(exc.value)
    assert client.models.generate_content.call_count == 5
    assert mock_sleep.call_count == 4
    for idx, c in enumerate(mock_sleep.call_args_list):
        attempt = idx + 1
        base = 2 ** (attempt - 1)
        sleep_time = c[0][0]
        assert base <= sleep_time <= base + 1.0


def test_retry_on_429_then_raises():
    err = errors.ClientError(429, {"error": {"message": "rate", "code": 429}})
    client = _mock_client_raising(err)
    with patch("core.api_client.genai.Client", return_value=client), \
         patch("core.api_client.time.sleep") as mock_sleep:
        with pytest.raises(APIRateLimitError) as exc:
            generate_refactoring("persona", "instr", "code", api_key="KEY")
    assert "status code 429 after 5 attempts" in str(exc.value)
    assert client.models.generate_content.call_count == 5
    assert mock_sleep.call_count == 4
    for idx, c in enumerate(mock_sleep.call_args_list):
        attempt = idx + 1
        base = 2 ** (attempt - 1)
        sleep_time = c[0][0]
        assert base + 30.0 <= sleep_time <= base + 31.0


def test_retry_on_network_error_then_raises():
    client = _mock_client_raising(httpx.ConnectError("connection failed"))
    with patch("core.api_client.genai.Client", return_value=client), \
         patch("core.api_client.time.sleep") as mock_sleep:
        with pytest.raises(APITimeoutError) as exc:
            generate_refactoring("persona", "instr", "code", api_key="KEY")
    assert "network errors after 5 attempts" in str(exc.value)
    assert client.models.generate_content.call_count == 5
    assert mock_sleep.call_count == 4


def test_retry_then_success():
    err = errors.ServerError(503, {"error": {"message": "unavailable", "code": 503}})
    good = Mock()
    good.parsed = RefactorResult(refactored_code="public class Ok {}")
    client = MagicMock()
    client.models.generate_content.side_effect = [err, good]
    with patch("core.api_client.genai.Client", return_value=client), \
         patch("core.api_client.time.sleep") as mock_sleep:
        result = generate_refactoring("persona", "instr", "code", api_key="KEY")
    assert result == "public class Ok {}"
    assert client.models.generate_content.call_count == 2
    assert mock_sleep.call_count == 1


def test_logging_lazy_initialization():
    import core.api_client
    core.api_client._logger = None  # reset global state
    client = _mock_client_raising(httpx.ConnectError("timeout"))
    with patch("core.api_client.os.makedirs") as mock_makedirs, \
         patch("core.api_client.genai.Client", return_value=client), \
         patch("core.api_client.time.sleep"):
        with pytest.raises(Exception):
            generate_refactoring("", "instr", "code", "key")
    mock_makedirs.assert_called_with("logs", exist_ok=True)

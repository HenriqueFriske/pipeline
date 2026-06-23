import pytest
import requests
from unittest.mock import patch, Mock
from core.api_client import generate_refactoring

def test_generate_refactoring_empty_api_key():
    # Calling generate_refactoring with empty api_key raises a RuntimeError with "API_KEY" in the message.
    with pytest.raises(RuntimeError) as exc_info:
        generate_refactoring(
            persona_preamble="You are a developer.",
            base_instruction="Refactor this",
            code_snippet="public class Main {}",
            api_key=""
        )
    assert "API_KEY" in str(exc_info.value)

def test_generate_refactoring_payload_with_preamble():
    mock_response = Mock()
    mock_response.status_code = 200
    mock_response.json.return_value = {
        "candidates": [
            {
                "content": {
                    "parts": [
                        {"text": "public class Refactored {}"}
                    ]
                }
            }
        ]
    }
    
    with patch("requests.post", return_value=mock_response) as mock_post:
        result = generate_refactoring(
            persona_preamble="You are a developer.",
            base_instruction="Refactor this code",
            code_snippet="public class Original {}",
            api_key="TEST_API_KEY",
            temperature=0.2
        )
        
        assert result == "public class Refactored {}"
        
        mock_post.assert_called_once()
        args, kwargs = mock_post.call_args
        url = args[0]
        assert "generativelanguage.googleapis.com" in url
        assert "key=TEST_API_KEY" in url
        
        headers = kwargs.get("headers")
        assert headers == {"Content-Type": "application/json"}
        
        json_data = kwargs.get("json")
        assert json_data["contents"] == [
            {"parts": [{"text": "Refactor this code\n\npublic class Original {}"}]}
        ]
        assert json_data["systemInstruction"] == {
            "parts": [{"text": "You are a developer."}]
        }
        assert json_data["generationConfig"] == {"temperature": 0.2}

def test_generate_refactoring_payload_without_preamble():
    mock_response = Mock()
    mock_response.status_code = 200
    mock_response.json.return_value = {
        "candidates": [
            {
                "content": {
                    "parts": [
                        {"text": "public class Refactored {}"}
                    ]
                }
            }
        ]
    }
    
    with patch("requests.post", return_value=mock_response) as mock_post:
        result = generate_refactoring(
            persona_preamble="",
            base_instruction="Refactor this code",
            code_snippet="public class Original {}",
            api_key="TEST_API_KEY",
            temperature=0.2
        )
        
        assert result == "public class Refactored {}"
        
        mock_post.assert_called_once()
        _, kwargs = mock_post.call_args
        json_data = kwargs.get("json")
        assert "systemInstruction" not in json_data

def test_generate_refactoring_terminal_error():
    mock_response = Mock()
    mock_response.status_code = 400
    mock_response.text = "Bad Request"
    
    with patch("requests.post", return_value=mock_response) as mock_post:
        with pytest.raises(RuntimeError) as exc_info:
            generate_refactoring(
                persona_preamble="You are a developer.",
                base_instruction="Refactor this",
                code_snippet="public class Main {}",
                api_key="TEST_API_KEY"
            )
        assert "terminal status code 400" in str(exc_info.value)
        mock_post.assert_called_once()

def test_generate_refactoring_invalid_json():
    mock_response = Mock()
    mock_response.status_code = 200
    mock_response.json.return_value = {"bad_key": "bad_value"}
    
    with patch("requests.post", return_value=mock_response) as mock_post:
        with pytest.raises(RuntimeError) as exc_info:
            generate_refactoring(
                persona_preamble="You are a developer.",
                base_instruction="Refactor this",
                code_snippet="public class Main {}",
                api_key="TEST_API_KEY"
            )
        assert "Failed to parse response JSON" in str(exc_info.value)
        mock_post.assert_called_once()

def test_generate_refactoring_retry_500():
    mock_response = Mock()
    mock_response.status_code = 500
    mock_response.text = "Internal Server Error"
    
    with patch("requests.post", return_value=mock_response) as mock_post, \
         patch("time.sleep") as mock_sleep:
        with pytest.raises(RuntimeError) as exc_info:
            generate_refactoring(
                persona_preamble="You are a developer.",
                base_instruction="Refactor this",
                code_snippet="public class Main {}",
                api_key="TEST_API_KEY"
            )
        assert "status code 500 after 5 attempts" in str(exc_info.value)
        assert mock_post.call_count == 5
        assert mock_sleep.call_count == 4
        
        # Verify backoff values
        calls = [call[0][0] for call in mock_sleep.call_args_list]
        for idx, sleep_time in enumerate(calls):
            attempt = idx + 1
            base_backoff = 2 ** (attempt - 1)
            # Sleep time should be base_backoff + jitter (where 0 <= jitter <= 1)
            assert base_backoff <= sleep_time <= base_backoff + 1.0

def test_generate_refactoring_retry_429():
    mock_response = Mock()
    mock_response.status_code = 429
    mock_response.text = "Rate Limit Exceeded"
    
    with patch("requests.post", return_value=mock_response) as mock_post, \
         patch("time.sleep") as mock_sleep:
        with pytest.raises(RuntimeError) as exc_info:
            generate_refactoring(
                persona_preamble="You are a developer.",
                base_instruction="Refactor this",
                code_snippet="public class Main {}",
                api_key="TEST_API_KEY"
            )
        assert "status code 429 after 5 attempts" in str(exc_info.value)
        assert mock_post.call_count == 5
        assert mock_sleep.call_count == 4
        
        # Verify backoff values with additional 30 seconds
        calls = [call[0][0] for call in mock_sleep.call_args_list]
        for idx, sleep_time in enumerate(calls):
            attempt = idx + 1
            base_backoff = 2 ** (attempt - 1)
            expected_min = base_backoff + 30.0
            expected_max = base_backoff + 31.0
            assert expected_min <= sleep_time <= expected_max

def test_generate_refactoring_retry_network_error():
    with patch("requests.post", side_effect=requests.RequestException("Connection error")) as mock_post, \
         patch("time.sleep") as mock_sleep:
        with pytest.raises(RuntimeError) as exc_info:
            generate_refactoring(
                persona_preamble="You are a developer.",
                base_instruction="Refactor this",
                code_snippet="public class Main {}",
                api_key="TEST_API_KEY"
            )
        assert "network errors after 5 attempts" in str(exc_info.value)
        assert mock_post.call_count == 5
        assert mock_sleep.call_count == 4
        
        # Verify backoff values
        calls = [call[0][0] for call in mock_sleep.call_args_list]
        for idx, sleep_time in enumerate(calls):
            attempt = idx + 1
            base_backoff = 2 ** (attempt - 1)
            assert base_backoff <= sleep_time <= base_backoff + 1.0

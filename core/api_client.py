import os
import time
import random
import logging
import threading

import httpx
from google import genai
from google.genai import types, errors
from pydantic import BaseModel

# Gemini 3.x model. Sampling params (temperature/top_p/top_k) are discouraged on
# 3.x; reasoning depth is controlled via thinking_level instead.
MODEL = "gemini-2.5-flash"
THINKING_LEVEL = "high"


class GeminiAPIError(Exception):
    """Base exception for Gemini API errors."""
    pass


class APIRateLimitError(GeminiAPIError):
    """Exception raised when the API rate limit is exceeded."""
    pass


class APITimeoutError(GeminiAPIError):
    """Exception raised when a network timeout or related error occurs."""
    pass


class RefactorResult(BaseModel):
    """Schema enforced on the model's structured JSON output."""
    refactored_code: str


# Lazy logger initialization
_logger = None
_logger_lock = threading.Lock()


def _get_logger() -> logging.Logger:
    global _logger
    with _logger_lock:
        if _logger is None:
            os.makedirs("logs", exist_ok=True)
            _logger = logging.getLogger("api_client")
            _logger.setLevel(logging.DEBUG)
            _logger.propagate = False
            if not _logger.handlers:
                file_handler = logging.FileHandler("logs/api.log", encoding="utf-8")
                formatter = logging.Formatter('%(asctime)s - %(levelname)s - %(message)s')
                file_handler.setFormatter(formatter)
                _logger.addHandler(file_handler)
    return _logger


def generate_refactoring(
    persona_preamble: str,
    base_instruction: str,
    code_snippet: str,
    api_key: str,
) -> str:
    """
    Calls the Gemini API to refactor a Java snippet and returns the clean code.

    Uses structured output (response_schema=RefactorResult) so the response is
    guaranteed JSON, removing the need for fragile markdown/brace parsing.
    Reasoning depth is set via thinking_level (no sampling params on Gemini 3.x).

    Raises:
        RuntimeError: api_key is empty.
        APIRateLimitError: 429 after max retries.
        GeminiAPIError: terminal 4xx, exhausted 5xx, or empty/invalid output.
        APITimeoutError: network errors after max retries.
    """
    if not api_key:
        raise RuntimeError("API_KEY must be provided and cannot be empty.")

    client = genai.Client(api_key=api_key)
    config = types.GenerateContentConfig(
        response_mime_type="application/json",
        response_schema=RefactorResult,
        system_instruction=persona_preamble or None,
    )
    contents = f"{base_instruction}\n\n{code_snippet}"

    max_attempts = 5
    for attempt in range(1, max_attempts + 1):
        is_rate_limit = False
        try:
            response = client.models.generate_content(
                model=MODEL,
                contents=contents,
                config=config,
            )

            result = response.parsed
            if result is None or not getattr(result, "refactored_code", None):
                _get_logger().error(
                    f"Attempt {attempt}: Empty or invalid structured response. parsed={result!r}"
                )
                raise GeminiAPIError("Failed to parse structured response from Gemini API")
            return result.refactored_code

        except errors.APIError as e:
            code = getattr(e, "code", None)
            if code == 429:
                is_rate_limit = True
                _get_logger().warning(f"Attempt {attempt}: rate limit (429). {e}")
                if attempt == max_attempts:
                    raise APIRateLimitError(
                        f"API request failed with status code 429 after {max_attempts} attempts."
                    ) from e
            elif code is not None and 500 <= code < 600:
                _get_logger().warning(f"Attempt {attempt}: server error {code}. {e}")
                if attempt == max_attempts:
                    raise GeminiAPIError(
                        f"API request failed with status code {code} after {max_attempts} attempts."
                    ) from e
            else:
                _get_logger().error(f"Attempt {attempt}: terminal error {code}. {e}")
                raise GeminiAPIError(
                    f"API request failed with terminal status code {code}."
                ) from e

        except httpx.HTTPError as e:
            _get_logger().warning(f"Attempt {attempt}: Network error occurred. Error: {e}")
            if attempt == max_attempts:
                raise APITimeoutError(
                    f"API request failed due to network errors after {max_attempts} attempts."
                ) from e

        # Calculate backoff: 2 ** (attempt - 1) + jitter, with a rate-limit penalty.
        sleep_time = (2 ** (attempt - 1)) + random.uniform(0.0, 1.0)
        if is_rate_limit:
            sleep_time += 30.0

        _get_logger().info(f"Sleeping for {sleep_time:.2f} seconds before attempt {attempt + 1}...")
        time.sleep(sleep_time)

    raise GeminiAPIError("API request failed: reached unreachable code block in retry loop.")

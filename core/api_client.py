import os
import time
import random
import logging
import requests

# Set up logging to logs/api.log silently
os.makedirs("logs", exist_ok=True)
logger = logging.getLogger("api_client")
logger.setLevel(logging.DEBUG)
logger.propagate = False
if not logger.handlers:
    file_handler = logging.FileHandler("logs/api.log", encoding="utf-8")
    formatter = logging.Formatter('%(asctime)s - %(levelname)s - %(message)s')
    file_handler.setFormatter(formatter)
    logger.addHandler(file_handler)

def generate_refactoring(
    persona_preamble: str,
    base_instruction: str,
    code_snippet: str,
    api_key: str,
    temperature: float = 0.2
) -> str:
    if not api_key:
        raise RuntimeError("API_KEY must be provided and cannot be empty.")
    
    url = f"https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-preview-09-2025:generateContent?key={api_key}"
    headers = {"Content-Type": "application/json"}
    
    # Construct payload
    payload = {
        "contents": [
            {
                "parts": [
                    {"text": f"{base_instruction}\n\n{code_snippet}"}
                ]
            }
        ],
        "generationConfig": {
            "temperature": temperature
        }
    }
    
    if persona_preamble:
        payload["systemInstruction"] = {
            "parts": [
                {"text": persona_preamble}
            ]
        }
        
    max_attempts = 5
    for attempt in range(1, max_attempts + 1):
        response = None
        try:
            response = requests.post(url, headers=headers, json=payload, timeout=60)
            
            if response.status_code == 200:
                response_data = response.json()
                try:
                    text = response_data['candidates'][0]['content']['parts'][0]['text']
                    return text
                except (KeyError, IndexError) as e:
                    logger.error(f"Attempt {attempt}: Failed to parse JSON response. Data: {response_data}. Error: {e}")
                    raise RuntimeError("Failed to parse response JSON from Gemini API") from e
            
            if response.status_code == 429 or 500 <= response.status_code < 600:
                logger.warning(f"Attempt {attempt}: Received status code {response.status_code}. Response: {response.text}")
                if attempt == max_attempts:
                    raise RuntimeError(f"API request failed with status code {response.status_code} after {max_attempts} attempts.")
            else:
                logger.error(f"Attempt {attempt}: Received terminal error status code {response.status_code}. Response: {response.text}")
                raise RuntimeError(f"API request failed with terminal status code {response.status_code}.")
                
        except requests.RequestException as e:
            logger.warning(f"Attempt {attempt}: Network error occurred. Error: {e}")
            if attempt == max_attempts:
                raise RuntimeError(f"API request failed due to network errors after {max_attempts} attempts.") from e
        
        # Calculate backoff: 2 ** (attempt - 1)
        base_backoff = 2 ** (attempt - 1)
        jitter = random.uniform(0.0, 1.0)
        sleep_time = base_backoff + jitter
        
        if response is not None and response.status_code == 429:
            sleep_time += 30.0
            
        logger.info(f"Sleeping for {sleep_time:.2f} seconds before attempt {attempt + 1}...")
        time.sleep(sleep_time)

    raise RuntimeError("API request failed: reached unreachable code block in retry loop.")

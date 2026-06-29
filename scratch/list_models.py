import os
import sys
from google import genai

def main():
    api_key = os.environ.get("GEMINI_API_KEY")
    if not api_key:
        if os.path.exists(".env"):
            with open(".env", "r") as f:
                for line in f:
                    if line.strip().startswith("GEMINI_API_KEY="):
                        api_key = line.strip().split("=", 1)[1].strip("'\" ")
                        break
    if not api_key:
        print("Error: GEMINI_API_KEY is missing.")
        sys.exit(1)
        
    client = genai.Client(api_key=api_key)
    
    print("Listing models:")
    for model in client.models.list():
        print(model.name, model.supported_actions)

if __name__ == "__main__":
    main()

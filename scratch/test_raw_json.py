import os
import sys
import json
from google import genai
from pydantic import BaseModel

class RefactorResult(BaseModel):
    refactored_code: str

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
    
    code = "public class Hello { public static void main(String[] args) { System.out.println(\"Hello\"); } }"
    
    response = client.models.generate_content(
        model="gemini-2.5-flash",
        contents=f"Refactor the following Java code to improve readability and maintainability. Include newlines and indentation in the refactored code:\n\n{code}",
        config={
            "response_mime_type": "application/json",
            "response_schema": RefactorResult
        }
    )
    
    print("--- RAW RESPONSE TEXT ---")
    print(response.text)
    print("-------------------------")
    
    result = response.parsed
    print("--- PARSED refactored_code ---")
    print(repr(result.refactored_code))
    print("------------------------------")

if __name__ == "__main__":
    main()

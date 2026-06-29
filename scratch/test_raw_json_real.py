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
    
    source_file = "temp_workspace/test_checkout/src/main/java/org/apache/commons/math3/linear/SingularValueDecomposition.java"
    with open(source_file, "r", encoding="utf-8") as f:
        code = f.read()
    
    response = client.models.generate_content(
        model="gemini-2.5-flash",
        contents=f"Refactor the following Java code to improve readability and maintainability. Include newlines and indentation in the refactored code:\n\n{code}",
        config={
            "response_mime_type": "application/json",
            "response_schema": RefactorResult
        }
    )
    
    print("--- RAW RESPONSE TEXT PREFIX ---")
    print(response.text[:1000])
    print("--------------------------------")
    
    result = response.parsed
    if result:
        print("Parsed refactored_code newline count:", result.refactored_code.count('\n'))
    else:
        print("Parsed result is None!")

if __name__ == "__main__":
    main()

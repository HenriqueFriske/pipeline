import os
import sys
import json
import subprocess
from core.api_client import generate_refactoring, RefactorResult

def main():
    api_key = os.environ.get("GEMINI_API_KEY")
    if not api_key:
        # Try loading from .env manually
        if os.path.exists(".env"):
            with open(".env", "r") as f:
                for line in f:
                    if line.strip().startswith("GEMINI_API_KEY="):
                        api_key = line.strip().split("=", 1)[1].strip("'\" ")
                        os.environ["GEMINI_API_KEY"] = api_key
                        break
        
    if not api_key:
        print("Error: GEMINI_API_KEY environment variable or .env entry is missing.")
        sys.exit(1)

    # 1. Load config
    settings_path = "config/settings.json"
    with open(settings_path, "r", encoding="utf-8") as f:
        settings = json.load(f)
    
    base_instruction = settings.get("base_instruction", "Refactor the following Java code:")
    base_instruction += "\nIMPORTANT: The refactored Java code must be properly formatted with standard indentation and newlines. Do not compress the code into a single line."
    
    # P-4 Persona from main.py
    persona_preamble = "You are a Senior Java Software Architect and Clean Code expert."

    # 2. Paths
    checkout_dir = "temp_workspace/test_checkout"
    file_rel_path = "src/main/java/org/apache/commons/math3/linear/SingularValueDecomposition.java"
    source_file = os.path.join(checkout_dir, file_rel_path)
    output_file = os.path.join(checkout_dir, "src/main/java/org/apache/commons/math3/linear/SingularValueDecomposition_refactored.java")

    if not os.path.exists(source_file):
        print(f"Error: Original source file not found at {source_file}. Run git checkout first.")
        sys.exit(1)

    print(f"Reading original file: {source_file}")
    with open(source_file, "r", encoding="utf-8") as f:
        original_code = f.read()

    print("Calling Gemini API to refactor (this may take up to a minute)...")
    try:
        refactored_code = generate_refactoring(
            persona_preamble=persona_preamble,
            base_instruction=base_instruction,
            code_snippet=original_code,
            api_key=api_key
        )
    except Exception as e:
        print(f"Gemini API Call failed: {e}")
        sys.exit(1)

    print(f"Writing refactored code to: {output_file}")
    os.makedirs(os.path.dirname(output_file), exist_ok=True)
    with open(output_file, "w", encoding="utf-8") as f:
        f.write(refactored_code)

    # 3. Compile
    print("Attempting to compile the refactored code using local javac...")
    bin_dir = os.path.join(checkout_dir, "bin")
    os.makedirs(bin_dir, exist_ok=True)
    
    compile_cmd = [
        "javac",
        "-sourcepath", os.path.join(checkout_dir, "src/main/java"),
        output_file,
        "-d", bin_dir
    ]
    
    print(f"Running: {' '.join(compile_cmd)}")
    res = subprocess.run(compile_cmd, capture_output=True, text=True)
    
    if res.returncode == 0:
        print("SUCCESS: Code compiled successfully!")
        if res.stderr:
            print("Compiler Warnings:")
            print(res.stderr)
    else:
        print("FAILURE: Compilation failed.")
        print("Compiler Errors:")
        print(res.stderr)
        sys.exit(1)

if __name__ == "__main__":
    main()

import os
import sys
import json
import subprocess
from google import genai

def extract_code(text):
    if "```java" in text:
        text = text.split("```java", 1)[1]
        text = text.split("```", 1)[0]
    elif "```" in text:
        text = text.split("```", 1)[1]
        text = text.split("```", 1)[0]
    return text.strip()

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
    
    # Paths
    checkout_dir = "temp_workspace/test_checkout"
    file_rel_path = "src/main/java/org/apache/commons/math3/geometry/euclidean/threed/SphericalCoordinates.java"
    source_file = os.path.join(checkout_dir, file_rel_path)
    output_file = source_file
    
    # 1. Read original file
    with open(source_file, "r", encoding="utf-8") as f:
        original_code = f.read()
        
    base_instruction = "Refactor the following Java code to improve readability and maintainability without changing its behavior. Return ONLY the refactored code inside a markdown java code block:"
    persona_preamble = "You are a Senior Java Software Architect and Clean Code expert."

    # 2. Call Gemini API
    print("Calling Gemini API in PLAIN TEXT mode...")
    try:
        response = client.models.generate_content(
            model="gemini-3.5-flash",
            contents=f"{base_instruction}\n\n{original_code}",
            config={
                "system_instruction": persona_preamble,
                "max_output_tokens": 16384
            }
        )
    except Exception as e:
        print(f"Gemini API Call failed: {e}")
        sys.exit(1)
        
    refactored_code = extract_code(response.text)
    print("Newlines in extracted code:", refactored_code.count('\n'))
    
    # Write directly to original file path to satisfy javac naming rules
    with open(output_file, "w", encoding="utf-8") as f:
        f.write(refactored_code)
        
    # 3. Attempt to compile
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
    
    # 4. Restore the original file using git checkout so the workspace is clean
    print("Restoring original file using git...")
    subprocess.run(["git", "-C", checkout_dir, "checkout", file_rel_path], capture_output=True)
    
    if res.returncode == 0:
        print("SUCCESS: Code compiled successfully!")
    else:
        print("FAILURE: Compilation failed.")
        print("Compiler Errors:")
        print(res.stderr)
        sys.exit(1)

if __name__ == "__main__":
    main()

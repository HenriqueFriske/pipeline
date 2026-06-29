import os
import sys
import json
import csv
import time
import subprocess
from google import genai
from google.genai import types
from pydantic import BaseModel, Field

# 1. Define Persona & Schema matching core/api_client.py
PERSONAS = {
    "P-1": "You are an experienced artisan baker specializing in sourdough breads and traditional pastries. You have no knowledge of software engineering, programming, or computer science.",
    "P-2": "",
    "P-3": "You are a developer.",
    "P-4": "You are a Senior Java Software Architect and Clean Code expert."
}

class RefactorResult(BaseModel):
    refactored_code: str = Field(description="The refactored Java code. MUST preserve newlines and standard indentation. Do NOT compress into a single line.")

def main():
    # Load API Key
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
    
    # Configurations
    limit = 30
    sleep_seconds = 5
    checkout_dir = "temp_workspace/test_checkout"
    results_csv = "scratch/candidates_compile_results.csv"
    raw_responses_dir = "scratch/raw_responses"
    refactored_outputs_dir = "scratch/refactored_outputs"
    
    os.makedirs(raw_responses_dir, exist_ok=True)
    os.makedirs(refactored_outputs_dir, exist_ok=True)
    
    # Load base instruction from settings
    with open("config/settings.json", "r", encoding="utf-8") as f:
        settings = json.load(f)
    base_instruction = settings.get("base_instruction", "Refactor the following Java code to improve readability and maintainability without changing its behavior:")
    
    # Use P-4 Persona by default (can be adjusted)
    persona_key = "P-4"
    persona_preamble = PERSONAS[persona_key]
    
    # Load candidates from subset
    with open("candidates_subset.json", "r", encoding="utf-8") as f:
        data = json.load(f)
        
    candidates = []
    for category, items in data.items():
        for item in items:
            item["categoria"] = category
            candidates.append(item)
    model = "gemini-3.5-flash" 
    print(f"Total candidates found: {len(candidates)}")
    print(f"Testing a limit of {limit} candidates with model {model} and persona {persona_key}.")
    
    # Setup CSV output
    file_exists = os.path.exists(results_csv)
    csv_file = open(results_csv, "a", newline="", encoding="utf-8")
    writer = csv.writer(csv_file)
    if not file_exists:
        writer.writerow(["candidate_id", "refactoring_type", "file_path", "status", "error_details", "prompt_tokens", "candidates_tokens", "total_tokens"])
        
    # We will process candidates and track the ones successfully refactored
    active_candidates = []
    
    # --- PHASE 1: Refactoring (Overwrite all files first) ---
    print("\n=== PHASE 1: Refactoring all files ===")
    processed_count = 0
    for cand in candidates:
        if processed_count >= limit:
            break
            
        cand_id = cand.get("trecho")
        ref_type = cand.get("refatoracao_tipo")
        orig_file_path = cand.get("file_path")
        
        # Replace temp_workspace/finder_Math_1/ with local checkout path
        rel_path = orig_file_path.replace("temp_workspace/finder_Math_1/", "")
        source_file = os.path.join(checkout_dir, rel_path)
        
        if not os.path.exists(source_file):
            print(f"Skipping {cand_id}: File not found at {source_file}")
            continue
            
        print(f"\n--- Refactoring Candidate {processed_count + 1}/{limit}: {cand_id} ---")
        
        # Read original file
        with open(source_file, "r", encoding="utf-8") as f:
            original_code = f.read()

        
        # Build targeted instruction dynamically
        line_num = cand.get("line", "unknown")
        targeted_instruction = f"Refactor the following Java code to improve readability and maintainability without changing its behavior. Focus only on the method or block of code located around line {line_num} by applying '{ref_type}' refactoring. Do NOT modify the signatures of other methods, their behavior, or any other parts of the class. Return the complete refactored class."
        formatting_instruction = "\nIMPORTANT: The refactored Java code in your response MUST be properly formatted with standard indentation and newlines. Do not compress the code into a single line."
        full_instruction = targeted_instruction + formatting_instruction
            
        # Query Gemini API
        print(f"Calling Gemini API ({model}) for {cand_id}...")
        try:
            response = client.models.generate_content(
                model=model,
                contents=f"{full_instruction}\n\n{original_code}",
                config=types.GenerateContentConfig(
                    response_mime_type="application/json",
                    response_schema=RefactorResult,
                    system_instruction=persona_preamble or None,
                )
            )
            
            # Save raw response text and metadata
            raw_path = os.path.join(raw_responses_dir, f"{cand_id}_raw.json")
            raw_json_str = response.model_dump_json(indent=2)
            with open(raw_path, "w", encoding="utf-8") as f_raw:
                f_raw.write(raw_json_str)
            print(f"Saved raw response to: {raw_path}")
            
            # Extract tokens
            prompt_tokens = response.usage_metadata.prompt_token_count if response.usage_metadata else 0
            candidates_tokens = response.usage_metadata.candidates_token_count if response.usage_metadata else 0
            total_tokens = response.usage_metadata.total_token_count if response.usage_metadata else 0
            
            # Parse structured result
            result = response.parsed
            if result is None or not getattr(result, "refactored_code", None):
                raise ValueError("Parsed structured response is empty or invalid.")
            refactored_code = result.refactored_code
            
        except Exception as e:
            print(f"Gemini API Call failed: {e}")
            writer.writerow([cand_id, ref_type, rel_path, "API_ERROR", str(e), 0, 0, 0])
            csv_file.flush()
            continue
            
        # Save a copy for user inspection
        save_copy_path = os.path.join(refactored_outputs_dir, f"{cand_id}.java")
        with open(save_copy_path, "w", encoding="utf-8") as f_copy:
            f_copy.write(refactored_code)
        print(f"Saved refactored copy to: {save_copy_path}")
        
        # Overwrite original file
        with open(source_file, "w", encoding="utf-8") as f:
            f.write(refactored_code)
            
        # Track this candidate for the compilation phase
        active_candidates.append({
            "cand_id": cand_id,
            "ref_type": ref_type,
            "rel_path": rel_path,
            "source_file": source_file,
            "prompt_tokens": prompt_tokens,
            "candidates_tokens": candidates_tokens,
            "total_tokens": total_tokens
        })
        
        processed_count += 1
        
        # Sleep to avoid rate limits
        if processed_count < limit:
            print(f"Sleeping for {sleep_seconds} seconds to avoid rate limits...")
            time.sleep(sleep_seconds)
            
    # --- PHASE 2: Compilation (Compile all at once) ---
    print("\n=== PHASE 2: Compiling all refactored files ===")
    bin_dir = os.path.join(checkout_dir, "bin")
    
    for item in active_candidates:
        cand_id = item["cand_id"]
        ref_type = item["ref_type"]
        rel_path = item["rel_path"]
        source_file = item["source_file"]
        
        print(f"Compiling {cand_id}...")
        compile_cmd = [
            "javac",
            "-sourcepath", os.path.join(checkout_dir, "src/main/java"),
            source_file,
            "-d", bin_dir
        ]
        
        res = subprocess.run(compile_cmd, capture_output=True, text=True)
        
        prompt_tokens = item.get("prompt_tokens", 0)
        candidates_tokens = item.get("candidates_tokens", 0)
        total_tokens = item.get("total_tokens", 0)
        
        # Record result
        if res.returncode == 0:
            print(f"SUCCESS: {cand_id} compiled successfully!")
            writer.writerow([cand_id, ref_type, rel_path, "SUCCESS", "", prompt_tokens, candidates_tokens, total_tokens])
        else:
            print(f"FAILURE: {cand_id} compilation failed.")
            # Filter warnings to find first actual error
            stderr_lines = res.stderr.splitlines()
            error_lines = [line for line in stderr_lines if "warning:" not in line and "[warning]" not in line]
            first_error_line = error_lines[0] if error_lines else (stderr_lines[0] if stderr_lines else "Unknown error")
            print(f"Error details: {first_error_line}")
            writer.writerow([cand_id, ref_type, rel_path, "FAILURE", first_error_line, prompt_tokens, candidates_tokens, total_tokens])
            
        csv_file.flush()
        
    # --- PHASE 3: Clean up (Restore entire repo using git checkout .) ---
    print("\n=== PHASE 3: Cleaning up repository ===")
    print("Restoring all files in checkout directory via git checkout...")
    subprocess.run(["git", "-C", checkout_dir, "checkout", "."], capture_output=True)
    
    csv_file.close()
    print(f"\nFinished testing. Results appended to {results_csv}")

if __name__ == "__main__":
    main()

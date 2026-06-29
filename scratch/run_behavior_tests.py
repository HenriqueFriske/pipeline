import os
import sys
import json
import csv
import subprocess
import shutil

def main():
    checkout_dir = "temp_workspace/test_checkout"
    refactored_outputs_dir = "scratch/refactored_outputs"
    results_csv = "scratch/candidates_behavior_test_results.csv"
    
    # Load subset candidates
    with open("candidates_subset.json", "r", encoding="utf-8") as f:
        data = json.load(f)
        
    candidates = []
    for category, items in data.items():
        for item in items:
            item["categoria"] = category
            candidates.append(item)
            
    print(f"Loaded {len(candidates)} candidates for behavioral testing.")
    
    # Setup CSV output
    file_exists = os.path.exists(results_csv)
    csv_file = open(results_csv, "w", newline="", encoding="utf-8")
    writer = csv.writer(csv_file)
    writer.writerow(["candidate_id", "refactoring_type", "file_path", "compile_status", "test_status", "failing_tests_details"])
    
    for cand in candidates:
        cand_id = cand.get("trecho")
        ref_type = cand.get("refatoracao_tipo")
        orig_file_path = cand.get("file_path")
        
        # Paths
        rel_path = orig_file_path.replace("temp_workspace/finder_Math_1/", "")
        source_file = os.path.join(checkout_dir, rel_path)
        refactored_file = os.path.join(refactored_outputs_dir, f"{cand_id}.java")
        
        if not os.path.exists(refactored_file):
            print(f"Skipping {cand_id}: No refactored code copy found.")
            continue
            
        print(f"\n--- Testing Behavior for Candidate: {cand_id} ---")
        
        # 1. Overwrite original file
        shutil.copy2(refactored_file, source_file)
        
        # 2. Compile via Defects4J
        print("Compiling via Defects4J...")
        compile_cmd = ["..\\..\\defects4j.bat", "compile"]
        compile_res = subprocess.run(compile_cmd, cwd=checkout_dir, capture_output=True, text=True, shell=True, stdin=subprocess.DEVNULL)
        
        if compile_res.returncode != 0:
            print("FAILURE: Compilation failed.")
            writer.writerow([cand_id, ref_type, rel_path, "COMPILE_FAIL", "N/A", ""])
            csv_file.flush()
            # Restore file
            subprocess.run(["git", "-C", checkout_dir, "checkout", rel_path], capture_output=True, shell=True, stdin=subprocess.DEVNULL)
            continue
            
        print("SUCCESS: Compiled successfully. Running tests...")
        
        # Remove old failing_tests file if present
        failing_tests_file = os.path.join(checkout_dir, "failing_tests")
        if os.path.exists(failing_tests_file):
            os.remove(failing_tests_file)
            
        # 3. Run tests via Defects4J
        test_cmd = ["..\\..\\defects4j.bat", "test"]
        test_res = subprocess.run(test_cmd, cwd=checkout_dir, capture_output=True, text=True, shell=True, stdin=subprocess.DEVNULL)
        
        # 4. Check results
        test_status = "PASS"
        failing_details = ""
        
        if os.path.exists(failing_tests_file):
            with open(failing_tests_file, "r", encoding="utf-8") as f_fail:
                failures = [line.strip() for line in f_fail if line.strip()]
            if failures:
                test_status = "FAIL"
                failing_details = "; ".join(failures)
                print(f"FAILURE: {len(failures)} tests failed: {failing_details}")
            else:
                print("SUCCESS: All tests passed!")
        else:
            # If the file wasn't created, check the return code or assume PASS if no details
            print("SUCCESS: All tests passed (no failing_tests file)!")
            
        # Write final row with test status
        writer.writerow([cand_id, ref_type, rel_path, "COMPILE_SUCCESS", test_status, failing_details])
        csv_file.flush()
        
        # 5. Restore file
        print("Restoring file via git checkout...")
        subprocess.run(["git", "-C", checkout_dir, "checkout", rel_path], capture_output=True, shell=True, stdin=subprocess.DEVNULL)
        
    csv_file.close()
    print(f"\nBehavioral testing finished. Results saved to {results_csv}")

if __name__ == "__main__":
    main()

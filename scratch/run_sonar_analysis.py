import os
import sys
import json
import csv
import shutil
import subprocess
from core.sonarqube_anal import SonarQubeManager

def main():
    checkout_dir = "temp_workspace/test_checkout"
    refactored_outputs_dir = "scratch/refactored_outputs"
    results_csv = "scratch/candidates_sonar_results.csv"
    
    # Load settings
    with open("config/settings.json", "r", encoding="utf-8") as f:
        settings = json.load(f)
        
    sonar_url = settings.get("sonar_url", "http://localhost:9000")
    sonar_token = settings.get("sonar_token", "")
    
    # Instantiate SonarQubeManager using the local Windows batch wrapper
    scanner_bin = os.path.abspath("sonar-scanner.bat")
    sonar_mgr = SonarQubeManager(url=sonar_url, token=sonar_token, scanner_bin=scanner_bin)
    
    # Load subset candidates
    with open("candidates_subset.json", "r", encoding="utf-8") as f:
        data = json.load(f)
        
    candidates = []
    for category, items in data.items():
        for item in items:
            item["categoria"] = category
            candidates.append(item)
            
    print(f"Loaded {len(candidates)} candidates for SonarQube quality analysis.")
    
    # Setup CSV output
    file_exists = os.path.exists(results_csv)
    csv_file = open(results_csv, "w", newline="", encoding="utf-8")
    writer = csv.writer(csv_file)
    writer.writerow([
        "candidate_id", "refactoring_type", "file_path", 
        "complexity_base", "complexity_refac", "complexity_delta",
        "smells_base", "smells_refac", "smells_delta"
    ])
    csv_file.flush()
    
    # Check if SonarQube is online
    if not sonar_mgr.ping():
        print("Error: SonarQube is not online or status is not UP.")
        sys.exit(1)
        
    abs_classes_dir = os.path.abspath(os.path.join(checkout_dir, "target/classes"))
    
    for i, cand in enumerate(candidates):
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
            
        print(f"\n--- Analyzing Candidate {i+1}/{len(candidates)}: {cand_id} ---")
        
        # 1. Run SonarQube baseline scan on original code
        project_key_base = f"{cand_id}_base"
        project_name_base = f"{cand_id}_base"
        
        print("Running SonarQube baseline scan...")
        try:
            sonar_mgr.scan(project_key_base, project_name_base, os.path.abspath(source_file), abs_classes_dir)
            sonar_mgr.wait_for_task(project_key_base)
            metrics_base = sonar_mgr.get_metrics(project_key_base)
            complexity_base = metrics_base.get("complexity", 0)
            smells_base = metrics_base.get("code_smells", 0)
            print(f"Baseline - Complexity: {complexity_base}, Code Smells: {smells_base}")
            
            # Delete baseline project
            sonar_mgr.delete_project(project_key_base)
        except Exception as e:
            print(f"Baseline scan failed for {cand_id}: {e}")
            continue
            
        # 2. Overwrite file with refactored code
        shutil.copy2(refactored_file, source_file)
        
        # 3. Run SonarQube scan on refactored code
        project_key_pos = f"{cand_id}_refac"
        project_name_pos = f"{cand_id}_refac"
        
        print("Running SonarQube post-refactoring scan...")
        try:
            sonar_mgr.scan(project_key_pos, project_name_pos, os.path.abspath(source_file), abs_classes_dir)
            sonar_mgr.wait_for_task(project_key_pos)
            metrics_refac = sonar_mgr.get_metrics(project_key_pos)
            complexity_refac = metrics_refac.get("complexity", 0)
            smells_refac = metrics_refac.get("code_smells", 0)
            print(f"Refactored - Complexity: {complexity_refac}, Code Smells: {smells_refac}")
            
            # Delete refactored project
            sonar_mgr.delete_project(project_key_pos)
        except Exception as e:
            print(f"Post-refactoring scan failed for {cand_id}: {e}")
            # Restore file
            subprocess.run(["git", "-C", checkout_dir, "checkout", rel_path], capture_output=True, shell=True, stdin=subprocess.DEVNULL)
            continue
            
        # 4. Restore original file
        print("Restoring file via git checkout...")
        subprocess.run(["git", "-C", checkout_dir, "checkout", rel_path], capture_output=True, shell=True, stdin=subprocess.DEVNULL)
        
        # 5. Record result
        comp_delta = complexity_refac - complexity_base
        smell_delta = smells_refac - smells_base
        
        writer.writerow([
            cand_id, ref_type, rel_path,
            complexity_base, complexity_refac, comp_delta,
            smells_base, smells_refac, smell_delta
        ])
        csv_file.flush()
        
    csv_file.close()
    print(f"\nSonarQube analysis finished. Results saved to {results_csv}")

if __name__ == "__main__":
    main()

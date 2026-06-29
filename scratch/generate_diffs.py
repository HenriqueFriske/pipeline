import os
import json
import difflib

def main():
    checkout_dir = "temp_workspace/test_checkout"
    refactored_outputs_dir = "scratch/refactored_outputs"
    diff_dir = "scratch/diffs"
    
    os.makedirs(diff_dir, exist_ok=True)
    
    # Load subset candidates
    with open("candidates_subset.json", "r", encoding="utf-8") as f:
        data = json.load(f)
        
    candidates = []
    for category, items in data.items():
        for item in items:
            item["categoria"] = category
            candidates.append(item)
            
    print(f"Generating diffs for {len(candidates)} candidates...")
    
    diff_count = 0
    for cand in candidates:
        cand_id = cand.get("trecho")
        orig_file_path = cand.get("file_path")
        
        # Paths
        rel_path = orig_file_path.replace("temp_workspace/finder_Math_1/", "")
        original_file = os.path.join(checkout_dir, rel_path)
        refactored_file = os.path.join(refactored_outputs_dir, f"{cand_id}.java")
        
        if not os.path.exists(original_file):
            print(f"Skipping {cand_id}: Original file not found.")
            continue
        if not os.path.exists(refactored_file):
            print(f"Skipping {cand_id}: Refactored file not found at {refactored_file}.")
            continue
            
        # Read contents
        with open(original_file, "r", encoding="utf-8") as f:
            orig_lines = f.readlines()
        with open(refactored_file, "r", encoding="utf-8") as f:
            ref_lines = f.readlines()
            
        # Generate unified diff
        diff = difflib.unified_diff(
            orig_lines,
            ref_lines,
            fromfile=f"original/{rel_path}",
            tofile=f"refactored/{rel_path}"
        )
        
        diff_text = "".join(diff)
        
        # Save diff
        diff_path = os.path.join(diff_dir, f"{cand_id}.diff")
        with open(diff_path, "w", encoding="utf-8") as f_diff:
            f_diff.write(diff_text)
            
        diff_count += 1
        
    print(f"Successfully generated {diff_count} diff files in {diff_dir}")

if __name__ == "__main__":
    main()

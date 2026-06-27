import json
import re

def clean_path(path, project, version):
    # Remove prefix like "temp_workspace/finder_Math_1/"
    prefix = f"temp_workspace/finder_{project}_{version}/"
    if path.startswith(prefix):
        return path[len(prefix):]
    # Fallback to general regex substitution
    path = re.sub(r"^temp_workspace/finder_[A-Za-z0-9_]+/", "", path)
    return path

def main():
    with open("candidates.json", encoding="utf-8") as f:
        candidates_data = json.load(f)

    types = ["ExtractMethod", "ReplaceMagicNumber", "ReplaceConditionalWithPolymorphism"]
    selected_snippets = []

    for rtype in types:
        items = candidates_data.get(rtype, [])
        # We need the top 10 candidates
        top_10 = items[:10]
        print(f"Selecting {len(top_10)} candidates for {rtype}:")
        
        for idx, item in enumerate(top_10, start=1):
            project = item["project"]
            version = str(item["version"])
            raw_path = item["file_path"]
            cleaned_path = clean_path(raw_path, project, version)
            
            snippet = {
                "trecho": f"{project}_{version}_{rtype}_{idx}",
                "project": project,
                "version": version,
                "file_path": cleaned_path,
                "refatoracao_tipo": rtype
            }
            selected_snippets.append(snippet)
            print(f"  - {snippet['trecho']}: {snippet['file_path']}")

    # Write to config/snippets.json
    output_path = "config/snippets.json"
    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(selected_snippets, f, indent=2)

    print(f"\nSuccessfully wrote {len(selected_snippets)} snippets to {output_path}")

if __name__ == "__main__":
    main()

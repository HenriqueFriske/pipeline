import sys
import subprocess
import os

PROJECT_ROOT = os.path.dirname(os.path.abspath(__file__))

def translate_path(path: str) -> str:
    norm_path = os.path.abspath(path)
    if norm_path.lower().startswith(PROJECT_ROOT.lower()):
        rel = os.path.relpath(norm_path, PROJECT_ROOT)
        if rel == ".":
            return "/app"
        return "/app/" + rel.replace("\\", "/")
    return path.replace("\\", "/")

def main():
    cwd = os.getcwd()
    container_cwd = translate_path(cwd)
    
    args = sys.argv[1:]
    translated_args = []
    for arg in args:
        if arg.startswith("-") or not ("\\" in arg or "/" in arg or PROJECT_ROOT.lower() in arg.lower()):
            translated_args.append(arg)
        else:
            translated_args.append(translate_path(arg))
            
    cmd = ["docker", "exec", "-i", "-w", container_cwd, "defects4j-container", "defects4j"] + translated_args
    res = subprocess.run(cmd)
    sys.exit(res.returncode)

if __name__ == "__main__":
    main()

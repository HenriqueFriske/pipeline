import sys
import subprocess
import os

PROJECT_ROOT = os.path.dirname(os.path.abspath(__file__))

def translate_path(path: str) -> str:
    # If the path contains the project root, translate it to /usr/src/...
    norm_path = os.path.abspath(path)
    if norm_path.lower().startswith(PROJECT_ROOT.lower()):
        rel = os.path.relpath(norm_path, PROJECT_ROOT)
        if rel == ".":
            return "/usr/src"
        return "/usr/src/" + rel.replace("\\", "/")
    return path.replace("\\", "/")

def main():
    args = sys.argv[1:]
    translated_args = []
    for arg in args:
        if arg.startswith("-Dsonar.sources="):
            path = arg.split("=", 1)[1]
            translated_args.append(f"-Dsonar.sources={translate_path(path)}")
        elif arg.startswith("-Dsonar.java.binaries="):
            path = arg.split("=", 1)[1]
            translated_args.append(f"-Dsonar.java.binaries={translate_path(path)}")
        elif arg.startswith("-Dsonar.projectBaseDir="):
            path = arg.split("=", 1)[1]
            translated_args.append(f"-Dsonar.projectBaseDir={translate_path(path)}")
        elif arg.startswith("C:\\") or arg.startswith("C:/") or PROJECT_ROOT.lower() in arg.lower():
            translated_args.append(translate_path(arg))
        else:
            translated_args.append(arg)
            
    # For Docker container to reach host network (where SonarQube is on port 9000)
    # We use --network=host on Linux/Docker WSL, or we can use host.docker.internal
    # since we are running on Windows. But wait! The sonar_url is http://localhost:9000.
    # On Windows/macOS, localhost from inside a container doesn't reach the host unless --network=host is used.
    # Actually, Docker Desktop supports --network=host, or we can map host.docker.internal.
    # Let's replace http://localhost:9000 with http://host.docker.internal:9000 for the scanner inside docker!
    for i, arg in enumerate(translated_args):
        if arg.startswith("-Dsonar.host.url=http://localhost:9000"):
            translated_args[i] = "-Dsonar.host.url=http://host.docker.internal:9000"
            
    cmd = ["docker", "run", "--rm", "-e", "SONAR_TOKEN", "-v", f"{PROJECT_ROOT}:/usr/src", "sonarsource/sonar-scanner-cli"] + translated_args
    res = subprocess.run(cmd)
    sys.exit(res.returncode)

if __name__ == "__main__":
    main()

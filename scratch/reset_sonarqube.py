import os
import sys
import json
import time
import subprocess
import requests

def main():
    print("=== Resetting SonarQube Container ===")
    
    # 1. Stop and remove existing container
    print("Stopping and removing existing 'sonarqube' container...")
    subprocess.run(["docker", "rm", "-f", "sonarqube"], capture_output=True)
    
    # 2. Run new SonarQube container from scratch
    print("Starting fresh SonarQube container...")
    run_cmd = [
        "docker", "run", "-d",
        "--name", "sonarqube",
        "-p", "9000:9000",
        "sonarqube:latest"
    ]
    res = subprocess.run(run_cmd, capture_output=True, text=True)
    if res.returncode != 0:
        print(f"Error starting container: {res.stderr}")
        sys.exit(1)
        
    print("Container started. Waiting for SonarQube to boot up (this may take 1-3 minutes)...")
    
    # 3. Poll status until UP
    url_status = "http://localhost:9000/api/system/status"
    start_time = time.time()
    booted = False
    
    while time.time() - start_time < 300: # 5 minutes timeout
        try:
            resp = requests.get(url_status, timeout=5)
            if resp.status_code == 200:
                status = resp.json().get("status")
                if status == "UP":
                    print("\nSonarQube is UP and ready!")
                    booted = True
                    break
        except Exception:
            pass
        sys.stdout.write(".")
        sys.stdout.flush()
        time.sleep(5)
        
    if not booted:
        print("\nTimeout: SonarQube did not start within 5 minutes.")
        sys.exit(1)
        
    # 4. Change default admin password (admin -> PipelineAdmin123!)
    print("Changing default admin password...")
    new_pw = "PipelineAdmin123!"
    change_url = f"http://localhost:9000/api/users/change_password?login=admin&previousPassword=admin&password={new_pw}"
    
    # Needs to wait a couple of seconds for DB migrations to settle
    time.sleep(5)
    
    try:
        resp = requests.post(change_url, auth=("admin", "admin"), timeout=15)
        if resp.status_code not in (200, 204):
            print(f"Failed to change password: HTTP {resp.status_code} - {resp.text}")
            sys.exit(1)
        print("Password changed successfully to PipelineAdmin123!")
    except Exception as e:
        print(f"Failed to change password due to request error: {e}")
        sys.exit(1)
        
    # 5. Generate User Token
    print("Generating analysis token...")
    token_url = "http://localhost:9000/api/user_tokens/generate?name=pipeline"
    try:
        resp = requests.post(token_url, auth=("admin", new_pw), timeout=15)
        if resp.status_code != 200:
            print(f"Failed to generate token: HTTP {resp.status_code} - {resp.text}")
            sys.exit(1)
        
        token_data = resp.json()
        token = token_data.get("token")
        print(f"Generated token: {token}")
        
        # 6. Save token to config/settings.json
        settings_path = "config/settings.json"
        if os.path.exists(settings_path):
            with open(settings_path, "r", encoding="utf-8") as f:
                settings = json.load(f)
            
            settings["sonar_token"] = token
            
            with open(settings_path, "w", encoding="utf-8") as f:
                json.dump(settings, f, indent=2)
            print("Successfully saved token to config/settings.json!")
        else:
            print(f"Warning: config/settings.json not found. Save this token: {token}")
            
    except Exception as e:
        print(f"Failed to generate token: {e}")
        sys.exit(1)
        
    print("=== SonarQube Reset completed successfully ===")

if __name__ == "__main__":
    main()

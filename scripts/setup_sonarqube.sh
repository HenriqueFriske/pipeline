#!/bin/bash
set -u
TOKEN_FILE=/tmp/sonar_token.txt
ADMIN_NEW_PW="PipelineAdmin123!"

echo "[1] waiting for sonarqube:community image..."
for i in $(seq 1 600); do
  [ -n "$(docker images -q sonarqube:community 2>/dev/null)" ] && break
  sleep 2
done
[ -z "$(docker images -q sonarqube:community 2>/dev/null)" ] && { echo "FAIL: image never arrived"; exit 1; }
echo "    image present."

echo "[2] (re)starting sonarqube container..."
docker rm -f sonarqube >/dev/null 2>&1
docker run -d --name sonarqube -p 9000:9000 sonarqube:community >/dev/null || { echo "FAIL: run"; exit 1; }

echo "[3] waiting for status UP (max ~6min)..."
UP=0
for i in $(seq 1 180); do
  s=$(curl -s -m 5 http://localhost:9000/api/system/status 2>/dev/null)
  echo "$s" | grep -q '"status":"UP"' && { UP=1; break; }
  sleep 2
done
[ "$UP" -eq 0 ] && { echo "FAIL: never UP. last=$s"; docker logs --tail 30 sonarqube; exit 1; }
echo "    UP."

echo "[4] changing default admin password (admin -> new)..."
curl -s -u admin:admin -X POST \
  "http://localhost:9000/api/users/change_password?login=admin&previousPassword=admin&password=${ADMIN_NEW_PW}" \
  -o /tmp/sonar_pw_change.txt -w "    http=%{http_code}\n"

echo "[5] generating user token..."
# delete any prior token of same name (idempotent), then generate.
curl -s -u "admin:${ADMIN_NEW_PW}" -X POST \
  "http://localhost:9000/api/user_tokens/revoke?name=pipeline" >/dev/null 2>&1
RESP=$(curl -s -u "admin:${ADMIN_NEW_PW}" -X POST \
  "http://localhost:9000/api/user_tokens/generate?name=pipeline")
echo "$RESP" > /tmp/sonar_token_resp.json
TOK=$(python3 -c "import sys,json; print(json.load(open('/tmp/sonar_token_resp.json')).get('token',''))" 2>/dev/null)
if [ -z "$TOK" ]; then echo "FAIL: no token. resp=$RESP"; exit 1; fi
echo "$TOK" > "$TOKEN_FILE"
echo "    token saved to $TOKEN_FILE"
echo "DONE: SonarQube ready, admin pw=${ADMIN_NEW_PW}, token in $TOKEN_FILE"

# Pipeline Setup — Reproducible Bring-up

End-to-end setup to run the refactoring pipeline on a fresh machine
(macOS / Linux; Windows via WSL2 recommended).

## Prerequisites

- **Docker** Engine 20.10+ (Docker Desktop on macOS/Windows). ~6 GB free disk.
- **Python 3.9+** (3.9 works thanks to `from __future__ import annotations`; 3.10+ also fine).
- A **Gemini API key** with enough quota (free tier rate-limits quickly → many
  `FALHA_API_RATE_LIMIT`; prefer a paid/raised-quota key for real runs).

## 1. Python environment

```sh
python3 -m venv .venv
. .venv/bin/activate           # Windows: .venv\Scripts\activate
pip install -r requirements.txt
```

## 2. Build the Defects4J image (Java 8 + defects4j v2.0.1)

```sh
docker build -f Dockerfile.defects4j -t defects4j-image .
```
> Slow (~15-30 min): `init.sh` downloads the benchmark repos. Pinned to v2.0.1
> because v3.x requires Java 11 and breaks the legacy project builds.

## 3. Run the Defects4J container (project bind-mounted at /app)

```sh
docker run -d --name defects4j-container -v "$PWD":/app defects4j-image
docker exec defects4j-container defects4j pids   # sanity: lists Math, Lang, ...
```

## 4. SonarQube server + scanner

```sh
docker pull sonarsource/sonar-scanner-cli
# Option A — scripted (boots Sonar, sets admin pw, prints token to /tmp/sonar_token.txt):
bash scripts/setup_sonarqube.sh
# Option B — manual:
docker run -d --name sonarqube -p 9000:9000 sonarqube:community
# wait until http://localhost:9000/api/system/status reports "UP", then create a
# user token under My Account > Security.
```

## 5. Secrets — `.env` (gitignored, never commit)

```sh
cat > .env <<'EOF'
GEMINI_API_KEY=<your-gemini-key>
SONAR_TOKEN=<sonar-token-from-step-4>
EOF
```

## 6. Platform wrappers

`config/settings.json` points `defects4j_path` / `sonar_scanner_path` at the
`.sh` wrappers (macOS/Linux). **On native Windows**, change them back to
`defects4j.bat` / `sonar-scanner.bat`. Both call the same `*_wrapper.py` Docker
shims.

## 7. Run

`main.py` does not auto-load `.env` — source it first:

```sh
set -a; . ./.env; set +a
# smoke (1 snippet):
python main.py config/settings.json config/snippets_test.json checkpoint_ledger_e2e.csv
# full experiment (30 snippets):
python main.py config/settings.json config/snippets.json checkpoint_ledger.csv
```

Runs are resumable: the ledger CSV records completed rounds; re-running skips them.

## Notes / gotchas

- `main.py` previously had a debug `if id_rodada == 3: return` limiter — now removed.
- `PERSONAS` in `main.py` has 4 entries (P-1..P-4); the design doc defines 5 (add
  P-5 contextual for the full 750-round matrix).
- Whole-file `ExtractMethod` refactors break compilation almost every time
  (legitimate experiment finding, not a pipeline bug).
- `pytest` writes a few tiny stub files into `refactored_code/` and `checkpoint.txt`
  via tmp-dir isolation now; pre-existing stubs there are safe to delete.

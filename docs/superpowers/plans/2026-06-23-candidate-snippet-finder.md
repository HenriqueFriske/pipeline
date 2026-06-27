# Candidate Snippet Finder Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an automated SonarQube-driven finder that scans fixed Defects4J versions and outputs ranked, classified refactoring-opportunity candidates for manual confirmation into the final 30 snippets.

**Architecture:** Three isolated, independently testable units — a new `search_issues` REST method on `SonarQubeManager`, a pure `candidate_ranker` module, and a new `export_source_dir` method on `Defects4JManager` — wired together by a thin `find_candidates.py` orchestrator that mirrors `main.py`'s per-target resilience.

**Tech Stack:** Python 3.12, `requests`, `pytest`, `unittest.mock`. SonarQube Web API. Defects4J CLI.

## Global Constraints

- Python ≥ 3.10 (uses `int | None` unions).
- No new third-party deps — `requests` and `pytest` only.
- Refactoring types (exact strings): `ExtractMethod`, `ReplaceMagicNumber`, `ReplaceConditionalWithPolymorphism`.
- SonarQube error convention: 5xx → `SonarQubeServerError`; non-200 → `SonarQubeConnectionError`; JSON parse failure → `SonarQubeError`; network error → `SonarQubeServerError`.
- Each candidate carries the `snippets.json` fields verbatim: `trecho, project, version, file_path, refatoracao_tipo`.
- `trecho` id format: `f"{project}_{version}_{refatoracao_tipo}_{idx}"` (idx 1-based per type, per target).

---

### Task 1: `SonarQubeManager.search_issues`

**Files:**
- Modify: `core/sonarqube_anal.py` (add method to `SonarQubeManager`)
- Test: `tests/test_sonarqube_anal.py` (append tests)

**Interfaces:**
- Consumes: existing `self.url`, `self.token`, module exceptions.
- Produces: `search_issues(self, component_key: str, rules: list[str], page_size: int = 500, max_pages: int = 20) -> list[dict]` where each dict is `{"rule": str, "file_path": str, "line": int|None, "message": str, "effort": str|None}`.

- [ ] **Step 1: Write the failing tests**

Append to `tests/test_sonarqube_anal.py` (ensure `from unittest.mock import patch, Mock` and `import core.sonarqube_anal` / relevant imports exist at top; add if missing):

```python
def _issue(rule, component, line, message="msg"):
    return {"rule": rule, "component": component, "line": line, "message": message, "effort": "5min"}


def test_search_issues_single_page_strips_prefix():
    from core.sonarqube_anal import SonarQubeManager
    mgr = SonarQubeManager(url="http://localhost:9000", token="t")
    resp = Mock()
    resp.status_code = 200
    resp.json.return_value = {
        "total": 2,
        "issues": [
            _issue("java:S109", "finder_Lang_1:src/main/java/A.java", 12),
            _issue("java:S3776", "finder_Lang_1:src/main/java/B.java", 30,
                   "Refactor this method to reduce its Cognitive Complexity from 27 to the 15 allowed."),
        ],
    }
    with patch("requests.get", return_value=resp):
        out = mgr.search_issues("finder_Lang_1", ["java:S109", "java:S3776"])
    assert len(out) == 2
    assert out[0] == {"rule": "java:S109", "file_path": "src/main/java/A.java",
                      "line": 12, "message": "msg", "effort": "5min"}
    assert out[1]["file_path"] == "src/main/java/B.java"


def test_search_issues_paginates():
    from core.sonarqube_anal import SonarQubeManager
    mgr = SonarQubeManager(url="http://localhost:9000", token="t")
    page1 = Mock(status_code=200)
    page1.json.return_value = {"total": 600, "issues": [_issue("java:S109", "k:f.java", i) for i in range(500)]}
    page2 = Mock(status_code=200)
    page2.json.return_value = {"total": 600, "issues": [_issue("java:S109", "k:f.java", i) for i in range(100)]}
    with patch("requests.get", side_effect=[page1, page2]) as mock_get:
        out = mgr.search_issues("k", ["java:S109"])
    assert len(out) == 600
    assert mock_get.call_count == 2


def test_search_issues_empty():
    from core.sonarqube_anal import SonarQubeManager
    mgr = SonarQubeManager(url="http://localhost:9000", token="t")
    resp = Mock(status_code=200)
    resp.json.return_value = {"total": 0, "issues": []}
    with patch("requests.get", return_value=resp):
        assert mgr.search_issues("k", ["java:S109"]) == []


def test_search_issues_server_error():
    from core.sonarqube_anal import SonarQubeManager, SonarQubeServerError
    mgr = SonarQubeManager(url="http://localhost:9000", token="t")
    resp = Mock(status_code=503, text="down")
    with patch("requests.get", return_value=resp):
        with pytest.raises(SonarQubeServerError):
            mgr.search_issues("k", ["java:S109"])
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `python -m pytest tests/test_sonarqube_anal.py -k search_issues -v`
Expected: FAIL with `AttributeError: 'SonarQubeManager' object has no attribute 'search_issues'`

- [ ] **Step 3: Implement the method**

Add to the `SonarQubeManager` class in `core/sonarqube_anal.py` (after `get_metrics`):

```python
    def search_issues(self, component_key: str, rules: list, page_size: int = 500, max_pages: int = 20) -> list:
        url = f"{self.url}/api/issues/search"
        logger = _get_logger()
        rules_param = ",".join(rules)
        logger.info(f"Searching issues for {component_key} rules={rules_param}")
        results = []
        page = 1
        while page <= max_pages:
            params = {"componentKeys": component_key, "rules": rules_param, "ps": page_size, "p": page}
            try:
                response = requests.get(url, params=params, auth=(self.token, ""), timeout=30)
            except requests.RequestException as e:
                raise SonarQubeServerError(f"Error searching issues: {e}") from e

            if response.status_code >= 500:
                raise SonarQubeServerError(f"SonarQube server returned 5xx error: {response.status_code}")
            if response.status_code != 200:
                raise SonarQubeConnectionError(f"Failed to search issues: HTTP {response.status_code} - {response.text}")

            try:
                data = response.json()
            except Exception as e:
                raise SonarQubeError(f"Error parsing issues JSON: {e}") from e

            issues = data.get("issues", [])
            for issue in issues:
                component = issue.get("component", "")
                file_path = component.split(":", 1)[1] if ":" in component else component
                results.append({
                    "rule": issue.get("rule"),
                    "file_path": file_path,
                    "line": issue.get("line"),
                    "message": issue.get("message", ""),
                    "effort": issue.get("effort"),
                })

            total = data.get("total", 0)
            if not issues or page * page_size >= total:
                break
            page += 1
        return results
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `python -m pytest tests/test_sonarqube_anal.py -k search_issues -v`
Expected: PASS (4 tests)

- [ ] **Step 5: Commit**

```bash
git add core/sonarqube_anal.py tests/test_sonarqube_anal.py
git commit -m "feat: add SonarQubeManager.search_issues with pagination"
```

---

### Task 2: `core/candidate_ranker.py`

**Files:**
- Create: `core/candidate_ranker.py`
- Test: `tests/test_candidate_ranker.py`

**Interfaces:**
- Consumes: issue dicts shaped like `search_issues` output (`{"rule", "file_path", "line", "message"}`).
- Produces:
  - `RULE_MAP: dict[str, list[str]]`, `ALL_RULES: list[str]`
  - `Candidate` dataclass: `trecho, project, version, file_path, refatoracao_tipo, line, score, signal`
  - `parse_complexity(message: str) -> int | None`
  - `classify_and_rank(issues: list[dict], project: str, version: str) -> list[Candidate]`

- [ ] **Step 1: Write the failing tests**

Create `tests/test_candidate_ranker.py`:

```python
from core.candidate_ranker import (
    RULE_MAP, ALL_RULES, Candidate, parse_complexity, classify_and_rank,
)


def test_parse_complexity_valid():
    msg = "Refactor this method to reduce its Cognitive Complexity from 27 to the 15 allowed."
    assert parse_complexity(msg) == 27


def test_parse_complexity_invalid():
    assert parse_complexity("no number here") is None
    assert parse_complexity("") is None


def test_all_rules_covers_three_types():
    assert set(RULE_MAP) == {"ExtractMethod", "ReplaceMagicNumber", "ReplaceConditionalWithPolymorphism"}
    assert "java:S3776" in ALL_RULES
    assert "java:S109" in ALL_RULES


def test_extract_method_per_issue_sorted_by_complexity():
    issues = [
        {"rule": "java:S3776", "file_path": "A.java", "line": 10,
         "message": "Cognitive Complexity from 15 to the 15 allowed."},
        {"rule": "java:S3776", "file_path": "B.java", "line": 20,
         "message": "Cognitive Complexity from 40 to the 15 allowed."},
    ]
    out = classify_and_rank(issues, "Lang", "1")
    assert [c.file_path for c in out] == ["B.java", "A.java"]
    assert out[0].score == 40
    assert out[0].refatoracao_tipo == "ExtractMethod"
    assert out[0].trecho == "Lang_1_ExtractMethod_1"
    assert out[1].trecho == "Lang_1_ExtractMethod_2"
    assert out[0].signal == "cognitive_complexity=40"


def test_magic_number_aggregated_per_file_by_count():
    issues = [
        {"rule": "java:S109", "file_path": "A.java", "line": 5, "message": "m"},
        {"rule": "java:S109", "file_path": "A.java", "line": 9, "message": "m"},
        {"rule": "java:S109", "file_path": "B.java", "line": 3, "message": "m"},
    ]
    out = classify_and_rank(issues, "Math", "2")
    assert len(out) == 2
    assert out[0].file_path == "A.java"
    assert out[0].score == 2
    assert out[0].line == 5
    assert out[0].signal == "2 issues"
    assert out[0].refatoracao_tipo == "ReplaceMagicNumber"


def test_polymorphism_aggregates_both_rules_per_file():
    issues = [
        {"rule": "java:S1151", "file_path": "C.java", "line": 7, "message": "m"},
        {"rule": "java:S1142", "file_path": "C.java", "line": 8, "message": "m"},
    ]
    out = classify_and_rank(issues, "Cli", "1")
    assert len(out) == 1
    assert out[0].refatoracao_tipo == "ReplaceConditionalWithPolymorphism"
    assert out[0].score == 2


def test_unknown_rule_ignored():
    issues = [{"rule": "java:S9999", "file_path": "X.java", "line": 1, "message": "m"}]
    assert classify_and_rank(issues, "Lang", "1") == []
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `python -m pytest tests/test_candidate_ranker.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'core.candidate_ranker'`

- [ ] **Step 3: Implement the module**

Create `core/candidate_ranker.py`:

```python
import re
from dataclasses import dataclass

RULE_MAP = {
    "ExtractMethod": ["java:S3776"],
    "ReplaceMagicNumber": ["java:S109"],
    "ReplaceConditionalWithPolymorphism": ["java:S1151", "java:S1142"],
}

ALL_RULES = [rule for rules in RULE_MAP.values() for rule in rules]
_RULE_TO_TYPE = {rule: rtype for rtype, rules in RULE_MAP.items() for rule in rules}

_COMPLEXITY_RE = re.compile(r"Complexity from (\d+)")


@dataclass
class Candidate:
    trecho: str
    project: str
    version: str
    file_path: str
    refatoracao_tipo: str
    line: int | None
    score: int
    signal: str


def parse_complexity(message: str) -> int | None:
    if not message:
        return None
    m = _COMPLEXITY_RE.search(message)
    return int(m.group(1)) if m else None


def classify_and_rank(issues: list, project: str, version: str) -> list:
    extract = []                      # ExtractMethod: one entry per issue
    aggregates = {}                   # (rtype, file_path) -> {"count", "line"}

    for issue in issues:
        rtype = _RULE_TO_TYPE.get(issue.get("rule"))
        if rtype is None:
            continue
        file_path = issue.get("file_path")
        line = issue.get("line")
        if rtype == "ExtractMethod":
            complexity = parse_complexity(issue.get("message", "")) or 0
            extract.append({"rtype": rtype, "file_path": file_path, "line": line,
                            "score": complexity, "signal": f"cognitive_complexity={complexity}"})
        else:
            key = (rtype, file_path)
            agg = aggregates.setdefault(key, {"count": 0, "line": line})
            agg["count"] += 1
            if agg["line"] is None:
                agg["line"] = line

    raw = list(extract)
    for (rtype, file_path), agg in aggregates.items():
        raw.append({"rtype": rtype, "file_path": file_path, "line": agg["line"],
                    "score": agg["count"], "signal": f"{agg['count']} issues"})

    candidates = []
    for rtype in RULE_MAP:            # deterministic type order
        group = [r for r in raw if r["rtype"] == rtype]
        group.sort(key=lambda r: r["score"], reverse=True)
        for idx, r in enumerate(group, start=1):
            candidates.append(Candidate(
                trecho=f"{project}_{version}_{rtype}_{idx}",
                project=project, version=version,
                file_path=r["file_path"], refatoracao_tipo=rtype,
                line=r["line"], score=r["score"], signal=r["signal"],
            ))
    return candidates
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `python -m pytest tests/test_candidate_ranker.py -v`
Expected: PASS (7 tests)

- [ ] **Step 5: Commit**

```bash
git add core/candidate_ranker.py tests/test_candidate_ranker.py
git commit -m "feat: add candidate_ranker for classifying SonarQube issues"
```

---

### Task 3: `Defects4JManager.export_source_dir`

**Files:**
- Modify: `core/defects4j_mgr.py` (add method to `Defects4JManager`)
- Test: `tests/test_defects4j_mgr.py` (append tests)

**Interfaces:**
- Consumes: existing `self.d4j_bin`, `self._get_env()`.
- Produces: `export_source_dir(self, workspace_path: str) -> str` (returns export stdout stripped, else fallback `"src/main/java"`).

- [ ] **Step 1: Write the failing tests**

Append to `tests/test_defects4j_mgr.py` (ensure `from unittest.mock import patch, MagicMock` and `from core.defects4j_mgr import Defects4JManager` exist; add if missing):

```python
def test_export_source_dir_success():
    mgr = Defects4JManager(d4j_bin="defects4j")
    fake = MagicMock(returncode=0, stdout=b"src/main/java\n", stderr=b"")
    with patch("subprocess.run", return_value=fake):
        assert mgr.export_source_dir("/ws") == "src/main/java"


def test_export_source_dir_fallback_on_failure():
    mgr = Defects4JManager(d4j_bin="defects4j")
    fake = MagicMock(returncode=1, stdout=b"", stderr=b"boom")
    with patch("subprocess.run", return_value=fake):
        assert mgr.export_source_dir("/ws") == "src/main/java"
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `python -m pytest tests/test_defects4j_mgr.py -k export_source_dir -v`
Expected: FAIL with `AttributeError: ... has no attribute 'export_source_dir'`

- [ ] **Step 3: Implement the method**

Add to the `Defects4JManager` class in `core/defects4j_mgr.py` (after `export_classes_dir`):

```python
    def export_source_dir(self, workspace_path: str) -> str:
        cmd = [self.d4j_bin, "export", "-p", "dir.src.classes"]
        logger = _get_logger()
        logger.info(f"Running command: {' '.join(cmd)} in {workspace_path}")
        try:
            res = subprocess.run(cmd, cwd=workspace_path, env=self._get_env(), capture_output=True)
            stdout = res.stdout.decode('utf-8', errors='replace')
            stderr = res.stderr.decode('utf-8', errors='replace')
            logger.debug(f"Command stdout: {stdout}")
            logger.debug(f"Command stderr: {stderr}")
            if res.returncode == 0:
                return stdout.strip()
        except Exception as e:
            logger.error(f"Error executing export: {e}")
        return "src/main/java"
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `python -m pytest tests/test_defects4j_mgr.py -k export_source_dir -v`
Expected: PASS (2 tests)

- [ ] **Step 5: Commit**

```bash
git add core/defects4j_mgr.py tests/test_defects4j_mgr.py
git commit -m "feat: add Defects4JManager.export_source_dir"
```

---

### Task 4: `find_candidates.py` orchestrator

**Files:**
- Create: `find_candidates.py`
- Create: `config/candidate_targets.json`
- Test: `tests/test_find_candidates.py`

**Interfaces:**
- Consumes: `Defects4JManager` (`checkout`, `compile`, `export_source_dir`, `export_classes_dir`), `SonarQubeManager` (`scan`, `wait_for_task`, `search_issues`, `delete_project`), `candidate_ranker.classify_and_rank`, `candidate_ranker.ALL_RULES`.
- Produces: `find_candidates(settings_path: str, targets_path: str, output_path: str = "candidates.json") -> dict` (keys = refactoring types, values = list of candidate dicts sorted by score desc). Writes `output_path` JSON.

- [ ] **Step 1: Write the failing tests**

Create `tests/test_find_candidates.py`:

```python
import os
import json
from unittest.mock import patch


def _write_inputs(tmp_path, targets):
    settings = {
        "sonar_url": "http://localhost:9000", "sonar_token": "t",
        "sonar_scanner_path": "sonar-scanner",
        "defects4j_path": "defects4j", "java_home": None,
        "workspace_root": str(tmp_path / "ws"),
    }
    sp = tmp_path / "settings.json"
    tp = tmp_path / "targets.json"
    sp.write_text(json.dumps(settings))
    tp.write_text(json.dumps(targets))
    return str(sp), str(tp)


def test_find_candidates_happy_path(tmp_path):
    sp, tp = _write_inputs(tmp_path, [{"project": "Lang", "version": "1"}])
    out_path = str(tmp_path / "candidates.json")

    issues = [
        {"rule": "java:S3776", "file_path": "A.java", "line": 10,
         "message": "Cognitive Complexity from 30 to the 15 allowed."},
        {"rule": "java:S109", "file_path": "B.java", "line": 4, "message": "m"},
    ]

    with patch("core.defects4j_mgr.Defects4JManager") as d4j_cls, \
         patch("core.sonarqube_anal.SonarQubeManager") as sonar_cls:
        d4j = d4j_cls.return_value
        d4j.compile.return_value = True
        d4j.export_source_dir.return_value = "src"
        d4j.export_classes_dir.return_value = "bin"
        sonar = sonar_cls.return_value
        sonar.wait_for_task.return_value = True
        sonar.search_issues.return_value = issues

        from find_candidates import find_candidates
        result = find_candidates(sp, tp, output_path=out_path)

    assert "ExtractMethod" in result
    assert result["ExtractMethod"][0]["file_path"] == "A.java"
    assert result["ExtractMethod"][0]["score"] == 30
    assert result["ReplaceMagicNumber"][0]["file_path"] == "B.java"
    d4j.checkout.assert_called_once()
    sonar.delete_project.assert_called_once()
    assert os.path.exists(out_path)
    with open(out_path) as f:
        assert "ExtractMethod" in json.load(f)


def test_find_candidates_skips_failed_target(tmp_path):
    sp, tp = _write_inputs(tmp_path, [
        {"project": "Bad", "version": "1"},
        {"project": "Good", "version": "1"},
    ])
    out_path = str(tmp_path / "candidates.json")

    with patch("core.defects4j_mgr.Defects4JManager") as d4j_cls, \
         patch("core.sonarqube_anal.SonarQubeManager") as sonar_cls:
        d4j = d4j_cls.return_value
        # First target checkout raises; second succeeds.
        d4j.checkout.side_effect = [RuntimeError("checkout failed"), None]
        d4j.compile.return_value = True
        d4j.export_source_dir.return_value = "src"
        d4j.export_classes_dir.return_value = "bin"
        sonar = sonar_cls.return_value
        sonar.wait_for_task.return_value = True
        sonar.search_issues.return_value = [
            {"rule": "java:S109", "file_path": "G.java", "line": 1, "message": "m"},
        ]

        from find_candidates import find_candidates
        result = find_candidates(sp, tp, output_path=out_path)

    assert d4j.checkout.call_count == 2
    assert result["ReplaceMagicNumber"][0]["project"] == "Good"
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `python -m pytest tests/test_find_candidates.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'find_candidates'`

- [ ] **Step 3: Create the example targets config**

Create `config/candidate_targets.json`:

```json
[
  {"project": "Lang", "version": "1"},
  {"project": "Math", "version": "1"},
  {"project": "Cli", "version": "1"}
]
```

- [ ] **Step 4: Implement the orchestrator**

Create `find_candidates.py`:

```python
import os
import sys
import json
import logging

import core.defects4j_mgr
import core.sonarqube_anal
import core.candidate_ranker

logging.basicConfig(level=logging.INFO, format="%(asctime)s - %(levelname)s - %(message)s")
logger = logging.getLogger("find_candidates")


def find_candidates(settings_path: str, targets_path: str, output_path: str = "candidates.json") -> dict:
    with open(settings_path, encoding="utf-8") as f:
        settings = json.load(f)
    with open(targets_path, encoding="utf-8") as f:
        targets = json.load(f)

    d4j = core.defects4j_mgr.Defects4JManager(
        d4j_bin=settings["defects4j_path"], java_home=settings.get("java_home"))
    sonar = core.sonarqube_anal.SonarQubeManager(
        url=settings["sonar_url"], token=settings["sonar_token"],
        scanner_bin=settings["sonar_scanner_path"])
    workspace_root = settings["workspace_root"]

    all_candidates = []
    for target in targets:
        project = target["project"]
        version = str(target["version"])
        project_key = f"finder_{project}_{version}"
        workspace = os.path.join(workspace_root, project_key)
        try:
            logger.info(f"Checking out {project} v{version}")
            d4j.checkout(project, version, workspace)
            if not d4j.compile(workspace):
                logger.warning(f"Compile failed for {project} v{version}; skipping (degraded analysis).")
                continue
            src = os.path.abspath(os.path.join(workspace, d4j.export_source_dir(workspace)))
            classes = os.path.abspath(os.path.join(workspace, d4j.export_classes_dir(workspace)))
            sonar.scan(project_key, project, src, classes)
            if not sonar.wait_for_task(project_key):
                logger.warning(f"SonarQube task failed for {project_key}; skipping.")
                continue
            issues = sonar.search_issues(project_key, core.candidate_ranker.ALL_RULES)
            cands = core.candidate_ranker.classify_and_rank(issues, project, version)
            all_candidates.extend(cands)
            logger.info(f"{project} v{version}: {len(cands)} candidates")
        except Exception as e:
            logger.error(f"Target {project} v{version} failed: {e}")
            continue
        finally:
            try:
                sonar.delete_project(project_key)
            except Exception:
                pass

    by_type = {}
    for c in all_candidates:
        by_type.setdefault(c.refatoracao_tipo, []).append(c)

    output = {}
    for rtype, items in by_type.items():
        items.sort(key=lambda c: c.score, reverse=True)
        output[rtype] = [{
            "trecho": c.trecho, "project": c.project, "version": c.version,
            "file_path": c.file_path, "refatoracao_tipo": c.refatoracao_tipo,
            "line": c.line, "score": c.score, "signal": c.signal,
        } for c in items]

    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(output, f, indent=2)

    for rtype, items in output.items():
        print(f"\n=== {rtype} (top 5 of {len(items)}) ===")
        for c in items[:5]:
            print(f"  [{c['score']}] {c['project']}:{c['file_path']}:{c['line']} ({c['signal']})")

    return output


if __name__ == "__main__":
    settings_arg = sys.argv[1] if len(sys.argv) > 1 else "config/settings.json"
    targets_arg = sys.argv[2] if len(sys.argv) > 2 else "config/candidate_targets.json"
    find_candidates(settings_arg, targets_arg)
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `python -m pytest tests/test_find_candidates.py -v`
Expected: PASS (2 tests)

- [ ] **Step 6: Run full suite (no regressions)**

Run: `python -m pytest -q`
Expected: only the 4 pre-existing `test_defects4j_mgr.py` Windows/POSIX failures (`os.getpgid`, java_home path asserts); everything else green.

- [ ] **Step 7: Commit**

```bash
git add find_candidates.py config/candidate_targets.json tests/test_find_candidates.py
git commit -m "feat: add find_candidates orchestrator for snippet discovery"
```

---

## How to run (after implementation)

```bash
# one-time prereq: clone the project repos defects4j checkout needs
./defects4j/project_repos/get_repos.sh
# point settings.defects4j_path at the local bin: ./defects4j/framework/bin/defects4j
python find_candidates.py config/settings.json config/candidate_targets.json
# review candidates.json, trim to 10 per type, copy confirmed rows into config/snippets.json
```

## Self-Review notes

- **Spec coverage:** search_issues (Task 1), candidate_ranker incl. RULE_MAP/parse_complexity/granularity (Task 2), export_source_dir (Task 3), orchestrator + targets config + resilience + candidates.json (Task 4). All spec sections covered.
- **Type consistency:** issue dict keys (`rule, file_path, line, message`) consistent between Task 1 output and Task 2 input; `ALL_RULES`, `classify_and_rank`, `Candidate` names consistent across Tasks 2 and 4.
- **Polymorphism low-confidence** is documented in the spec; ranker still emits candidates for manual confirmation.
```

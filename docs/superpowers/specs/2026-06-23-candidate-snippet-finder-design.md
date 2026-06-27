# Candidate Snippet Finder — Design

**Date:** 2026-06-23
**Status:** Approved (design phase)

## 1. Purpose

Automate SonarQube-driven discovery of refactoring-opportunity candidates from
the **fixed** versions of local Defects4J projects, producing a ranked and
classified candidate list. A researcher then manually confirms candidates into
the final 30 snippets (`config/snippets.json`).

This matches the paper's protocol: candidates are *identified by SonarQube
indicators and confirmed manually*. The tool does NOT auto-select the final 30 —
it ranks; the human confirms.

The 30 final snippets = 10 per refactoring type × 3 types:
`ExtractMethod`, `ReplaceMagicNumber`, `ReplaceConditionalWithPolymorphism`.

## 2. Inputs

- `config/settings.json` (existing): `sonar_url`, `sonar_token`,
  `sonar_scanner_path`, `defects4j_path`, `java_home`, `workspace_root`.
  - `defects4j_path` points at the local install: `./defects4j/framework/bin/defects4j`.
- `config/candidate_targets.json` (new): list of `{project, version}` pairs to scan.
  ```json
  [
    {"project": "Lang", "version": "1"},
    {"project": "Math", "version": "1"},
    {"project": "Cli",  "version": "1"}
  ]
  ```
  Valid projects (local install): Chart, Cli, Closure, Codec, Collections,
  Compress, Csv, Gson, JacksonCore, JacksonDatabind, JacksonXml, Jsoup, JxPath,
  Lang, Math, Mockito, Time.

## 3. Rule → refactoring-type mapping

| Type | SonarQube rules | Score (desc) | Confidence |
| --- | --- | --- | --- |
| `ExtractMethod` | `java:S3776` (Cognitive Complexity) | parsed complexity value from message | high |
| `ReplaceMagicNumber` | `java:S109` | count of magic-number issues per file | high |
| `ReplaceConditionalWithPolymorphism` | `java:S1151`, `java:S1142` | count of issues per file | **low — weakest automated signal; SonarQube has no direct "type-switch" rule. Leans most on manual confirmation.** |

## 4. Components (isolated, independently testable)

### 4.1 `SonarQubeManager.search_issues(component_key, rules)` — new method
- `GET /api/issues/search?componentKeys=<key>&rules=<csv>&ps=500&p=<page>`.
- Paginates until all issues fetched (SonarQube caps `ps` at 500). Safety cap on
  total pages to avoid runaway.
- Returns `list[dict]`: `{"rule", "file_path", "line", "message", "effort"}`.
  - `file_path` = `component` with leading `"<projectKey>:"` stripped.
- Error handling mirrors existing methods: 5xx → `SonarQubeServerError`; non-200
  → `SonarQubeConnectionError`; network error → `SonarQubeServerError`.

### 4.2 `core/candidate_ranker.py` — new pure module (no I/O)
- `RULE_MAP: dict[str, list[str]]` (type → rules) and reverse lookup.
- `parse_complexity(message: str) -> int | None`: extracts the cognitive
  complexity value from S3776 messages ("...from 27 to the 15 allowed" → 27).
- `classify_and_rank(issues, project, version) -> list[Candidate]`:
  - maps each issue's rule to its refactoring type,
  - **granularity differs by type**: `ExtractMethod` = one candidate per issue
    (each S3776 issue is one method, `line` = that method), score = parsed
    complexity; `ReplaceMagicNumber` / `ReplaceConditionalWithPolymorphism` =
    one candidate per `file_path` (aggregated), score = issue count, `line` =
    line of the first issue in that file,
  - returns `Candidate` objects sorted by type then score desc.
- `Candidate` (dataclass): `project, version, file_path, refatoracao_tipo, line,
  score, signal`, plus `trecho` id = `f"{project}_{version}_{type}_{idx}"`.
  - Carries the `snippets.json` fields (`trecho, project, version, file_path,
    refatoracao_tipo`) so confirmed rows copy straight across, plus ranking
    metadata (`line, score, signal`).

### 4.3 `Defects4JManager.export_source_dir(workspace)` — new method
- Mirrors existing `export_classes_dir`; runs `defects4j export -p dir.src.classes`.
- Returns the source root (for scanning the whole tree); falls back sensibly.

### 4.4 `find_candidates.py` — orchestration script (repo root, like `main.py`)
Per target:
1. `d4j.checkout(project, version, workspace)` → fixed version.
2. `d4j.compile(workspace)` → bytecode for accurate Java analysis. Compile fails →
   log warning, **skip target** (degraded analysis not worth it).
3. `src = d4j.export_source_dir(workspace)`; `bin = d4j.export_classes_dir(workspace)`.
4. `sonar.scan(project_key, project_name, src, bin)` (whole source tree).
5. `sonar.wait_for_task(project_key)`.
6. `issues = sonar.search_issues(project_key, ALL_RULES)`.
7. `candidates += ranker.classify_and_rank(issues, project, version)`.
8. `sonar.delete_project(project_key)` (cleanup, like `main.py`).

Resilience: any per-target failure (checkout/compile/scan/network) is logged and
the target is skipped — the run continues. Aggregate all candidates, sort per
type by score desc, write `candidates.json`, print top-N per type to console.

## 5. Data flow

```
candidate_targets.json
  └─> per target: checkout → compile → scan → wait → search_issues
        └─> raw issues → candidate_ranker → Candidate[]
  └─> aggregate + rank → candidates.json
        └─> (human trims to 10/type) → config/snippets.json
```

## 6. Out of scope (YAGNI)

- No auto-trim to 10 (manual confirmation is the protocol).
- No bug-id enumeration (targets are manual config).
- No method-level AST parsing (rely on SonarQube issue line + manual confirmation).

## 7. Prerequisites (operational, not code)

- `defects4j/project_repos/` must be initialized (`./defects4j/project_repos/get_repos.sh`)
  before `checkout` works — currently only `get_repos.sh` is present.
- Working Defects4J toolchain (Perl 5, Java 8) and a running SonarQube server.

## 8. Testing (TDD)

- `search_issues`: single page; pagination; `file_path` prefix strip; 5xx error;
  empty result (mocked `requests.get`).
- `candidate_ranker`: `parse_complexity` (valid/invalid); grouping, scoring,
  ordering; rule→type mapping; `trecho` id generation (pure-function tests).
- `export_source_dir`: success + fallback (mocked `subprocess`).
- `find_candidates`: managers mocked — happy-path flow, `candidates.json` output,
  per-target resilience (one target raises → others still processed).

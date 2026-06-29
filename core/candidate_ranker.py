from __future__ import annotations

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

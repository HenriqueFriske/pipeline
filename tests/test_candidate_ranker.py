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

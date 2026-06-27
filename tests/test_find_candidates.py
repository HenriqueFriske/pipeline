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
    sonar.delete_project.assert_called_once_with("finder_Lang_1")
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
    sonar.delete_project.assert_called_once_with("finder_Good_1")
    assert sonar.delete_project.call_count == 1

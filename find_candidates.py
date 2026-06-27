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
        d4j_bin=settings["defects4j_path"], java_home=settings.get("java_home"),
        perl5lib=settings.get("perl5lib"))
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
        scanned = False
        try:
            logger.info(f"Checking out {project} v{version}")
            d4j.checkout(project, version, workspace)
            if not d4j.compile(workspace):
                logger.warning(f"Compile failed for {project} v{version}; skipping (degraded analysis).")
                continue
            src = os.path.abspath(os.path.join(workspace, d4j.export_source_dir(workspace)))
            classes = os.path.abspath(os.path.join(workspace, d4j.export_classes_dir(workspace)))
            sonar.scan(project_key, project, src, classes)
            scanned = True
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
            if scanned:
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

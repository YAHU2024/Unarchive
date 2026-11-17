#!/usr/bin/env python3
"""Summarize explicitly selected acceptance artifacts without running acceptance.

The manifest is an evidence index, not an authority to run tests or live services.
Only fixed, allow-listed fields are exported. Historical provenance is unknown
unless a separate context was recorded for that run; capture never backfills it.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Any

KINDS = {"junit", "build", "device", "process", "release", "live", "visual", "manual"}
AUTOMATED = {"junit", "build", "device", "process", "release"}
STATUSES = {"passed", "failed", "not_run", "blocked"}
IDENTIFIER = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,127}\Z")
SHA256 = re.compile(r"[a-fA-F0-9]{64}\Z")


class EvidenceError(ValueError):
    """Fixed safe diagnostic, deliberately excluding source exception details."""


def require(condition: bool, code: str) -> None:
    if not condition:
        raise EvidenceError(code)


def identifier(value: Any, code: str = "invalid_identifier") -> str:
    require(isinstance(value, str) and IDENTIFIER.fullmatch(value) is not None, code)
    return value


def integer(value: Any) -> int:
    require(type(value) is int and value >= 0, "invalid_count")
    return value


def read_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8-sig"))
    except (OSError, UnicodeError, ValueError):
        raise EvidenceError("unreadable_json") from None
    require(isinstance(value, dict), "json_object_required")
    return value


def digest(path: Path) -> str:
    hasher = hashlib.sha256()
    try:
        with path.open("rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                hasher.update(chunk)
    except OSError:
        raise EvidenceError("unreadable_evidence") from None
    return hasher.hexdigest()


def path_from(value: Any, base: Path) -> Path:
    require(isinstance(value, str) and bool(value.strip()), "invalid_evidence_path")
    path = Path(value)
    return (base / path).resolve() if not path.is_absolute() else path.resolve()


def counts(total: Any, passed: Any, failed: Any, skipped: Any) -> dict[str, int]:
    result = dict(zip(("total", "passed", "failed", "skipped"),
                      map(integer, (total, passed, failed, skipped))))
    require(result["total"] == result["passed"] + result["failed"] + result["skipped"],
            "inconsistent_counts")
    return result


def count_status(value: dict[str, int]) -> str:
    if value["failed"]:
        return "failed"
    if value["skipped"]:
        return "incomplete"
    return "passed" if value["total"] else "not_run"


def junit(path: Path) -> dict[str, Any]:
    files = sorted(path.rglob("TEST-*.xml")) if path.is_dir() else [path]
    require(bool(files), "no_junit_reports")
    total = failures = skipped = 0
    combined = hashlib.sha256()
    for file in files:
        require(file.is_file(), "junit_file_required")
        label = file.relative_to(path).as_posix() if path.is_dir() else file.name
        combined.update(label.encode("utf-8") + b"\0" + bytes.fromhex(digest(file)))
        try:
            root = ET.parse(file).getroot()
            suites = [node for node in root.iter("testsuite") if not node.findall("testsuite")]
            require(bool(suites), "invalid_junit")
            for suite in suites:
                values = [int(suite.get(key, "0")) for key in ("tests", "failures", "errors", "skipped")]
                for value in values:
                    integer(value)
                current, failed, errors, ignored = values
                require(failed + errors + ignored <= current, "inconsistent_junit_counts")
                cases = suite.findall("testcase")
                if cases:
                    require(len(cases) == current, "inconsistent_junit_cases")
                    require(sum(case.find("failure") is not None for case in cases) == failed and
                            sum(case.find("error") is not None for case in cases) == errors and
                            sum(case.find("skipped") is not None for case in cases) == ignored,
                            "inconsistent_junit_cases")
                total += current
                failures += failed + errors
                skipped += ignored
        except (OSError, ET.ParseError, ValueError):
            raise EvidenceError("invalid_junit") from None
    values = counts(total, total - failures - skipped, failures, skipped)
    return {"status": count_status(values), "counts": values, "reportFiles": len(files),
            "evidenceSha256": combined.hexdigest()}


def build(path: Path) -> dict[str, Any]:
    try:
        text = path.read_text(encoding="utf-8-sig", errors="replace")
    except OSError:
        raise EvidenceError("unreadable_build_log") from None
    markers = re.findall(r"(?m)^BUILD (SUCCESSFUL|FAILED)\b", text)
    require(bool(markers), "build_completion_missing")
    # A file containing several invocations is not an unambiguous build result.
    require(len(markers) == 1, "multiple_build_completions")
    return {"status": "passed" if markers[0] == "SUCCESSFUL" else "failed",
            "scope": "one Gradle completion; task selection is not inferred"}


def device(report: dict[str, Any]) -> dict[str, Any]:
    values = counts(*(report.get(key) for key in
                      ("testCount", "passedCount", "failedCount", "skippedCount")))
    require(type(report.get("automatedPassed")) is bool, "device_completion_missing")
    status = count_status(values)
    require(not report["automatedPassed"] or status == "passed", "device_completion_conflict")
    if status == "passed" and not report["automatedPassed"]:
        status = "incomplete"
    return {"status": status, "counts": values}


def process(report: dict[str, Any]) -> dict[str, Any]:
    keys = ("seedPassed", "verifyPassed", "forceStopExecuted", "targetAbsentAfterForceStop",
            "processIdsDiffer", "automatedPassed")
    require(all(type(report.get(key)) is bool for key in keys), "process_completion_missing")
    phases = report.get("phases")
    require(isinstance(phases, list) and len(phases) == 2, "process_phases_missing")
    require({phase.get("name") for phase in phases if isinstance(phase, dict)} == {"seed", "verify"},
            "process_phases_invalid")
    phase_passed = all(type(phase.get("passed")) is bool and phase["passed"] and
                       phase.get("testCount") == 1 and phase.get("instrumentationExitCode") == 0
                       for phase in phases)
    passed = all(report[key] for key in keys) and phase_passed
    return {"status": "passed" if passed else "failed", "phaseCount": len(phases),
            "scope": "actual force-stop/restart evidence; not system Picker or live-service acceptance"}


def release(report: dict[str, Any]) -> dict[str, Any]:
    metadata, scenarios = report.get("metadata"), report.get("scenarios")
    require(isinstance(metadata, dict) and isinstance(scenarios, dict) and bool(scenarios),
            "release_scenarios_missing")
    require(type(metadata.get("measurementsCompleted")) is bool, "release_completion_missing")
    results = []
    for name, scenario in scenarios.items():
        identifier(name, "invalid_scenario_identifier")
        require(isinstance(scenario, dict) and type(scenario.get("passed")) is bool,
                "release_scenario_invalid")
        require(scenario.get("runId") == metadata.get("runId"), "release_scenario_run_mismatch")
        rounds = scenario.get("roundMeasurements")
        require(isinstance(rounds, list) and bool(rounds), "release_rounds_missing")
        require(len(rounds) == integer(metadata.get("rounds")) and
                len(rounds) == integer(scenario.get("rounds")), "release_round_count_mismatch")
        inputs = scrolls = 0
        for sample in rounds:
            require(isinstance(sample, dict) and type(sample.get("passed")) is bool,
                    "release_round_invalid")
            values = sample.get("inputs")
            require(isinstance(values, list) and bool(values), "release_inputs_missing")
            gestures = sample.get("readingScrollMeasurements", [])
            require(isinstance(gestures, list), "release_scrolls_invalid")
            inputs += len(values)
            scrolls += len(gestures)
        passed = scenario["passed"] and all(sample["passed"] for sample in rounds)
        results.append({"id": name, "status": "passed" if passed else "failed",
                        "rounds": len(rounds), "inputSamples": inputs, "readingGesturePhases": scrolls})
    return {"status": "passed" if metadata["measurementsCompleted"] and
            all(item["status"] == "passed" for item in results) else "failed",
            "scenarios": results, "scope": "measurement completed; no latency or human acceptance inferred"}


def live(report: dict[str, Any]) -> dict[str, Any]:
    require(type(report.get("passed")) is bool, "live_completion_missing")
    skipped = report.get("skipped", False)
    require(type(skipped) is bool and not (skipped and report["passed"]), "live_completion_conflict")
    return {"status": "incomplete" if skipped else "passed" if report["passed"] else "failed",
            "scope": "only explicitly recorded service check; no remote rendering or quality inferred"}


def declared(report: dict[str, Any]) -> dict[str, Any]:
    status = report.get("status")
    require(isinstance(status, str) and status in STATUSES, "invalid_declared_status")
    evidence = report.get("evidence", [])
    require(isinstance(evidence, list) and all(isinstance(item, str) for item in evidence),
            "invalid_declared_evidence")
    require(status not in {"passed", "failed"} or bool(evidence), "declared_evidence_missing")
    # References are not inspected as proof of pixels or a human decision.
    return {"status": status, "evidenceReferences": evidence,
            "scope": "operator-declared result; not automatically verified visual fidelity"}


def provenance(path: Path | None) -> dict[str, Any]:
    if path is None:
        return {"status": "unknown", "scope": "not recorded for this run; never backfilled"}
    require(path.is_file(), "provenance_file_missing")
    report = read_json(path)
    output: dict[str, Any] = {"status": "recorded", "contextSha256": digest(path),
                              "scope": "recorded context; does not prove installed-device APK identity"}
    for key in ("gitHead", "sourceFingerprint", "appApkSha256", "testApkSha256", "buildType"):
        if key not in report:
            continue
        value = report[key]
        if key == "gitHead":
            require(isinstance(value, str) and re.fullmatch(r"[a-fA-F0-9]{40}|[a-fA-F0-9]{64}", value) is not None,
                    "invalid_provenance_hash")
        elif key == "buildType":
            identifier(value, "invalid_build_type")
        else:
            require(isinstance(value, str) and SHA256.fullmatch(value) is not None,
                    "invalid_provenance_hash")
        output[key] = value
    require("gitHead" in output and "sourceFingerprint" in output, "provenance_identity_missing")
    return output


def aggregate_status(checks: list[dict[str, Any]]) -> str:
    if not checks:
        return "not_run"
    statuses = {check["status"] for check in checks}
    for state in ("error", "failed", "blocked", "incomplete", "not_run"):
        if state in statuses:
            return state
    return "passed"


def summarize(manifest_path: Path) -> dict[str, Any]:
    manifest = read_json(manifest_path)
    require(type(manifest.get("schemaVersion")) is int and manifest["schemaVersion"] == 1,
            "unsupported_manifest_version")
    task_id = identifier(manifest.get("taskId"), "invalid_task_id")
    requirements = manifest.get("requirementIds", [])
    require(isinstance(requirements, list), "invalid_requirement_ids")
    requirements = [identifier(item) for item in requirements]
    specifications = manifest.get("checks")
    require(isinstance(specifications, list) and bool(specifications), "checks_required")
    checks: list[dict[str, Any]] = []
    seen = set()
    for spec in specifications:
        require(isinstance(spec, dict), "invalid_check")
        check_id = identifier(spec.get("id"))
        require(check_id not in seen, "duplicate_check_id")
        seen.add(check_id)
        kind = spec.get("kind")
        require(isinstance(kind, str) and kind in KINDS, "unsupported_evidence_kind")
        required = spec.get("required", True)
        require(type(required) is bool, "invalid_required_flag")
        path = path_from(spec.get("path"), manifest_path.parent)
        item: dict[str, Any] = {"id": check_id, "kind": kind, "required": required,
                                "evidence": str(path), "provenance": provenance(None)}
        try:
            if "provenance" in spec:
                item["provenance"] = provenance(path_from(spec["provenance"], manifest_path.parent))
            if not path.exists():
                item.update(status="not_run", reasonCode="evidence_missing")
            elif kind == "junit":
                require("runId" not in spec, "run_identity_not_in_junit")
                item.update(junit(path))
            else:
                require(path.is_file(), "evidence_file_required")
                item["evidenceSha256"] = digest(path)
                if kind == "build":
                    require("runId" not in spec, "run_identity_not_in_build_log")
                    item.update(build(path))
                else:
                    report = read_json(path)
                    metadata = report.get("metadata", report)
                    run_id = metadata.get("runId") if isinstance(metadata, dict) else None
                    if run_id is not None:
                        item["runId"] = identifier(run_id, "invalid_report_run_id")
                    if "runId" in spec:
                        require(identifier(spec["runId"]) == run_id, "evidence_run_mismatch")
                    if isinstance(metadata, dict):
                        artifacts = {}
                        for key in ("appApkSha256", "testApkSha256", "mappingSha256"):
                            if key in metadata:
                                value = metadata[key]
                                require(isinstance(value, str) and SHA256.fullmatch(value) is not None,
                                        "invalid_report_artifact_hash")
                                artifacts[key] = value
                        if artifacts:
                            item["reportedArtifactHashes"] = artifacts
                    parser = {"device": device, "process": process, "release": release,
                              "live": live, "visual": declared, "manual": declared}[kind]
                    item.update(parser(report))
        except EvidenceError as error:
            item.update(status="error", reasonCode=str(error))
        checks.append(item)
    return {"schemaVersion": 1, "taskId": task_id, "requirementIds": requirements,
            "requiredAutomatedGate": aggregate_status([check for check in checks if check["required"] and check["kind"] in AUTOMATED]),
            "overallRequiredChecks": aggregate_status([check for check in checks if check["required"]]),
            "manualAcceptance": [{"id": check["id"], "status": check["status"]}
                                 for check in checks if check["kind"] in {"manual", "visual"}],
            "liveServiceAcceptance": [{"id": check["id"], "status": check["status"]}
                                      for check in checks if check["kind"] == "live"],
            "limitations": ["No counts are added across checks or layers; overlapping cases are not unique totals.",
                            "Passed evidence does not imply complete requirements or user acceptance.",
                            "Source and APK provenance is unknown unless recorded for that execution.",
                            "The helper reads reports only; it never runs builds, devices or network services."],
            "checks": checks}


def markdown(summary: dict[str, Any]) -> str:
    lines = [f"# Acceptance evidence: {summary['taskId']}", "",
             f"Required automated gate: **{summary['requiredAutomatedGate']}**",
             f"All required checks: **{summary['overallRequiredChecks']}**", "",
             "These are evidence summaries, not a declaration of complete product acceptance.", "",
             "| Check | Kind | Required | Result | Counts / scope | Run | Provenance |",
             "|---|---|---|---|---|---|---|"]
    for check in summary["checks"]:
        count = check.get("counts")
        detail = (f"total {count['total']}; passed {count['passed']}; failed {count['failed']}; skipped {count['skipped']}"
                  if count else check.get("reasonCode", check.get("scope", "see JSON")))
        lines.append(f"| {check['id']} | {check['kind']} | {check['required']} | {check['status']} | {detail} | {check.get('runId', 'not recorded')} | {check['provenance']['status']} |")
    lines += ["", "Evidence paths and SHA256 are recorded in summary.json. No cross-check total is computed.", ""]
    lines += [f"- {limit}" for limit in summary["limitations"]]
    return "\n".join(lines) + "\n"


def write_summary(manifest: Path, output: Path) -> dict[str, Any]:
    require(not output.exists(), "output_already_exists")
    summary = summarize(manifest)
    # Do not create the output within an input directory (including JUnit roots).
    for check in summary["checks"]:
        evidence = Path(check["evidence"])
        require(not (output == evidence or evidence in output.parents), "output_inside_evidence")
    require(not (output == manifest or output in manifest.parents), "output_contains_manifest")
    try:
        output.mkdir(parents=True, exist_ok=False)
        (output / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        (output / "summary.md").write_text(markdown(summary), encoding="utf-8")
    except OSError:
        raise EvidenceError("output_write_failed") from None
    return summary


def capture_context(repo: Path, output: Path, app_apk: Path | None = None,
                    test_apk: Path | None = None) -> dict[str, Any]:
    require(not output.exists(), "output_already_exists")
    try:
        actual_root = subprocess.check_output(["git", "rev-parse", "--show-toplevel"], cwd=repo,
                                             stderr=subprocess.DEVNULL).decode().strip()
        root = Path(actual_root).resolve()
        head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root,
                                       stderr=subprocess.DEVNULL).decode().strip()
        listed = subprocess.check_output(["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard", "--", "android"],
                                         cwd=root, stderr=subprocess.DEVNULL)
    except (OSError, subprocess.SubprocessError, UnicodeError):
        raise EvidenceError("git_context_unavailable") from None
    hasher = hashlib.sha256()
    try:
        files = sorted(set(value.decode("utf-8") for value in listed.split(b"\0") if value))
    except UnicodeError:
        raise EvidenceError("source_path_encoding_invalid") from None
    require(bool(files), "android_sources_missing")
    for relative in files:
        path = (root / relative).resolve()
        require(root in path.parents, "source_path_outside_repository")
        hasher.update(relative.replace("\\", "/").encode("utf-8") + b"\0")
        if not path.exists():
            # git ls-files still includes a tracked deletion in the working tree.
            hasher.update(b"deleted\0")
        else:
            require(path.is_file(), "source_file_required")
            hasher.update(b"file\0" + bytes.fromhex(digest(path)))
    report = {"schemaVersion": 1, "gitHead": head, "sourceFingerprint": hasher.hexdigest(),
              "sourceFileCount": len(files), "scope": "pre-run source/artifact snapshot; not proof of test execution or installed APK"}
    for key, path in (("appApkSha256", app_apk), ("testApkSha256", test_apk)):
        if path is not None:
            report[key] = digest(path)
    try:
        output.parent.mkdir(parents=True, exist_ok=True)
        with output.open("x", encoding="utf-8") as stream:
            json.dump(report, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
    except OSError:
        raise EvidenceError("context_write_failed") from None
    return report


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path)
    parser.add_argument("--output-dir", type=Path)
    parser.add_argument("--capture-context", type=Path)
    parser.add_argument("--repo-root", type=Path, default=Path.cwd())
    parser.add_argument("--app-apk", type=Path)
    parser.add_argument("--test-apk", type=Path)
    args = parser.parse_args(argv)
    try:
        if args.capture_context is not None:
            require(args.manifest is None and args.output_dir is None, "choose_one_mode")
            capture_context(args.repo_root.resolve(), args.capture_context.resolve(), args.app_apk, args.test_apk)
            print("Context recorded; it is not acceptance evidence.")
            return 0
        require(args.manifest is not None and args.output_dir is not None, "manifest_and_output_required")
        require(args.app_apk is None and args.test_apk is None, "apk_options_require_capture")
        summary = write_summary(args.manifest.resolve(), args.output_dir.resolve())
        print(f"Evidence summary written. Required automated gate: {summary['requiredAutomatedGate']}.")
        return 0 if summary["overallRequiredChecks"] == "passed" else 1
    except EvidenceError as error:
        print(f"Acceptance summary error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())

"""Meaningful evidence-boundary tests; no Android build, device, or network."""
from __future__ import annotations

import contextlib
import io
import json
import subprocess
import tempfile
import unittest
from pathlib import Path

import summarize_acceptance as subject


class AcceptanceSummaryTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self) -> None:
        self.temp.cleanup()

    def write_json(self, name: str, value: dict) -> Path:
        path = self.root / name
        path.write_text(json.dumps(value), encoding="utf-8")
        return path

    def manifest(self, checks: list[dict]) -> Path:
        return self.write_json("manifest.json", {"schemaVersion": 1, "taskId": "R4-slice",
                                                "requirementIds": ["U14"], "checks": checks})

    def check(self, kind: str, path: str, **values) -> dict:
        return {"id": "check-" + kind, "kind": kind, "path": path, **values}

    def device(self, **values) -> dict:
        return {"runId": "run-1", "testCount": 3, "passedCount": 3,
                "failedCount": 0, "skippedCount": 0, "automatedPassed": True, **values}

    def summary(self, kind: str, report: dict, **check_values) -> dict:
        self.write_json("report.json", report)
        return subject.summarize(self.manifest([self.check(kind, "report.json", **check_values)]))

    def test_skip_is_incomplete_even_when_junit_would_say_ok(self) -> None:
        result = self.summary("device", self.device(passedCount=2, skippedCount=1, automatedPassed=False))
        self.assertEqual("incomplete", result["requiredAutomatedGate"])
        self.assertEqual({"total": 3, "passed": 2, "failed": 0, "skipped": 1}, result["checks"][0]["counts"])

    def test_count_mismatch_is_error_and_does_not_claim_pass(self) -> None:
        result = self.summary("device", self.device(passedCount=2))
        self.assertEqual("error", result["requiredAutomatedGate"])
        self.assertEqual("inconsistent_counts", result["checks"][0]["reasonCode"])

    def test_false_completion_is_not_turned_into_pass(self) -> None:
        result = self.summary("device", self.device(automatedPassed=False))
        self.assertEqual("incomplete", result["overallRequiredChecks"])

    def test_passing_device_report_with_skip_flag_conflict_is_error(self) -> None:
        result = self.summary("device", self.device(passedCount=2, skippedCount=1))
        self.assertEqual("error", result["overallRequiredChecks"])

    def test_zero_case_junit_is_not_run(self) -> None:
        (self.root / "TEST-empty.xml").write_text('<testsuite tests="0" failures="0" errors="0" skipped="0"/>')
        result = subject.summarize(self.manifest([self.check("junit", "TEST-empty.xml")]))
        self.assertEqual("not_run", result["requiredAutomatedGate"])

    def test_junit_failure_and_error_are_both_retained(self) -> None:
        (self.root / "TEST-a.xml").write_text('<testsuite tests="3" failures="1" errors="1" skipped="0">'
                                             '<testcase/><testcase><failure>PRIVATE_BODY</failure></testcase>'
                                             '<testcase><error>SECRET</error></testcase></testsuite>')
        result = subject.summarize(self.manifest([self.check("junit", "TEST-a.xml")]))
        self.assertEqual("failed", result["requiredAutomatedGate"])
        self.assertEqual(2, result["checks"][0]["counts"]["failed"])
        self.assertNotIn("PRIVATE_BODY", json.dumps(result))
        self.assertNotIn("SECRET", json.dumps(result))

    def test_junit_directory_digest_and_counts_are_deterministic(self) -> None:
        directory = self.root / "xml"
        directory.mkdir()
        for name in ["b", "a"]:
            (directory / f"TEST-{name}.xml").write_text('<testsuite tests="1"><testcase/></testsuite>')
        first = subject.junit(directory)
        self.assertEqual(first, subject.junit(directory))
        self.assertEqual(2, first["counts"]["passed"])
        (directory / "TEST-a.xml").write_text('<testsuite tests="1" skipped="1"><testcase><skipped/></testcase></testsuite>')
        self.assertNotEqual(first["evidenceSha256"], subject.junit(directory)["evidenceSha256"])

    def test_missing_evidence_is_not_run(self) -> None:
        result = subject.summarize(self.manifest([self.check("device", "absent.json")]))
        self.assertEqual("not_run", result["overallRequiredChecks"])

    def test_wrong_expected_run_identity_is_error(self) -> None:
        result = self.summary("device", self.device(), runId="other-run")
        self.assertEqual("error", result["overallRequiredChecks"])
        self.assertEqual("evidence_run_mismatch", result["checks"][0]["reasonCode"])

    def test_run_identity_cannot_be_invented_for_build_log(self) -> None:
        (self.root / "build.log").write_text("BUILD SUCCESSFUL in 3s\n")
        result = subject.summarize(self.manifest([self.check("build", "build.log", runId="invented")]))
        self.assertEqual("error", result["requiredAutomatedGate"])
        self.assertNotIn("runId", result["checks"][0])

    def test_build_requires_one_completion_and_does_not_export_log(self) -> None:
        path = self.root / "build.log"
        path.write_text("secret API_KEY=do-not-copy\nBUILD SUCCESSFUL in 3s\n")
        result = subject.summarize(self.manifest([self.check("build", "build.log")]))
        self.assertEqual("passed", result["requiredAutomatedGate"])
        self.assertNotIn("do-not-copy", json.dumps(result))
        path.write_text("BUILD FAILED in 3s\nBUILD SUCCESSFUL in 4s\n")
        self.assertEqual("error", subject.summarize(self.root / "manifest.json")["requiredAutomatedGate"])

    def test_release_requires_rounds_and_keeps_measurement_boundary(self) -> None:
        value = {"metadata": {"runId": "release-1", "measurementsCompleted": True, "rounds": 1},
                 "scenarios": {"long": {"runId": "release-1", "passed": True, "rounds": 1, "roundMeasurements": []}}}
        result = self.summary("release", value)
        self.assertEqual("error", result["requiredAutomatedGate"])
        value["scenarios"]["long"]["roundMeasurements"] = [
            {"passed": True, "inputs": [{"position": "head"}], "readingScrollMeasurements": [{}]}]
        result = self.summary("release", value)
        self.assertEqual("passed", result["requiredAutomatedGate"])
        self.assertEqual(1, result["checks"][0]["scenarios"][0]["inputSamples"])
        self.assertIn("no latency", result["checks"][0]["scope"])

    def test_process_requires_actual_restart_evidence(self) -> None:
        value = {key: True for key in ("seedPassed", "verifyPassed", "forceStopExecuted",
                                      "targetAbsentAfterForceStop", "processIdsDiffer", "automatedPassed")}
        value["runId"] = "process-1"
        value["phases"] = [{"name": name, "passed": True, "testCount": 1, "instrumentationExitCode": 0}
                           for name in ["seed", "verify"]]
        self.assertEqual("passed", self.summary("process", value)["requiredAutomatedGate"])
        value["targetAbsentAfterForceStop"] = False
        self.assertEqual("failed", self.summary("process", value)["requiredAutomatedGate"])

    def test_unknown_provenance_never_gets_current_commit(self) -> None:
        result = self.summary("device", self.device())
        self.assertEqual("unknown", result["checks"][0]["provenance"]["status"])
        self.assertNotIn("gitHead", result["checks"][0]["provenance"])

    def test_provenance_exports_only_safe_fields_and_checks_hashes(self) -> None:
        self.write_json("context.json", {"gitHead": "a" * 40, "sourceFingerprint": "b" * 64,
                                         "apiKey": "VERY_PRIVATE", "body": "DO_NOT_OUTPUT"})
        result = self.summary("device", self.device(), provenance="context.json")
        self.assertEqual("recorded", result["checks"][0]["provenance"]["status"])
        self.assertNotIn("VERY_PRIVATE", json.dumps(result))
        self.assertNotIn("DO_NOT_OUTPUT", json.dumps(result))
        self.write_json("context.json", {"gitHead": "not-a-hash", "sourceFingerprint": "b" * 64})
        self.assertEqual("error", self.summary("device", self.device(), provenance="context.json")["requiredAutomatedGate"])

    def test_manual_and_live_do_not_make_automated_gate_pass(self) -> None:
        self.write_json("manual.json", {"status": "not_run", "body": "PRIVATE"})
        self.write_json("live.json", {"passed": False, "skipped": True, "reason": "SECRET"})
        result = subject.summarize(self.manifest([self.check("manual", "manual.json"), self.check("live", "live.json")]))
        self.assertEqual("not_run", result["requiredAutomatedGate"])
        self.assertEqual("incomplete", result["overallRequiredChecks"])
        self.assertEqual("not_run", result["manualAcceptance"][0]["status"])
        self.assertNotIn("SECRET", json.dumps(result))

    def test_existing_output_is_not_overwritten(self) -> None:
        self.write_json("report.json", self.device())
        manifest = self.manifest([self.check("device", "report.json")])
        output = self.root / "summary"
        subject.write_summary(manifest, output)
        original = (output / "summary.json").read_bytes()
        with self.assertRaisesRegex(subject.EvidenceError, "output_already_exists"):
            subject.write_summary(manifest, output)
        self.assertEqual(original, (output / "summary.json").read_bytes())

    def test_no_summary_output_inside_junit_directory(self) -> None:
        path = self.root / "junit"
        path.mkdir()
        (path / "TEST-a.xml").write_text('<testsuite tests="1"><testcase/></testsuite>')
        with self.assertRaisesRegex(subject.EvidenceError, "output_inside_evidence"):
            subject.write_summary(self.manifest([self.check("junit", "junit")]), path / "output")

    def test_cli_malformed_report_does_not_echo_raw_secret(self) -> None:
        (self.root / "report.json").write_text('{"apiKey":"VERY_PRIVATE", invalid')
        manifest = self.manifest([self.check("device", "report.json")])
        stream = io.StringIO()
        with contextlib.redirect_stdout(stream), contextlib.redirect_stderr(stream):
            code = subject.main(["--manifest", str(manifest), "--output-dir", str(self.root / "out")])
        self.assertEqual(1, code)
        self.assertNotIn("VERY_PRIVATE", stream.getvalue())
        self.assertNotIn("VERY_PRIVATE", (self.root / "out" / "summary.json").read_text())

    def test_capture_hash_changes_for_source_not_ignored_files(self) -> None:
        repo = self.root / "repo"
        repo.mkdir()
        subprocess.run(["git", "init", "-q", str(repo)], check=True)
        subprocess.run(["git", "-C", str(repo), "config", "user.email", "synthetic@example.invalid"], check=True)
        subprocess.run(["git", "-C", str(repo), "config", "user.name", "Synthetic fixture"], check=True)
        (repo / "android").mkdir()
        source = repo / "android" / "source.kt"
        source.write_text("first")
        (repo / ".gitignore").write_text("android/secret.txt\n")
        subprocess.run(["git", "-C", str(repo), "add", "."], check=True)
        # Commits exist only inside this temporary synthetic repository.
        subprocess.run(["git", "-C", str(repo), "-c", "commit.gpgsign=false", "commit", "-qm", "synthetic fixture"], check=True)
        first = subject.capture_context(repo, self.root / "first.json")
        (repo / "android" / "secret.txt").write_text("DO_NOT_INCLUDE")
        second = subject.capture_context(repo, self.root / "second.json")
        self.assertEqual(first["sourceFingerprint"], second["sourceFingerprint"])
        source.write_text("changed")
        third = subject.capture_context(repo, self.root / "third.json")
        self.assertNotEqual(first["sourceFingerprint"], third["sourceFingerprint"])
        self.assertNotIn("DO_NOT_INCLUDE", json.dumps(third))
        source.unlink()
        deleted = subject.capture_context(repo, self.root / "deleted.json")
        self.assertNotEqual(third["sourceFingerprint"], deleted["sourceFingerprint"])


if __name__ == "__main__":
    unittest.main()

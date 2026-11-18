import copy
import hashlib
import json
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

from evaluate import (business_due, component, decide, evaluate, preserve_history, read_json,
                      validate_policy)

NOW = datetime(2026, 11, 17, 12, tzinfo=timezone.utc)


def finding():
    return {
        "id": "test-risk", "vulnerabilityId": "CVE-2026-12345", "aliases": ["GHSA-TEST-0001"],
        "component": "pkg:maven/example/library", "affectedVersions": ["1.0", "1.1"],
        "artifactScopes": ["orders:linux/amd64", "orders:linux/arm64"],
        "firstSeen": "2026-11-10T12:00:00Z", "owner": "@edgarrth",
        "acknowledgedAt": "2026-11-11T12:00:00Z", "triageDue": "2026-11-24T12:00:00Z",
        "dueAt": "2026-12-01T12:00:00Z", "nextMinorAt": "2026-12-01T12:00:00Z",
        "applicability": "applicable", "contextualRisk": "medium", "knownExploited": False,
        "treatment": "Upgrade the dependency", "status": "open", "evidence": ["issue:synthetic-test"],
        "exceptions": [],
    }


def policy():
    return {"schema": 2, "calendar": {"weekend": [6, 7], "holidays": []},
            "authorizedOwners": ["@edgarrth"], "findings": [finding()]}


def vulnerability():
    return {"vulnerabilityId": "CVE-2026-12345", "aliases": [], "component": "pkg:maven/example/library",
            "version": "1.0", "scope": "orders:linux/amd64", "severity": "MEDIUM"}


class PolicyTest(unittest.TestCase):
    def decide(self, p=None, v=None, history=None, now=NOW, minor=False):
        p = p or policy()
        validate_policy(p, now)
        return decide(v or vulnerability(), p, history or [], now, minor)

    def test_medium_high_and_critical_always_block_and_unknown_is_incomplete(self):
        self.assertEqual(self.decide(v={**vulnerability(), "severity": "UNKNOWN"})[0], "INCOMPLETE")
        for severity in ("MEDIUM", "HIGH", "CRITICAL"):
            self.assertEqual(self.decide(v={**vulnerability(), "severity": severity})[0], "BLOCK")
        self.assertEqual(self.decide(v={**vulnerability(), "severity": "LOW"})[0], "PASS")

    def test_new_medium_blocks_and_invalid_owner_fails_validation(self):
        p = policy()
        p["findings"] = []
        self.assertEqual(self.decide(p)[0], "BLOCK")
        p = policy()
        p["findings"][0]["owner"] = ""
        with self.assertRaises(ValueError):
            self.decide(p)

    def test_urgent_or_applicable_exploitation_cannot_be_excepted(self):
        for field, value in (("contextualRisk", "urgent"), ("contextualRisk", "high"), ("knownExploited", True)):
            p = policy()
            p["findings"][0][field] = value
            self.assertEqual(self.decide(p)[0], "BLOCK")
            self.assertEqual(self.decide(p, {**vulnerability(), "severity": "LOW"})[0], "BLOCK")

    def test_treatment_deadline_is_inclusive_and_minor_release_is_a_gate(self):
        self.assertEqual(self.decide(now=datetime(2026, 12, 1, 12, tzinfo=timezone.utc))[0], "BLOCK")
        self.assertEqual(self.decide(minor=True)[0], "BLOCK")
        p = policy()
        p["findings"][0]["dueAt"] = "2027-04-01T12:00:00Z"
        with self.assertRaises(ValueError):
            self.decide(p)

    def test_pending_triage_and_acknowledgement_use_declared_business_days(self):
        p = policy()
        p["findings"][0].update(applicability="pending", contextualRisk="pending", acknowledgedAt=None)
        self.assertEqual(self.decide(p, now=NOW - timedelta(days=1))[0], "BLOCK")
        self.assertEqual(self.decide(p)[1], "medium-high-critical")
        p["findings"][0]["acknowledgedAt"] = "2026-11-11T12:00:00Z"
        self.assertEqual(self.decide(p, now=NOW + timedelta(days=7))[0], "BLOCK")
        calendar = {"weekend": [6, 7], "holidays": ["2026-11-16"]}
        self.assertEqual(business_due(datetime(2026, 11, 13, tzinfo=timezone.utc), 1, calendar).day, 17)

    def test_alias_rows_versions_scopes_and_rescans_do_not_reset_age(self):
        old = finding()
        p = policy()
        p["findings"][0].update(id="renamed", vulnerabilityId="GHSA-TEST-0001", aliases=[],
                                 firstSeen="2026-11-11T12:00:00Z")
        v = {**vulnerability(), "vulnerabilityId": "GHSA-TEST-0001", "version": "1.1",
             "scope": "orders:linux/arm64"}
        self.assertEqual(self.decide(p, v, [old])[0], "BLOCK")
        with self.assertRaises(ValueError):
            preserve_history(p["findings"], [old])
        with self.assertRaises(ValueError):
            preserve_history([], [old])

    def test_resolved_record_cannot_hide_a_current_detection(self):
        p = policy()
        p["findings"][0]["status"] = "resolved"
        self.assertEqual(self.decide(p)[0], "BLOCK")

    def test_historical_exception_cannot_waive_the_strict_gate(self):
        p = policy()
        p["findings"][0]["dueAt"] = "2026-11-16T12:00:00Z"
        exception = {"id": "synthetic-acceptance", "reason": "Upstream fix pending", "mitigation": "Route disabled",
                     "evidence": ["test:route-denied"], "approvedBy": "@edgarrth", "approval": "review:123",
                     "approvedAt": "2026-11-15T12:00:00Z", "expiresAt": "2026-11-18T12:00:00Z",
                     "affectedVersions": ["1.0"], "artifactScopes": ["orders:linux/amd64"]}
        p["findings"][0]["exceptions"] = [exception]
        self.assertEqual(self.decide(p)[0], "BLOCK")
        self.assertEqual(self.decide(p, now=NOW + timedelta(days=1))[0], "BLOCK")
        self.assertEqual(self.decide(p, v={**vulnerability(), "scope": "orders:linux/arm64"})[0], "BLOCK")
        self.assertEqual(self.decide(p, v={**vulnerability(), "severity": "HIGH"})[0], "BLOCK")
        exception["approvedBy"] = "@self-declared"
        with self.assertRaises(ValueError):
            self.decide(p)

    def test_unknown_fields_invalid_dates_duplicate_json_and_wildcards_fail(self):
        for field, value in (("ignore", True), ("firstSeen", "2026-11-10T12:00:00"), ("artifactScopes", ["*"])):
            p = policy()
            p["findings"][0][field] = value
            with self.assertRaises(ValueError):
                self.decide(p)
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "policy.json"
            path.write_text('{"schema":2,"schema":1}')
            with self.assertRaises(ValueError):
                read_json(path)
        self.assertEqual(component("pkg:npm/%40example/lib@1.0?qualifier=a"), "pkg:npm/%40example/lib")

    def test_reports_are_complete_fresh_and_bound_to_artifact_digest_platform(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            digest = "sha256:" + "1" * 64
            report = {"SchemaVersion": 2, "CreatedAt": "2026-11-17T11:59:00Z", "ArtifactName": "registry/orders@" + digest,
                      "ArtifactType": "container_image", "Metadata": {"ImageConfig": {"os": "linux", "architecture": "amd64"}},
                      "Results": [{"Target": "app.jar", "Vulnerabilities": [{"VulnerabilityID": "CVE-2026-12345",
                          "PkgName": "example:library", "InstalledVersion": "1.0", "Severity": "MEDIUM",
                          "PkgIdentifier": {"PURL": "pkg:maven/example/library@1.0"}}]}]}
            path = root / "scan.json"
            path.write_text(json.dumps(report))
            descriptor = {"path": "scan.json", "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                          "scope": "orders:linux/amd64", "artifact": report["ArtifactName"], "kind": "image",
                          "digest": digest, "platform": "linux/amd64", "scanner": "trivy", "scannerVersion": "0.76.2",
                          "exitCode": 0, "severities": ["UNKNOWN", "LOW", "MEDIUM", "HIGH", "CRITICAL"]}
            expected = {"schema": 1, "reports": [descriptor]}
            self.assertEqual(evaluate(policy(), [], expected, root, NOW)["decision"], "BLOCK")
            for created in ("2026-11-17T06:59:00.123456789-05:00", "2026-11-17T13:59:00+02:00"):
                report["CreatedAt"] = created
                path.write_text(json.dumps(report))
                descriptor["sha256"] = hashlib.sha256(path.read_bytes()).hexdigest()
                result = evaluate(policy(), [], expected, root, NOW)
                self.assertEqual(result["decision"], "BLOCK")
                self.assertTrue(result["findings"][0]["firstObserved"].startswith("2026-11-17T11:59:00"))
                self.assertTrue(result["findings"][0]["firstObserved"].endswith("Z"))
            for created in ("2026-11-17T11:59:00", "2026-11-17T11:59:00-00:00",
                            "2026-11-17T11:59:00+00:99", "2026-11-17T11:59:00+24:00"):
                report["CreatedAt"] = created
                path.write_text(json.dumps(report))
                descriptor["sha256"] = hashlib.sha256(path.read_bytes()).hexdigest()
                with self.assertRaises(ValueError):
                    evaluate(policy(), [], expected, root, NOW)
            report["CreatedAt"] = "2026-11-17T11:59:00Z"
            path.write_text(json.dumps(report))
            descriptor["sha256"] = hashlib.sha256(path.read_bytes()).hexdigest()
            for key, value in (("exitCode", 1), ("sha256", "0" * 64), ("platform", "linux/arm64"),
                               ("artifact", "different"), ("severities", ["HIGH", "CRITICAL"]), ("path", "missing.json")):
                bad = copy.deepcopy(expected)
                bad["reports"][0][key] = value
                with self.assertRaises((ValueError, OSError)):
                    evaluate(policy(), [], bad, root, NOW)
            with self.assertRaises(ValueError):
                evaluate(policy(), [], {"schema": 1, "reports": []}, root, NOW)
            with self.assertRaises(ValueError):
                evaluate(policy(), [], expected, root, NOW + timedelta(days=2))


if __name__ == "__main__":
    unittest.main()

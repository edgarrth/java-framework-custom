import base64
import copy
import io
import json
import stat
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

import rfc8785

import manifest
import service_build_proof as proof
from test_manifest import fixture


class ServiceBuildProofTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.bundle = self.root / "bundle"
        (self.bundle / "reports").mkdir(parents=True)
        (self.bundle / "reports/gates.json").write_bytes(b"passed\n")
        (self.bundle / "reports/bom.json").write_bytes(b'{"bomFormat":"CycloneDX"}')
        (self.bundle / "reports/provenance.json").write_bytes(b'{"source":"fixture"}')
        self.data = fixture("service")
        (self.bundle / "build-manifest.json").write_bytes(rfc8785.dumps(self.data))
        self.expected = manifest.sha256((self.bundle / "build-manifest.json").read_bytes())
        self.statement = proof.create(self.bundle, self.expected, "example/payments")
        self.directory = self.root / "proof"
        self.directory.mkdir()
        self.write_proof(self.statement)
        (self.directory / proof.SIGNATURE).write_bytes(b"signature fixture")

    def write_proof(self, statement):
        (self.directory / proof.PROOF).write_bytes(rfc8785.dumps(statement))

    def verify(self):
        return proof.verify_proof(self.directory, "example/payments", "a" * 40, 123, 1, "payments", ".")

    def test_reference_authenticates_the_external_hash_with_the_exact_producer_certificate(self):
        with patch.object(proof, "resolve_workflow", return_value="b" * 40), patch.object(proof.subprocess, "run") as run:
            self.assertEqual(self.expected, self.verify())
            command = run.call_args.args[0]
            for flag, value in {"--certificate-identity": "https://github.com/edgarrth/java-framework-custom/.github/workflows/service-build.yaml@refs/tags/axiz-workflows-v2.0.0",
                                "--certificate-oidc-issuer": "https://token.actions.githubusercontent.com",
                                "--certificate-github-workflow-repository": "example/payments",
                                "--certificate-github-workflow-sha": "a" * 40}.items():
                self.assertEqual(value, command[command.index(flag) + 1])
        self.assertEqual("axiz.service-build-proof/v1", self.statement["schema"])
        self.assertEqual(self.data["build"], self.statement["build"])

    def test_rejects_changed_execution_attempt_source_workflow_service_module_and_extra_fields(self):
        mutators = [lambda d: d["build"].update(run_id=999), lambda d: d["build"].update(run_attempt=2),
                    lambda d: d["build"].update(run_attempt=True), lambda d: d["source"].update(sha="c" * 40),
                    lambda d: d["build"]["workflow"].update(sha="d" * 40), lambda d: d.update(service="other"),
                    lambda d: d.update(module="application"), lambda d: d.update(manifest_sha256="bad"),
                    lambda d: d.update(extra="unreviewed")]
        with patch.object(proof, "resolve_workflow", return_value="b" * 40), patch.object(proof.subprocess, "run"):
            for mutate in mutators:
                statement = copy.deepcopy(self.statement)
                mutate(statement)
                self.write_proof(statement)
                with self.assertRaisesRegex(ValueError, "does not match"):
                    self.verify()
            (self.directory / proof.PROOF).write_text(json.dumps(self.statement, indent=2))
            with self.assertRaisesRegex(ValueError, "does not match"):
                self.verify()

    def test_signature_is_verified_before_untrusted_json_is_interpreted(self):
        (self.directory / proof.PROOF).write_text("not JSON and never trusted")
        with patch.object(proof, "resolve_workflow", return_value="b" * 40), patch.object(proof.subprocess, "run", side_effect=subprocess.CalledProcessError(1, "cosign")):
            with self.assertRaises(subprocess.CalledProcessError):
                self.verify()
        with self.assertRaisesRegex(ValueError, "immutable"):
            proof.verify_proof(self.directory, "example/payments", "HEAD", 123, 1, "payments", ".")

    def test_creation_requires_full_manifest_hash_and_evidence_integrity(self):
        with self.assertRaisesRegex(ValueError, "externally supplied"):
            proof.create(self.bundle, "f" * 64, "example/payments")
        (self.bundle / "reports/gates.json").write_bytes(b"modified gate")
        with self.assertRaisesRegex(ValueError, "Evidence"):
            proof.create(self.bundle, self.expected, "example/payments")

    @staticmethod
    def producer_run():
        return {"id": 123, "run_attempt": 1, "repository": {"full_name": "example/payments"},
                "head_repository": {"full_name": "example/payments"}, "status": "completed", "conclusion": "success",
                "event": "push", "path": ".github/workflows/ci.yaml", "workflow_id": 50,
                "head_sha": "a" * 40, "head_branch": "v2.0.0"}

    def authority(self, run):
        def api(path):
            if path.endswith("/actions/runs/123"):
                return run
            if path.endswith("/actions/workflows/ci.yaml"):
                return {"id": 50}
            if path.endswith("/branches/main"):
                return {"protected": True, "commit": {"sha": "a" * 40}}
            raise AssertionError(path)
        return api

    def test_automatic_policy_rejects_forks_prs_failed_or_stale_attempts_and_other_workflows(self):
        with patch.object(proof, "api", side_effect=self.authority(self.producer_run())):
            self.assertEqual("a" * 40, proof.producer("example/payments", 123, 1)["head_sha"])
        for mutate in (lambda r: r["head_repository"].update(full_name="fork/payments"), lambda r: r.update(event="pull_request"),
                       lambda r: r.update(conclusion="failure"), lambda r: r.update(status="in_progress"),
                       lambda r: r.update(run_attempt=2), lambda r: r.update(path=".github/workflows/other.yaml"),
                       lambda r: r.update(workflow_id=51), lambda r: r.update(head_branch="feature/unsafe"),
                       lambda r: r.update(head_sha="c" * 40)):
            run = self.producer_run()
            mutate(run)
            with patch.object(proof, "api", side_effect=self.authority(run)), self.assertRaises(ValueError):
                proof.producer("example/payments", 123, 1)
        with self.assertRaisesRegex(ValueError, "positive"):
            proof.producer("example/payments", 123, True)

    def archive(self, extra=False, symlink=False, oversized=False):
        data = io.BytesIO()
        with zipfile.ZipFile(data, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            entry = zipfile.ZipInfo(proof.PROOF)
            if symlink:
                entry.external_attr = (stat.S_IFLNK | 0o777) << 16
            archive.writestr(entry, b"x" * (proof.LIMIT + 1) if oversized else rfc8785.dumps(self.statement))
            archive.writestr(proof.SIGNATURE, b"signature")
            if extra:
                archive.writestr("../outside", b"unsafe")
        return data.getvalue()

    def artifacts(self):
        return {"artifacts": [{"id": 456, "name": "service-build-proof-payments-1", "expired": False, "size_in_bytes": 2048}]}

    def test_proof_download_checks_attempt_name_expiry_unique_entries_and_size_before_extracting(self):
        destination = self.root / "download"
        with patch.object(proof, "api", return_value=self.artifacts()), patch.object(proof.subprocess, "check_output", return_value=self.archive()):
            self.assertTrue(proof.download_proof("example/payments", 123, 1, "payments", destination))
        self.assertEqual(self.statement, manifest.read_json(destination / proof.PROOF))
        for options in ({"extra": True}, {"symlink": True}, {"oversized": True}):
            with patch.object(proof, "api", return_value=self.artifacts()), patch.object(proof.subprocess, "check_output", return_value=self.archive(**options)):
                with self.assertRaises(ValueError):
                    proof.download_proof("example/payments", 123, 1, "payments", self.root / "unsafe")
            self.assertFalse((self.root / "unsafe").exists())
        with patch.object(proof, "api", return_value={"artifacts": []}):
            self.assertFalse(proof.download_proof("example/payments", 123, 2, "payments", self.root / "none"))
        for rows in ([dict(self.artifacts()["artifacts"][0], expired=True)], self.artifacts()["artifacts"] * 2):
            with patch.object(proof, "api", return_value={"artifacts": rows}), self.assertRaises(ValueError):
                proof.download_proof("example/payments", 123, 1, "payments", self.root / "unsafe")

    def test_full_automatic_resolution_outputs_only_authenticated_reference_and_cas_predecessor(self):
        import os
        event_path = self.root / "event.json"
        event_path.write_text(json.dumps({"action": "completed", "repository": {"full_name": "example/payments"},
                                         "workflow_run": self.producer_run()}))
        output = self.root / "outputs"
        run = self.producer_run()
        authority = self.authority(run)
        def api(path, token=None):
            if "/artifacts?" in path:
                return self.artifacts()
            return authority(path)
        argv = ["service_build_proof.py", "resolve", "--repository", "example/payments", "--service", "payments",
                "--module", ".", "--gitops-repository", "example/gitops", "--directory", str(self.root / "resolved")]
        env = {"GITHUB_EVENT_PATH": str(event_path), "GITHUB_OUTPUT": str(output), "GITOPS_READ_TOKEN": "read-only-fixture"}
        with patch.dict(os.environ, env), patch("sys.argv", argv), patch.object(proof, "api", side_effect=api), \
                patch.object(proof.subprocess, "check_output", return_value=self.archive()), \
                patch.object(proof.subprocess, "run"), patch.object(proof, "resolve_workflow", return_value="b" * 40), \
                patch.object(proof, "predecessor", return_value="f" * 64) as previous:
            proof.main()
            self.assertEqual(("example/gitops", "main", "payments", "dev", "read-only-fixture"), previous.call_args.args)
        fields = dict(line.split("=", 1) for line in output.read_text().splitlines())
        self.assertEqual({"eligible": "true", "manifest-sha256": self.expected, "build-run-id": "123", "build-run-attempt": "1",
                          "values-revision": "a" * 40, "previous-manifest-sha256": "f" * 64, "environment": "dev"}, fields)
        output.unlink()
        with patch.dict(os.environ, env), patch("sys.argv", argv), patch.object(proof, "producer", return_value=run), \
                patch.object(proof, "download_proof", return_value=False), patch.object(proof, "verify_proof") as verify:
            proof.main()
            verify.assert_not_called()
        self.assertEqual("eligible=false\n", output.read_text())
        output.unlink()
        with patch.dict(os.environ, env), patch("sys.argv", argv), patch.object(proof, "producer", return_value=run), \
                patch.object(proof, "download_proof", return_value=True), \
                patch.object(proof, "verify_proof", side_effect=ValueError("Invalid signature")), self.assertRaises(ValueError):
            proof.main()
        self.assertFalse(output.exists())

    def test_gitops_predecessor_walks_immutable_trees_and_retains_read_only_token(self):
        entry = {"service": "payments", "environment": "dev", "manifest_sha256": "f" * 64}
        raw = json.dumps(entry).encode()
        calls = []
        def api(path, token):
            calls.append((path, token))
            if "/git/ref/" in path:
                return {"object": {"type": "commit", "sha": "d" * 40}}
            if "/git/commits/" in path:
                return {"tree": {"sha": "1" * 40}}
            if path.endswith("1" * 40):
                return {"truncated": False, "tree": [{"path": "environments", "type": "tree", "mode": "040000", "sha": "2" * 40}]}
            if path.endswith("2" * 40):
                return {"truncated": False, "tree": [{"path": "dev", "type": "tree", "mode": "040000", "sha": "3" * 40}]}
            if path.endswith("3" * 40):
                return {"truncated": False, "tree": [{"path": "payments.yaml", "type": "blob", "mode": "100644", "sha": "4" * 40}]}
            return {"encoding": "base64", "size": len(raw), "content": base64.b64encode(raw).decode()}
        with patch.object(proof, "api", side_effect=api):
            self.assertEqual("f" * 64, proof.predecessor("example/gitops", "main", "payments", "dev", "read-only-fixture"))
        self.assertTrue(all(token == "read-only-fixture" for _, token in calls))
        with patch.object(proof, "api", side_effect=subprocess.CalledProcessError(1, "gh")), self.assertRaises(subprocess.CalledProcessError):
            proof.predecessor("example/gitops", "main", "payments", "dev", "read-only-fixture")
        def absent(path, token):
            if "/git/ref/" in path:
                return {"object": {"type": "commit", "sha": "d" * 40}}
            if "/git/commits/" in path:
                return {"tree": {"sha": "1" * 40}}
            return {"truncated": False, "tree": []}
        with patch.object(proof, "api", side_effect=absent):
            self.assertEqual("initial-deployment", proof.predecessor("example/gitops", "main", "payments", "dev", "read"))


if __name__ == "__main__":
    unittest.main()

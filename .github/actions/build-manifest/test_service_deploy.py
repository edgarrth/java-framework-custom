import base64
import copy
import fnmatch
import hashlib
import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import yaml

from build_destination import BuildDestination
import deploy_record
import gitops
import service_deploy
from test_manifest import fixture


class ServiceDeployTest(unittest.TestCase):
    def setUp(self):
        self.data = fixture("service")
        self.values = {"repoURL": "https://github.com/example/payments", "revision": "e" * 40,
                       "files": ["deploy/helm/payments/values.yaml", "deploy/helm/payments/values-prod.yaml"]}
        self.expected = service_deploy.desired(self.data, "f" * 64, "prod", self.values, "1" * 64, 321)

    def test_hashes_registry_bytes_instead_of_trusting_the_registry_digest_label(self):
        raw = b'{"schemaVersion":2}'
        digest = "sha256:" + hashlib.sha256(raw).hexdigest()
        with patch.object(service_deploy.subprocess, "check_output", return_value=raw + b"\n"):
            self.assertEqual({"schemaVersion": 2}, service_deploy.raw_manifest("registry/image@" + digest, digest))
        with patch.object(service_deploy.subprocess, "check_output", return_value=raw + b" "):
            with self.assertRaisesRegex(ValueError, "bytes differ"):
                service_deploy.raw_manifest("registry/image@" + digest, digest)

    def test_values_are_read_at_a_full_commit_and_only_hash_references_enter_the_record(self):
        calls = []

        def api(path):
            calls.append(path)
            if "/commits/" in path:
                return {"sha": "e" * 40}
            name = path.split("/contents/", 1)[1].split("?", 1)[0]
            return {"type": "file", "path": name, "encoding": "base64",
                    "content": base64.b64encode(b"env:\n  secretRef: payment-credentials\n").decode()}

        with patch.object(service_deploy, "api", side_effect=api):
            source, refs = service_deploy.values(self.data, "prod", "e" * 40)
            self.assertEqual(self.values, source)
            self.assertTrue(all("#sha256=" in ref["ref"] and "e" * 40 in ref["ref"] for ref in refs))
            self.assertNotIn("payment-credentials", json.dumps(refs))
            self.assertTrue(all("?ref=" + "e" * 40 in call for call in calls[1:]))
            with self.assertRaises(ValueError):
                service_deploy.values(self.data, "prod", "main")
        with patch.object(service_deploy, "api", side_effect=[{"sha": "e" * 40}, {"type": "symlink"}]):
            with self.assertRaisesRegex(ValueError, "regular"):
                service_deploy.values(self.data, "prod", "e" * 40)

    def state(self):
        image, chart = self.expected["image"], self.expected["chart"]
        return {"metadata": {"annotations": {"axiz.pe/build-manifest-sha256": "f" * 64, "axiz.pe/deploy-record-sha256": "1" * 64}},
                "spec": {"sources": [{"repoURL": chart["oci"], "targetRevision": chart["digest"], "path": ".",
                                      "helm": {"valueFiles": ["$values/" + path for path in self.values["files"]],
                                               "parameters": [{"name": "image." + name, "value": image[name]} for name in ("repository", "tag", "digest")]}},
                                     {"repoURL": self.values["repoURL"], "targetRevision": self.values["revision"], "ref": "values"}]},
                "status": {"sync": {"status": "Synced", "revisions": [chart["digest"], self.values["revision"]]},
                           "health": {"status": "Healthy"}, "operationState": {"phase": "Succeeded"}}}

    def test_a_healthy_previous_or_mutable_deployment_is_not_reported_as_success(self):
        self.assertTrue(service_deploy.synced(self.state(), self.expected))
        changes = [lambda s: s["spec"]["sources"][0].update(targetRevision="2.0.0"),
                   lambda s: s["spec"]["sources"][1].update(targetRevision="main"),
                   lambda s: s["spec"]["sources"][0]["helm"]["parameters"].pop(),
                   lambda s: s["status"]["sync"].update(revisions=["old", "revision"]),
                   lambda s: s["status"]["health"].update(status="Degraded"),
                   lambda s: s["status"]["operationState"].update(phase="Running"),
                   lambda s: s["metadata"]["annotations"].update({"axiz.pe/deploy-record-sha256": "other-request"})]
        for change in changes:
            state = self.state()
            change(state)
            self.assertFalse(service_deploy.synced(state, self.expected))


class GitOpsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / "repo"
        self.root.mkdir()
        self.data = fixture("service")
        self.values = {"repoURL": "https://github.com/example/payments", "revision": "e" * 40,
                       "files": ["deploy/helm/payments/values.yaml", "deploy/helm/payments/values-prod.yaml"]}
        self.record = Path(self.temp.name) / "record.json"
        self.signature = Path(self.temp.name) / "record.sigstore.json"
        self.signature.write_text('{"bundle":"test-only"}')
        self.write_record("requested")

    def write_record(self, result):
        data = deploy_record.record(self.data, "f" * 64, "prod", "https://github.com/example/gitops", 321,
                                    "initial-deployment", [{"name": "values", "ref": "commit:" + "e" * 40}],
                                    [("reviewer", "environment-approval")], result)
        self.record.write_text(json.dumps(data))

    def apply(self, **changes):
        params = dict(root=self.root, data=self.data, manifest_hash="f" * 64, environment="prod", source=self.values,
                      previous="initial-deployment", record=self.record, signature=self.signature, run=321, attempt=1)
        params.update(changes)
        return gitops.apply(**params)

    def test_persists_signed_request_and_outcome_without_changing_deployed_artifacts(self):
        self.apply()
        target = self.root / "environments/prod/payments.yaml"
        templates = Path(__file__).resolve().parents[2] / "gitops"
        destination = BuildDestination.parse((templates / "fixtures/build-configuration.json").read_text())
        documents = destination.render(templates, Path(self.temp.name) / "rendered")
        patterns = documents["argocd/applicationset"]["spec"]["generators"][0]["git"]["files"]
        self.assertTrue(any(fnmatch.fnmatchcase(target.relative_to(self.root).as_posix(), pattern["path"])
                            for pattern in patterns), "ApplicationSet must discover the descriptor written by deploy")
        requested = yaml.safe_load(target.read_text())
        self.assertEqual(self.data["artifacts"]["chart"], requested["chart"])
        self.assertEqual(hashlib.sha256(self.record.read_bytes()).hexdigest(), requested["deploy_record_sha256"])
        self.assertEqual(self.record.read_bytes(), (self.root / "deployments/prod/payments/321-1/request.json").read_bytes())
        self.write_record("succeeded")
        self.apply(outcome=True)
        outcome = yaml.safe_load(target.read_text())
        self.assertEqual("succeeded", outcome.pop("result"))
        self.assertEqual(hashlib.sha256(self.record.read_bytes()).hexdigest(), outcome.pop("outcome_record_sha256"))
        self.assertEqual(requested, outcome)
        # Una reejecución no puede reemplazar la evidencia de un intento anterior.
        self.write_record("failed")
        with self.assertRaisesRegex(ValueError, "cannot be overwritten"):
            self.apply(outcome=True)

    def test_stale_approval_or_wrong_record_makes_no_destination_changes(self):
        existing = self.root / "environments/prod/payments.yaml"
        existing.parent.mkdir(parents=True)
        existing.write_text("manifest_sha256: " + "a" * 64 + "\n")
        with self.assertRaisesRegex(ValueError, "changed since approval"):
            self.apply()
        self.assertFalse((self.root / "deployments").exists())
        self.assertEqual("a" * 64, yaml.safe_load(existing.read_text())["manifest_sha256"])
        with self.assertRaisesRegex(ValueError, "does not describe"):
            self.apply(environment="qa")

    def test_refuses_symlinked_gitops_directories(self):
        outside = Path(self.temp.name) / "outside"
        outside.mkdir()
        (self.root / "environments").symlink_to(outside, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, "symlinks"):
            self.apply()
        self.assertEqual([], list(outside.iterdir()))

    def test_concurrent_unrelated_push_is_retried_and_token_never_enters_remote_url_or_config(self):
        remote = Path(self.temp.name) / "bare.git"
        gitops.git(self.root, "init", "--initial-branch=main")
        (self.root / "README.md").write_text("GitOps\n")
        gitops.git(self.root, "add", ".")
        gitops.git(self.root, "-c", "user.name=test", "-c", "user.email=test@example.com", "commit", "-m", "initial")
        subprocess.run(["git", "clone", "--bare", str(self.root), str(remote)], check=True, capture_output=True)
        real_run = subprocess.run
        attempts = []
        configs = []

        def run(command, **kwargs):
            if command[:2] == ["git", "clone"]:
                command = list(command)
                self.assertEqual("https://github.com/example/gitops.git", command[-2])
                command[-2] = str(remote)
                result = real_run(command, **kwargs)
                configs.append((Path(command[-1]) / ".git/config").read_text())
                return result
            if "push" in command:
                attempts.append(command)
                if len(attempts) == 1:
                    # Otro servicio actualiza el destino después del clonado propio.
                    (self.root / "other-service.yaml").write_text("preserve: true\n")
                    gitops.git(self.root, "add", ".")
                    gitops.git(self.root, "-c", "user.name=test", "-c", "user.email=test@example.com", "commit", "-m", "concurrent")
                    real_run(["git", "-C", str(self.root), "push", str(remote), "HEAD:main"], check=True, capture_output=True)
            return real_run(command, **kwargs)

        with patch.object(gitops.subprocess, "run", side_effect=run):
            commit = gitops.publish("example/gitops", "main", {"manifest": self.data, "values": self.values},
                                    "f" * 64, "prod", "initial-deployment", self.record, self.signature, 321, 1, "sensitive-test-token")
        self.assertEqual(2, len(attempts))
        self.assertEqual("preserve: true", subprocess.check_output(["git", "--git-dir", str(remote), "show", commit + ":other-service.yaml"], text=True).strip())
        self.assertNotIn("sensitive-test-token", "".join(configs))
        message = subprocess.check_output(["git", "--git-dir", str(remote), "log", "-1", "--format=%B"], text=True)
        self.assertIn("Build-manifest-SHA256: " + "f" * 64, message)
        self.assertIn("Deploy-record-SHA256: " + hashlib.sha256(self.record.read_bytes()).hexdigest(), message)


class ConfiguredGitOpsTest(unittest.TestCase):
    def test_writer_uses_the_same_configuration_for_promotion_and_rollback(self):
        import os
        import sys
        configuration = {'registry': 'registry.bank.test', 'namespace': 'bank', 'repository': 'example/payments',
                         'gitops_repository': 'another-bank/approved-state', 'services': ['payments']}
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            prepared = root / 'prepared.json'
            prepared.write_text(json.dumps({'manifest': fixture('service'), 'values': {}}))
            argv = ['gitops.py', '--prepared', str(prepared), '--manifest-sha256', 'f' * 64,
                    '--environment', 'qa', '--previous-manifest', 'initial-deployment',
                    '--record', str(root / 'record'), '--signature', str(root / 'signature')]
            environment = {'BUILD_DESTINATION': json.dumps(configuration), 'GITHUB_REPOSITORY': 'example/payments',
                           'GITHUB_RUN_ID': '1', 'GITHUB_RUN_ATTEMPT': '1', 'GITOPS_TOKEN': 'fixture-only'}
            for outcome in ([], ['--outcome']):
                with patch.dict(os.environ, environment), patch.object(sys, 'argv', argv + outcome), \
                        patch.object(gitops, 'publish', return_value='a' * 40) as publish:
                    gitops.main()
                    self.assertEqual(('another-bank/approved-state', 'main'), publish.call_args.args[:2])
            for selectors in (['--repository', 'other-bank/wrong-state'], ['--branch', 'unobserved']):
                with patch.object(sys, 'argv', argv + selectors), patch.object(gitops, 'publish') as publish, \
                        patch('sys.stderr'), self.assertRaises(SystemExit):
                    gitops.main()
                publish.assert_not_called()
            environment['BUILD_DESTINATION'] = '{}'
            with patch.dict(os.environ, environment), patch.object(sys, 'argv', argv), \
                    patch.object(gitops, 'publish') as publish, self.assertRaises(ValueError):
                gitops.main()
            publish.assert_not_called()


if __name__ == "__main__":
    unittest.main()

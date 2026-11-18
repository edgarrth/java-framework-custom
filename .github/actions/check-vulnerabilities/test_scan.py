import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from scan import planned_scans, protected_base, review_protection, run_scans


class ScanContractTest(unittest.TestCase):
    def test_all_image_platforms_and_child_digests_are_mandatory(self):
        commit = "a" * 40
        metadata = {"source": {"sha": commit}, "parameters": {"platforms": ["linux/amd64", "linux/arm64"]},
                    "artifacts": {"image": {"repository": "registry.example/orders", "digest": "sha256:" + "1" * 64,
                                           "platforms": {"linux/amd64": "sha256:" + "2" * 64,
                                                         "linux/arm64": "sha256:" + "3" * 64}}}}
        with patch("scan.git", return_value=commit):
            scans = planned_scans(Path("/checkout"), "service", "orders", metadata)
            self.assertEqual(len(scans), 3)
            self.assertEqual(scans[1]["platform"], "linux/amd64")
            self.assertTrue(scans[2]["artifact"].endswith("3" * 64))
            del metadata["artifacts"]["image"]["platforms"]["linux/arm64"]
            with self.assertRaises(ValueError):
                planned_scans(Path("/checkout"), "service", "orders", metadata)

    def test_github_must_confirm_protection_and_base_ancestry(self):
        result = subprocess.CompletedProcess([], 0, json.dumps({"name": "main", "protected": True,
                                                               "commit": {"sha": "a" * 40}}), "")
        with patch("scan.subprocess.run", return_value=result), patch("scan.git", return_value="") as git, \
                patch("scan.review_protection", return_value={"kind": "verified-fixture"}):
            base, proof = protected_base(Path("."), "owner/project", "main", "b" * 40)
            self.assertEqual(base, "b" * 40)
            self.assertTrue(proof["protected"])
            git.assert_called_once_with(Path("."), "merge-base", "--is-ancestor", "b" * 40, "a" * 40)
        result.stdout = json.dumps({"name": "main", "protected": False, "commit": {"sha": "a" * 40}})
        with patch("scan.subprocess.run", return_value=result):
            with self.assertRaises(ValueError):
                protected_base(Path("."), "owner/project", "main", "b" * 40)

    def test_protected_flag_without_mandatory_owner_review_is_insufficient(self):
        def reply(body):
            return subprocess.CompletedProcess([], 0, json.dumps(body), "")
        rule = {"type": "pull_request", "parameters": {
            "require_code_owner_review": True, "required_approving_review_count": 1}}
        with patch("scan.subprocess.run", return_value=reply([[rule]])) as execute:
            self.assertEqual(review_protection("owner/project", "main")["kind"], "active-ruleset")
            self.assertIn("--paginate", execute.call_args.args[0])
        for settings in ({}, {"require_code_owner_reviews": True, "required_approving_review_count": 0}):
            with patch("scan.subprocess.run", side_effect=[reply([[]]), reply({"required_pull_request_reviews": settings})]):
                with self.assertRaisesRegex(ValueError, "mandatory code-owner"):
                    review_protection("owner/project", "main")
        with patch("scan.subprocess.run", side_effect=[reply([[]]), reply({"required_pull_request_reviews": {
                "require_code_owner_reviews": True, "required_approving_review_count": 1}})]):
            self.assertEqual(review_protection("owner/project", "main")["kind"], "classic-protection")

    def test_scanner_failure_cannot_be_an_empty_report_and_filters_are_not_inherited(self):
        commands = []

        def execute(command, **kwargs):
            if "--version" in command:
                return subprocess.CompletedProcess(command, 0, '{"Version":"0.76.2"}', "")
            commands.append((command, kwargs))
            return subprocess.CompletedProcess(command, 1)

        with tempfile.TemporaryDirectory() as tmp, patch("scan.subprocess.run", side_effect=execute):
            with patch.dict("scan.os.environ", {"TRIVY_SEVERITY": "LOW", "TRIVY_SKIP_DIRS": "everything"}):
                with self.assertRaisesRegex(ValueError, "Scanner execution failed"):
                    run_scans([{"kind": "source", "artifact": "/checkout"}], Path(tmp))
            command, args = commands[0]
            self.assertIn("MEDIUM", command[command.index("--severity") + 1])
            self.assertNotIn("TRIVY_SKIP_DIRS", args["env"])
            self.assertNotIn("TRIVY_SEVERITY", args["env"])
            self.assertIn("--ignore-unfixed=false", command)
            self.assertEqual("pkg:\n  include-dev-deps: true\n",
                             Path(command[command.index("--config") + 1]).read_text())


if __name__ == "__main__":
    unittest.main()

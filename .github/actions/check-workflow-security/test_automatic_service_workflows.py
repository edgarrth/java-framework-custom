import os
import json
import tempfile
import sys
from pathlib import Path
import subprocess
import unittest

import yaml

ROOT = Path(__file__).resolve().parents[2]


class AutomaticServicePolicyTest(unittest.TestCase):
    def test_empty_resolver_destination_fails_before_credentials_or_network(self):
        action = yaml.safe_load((ROOT / "actions/resolve-service-build/action.yaml").read_text())
        steps = action["runs"]["steps"]
        self.assertEqual("destination-input", steps[0]["id"])
        self.assertNotIn("TOKEN", str(steps[0]["env"]))
        for value in (None, "", " \t\n"):
            environment = dict(os.environ)
            environment.pop("BUILD_DESTINATION", None)
            if value is not None:
                environment['BUILD_DESTINATION'] = value
            result = subprocess.run(['bash', '-euo', 'pipefail', '-c', steps[0]['run']],
                                    env=environment, text=True, capture_output=True)
            self.assertNotEqual(0, result.returncode)
            self.assertIn('build-destination must contain', result.stderr)
            self.assertNotIn('unbound variable', result.stderr)

    def test_producer_retains_a_separately_signed_reference_after_the_complete_manifest(self):
        data = yaml.safe_load((ROOT / "workflows/service-build.yaml").read_text())
        self.assertNotIn("id-token", data["jobs"]["build"]["permissions"])
        self.assertEqual("write", data["jobs"]["sign"]["permissions"]["id-token"])
        steps = data["jobs"]["sign"]["steps"]
        signer = next(step for step in steps if "sign_product.py" in step.get("run", ""))
        self.assertIn("--kind service", signer["run"])
        artifact = next(step for step in steps if step.get("with", {}).get("name", "").startswith("service-build-proof-"))
        self.assertIn("github.run_attempt", artifact["with"]["name"])
        self.assertIn("inputs.service", artifact["with"]["name"])
        self.assertEqual("${{ runner.temp }}/service-build-proof", artifact["with"]["path"])

    def test_resolver_has_no_checkout_build_or_write_credentials(self):
        action = yaml.safe_load((ROOT / "actions/resolve-service-build/action.yaml").read_text())
        self.assertEqual("Read-only GitOps contents credential", action["inputs"]["gitops-read-token"]["description"])
        self.assertEqual("dev", action["inputs"]["environment"]["default"])
        steps = action["runs"]["steps"]
        self.assertTrue(any(step.get("uses", "").startswith("sigstore/cosign-installer@") for step in steps))
        for step in steps:
            self.assertNotIn("checkout@", step.get("uses", ""))
            self.assertNotIn("PACKAGES_PUBLISH", str(step))
            self.assertNotIn("GITOPS_APP_PRIVATE_KEY", str(step))
            self.assertNotIn("./mvnw", step.get("run", ""))

    def test_shared_destination_is_validated_before_any_destination_credentials(self):
        workflow = yaml.safe_load((ROOT / "workflows/service-deploy.yaml").read_text())
        steps = workflow["jobs"]["deploy"]["steps"]
        step = next(step for step in steps if step.get("id") == "destination")
        token = next(step for step in steps if step.get("id") == "token")
        self.assertLess(steps.index(step), steps.index(token))
        self.assertEqual("${{ steps.destination.outputs.owner }}", token["with"]["owner"])
        self.assertEqual("${{ steps.destination.outputs.name }}", token["with"]["repositories"])
        contract = workflow[True]["workflow_call"]
        self.assertTrue(contract["secrets"]["BUILD_DESTINATION"]["required"])
        self.assertNotIn("expected-gitops-repository", contract["inputs"])
        self.assertNotIn("gitops-branch", contract["inputs"])
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "build-manifest.json").write_text(json.dumps({"parameters": {"service": "payment-orders"}}))
            output = root / "outputs"
            configuration = json.loads((ROOT / "gitops/fixtures/build-configuration.json").read_text())
            environment = dict(os.environ, BUILD_DESTINATION=json.dumps(configuration), PYTHON=sys.executable,
                               TOOLS=str(ROOT / "actions/build-manifest"), BUNDLE=directory,
                               GITHUB_REPOSITORY=configuration["repository"], GITHUB_OUTPUT=str(output))
            result = subprocess.run(["bash", "-euo", "pipefail", "-c", step["run"]], env=environment, text=True, capture_output=True)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn("repository=" + configuration["gitops_repository"], output.read_text())
            self.assertIn("branch=main", output.read_text())
            output.unlink()
            environment["BUILD_DESTINATION"] = "{}"
            result = subprocess.run(["bash", "-euo", "pipefail", "-c", step["run"]], env=environment, text=True, capture_output=True)
            self.assertNotEqual(0, result.returncode)
            self.assertEqual("", output.read_text())


if __name__ == "__main__":
    unittest.main()

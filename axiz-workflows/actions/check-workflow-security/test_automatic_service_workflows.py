import os
from pathlib import Path
import subprocess
import unittest

import yaml

ROOT = Path(__file__).resolve().parents[2]


class AutomaticServicePolicyTest(unittest.TestCase):
    def test_producer_retains_a_separately_signed_reference_after_the_complete_manifest(self):
        data = yaml.safe_load((ROOT / ".github/workflows/service-build.yaml").read_text())
        steps = data["jobs"]["build"]["steps"]
        sign = next(index for index, step in enumerate(steps) if "cosign sign-blob --yes --bundle target/build-bundle/" in step.get("run", ""))
        proof = next(index for index, step in enumerate(steps) if '"$TOOLS/service_build_proof.py" create' in step.get("run", ""))
        self.assertLess(sign, proof)
        self.assertIn("--manifest-sha256", steps[proof]["run"])
        self.assertIn("service-build-proof.sigstore.json", steps[proof]["run"])
        artifact = next(step for step in steps if step.get("with", {}).get("name", "").startswith("service-build-proof-"))
        self.assertIn("github.run_attempt", artifact["with"]["name"])
        self.assertIn("inputs.service", artifact["with"]["name"])

    def test_resolver_has_no_checkout_build_or_write_credentials(self):
        action = yaml.safe_load((ROOT / "actions/resolve-service-build/action.yaml").read_text())
        self.assertEqual("Read-only GitOps contents credential", action["inputs"]["gitops-read-token"]["description"])
        self.assertEqual("dev", action["inputs"]["environment"]["default"])
        steps = action["runs"]["steps"]
        self.assertTrue(any(step.get("uses", "").startswith("sigstore/cosign-installer@") for step in steps))
        for step in steps:
            self.assertNotIn("checkout@", step.get("uses", ""))
            self.assertNotIn("NEXUS_PUBLISH", str(step))
            self.assertNotIn("GITOPS_APP_PRIVATE_KEY", str(step))
            self.assertNotIn("./mvnw", step.get("run", ""))

    def test_destination_override_is_rejected_before_registry_or_gitops_access(self):
        workflow = yaml.safe_load((ROOT / ".github/workflows/service-deploy.yaml").read_text())
        step = next(step for step in workflow["jobs"]["deploy"]["steps"] if step.get("id") == "preflight")
        environment = dict(os.environ, EXPECTED_GITOPS="example/observed", GITOPS="example/different")
        result = subprocess.run(["bash", "-euo", "pipefail", "-c", step["run"]], env=environment, text=True, capture_output=True)
        self.assertEqual(1, result.returncode)
        self.assertIn("differs from the predecessor", result.stderr)
        self.assertNotIn("unbound variable", result.stderr)
        self.assertEqual("${{ inputs.expected-gitops-repository }}", step["env"]["EXPECTED_GITOPS"])


if __name__ == "__main__":
    unittest.main()

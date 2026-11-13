import copy
import importlib.util
from pathlib import Path
import tempfile
import unittest

import yaml

HERE = Path(__file__).parent
spec = importlib.util.spec_from_file_location("publish_gates", HERE / "check-publish-gates.py")
policy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(policy)


class PublishGatesTest(unittest.TestCase):
    def setUp(self):
        self.build = yaml.safe_load((HERE.resolve().parents[2] / ".github/workflows/framework-ci.yaml").read_text())
        self.deploy = yaml.safe_load((HERE.resolve().parents[2] / ".github/workflows/framework-deploy.yaml").read_text())

    def check(self, build=None, deploy=None):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "ci.yaml").write_text(yaml.safe_dump(self.build if build is None else build))
            (root / "deploy.yaml").write_text(yaml.safe_dump(self.deploy if deploy is None else deploy))
            return policy.check(root / "ci.yaml", root / "deploy.yaml")

    def test_actual_workflows_keep_build_and_deploy_separate(self):
        self.assertEqual([], self.check())

    def test_every_quality_gate_is_mandatory_for_the_completed_producer(self):
        for name in policy.GATES:
            for setting in (None, {"if": "always()"}, {"continue-on-error": True}):
                build = copy.deepcopy(self.build)
                if setting is None:
                    del build["jobs"][name]
                else:
                    build["jobs"][name].update(setting)
                with self.subTest(gate=name, setting=setting):
                    self.assertTrue(self.check(build=build))

    def test_publishing_inside_the_build_is_rejected(self):
        self.build["jobs"]["publish"] = {"uses": "edgarrth/java-framework-custom/.github/workflows/lib-deploy.yaml@axiz-workflows-v2.0.0"}
        self.assertTrue(self.check())

    def test_native_gate_cannot_be_replaced_with_a_noop(self):
        self.build["jobs"]["native-reactive"] = {"steps": [{"run": "echo accepted"}]}
        self.assertTrue(any("native-reactive must execute" in error for error in self.check()))

    def test_deployment_cannot_replace_external_identity_or_rebuild(self):
        self.deploy["jobs"]["deploy"]["with"]["manifest-sha256"] = "${{ github.sha }}"
        self.assertTrue(self.check())
        self.deploy["jobs"]["rebuild"] = {"steps": [{"run": "./mvnw package"}]}
        self.assertTrue(any("execute Maven" in error for error in self.check()))


if __name__ == "__main__":
    unittest.main()

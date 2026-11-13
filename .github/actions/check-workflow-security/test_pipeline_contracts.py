import copy
import unittest
from pathlib import Path

import yaml

from check_pipeline_contracts import validate, validate_consumer


class PipelineContractsTest(unittest.TestCase):
    def setUp(self):
        workflows = Path(__file__).resolve().parents[2] / "workflows"
        self.build = yaml.safe_load((workflows / "lib-build.yaml").read_text())
        self.deploy = yaml.safe_load((workflows / "lib-deploy.yaml").read_text())

    def test_library_workflows_have_separate_contracts(self):
        self.assertEqual([], validate(self.build, self.deploy))

    def test_service_workflows_and_registry_credentials_are_separate(self):
        workflows = Path(__file__).resolve().parents[2] / "workflows"
        build = yaml.safe_load((workflows / "service-build.yaml").read_text())
        deploy = yaml.safe_load((workflows / "service-deploy.yaml").read_text())
        self.assertEqual([], validate(build, deploy))
        deploy[True]["workflow_call"]["secrets"]["REGISTRY_BUILD_PASSWORD"] = {"required": True}
        self.assertTrue(any("write credential" in error for error in validate(build, deploy)))
        deploy["jobs"]["deploy"]["steps"].append({"run": "helm push product.tgz oci://registry"})
        self.assertTrue(any("artifact registry" in error for error in validate(build, deploy)))

    def test_a_second_deploy_job_inside_build_is_rejected(self):
        self.build["jobs"]["publish"] = {"uses": "edgarrth/java-framework-custom/.github/workflows/lib-deploy.yaml@axiz-workflows-v2.0.0"}
        self.assertTrue(any("invoke a deploy" in error for error in validate(self.build, self.deploy)))

    def test_deploy_cannot_compile_or_repackage(self):
        for command in ("mvn package", "./mvnw verify", "helm package chart", "docker build .", "mvn jib:build", "mvn native:compile"):
            deploy = copy.deepcopy(self.deploy)
            deploy["jobs"]["deploy"]["steps"].append({"run": command})
            with self.subTest(command=command):
                self.assertTrue(any("regenerating" in error for error in validate(self.build, deploy)))

    def test_expected_identity_must_not_be_generated_from_a_download(self):
        self.deploy[True]["workflow_call"]["inputs"]["manifest-sha256"]["default"] = "from-bundle"
        self.assertTrue(any("manifest-sha256" in error for error in validate(self.build, self.deploy)))

    def test_build_credentials_and_content_permissions_cannot_write_to_destinations(self):
        self.build[True]["workflow_call"]["secrets"]["NEXUS_PUBLISH_PASSWORD"] = {"required": True}
        self.build["permissions"]["contents"] = "write"
        errors = validate(self.build, self.deploy)
        self.assertTrue(any("credential" in error for error in errors))
        self.assertTrue(any("contents write" in error for error in errors))

    def consumer(self, filename):
        workflows = Path(__file__).resolve().parents[2] / "workflows"
        target = yaml.safe_load((workflows / filename).read_text())
        contract = target[True]["workflow_call"]
        job = {"uses": "edgarrth/java-framework-custom/.github/workflows/" + filename + "@axiz-workflows-v2.0.0",
               "with": {key: "explicit" for key, value in contract["inputs"].items() if value.get("required")},
               "secrets": {key: "explicit" for key, value in contract.get("secrets", {}).items() if value.get("required")}}
        return {"jobs": {"product": job}}, workflows

    def test_all_five_public_consumers_have_exact_inputs_and_credentials(self):
        for filename, phase in (("lib-build.yaml", "build"), ("native.yaml", "build"), ("service-build.yaml", "build"),
                                ("lib-deploy.yaml", "deploy"), ("service-deploy.yaml", "deploy")):
            document, workflows = self.consumer(filename)
            with self.subTest(filename=filename):
                self.assertEqual([], validate_consumer(document, workflows, phase))
                document["jobs"]["product"]["with"]["undeclared"] = "value"
                self.assertTrue(any("unknown inputs" in error for error in validate_consumer(document, workflows, phase)))

    def test_missing_identity_and_excess_credentials_fail_in_the_consumer(self):
        document, workflows = self.consumer("lib-deploy.yaml")
        del document["jobs"]["product"]["with"]["manifest-sha256"]
        document["jobs"]["product"]["secrets"]["REGISTRY_BUILD_PASSWORD"] = "forbidden"
        errors = validate_consumer(document, workflows, "deploy")
        self.assertTrue(any("manifest-sha256" in error for error in errors))
        self.assertTrue(any("unknown secrets" in error for error in errors))
        document["jobs"]["product"]["uses"] = "edgarrth/java-framework-custom/.github/workflows/lib-promote.yaml@v1.0.0"
        self.assertTrue(any("exact v2" in error for error in validate_consumer(document, workflows, "deploy")))

    def test_consumer_phase_boundaries_include_native_builds(self):
        document, workflows = self.consumer("native.yaml")
        self.assertTrue(any("building" in error for error in validate_consumer(document, workflows, "deploy")))
        document["permissions"] = {"contents": "write"}
        document["jobs"]["upload"] = {"steps": [{"run": "gh release upload v2.0.0 bin/*"}]}
        self.assertTrue(any("mutation" in error for error in validate_consumer(document, workflows, "build")))
        self.assertTrue(any("contents write" in error for error in validate_consumer(document, workflows, "build")))

    def test_native_components_build_has_no_destination_permissions_or_release_mutation(self):
        workflows = Path(__file__).resolve().parents[2] / "workflows"
        native = yaml.safe_load((workflows / "native.yaml").read_text())
        native[True]["workflow_call"]["outputs"] = {"manifest-sha256": {"value": "private component; main builder seals the product"}}
        self.assertEqual([], validate(native, self.deploy))


if __name__ == "__main__":
    unittest.main()

import copy
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import build_bundle
import prepare
import manifest
from test_manifest import fixture


class PrepareBuildTest(unittest.TestCase):
    def test_framework_dependency_is_bound_to_the_verified_candidate_not_just_its_version(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "reports").mkdir()
            (root / "reports/gates.json").write_bytes(b"passed\n")
            (root / "reports/bom.json").write_bytes(b'{"bomFormat":"CycloneDX"}')
            (root / "reports/provenance.json").write_bytes(b'{"source":"fixture"}')
            dependency = fixture()
            artifact = dependency["artifacts"]["maven"][0]
            product = root / "maven" / artifact["path"]
            product.parent.mkdir(parents=True)
            product.write_bytes(b"artifact bytes")
            (root / "maven/SHA256SUMS").write_text(artifact["sha256"] + "  " + artifact["path"] + "\n")
            location = root / "build-manifest.json"
            location.write_text(json.dumps(dependency))
            expected = manifest.sha256(location.read_bytes())
            data = prepare.inputs("2.0.0", "a" * 40, "axiz/tools", [], "2.0.0")
            prepare.bind_candidate(data, str(root), expected)
            self.assertEqual(expected, data["dependencies"]["axiz_build"]["manifest_sha256"])
            self.assertEqual(123, data["dependencies"]["axiz_build"]["run_id"])
            with self.assertRaises(ValueError):
                prepare.bind_candidate(data, str(root), "0" * 64)
            with self.assertRaises(ValueError):
                prepare.bind_candidate(prepare.inputs("2.0.0", "a" * 40, "axiz/tools", [], "2.1.0"), str(root), expected)
            with self.assertRaises(ValueError):
                prepare.bind_candidate(data, str(root), "")

    def test_effective_parameters_are_explicit_and_final_coordinates_remain_unchanged(self):
        data = prepare.inputs("2.0.0", "a" * 40, "axiz/axiz-tools", ["-Daxiz.it.requireDocker=true"], "2.0.0", "2.0.0/3")
        self.assertEqual("2.0.0", data["version"])
        self.assertEqual("2.0.0/3", data["candidate"])
        self.assertEqual(["quality", "release"], data["parameters"]["profiles"])
        self.assertEqual("2.0.0", data["dependencies"]["axiz_version"])

    def test_monorepo_directory_is_explicit_and_cannot_escape_the_checkout(self):
        data = prepare.inputs("2.0.0", "a" * 40, "edgarrth/java-framework-custom", [], project_directory="axiz-framework")
        self.assertEqual("axiz-framework", data["parameters"]["project_directory"])
        for directory in ["../other", "/tmp/project", "a/../b", "a//b", "a\\b", "./project", ""]:
            with self.subTest(directory=directory), self.assertRaises(ValueError):
                prepare.inputs("2.0.0", "a" * 40, "example/payments", [], project_directory=directory)

    def test_rejects_rc_coordinates_and_gate_bypass(self):
        for version, args in [("2.0.0-RC.1", []), ("2.0.0-SNAPSHOT", []), ("2.0.0", ["-DskipTests"]), ("2.0.0", ["--fail-never"])]:
            with self.subTest(version=version, args=args), self.assertRaises(ValueError):
                prepare.inputs(version, "a" * 40, "axiz/framework", args)
        with self.assertRaises(ValueError):
            prepare.inputs("2.0.0", "main", "axiz/framework", [])
        with self.assertRaises(ValueError):
            prepare.inputs("2.0.0", "a" * 40, "axiz/framework", [], candidate="2.0.0-RC.1")

    def test_oidc_binds_the_reusable_workflow_and_actual_source(self):
        claims = {"job_workflow_ref": "edgarrth/java-framework-custom/.github/workflows/lib-build.yaml@refs/tags/axiz-workflows-v2.0.0",
                  "job_workflow_sha": "b" * 40, "repository": "axiz/framework", "sha": "a" * 40}
        self.assertEqual("b" * 40, prepare.workflow_identity(claims, "axiz/framework", "a" * 40, "b" * 40, tag="axiz-workflows-v2.0.0")["sha"])
        for key in claims:
            wrong = dict(claims, **{key: "wrong"})
            with self.subTest(key=key), self.assertRaises(ValueError):
                prepare.workflow_identity(wrong, "axiz/framework", "a" * 40, "b" * 40, tag="axiz-workflows-v2.0.0")

    def test_worker_preparation_never_requests_oidc(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            environment = {"WORKFLOWS_TAG": "axiz-workflows-v2.0.0", "BUILD_VERSION": "2.0.0", "SOURCE_SHA": "a" * 40,
                           "GITHUB_SHA": "a" * 40, "GITHUB_REPOSITORY": "example/framework",
                           "GITHUB_WORKSPACE": temporary, "GITHUB_RUN_ID": "123", "GITHUB_RUN_ATTEMPT": "1"}
            import os
            previous = Path.cwd()
            try:
                with patch.dict(os.environ, environment, clear=True), \
                        patch.object(prepare, "resolve_workflow", return_value="b" * 40), \
                        patch.object(prepare.subprocess, "check_output", return_value="a" * 40 + "\n"), \
                        patch.object(prepare, "oidc_claims", side_effect=AssertionError("worker requested OIDC")), \
                        patch("sys.argv", ["prepare.py", "--output", str(root / "metadata.json"),
                                           "--arguments", str(root / "arguments")]):
                    prepare.main()
                data = json.loads((root / "metadata.json").read_text())
                self.assertEqual("b" * 40, data["build"]["workflow"]["sha"])
                self.assertEqual(123, data["build"]["run_id"])
                self.assertEqual(b"", (root / "arguments").read_bytes())
            finally:
                os.chdir(previous)

    def test_immutable_tag_resolution_includes_annotated_tags_and_rejects_cycles(self):
        release = {"tag_name": "axiz-workflows-v2.0.0", "immutable": True, "draft": False}
        with patch.object(prepare, "api", side_effect=[release, {"object": {"type": "tag", "sha": "c" * 40}}, {"object": {"type": "commit", "sha": "b" * 40}}]):
            self.assertEqual("b" * 40, prepare.resolve_workflow("axiz-workflows-v2.0.0"))
        with patch.object(prepare, "api", side_effect=[dict(release, immutable=False)]), self.assertRaises(ValueError):
            prepare.resolve_workflow("axiz-workflows-v2.0.0")
        tag = {"object": {"type": "tag", "sha": "c" * 40}}
        with patch.object(prepare, "api", side_effect=[release, tag, tag]), self.assertRaises(ValueError):
            prepare.resolve_workflow("axiz-workflows-v2.0.0")

    def test_gate_evidence_and_provenance_identify_the_actual_product_bytes(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "reports").mkdir()
            for name in ("verify.log", "mutation.log", "encapsulation.log", "bom.json", "scan.json", "contracts.log", "chart.log"):
                (root / "reports" / name).write_text("passed\n")
            for kind in ("maven-library", "service"):
                data = copy.deepcopy(fixture(kind))
                build_bundle.complete(data, root)
                provenance = json.loads((root / data["provenance"]["path"]).read_text())
                self.assertEqual("https://slsa.dev/provenance/v1", provenance["predicateType"])
                self.assertEqual("a" * 40, provenance["predicate"]["buildDefinition"]["resolvedDependencies"][0]["digest"]["gitCommit"])
                self.assertEqual(8 if kind == "service" else 6, len(data["gates"]))
                self.assertTrue(provenance["subject"])
            (root / "reports/mutation.log").write_text("")
            with self.assertRaises(ValueError):
                build_bundle.complete(data, root)


if __name__ == "__main__":
    unittest.main()


class ExplicitWorkflowTagTest(unittest.TestCase):
    def test_tag_is_required_and_validated_before_claims(self):
        args = ({}, 'example/repository', 'a' * 40, 'b' * 40)
        with self.assertRaises(TypeError):prepare.workflow_identity(*args)
        for tag in ('', None, 'main', 'axiz-workflows-v1.0.0', 'axiz-workflows-v2.01.0', 'axiz-tools-v2.0.0'):
            with self.subTest(tag=tag), self.assertRaises(ValueError):prepare.workflow_identity(*args, tag=tag)

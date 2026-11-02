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

    def test_rejects_rc_coordinates_and_gate_bypass(self):
        for version, args in [("2.0.0-RC.1", []), ("2.0.0-SNAPSHOT", []), ("2.0.0", ["-DskipTests"]), ("2.0.0", ["--fail-never"])]:
            with self.subTest(version=version, args=args), self.assertRaises(ValueError):
                prepare.inputs(version, "a" * 40, "axiz/framework", args)
        with self.assertRaises(ValueError):
            prepare.inputs("2.0.0", "main", "axiz/framework", [])
        with self.assertRaises(ValueError):
            prepare.inputs("2.0.0", "a" * 40, "axiz/framework", [], candidate="2.0.0-RC.1")

    def test_oidc_binds_the_reusable_workflow_and_actual_source(self):
        claims = {"job_workflow_ref": "axiz/axiz-workflows/.github/workflows/lib-build.yaml@refs/tags/v2.0.0",
                  "job_workflow_sha": "b" * 40, "repository": "axiz/framework", "sha": "a" * 40}
        self.assertEqual("b" * 40, prepare.workflow_identity(claims, "axiz/framework", "a" * 40, "b" * 40)["sha"])
        for key in claims:
            wrong = dict(claims, **{key: "wrong"})
            with self.subTest(key=key), self.assertRaises(ValueError):
                prepare.workflow_identity(wrong, "axiz/framework", "a" * 40, "b" * 40)

    def test_immutable_tag_resolution_includes_annotated_tags_and_rejects_cycles(self):
        release = {"tag_name": "v2.0.0", "immutable": True, "draft": False}
        with patch.object(prepare, "api", side_effect=[release, {"object": {"type": "tag", "sha": "c" * 40}}, {"object": {"type": "commit", "sha": "b" * 40}}]):
            self.assertEqual("b" * 40, prepare.resolve_workflow("v2.0.0"))
        with patch.object(prepare, "api", side_effect=[dict(release, immutable=False)]), self.assertRaises(ValueError):
            prepare.resolve_workflow("v2.0.0")
        tag = {"object": {"type": "tag", "sha": "c" * 40}}
        with patch.object(prepare, "api", side_effect=[release, tag, tag]), self.assertRaises(ValueError):
            prepare.resolve_workflow("v2.0.0")

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

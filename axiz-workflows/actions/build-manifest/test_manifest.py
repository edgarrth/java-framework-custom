import copy
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

import jsonschema

spec = importlib.util.spec_from_file_location("manifest", Path(__file__).with_name("manifest.py"))
manifest = importlib.util.module_from_spec(spec)
spec.loader.exec_module(manifest)


def fixture(kind="maven-library"):
    evidence = {"path": "reports/gates.json", "sha256": manifest.sha256(b"passed\n")}
    data = {
        "schema": "axiz.build-manifest/v1", "kind": kind, "version": "2.0.0",
        "source": {"repository": "example/payments", "sha": "a" * 40},
        "build": {"run_id": 123, "run_attempt": 1, "workflow": {
            "repository": "axiz/axiz-workflows", "path": ".github/workflows/lib-build.yaml",
            "ref": "refs/tags/v2.0.0", "sha": "b" * 40}},
        "parameters": {"jdk": "25", "profiles": ["quality", "release"], "maven_args": [], "modules": ["."]},
        "gates": [{"name": name, "result": "passed", "evidence": evidence} for name in sorted(manifest.REQUIRED_GATES)],
        "sbom": {"path": "reports/bom.json", "sha256": manifest.sha256(b'{"bomFormat":"CycloneDX"}')},
        "provenance": {"path": "reports/provenance.json", "sha256": manifest.sha256(b'{"source":"fixture"}')},
        "artifacts": {"maven": [{"groupId": "pe.axiz", "artifactId": "axiz-example", "version": "2.0.0",
                                   "extension": "jar", "path": "pe/axiz/axiz-example/2.0.0/axiz-example-2.0.0.jar",
                                   "sha256": manifest.sha256(b"artifact bytes")}]}}
    if kind == "service":
        data["gates"].extend({"name": name, "result": "passed", "evidence": evidence} for name in ("contracts", "chart"))
        data["build"]["workflow"]["path"] = ".github/workflows/service-build.yaml"
        data["parameters"] = {"service": "payments", "module": ".", "jdk": "25", "platforms": ["linux/amd64"],
                              "variant": "jvm", "chart": "deploy/helm/payments"}
        data["artifacts"] = {
            "image": {"repository": "registry.example/payments", "digest": "sha256:" + "c" * 64, "tag": "source-label"},
            "chart": {"name": "payments", "version": "2.0.0", "oci": "oci://registry.example/charts/payments",
                      "digest": "sha256:" + "d" * 64}}
        data["dependencies"] = {"axiz_version": "2.0.0", "base_image": "scratch"}
    data["parameters_sha256"] = manifest.canonical_hash(data["parameters"])
    return data


class ManifestTest(unittest.TestCase):
    def test_both_kinds_and_optional_maven_dependency_are_valid(self):
        manifest.validate(fixture())
        library = fixture()
        library["dependencies"] = {"axiz_version": "2.0.0"}
        manifest.validate(library)
        manifest.validate(fixture("service"))

    def test_product_fields_cannot_leak_between_kinds(self):
        bad = []
        library = fixture()
        library["artifacts"]["image"] = fixture("service")["artifacts"]["image"]
        bad.append(library)
        library = fixture()
        library["parameters"]["variant"] = "jvm"
        bad.append(library)
        service = fixture("service")
        service["artifacts"]["maven"] = fixture()["artifacts"]["maven"]
        bad.append(service)
        service = fixture("service")
        del service["artifacts"]["chart"]
        bad.append(service)
        service = fixture("service")
        service["artifacts"]["image"]["digest"] = ""
        bad.append(service)
        library = fixture()
        library["dependencies"] = {"base_image": "scratch"}
        bad.append(library)
        for value in bad:
            with self.subTest(value=value), self.assertRaises(jsonschema.ValidationError):
                manifest.validate(value)

    def test_parameters_use_jcs_and_change_identity_even_when_product_bytes_match(self):
        original = fixture()
        changed = copy.deepcopy(original)
        changed["parameters"]["maven_args"] = ["-Duser.timezone=UTC"]
        with self.assertRaises(ValueError):
            manifest.validate(changed)
        changed["parameters_sha256"] = manifest.canonical_hash(changed["parameters"])
        manifest.validate(changed)
        self.assertEqual(original["artifacts"], changed["artifacts"])
        self.assertNotEqual(original["parameters_sha256"], changed["parameters_sha256"])
        self.assertEqual(manifest.canonical_hash({"b": 2, "a": 1}), manifest.canonical_hash({"a": 1, "b": 2}))

    def test_no_skipped_missing_or_duplicated_gates(self):
        for change in [lambda data: data["gates"].pop(),
                       lambda data: data["gates"].append(data["gates"][0]),
                       lambda data: data["gates"].clear()]:
            data = fixture()
            change(data)
            with self.assertRaises((ValueError, jsonschema.ValidationError)):
                manifest.validate(data)

    def test_service_requires_contract_and_chart_evidence(self):
        for name in ("chart", "contracts"):
            data = fixture("service")
            data["gates"] = [gate for gate in data["gates"] if gate["name"] != name]
            with self.subTest(gate=name), self.assertRaisesRegex(ValueError, "missing"):
                manifest.validate(data)
    def test_maven_parameter_allowlist_rejects_gate_bypasses(self):
        for arg in ["-DskipTests", "-DskipPitest=true", "-Denforcer.skip=true", "-Dpassword=hidden", "-P!quality", "--fail-never", "-fn", "-Dtest=OnlyOneTest",
                    "-Dmaven.test.failure.ignore=true", "-Dmaven.repo.local=/tmp/shared"]:
            data = fixture()
            data["parameters"]["maven_args"] = [arg]
            data["parameters_sha256"] = manifest.canonical_hash(data["parameters"])
            with self.subTest(argument=arg), self.assertRaises(ValueError):
                manifest.validate(data)

    def test_staging_checks_all_bytes_against_an_external_identity(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "reports").mkdir()
            (root / "reports/gates.json").write_bytes(b"passed\n")
            (root / "reports/bom.json").write_bytes(b'{"bomFormat":"CycloneDX"}')
            (root / "reports/provenance.json").write_bytes(b'{"source":"fixture"}')
            data = fixture()
            artifact = data["artifacts"]["maven"][0]
            file = root / "maven" / artifact["path"]
            file.parent.mkdir(parents=True)
            file.write_bytes(b"artifact bytes")
            (root / "maven/SHA256SUMS").write_text(artifact["sha256"] + "  " + artifact["path"] + "\n")
            location = root / "build-manifest.json"
            location.write_text(json.dumps(data))
            expected = manifest.sha256(location.read_bytes())
            manifest.verify(location, expected, root, "example/payments", "a" * 40)
            file.write_bytes(b"tampered")
            with self.assertRaisesRegex(ValueError, "content hash"):
                manifest.verify(location, expected, root, "example/payments")
            # An attacker updating the file, manifest and checksum inside one archive still fails the external identity.
            artifact["sha256"] = manifest.sha256(file.read_bytes())
            (root / "maven/SHA256SUMS").write_text(artifact["sha256"] + "  " + artifact["path"] + "\n")
            location.write_text(json.dumps(data))
            with self.assertRaisesRegex(ValueError, "externally"):
                manifest.verify(location, expected, root, "example/payments")

    def test_paths_coordinates_and_producers_are_bound(self):
        for path in ["../outside.jar", "/absolute.jar", "pe/axiz/other/2.0.0/other-2.0.0.jar"]:
            data = fixture()
            data["artifacts"]["maven"][0]["path"] = path
            with self.assertRaises((ValueError, jsonschema.ValidationError)):
                manifest.validate(data)
        data = fixture()
        data["artifacts"]["maven"][0]["version"] = "2.0.1"
        with self.assertRaises(ValueError):
            manifest.validate(data)
        data = fixture()
        data["build"]["workflow"]["path"] = ".github/workflows/service-build.yaml"
        with self.assertRaises(ValueError):
            manifest.validate(data)

    def test_platform_index_is_bound_to_every_requested_platform(self):
        data = fixture("service")
        data["parameters"]["platforms"] = ["linux/amd64", "linux/arm64"]
        data["parameters_sha256"] = manifest.canonical_hash(data["parameters"])
        with self.assertRaisesRegex(ValueError, "every platform"):
            manifest.validate(data)
        data["artifacts"]["image"]["platforms"] = {"linux/amd64": "sha256:" + "1" * 64,
                                                   "linux/arm64": "sha256:" + "2" * 64}
        manifest.validate(data)
        data["artifacts"]["image"]["platforms"]["linux/riscv64"] = "sha256:" + "3" * 64
        with self.assertRaisesRegex(ValueError, "exactly"):
            manifest.validate(data)

    def test_version_controlled_valid_and_invalid_schema_cases(self):
        fixtures = manifest.SCHEMA_ROOT / "fixtures"
        for path in sorted(fixtures.glob("*.json")):
            with self.subTest(fixture=path.name):
                if path.name.startswith("valid-"):
                    manifest.validate(manifest.read_json(path))
                else:
                    with self.assertRaises((jsonschema.ValidationError, ValueError)):
                        manifest.validate(manifest.read_json(path))

    def test_duplicate_json_keys_are_not_accepted(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "manifest.json"
            path.write_text('{"kind":"service","kind":"maven-library"}')
            with self.assertRaises(ValueError):
                manifest.read_json(path)


if __name__ == "__main__":
    unittest.main()

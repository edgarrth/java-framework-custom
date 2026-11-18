import importlib.util
import io
import json
import tempfile
import unittest
import urllib.error
from pathlib import Path
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("api_gate", Path(__file__).with_name("check-api-compatibility.py"))
gate = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(gate)


class ApiCompatibilityTest(unittest.TestCase):
    policy = {"schema": 1, "mode": "first-release", "version": "2.0.0", "baseline": None}

    registry = "https://maven.pkg.github.com/example/framework"

    def setUp(self):
        patcher = patch.dict("os.environ", {"PACKAGES_TOKEN": "fixture-token", "PACKAGES_USERNAME": "example"})
        patcher.start()
        self.addCleanup(patcher.stop)

    def item(self, **changes):
        return {"id": 1, "package_type": "maven", "name": "pe.axiz.axiz-core-audit",
                "repository": {"full_name": "example/framework"}, **changes}

    def opener(self, packages=(), versions=()):
        def request(request, **kwargs):
            self.assertEqual("Bearer fixture-token", request.get_header("Authorization"))
            url = request.full_url
            if "/repos/" in url:
                value = {"full_name": "example/framework", "owner": {"type": "User"}}
            elif "/versions?" in url:
                value = list(versions)
            else:
                value = list(packages)
            return io.BytesIO(json.dumps(value).encode())
        return request

    def test_first_release_only_when_the_product_has_no_published_v2(self):
        gate.validate_first_release(self.policy, set())
        with self.assertRaisesRegex(ValueError, "already has published"):
            gate.validate_first_release(self.policy, {"2.0.0"})
        for version in ["1.0.0", "2.0.1", "3.0.0"]:
            with self.assertRaises(ValueError):
                gate.validate_first_release({**self.policy, "version": version}, set())

    def test_search_authenticates_and_filters_exact_repository_and_coordinates(self):
        packages = [self.item(id=1, name="pe.axiz.other"),
                    self.item(id=2, repository={"full_name": "example/other"}), self.item(id=3)]
        versions = gate.published_versions(self.registry, {"axiz-core-audit"},
            self.opener(packages, [{"id": 10, "name": "2.0.0"}, {"id": 11, "name": "1.0.0"}]))
        self.assertEqual({"2.0.0"}, versions)
        self.assertEqual(set(), gate.published_versions(self.registry, {"axiz-core-audit"}, self.opener()))

    def test_pagination_includes_later_pages_and_rejects_repeated_ids(self):
        seen = []
        def opener(request, **kwargs):
            seen.append(request.full_url)
            page = [{"id": n} for n in range(1, 101)] if "&page=1" in request.full_url else [{"id": 101}]
            return io.BytesIO(json.dumps(page).encode())
        self.assertEqual(101, len(list(gate.package_pages("users/example/packages?package_type=maven", opener))))
        self.assertIn("page=2", seen[-1])
        with self.assertRaisesRegex(ValueError, "pagination"):
            list(gate.package_pages("users/example/packages", lambda *a, **k: io.BytesIO(json.dumps([{"id": n} for n in range(1,101)]).encode())))

    def test_authentication_and_network_errors_never_mean_empty_registry(self):
        for code in [401, 403, 404, 429, 500]:
            def fail(*args, **kwargs):
                raise urllib.error.HTTPError("https://api.github.com/", code, "denied", {}, None)
            with self.subTest(code=code), self.assertRaises(OSError):
                gate.published_versions(self.registry, {"axiz-core-audit"}, fail)
        with patch.dict("os.environ", {"PACKAGES_TOKEN": ""}), self.assertRaisesRegex(ValueError, "mandatory"):
            gate.published_versions(self.registry, set(), self.opener())

    def test_repository_and_package_metadata_fail_closed(self):
        with self.assertRaisesRegex(ValueError, "missing or ambiguous"):
            gate.published_versions(self.registry, set(), lambda *a, **k: io.BytesIO(b"{}"))
        for key, value in [("name", None), ("repository", None), ("package_type", "container"), ("id", None)]:
            with self.subTest(key=key), self.assertRaises(ValueError):
                gate.published_versions(self.registry, {"axiz-core-audit"}, self.opener([self.item(**{key:value})]))
        for invalid in ["http://maven.pkg.github.com/a/b", "https://evil.test/a/b",
                        "https://maven.pkg.github.com/a/b?token=x", "https://secret@maven.pkg.github.com/a/b"]:
            with self.assertRaises(ValueError):
                gate.published_versions(invalid, set(), self.opener())

    def test_a_v2_prerelease_also_forbids_bootstrap(self):
        versions = gate.published_versions(self.registry, {"axiz-core-audit"},
            self.opener([self.item()], [{"id": 2, "name": "2.0.0-rc.1"}]))
        with self.assertRaises(ValueError):
            gate.validate_first_release(self.policy, versions)

    def test_signature_changes_and_missing_modules_require_review(self):
        observed = {"schema": 1, "version": "2.0.0", "artifacts": {"demo": {"apiSha256": "approved", "jarSha256": "actual"}}}
        approved = {"schema": 1, "version": "2.0.0", "artifacts": {"demo": {"apiSha256": "approved"}}}
        gate.check_signatures(observed, approved)
        with self.assertRaises(ValueError):
            gate.check_signatures(observed, {**approved, "artifacts": {}})
        with self.assertRaises(ValueError):
            gate.check_signatures(observed, {**approved, "artifacts": {"demo": {"apiSha256": "changed"}}})

    def test_published_comparison_cannot_resolve_to_self_or_pre_v2(self):
        for baseline in ["1.0.0", "2.0.0", "3.0.0", "2.0.0-SNAPSHOT"]:
            with self.assertRaises(ValueError):
                gate.check_comparisons(Path("."), baseline, "2.0.0")

    def test_baseline_bytes_come_from_final_repository_and_tampering_is_rejected(self):
        import zipfile
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "pom.xml").write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"><artifactId>axiz-core-demo</artifactId></project>')
            jar = io.BytesIO()
            with zipfile.ZipFile(jar, "w") as archive:
                archive.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n")
            seen = []
            def download(request, **kwargs):
                seen.append(request.full_url)
                return io.BytesIO(jar.getvalue())
            directory = root / "pinned"
            gate.release_baseline(root, "https://maven.pkg.github.com/example/framework", "2.0.0", directory, download)
            self.assertEqual(["https://maven.pkg.github.com/example/framework/pe/axiz/axiz-core-demo/2.0.0/axiz-core-demo-2.0.0.jar"], seen)
            gate.verify_baseline(root, "https://maven.pkg.github.com/example/framework", "2.0.0", directory)
            (directory / "axiz-core-demo-2.0.0.jar").write_bytes(b"candidate cache substitution")
            with self.assertRaisesRegex(ValueError, "modified"):
                gate.verify_baseline(root, "https://maven.pkg.github.com/example/framework", "2.0.0", directory)

    def test_post_release_gate_checks_real_report_identity_and_compatibility(self):
        import os
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "pom.xml").write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"><artifactId>axiz-core-demo</artifactId></project>')
            reports = root / "target/japicmp"
            reports.mkdir(parents=True)
            (root / "target/axiz-core-demo-2.0.1.jar").write_bytes(b"current")
            report = reports / "api-compat.xml"
            valid = '<japicmp oldVersion="2.0.0" newVersion="2.0.1" oldJar="axiz-core-demo-2.0.0.jar" newJar="axiz-core-demo-2.0.1.jar" ignoreMissingClasses="false" accessModifier="PROTECTED"><classes/></japicmp>'
            report.write_text(valid)
            gate.check_comparisons(root, "2.0.0", "2.0.1")
            report.write_text(valid.replace('<classes/>', '<classes><class binaryCompatible="false"/></classes>'))
            with self.assertRaisesRegex(ValueError, "Incompatible"):
                gate.check_comparisons(root, "2.0.0", "2.0.1")
            report.write_text(valid.replace('oldVersion="2.0.0"', 'oldVersion="2.0.1"'))
            with self.assertRaises(ValueError):
                gate.check_comparisons(root, "2.0.0", "2.0.1")
            report.write_text(valid)
            os.utime(report, (0, 0))
            with self.assertRaisesRegex(ValueError, "stale"):
                gate.check_comparisons(root, "2.0.0", "2.0.1")

    def test_reviewed_signatures_are_readable_evidence_bound_to_the_inventory(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            signatures = root / "docs/api/signatures"
            signatures.mkdir(parents=True)
            text = b"public interface Api { public abstract void read(); }\n"
            approved = {"artifacts": {"axiz-demo": {"apiSha256": gate.sha(text)}}}
            (signatures / "axiz-demo.txt").write_bytes(text)
            gate.verify_reviewed_signatures(root, approved)
            (signatures / "axiz-demo.txt").write_text("unreviewed")
            with self.assertRaisesRegex(ValueError, "differ"):
                gate.verify_reviewed_signatures(root, approved)

    def test_real_javap_detects_an_actual_signature_change(self):
        import subprocess
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "Api.java"
            source.write_text('public class Api { public String read() { return "ok"; } private void secret() {} }')
            subprocess.run(["javac", str(source)], check=True, capture_output=True)
            jar = root / "api.jar"
            subprocess.run(["jar", "cf", str(jar), "-C", str(root), "Api.class"], check=True, capture_output=True)
            before = gate.public_api(jar)
            self.assertIn("java.lang.String read()", before)
            self.assertNotIn("secret", before)
            source.write_text('public class Api { public long read() { return 1; } }')
            subprocess.run(["javac", str(source)], check=True, capture_output=True)
            subprocess.run(["jar", "cf", str(jar), "-C", str(root), "Api.class"], check=True, capture_output=True)
            self.assertNotEqual(before, gate.public_api(jar))

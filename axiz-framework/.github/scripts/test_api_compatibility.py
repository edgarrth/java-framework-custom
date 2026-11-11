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

    def setUp(self):
        self.original_search = gate.published_versions
        def search(nexus, coordinates, opener):
            def metadata_aware(request, **kwargs):
                if request.full_url.endswith("/repositories/maven-releases"):
                    return io.BytesIO(b'{"name":"maven-releases","format":"maven2","type":"hosted"}')
                return opener(request, **kwargs)
            return self.original_search(nexus, coordinates, metadata_aware)
        patcher = patch.object(gate, "published_versions", side_effect=search)
        patcher.start()
        self.addCleanup(patcher.stop)

    def response(self, items=(), token=None):
        return io.BytesIO(json.dumps({"items": list(items), "continuationToken": token}).encode())

    def item(self, name="axiz-core-audit", version="2.0.0", repository="maven-releases"):
        return {"group": "pe.axiz", "name": name, "version": version, "repository": repository}

    def test_first_release_only_when_the_product_has_no_published_v2(self):
        gate.validate_first_release(self.policy, set())
        with self.assertRaisesRegex(ValueError, "already has published"):
            gate.validate_first_release(self.policy, {"2.0.0"})
        for version in ["1.0.0", "2.0.1", "3.0.0"]:
            with self.assertRaises(ValueError):
                gate.validate_first_release({**self.policy, "version": version}, set())

    def test_search_uses_only_final_repository_and_product_coordinates(self):
        seen = []
        def opener(request, **kwargs):
            seen.append(request.full_url)
            return self.response([self.item("other-product")])
        self.assertEqual(set(), gate.published_versions("https://nexus.example", {"axiz-core-audit"}, opener))
        self.assertIn("repository=maven-releases", seen[0])
        self.assertNotIn("staging", seen[0])

    def test_published_release_is_detected_on_later_pages(self):
        pages = iter([self.response(token="page2"), self.response([self.item()])])
        versions = gate.published_versions("https://nexus.example", {"axiz-core-audit"}, lambda *a, **k: next(pages))
        self.assertEqual({"2.0.0"}, versions)
        with self.assertRaises(ValueError):
            gate.validate_first_release(self.policy, versions)

    def test_query_failure_or_ambiguous_metadata_never_bootstraps(self):
        def fail(*a, **k):
            raise urllib.error.URLError("unavailable")
        with self.assertRaises(OSError):
            gate.published_versions("https://nexus.example", {"axiz-core-audit"}, fail)
        for data in [{}, {"items": []}, {"items": [{}], "continuationToken": None}]:
            with self.assertRaises(ValueError):
                gate.published_versions("https://nexus.example", set(), lambda *a, **k: io.BytesIO(json.dumps(data).encode()))
        with self.assertRaisesRegex(ValueError, "different repository"):
            gate.published_versions("https://nexus.example", {"axiz-core-audit"},
                                    lambda *a, **k: self.response([self.item(repository="staging")]))

    def test_final_repository_must_exist_and_be_hosted_maven(self):
        for metadata in [{}, {"name": "maven-releases", "format": "maven2", "type": "proxy"}]:
            with self.assertRaisesRegex(ValueError, "missing or ambiguous"):
                self.original_search("https://nexus.example", set(), lambda *a, **k: io.BytesIO(json.dumps(metadata).encode()))

    def test_malformed_component_metadata_cannot_appear_as_an_empty_repository(self):
        for key in ("repository", "group", "name", "version"):
            for value in (None, 2, {}, "", " "):
                with self.subTest(key=key, value=value), self.assertRaisesRegex(ValueError, "Invalid publication metadata"):
                    gate.published_versions("https://nexus.example", {"axiz-core-audit"},
                        lambda *a, **k: self.response([{**self.item(), key: value}]))

    def test_a_v2_prerelease_in_final_repository_also_forbids_bootstrap(self):
        versions = gate.published_versions("https://nexus.example", {"axiz-core-audit"},
            lambda *a, **k: self.response([self.item(version="2.0.0-rc.1")]))
        with self.assertRaises(ValueError):
            gate.validate_first_release(self.policy, versions)

    def test_repeated_continuation_token_fails_closed(self):
        with self.assertRaisesRegex(ValueError, "pagination"):
            gate.published_versions("https://nexus.example", set(), lambda *a, **k: self.response(token="loop"))

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
            gate.release_baseline(root, "https://nexus.example", "2.0.0", directory, download)
            self.assertEqual(["https://nexus.example/repository/maven-releases/pe/axiz/axiz-core-demo/2.0.0/axiz-core-demo-2.0.0.jar"], seen)
            gate.verify_baseline(root, "https://nexus.example", "2.0.0", directory)
            (directory / "axiz-core-demo-2.0.0.jar").write_bytes(b"candidate cache substitution")
            with self.assertRaisesRegex(ValueError, "modified"):
                gate.verify_baseline(root, "https://nexus.example", "2.0.0", directory)

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

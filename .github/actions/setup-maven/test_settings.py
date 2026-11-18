import importlib.util
import json
import sys
import tempfile
import unittest
from defusedxml import ElementTree as ET
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "build-manifest"))
from test_manifest import fixture, manifest

spec = importlib.util.spec_from_file_location("maven_settings", Path(__file__).with_name("settings.py"))
settings = importlib.util.module_from_spec(spec)
spec.loader.exec_module(settings)


class SettingsTest(unittest.TestCase):
    def test_packages_repository_and_credentials_are_environment_references(self):
        with tempfile.TemporaryDirectory() as tmp:
            file = Path(tmp) / "settings.xml"
            settings.create(file, "https://maven.pkg.github.com/owner/repo", tmp)
            root = ET.parse(file).getroot()
            self.assertIsNone(root.find("mirrors"))
            self.assertEqual("github", root.findtext("profiles/profile/repositories/repository/id"))
            self.assertIsNone(root.find("localRepository"))
            self.assertEqual("${env.PACKAGES_TOKEN}", root.findtext("servers/server/password"))
            self.assertEqual(0o600, file.stat().st_mode & 0o777)

    def test_candidate_inputs_are_complete_and_url_has_no_credentials(self):
        with tempfile.TemporaryDirectory() as tmp:
            for kwargs in [{"candidate": Path(tmp)}, {"expected_hash": "a" * 64}]:
                with self.assertRaisesRegex(ValueError, "supplied together"):
                    settings.create(Path(tmp) / "settings.xml", "https://maven.pkg.github.com/owner/repo", tmp, **kwargs)
            with self.assertRaises(ValueError):
                settings.create(Path(tmp) / "settings.xml", "https://user:secret@maven.pkg.github.com/owner/repo", tmp)

    def test_candidate_is_verified_copied_read_only_and_never_uses_cached_m2(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            bundle = base / "bundle"
            data = fixture()
            for relative, content in {"reports/gates.json": b"passed\n",
                    "reports/bom.json": b'{"bomFormat":"CycloneDX"}',
                    "reports/provenance.json": b'{"source":"fixture"}',
                    "maven/pe/axiz/axiz-example/2.0.0/axiz-example-2.0.0.jar": b"artifact bytes"}.items():
                file = bundle / relative
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_bytes(content)
            artifact = data["artifacts"]["maven"][0]
            (bundle / "maven/SHA256SUMS").write_text(artifact["sha256"] + "  " + artifact["path"] + "\n")
            location = bundle / "build-manifest.json"
            location.write_text(json.dumps(data))
            expected = manifest.sha256(location.read_bytes())
            copied, local = settings.create(base / "settings.xml", "https://maven.pkg.github.com/owner/repo", base,
                bundle, expected, "example/payments")
            self.assertNotEqual(bundle / "maven", copied)
            self.assertEqual(b"artifact bytes", (copied / artifact["path"]).read_bytes())
            self.assertEqual(0o444, (copied / artifact["path"]).stat().st_mode & 0o777)
            self.assertEqual(0o555, copied.stat().st_mode & 0o777)
            root = ET.parse(base / "settings.xml").getroot()
            self.assertIsNone(root.find("mirrors"))
            self.assertEqual("axiz-candidate", root.findtext("profiles/profile/repositories/repository/id"))
            for section in ["repositories", "pluginRepositories"]:
                self.assertEqual(["axiz-candidate", "github"],
                                 [r.findtext("id") for r in root.find("profiles/profile/" + section)])
            self.assertEqual(str(local), root.findtext("localRepository"))
            self.assertEqual([], list(local.iterdir()))
            # A second run with identical final coordinates must have its own empty local repository.
            other_copy, other_local = settings.create(base / "settings2.xml", "https://maven.pkg.github.com/owner/repo", base,
                bundle, expected, "example/payments")
            self.assertNotEqual(local, other_local)
            self.assertNotEqual(copied, other_copy)
            (bundle / "maven" / artifact["path"]).write_bytes(b"tampered")
            with self.assertRaisesRegex(ValueError, "content hash"):
                settings.create(base / "bad.xml", "https://maven.pkg.github.com/owner/repo", base,
                    bundle, expected, "example/payments")
            # Temporary copies are read-only by contract; make them writable for cleanup.
            for copy in [copied, other_copy]:
                copy.chmod(0o755)
                for path in copy.rglob("*"):
                    path.chmod(0o755 if path.is_dir() else 0o644)


if __name__ == "__main__":
    unittest.main()

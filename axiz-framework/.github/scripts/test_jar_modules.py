import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile

SPEC = importlib.util.spec_from_file_location("jar_modules", Path(__file__).with_name("check-jar-modules.py"))
gate = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(gate)


class JarModulesTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.root.joinpath("pom.xml").write_text('''<project xmlns="http://maven.apache.org/POM/4.0.0">
          <artifactId>reactor</artifactId><version>2.0.0</version><packaging>pom</packaging>
          <modules><module>library</module><module>service</module></modules></project>''')
        self.project("library", "axiz-parent-core", "pe.axiz.example")
        self.project("service", "axiz-parent-service", None)
        self.jar("library", "Automatic-Module-Name: pe.axiz.example\r\n")
        self.jar("service", "Main-Class: example.Service\r\n")

    def project(self, artifact, parent, module):
        directory = self.root / artifact
        directory.mkdir(exist_ok=True)
        properties = f"<properties><axiz.module.name>{module}</axiz.module.name></properties>" if module else ""
        (directory / "pom.xml").write_text(f'''<project xmlns="http://maven.apache.org/POM/4.0.0">
          <parent><artifactId>{parent}</artifactId></parent><artifactId>{artifact}</artifactId>
          <version>2.0.0</version>{properties}</project>''')

    def jar(self, artifact, attributes):
        target = self.root / artifact / "target"
        target.mkdir(exist_ok=True)
        with zipfile.ZipFile(target / f"{artifact}-2.0.0.jar", "w") as jar:
            jar.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\n" + attributes + "\r\n")

    def test_final_library_and_boot_application_have_distinct_manifest_contracts(self):
        observed = gate.verify([self.root])
        self.assertEqual(["pe.axiz.example", None], [row["module"] for row in observed["jars"]])
        self.assertTrue(all(len(row["sha256"]) == 64 for row in observed["jars"]))
        # A stale, unrelated artifact must not enter the reactor inventory.
        (self.root / "stale.jar").write_bytes(b"not a jar")
        self.assertEqual(2, len(gate.verify([self.root])["jars"]))

    def test_source_property_cannot_substitute_a_missing_or_wrong_manifest(self):
        for value in ("", "Automatic-Module-Name: pe.axiz.other\r\n"):
            self.jar("library", value)
            with self.subTest(value=value), self.assertRaises(ValueError):
                gate.verify([self.root])

    def test_service_must_not_inherit_a_library_module_identity(self):
        self.jar("service", "Automatic-Module-Name: pe.axiz.service\r\n")
        with self.assertRaisesRegex(ValueError, "Service JAR"):
            gate.verify([self.root])

    def test_no_two_library_jars_may_publish_the_same_identity(self):
        self.project("service", "axiz-parent-core", "pe.axiz.example")
        self.jar("service", "Automatic-Module-Name: pe.axiz.example\r\n")
        with self.assertRaisesRegex(ValueError, "Duplicate JPMS"):
            gate.verify([self.root])

    def test_manifest_continuations_and_main_section_boundaries_are_respected(self):
        self.jar("library", "Automatic-Module-Name: pe.axiz.\r\n example\r\n\r\nName: entry\r\nAutomatic-Module-Name: ignored\r\n")
        self.assertEqual("pe.axiz.example", gate.verify([self.root])["jars"][0]["module"])

    def test_repeated_attributes_and_unbuilt_artifacts_fail_closed(self):
        self.jar("library", "Automatic-Module-Name: pe.axiz.example\r\nautomatic-module-name: pe.axiz.other\r\n")
        with self.assertRaises(ValueError):
            gate.verify([self.root])
        (self.root / "library/target/library-2.0.0.jar").unlink()
        with self.assertRaises(OSError):
            gate.verify([self.root])

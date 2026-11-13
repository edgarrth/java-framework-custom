import tempfile
import unittest
from pathlib import Path
from defusedxml import ElementTree as ET

import maven_version

NS = {"m": "http://maven.apache.org/POM/4.0.0"}


class MavenVersionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def write(self, name, body):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text('<project xmlns="http://maven.apache.org/POM/4.0.0">' + body + '</project>')
        return path

    def parse(self, name):
        return ET.parse(self.root / name).getroot()

    def test_updates_internal_reactor_references_but_preserves_the_axiz_parent_and_bom(self):
        self.write("pom.xml", '''<groupId>example</groupId><artifactId>parent</artifactId><version>0.1.0-SNAPSHOT</version>
          <modules><module>library</module><module>service</module></modules>''')
        self.write("library/pom.xml", '''<!-- preserve this comment -->
          <parent><groupId>pe.axiz</groupId><artifactId>axiz-parent-core</artifactId><version>2.0.0</version></parent>
          <groupId>example</groupId><artifactId>library</artifactId><version>0.1.0-SNAPSHOT</version>''')
        self.write("service/pom.xml", '''<parent><groupId>example</groupId><artifactId>parent</artifactId><version>0.1.0-SNAPSHOT</version></parent>
          <artifactId>service</artifactId><dependencies>
            <dependency><groupId>${project.groupId}</groupId><artifactId>library</artifactId><version>0.1.0-SNAPSHOT</version></dependency>
            <dependency><groupId>third.party</groupId><artifactId>driver</artifactId><version>3.2.1</version></dependency>
          </dependencies><dependencyManagement><dependencies>
            <dependency><groupId>pe.axiz</groupId><artifactId>axiz-bom</artifactId><version>2.0.0</version></dependency>
          </dependencies></dependencyManagement>''')
        self.assertEqual(3, len(maven_version.update(self.root, "0.4.0")))
        library, service = self.parse("library/pom.xml"), self.parse("service/pom.xml")
        self.assertEqual("2.0.0", library.findtext("m:parent/m:version", namespaces=NS))
        self.assertEqual("0.4.0", library.findtext("m:version", namespaces=NS))
        self.assertIn("preserve this comment", (self.root / "library/pom.xml").read_text())
        self.assertEqual("0.4.0", service.findtext("m:parent/m:version", namespaces=NS))
        self.assertIsNone(service.findtext("m:version", namespaces=NS))
        self.assertEqual(["0.4.0", "3.2.1"], [node.text for node in service.findall("m:dependencies/m:dependency/m:version", NS)])
        self.assertEqual("2.0.0", service.findtext("m:dependencyManagement/m:dependencies/m:dependency/m:version", namespaces=NS))

    def test_supports_ci_friendly_revision_without_replacing_the_expression(self):
        self.write("pom.xml", "<groupId>example</groupId><artifactId>library</artifactId><version>${revision}</version><properties><revision>0.1.0-SNAPSHOT</revision></properties><modules><module>child</module></modules>")
        self.write("child/pom.xml", "<parent><groupId>example</groupId><artifactId>library</artifactId><version>${revision}</version></parent><artifactId>child</artifactId>")
        maven_version.update(self.root, "1.2.0")
        project = self.parse("pom.xml")
        self.assertEqual("${revision}", project.findtext("m:version", namespaces=NS))
        self.assertEqual("1.2.0", project.findtext("m:properties/m:revision", namespaces=NS))
        self.assertEqual("${revision}", self.parse("child/pom.xml").findtext("m:parent/m:version", namespaces=NS))
        self.assertIsNone(self.parse("child/pom.xml").findtext("m:version", namespaces=NS))

    def test_invalid_or_escaping_module_fails_before_changing_any_source_file(self):
        source = self.write("pom.xml", "<groupId>example</groupId><artifactId>parent</artifactId><version>0.1.0</version><modules><module>../outside</module></modules>")
        before = source.read_bytes()
        with self.assertRaises(ValueError):
            maven_version.update(self.root, "1.2.0")
        self.assertEqual(before, source.read_bytes())
        with self.assertRaises(ValueError):
            maven_version.update(self.root, "1.2.0-SNAPSHOT")


if __name__ == "__main__":
    unittest.main()

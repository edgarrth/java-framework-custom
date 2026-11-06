import importlib.util
import json
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET
from pathlib import Path

SPEC = importlib.util.spec_from_file_location("api_gate", Path(__file__).with_name("check-api-compatibility.py"))
gate = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(gate)


class ApiCompatibilityTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        docs = self.root / "docs/migration/2.0"
        docs.mkdir(parents=True)
        (docs / "renames.json").write_text('{"modules": []}')
        (docs / "new-modules.txt").write_text("")
        self.module = self.root / "core/axiz-core-demo"
        self.module.mkdir(parents=True)
        self.pom()
        target = self.module / "target"
        (target / "japicmp").mkdir(parents=True)
        (target / "axiz-core-demo-2.0.0.jar").write_bytes(b"fixture")
        self.report = target / "japicmp/api-compat.xml"
        self.xml = ET.fromstring('''<japicmp oldVersion="1.0.0" newVersion="2.0.0"
            oldJar="axiz-core-demo-1.0.0.jar" newJar="axiz-core-demo-2.0.0.jar"
            ignoreMissingClasses="false" accessModifier="PROTECTED"><classes>
            <class fullyQualifiedName="pe.axiz.core.demo.Port" binaryCompatible="false" sourceCompatible="false">
              <methods><method name="read" binaryCompatible="false" oldLineNumber="10" newLineNumber="20">
                <parameters><parameter type="java.lang.String"/><parameter type="long"/></parameters>
                <returnType oldValue="java.lang.String" newValue="long"/>
              </method></methods>
            </class></classes></japicmp>''')
        self.save()
        self.changes = [{"artifact": "axiz-core-demo", "class": "pe.axiz.core.demo.Port",
                         "sha256": gate.fingerprint(self.xml.find("classes/class")), "reason": "Explicit reviewed fixture"}]
        self.inventory()

    def pom(self, properties=""):
        (self.module / "pom.xml").write_text('''<project xmlns="http://maven.apache.org/POM/4.0.0">
            <artifactId>axiz-core-demo</artifactId><properties>''' + properties + '</properties></project>')

    def save(self):
        ET.ElementTree(self.xml).write(self.report)

    def inventory(self):
        (self.root / "docs/migration/2.0/api-changes.md").write_text("# Reviewed\n```json\n" + json.dumps({
            "schema": 1, "baseline": "1.0.0", "version": "2.0.0", "changes": self.changes}) + "\n```\n")

    def check(self):
        return gate.check(self.root, "1.0.0")

    def test_partial_extraction_uses_its_real_predecessor_without_renaming_the_original_module(self):
        self.pom('<axiz.api.baseline.artifactId>axiz-core-legacy</axiz.api.baseline.artifactId>')
        (self.root / "docs/migration/2.0/renames.json").write_text(json.dumps({
            "modules": [], "extractions": [{"oldArtifact": "axiz-core-legacy", "newArtifact": "axiz-core-demo"}]}))
        self.xml.set("oldJar", "axiz-core-legacy-1.0.0.jar")
        self.save()
        self.assertEqual([], self.check())

    def test_accepts_reviewed_break(self):
        self.assertEqual([], self.check())

    def test_rejects_unreviewed_break_and_parameter_order_change(self):
        params = self.xml.find(".//parameters")
        params[:] = list(reversed(params))
        self.save()
        self.assertTrue(any("Unreviewed API break" in e for e in self.check()))

    def test_ignores_line_numbers_and_formatting(self):
        self.xml.find(".//method").set("oldLineNumber", "999")
        ET.indent(self.xml)
        self.save()
        self.assertEqual([], self.check())

    def test_rejects_missing_report_and_stale_inventory(self):
        self.report.unlink()
        errors = self.check()
        self.assertTrue(any("mandatory japicmp" in e for e in errors))
        self.assertTrue(any("Stale API inventory" in e for e in errors))

    def test_rejects_missing_classes_and_wrong_baseline(self):
        self.xml.set("ignoreMissingClasses", "true")
        self.xml.set("oldVersion", "2.0.0")
        self.save()
        self.assertTrue(any("ignoreMissingClasses" in e for e in self.check()))
        self.assertTrue(any("oldVersion" in e for e in self.check()))

    def test_rejects_a_skipped_published_module(self):
        self.pom('<axiz.api.skip>true</axiz.api.skip>')
        self.assertTrue(any("may not be skipped" in e for e in self.check()))

    def new_module(self):
        self.pom('<axiz.api.skip>true</axiz.api.skip><axiz.api.new-module>true</axiz.api.new-module>')
        (self.root / "docs/migration/2.0/new-modules.txt").write_text("axiz-core-demo\n")
        self.changes = []
        self.inventory()
        reference = self.root / "docs/migration/2.0/new-api/axiz-core-demo.txt"
        reference.parent.mkdir()
        reference.write_text("reviewed signatures\n")
        return reference

    def test_new_module_checks_its_actual_api_without_a_fake_historical_report(self):
        self.new_module()
        self.report.unlink()
        with patch.object(gate, "public_api", return_value="reviewed signatures\n"):
            self.assertEqual([], self.check())
        with patch.object(gate, "public_api", return_value="changed signatures\n"):
            self.assertTrue(any("differs from its reviewed signatures" in error for error in self.check()))

    def test_new_module_cannot_hide_a_missing_jar_or_missing_review(self):
        reference = self.new_module()
        reference.unlink()
        self.assertTrue(any("missing reviewed public API" in e for e in self.check()))
        (self.module / "target/axiz-core-demo-2.0.0.jar").unlink()
        self.assertTrue(any("missing current JAR" in e for e in self.check()))

    def test_new_module_opt_out_requires_the_approved_inventory(self):
        self.pom('<axiz.api.skip>true</axiz.api.skip><axiz.api.new-module>true</axiz.api.new-module>')
        self.assertTrue(any("may not be skipped" in e for e in self.check()))

    def test_requires_approved_predecessor_coordinate(self):
        self.pom('<axiz.api.baseline.artifactId>invented</axiz.api.baseline.artifactId>')
        self.assertTrue(any("predecessor differs" in e for e in self.check()))

    def test_rejects_report_older_than_jar(self):
        import os
        jar = self.module / "target/axiz-core-demo-2.0.0.jar"
        newer = self.report.stat().st_mtime_ns + 1_000_000_000
        os.utime(jar, ns=(newer, newer))
        self.assertTrue(any("predates" in e for e in self.check()))

    def test_relocation_does_not_hide_changed_or_removed_replacement_members(self):
        replacement = ET.SubElement(self.xml.find("classes"), "class", {
            "fullyQualifiedName": "pe.axiz.core.demo.Replacement", "binaryCompatible": "true", "sourceCompatible": "true"})
        method = ET.SubElement(replacement, "method", {"name": "read", "binaryCompatible": "true"})
        self.changes[0]["replacement"] = {"artifact": "axiz-core-demo", "class": "pe.axiz.core.demo.Replacement",
                                          "sha256": gate.fingerprint(replacement)}
        self.inventory()
        self.save()
        self.assertEqual([], self.check())
        replacement.remove(method)
        self.save()
        self.assertTrue(any("relocated public API differs" in e for e in self.check()))

    def test_rejects_duplicate_inventory_entries(self):
        self.changes.append(dict(self.changes[0]))
        self.inventory()
        self.assertTrue(any("Duplicate API inventory" in e for e in self.check()))

    def test_new_types_do_not_break_an_api_that_did_not_exist(self):
        ET.SubElement(self.xml.find("classes"), "class", {
            "fullyQualifiedName": "pe.axiz.core.demo.NewPort", "changeStatus": "NEW",
            "binaryCompatible": "false", "sourceCompatible": "false"})
        self.save()
        self.assertEqual([], self.check())


if __name__ == "__main__":
    unittest.main()

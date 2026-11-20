import contextlib
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location(
    "coverage_aggregate", Path(__file__).with_name("check-coverage-aggregate.py"))
gate = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(gate)


class CoverageInventoryTest(unittest.TestCase):
    def test_cross_module_test_execution_cannot_be_omitted_or_treated_as_production(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            module = root / 'integration-tests'
            (module / 'src/test/java').mkdir(parents=True)
            (module / 'src/test/java/StoreIT.java').write_text('class StoreIT {}')
            (module / 'pom.xml').write_text(
                '<project><artifactId>integration-tests</artifactId></project>')
            report = root / gate.REPORT_POM
            report.parent.mkdir(parents=True)
            for scope, expected in ((None, 1), ('provided', 1), ('test', 0)):
                dependency = '' if scope is None else (
                    '<dependency><groupId>pe.axiz</groupId>'
                    '<artifactId>integration-tests</artifactId>'
                    f'<scope>{scope}</scope></dependency>')
                report.write_text(f'<project><dependencies>{dependency}</dependencies></project>')
                with self.subTest(scope=scope), contextlib.redirect_stdout(io.StringIO()):
                    self.assertEqual(expected, gate.check_inventory(root))

    def test_scopes_belong_to_their_own_dependency_and_cannot_hide_missing_modules(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ("transport", "core", "missing"):
                module = root / name
                (module / "src/main/java").mkdir(parents=True)
                (module / "src/main/java/Example.java").write_text("class Example {}")
                (module / "pom.xml").write_text(
                    f"<project><artifactId>{name}</artifactId></project>")
            report = root / gate.REPORT_POM
            report.parent.mkdir(parents=True)
            report.write_text('''<project xmlns="http://maven.apache.org/POM/4.0.0">
              <dependencies>
                <dependency><groupId>pe.axiz</groupId><artifactId>transport</artifactId></dependency>
                <dependency><groupId>pe.axiz</groupId><artifactId>core</artifactId>
                  <scope>provided</scope></dependency>
              </dependencies></project>''')
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                self.assertEqual(2, gate.check_inventory(root))
            self.assertIn("transport has scope compile", output.getvalue())
            self.assertIn("missing has code but is not in", output.getvalue())
            self.assertNotIn("core has code but is not in", output.getvalue())


class ModuleCoverageTest(unittest.TestCase):
    def test_aggregate_average_cannot_hide_an_uncovered_provider(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / 'jacoco.xml'
            for missed, expected in ((0, 0), (1, 1)):
                report.write_text(f'''<report>
                  <group name="large"><package name="large"/><counter type="LINE" covered="9999" missed="0"/></group>
                  <group name="provider"><package name="provider"/>
                    <counter type="LINE" covered="{1-missed}" missed="{missed}"/>
                    <counter type="BRANCH" covered="7" missed="3"/>
                  </group><counter type="LINE" covered="9999" missed="1"/>
                </report>''')
                with contextlib.redirect_stdout(io.StringIO()):
                    self.assertEqual(expected, gate.check_report(report))

    def test_branch_threshold_and_missing_groups_fail_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / 'jacoco.xml'
            report.write_text('''<report><package name="flat"/>
              <counter type="LINE" covered="100" missed="0"/></report>''')
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(1, gate.check_report(report))
            report.write_text('''<report><group name="provider"><package name="p"/>
              <counter type="LINE" covered="8" missed="2"/>
              <counter type="BRANCH" covered="6" missed="4"/>
              </group><counter type="LINE" covered="8" missed="2"/></report>''')
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(1, gate.check_report(report))


if __name__ == "__main__":
    unittest.main()

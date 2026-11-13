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


if __name__ == "__main__":
    unittest.main()

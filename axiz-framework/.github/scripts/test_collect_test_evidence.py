import importlib.util
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("collector", Path(__file__).with_name("collect-test-evidence.py"))
collector = importlib.util.module_from_spec(spec)
spec.loader.exec_module(collector)


class EvidenceTest(unittest.TestCase):
    def test_reactor_surefire_failsafe_and_statuses_without_copies(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "pom.xml").write_text("<project><modules><module>core</module><module>spring</module></modules></project>")
            for name in ("core", "spring"):
                module = root / name
                module.mkdir()
                (module / "pom.xml").write_text("<project/>")
            self.xml(root / "core", "surefire", 'tests="2" failures="1" errors="0" skipped="1"')
            self.xml(root / "spring", "failsafe", 'tests="3" failures="0" errors="1" skipped="0"')
            self.xml(root / "target/copied-fixture", "surefire", 'tests="900"')
            (root / "spring/target/failsafe-reports/failsafe-summary.xml").write_text("<failsafe-summary/>")
            result = collector.reports(root)
            self.assertEqual({"tests": 5, "failures": 1, "errors": 1, "skipped": 1}, result["counts"])
            self.assertEqual(2, len(result["reportSha256"]))
            self.assertEqual(5, result["consumerTests"])

    def test_single_module_and_framework_harness_are_reported_separately(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "pom.xml").write_text("<project/>")
            self.xml(root, "surefire", 'tests="4"',
                     '<testcase name="verifiesFrameworkOwnedAuditTransport"/>'
                     '<testcase name="verifiesFrameworkOwnedAuditTransport(ApplicationContext, MeterRegistry)"/>'
                     '<testcase name="verifiesFrameworkOwnedAuditTransportOther"/><testcase name="health"/>')
            result = collector.reports(root)
            self.assertEqual(4, result["counts"]["tests"])
            self.assertEqual(2, result["consumerTests"])
            self.assertEqual(2, result["frameworkHarnessTests"])

    def test_missing_reports_are_an_error_unless_intentionally_empty(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "pom.xml").write_text("<project/>")
            with self.assertRaises(ValueError):
                collector.reports(root)
            self.assertEqual(0, collector.reports(root, allow_empty=True)["counts"]["tests"])

    @staticmethod
    def xml(module, tool, attributes, cases=""):
        reports = module / "target" / (tool + "-reports")
        reports.mkdir(parents=True, exist_ok=True)
        (reports / "TEST-example.xml").write_text(f"<testsuite {attributes}>{cases}</testsuite>")


if __name__ == "__main__":
    unittest.main()

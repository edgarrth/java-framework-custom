import tempfile
import unittest
from pathlib import Path
from check_architecture import evaluate, prepare


class ArchitectureExecutionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.module = Path(self.temp.name)
        self.reports = self.module / "target" / "surefire-reports"
        self.reports.mkdir(parents=True)

    def report(self, name, body="", attributes=""):
        target = self.reports / f"TEST-example.{name}.xml"
        target.write_text(f'<testsuite name="example.{name}" {attributes}>'
                          f'<testcase classname="example.{name}" name="checks">{body}</testcase>'
                          '</testsuite>')
        return target

    def test_executed_architecture_and_shared_self_check_pass(self):
        self.report("ArchitectureTest")
        self.report("ArchitectureSelfCheckTest")
        self.assertEqual("PASS", evaluate(self.module)["decision"])

    def test_absent_reports_skip_tests_or_missing_self_check_fail(self):
        with self.assertRaises(ValueError):
            evaluate(self.module)
        self.report("ArchitectureTest")
        with self.assertRaises(ValueError):
            evaluate(self.module)

    def test_disabled_failed_or_error_cases_do_not_count_as_execution(self):
        self.report("ArchitectureSelfCheckTest")
        for element in ("skipped", "failure", "error"):
            with self.subTest(element=element):
                self.report("ArchitectureTest", f'<{element} message="fixture"/>')
                with self.assertRaises(ValueError):
                    evaluate(self.module)

    def test_suite_omissions_and_empty_cases_fail(self):
        self.report("ArchitectureSelfCheckTest")
        self.report("ArchitectureTest", attributes='skipped="1"')
        with self.assertRaises(ValueError):
            evaluate(self.module)
        self.report("ArchitectureTest").write_text('<testsuite name="example.ArchitectureTest" tests="1"/>')
        with self.assertRaises(ValueError):
            evaluate(self.module)

    def test_malformed_and_entity_reports_are_rejected(self):
        self.report("ArchitectureSelfCheckTest")
        target = self.report("ArchitectureTest")
        target.write_text('<!DOCTYPE suite [<!ENTITY external SYSTEM "file:///etc/passwd">]><testsuite/>')
        with self.assertRaises(ValueError):
            evaluate(self.module)

    def test_preparation_removes_stale_cases_and_preserves_other_evidence(self):
        self.report("ArchitectureTest")
        self.report("ArchitectureSelfCheckTest")
        evidence = self.module / "target" / "build-evidence.json"
        evidence.write_text('{}')
        prepare(self.module)
        self.assertTrue(evidence.exists())
        with self.assertRaises(ValueError):
            evaluate(self.module)


if __name__ == "__main__":
    unittest.main()

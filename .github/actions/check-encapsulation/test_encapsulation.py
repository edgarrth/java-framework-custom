from contextlib import redirect_stdout
import io
from pathlib import Path
import tempfile
import unittest

from check_encapsulation import PATTERNS, check, filter_errors


class EncapsulationTest(unittest.TestCase):
    def test_each_detector_fails_even_at_medium_priority(self):
        for pattern in PATTERNS:
            with self.subTest(pattern=pattern), tempfile.TemporaryDirectory() as directory:
                report = Path(directory) / 'module/target/spotbugsXml.xml'
                report.parent.mkdir(parents=True)
                report.write_text(f'<BugCollection><BugInstance type="{pattern}" priority="2"><Class classname="example.Exposed"/></BugInstance></BugCollection>')
                with redirect_stdout(io.StringIO()):
                    self.assertEqual(1, check(directory))

    def test_clean_report_and_unrelated_findings(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / 'target/spotbugsXml.xml'
            report.parent.mkdir()
            for content in ['<BugCollection/>', '<BugCollection><BugInstance type="UNRELATED"/></BugCollection>']:
                report.write_text(content)
                with redirect_stdout(io.StringIO()):
                    self.assertEqual(0, check(directory))

    def test_missing_reports_fail(self):
        with tempfile.TemporaryDirectory() as directory, redirect_stdout(io.StringIO()):
            self.assertEqual(1, check(directory))

    def test_global_and_wildcard_exclusions_rejected(self):
        for selectors in ['', '<Class name="~example.*"/><Field name="client"/>', '<Class name="example.Adapter"/>']:
            with self.subTest(selectors=selectors), tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / 'exclude.xml'
                path.write_text(f'<FindBugsFilter><Match>{selectors}<Bug pattern="EI_EXPOSE_REP2"/></Match></FindBugsFilter>')
                self.assertTrue(filter_errors(path))

    def test_exact_collaborator_and_constructor_exceptions(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'exclude.xml'
            path.write_text('''<FindBugsFilter xmlns="https://github.com/spotbugs/filter/3.0.0">
              <Match><Class name="example.Adapter"/><Field name="client"/><Bug pattern="EI_EXPOSE_REP2"/></Match>
              <Match><Class name="example.Adapter"/><Method name="&lt;init&gt;" params="" returns="void"/><Bug pattern="CT_CONSTRUCTOR_THROW"/></Match>
              </FindBugsFilter>''')
            self.assertEqual([], filter_errors(path))
            path.write_text(path.read_text().replace(' params="" returns="void"', ''))
            self.assertTrue(filter_errors(path))

    def test_only_exact_generated_annotation_with_collection_detectors_is_allowed(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'exclude.xml'
            for annotation, allowed in [('pe.axiz.core.commons.Generated', True),
                                        ('~.*Generated', False), ('example.Generated', False)]:
                path.write_text(f'<FindBugsFilter><Match><Annotation name="{annotation}"/><Bug pattern="EI_EXPOSE_REP,EI_EXPOSE_REP2"/></Match></FindBugsFilter>')
                self.assertEqual(allowed, not filter_errors(path))

    def test_generated_marker_cannot_hide_security_or_constructor_findings(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'exclude.xml'
            for bug in ('', '<Bug pattern="SQL_INJECTION_JDBC"/>', '<Bug pattern="CT_CONSTRUCTOR_THROW"/>'):
                path.write_text(f'<FindBugsFilter><Match><Annotation name="pe.axiz.core.commons.Generated"/>{bug}</Match></FindBugsFilter>')
                self.assertTrue(filter_errors(path))

    def test_local_filter_cannot_disable_detector(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            report = root / 'module/target/spotbugsXml.xml'
            report.parent.mkdir(parents=True)
            report.write_text('<BugCollection/>')
            (root / 'module/spotbugs-exclude.xml').write_text(
                '<FindBugsFilter><Match><Bug pattern="EI_EXPOSE_REP"/></Match></FindBugsFilter>')
            with redirect_stdout(io.StringIO()):
                self.assertEqual(1, check(root))


if __name__ == '__main__':
    unittest.main()

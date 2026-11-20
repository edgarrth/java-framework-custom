from contextlib import redirect_stdout
import io
import os
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
            for content in ['<BugCollection><Errors errors="0" missingClasses="0"/><FindBugsSummary total_classes="0"/></BugCollection>', '<BugCollection><Errors errors="0" missingClasses="0"/><FindBugsSummary total_classes="0"/><BugInstance type="UNRELATED"/></BugCollection>']:
                report.write_text(content)
                with redirect_stdout(io.StringIO()):
                    self.assertEqual(0, check(directory))

    def test_internal_failures_and_missing_productive_types_fail_closed(self):
        statuses = [
            '<Errors errors="1" missingClasses="0"><Error><ErrorMessage>Detector crashed</ErrorMessage></Error></Errors>',
            '<Errors errors="0" missingClasses="1"><MissingClass>pe.axiz.core.tokenization.TokenProfile</MissingClass></Errors>',
            '<Errors errors="0" missingClasses="1"/>', '',
        ]
        for status in statuses:
            with self.subTest(status=status), tempfile.TemporaryDirectory() as directory:
                report = Path(directory) / 'target/spotbugsXml.xml'
                report.parent.mkdir()
                report.write_text('<BugCollection>' + status + '<FindBugsSummary total_classes="0"/></BugCollection>')
                with redirect_stdout(io.StringIO()):
                    self.assertEqual(1, check(directory))

    def test_missing_module_report_and_unanalyzed_class_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ('one', 'two'):
                module = root / name
                (module / 'src/main/java/example').mkdir(parents=True)
                (module / 'pom.xml').write_text('<project/>')
                (module / 'src/main/java/example/Main.java').write_text('package example; class Main {}')
                (module / 'target/classes/example').mkdir(parents=True)
                (module / 'target/classes/example/Main.class').write_bytes(b'synthetic class inventory')
            report = root / 'one/target/spotbugsXml.xml'
            report.write_text('<BugCollection><Errors errors="0" missingClasses="0"/><FindBugsSummary total_classes="1"><ClassStats class="example.Main"/></FindBugsSummary></BugCollection>')
            with redirect_stdout(io.StringIO()):
                self.assertEqual(1, check(root))
            other = root / 'two/target/spotbugsXml.xml'
            other.write_text(report.read_text())
            with redirect_stdout(io.StringIO()):
                self.assertEqual(0, check(root))
            other.write_text(other.read_text().replace('example.Main', 'example.Another'))
            with redirect_stdout(io.StringIO()):
                self.assertEqual(1, check(root))

    def test_only_reviewed_optional_annotation_metadata_is_tolerated(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / 'target/spotbugsXml.xml'
            report.parent.mkdir()
            report.write_text('<BugCollection><Errors errors="0" missingClasses="1"><MissingClass>lombok.Generated</MissingClass></Errors><FindBugsSummary total_classes="0"/></BugCollection>')
            with redirect_stdout(io.StringIO()):
                self.assertEqual(0, check(directory))

    def test_stale_report_is_rejected_after_source_change(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'pom.xml').write_text('<project/>')
            source = root / 'src/main/java/example/Main.java'
            source.parent.mkdir(parents=True)
            source.write_text('package example; class Main {}')
            compiled = root / 'target/classes/example/Main.class'
            compiled.parent.mkdir(parents=True)
            compiled.write_bytes(b'compiled inventory')
            report = root / 'target/spotbugsXml.xml'
            report.write_text('<BugCollection><Errors errors="0" missingClasses="0"/><FindBugsSummary><ClassStats class="example.Main"/></FindBugsSummary></BugCollection>')
            with redirect_stdout(io.StringIO()):
                self.assertEqual(0, check(root))
            changed = report.stat().st_mtime_ns + 1_000_000_000
            os.utime(source, ns=(changed, changed))
            with redirect_stdout(io.StringIO()):
                self.assertEqual(1, check(root))

    def test_arbitrary_declared_filter_is_validated_and_unknown_paths_fail_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'target').mkdir()
            (root / 'target/spotbugsXml.xml').write_text('<BugCollection><Errors errors="0" missingClasses="0"/><FindBugsSummary/></BugCollection>')
            pom = root / 'pom.xml'
            pom.write_text('<project><excludeFilterFile>${project.basedir}/other.xml</excludeFilterFile></project>')
            excluded = root / 'other.xml'
            excluded.write_text('<FindBugsFilter><Match><Bug pattern="EI_EXPOSE_REP"/></Match></FindBugsFilter>')
            with redirect_stdout(io.StringIO()):
                self.assertEqual(1, check(root))
            excluded.write_text('<FindBugsFilter/>')
            with redirect_stdout(io.StringIO()):
                self.assertEqual(0, check(root))
            pom.write_text('<project><excludeFilterFile>${unreviewed}/other.xml</excludeFilterFile></project>')
            with redirect_stdout(io.StringIO()):
                self.assertEqual(1, check(root))

    def test_partial_analysis_configuration_is_rejected(self):
        from check_encapsulation import declared_filters
        for tag, value in [('omitVisitors', 'FindReturnRef'), ('onlyAnalyze', 'example.Safe'),
                           ('visitors', 'FindNullDeref'), ('includeFilterFile', 'only.xml'),
                           ('excludeBugsFile', 'baseline.xml'), ('failThreshold', 'High'),
                           ('threshold', 'High'), ('spotbugs.skip', 'true')]:
            with self.subTest(tag=tag), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                (root / 'pom.xml').write_text(f'<project><{tag}>{value}</{tag}></project>')
                self.assertTrue(declared_filters(root)[1])

    def test_filters_cannot_escape_the_reviewed_root(self):
        from check_encapsulation import declared_filters
        with tempfile.TemporaryDirectory() as directory:
            parent = Path(directory)
            root = parent / 'repo'
            root.mkdir()
            (parent / 'external.xml').write_text('<FindBugsFilter/>')
            (root / 'pom.xml').write_text('<project><excludeFilterFile>../external.xml</excludeFilterFile></project>')
            self.assertTrue(declared_filters(root)[1])

    def test_method_name_does_not_suppress_every_overload(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'exclude.xml'
            path.write_text('<FindBugsFilter><Match><Class name="example.Adapter"/>'
                            '<Method name="value"/><Bug pattern="EI_EXPOSE_REP"/></Match></FindBugsFilter>')
            self.assertTrue(filter_errors(path))
            path.write_text(path.read_text().replace('name="value"', 'name="value" params="" returns="byte[]"'))
            self.assertEqual([], filter_errors(path))

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
            report.write_text('<BugCollection><Errors errors="0" missingClasses="0"/><FindBugsSummary total_classes="0"/></BugCollection>')
            (root / 'module/spotbugs-exclude.xml').write_text(
                '<FindBugsFilter><Match><Bug pattern="EI_EXPOSE_REP"/></Match></FindBugsFilter>')
            with redirect_stdout(io.StringIO()):
                self.assertEqual(1, check(root))


if __name__ == '__main__':
    unittest.main()

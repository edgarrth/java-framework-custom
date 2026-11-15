from pathlib import Path
import tempfile
import unittest
import yaml

from check_demo_workflows import ROOT, TEMPLATE, FIXTURE, check, rendered_deploy, fixture_errors
from workflow_release_normalization import CONTRACT, SPEC, normalize, specification


class DemoWorkflowTest(unittest.TestCase):
    def test_all_demo_callers_match_the_actual_template(self):
        self.assertEqual([], check(ROOT))

    def test_changed_caller_is_reported(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            template = root / TEMPLATE
            template.parent.mkdir(parents=True)
            template.write_text((ROOT / TEMPLATE).read_text())
            project = root / 'axiz-demo/services/payments'
            (project / '.axiz').mkdir(parents=True)
            (project / '.axiz/project.yaml').write_text('name: payments\nmodule: service-module\n')
            workflow = project / '.github/workflows/deploy.yaml'
            workflow.parent.mkdir(parents=True)
            rendered = rendered_deploy(root, project)
            workflow.write_text(rendered)
            self.assertEqual('service-module', yaml.safe_load(rendered)['jobs']['automatic']['steps'][0]['with']['module'])
            self.assertEqual([], check(root))
            workflow.write_text(rendered.replace('BUILD_DESTINATION:', 'UNDECLARED:'))
            self.assertEqual(1, len(check(root)))

    def test_lightweight_engine_matches_jmustache_fixture_for_delimiters_sections_and_escaping(self):
        self.assertEqual([], fixture_errors(ROOT))

    def test_template_change_and_stale_rendered_fixture_fail_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for relative in (TEMPLATE, FIXTURE, SPEC, CONTRACT):
                target = root / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes((ROOT / relative).read_bytes())
            template = root / TEMPLATE
            template.write_text(template.read_text() + '# changed\n')
            self.assertIn('template changed', fixture_errors(root)[0])
            template.write_bytes((ROOT / TEMPLATE).read_bytes())
            fixture = yaml.safe_load((root / FIXTURE).read_text())
            fixture['cases'][0]['rendered'] = 'wrong output'
            (root / FIXTURE).write_text(yaml.safe_dump(fixture))
            self.assertIn('differs from the real generator', fixture_errors(root)[0])


class WorkflowNormalizationTest(unittest.TestCase):
    def test_shared_cases_and_strict_utf8(self):
        data = yaml.safe_load((ROOT / SPEC).read_text(encoding='utf-8'))
        for case in data['cases']:
            with self.subTest(case=case['id']):
                if case.get('error'):
                    with self.assertRaises(ValueError):normalize(case['value'], specification(ROOT))
                else:self.assertEqual(case['expected'], normalize(case['value'], specification(ROOT)))
        with self.assertRaises(UnicodeDecodeError):normalize(b'\xff', specification(ROOT))

    def test_version_only_change_preserves_fixture_but_not_effective_demo_parity(self):
        import re
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for relative in (TEMPLATE, FIXTURE, SPEC, CONTRACT):
                target = root / relative;target.parent.mkdir(parents=True, exist_ok=True);target.write_bytes((ROOT / relative).read_bytes())
            template = root / TEMPLATE
            template.write_text(re.sub(specification(root)['pattern'], 'axiz-workflows-v2.9.9', template.read_text()))
            self.assertEqual([], fixture_errors(root))
            template.write_text(template.read_text().replace('axiz-workflows-v2.9.9', 'axiz-workflows-v2.9.8', 1))
            with self.assertRaises(ValueError):fixture_errors(root)

import copy
from pathlib import Path
import tempfile
import unittest
import yaml
from check_workflow_security import validate_monorepo_file

ROOT = Path(__file__).resolve().parents[2]

class MonorepoBoundariesTest(unittest.TestCase):
    def test_real_product_filters_do_not_accept_other_components_candidates(self):
        for name in ('framework-ci.yaml', 'tools-ci.yaml', 'demo-ci.yaml'):
            path = ROOT / 'workflows' / name
            data = yaml.safe_load(path.read_text())
            self.assertEqual([], validate_monorepo_file(path, data, (ROOT / 'version.txt').read_text().strip(), managed=False))
            wrong = copy.deepcopy(data)
            wrong.get('on', wrong.get(True))['push']['tags'] = ['candidate/**']
            self.assertTrue(validate_monorepo_file(path, wrong, (ROOT / 'version.txt').read_text().strip(), managed=False))
        path = ROOT / 'workflows/demo-ci.yaml'
        data = yaml.safe_load(path.read_text())
        data.get('on', data.get(True))['push'] = {}
        self.assertTrue(validate_monorepo_file(path, data, (ROOT / 'version.txt').read_text().strip(), managed=False), 'unfiltered push also subscribes to tags')
        self.assertFalse((ROOT / 'workflows/self-check.yaml').exists())
        control = yaml.safe_load((ROOT / 'workflows/workflows-ci.yaml').read_text())
        triggers = control.get('on', control.get(True))
        self.assertNotIn('paths', triggers['pull_request'] or {})
        self.assertNotIn('paths', triggers['push'])
        self.assertEqual(['main', 'develop'], triggers['push']['branches'])
        self.assertEqual({'contents': 'read'}, control['jobs']['contracts']['permissions'])

    def test_internal_component_version_and_updater_marker_are_required(self):
        for ref in ('v2.0.0', 'axiz-tools-v2.0.0', 'axiz-workflows-v9.0.0', 'axiz-workflows-v2.0.0'):
            for marker in ('', ' # x-release-please-version'):
                with tempfile.TemporaryDirectory() as directory:
                    path = Path(directory) / 'unit.yaml'
                    path.write_text('jobs:\n  test:\n    uses: edgarrth/java-framework-custom/.github/workflows/pr.yaml@' + ref + marker + '\n')
                    errors = validate_monorepo_file(path, yaml.safe_load(path.read_text()), '2.0.0')
                    self.assertEqual(ref != 'axiz-workflows-v2.0.0' or not marker, bool(errors))

    def test_internal_checkout_refs_require_current_version_and_updater_marker(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'fixture.yaml'
            for repository in ('', '        repository: edgarrth/java-framework-custom\n',
                               '        repository: ${{ github.repository }}\n'):
                for version in ('2.0.0', '2.0.1'):
                    for marker in ('', ' # x-release-please-version'):
                        path.write_text('permissions: {}\njobs:\n  work:\n    steps:\n    - uses: actions/checkout@' + 'a' * 40 + '\n      with:\n' + repository +
                                        '        ref: axiz-workflows-v' + version + marker + '\n')
                        errors = validate_monorepo_file(path, yaml.safe_load(path.read_text()), '2.0.1')
                        self.assertEqual(version != '2.0.1' or not marker, bool(errors))

    def test_foreign_and_other_component_checkout_refs_are_independent(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'fixture.yaml'
            for repository, ref in [('other/repo', 'axiz-workflows-v2.0.0'),
                                    ('edgarrth/java-framework-custom', 'axiz-framework-v2.0.0'),
                                    ('edgarrth/java-framework-custom', '${{ github.sha }}')]:
                path.write_text('jobs:\n  work:\n    steps:\n    - uses: actions/checkout@' + 'a' * 40 + '\n      with:\n        repository: ' + repository + '\n        ref: ' + ref + '\n')
                self.assertEqual([], validate_monorepo_file(path, yaml.safe_load(path.read_text()), '2.0.1'))

    def test_checkout_pins_in_unmanaged_files_are_not_forced_to_candidate(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'fixture.yaml'
            path.write_text('jobs:\n  work:\n    steps:\n    - uses: actions/checkout@' + 'a' * 40 + '\n      with:\n        ref: axiz-workflows-v2.0.0\n')
            self.assertEqual([], validate_monorepo_file(path, yaml.safe_load(path.read_text()), '2.0.1', managed=False))

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
            self.assertEqual([], validate_monorepo_file(path, data, '2.0.0'))
            wrong = copy.deepcopy(data)
            wrong.get('on', wrong.get(True))['push']['tags'] = ['candidate/**']
            self.assertTrue(validate_monorepo_file(path, wrong, '2.0.0'))
        path = ROOT / 'workflows/demo-ci.yaml'
        data = yaml.safe_load(path.read_text())
        data.get('on', data.get(True))['push'] = {}
        self.assertTrue(validate_monorepo_file(path, data, '2.0.0'), 'unfiltered push also subscribes to tags')
        self.assertFalse((ROOT / 'workflows/self-check.yaml').exists())
        control = yaml.safe_load((ROOT / 'workflows/workflows-ci.yaml').read_text())
        self.assertIn('.github/**', control.get('on', control.get(True))['pull_request']['paths'])
        self.assertEqual({'contents': 'read'}, control['jobs']['contracts']['permissions'])

    def test_internal_component_version_and_updater_marker_are_required(self):
        for ref in ('v2.0.0', 'axiz-tools-v2.0.0', 'axiz-workflows-v9.0.0', 'axiz-workflows-v2.0.0'):
            for marker in ('', ' # x-release-please-version'):
                with tempfile.TemporaryDirectory() as directory:
                    path = Path(directory) / 'unit.yaml'
                    path.write_text('jobs:\n  test:\n    uses: edgarrth/java-framework-custom/.github/workflows/pr.yaml@' + ref + marker + '\n')
                    errors = validate_monorepo_file(path, yaml.safe_load(path.read_text()), '2.0.0')
                    self.assertEqual(ref != 'axiz-workflows-v2.0.0' or not marker, bool(errors))

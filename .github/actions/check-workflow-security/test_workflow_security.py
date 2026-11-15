import copy
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

import yaml

from check_workflow_security import CONTRACTS, GUARD, VERIFY, guarded_condition, validate

ROOT = Path(__file__).resolve().parents[2]


class PolicyTest(unittest.TestCase):
    def setUp(self):
        self.document = yaml.safe_load((ROOT / 'workflows/lib-build.yaml').read_text())
        # This is an unprivileged caller fixture; closed signer roles are tested separately.
        del self.document['jobs']['sign']
        self.document['jobs']['consumer'] = {
            'needs': GUARD, 'uses': 'edgarrth/java-framework-custom/.github/workflows/lib-build.yaml@axiz-workflows-v' + (ROOT / 'version.txt').read_text().strip(),
            'secrets': {'NEXUS_URL': '${{ secrets.NEXUS_URL }}'}}
        contract = self.document.get('on', self.document.get(True))['workflow_call']
        self.document['jobs']['consumer']['with'] = {
            key: 'explicit' for key, value in contract.get('inputs', {}).items() if value.get('required')}
        self.document['jobs']['consumer']['secrets'] = {
            key: '${{ secrets.' + key + ' }}' for key, value in contract.get('secrets', {}).items() if value.get('required')}

    def test_current_workflow(self):
        self.assertEqual([], validate(self.document))

    def test_no_global_secrets(self):
        self.document['jobs']['consumer']['secrets'] = 'inherit'
        self.assertTrue(validate(self.document))

    def test_unknown_secret_rejected(self):
        self.document['jobs']['consumer']['secrets']['UNRELATED_PRODUCTION_KEY'] = '${{ secrets.KEY }}'
        self.assertTrue(validate(self.document))

    def test_missing_guard(self):
        del self.document['jobs'][GUARD]
        self.assertTrue(validate(self.document))

    def test_guard_cannot_skip_or_ignore_failures(self):
        for name, value in [('if', False), ('continue-on-error', True), ('needs', 'build')]:
            with self.subTest(name=name):
                document = copy.deepcopy(self.document)
                document['jobs'][GUARD][name] = value
                self.assertTrue(validate(document))

    def test_modified_command_or_version_rejected(self):
        step = self.document['jobs'][GUARD]['steps'][0]
        for change in ['run', 'version', 'permissions']:
            with self.subTest(change=change):
                document = copy.deepcopy(self.document)
                guard = document['jobs'][GUARD]
                if change == 'run':
                    guard['steps'][0]['run'] = step['run'].replace('.immutable == true', 'true')
                elif change == 'version':
                    guard['steps'][0]['env']['AXIZ_WORKFLOWS_VERSION'] = 'v9.9.9'
                else:
                    guard['permissions']['contents'] = 'write'
                self.assertTrue(validate(document))

    def test_direct_dependency_is_required(self):
        self.document['jobs']['build']['needs'] = []
        self.assertTrue(validate(self.document))

    def test_custom_condition_must_require_guard_success(self):
        for condition in ['always()', "${{ needs.axiz-workflows-trust.result == 'success' && (true) || always() && (true) }}"]:
            self.document['jobs']['build']['if'] = condition
            self.assertTrue(validate(self.document))
        self.assertTrue(guarded_condition("${{ needs.axiz-workflows-trust.result == 'success' && (!cancelled() && contains('x)', 'x')) }}"))

    def test_third_party_tag_rejected(self):
        self.document['jobs']['build']['steps'][0]['uses'] = 'actions/checkout@v5'
        self.assertTrue(validate(self.document))

    def test_secret_contracts_match_reusable_workflows(self):
        for name, contract in CONTRACTS.items():
            document = yaml.safe_load((ROOT / 'workflows' / name).read_text())
            trigger = document.get('on', document.get(True))
            self.assertEqual(contract, set(trigger['workflow_call'].get('secrets', {})), name)


class ReleaseVerificationTest(unittest.TestCase):
    def run_guard(self, response, exit_code=0):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'response.json').write_text(response)
            gh = root / 'gh'
            gh.write_text('#!/bin/bash\nset -eu\n[[ "$*" == "api repos/edgarrth/java-framework-custom/releases/tags/axiz-workflows-v2.0.0" ]] || exit 99\ncat "$FIXTURE/response.json"\nexit "$GH_EXIT"\n')
            gh.chmod(0o700)
            env = dict(os.environ, PATH=str(root) + os.pathsep + os.environ['PATH'], FIXTURE=directory,
                       GH_EXIT=str(exit_code), AXIZ_WORKFLOWS_VERSION='axiz-workflows-v2.0.0')
            return subprocess.run(['bash', '-c', VERIFY], env=env, capture_output=True).returncode

    def test_only_published_immutable_exact_release_passes(self):
        valid = {'tag_name': 'axiz-workflows-v2.0.0', 'immutable': True, 'draft': False}
        self.assertEqual(0, self.run_guard(json.dumps(valid)))
        for mutation in [{'immutable': False}, {'immutable': 'true'}, {'immutable': None},
                         {'draft': True}, {'tag_name': 'axiz-workflows-v2.0.1'}]:
            with self.subTest(mutation=mutation):
                self.assertNotEqual(0, self.run_guard(json.dumps(valid | mutation)))
        self.assertNotEqual(0, self.run_guard(json.dumps({'tag_name': 'axiz-workflows-v2.0.0'})))
        self.assertNotEqual(0, self.run_guard('invalid json'))
        self.assertNotEqual(0, self.run_guard(json.dumps(valid), 1))
        self.assertNotEqual(0, self.run_guard(''))


if __name__ == '__main__':
    unittest.main()

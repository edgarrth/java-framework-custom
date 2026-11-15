import copy
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

import yaml

from required_checks import POLICY, check, token_permissions
from internal_contracts import ROOT


class RequiredCheckTest(unittest.TestCase):
    def test_actual_workflows_have_complete_reviewed_verdicts(self):
        self.assertEqual([], check(ROOT))

    def test_every_job_result_is_checked_and_only_reviewed_skips_pass(self):
        policy = json.loads((ROOT / POLICY).read_text())
        for file, entry in policy['workflows'].items():
            if entry['verdict']['mode'] == 'single':continue
            jobs = {key: {'result': 'success'} for key, value in entry['jobs'].items() if value['class'] != 'non-blocking'}
            skips = [key for key, value in entry['jobs'].items() if value['class'] == 'skipped-in-pr']
            cases = [(jobs, True), ({}, False)]
            for key in jobs:
                for result in ('failure', 'cancelled', 'skipped'):
                    altered = copy.deepcopy(jobs); altered[key]['result'] = result
                    cases.append((altered, result == 'skipped' and key in skips))
            for needs, expected in cases:
                with self.subTest(workflow=file, needs=needs):
                    result = subprocess.run(['bash', '-euo', 'pipefail', '-c', policy['verdictScript']], env=dict(os.environ, NEEDS=json.dumps(needs), SKIPPABLE=json.dumps(skips)), capture_output=True)
                    self.assertEqual(expected, result.returncode == 0)

    def test_new_jobs_triggers_names_and_tolerated_errors_cannot_escape(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); shutil.copytree(ROOT / '.github/workflows', root / '.github/workflows')
            (root / POLICY).parent.mkdir(parents=True, exist_ok=True); shutil.copy2(ROOT / POLICY, root / POLICY)
            shutil.copy2(ROOT / 'RELEASING.md', root / 'RELEASING.md')
            path = root / '.github/workflows/framework-ci.yaml'; original = path.read_text(); document = yaml.safe_load(original)
            mutations = [lambda d: d['jobs'].update(new={'runs-on':'ubuntu-24.04','steps':[{'run':'false'}]}), lambda d: d['jobs']['framework-pr-check'].update(needs=['pr']), lambda d: d['jobs']['framework-pr-check'].update(**{'continue-on-error':True}), lambda d: d['jobs']['parameters']['steps'][0].update(**{'continue-on-error':True}), lambda d: d['jobs']['pr'].update(name='axiz-tools-pr')]
            for trigger in ('paths', 'paths-ignore', 'branches', 'branches-ignore'):
                mutations.append(lambda d, key=trigger: d.setdefault('on', d.get(True, {})).update(pull_request={key:['main']}))
            mutations.append(lambda d: d.setdefault('on', d.get(True, {})).update(pull_request={'types':['closed']}))
            for mutate in mutations:
                altered = copy.deepcopy(document); mutate(altered); path.write_text(yaml.safe_dump(altered)); self.assertTrue(check(root)); path.write_text(original)
            guide = root / 'RELEASING.md'; original_guide = guide.read_text()
            guide.write_text(original_guide.replace('## Checks requeridos\n', '## Checks\n'))
            self.assertIn('RELEASING.md must list exactly the required verdicts', check(root))
            guide.write_text(original_guide)
            policy = json.loads((root/POLICY).read_text());policy['toleratedSteps']=[{'path':'unused','reason':'fixture'}]
            (root/POLICY).write_text(json.dumps(policy));self.assertTrue(check(root))

    def test_tokens_cannot_create_or_impersonate_verdicts(self):
        for permissions in ('write-all', {'checks':'write'}, {'statuses':'write'}):
            self.assertTrue(token_permissions({'permissions':permissions}))
            self.assertTrue(token_permissions({'jobs':{'test':{'permissions':permissions}}}))
        self.assertFalse(token_permissions({'permissions':{'checks':'read'}}))
        self.assertFalse(token_permissions({'jobs':{'test':{'permissions':{'checks':'write'}}}}, {('test','checks')}))

from pathlib import Path
from contextlib import redirect_stdout
import io
import json
import os
import re
import tempfile
import unittest
from unittest.mock import patch
import yaml

import workflows_policy_checks as executor
from workflows_policy_checks import COMMANDS

ROOT=Path(__file__).resolve().parents[2]


class WorkflowExecutorTest(unittest.TestCase):
    def test_ci_uses_a_pinned_node_and_one_integrity_locked_toolchain(self):
        directory=ROOT/'.github/scripts/release-version'
        version=(directory/'.node-version').read_text().strip()
        self.assertRegex(version,r'^[0-9]+\.[0-9]+\.[0-9]+$')
        manifest=json.loads((directory/'package.json').read_text())
        lock=json.loads((directory/'package-lock.json').read_text())
        self.assertEqual(3,lock['lockfileVersion'])
        self.assertEqual(manifest['devDependencies'],lock['packages']['']['devDependencies'])
        self.assertRegex(manifest['devDependencies']['renovate'],r'^[0-9]+\.[0-9]+\.[0-9]+$')
        self.assertTrue(all(row.get('integrity') for name,row in lock['packages'].items() if name))
        workflow=yaml.safe_load((ROOT/'.github/workflows/workflows-ci.yaml').read_text())
        setup=next(step for step in workflow['jobs']['contracts']['steps'] if step.get('uses','').startswith('actions/setup-node@'))
        self.assertEqual('.github/scripts/release-version/.node-version',setup['with']['node-version-file'])
        commands='\n'.join(step.get('run','') for step in workflow['jobs']['contracts']['steps'])
        self.assertEqual(1,commands.count('npm ci '))
        self.assertIn('--ignore-scripts',commands)
        self.assertIn('--include=dev',commands)
        self.assertNotIn('dependencies',manifest)
        self.assertTrue(yaml.safe_load((ROOT/'trivy.yaml').read_text())['pkg']['include-dev-deps'])
        security=yaml.safe_load((ROOT/'.github/workflows/security.yaml').read_text())
        scanner=next(step for step in security['jobs']['filesystem']['steps'] if step.get('uses','').startswith('aquasecurity/trivy-action@'))
        self.assertEqual(True,scanner['with']['limit-severities-for-sarif'])

    def test_ci_and_lifecycle_share_one_nonrecursive_command_inventory(self):
        workflow=yaml.safe_load((ROOT/'.github/workflows/workflows-ci.yaml').read_text())
        commands='\n'.join(step.get('run','') for step in workflow['jobs']['contracts']['steps'])
        self.assertEqual(1,commands.count('workflows_policy_checks.py'))
        self.assertEqual(1,commands.count('release_entrypoints.py'))
        self.assertNotIn('unittest discover',commands)
        self.assertNotIn('npx',commands)
        self.assertEqual(len(COMMANDS),len({name for name,_ in COMMANDS}))
        self.assertFalse(any('release_entrypoints.py' in part for _,command in COMMANDS for part in command))
        self.assertIn('manifest-tests',{name for name,_ in COMMANDS})
        self.assertIn('literals',{name for name,_ in COMMANDS})
        self.assertIn('release-permissions',{name for name,_ in COMMANDS})
        self.assertIn('documentation',{name for name,_ in COMMANDS})

    def test_a_failed_control_preserves_and_prints_its_diagnostic_before_stopping(self):
        with tempfile.TemporaryDirectory(prefix='axiz-control-diagnostic-') as folder:
            source=Path(folder)/'source';source.mkdir()
            output=Path(folder)/'results'
            commands=[('failing-check',['{python}','-c','print("Fixture failure details"); raise SystemExit(1)']),
                      ('must-not-run',['{python}','-c','raise SystemExit(99)'])]
            tools={'actionlint':'unused','kyverno':'unused','kubeconform':'unused','schemas':str(source/'schemas')}
            console=io.StringIO()
            with patch.object(executor,'verify_schemas'),patch.object(executor,'COMMANDS',commands),patch.dict(os.environ,{
                    'AXIZ_NPM_AUDIT_INPUT':'fixture','AXIZ_RELEASE_ACTION_CACHE':'fixture'}),redirect_stdout(console):
                result=executor.execute(source,output,tools)
            self.assertFalse(result['passed'])
            self.assertEqual(['failing-check'],[row['name'] for row in result['checks']])
            self.assertIn('Fixture failure details',console.getvalue())
            self.assertIn('::group::failing-check diagnostics',console.getvalue())
            self.assertEqual('Fixture failure details\n',(output/'failing-check.log').read_text())
            self.assertFalse((output/'must-not-run.log').exists())
            self.assertFalse(json.loads((output/'result.json').read_text())['passed'])

    def test_ci_keeps_control_logs_available_after_failure(self):
        workflow=yaml.safe_load((ROOT/'.github/workflows/workflows-ci.yaml').read_text())
        upload=next(step for step in workflow['jobs']['contracts']['steps']
                    if step.get('uses','').startswith('actions/upload-artifact@'))
        self.assertEqual('always()',upload['if'])
        self.assertIn('/policy-results/*.log',upload['with']['path'])
        self.assertIn('/lifecycle-results/result.json',upload['with']['path'])
        self.assertEqual(7,upload['with']['retention-days'])

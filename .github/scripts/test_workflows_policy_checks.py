from pathlib import Path
import json
import re
import unittest
import yaml

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

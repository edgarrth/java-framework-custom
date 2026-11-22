import json
from pathlib import Path
import tempfile
import unittest

from check_workflow_security import check_tree
from internal_contracts import ROOT


class WorkflowSourcesTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)

    def write(self, name, content):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
        return path

    def workflow(self, call='./ci/local'):
        return self.write('.github/workflows/main.yaml', 'on: push\npermissions: {}\njobs:\n  work:\n    permissions: {contents: read}\n    runs-on: ubuntu-24.04\n    steps:\n      - uses: ' + call + '\n')

    def action(self, name='ci/local/action.yaml', call='vendor/action@' + 'a' * 40):
        return self.write(name, 'name: fixture\nruns:\n  using: composite\n  steps:\n    - uses: ' + call + '\n')

    def check(self, scope='executable'):
        return check_tree(self.root, ROOT, scope=scope)

    def test_root_scope_omits_nested_consumers_and_templates_but_all_scope_keeps_them(self):
        self.workflow()
        self.action()
        for name in ('demo/.github/workflows/ci.yaml', 'codegen/templates/ci.yaml.mustache'):
            self.write(name, 'on: push\npermissions: {}\njobs:\n  broken:\n    steps:\n      - uses: vendor/action@mutable\n')
        self.assertEqual([], self.check()[0])
        all_errors, files, _ = self.check('all')
        self.assertEqual(4, files)
        self.assertEqual(2, len(all_errors))

    def test_transitive_actions_outside_conventional_folder_are_checked_once(self):
        self.workflow()
        self.action(call='./other/local')
        self.action('other/local/action.yml', call='vendor/action@mutable')
        errors, files, _ = self.check()
        self.assertEqual(3, files)
        self.assertEqual(1, len(errors))
        self.assertIn('other/local/action.yml', errors[0])

    def test_unreferenced_actions_are_only_checked_in_integral_scope(self):
        self.workflow()
        self.action()
        self.action('.github/actions/unused/action.yaml', 'vendor/action@mutable')
        self.assertEqual([], self.check()[0])
        self.assertTrue(any('.github/actions/unused/action.yaml' in e for e in self.check('all')[0]))

    def test_missing_and_ambiguous_local_metadata_fail(self):
        self.workflow()
        self.assertTrue(any('missing or ambiguous' in e for e in self.check()[0]))
        self.action()
        self.action('ci/local/action.yml')
        self.assertTrue(any('missing or ambiguous' in e for e in self.check()[0]))

    def test_local_action_metadata_requires_runs(self):
        self.workflow()
        self.write('ci/local/action.yaml', 'name: invalid\n')
        self.assertTrue(any('requires runs' in e for e in self.check()[0]))

    def test_path_traversal_and_symlinks_cannot_leave_checkout(self):
        self.workflow('./../outside')
        self.assertTrue(any('escapes the checkout' in e for e in self.check()[0]))
        self.workflow()
        with tempfile.TemporaryDirectory() as outside:
            target = Path(outside) / 'action.yaml'
            target.write_text('runs: {using: node24, main: index.js}\n')
            link = self.root / 'ci/local'
            link.parent.mkdir(parents=True, exist_ok=True)
            link.symlink_to(Path(outside), target_is_directory=True)
            self.assertTrue(any('escapes the checkout' in e for e in self.check()[0]))

    def test_metadata_symlink_is_checked_separately_from_its_directory(self):
        self.workflow()
        with tempfile.TemporaryDirectory() as outside:
            target = Path(outside) / 'metadata.yaml'
            target.write_text('runs: {using: node24, main: index.js}\n')
            metadata = self.root / 'ci/local/action.yaml'
            metadata.parent.mkdir(parents=True)
            metadata.symlink_to(target)
            self.assertTrue(any('metadata escapes' in e for e in self.check()[0]))

    def test_cycles_are_reported_and_shared_dependencies_do_not_create_false_cycles(self):
        self.workflow()
        self.action(call='./other/local')
        dependency = self.action('other/local/action.yaml', call='./ci/local')
        self.assertTrue(any('cyclic local' in e for e in self.check()[0]))
        dependency.write_text('runs: {using: node24, main: index.js}\n')
        self.workflow('./other/local')
        self.assertEqual([], self.check()[0])
        self.action('.github/actions/another/action.yaml', './other/local')
        self.assertEqual([], self.check()[0])

    def test_local_reusable_workflows_are_followed_and_missing_ones_fail(self):
        root = self.write('.github/workflows/main.yaml', 'on: push\npermissions: {}\njobs:\n  work:\n    uses: ./.github/workflows/reuse.yaml\n')
        self.assertTrue(any('reusable workflow is missing' in e for e in self.check()[0]))
        self.write('.github/workflows/reuse.yaml', 'on: push\npermissions: {}\njobs:\n  work:\n    steps:\n      - uses: vendor/action@mutable\n')
        self.assertTrue(any('reuse.yaml' in e and 'pin external' in e for e in self.check()[0]))
        root.write_text('on: push\npermissions: {}\njobs:\n  work:\n    uses: ./nested/.github/workflows/reuse.yaml\n')
        self.assertTrue(any('outside .github/workflows' in e for e in self.check()[0]))

    def test_repository_executable_scope_and_full_scope_both_pass(self):
        self.assertEqual(([], 18, 22), check_tree(ROOT, ROOT, scope='executable'))
        self.assertEqual(([], 68, 92), check_tree(ROOT, ROOT, scope='all'))

    def test_local_audit_copies_are_not_product_sources_but_called_actions_are_checked(self):
        self.workflow()
        self.action()
        self.write('_validacion-framework-axiz/copy/.github/workflows/demo.yaml',
                   'on: push\njobs:\n  work:\n    steps:\n      - uses: vendor/action@mutable\n')
        self.assertEqual([], self.check('all')[0])
        self.workflow('./_validacion-framework-axiz/tool')
        self.action('_validacion-framework-axiz/tool/action.yaml', 'vendor/action@mutable')
        self.assertTrue(any('pin external' in e for e in self.check()[0]))

    def test_consumer_release_configuration_does_not_become_axiz_monorepo_policy(self):
        self.workflow().rename(self.root / '.github/workflows/framework-ci.yaml')
        self.action()
        self.write('.github/version.txt', '7.2.0\n')
        for packages in ({'.': {'release-type': 'java'}}, {'.github': {'component': 'consumer-workflows'}}):
            self.write('release-please-config.json', json.dumps({'packages': packages}))
            self.assertEqual([], self.check()[0])

    def test_workflow_call_only_is_not_seeded_but_mixed_events_are(self):
        for trigger in ('workflow_call', '[workflow_call]', '{workflow_call: {}}'):
            self.write('.github/workflows/reuse.yaml', 'on: ' + trigger + '\npermissions: {}\njobs:\n  work:\n    steps:\n      - uses: vendor/action@mutable\n')
            self.assertEqual([], self.check()[0])
            self.assertTrue(self.check('all')[0])
        self.write('.github/workflows/reuse.yaml', 'on: [workflow_call, workflow_dispatch]\npermissions: {}\njobs:\n  work:\n    steps:\n      - uses: vendor/action@mutable\n')
        self.assertTrue(self.check()[0])

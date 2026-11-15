import copy
import json
from pathlib import Path
import shutil
import tempfile
import unittest

import yaml

import release_permissions as policy


class ReleaseDestinationTest(unittest.TestCase):
    def test_current_destinations_and_component_relative_paths(self):
        config = json.loads((policy.ROOT/'release-please-config.json').read_text())
        result = policy.workflow_targets(config)
        self.assertEqual(7, len(result['.github']))
        self.assertTrue(all(not paths for component, paths in result.items() if component != '.github'))
        self.assertIn('.github/workflows/native.yaml', result['.github'])

    def test_root_inheritance_is_replaced_including_an_empty_list(self):
        config = {'extra-files': ['/.github/workflows/root.yaml'], 'packages': {
            'inherited': {}, 'null': {'extra-files': None},
            '.github': {'extra-files': ['workflows/own.yaml']}, 'empty': {'extra-files': []}}}
        self.assertEqual({
            'inherited': ['.github/workflows/root.yaml'], 'null': ['.github/workflows/root.yaml'],
            '.github': ['.github/workflows/own.yaml'], 'empty': []}, policy.workflow_targets(config))

    def test_strings_objects_root_package_and_repository_root_paths(self):
        config = {'packages': {'.': {'extra-files': [
            '.github/workflows/a.yaml', {'type': 'generic', 'path': '/.github/workflows/b.yml'},
            {'type': 'yaml', 'path': '.github/workflows/a.yaml', 'glob': False}, 'elsewhere.yaml']}}}
        self.assertEqual({'.': ['.github/workflows/a.yaml', '.github/workflows/b.yml']}, policy.workflow_targets(config))
        self.assertEqual('.github/workflows/root.yaml', policy.release_path('some/component', '/.github/workflows/root.yaml'))

    def test_globs_are_rejected_in_effective_package_or_root_configuration(self):
        glob = {'type': 'generic', 'path': 'workflows/*.yaml', 'glob': True}
        for config in [
                {'packages': {'.github': {'extra-files': [glob]}}},
                {'extra-files': [glob], 'packages': {'.github': {}}}]:
            with self.subTest(config=config), self.assertRaisesRegex(ValueError, 'glob'):
                policy.workflow_targets(config)

    def test_overridden_root_glob_is_not_part_of_the_effective_configuration(self):
        config = {'extra-files': [{'type': 'generic', 'path': '*', 'glob': True}],
                  'packages': {'.github': {'extra-files': []}}}
        self.assertEqual({'.github': []}, policy.workflow_targets(config))

    def test_paths_cannot_escape_or_hide_a_workflow_destination(self):
        for path in ['../workflows/a.yaml', '~/a', '.github//workflows/a.yaml', 'a/./b', 'a\\b', '', None]:
            with self.subTest(path=path), self.assertRaises(ValueError):
                policy.release_path('.', path)

    def test_invalid_configuration_or_updater_fails_closed(self):
        for config in [None, {}, {'packages': []}, {'packages': {'a': []}},
                       {'packages': {'a': {'extra-files': 'file'}}},
                       {'packages': {'a': {'extra-files': [1]}}},
                       {'packages': {'a': {'extra-files': [{'type': 'unknown', 'path': 'a'}]}}},
                       {'packages': {'a': {'extra-files': [{'type': 'generic', 'path': 'a', 'glob': 'false'}]}}}]:
            with self.subTest(config=config), self.assertRaises(ValueError):
                policy.workflow_targets(config)


class ReleaseTokenRoleTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='axiz-release-permissions-')
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.workflows = self.root/'.github/workflows'
        self.workflows.mkdir(parents=True)
        for filename in ['release-self.yaml', 'release.yaml', 'service-deploy.yaml']:
            shutil.copyfile(policy.ROOT/'.github/workflows'/filename, self.workflows/filename)
        self.config = json.loads((policy.ROOT/'release-please-config.json').read_text())
        self.save_config()

    def save_config(self):
        (self.root/'release-please-config.json').write_text(json.dumps(self.config))

    def change_token(self, filename, mutate):
        path = self.workflows/filename
        doc = yaml.safe_load(path.read_text())
        token = next(step for job in doc['jobs'].values() for step in job.get('steps', [])
                     if step.get('uses', '').startswith('actions/create-github-app-token@'))
        mutate(token['with'])
        path.write_text(yaml.safe_dump(doc))

    def test_current_roles_match_configuration_and_minimum_permissions(self):
        result = policy.verify(self.root)
        self.assertTrue(result['passed'], result['errors'])
        self.assertEqual(3, len(result['roles']))

    def test_monorepo_without_workflows_permission_is_rejected(self):
        self.change_token('release-self.yaml', lambda token: token.pop('permission-workflows'))
        self.assertFalse(policy.verify(self.root)['passed'])

    def test_unneeded_monorepo_permission_is_rejected_until_role_is_restricted(self):
        self.config['packages']['.github']['extra-files'] = []
        self.save_config()
        self.assertFalse(policy.verify(self.root)['passed'])
        self.change_token('release-self.yaml', lambda token: token.pop('permission-workflows'))
        self.assertTrue(policy.verify(self.root)['passed'])

    def test_workflow_destination_from_another_component_still_requires_permission(self):
        self.config['packages']['.github']['extra-files'] = []
        self.config['packages']['axiz-tools']['extra-files'] = ['/.github/workflows/tool.yaml']
        self.save_config()
        self.assertTrue(policy.verify(self.root)['passed'])
        self.change_token('release-self.yaml', lambda token: token.pop('permission-workflows'))
        self.assertFalse(policy.verify(self.root)['passed'])

    def test_consumer_and_gitops_cannot_gain_workflows_or_issues_permissions(self):
        for filename in ['release.yaml', 'service-deploy.yaml']:
            original = (self.workflows/filename).read_bytes()
            for name in ['permission-workflows', 'permission-issues']:
                with self.subTest(filename=filename, permission=name):
                    self.change_token(filename, lambda token: token.update({name: 'write'}))
                    self.assertFalse(policy.verify(self.root)['passed'])
                    (self.workflows/filename).write_bytes(original)

    def test_dynamic_permissions_are_rejected(self):
        self.change_token('release-self.yaml', lambda token: token.update({'permission-workflows': '${{ inputs.permission }}'}))
        self.assertFalse(policy.verify(self.root)['passed'])

    def test_missing_duplicate_or_unreviewed_token_role_is_rejected(self):
        path = self.workflows/'release.yaml'
        original = path.read_bytes()
        path.unlink()
        self.assertFalse(policy.verify(self.root)['passed'])
        path.write_bytes(original)
        doc = yaml.safe_load(original)
        token = copy.deepcopy(doc['jobs']['release']['steps'][0])
        doc['jobs']['release']['steps'].append(token)
        path.write_text(yaml.safe_dump(doc))
        self.assertFalse(policy.verify(self.root)['passed'])
        path.write_bytes(original)
        (self.workflows/'unreviewed.yaml').write_bytes(original)
        self.assertFalse(policy.verify(self.root)['passed'])

    def test_gitops_token_cannot_expand_the_destination_repository(self):
        self.change_token('service-deploy.yaml', lambda token: token.pop('repositories'))
        self.assertFalse(policy.verify(self.root)['passed'])


if __name__ == '__main__':
    unittest.main()

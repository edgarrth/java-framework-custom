"""Regressions for stale workflow releases and removed generated sample names."""
import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

import yaml

HERE = Path(__file__).parent
spec = importlib.util.spec_from_file_location('sample_workflows', HERE / 'check-sample-workflows.py')
policy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(policy)


class SampleWorkflowsTest(unittest.TestCase):
    def test_all_actual_framework_workflows_use_the_same_v2_release_as_their_guard(self):
        self.assertEqual([], policy.check(HERE.resolve().parents[1]))

    def test_sample_builds_reject_missing_or_mutating_format_checks(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            workflows = root / '.github/workflows'
            workflows.mkdir(parents=True)
            for name in ['ci.yaml', 'samples-matrix.yaml']:
                path = workflows / name
                for goal, valid in [('verify', False), ('spotless:apply verify', False),
                                    ('spotless:check verify', True)]:
                    document = {'jobs': {'samples': {'steps': [{'run':
                        'for dir in target/samples/*/ target/audit-samples/*/; do\n'
                        f'  (cd "$dir" && ./mvnw -B -ntp {goal})\n' + 'done'}]}}}
                    path.write_text(yaml.safe_dump(document))
                    with self.subTest(workflow=name, goal=goal):
                        self.assertEqual(valid, not policy.check(root))
                path.unlink()

    def test_acceptance_scripts_cannot_repair_generated_sources(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            scripts = root / '.github/scripts'
            scripts.mkdir(parents=True)
            for name, goal in [('authorization-samples.sh', 'test'), ('reactive-native-sample.sh', 'verify')]:
                path = scripts / name
                for formatting, valid in [('spotless:apply', False), ('', False), ('spotless:check', True)]:
                    path.write_text(f'./mvnw -B -ntp {formatting} {goal}\n')
                    with self.subTest(script=name, formatting=formatting):
                        self.assertEqual(valid, not policy.check(root))
                path.unlink()

    def fixture(self, root):
        workflows = root / '.github/workflows'
        workflows.mkdir(parents=True)
        modules = ['target/samples/sample-orders', 'target/samples/sample-ux']
        for path, role in zip(modules + ['target/samples/axiz-sample-library'],
                              ['business', 'ux', 'library']):
            manifest = root / path / '.axiz/project.yaml'
            manifest.parent.mkdir(parents=True)
            manifest.write_text(yaml.safe_dump({'archetype': role}))
        generated = root / 'target/samples/modules.json'
        generated.write_text(json.dumps(modules + ['target/samples/axiz-sample-library']))
        document = {
            'jobs': {
                'axiz-workflows-trust': {'steps': [{'env': {'AXIZ_WORKFLOWS_VERSION': 'v2.0.0'}}]},
                'native': {
                    'uses': 'axiz/axiz-workflows/.github/workflows/native-nightly.yaml@v2.0.0',
                    'with': {'modules': json.dumps(modules)},
                    'secrets': {'NEXUS_USERNAME': '${{ secrets.NEXUS_READ_USERNAME }}',
                                'NEXUS_PASSWORD': '${{ secrets.NEXUS_READ_PASSWORD }}'},
                },
            },
        }
        path = workflows / 'native-nightly.yaml'
        path.write_text(yaml.safe_dump(document))
        return path, document, generated, modules

    def test_native_selection_covers_every_generated_service_and_excludes_libraries(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            _, _, generated, _ = self.fixture(root)
            self.assertEqual([], policy.check(root, generated))

    def test_stale_missing_duplicate_and_platform_native_projects_are_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            path, original, generated, modules = self.fixture(root)
            for selected in ([modules[0], 'target/samples/sample-experience'], modules[:1],
                             modules + [modules[0]], modules + ['target/samples/axiz-sample-library']):
                document = copy.deepcopy(original)
                document['jobs']['native']['with']['modules'] = json.dumps(selected)
                path.write_text(yaml.safe_dump(document))
                with self.subTest(selected=selected):
                    self.assertTrue(policy.check(root, generated))

    def test_old_release_and_mismatched_release_guards_are_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            path, original, _, _ = self.fixture(root)
            for old_version in ('v1.0.0', 'main', 'v2.0.1'):
                document = copy.deepcopy(original)
                document['jobs']['native']['uses'] = document['jobs']['native']['uses'].replace(
                    'v2.0.0', old_version)
                path.write_text(yaml.safe_dump(document))
                with self.subTest(version=old_version):
                    self.assertTrue(policy.check(root))
            document = copy.deepcopy(original)
            document['jobs']['native']['uses'] = document['jobs']['native']['uses'].replace(
                'v2.0.0', 'v1.0.0')
            document['jobs']['axiz-workflows-trust']['steps'][0]['env']['AXIZ_WORKFLOWS_VERSION'] = 'v1.0.0'
            path.write_text(yaml.safe_dump(document))
            self.assertTrue(policy.check(root))

    def test_sample_generation_jobs_use_read_credentials(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            path, original, _, _ = self.fixture(root)
            original['jobs']['native']['secrets']['NEXUS_PASSWORD'] = '${{ secrets.NEXUS_PASSWORD }}'
            path.write_text(yaml.safe_dump(original))
            self.assertTrue(any('read-only' in error for error in policy.check(root)))

    def test_duplicate_or_outside_generator_entries_are_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            _, _, generated, modules = self.fixture(root)
            for entries in ([modules[0], modules[0]], ['../outside-project']):
                generated.write_text(json.dumps(entries))
                with self.subTest(entries=entries), self.assertRaises(ValueError):
                    policy.check(root, generated)


if __name__ == '__main__':
    unittest.main()

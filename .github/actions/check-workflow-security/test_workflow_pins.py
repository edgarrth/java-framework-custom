import json
from pathlib import Path
import subprocess
import tempfile
import unittest

from workflow_pins import inventory, validate_pins


class WorkflowPinTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name) / 'source'
        self.root.mkdir()
        (self.root / '.github/workflows').mkdir(parents=True)
        self.config = self.root / 'release-please-config.json'
        self.config.write_text(json.dumps({'packages': {'.github': {'component': 'axiz-workflows', 'extra-files': [{'path': 'workflows/product.yaml'}]}}}))
        (self.root / '.github/version.txt').write_text('2.0.0\n')
        self.product = self.root / '.github/workflows/product.yaml'
        self.product.write_text('on: workflow_call\nversion: axiz-workflows-v2.0.0 # x-release-please-version\n')
        self.consumer = self.root / '.github/workflows/consumer.yaml'
        self.consumer.write_text('on: push\nenv:\n  AXIZ_WORKFLOWS_VERSION: axiz-workflows-v2.0.0\n')

    def git(self, *args):
        return subprocess.run(['git', '-C', str(self.root), *args], check=True, capture_output=True, text=True)

    def initialize(self):
        self.git('init', '--quiet')
        self.git('add', '.')
        self.git('-c', 'user.name=fixture', '-c', 'user.email=fixture@example.test', 'commit', '--quiet', '-m', 'fixture')

    def check(self, root=None):
        return validate_pins(self.root, root or self.root, require_history=True)

    def test_bootstrap_requires_complete_trusted_git_and_initial_version(self):
        self.assertIn('complete trusted Git history', ' '.join(self.check()[0]))
        self.initialize()
        self.assertEqual(([], True), self.check())
        (self.root / '.github/version.txt').write_text('2.0.1\n')
        self.assertTrue(self.check()[0])

    def test_published_consumer_and_new_candidate_are_independent(self):
        self.initialize()
        self.git('tag', 'axiz-workflows-v2.0.0')
        (self.root / '.github/version.txt').write_text('2.0.1\n')
        self.product.write_text(self.product.read_text().replace('2.0.0', '2.0.1'))
        self.assertEqual(([], False), self.check())
        self.consumer.write_text(self.consumer.read_text().replace('2.0.0', '2.0.1'))
        self.assertIn('tag is unavailable', ' '.join(self.check()[0]))

    def test_mixed_consumers_and_legacy_ownership_markers_fail(self):
        self.initialize()
        self.consumer.write_text(self.consumer.read_text() + '  OTHER: ignored\n  AXIZ_WORKFLOWS_VERSION: axiz-workflows-v2.0.1 # x-release-please-version\n')
        errors = ' '.join(self.check()[0])
        self.assertIn('one workflow release', errors)
        self.assertIn('owned by Renovate', errors)

    def test_product_pin_needs_candidate_version_and_updater_marker(self):
        self.initialize()
        self.product.write_text('version: axiz-workflows-v2.0.1\n')
        self.assertIn('product pins must follow', ' '.join(self.check()[0]))

    def test_consumer_preset_is_in_the_global_adoption_inventory(self):
        (self.root / 'renovate.json').write_text('{"extends":["github>edgarrth/java-framework-custom//.github/renovate/platform#axiz-workflows-v2.0.0"]}')
        rows = inventory(self.root)
        self.assertEqual(1, len([row for row in rows if row['kind'] == 'preset']))

    def test_missing_history_cannot_borrow_a_parent_checkout(self):
        self.initialize()
        nested = self.root / 'nested'
        nested.mkdir()
        self.assertIn('complete trusted Git history', ' '.join(self.check(nested)[0]))

    def test_shallow_and_no_tags_clones_do_not_establish_bootstrap(self):
        self.initialize()
        for flags in (['--depth','1'], ['--no-tags']):
            destination = self.root.parent / ('clone-' + str(len(flags)))
            subprocess.run(['git','clone','--quiet',*flags,self.root.as_uri(),str(destination)],check=True,capture_output=True)
            self.assertTrue(self.check(destination)[0])

import copy
import re
import shutil
from pathlib import Path
import tempfile
import subprocess
import unittest

import yaml

from internal_contracts import ROOT, validate_call
from check_workflow_security import validate, check_tree
from check_pipeline_contracts import validate_consumer

PREFIX = 'edgarrth/java-framework-custom/'


class InternalContractTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / '.github/workflows').mkdir(parents=True)
        (self.root / '.github/version.txt').write_text('2.0.0\n')
        self.workflow = self.root / '.github/workflows/fixture.yaml'
        self.workflow.write_text('''on:
  workflow_call:
    inputs:
      identity: {required: true, type: string}
      optional: {required: true, type: string, default: explicit-default}
    secrets:
      READ_TOKEN: {required: true}
''')
        self.call = {'uses': PREFIX + '.github/workflows/fixture.yaml@axiz-workflows-v2.0.0',
                     'with': {'identity': '123'}, 'secrets': {'READ_TOKEN': '${{ secrets.READ_TOKEN }}'}}

    def test_correct_call_and_explicit_default_are_accepted(self):
        self.assertEqual([], validate_call(self.call, self.root))

    def test_consumer_cannot_grant_itself_check_writing_permissions(self):
        consumer = self.root / 'untrusted-consumer'
        directory = consumer / '.github/workflows'
        directory.mkdir(parents=True)
        (directory / 'only-shell.yaml').write_text('on: push\npermissions: {checks: write}\njobs:\n  forge:\n    runs-on: ubuntu-24.04\n    steps: [{run: "true"}]\n')
        policy = consumer / '.github/policies/required-checks-v2.json'
        policy.parent.mkdir(parents=True)
        import json
        policy.write_text(json.dumps({'permissionExceptions': [{'path': '.github/workflows/only-shell.yaml', 'scope': 'workflow', 'permission': 'checks', 'reason': 'Forged consumer approval'}]}))
        self.assertTrue(any('checks: write' in error for error in check_tree(consumer, self.root)[0]))

    def test_required_secret_and_input_and_unknown_input_are_rejected(self):
        for category, field, kind in [('secrets', 'READ_TOKEN', 'required secrets'), ('with', 'identity', 'required inputs')]:
            call = copy.deepcopy(self.call)
            del call[category][field]
            self.assertTrue(any(kind in e for e in validate_call(call, self.root)))
        self.call['with']['retired-selector'] = 'old'
        self.assertTrue(any('unknown inputs' in e for e in validate_call(self.call, self.root)))

    def test_action_contract_supports_both_metadata_extensions(self):
        directory = self.root / '.github/actions/fixture'
        directory.mkdir(parents=True)
        self.call = {'uses': PREFIX + '.github/actions/fixture@axiz-workflows-v2.0.0', 'with': {'identity': '123'}}
        for suffix in ('yaml', 'yml'):
            file = directory / ('action.' + suffix)
            file.write_text('inputs:\n  identity: {required: true}\n')
            self.assertEqual([], validate_call(self.call, self.root))
            wrong = copy.deepcopy(self.call)
            wrong['with'] = {'retired-selector': 'old'}
            errors = validate_call(wrong, self.root)
            self.assertTrue(any('unknown inputs' in e for e in errors))
            self.assertTrue(any('required inputs' in e for e in errors))
            file.unlink()

    def test_missing_metadata_and_wrong_release_and_path_escape_fail_closed(self):
        self.workflow.unlink()
        self.assertTrue(any('metadata' in e for e in validate_call(self.call, self.root)))
        self.call['uses'] = PREFIX + '.github/actions/fixture@axiz-workflows-v9.0.0'
        self.assertTrue(any('exact v2' in e for e in validate_call(self.call, self.root)))
        self.call['uses'] = PREFIX + '.github/actions/../../outside@axiz-workflows-v2.0.0'
        self.assertTrue(any('path' in e for e in validate_call(self.call, self.root)))

    def test_real_demo_callers_and_previous_broken_contract_are_checked(self):
        paths = [ROOT / '.github/workflows/demo-deploy.yaml', *sorted((ROOT / 'axiz-demo/services').glob('*/.github/workflows/deploy.yaml'))]
        self.assertEqual(10, len(paths))
        for path in paths:
            document = yaml.safe_load(path.read_text())
            with self.subTest(path=path.name):
                self.assertEqual([], validate(document))
                broken = copy.deepcopy(document)
                del broken['jobs']['deploy']['secrets']['BUILD_DESTINATION']
                broken['jobs']['deploy']['with']['gitops-branch'] = 'legacy'
                errors = validate(broken)
                self.assertTrue(any('required secrets entry BUILD_DESTINATION' in e for e in errors))
                self.assertTrue(any('unknown inputs entry gitops-branch' in e for e in errors))

    def test_sha_reference_reads_the_pinned_contract_not_the_working_copy(self):
        subprocess.run(['git', '-C', str(self.root), 'init', '--quiet'], check=True)
        subprocess.run(['git', '-C', str(self.root), 'add', '.'], check=True)
        subprocess.run(['git', '-C', str(self.root), '-c', 'user.name=fixture',
                        '-c', 'user.email=fixture@example.test', 'commit', '--quiet', '-m', 'fixture'], check=True)
        revision = subprocess.check_output(['git', '-C', str(self.root), 'rev-parse', 'HEAD'], text=True).strip()
        self.workflow.write_text('on: {workflow_call: {inputs: {new: {required: true}}}}')
        self.call['uses'] = PREFIX + '.github/workflows/fixture.yaml@' + revision
        self.assertEqual([], validate_call(self.call, self.root))
        self.call['uses'] = PREFIX + '.github/workflows/fixture.yaml@' + '0' * 40
        self.assertTrue(any('revision unavailable' in e for e in validate_call(self.call, self.root)))

    def commit(self, root):
        for args in (['init', '--quiet'], ['add', '.'],
                     ['-c', 'user.name=fixture', '-c', 'user.email=fixture@example.test',
                      'commit', '--quiet', '-m', 'fixture']):
            subprocess.run(['git', '-C', str(root), *args], check=True, capture_output=True)

    def test_previous_tag_uses_its_contract_and_candidate_uses_working_contract(self):
        self.commit(self.root)
        subprocess.run(['git', '-C', str(self.root), 'tag', 'axiz-workflows-v2.0.0'], check=True)
        (self.root / '.github/version.txt').write_text('2.0.1\n')
        self.workflow.write_text('on: {workflow_call: {inputs: {candidate: {required: true}}}}')
        self.assertEqual([], validate_call(self.call, self.root))
        candidate = {'uses': self.call['uses'].replace('2.0.0', '2.0.1'), 'with': {'candidate': 'yes'}}
        self.assertEqual([], validate_call(candidate, self.root))
        candidate['with'] = self.call['with']
        self.assertTrue(any('required inputs entry candidate' in e for e in validate_call(candidate, self.root)))
        self.call['with'] = {'candidate': 'yes'}
        self.assertTrue(any('required inputs entry identity' in e for e in validate_call(self.call, self.root)))

    def test_unavailable_tag_and_archive_do_not_borrow_parent_or_fallback(self):
        self.call['uses'] = self.call['uses'].replace('2.0.0', '2.0.1')
        self.assertTrue(any('history unavailable' in e for e in validate_call(self.call, self.root)))
        self.commit(self.root)
        self.assertTrue(any('revision unavailable: refs/tags/' in e for e in validate_call(self.call, self.root)))
        nested = self.root / 'downloaded-action'
        shutil.copytree(self.root / '.github', nested / '.github')
        self.assertTrue(any('history unavailable' in e for e in validate_call(self.call, nested)))
        # Una rama con el nombre del tag solicitado no es un tag publicado.
        subprocess.run(['git', '-C', str(self.root), 'branch', 'axiz-workflows-v2.0.1'], check=True)
        self.assertTrue(any('revision unavailable: refs/tags/' in e for e in validate_call(self.call, self.root)))

    def test_scan_root_and_trusted_contract_root_are_explicitly_separate(self):
        caller = ROOT / 'axiz-demo/services/payment-orders/.github/workflows/deploy.yaml'
        reference = yaml.safe_load(caller.read_text())['jobs']['automatic']['steps'][0]['uses']
        adopted = reference.rsplit('@', 1)[1].removeprefix('axiz-workflows-v')
        copy_root = self.root / 'candidate-copy'
        shutil.copytree(ROOT / '.github/actions/resolve-service-build', copy_root / '.github/actions/resolve-service-build')
        (copy_root / '.github/version.txt').write_text(adopted + '\n')
        action = copy_root / '.github/actions/resolve-service-build/action.yaml'
        document = yaml.safe_load(action.read_text())
        document['inputs']['candidate-only'] = {'required': True}
        action.write_text(yaml.safe_dump(document))
        source = self.root / 'consumer/.github/workflows'
        source.mkdir(parents=True)
        shutil.copy(caller, source / 'deploy.yaml')
        self.assertEqual([], check_tree(source.parent.parent, ROOT)[0])
        self.assertTrue(any('candidate-only' in e for e in check_tree(source.parent.parent, copy_root)[0]))
        # Los metadatos falsificados de un consumidor no pueden cambiar la fuente de contrato de confianza por defecto.
        shutil.copytree(copy_root / '.github/actions', source.parent / 'actions')
        (source.parent / 'version.txt').write_text(adopted + '\n')
        self.assertEqual([], check_tree(source.parent.parent)[0])

    def test_pipeline_gate_resolves_a_previous_tag_even_if_candidate_removed_the_workflow(self):
        self.commit(self.root)
        subprocess.run(['git', '-C', str(self.root), 'tag', 'axiz-workflows-v2.0.0'], check=True)
        (self.root / '.github/version.txt').write_text('2.0.1\n')
        self.workflow.unlink()
        document = {'jobs': {'contract': self.call}}
        self.assertEqual([], validate_consumer(document, self.workflow.parent, 'build'))
        self.call['uses'] = self.call['uses'].replace('2.0.0', '2.0.1')
        self.assertTrue(any('missing or ambiguous' in e for e in validate_consumer(document, self.workflow.parent, 'build')))

    def test_consumer_same_version_can_read_tag_instead_of_dirty_candidate(self):
        self.commit(self.root)
        subprocess.run(['git', '-C', str(self.root), 'tag', 'axiz-workflows-v2.0.0'], check=True)
        self.workflow.write_text('on: {workflow_call: {inputs: {candidate: {required: true}}}}')
        self.assertEqual([], validate_call(self.call, self.root, snapshot=False))
        self.assertTrue(validate_call(self.call, self.root, snapshot=True))

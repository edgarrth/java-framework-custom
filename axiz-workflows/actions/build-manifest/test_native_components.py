import copy
import io
import json
import os
import platform
import shutil
import subprocess
import tarfile
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import rfc8785

import build_bundle
import manifest
import native_components as native
import prepare
import release_manifest
from test_manifest import fixture


def executable(target):
    if target == 'linux-x64':
        data = bytearray(64)
        data[:7] = b'\x7fELF\x02\x01\x01'
        data[18:20] = (62).to_bytes(2, 'little')
    elif target == 'macos-arm64':
        data = bytearray(32)
        data[:4] = b'\xcf\xfa\xed\xfe'
        data[4:8] = (0x100000c).to_bytes(4, 'little')
    else:
        data = bytearray(80)
        data[:2] = b'MZ'
        data[60:64] = (64).to_bytes(4, 'little')
        data[64:70] = b'PE\0\0\x64\x86'
    return bytes(data)


class NativeComponentsTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.components = self.root / 'components'
        self.staging = self.root / 'staging'
        self.reports = self.root / 'reports'
        self.components.mkdir()
        self.staging.mkdir()
        self.reports.mkdir()
        (self.root / 'cli').mkdir()
        (self.root / 'cli/pom.xml').write_text('<project><groupId>pe.axiz</groupId><artifactId>cli</artifactId><version>2.0.0</version></project>')
        self.data = fixture()
        self.spec = native.specification('cli', 'axiz', list(native.PLATFORMS))
        self.data['parameters']['native'] = self.spec
        self.metadata = self.root / 'metadata.json'
        self.metadata.write_text(json.dumps(self.data))
        self.prefix = 'pe/axiz/cli/2.0.0/cli-2.0.0'
        pom = self.staging / (self.prefix + '.pom')
        pom.parent.mkdir(parents=True)
        pom.write_bytes(b'<project/>')
        for target in self.spec['platforms']:
            directory = self.components / ('native-component-1-' + target)
            directory.mkdir()
            archive = 'archive.' + native.PLATFORMS[target][2]
            payload = native.archive_bytes(executable(target), 'axiz', target)
            (directory / archive).write_bytes(payload)
            (directory / 'smoke.log').write_bytes(b'2.0.0\ncatalog passed\n')
            component = {'schema': 'axiz.native-component/v1',
                         'source': self.data['source'], 'version': self.data['version'],
                         'parameters': self.data['parameters'], 'candidate': '', 'dependencies': {},
                         'build': dict(self.data['build'], workflow=dict(self.data['build']['workflow'], path='.github/workflows/native.yaml')),
                         'native': {'module': 'cli', 'binary': 'axiz', 'platform': target},
                         'coordinate': {'groupId': 'pe.axiz', 'artifactId': 'cli', 'version': '2.0.0'},
                         'archive': {'path': archive, 'sha256': manifest.sha256(payload)},
                         'smoke': {'path': 'smoke.log', 'sha256': manifest.sha256((directory / 'smoke.log').read_bytes())}}
            (directory / 'native-component.json').write_bytes(rfc8785.dumps(component))
            (directory / 'native-component.sigstore.json').write_bytes(b'{"fixture":"signature verifier mocked"}')

    def directory(self, target='linux-x64'):
        return self.components / ('native-component-1-' + target)

    def mutate(self, change):
        path = self.directory() / 'native-component.json'
        value = manifest.read_json(path)
        change(value)
        path.write_bytes(rfc8785.dumps(value))

    def merge(self):
        return native.merge(self.root, self.metadata, self.components, self.staging, self.reports)

    def assert_unmodified(self):
        self.assertEqual([self.prefix + '.pom'], [p.relative_to(self.staging).as_posix() for p in self.staging.rglob('*') if p.is_file()])
        self.assertEqual([], list(self.reports.iterdir()))

    def test_all_platforms_are_verified_before_staging_and_exact_signer_is_required(self):
        with patch.object(native.subprocess, 'run') as run:
            records = self.merge()
        self.assertEqual(self.spec['platforms'], [r['platform'] for r in records])
        self.assertEqual(3, run.call_count)
        for call in run.call_args_list:
            command = call.args[0]
            self.assertEqual('https://github.com/axiz/axiz-workflows/.github/workflows/native.yaml@refs/tags/v2.0.0', command[command.index('--certificate-identity') + 1])
            self.assertEqual('a' * 40, command[command.index('--certificate-github-workflow-sha') + 1])
            self.assertEqual('example/payments', command[command.index('--certificate-github-workflow-repository') + 1])
        for record in records:
            archive = self.staging / record['artifact']
            self.assertEqual(manifest.sha256(archive.read_bytes()) + '\n', archive.with_name(archive.name + '.sha256').read_text())

    def test_different_producer_source_dependency_parameters_or_candidate_never_stage_bytes(self):
        path = self.directory() / 'native-component.json'
        original = path.read_bytes()
        mutations = [lambda c: c['source'].update(sha='c' * 40),
                     lambda c: c['build'].update(run_attempt=2),
                     lambda c: c['build']['workflow'].update(sha='d' * 40),
                     lambda c: c['dependencies'].update(axiz_version='2.1.0'),
                     lambda c: c.update(candidate='2.0.0/2'),
                     lambda c: c['parameters'].update(maven_args=['-Duser.timezone=UTC']),
                     lambda c: c.update(extra='unreviewed'),
                     lambda c: c['coordinate'].update(artifactId='other')]
        for change in mutations:
            with self.subTest(change=change), patch.object(native.subprocess, 'run'):
                path.write_bytes(original)
                self.mutate(change)
                with self.assertRaises(ValueError):
                    self.merge()
                self.assert_unmodified()

    def test_failed_last_signature_does_not_stage_any_platform(self):
        with patch.object(native.subprocess, 'run', side_effect=[None, None, subprocess.CalledProcessError(1, 'cosign')]), self.assertRaises(subprocess.CalledProcessError):
            self.merge()
        self.assert_unmodified()

    def test_rejects_corrupt_archive_unknown_files_and_noncanonical_metadata(self):
        archive = self.directory() / 'archive.tar.gz'
        original = archive.read_bytes()
        archive.write_bytes(b'corrupted')
        with self.assertRaises(ValueError):
            self.merge()
        archive.write_bytes(original)
        extra = self.directory() / 'undeclared.txt'
        extra.write_text('untrusted')
        with self.assertRaises(ValueError):
            self.merge()
        extra.unlink()
        metadata = self.directory() / 'native-component.json'
        metadata.write_text(json.dumps(manifest.read_json(metadata), indent=2))
        with self.assertRaisesRegex(ValueError, 'canonical'):
            self.merge()
        self.assert_unmodified()

    def test_archive_symlinks_additional_entries_and_wrong_machine_are_rejected(self):
        for path_target in ('../outside', 'axiz'):
            stream = io.BytesIO()
            with tarfile.open(fileobj=stream, mode='w:gz') as archive:
                item = tarfile.TarInfo(path_target)
                item.type = tarfile.SYMTYPE
                item.linkname = '/tmp/outside'
                archive.addfile(item)
            path = self.root / 'malicious.tar.gz'
            path.write_bytes(stream.getvalue())
            with self.assertRaises(ValueError):
                native.verify_archive(path, 'axiz', 'linux-x64')
        for target in native.PLATFORMS:
            with self.subTest(target=target), self.assertRaises(ValueError):
                native.binary_architecture(b'#!/bin/sh\n', target)
            payload = native.archive_bytes(executable(target), 'axiz', target)
            self.assertEqual(payload, native.archive_bytes(executable(target), 'axiz', target))
            archive = self.root / ('valid.' + native.PLATFORMS[target][2])
            archive.write_bytes(payload)
            native.verify_archive(archive, 'axiz', target)

    def test_rejects_missing_platform_and_symlinked_download_root(self):
        shutil.rmtree(self.directory('windows-x64'))
        with patch.object(native.subprocess, 'run'), self.assertRaises(ValueError):
            self.merge()
        self.assert_unmodified()
        replacement = self.root / 'alias'
        replacement.symlink_to(self.components, target_is_directory=True)
        with self.assertRaises(ValueError):
            native.merge(self.root, self.metadata, replacement, self.staging, self.reports)
        self.assert_unmodified()

    def test_no_checksum_or_evidence_overwrite_even_with_dangling_symlink(self):
        checksum = self.staging / (self.prefix + '-native-windows-x64.zip.sha256')
        checksum.symlink_to(self.root / 'absent')
        with patch.object(native.subprocess, 'run'), self.assertRaises(ValueError):
            self.merge()
        self.assertFalse((self.staging / (self.prefix + '-native-linux-x64.tar.gz')).exists())
        self.assertEqual([], list(self.reports.iterdir()))
        checksum.unlink()
        proof = self.reports / 'native.json'
        proof.write_bytes(b'previous evidence')
        with self.assertRaises(ValueError):
            self.merge()
        self.assertEqual(b'previous evidence', proof.read_bytes())

    def test_rejects_invalid_specifications_and_unsafe_modules(self):
        for module, binary, targets in [('../cli', 'axiz', ['linux-x64']), ('cli//sub', 'axiz', ['linux-x64']),
                                         ('cli/./sub', 'axiz', ['linux-x64']),
                                         ('cli', 'bad;name', ['linux-x64']), ('cli', 'axiz', []),
                                         ('cli', 'axiz', ['linux-x64'] * 2), ('cli', 'axiz', [{}]),
                                         ('cli', 'axiz', ['linux-arm64'])]:
            with self.subTest(module=module, binary=binary, targets=targets), self.assertRaises(ValueError):
                native.specification(module, binary, targets)

    def sealed(self):
        with patch.object(native.subprocess, 'run'):
            self.merge()
        bundle = self.root / 'bundle'
        bundle.mkdir()
        shutil.copytree(self.staging, bundle / 'maven')
        shutil.copytree(self.reports, bundle / 'reports')
        data = copy.deepcopy(self.data)
        data['artifacts'] = {'maven': build_bundle.inventory(bundle / 'maven', data['version'])}
        for name in ('verify.log', 'mutation.log', 'encapsulation.log', 'bom.json', 'scan.json'):
            (bundle / 'reports' / name).write_bytes(b'passed\n')
        build_bundle.complete(data, bundle)
        data['parameters_sha256'] = manifest.canonical_hash(data['parameters'])
        manifest.validate(data, bundle)
        (bundle / 'build-manifest.json').write_bytes(rfc8785.dumps(data))
        (bundle / 'build-manifest.sigstore.json').write_bytes(b'fixture main signature')
        return data, bundle

    def test_complete_bundle_binds_native_evidence_and_platform_download_names(self):
        data, bundle = self.sealed()
        assets = native.release_assets(data, bundle)
        self.assertEqual(6, len(assets))
        for target in self.spec['platforms']:
            name = 'axiz-v2.0.0-' + target + '.' + native.PLATFORMS[target][2]
            self.assertEqual((manifest.sha256(assets[name]) + '  ' + name + '\n').encode(), assets[name + '.sha256'])
        proof = bundle / 'reports/native/linux-x64/smoke.log'
        proof.write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'evidence changed'):
            manifest.validate(data, bundle)

    def test_configured_native_platforms_cannot_be_omitted_or_injected_after_sealing(self):
        data, bundle = self.sealed()
        for mutate in [lambda d: d['gates'].pop(next(i for i, g in enumerate(d['gates']) if g['name'] == 'native')),
                       lambda d: d['artifacts']['maven'].pop(),
                       lambda d: d['parameters'].pop('native')]:
            changed = copy.deepcopy(data)
            mutate(changed)
            changed['parameters_sha256'] = manifest.canonical_hash(changed['parameters'])
            with self.assertRaises(ValueError):
                manifest.validate(changed)

    def test_cli_compile_forwards_only_validated_arguments_without_shell(self):
        with patch.object(native.shutil, 'which', return_value='/usr/bin/mvn'), patch.object(native.subprocess, 'run') as run:
            native.compile_native(self.root, self.data)
            self.assertEqual(['/usr/bin/mvn', '-B', '-ntp', '-Pnative', '-pl', 'cli', '-am', 'package'], run.call_args.args[0])
            self.assertNotIn('shell', run.call_args.kwargs)
        self.data['parameters']['maven_args'] = ['-DskipTests']
        with patch.object(native.subprocess, 'run') as run, self.assertRaises(ValueError):
            native.compile_native(self.root, self.data)
        run.assert_not_called()

    def test_smoke_rejects_a_different_cli_version_and_does_not_pass_repository_credentials(self):
        directory = self.root / 'cli/target'
        directory.mkdir()
        (directory / 'axiz').write_bytes(executable('linux-x64'))
        output = self.root / 'rejected-native'
        response = subprocess.CompletedProcess([], 0, stdout=b'axiz 1.0.0\n', stderr=b'')
        with patch.object(native.platform, 'system', return_value='Linux'), patch.object(native.platform, 'machine', return_value='x86_64'), patch.dict(os.environ, {'NEXUS_PASSWORD': 'must-not-inherit', 'GH_TOKEN': 'must-not-inherit'}), patch.object(native.subprocess, 'run', return_value=response) as run:
            with self.assertRaisesRegex(ValueError, 'version differs'):
                native.create(self.root, self.data, self.spec, 'linux-x64', output)
            for call in run.call_args_list:
                self.assertNotIn('NEXUS_PASSWORD', call.kwargs['env'])
                self.assertNotIn('GH_TOKEN', call.kwargs['env'])
            self.assertFalse(output.exists())

    def test_archive_additional_entries_are_rejected_even_if_the_first_executable_is_valid(self):
        archive = self.root / 'extra.tar.gz'
        with tarfile.open(archive, mode='w:gz') as stream:
            data = executable('linux-x64')
            entry = tarfile.TarInfo('axiz')
            entry.size, entry.mode = len(data), 0o755
            stream.addfile(entry, io.BytesIO(data))
            stream.addfile(tarfile.TarInfo('undeclared'))
        with self.assertRaisesRegex(ValueError, 'additional files'):
            native.verify_archive(archive, 'axiz', 'linux-x64')

    def test_another_build_manifest_cannot_be_mixed_into_the_same_draft_release(self):
        data, bundle = self.sealed()
        digest = manifest.sha256((bundle / 'build-manifest.json').read_bytes())
        release = {'tag_name': 'v2.0.0', 'draft': True, 'assets': [{'name': 'build-manifest-' + 'e' * 64 + '.json', 'id': 5}]}
        with patch.object(release_manifest, 'api', return_value=release), patch.object(release_manifest.subprocess, 'run') as run:
            with self.assertRaisesRegex(ValueError, 'different approved build'):
                release_manifest.retain('example/payments', 'v2.0.0', digest, bundle, publish=True)
            run.assert_not_called()

    @unittest.skipUnless(platform.system() == 'Linux' and platform.machine() == 'x86_64', 'Real local ELF smoke is specific to Fedora x64')
    def test_real_linux_executable_is_smoked_packaged_and_verified(self):
        directory = self.root / 'cli/target'
        directory.mkdir()
        shutil.copyfile(shutil.which('true'), directory / 'tool')
        (directory / 'tool').chmod(0o755)
        spec = native.specification('cli', 'tool', ['linux-x64'])
        data = copy.deepcopy(self.data)
        data['parameters']['native'] = spec
        data['build']['workflow']['path'] = '.github/workflows/native.yaml'
        output = self.root / 'real-native'
        component = native.create(self.root, data, spec, 'linux-x64', output)
        (output / 'native-component.sigstore.json').write_bytes(b'fixture')
        expected = copy.deepcopy(data)
        expected['build']['workflow']['path'] = '.github/workflows/lib-build.yaml'
        native.verify_component(component, output, expected, spec, 'linux-x64')
        self.assertIn(b'GNU coreutils', (output / 'smoke.log').read_bytes())

    def test_release_preflight_compares_all_native_bytes_before_upload_and_never_clobbers(self):
        data, bundle = self.sealed()
        digest = manifest.sha256((bundle / 'build-manifest.json').read_bytes())
        release = {'tag_name': 'v2.0.0', 'draft': True, 'assets': [{'name': 'axiz-v2.0.0-windows-x64.zip', 'id': 9}]}
        with patch.object(release_manifest, 'api', return_value=release), patch.object(release_manifest, 'asset', return_value=b'other bytes'), patch.object(release_manifest.subprocess, 'run') as run:
            with self.assertRaisesRegex(ValueError, 'cannot be overwritten'):
                release_manifest.retain('example/payments', 'v2.0.0', digest, bundle, publish=True)
            run.assert_not_called()
        release['assets'] = []
        uploaded = {}
        def consume(command, **kwargs):
            if 'upload' in command:
                for name in command[4:command.index('--repo')]:
                    path = Path(name)
                    uploaded[path.name] = path.read_bytes()
                self.assertNotIn('--clobber', command)
        with patch.object(release_manifest, 'api', return_value=release), patch.object(release_manifest.subprocess, 'run', side_effect=consume):
            release_manifest.retain('example/payments', 'v2.0.0', digest, bundle, publish=True)
        self.assertEqual(8, len(uploaded))
        self.assertIn('axiz-v2.0.0-windows-x64.zip', uploaded)

    def test_native_producer_oidc_identity_cannot_be_substituted_by_a_library_build(self):
        claims = {'job_workflow_ref': 'axiz/axiz-workflows/.github/workflows/native.yaml@refs/tags/v2.0.0',
                  'job_workflow_sha': 'b' * 40, 'repository': 'example/payments', 'sha': 'a' * 40}
        self.assertEqual('.github/workflows/native.yaml', prepare.workflow_identity(claims, 'example/payments', 'a' * 40, 'b' * 40, kind='native-component')['path'])
        with self.assertRaises(ValueError):
            prepare.workflow_identity(claims, 'example/payments', 'a' * 40, 'b' * 40)
        with self.assertRaises(ValueError):
            prepare.workflow_identity(claims, 'example/payments', 'a' * 40, 'b' * 40, kind='anything')


if __name__ == '__main__':
    unittest.main()

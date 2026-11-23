import copy
import hashlib
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import unittest

from check_image_scan import fixed_components, source_repository, validate, verify_fixed_ancestry


class ImageScanTest(unittest.TestCase):
    def setUp(self):
        self.now = datetime.now(timezone.utc)
        self.report = {
            'SchemaVersion': 2, 'ArtifactType': 'container_image', 'ArtifactName': 'example:1',
            'CreatedAt': self.now.isoformat(), 'Metadata': {'ImageID': 'sha256:' + 'a' * 64},
            'Results': [{'Packages': [{'Name': 'library', 'Version': '1.0'}]}],
        }

    def test_fixed_source_must_be_recomputed_and_bound_to_actual_image(self):
        self.report['Results'][0]['ExperimentalModifiedFindings'] = [{'Status': 'fixed'}]
        vex = {'statements': [{'products': [{'@id': 'pkg:golang/github.com/grafana/tempo@v0.0.0-20260101000000-' + 'b' * 12}], 'x_axiz_evidence': {
            'repository': 'https://github.com/grafana/tempo', 'fix': 'a' * 40, 'revision': 'b' * 40,
            'patch': 'https://github.com/grafana/tempo/commit/' + 'a' * 40,
            'advisory': 'https://grafana.com/security/example/'},
            'vulnerability': {'name': 'CVE-2026-12345', '@id': 'https://github.com/advisories/GHSA-aaaa-bbbb-cccc'}}]}
        published = {'ghsa_id': 'GHSA-aaaa-bbbb-cccc', 'cve_id': 'CVE-2026-12345',
                     'references': ['https://grafana.com/security/example', 'https://github.com/grafana/tempo/commit/' + 'a' * 40]}
        fetch_advisory = lambda ghsa: published
        image = self.report['Metadata']['ImageID']
        actual = {'status': 'ahead', 'ahead_by': 2, 'behind_by': 0,
                  'merge_base_commit': {'sha': 'a' * 40}}
        verify_fixed_ancestry(self.report, vex, image, lambda *args: actual, fetch_advisory)
        with self.assertRaisesRegex(ValueError, 'immutable'):
            verify_fixed_ancestry(self.report, vex, 'sha256:' + 'b' * 64, lambda *args: actual, fetch_advisory)
        for wrong in ({}, {**actual, 'behind_by': 1}, {**actual, 'merge_base_commit': {'sha': 'c' * 40}}):
            with self.assertRaisesRegex(ValueError, 'Upstream'):
                verify_fixed_ancestry(self.report, vex, image, lambda *args: wrong, fetch_advisory)

        for changed in ({}, {**published, 'cve_id': 'CVE-2026-00000'},
                        {**published, 'ghsa_id': 'GHSA-xxxx-yyyy-zzzz'},
                        {**published, 'references': published['references'][:1]},
                        {**published, 'references': published['references'][1:]}):
            with self.assertRaisesRegex(ValueError, 'Upstream advisory'):
                verify_fixed_ancestry(self.report, vex, image, lambda *args: actual, lambda ghsa: changed)

    def test_source_proof_cannot_substitute_an_unrelated_repository_or_short_commit(self):
        purl = 'pkg:golang/github.com/grafana/tempo@v0.0.0-20260101000000-' + 'b' * 12
        self.assertEqual('https://github.com/grafana/tempo', source_repository(purl, 'b' * 40))
        for revision in ('b' * 12, 'c' * 40):
            with self.assertRaises(ValueError):
                source_repository(purl, revision)
        vex = json.loads((Path(__file__).parents[1] / 'policies/tempo-fixed.openvex.json').read_text())
        vex['statements'][0]['x_axiz_evidence']['repository'] = 'https://github.com/other/project'
        instant = datetime.fromisoformat(vex['timestamp'].replace('Z', '+00:00')) + timedelta(hours=1)
        with self.assertRaises(ValueError):
            fixed_components(vex, instant)

    def test_complete_clean_inventory_is_accepted(self):
        self.assertEqual(1, validate(self.report, 'example:1', self.now))

    def test_clean_report_cannot_substitute_another_build_of_the_same_tag(self):
        verify_fixed_ancestry(self.report, None, self.report['Metadata']['ImageID'])
        with self.assertRaisesRegex(ValueError, 'immutable'):
            verify_fixed_ancestry(self.report, None, 'sha256:' + 'b' * 64)

    def test_os_inventory_cannot_hide_an_opaque_application_binary(self):
        self.report['Results'][0]['Type'] = 'alpine'
        with self.assertRaisesRegex(ValueError, 'gobinary'):
            validate(self.report, 'example:1', self.now, required_types=('gobinary',))
        self.report['Results'].append({'Type': 'gobinary', 'Packages': [{'Name': 'stdlib', 'Version': 'v1.27.4'}]})
        self.assertEqual(2, validate(self.report, 'example:1', self.now, required_types=('gobinary',)))
        with self.assertRaisesRegex(ValueError, 'jar'):
            validate(self.report, 'example:1', self.now, required_types=('gobinary', 'jar'))

    def test_packed_or_unrecognized_binary_cannot_appear_clean(self):
        for results in (None, [], [{}], [{'Packages': []}]):
            with self.subTest(results=results):
                report = copy.deepcopy(self.report)
                report['Results'] = results
                with self.assertRaises(ValueError):
                    validate(report, 'example:1', self.now)

    def test_every_medium_high_critical_and_unknown_blocks_without_a_fix(self):
        for severity in ('MEDIUM', 'HIGH', 'CRITICAL', 'UNKNOWN', None):
            report = copy.deepcopy(self.report)
            report['Results'][0]['Vulnerabilities'] = [{'Severity': severity, 'FixedVersion': ''}]
            with self.subTest(severity=severity), self.assertRaises(ValueError):
                validate(report, 'example:1', self.now)

    def test_low_does_not_change_the_requested_threshold(self):
        self.report['Results'][0]['Vulnerabilities'] = [{'Severity': 'LOW'}]
        self.assertEqual(1, validate(self.report, 'example:1', self.now))

    def test_wrong_image_identity_or_stale_evidence_is_rejected(self):
        cases = [('ArtifactName', 'different:1'), ('ArtifactType', 'filesystem'),
                 ('Metadata', {}), ('CreatedAt', (self.now - timedelta(days=1)).isoformat()),
                 ('CreatedAt', (self.now + timedelta(hours=1)).isoformat()),
                 ('CreatedAt', self.now.replace(tzinfo=None).isoformat())]
        for key, value in cases:
            report = copy.deepcopy(self.report)
            report[key] = value
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                validate(report, 'example:1', self.now)

    def test_unversioned_components_do_not_prove_coverage(self):
        self.report['Results'][0]['Packages'] = [{'Name': 'library'}]
        with self.assertRaises(ValueError):
            validate(self.report, 'example:1', self.now)

    def test_locked_java_dependencies_must_be_individually_inventoried(self):
        lock = {'schema': 1, 'artifacts': [{'group': 'test', 'name': 'engine', 'version': '2'}]}
        with self.assertRaises(ValueError):
            validate(self.report, 'example:1', self.now, lock)
        self.report['Results'].append({'Type': 'jar', 'Packages': [{'Name': 'test:engine', 'Version': '2'}]})
        self.assertEqual(2, validate(self.report, 'example:1', self.now, lock))
        self.report['Results'][-1]['Packages'][0]['Version'] = '1'
        with self.assertRaises(ValueError):
            validate(self.report, 'example:1', self.now, lock)

    def test_old_engine_or_opaque_standalone_cannot_coexist_with_corrected_dependencies(self):
        lock = {'schema': 1, 'artifacts': [{'group': 'test', 'name': 'engine', 'version': '2'}]}
        for extra in ({'Name': 'test:engine', 'Version': '1'},
                      {'Name': 'org.wiremock:wiremock-standalone', 'Version': '3.13.2'}):
            report = copy.deepcopy(self.report)
            report['Results'].append({'Type': 'jar', 'Packages': [{'Name': 'test:engine', 'Version': '2'}, extra]})
            with self.subTest(extra=extra), self.assertRaises(ValueError):
                validate(report, 'example:1', self.now, lock)

    def test_empty_or_invalid_lock_cannot_disable_java_coverage(self):
        for lock in ({}, {'schema': 1, 'artifacts': []}, {'schema': 2, 'artifacts': [{}]}):
            with self.subTest(lock=lock), self.assertRaises(ValueError):
                validate(self.report, 'example:1', self.now, lock)

    def test_unversioned_go_graph_root_does_not_count_as_versioned_dependencies(self):
        root = {'Name': 'main', 'Relationship': 'root', 'DependsOn': ['stdlib@1']}
        self.report['Results'] = [{'Type': 'gobinary', 'Packages': [root]}]
        with self.assertRaisesRegex(ValueError, 'no components'):
            validate(self.report, 'example:1', self.now)
        self.report['Results'][0]['Packages'].append({'Name': 'stdlib', 'Version': '1'})
        self.assertEqual(1, validate(self.report, 'example:1', self.now))

    def test_workspace_modules_need_exact_source_provenance_and_cannot_hide_other_dependencies(self):
        sources = json.dumps([{'name': 'loki', 'version': '3.7.10',
            'url': 'https://codeload.github.com/grafana/loki/tar.gz/refs/tags/v3.7.10',
            'sha256': 'a' * 64, 'workspaceModules': [{'module': 'github.com/grafana/loki/pkg/push',
                'path': 'pkg/push', 'goModSha256': 'b' * 64}]}]).encode()
        self.report['Metadata']['ImageConfig'] = {'config': {'Labels': {
            'pe.axiz.go-sources-sha256': hashlib.sha256(sources).hexdigest()}}}
        result = {'Type': 'gobinary', 'Target': 'usr/local/bin/loki',
                  'Packages': [{'Name': './pkg/push'}, {'Name': 'stdlib', 'Version': 'v1.27.4'}]}
        self.report['Results'] = [result]
        self.assertEqual(2, validate(self.report, 'example:1', self.now, go_sources=sources))
        with self.assertRaisesRegex(ValueError, 'source inventory'):
            validate(self.report, 'example:1', self.now, go_sources=sources + b' ')
        for name in ('./pkg/other', 'github.com/other/library'):
            result['Packages'][0]['Name'] = name
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, 'workspace source'):
                validate(self.report, 'example:1', self.now, go_sources=sources)
        result['Packages'][0]['Name'] = './pkg/push'
        result['Vulnerabilities'] = [{'Severity': 'HIGH'}]
        with self.assertRaisesRegex(ValueError, 'unresolved'):
            validate(self.report, 'example:1', self.now, go_sources=sources)

    def test_suppressed_findings_require_an_exact_fixed_source_review(self):
        vex = json.loads((Path(__file__).parents[1] / 'policies/tempo-fixed.openvex.json').read_text())
        # Prueba el intervalo de validez de la política, con independencia del reloj real.
        self.now = datetime.fromisoformat(vex['timestamp'].replace('Z', '+00:00')) + timedelta(hours=1)
        self.report['CreatedAt'] = self.now.isoformat()
        statement = vex['statements'][0]
        purl = statement['products'][0]['@id']
        result = self.report['Results'][0]
        result['Packages'][0]['Identifier'] = {'PURL': purl}
        modified = {'Type': 'vulnerability', 'Status': 'fixed', 'Finding': {
            'VulnerabilityID': statement['vulnerability']['name'],
            'PkgIdentifier': {'PURL': purl}, 'Severity': 'HIGH'}}
        result['ExperimentalModifiedFindings'] = [modified]
        with self.assertRaisesRegex(ValueError, 'suppressed'):
            validate(self.report, 'example:1', self.now)
        self.assertEqual(1, validate(self.report, 'example:1', self.now, vex=vex))
        for status in ('ignored', 'not_affected', 'unknown'):
            modified['Status'] = status
            with self.subTest(status=status), self.assertRaisesRegex(ValueError, 'suppressed'):
                validate(self.report, 'example:1', self.now, vex=vex)
        modified['Status'] = 'fixed'
        for key, value in [('VulnerabilityID', 'CVE-2026-99999'),
                           ('PkgIdentifier', {'PURL': purl + '-different'})]:
            altered = copy.deepcopy(self.report)
            altered['Results'][0]['ExperimentalModifiedFindings'][0]['Finding'][key] = value
            with self.subTest(key=key), self.assertRaisesRegex(ValueError, 'suppressed'):
                validate(altered, 'example:1', self.now, vex=vex)
        result['Vulnerabilities'] = [modified['Finding']]
        with self.assertRaisesRegex(ValueError, 'unresolved'):
            validate(self.report, 'example:1', self.now, vex=vex)

    def test_vex_cannot_authorize_missing_patches_wildcards_or_expired_review(self):
        vex = json.loads((Path(__file__).parents[1] / 'policies/tempo-fixed.openvex.json').read_text())
        now = datetime.fromisoformat(vex['timestamp'].replace('Z', '+00:00')) + timedelta(hours=1)
        self.assertEqual(2, len(fixed_components(vex, now)))
        for field, value in [('status', 'not_affected'), ('products', [{'@id': 'pkg:golang/*'}]),
                             ('x_axiz_evidence', {'merge_base': 'wrong'}),
                             ('x_axiz_evidence', {'patch': 'https://github.com/other/project/commit/' + 'a' * 40}),
                             ('x_axiz_evidence', {'advisory_patch_reference': 'https://github.com/advisories/GHSA-aaaa-bbbb-cccc'})]:
            altered = copy.deepcopy(vex)
            altered['statements'][0][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                fixed_components(altered, now)
        with self.assertRaisesRegex(ValueError, 'expired'):
            fixed_components(vex, now + timedelta(days=61))
        with self.assertRaisesRegex(ValueError, 'not yet valid'):
            fixed_components(vex, now - timedelta(days=1))


if __name__ == '__main__':
    unittest.main()

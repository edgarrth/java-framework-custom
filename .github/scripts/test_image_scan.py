import copy
from datetime import datetime, timedelta, timezone
import unittest

from check_image_scan import validate


class ImageScanTest(unittest.TestCase):
    def setUp(self):
        self.now = datetime.now(timezone.utc)
        self.report = {
            'SchemaVersion': 2, 'ArtifactType': 'container_image', 'ArtifactName': 'example:1',
            'CreatedAt': self.now.isoformat(), 'Metadata': {'ImageID': 'sha256:' + 'a' * 64},
            'Results': [{'Packages': [{'Name': 'library', 'Version': '1.0'}]}],
        }

    def test_complete_clean_inventory_is_accepted(self):
        self.assertEqual(1, validate(self.report, 'example:1', self.now))

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


if __name__ == '__main__':
    unittest.main()

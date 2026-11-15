import copy
import fnmatch
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from build_destination import BuildDestination
import sign_product

ROOT = Path(__file__).resolve().parents[2]
CONFIGURATION = ROOT / 'gitops/fixtures/build-configuration.json'

class BuildDestinationTest(unittest.TestCase):
    def setUp(self):
        self.value = json.loads(CONFIGURATION.read_text())
        self.destination = BuildDestination.parse(json.dumps(self.value))
        self.repository = self.value['repository']

    def test_host_and_namespace_produce_exact_ghcr_and_private_coordinates(self):
        for registry, namespace in [('ghcr.io', 'edgarrth'), ('registry.bank.test:5443', 'finance/backend')]:
            value = dict(self.value, registry=registry, namespace=namespace)
            destination = BuildDestination.parse(json.dumps(value))
            base = registry + '/' + namespace
            self.assertEqual(base + '/builds/' + self.repository + '/payment-orders', destination.image(self.repository, 'payment-orders'))
            self.assertEqual('oci://' + base + '/build-charts/' + self.repository + '/123/2/native/payment-orders', destination.chart(self.repository, 'payment-orders', 123, 2, 'native'))
            self.assertNotIn('/', destination.registry)

    def test_missing_unconfigured_or_ambiguous_destinations_fail(self):
        mutations = [{'registry': ''}, {'registry': 'https://registry.test'}, {'registry': 'ghcr.io/edgarrth'},
                     {'registry': 'registry.test:65536'}, {'registry': 'registry.test:0'},
                     {'registry': 'registry.example.invalid'}, {'namespace': ''}, {'namespace': '../escape'},
                     {'namespace': 'owner/*'}, {'repository': 'owner/*'}, {'gitops_repository': ''},
                     {'gitops_path': 'custom/environments'}, {'services': []},
                     {'services': ['payment-orders', 'payment-orders']}, {'services': ['*']}, {'old_selector': 'host'}]
        for mutation in mutations:
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                BuildDestination.parse(json.dumps(self.value | mutation))
        with self.assertRaises(ValueError):
            BuildDestination.parse((ROOT / 'gitops/build-configuration.example.json').read_text())
        for field in self.value:
            value = dict(self.value)
            del value[field]
            with self.subTest(missing=field), self.assertRaises(ValueError):
                BuildDestination.parse(json.dumps(value))

    def test_rendered_admission_signature_and_chart_sources_cover_the_same_products(self):
        with tempfile.TemporaryDirectory() as directory:
            documents = self.destination.render(ROOT / 'gitops', Path(directory))
        policy = documents['policies/verify-images']['spec']['rules']
        signed = policy[0]['verifyImages'][0]['imageReferences']
        verification = policy[0]['verifyImages'][0]
        self.assertTrue(verification['required'])
        self.assertTrue(verification['verifyDigest'])
        self.assertFalse(verification['mutateDigest'])
        expected_identity = 'https://github.com/edgarrth/java-framework-custom/.github/workflows/service-build.yaml@refs/tags/axiz-workflows-v' + (ROOT / 'version.txt').read_text().strip()
        self.assertEqual(expected_identity, verification['attestors'][0]['entries'][0]['keyless']['subject'])
        self.assertEqual('https://token.actions.githubusercontent.com', verification['attestors'][0]['entries'][0]['keyless']['issuer'])
        self.assertEqual(expected_identity, verification['attestations'][0]['conditions'][0]['all'][0]['value'])
        sources = documents['argocd/project']['spec']['sourceRepos']
        foreach = policy[1]['validate']['foreach']
        self.assertEqual(2, len(foreach), 'Pod and controller rules cover regular and init containers')
        ephemeral = documents['policies/verify-ephemeral-images']['spec']['rules']
        self.assertEqual(['Pod/ephemeralcontainers'], ephemeral[0]['match']['any'][0]['resources']['kinds'])
        self.assertEqual(policy[0]['verifyImages'], ephemeral[0]['verifyImages'])
        self.assertEqual('request.object.spec.ephemeralContainers || `[]`', ephemeral[1]['validate']['foreach'][0]['list'])
        for service in self.value['services']:
            image = self.destination.image(self.repository, service) + '@sha256:' + 'a' * 64
            self.assertTrue(any(fnmatch.fnmatchcase(image, allowed) for allowed in signed))
            for loop in foreach:
                self.assertTrue(any(fnmatch.fnmatchcase(image, p['image']) for p in loop['anyPattern']))
            for variant in ('jvm', 'native'):
                chart = self.destination.chart(self.repository, service, 27, 2, variant)
                self.assertTrue(any(fnmatch.fnmatchcase(chart, allowed) for allowed in sources))
            for wrong in (image.replace(self.value['registry'], 'other.test'), image.replace('/payments/', '/foreign/'),
                          image.replace(service, 'unauthorized'), image.split('@')[0] + ':latest'):
                self.assertFalse(any(fnmatch.fnmatchcase(wrong, p) for p in signed), wrong)
                self.assertFalse(any(fnmatch.fnmatchcase(wrong, p['image']) for p in foreach[0]['anyPattern']), wrong)
        self.assertNotIn('https://github.com/axiz/*', sources)
        self.assertEqual('https://github.com/' + self.value['gitops_repository'], documents['argocd/applicationset']['spec']['generators'][0]['git']['repoURL'])

    def test_producer_and_signer_reject_out_of_scope_destinations_before_access(self):
        for repository, service in [('other/project', 'payment-orders'), (self.repository, 'foreign-service')]:
            with self.assertRaises(ValueError):
                self.destination.image(repository, service)
        for run, attempt, variant in [('0', '1', 'jvm'), ('1', '../2', 'jvm'), ('1', '1', 'other')]:
            with self.assertRaises(ValueError):
                self.destination.chart(self.repository, 'payment-orders', run, attempt, variant)
        data = {'source': {'repository': self.repository, 'sha': 'a' * 40}, 'version': '2.0.0',
                'build': {'run_id': 10, 'run_attempt': 1}, 'parameters': {'variant': 'jvm'},
                'artifacts': {'image': {'repository': self.destination.image(self.repository, 'payment-orders'), 'tag': 'a' * 40 + '-10-1-jvm'},
                              'chart': {'oci': self.destination.chart(self.repository, 'payment-orders', 10, 1, 'jvm'), 'name': 'payment-orders', 'version': '2.0.0'}}}
        for artifact, field, wrong in [('image', 'repository', 'registry.evil/foreign'), ('chart', 'oci', 'oci://registry.evil/foreign')]:
            mutated = copy.deepcopy(data)
            mutated['artifacts'][artifact][field] = wrong
            with patch.dict(os.environ, {'BUILD_DESTINATION': json.dumps(self.value)}), patch.object(sign_product.subprocess, 'run') as run:
                with self.assertRaises(ValueError):
                    sign_product.service_signatures(mutated, {'service': 'payment-orders'}, Path('.'))
                run.assert_not_called()

    def test_deploy_and_applicationset_share_exact_repository_branch_and_path(self):
        value = dict(self.value, gitops_repository='other-bank/platform-state')
        destination = BuildDestination.parse(json.dumps(value))
        deployed = destination.deployment(self.repository, 'payment-orders')
        self.assertEqual('other-bank', deployed['owner'])
        self.assertEqual('platform-state', deployed['name'])
        with tempfile.TemporaryDirectory() as directory:
            documents = destination.render(ROOT / 'gitops', Path(directory))
        generator = documents['argocd/applicationset']['spec']['generators'][0]['git']
        self.assertEqual('https://github.com/' + deployed['repository'], generator['repoURL'])
        self.assertEqual(deployed['branch'], generator['revision'])
        self.assertEqual(deployed['state-root'] + '/*/*.yaml', generator['files'][0]['path'])
        self.assertNotEqual('https://github.com/foreign/state', generator['repoURL'])
        self.assertNotEqual('release', generator['revision'])
        with self.assertRaises(ValueError):
            destination.deployment('foreign/source', 'payment-orders')
        with self.assertRaises(ValueError):
            destination.deployment(self.repository, 'foreign-service')

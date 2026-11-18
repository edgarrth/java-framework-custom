import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

import yaml

from sync_container_recipes import ROOT, SOURCE, sync


class ContainerRecipesTest(unittest.TestCase):
    def test_development_services_are_exposed_only_on_loopback(self):
        for compose in [ROOT / 'axiz-demo/local/compose.yaml',
                        *sorted((ROOT / 'axiz-demo/services').glob('*/compose.yaml'))]:
            for name, service in yaml.safe_load(compose.read_text())['services'].items():
                for port in service.get('ports', []):
                    self.assertTrue(str(port).startswith('127.0.0.1:'), f'{compose}: {name}: {port}')
        template = ROOT / 'axiz-framework/codegen/axiz-codegen-templates/src/main/resources/axiz/templates/service/local/compose.yaml.mustache'
        for line in template.read_text().splitlines():
            if line.strip().startswith('ports:'):
                for port in yaml.safe_load(line)['ports']:
                    self.assertTrue(port.startswith('127.0.0.1:'), f'Generated service: {port}')

    def test_demo_and_generated_recipes_match_the_canonical_test_artifact(self):
        sync(check=True)

    def test_each_demo_compose_builds_the_declared_patched_image(self):
        for compose in [ROOT / 'axiz-demo/local/compose.yaml',
                        *sorted((ROOT / 'axiz-demo/services').glob('*/compose.yaml'))]:
            services = yaml.safe_load(compose.read_text())['services']
            for service in services.values():
                image = service.get('image', '')
                if not image.startswith('axiz-local/'):
                    continue
                name = image.split('/')[1].split(':')[0]
                recipe = (compose.parent / service['build']['context'] / 'Dockerfile').resolve()
                self.assertEqual(recipe.read_bytes(), (SOURCE / name / 'Dockerfile').read_bytes(), str(compose))

    def test_image_gate_covers_all_demo_images_and_blocks_medium_even_without_a_fix(self):
        workflow = yaml.safe_load((ROOT / '.github/workflows/security.yaml').read_text())
        job = workflow['jobs']['infrastructure-images']
        images = {row['image'] for row in job['strategy']['matrix']['include']}
        for pom, properties in (
            ('axiz-framework/platform/axiz-parent-service/pom.xml', ('axiz.image.base', 'axiz.image.native-base')),
            ('axiz-tools/axiz-initializr/pom.xml', ('axiz.initializr.base-image',)),
        ):
            tree = ET.parse(ROOT / pom)
            for prop in properties:
                value = tree.findtext('.//{http://maven.apache.org/POM/4.0.0}properties/{http://maven.apache.org/POM/4.0.0}' + prop)
                self.assertIn(value, images, f'Runtime base outside the security gate: {pom}: {prop}')
        for compose in [ROOT / 'axiz-demo/local/compose.yaml',
                        *sorted((ROOT / 'axiz-demo/services').glob('*/compose.yaml'))]:
            for service in yaml.safe_load(compose.read_text())['services'].values():
                self.assertIn(service['image'], images, str(compose))
        scanner = next(step['with'] for step in job['steps']
                       if step.get('uses', '').startswith('aquasecurity/trivy-action@'))
        self.assertEqual({'MEDIUM', 'HIGH', 'CRITICAL'}, set(scanner['severity'].split(',')))
        self.assertEqual('1', scanner['exit-code'])
        self.assertEqual('false', scanner['ignore-unfixed'])
        self.assertEqual('json', scanner['format'])
        inventory = next(step for step in job['steps']
                         if 'check_image_scan.py' in step.get('run', ''))
        self.assertEqual('always()', inventory['if'])
        self.assertIn('--maven-lock', inventory['run'])
        self.assertIn('libraries.lock.json', inventory['run'])
        self.assertIn('infrastructure-images', workflow['jobs']['security-pr-check']['needs'])
        self.assertFalse(job['strategy']['fail-fast'])


if __name__ == '__main__':
    unittest.main()

import copy
import json
import unittest
from unittest.mock import patch
from pathlib import Path
import tempfile
from defusedxml.common import EntitiesForbidden

from update_wiremock_image import LOCK, render, settings


class WireMockImageTest(unittest.TestCase):
    def setUp(self):
        self.lock = json.loads(LOCK.read_text())

    def test_platform_xml_entities_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            pom = root / 'axiz-framework/platform/axiz-dependencies/pom.xml'
            pom.parent.mkdir(parents=True)
            pom.write_text('<!DOCTYPE project [<!ENTITY x "synthetic">]>'
                           '<project xmlns="http://maven.apache.org/POM/4.0.0">'
                           '<properties><wiremock.version>&x;</wiremock.version></properties></project>')
            with patch('update_wiremock_image.ROOT', root), self.assertRaises(EntitiesForbidden):
                settings()

    def test_every_runtime_jar_is_downloaded_and_verified_individually(self):
        recipe = render(self.lock)
        for item in self.lock['artifacts']:
            filename = f'{item["name"]}-{item["version"]}.jar'
            self.assertIn(f'/var/wiremock/lib/{filename}', recipe)
            self.assertIn(f'{item["sha256"]}  /var/wiremock/lib/{filename}', recipe)
        runtime = recipe.split('FROM patched-runtime\n', 1)[1]
        self.assertNotIn('wiremock-standalone.jar', runtime)
        self.assertIn('COPY --from=launcher /launcher /var/wiremock/launcher', runtime)
        self.assertIn('USER 1000:1000', runtime)

    def test_old_template_engine_or_mixed_jetty_cannot_silently_return(self):
        for group, version in [('com.github.jknack', '4.3.1'), ('org.eclipse.jetty', '12.0.30')]:
            changed = copy.deepcopy(self.lock)
            item = next(a for a in changed['artifacts'] if a['group'] == group)
            item['version'] = version
            with self.subTest(group=group), self.assertRaises(ValueError):
                render(changed)

    def test_shaded_standalone_or_missing_unshaded_root_is_rejected(self):
        for replacement in ('wiremock-standalone', 'unrelated'):
            changed = copy.deepcopy(self.lock)
            next(a for a in changed['artifacts'] if a['name'] == 'wiremock')['name'] = replacement
            with self.subTest(replacement=replacement), self.assertRaises(ValueError):
                render(changed)

    def test_injected_commands_bad_hashes_and_colliding_filenames_are_rejected(self):
        for field, value in [('version', '../escape'), ('version', '..'), ('group', 'org..escape'),
                             ('name', 'lib;touch-marker'),
                             ('sha256', 'unverified'), ('version', '1.0-SNAPSHOT')]:
            changed = copy.deepcopy(self.lock)
            changed['artifacts'][0][field] = value
            with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                render(changed)
        changed = copy.deepcopy(self.lock)
        changed['artifacts'].append(changed['artifacts'][0].copy())
        with self.assertRaises(ValueError):
            render(changed)


if __name__ == '__main__':
    unittest.main()

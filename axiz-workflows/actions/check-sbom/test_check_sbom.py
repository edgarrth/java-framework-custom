import json
from pathlib import Path
import tempfile
import unittest
from check_sbom import validate


class InventoryTest(unittest.TestCase):
    def test_missing_empty_and_incomplete_inventories_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            pom = root / 'pom.xml'
            pom.write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"><modules>'
                           '<module>cli</module><module>initializr</module><module>recipes</module>'
                           '</modules></project>')
            for name in ['cli', 'initializr', 'recipes']:
                (root / name).mkdir()
                (root / name / 'pom.xml').write_text(
                    '<project xmlns="http://maven.apache.org/POM/4.0.0">'
                    f'<artifactId>{name}</artifactId></project>')
            bom = root / 'bom.json'
            with self.assertRaises(OSError):
                validate(bom, pom)
            for components in [[], [{'name': 'cli'}]]:
                bom.write_text(json.dumps({'bomFormat': 'CycloneDX', 'components': components}))
                with self.assertRaises(ValueError):
                    validate(bom, pom)
            bom.write_text(json.dumps({'bomFormat': 'CycloneDX', 'components': [
                {'name': name} for name in ['cli', 'initializr', 'recipes']]}))
            self.assertEqual((3, 3), validate(bom, pom))


if __name__ == '__main__':
    unittest.main()

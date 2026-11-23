"""Regresión de las excepciones tecnológicas cerradas tras extraer puertos neutrales respecto del proveedor."""
import importlib.util
import tempfile
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location("layers", Path(__file__).with_name("check-layer-structure.py"))
layers = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(layers)


class LayerTest(unittest.TestCase):
    def test_neutral_contract_cannot_acquire_a_database_or_another_provider(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            module = root / "kits" / "axiz-kit-audit-reactor"
            module.mkdir(parents=True)
            prefix = '<project xmlns="http://maven.apache.org/POM/4.0.0">'
            (root / "pom.xml").write_text(prefix + '<artifactId>root</artifactId><packaging>pom</packaging>'
                '<modules><module>kits/axiz-kit-audit-reactor</module></modules></project>')
            pom = prefix + '<artifactId>axiz-kit-audit-reactor</artifactId>' \
                '<parent><artifactId>axiz-parent-technology</artifactId></parent>' \
                '<properties><axiz.module.name>pe.axiz.kit.reactor.audit</axiz.module.name>' \
                '<axiz.technology.reactive-native>true</axiz.technology.reactive-native></properties>'
            (module / "pom.xml").write_text(pom + '<dependencies><dependency><groupId>pe.axiz</groupId>'
                '<artifactId>axiz-kit-r2dbc</artifactId></dependency></dependencies></project>')
            errors, _ = layers.validate(root)
            self.assertTrue(any("provider/extra dependency" in error for error in errors))
            (module / "pom.xml").write_text(pom + '<dependencies><dependency><groupId>pe.axiz</groupId>'
                '<artifactId>axiz-core-audit</artifactId></dependency></dependencies></project>')
            errors, _ = layers.validate(root)
            self.assertFalse(any("provider/extra dependency" in error for error in errors))
            self.assertTrue(any("must be exactly" in error for error in errors))


if __name__ == "__main__":
    unittest.main()

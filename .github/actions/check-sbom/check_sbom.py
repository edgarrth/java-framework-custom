"""Falla de forma cerrada antes del escáner: el éxito de Maven no es evidencia de que se haya producido un SBOM."""
import json
from pathlib import Path
import sys
from defusedxml import ElementTree as ET

NS = {'m': 'http://maven.apache.org/POM/4.0.0'}


def validate(path, pom):
    document = json.loads(Path(path).read_text())
    if document.get('bomFormat') != 'CycloneDX' or not document.get('components'):
        raise ValueError('A nonempty CycloneDX components inventory is required')
    names = {c.get('name') for c in document['components']}
        # POM o reporte de build de confianza, tras ejecutar el mismo checkout; no se analiza XML de la petición.
    # nosemgrep: python.lang.security.use-defused-xml-parse.use-defused-xml-parse
    root = ET.parse(pom).getroot()
    expected = set()
    for module in root.findall('m:modules/m:module', NS):
                # POM o reporte de build de confianza, tras ejecutar el mismo checkout; no se analiza XML de la petición.
        # nosemgrep: python.lang.security.use-defused-xml-parse.use-defused-xml-parse
        child = ET.parse(Path(pom).parent / module.text / 'pom.xml').getroot()
        if child.findtext('m:packaging', 'jar', NS) == 'jar':
            expected.add(child.findtext('m:artifactId', namespaces=NS))
    missing = expected - names
    if missing:
        raise ValueError('Reactor artifacts missing from SBOM: ' + ', '.join(sorted(missing)))
    return len(document['components']), len(expected)


if __name__ == '__main__':
    try:
        components, modules = validate(sys.argv[1], sys.argv[2])
        print(f'SBOM verified: {components} components, {modules} direct jar modules')
    except (OSError, ValueError, KeyError, TypeError, ET.ParseError) as error:
        print(f'::error::Invalid SBOM: {error}', file=sys.stderr)
        sys.exit(1)

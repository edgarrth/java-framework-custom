#!/usr/bin/env python3
"""Bloquea el grafo de dependencias de Maven resuelto de WireMock y renderiza una receta de contenedor inspeccionable."""
import argparse
import hashlib
import json
from pathlib import Path
import re
from defusedxml import ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
DESTINATION = ROOT / 'axiz-framework/quality/axiz-test-containers/src/main/resources/pe/axiz/quality/containers/images/wiremock'
LOCK = DESTINATION / 'libraries.lock.json'
COORDINATE = re.compile(r'[A-Za-z0-9][A-Za-z0-9_+-]*(?:\.[A-Za-z0-9_+-]+)*')


def settings():
    tree = ET.parse(ROOT / 'axiz-framework/platform/axiz-dependencies/pom.xml')
    properties = tree.find('{http://maven.apache.org/POM/4.0.0}properties')
    return {child.tag.split('}')[1]: child.text for child in properties}


def lock_from_bom(bom, repository):
    components = {item['bom-ref']: item for item in bom['components']}
    graph = {item['ref']: item['dependsOn'] for item in bom['dependencies']}
    roots = [ref for ref, item in components.items()
             if item.get('group') == 'org.wiremock' and item['name'] == 'wiremock-jetty12']
    if len(roots) != 1:
        raise ValueError('Exactly one resolved WireMock Jetty 12 root is required')
    pending = list(roots)
    visited = set()
    artifacts = []
    while pending:
        ref = pending.pop()
        if ref in visited:
            continue
        visited.add(ref)
        item = components[ref]
        group, name, version = item['group'], item['name'], item['version']
        if not all(COORDINATE.fullmatch(value) for value in (group, name, version)):
            raise ValueError('Unsafe Maven coordinate')
        relative = f'{group.replace(".", "/")}/{name}/{version}/{name}-{version}.jar'
        artifact = (repository / relative).resolve()
        if not artifact.is_relative_to(repository.resolve()) or not artifact.is_file():
            raise ValueError('Resolved Maven JAR is absent or outside the repository: ' + relative)
        artifacts.append({'group': group, 'name': name, 'version': version,
                          'sha256': hashlib.sha256(artifact.read_bytes()).hexdigest()})
        pending.extend(graph[ref])
    return {'schema': 1, 'root': roots[0], 'artifacts': sorted(artifacts, key=lambda x: (x['group'], x['name']))}


def render(lock):
    if lock.get('schema') != 1 or not lock.get('artifacts'):
        raise ValueError('A complete WireMock dependency lock is required')
    props = settings()
    files = set()
    downloads = []
    wiremock = set()
    for item in lock['artifacts']:
        group, name, version, digest = (item[key] for key in ('group', 'name', 'version', 'sha256'))
        if (not all(COORDINATE.fullmatch(value) for value in (group, name, version))
                or not re.fullmatch(r'[0-9a-f]{64}', digest) or 'SNAPSHOT' in version):
            raise ValueError('Invalid locked Maven artifact')
        filename = f'{name}-{version}.jar'
        if filename in files:
            raise ValueError('Ambiguous runtime JAR filename')
        files.add(filename)
        if group == 'org.wiremock':
            wiremock.add(name)
            if version != props['wiremock.version']:
                raise ValueError('WireMock image and platform versions differ')
        if group.startswith('org.eclipse.jetty') and version != props['jetty.version']:
            raise ValueError('Jetty runtime and EE modules must match the platform')
        if group == 'com.github.jknack' and name == 'handlebars' and version != props['handlebars.version']:
            raise ValueError('The image must use the corrected platform template engine')
        if group == 'com.github.jknack' and name == 'handlebars-helpers' and version != props['handlebars-helpers.version']:
            raise ValueError('The template helpers must match the compatible platform API')
        if group == 'org.ow2.asm' and version != props['asm.version']:
            raise ValueError('The image bytecode libraries must match the platform')
        relative = f'{group.replace(".", "/")}/{name}/{version}/{filename}'
        target = f'/var/wiremock/lib/{filename}'
        downloads.append(f"    fetch {target} {relative}; \\\n"
                         f"    printf '%s\\n' '{digest}  {target}' | sha256sum -c -;")
    if wiremock != {'wiremock', 'wiremock-jetty12'}:
        raise ValueError('Both unshaded WireMock artifacts are required; standalone is prohibited')
    prologue = '''# Generado por .github/scripts/update_wiremock_image.py desde libraries.lock.json.
# Solo se extrae el launcher Run.class upstream; no se distribuye ningún JAR de dependencias sombreado.
FROM wiremock/wiremock:3.13.2@sha256:0d4ecb3e4dc8213fd7a4d37d6a78f6e6b553a6d2e15bd51b0999781282ac61b3 AS distribution
FROM eclipse-temurin:25-jre-alpine@sha256:e1898fd6a11fc965a994d8ace186fd3585aeb636cf8f05f47a723fcfee0b0918 AS patched-runtime
RUN apk upgrade --no-cache && apk add --no-cache zlib
FROM patched-runtime AS launcher
COPY --from=distribution /var/wiremock/lib/wiremock-standalone.jar /tmp/launcher-source.jar
RUN printf '%s\\n' 'd097b19bd483c5038479b13a5c71e9faf8f2f5106584f0c120a7770ab0bdb367  /tmp/launcher-source.jar' | sha256sum -c - \\
    && mkdir -p /launcher/wiremock \\
    && unzip -p /tmp/launcher-source.jar wiremock/Run.class > /launcher/wiremock/Run.class
FROM patched-runtime
RUN addgroup -g 1000 wiremock && adduser -D -u 1000 -G wiremock wiremock \\
    && mkdir -p /var/wiremock/lib /var/wiremock/extensions /home/wiremock/mappings /home/wiremock/__files \\
    && chown -R wiremock:wiremock /home/wiremock
COPY --from=launcher /launcher /var/wiremock/launcher
# Las descargas fijadas por checksum se intentan en Maven Central y en su espejo oficial de Google
# (Central limita por IP con 429); el checksum se verifica igual sea cual sea el origen.
RUN set -eu; \\
    fetch() { for attempt in 1 2 3 4 5 6; do for repository in https://repo.maven.apache.org/maven2 https://maven-central.storage-download.googleapis.com/maven2; do wget -q -O "$1" "$repository/$2" && return 0; done; rm -f "$1"; [ "$attempt" -lt 6 ] || return 1; sleep $((attempt * 10)); done; }; \\
'''
    # La versión del launcher upstream se fija por separado y cambia junto con la raíz de la biblioteca.
    if props['wiremock.version'] != '3.13.2':
        raise ValueError('Update and verify the upstream launcher pin before changing WireMock')
    return prologue + ' \\\n'.join(downloads) + '''
USER 1000:1000
WORKDIR /home/wiremock
EXPOSE 8080
ENTRYPOINT ["java", "-cp", "/var/wiremock/launcher:/var/wiremock/lib/*:/var/wiremock/extensions/*", "wiremock.Run"]
'''


def check():
    expected = render(json.loads(LOCK.read_text()))
    if (DESTINATION / 'Dockerfile').read_text() != expected:
        raise ValueError('WireMock recipe does not match its resolved dependency lock')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sbom', type=Path)
    parser.add_argument('--repository', type=Path)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    if args.check:
        check()
        return
    if not args.sbom or not args.repository:
        parser.error('--sbom and --repository are required to update the image')
    lock = lock_from_bom(json.loads(args.sbom.read_text()), args.repository)
    recipe = render(lock)
    LOCK.write_text(json.dumps(lock, indent=2) + '\n')
    (DESTINATION / 'Dockerfile').write_text(recipe)
    print(f'Locked {len(lock["artifacts"])} individually inspectable Maven JARs')


if __name__ == '__main__':
    main()

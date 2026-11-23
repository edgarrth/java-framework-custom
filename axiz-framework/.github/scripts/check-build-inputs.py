#!/usr/bin/env python3
"""Verifica las entradas del build en el índice de Git, no solo en el árbol de trabajo de quien desarrolla.

Se ejecuta desde un checkout: check-build-inputs.py [repository root ...]. Funciona con repositorios
de Axiz independientes y también con el checkout que los agrupa. No añade archivos al stage ni modifica la configuración de Git.
"""
from pathlib import Path
import subprocess
import sys
from defusedxml import ElementTree as ET

NS = {'m': 'http://maven.apache.org/POM/4.0.0'}
CONFIG = ('.mvn/jvm.config', '.mvn/maven.config', '.mvn/wrapper/maven-wrapper.properties')


def check(directory):
    root = Path(directory).resolve()
    output = subprocess.check_output(['git', '-C', str(root), 'ls-files', '--stage', '-z'], text=True)
    tracked = {}
    for entry in output.split('\0'):
        if entry:
            metadata, path = entry.split('\t', 1)
            tracked[path] = metadata.split()[0]
    errors = []

    def require(path):
        relative = path.relative_to(root).as_posix()
        if relative not in tracked or not path.is_file():
            errors.append(f'{relative}: required build input is missing from Git or disk')

    for name in tracked:
        path = Path(name)
        if path.name == 'mvnw' and 'templates' not in path.parts:
            if tracked[name] != '100755':
                errors.append(f'{name}: wrapper must be executable in Git (100755)')
            for config in CONFIG:
                require(root / path.parent / config)
        elif name.endswith('.sh') and 'templates' not in path.parts:
            if tracked[name] != '100755':
                errors.append(f'{name}: shell entry point must be executable in Git (100755)')

    visited = set()

    def modules(pom):
        if pom in visited:
            return
        visited.add(pom)
        require(pom)
        if not pom.is_file():
            return
        # Configuración de Maven de confianza incluida en el repositorio, nunca XML de la petición.
        # nosemgrep: python.lang.security.use-defused-xml-parse.use-defused-xml-parse
        project = ET.parse(pom).getroot()
        for module in project.findall('m:modules/m:module', NS):
            child = (pom.parent / module.text.strip() / 'pom.xml').resolve()
            if not child.is_relative_to(root):
                errors.append(f'{pom}: module escapes repository root')
            else:
                modules(child)

    modules(root / 'pom.xml')
    return errors


if __name__ == '__main__':
    errors = [f'{root}: {error}' for root in sys.argv[1:] or ['.'] for error in check(root)]
    for error in errors:
        print(f'::error::{error}')
    print(f'Build inputs: {len(errors)} problems')
    sys.exit(bool(errors))

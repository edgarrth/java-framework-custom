#!/usr/bin/env python3
"""Mantiene los workflows del framework en una única release v2 revisada y los samples nativos en rutas de servicio reales."""
import argparse
import json
from pathlib import Path
import re
import sys

import yaml


def nodes(value):
    if isinstance(value, dict):
        yield value
        for item in value.values():
            yield from nodes(item)
    elif isinstance(value, list):
        for item in value:
            yield from nodes(item)


def check(root, samples=None, workflows_directory=None):
    root = Path(root).resolve()
    errors = []
    workflows = Path(workflows_directory).resolve() if workflows_directory is not None else root / '.github/workflows'
    for path in sorted([*workflows.glob('*.yaml'), *workflows.glob('*.yml')]):
        document = yaml.safe_load(path.read_text())
        versions = set()
        for node in nodes(document):
            reference = node.get('uses', '')
            if isinstance(reference, str) and reference.startswith('edgarrth/java-framework-custom/'):
                version = reference.rpartition('@')[2]
                if not re.fullmatch(r'axiz-workflows-v2\.\d+\.\d+', version):
                    errors.append(f'{path.name}: an Axiz reference is outside an exact v2 release')
                versions.add(version)
        if versions:
            guard = document.get('jobs', {}).get('axiz-workflows-trust', {})
            guarded = {node['AXIZ_WORKFLOWS_VERSION'] for node in nodes(guard)
                       if 'AXIZ_WORKFLOWS_VERSION' in node}
            if len(versions) != 1 or guarded != versions:
                errors.append(f'{path.name}: workflow and release guard versions differ')
        if path.name in ('framework-native-nightly.yaml', 'framework-samples-matrix.yaml'):
            for node in nodes(document):
                expected = {
                    'PACKAGES_USERNAME': '${{ secrets.PACKAGES_READ_USERNAME || github.actor }}',
                    'PACKAGES_TOKEN': '${{ secrets.PACKAGES_READ_TOKEN || github.token }}',
                }
                for key, value in expected.items():
                    if key in node and node[key] != value:
                        errors.append(f'{path.name}: {key} must use the read-only credential')
        for node in nodes(document):
            run = node.get('run', '')
            if isinstance(run, str) and 'for dir in target/samples/' in run:
                commands = [line for line in run.splitlines() if './mvnw' in line]
                if not commands or any('spotless:check' not in line or 'verify' not in line or '-Pquality' not in line
                                       or 'spotless:apply' in line for line in commands):
                    errors.append(f'{path.name}: generated samples must use -Pquality, check formatting and verify without apply')
    for name, goal in [('authorization-samples.sh', 'test'), ('reactive-native-sample.sh', 'verify')]:
        script = root / '.github/scripts' / name
        if script.is_file():
            content = script.read_text()
            if 'spotless:apply' in content or not re.search(r'\./mvnw[^\n]*spotless:check\s+' + goal, content):
                errors.append(f'{name}: acceptance must check formatting without apply')
    if samples is not None:
        # Se lee la salida real del generador; nunca se infieren sus nombres con un segundo algoritmo de nombrado.
        generated = json.loads(Path(samples).read_text())
        if not isinstance(generated, list) or not generated or any(not isinstance(p, str) for p in generated):
            raise ValueError('modules.json must contain a nonempty list of generated project paths')
        if len(set(generated)) != len(generated):
            raise ValueError('modules.json contains duplicate projects')
        services = set()
        for relative in generated:
            directory = Path(relative)
            if not directory.is_absolute():
                directory = root / directory
            directory = directory.resolve()
            directory.relative_to(root)  # Los samples generados deben pertenecer a este checkout.
            manifest = yaml.safe_load((directory / '.axiz/project.yaml').read_text())
            if manifest['archetype'] not in ('library', 'starter'):
                services.add(directory)
        nightly = yaml.safe_load((workflows / 'framework-native-nightly.yaml').read_text())
        selected = json.loads(nightly['jobs']['native']['with']['modules'])
        if not isinstance(selected, list) or not selected or any(not isinstance(p, str) for p in selected):
            raise ValueError('Native modules must be a nonempty JSON list of paths')
        if len(set(selected)) != len(selected):
            errors.append('Native modules contain duplicate projects')
        actual = {(root / relative).resolve() for relative in selected}
        if actual != services:
            errors.append('Native modules differ from the actual generated service projects: '
                          f'missing={sorted(str(p.relative_to(root)) for p in services - actual)}, '
                          f'extra={sorted(str(p) for p in actual - services)}')
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path('.'))
    parser.add_argument('--samples', type=Path)
    parser.add_argument('--workflows', type=Path)
    args = parser.parse_args()
    try:
        errors = check(args.root, args.samples, args.workflows)
    except (OSError, ValueError, KeyError, TypeError, yaml.YAMLError) as error:
        errors = [str(error)]
    for error in errors:
        print('::error::' + error)
    if not errors:
        print('Framework workflow versions and generated native sample selection are consistent')
    return int(bool(errors))


if __name__ == '__main__':
    sys.exit(main())

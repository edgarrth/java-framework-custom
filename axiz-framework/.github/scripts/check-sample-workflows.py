#!/usr/bin/env python3
"""Keep framework workflows on one reviewed v2 release and native samples on real service paths."""
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


def check(root, samples=None):
    root = Path(root).resolve()
    errors = []
    workflows = root / '.github/workflows'
    for path in sorted([*workflows.glob('*.yaml'), *workflows.glob('*.yml')]):
        document = yaml.safe_load(path.read_text())
        versions = set()
        for node in nodes(document):
            reference = node.get('uses', '')
            if isinstance(reference, str) and reference.startswith('axiz/axiz-workflows/'):
                version = reference.rpartition('@')[2]
                if not re.fullmatch(r'v2\.\d+\.\d+', version):
                    errors.append(f'{path.name}: an Axiz reference is outside an exact v2 release')
                versions.add(version)
        if versions:
            guard = document.get('jobs', {}).get('axiz-workflows-trust', {})
            guarded = {node['AXIZ_WORKFLOWS_VERSION'] for node in nodes(guard)
                       if 'AXIZ_WORKFLOWS_VERSION' in node}
            if len(versions) != 1 or guarded != versions:
                errors.append(f'{path.name}: workflow and release guard versions differ')
        if path.name in ('native-nightly.yaml', 'samples-matrix.yaml'):
            for node in nodes(document):
                for credential in ('USERNAME', 'PASSWORD'):
                    key = 'NEXUS_' + credential
                    expected = '${{ secrets.NEXUS_READ_' + credential + ' }}'
                    if key in node and node[key] != expected:
                        errors.append(f'{path.name}: {key} must use the read-only credential')
    if samples is not None:
        # Read the actual generator output, never infer its names from a second naming algorithm.
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
            directory.relative_to(root)  # Generated samples must belong to this checkout.
            manifest = yaml.safe_load((directory / '.axiz/project.yaml').read_text())
            if manifest['archetype'] not in ('library', 'starter'):
                services.add(directory)
        nightly = yaml.safe_load((workflows / 'native-nightly.yaml').read_text())
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
    args = parser.parse_args()
    try:
        errors = check(args.root, args.samples)
    except (OSError, ValueError, KeyError, TypeError, yaml.YAMLError) as error:
        errors = [str(error)]
    for error in errors:
        print('::error::' + error)
    if not errors:
        print('Framework workflow versions and generated native sample selection are consistent')
    return int(bool(errors))


if __name__ == '__main__':
    sys.exit(main())

#!/usr/bin/env python3
"""Construye las variantes publicadas de Renovate a partir de una base y de overlays de reglas declarativos."""
import argparse
from copy import deepcopy
import json
from pathlib import Path
import sys

sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'actions'))
from workflow_release import TAG_PATTERN

ROOT = Path(__file__).resolve().parents[2]


def build(root, *, write=False):
    base = json.loads((root / '.github/renovate/default.json').read_text())
    variants = json.loads((root / '.github/scripts/renovate-presets/variants.json').read_text())
    errors = []
    # El JSON publicado sigue siendo consumible directamente por Renovate; su gramática de versiones se genera
    # a partir del mismo contrato revisado que los validadores y los normalizadores de fixtures de Java y Python.
    derived=deepcopy(base)
    versioning='regex:^'+TAG_PATTERN.replace('v2\\.', 'v(?<major>2)\\.').replace('(?:','(?<minor>',1).replace('(?:','(?<patch>',1)+'$'
    for rule in derived['packageRules']:
        if rule.get('versioning','').startswith('regex:^axiz-workflows-'):rule['versioning']=versioning
    for manager in derived['customManagers']:
        if manager.get('versioningTemplate','').startswith('regex:^axiz-workflows-'):manager['versioningTemplate']=versioning
        for index,pattern in enumerate(manager['matchStrings']):
            for original in (r'axiz-workflows-v\d+\.\d+\.\d+',r'axiz-workflows-v2\.\d+\.\d+'):
                pattern=pattern.replace(original,TAG_PATTERN)
            manager['matchStrings'][index]=pattern
    if base!=derived:
        if write:(root/'.github/renovate/default.json').write_text(json.dumps(derived,indent=2)+'\n')
        else:errors.append('Base Renovate release grammar differs from the canonical v2 contract')
    base=derived
    for name, overlay in variants.items():
        if name not in {'platform', 'service'} or set(overlay) != {'description', 'packageRules'}:
            raise ValueError('Invalid published preset overlay')
        config = deepcopy(base)
        config['description'] = overlay['description']
        config['packageRules'].extend(overlay['packageRules'])
        text = json.dumps(config, indent=2) + '\n'
        destination = root / '.github/renovate' / (name + '.json')
        if write:
            destination.write_text(text)
        elif not destination.is_file() or destination.read_text() != text:
            errors.append(str(destination.relative_to(root)) + ': regenerate from the base and overlays')
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root', type=Path, nargs='?', default=ROOT)
    parser.add_argument('--write', action='store_true', help='Regenerate the published variants')
    args = parser.parse_args()
    errors = build(args.root, write=args.write)
    for error in errors:
        print(error)
    if not errors:
        print('Published Renovate presets match the common base and overlays')
    return int(bool(errors))


if __name__ == '__main__':
    raise SystemExit(main())

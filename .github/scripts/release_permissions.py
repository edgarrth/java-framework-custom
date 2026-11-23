#!/usr/bin/env python3
"""Verifica los destinos de release explícitos contra los tres roles cerrados de token de la GitHub App."""
import argparse
import json
from pathlib import Path
import sys

import yaml

ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS = '.github/workflows/'
TYPES = {'generic', 'json', 'yaml', 'toml', 'xml', 'pom'}


def release_path(component, filename):
    if not isinstance(component, str) or not component or component.startswith('/'):
        raise ValueError('Invalid component path')
    if not isinstance(filename, str) or not filename or '\\' in filename or '\x00' in filename:
        raise ValueError('Invalid release file path')
    prefix = '' if component == '.' or filename.startswith('/') else component.rstrip('/') + '/'
    result = (prefix + filename.lstrip('/')).rstrip('/')
    if not result or result.startswith('~') or any(part in {'', '.', '..'} for part in result.split('/')):
        raise ValueError('Release file path must be canonical and stay inside the repository')
    return result


def workflow_targets(config):
    """Reproduce la herencia nullish de extraFiles y addPath para destinos explícitos y admitidos.

    La regresión real del planificador rechaza además las escrituras de workflows desde cualquier otra fuente.
    La expansión de globs depende del remoto y queda fuera de este contrato de forma deliberada.
    """
    if not isinstance(config, dict) or not isinstance(config.get('packages'), dict) or not config['packages']:
        raise ValueError('Expected a nonempty Release Please packages configuration')
    result = {}
    for component, package in config['packages'].items():
        if not isinstance(package, dict):
            raise ValueError('Invalid package configuration')
        release_path(component, 'version.txt')
        entries = package.get('extra-files')
        if entries is None:
            entries = config.get('extra-files')
        if entries is None:
            entries = []
        if not isinstance(entries, list):
            raise ValueError('extra-files must be a list')
        targets = set()
        for entry in entries:
            if isinstance(entry, str):
                filename = entry
            elif isinstance(entry, dict):
                if entry.get('glob') not in (None, False) or ('glob' in entry and entry['glob'] is not None and type(entry['glob']) is not bool):
                    raise ValueError('glob expansion is forbidden in the static release contract')
                if entry.get('type') not in TYPES:
                    raise ValueError('Unsupported extra-file updater')
                filename = entry.get('path')
            else:
                raise ValueError('Invalid extra-file entry')
            target = release_path(component, filename)
            if target.startswith(WORKFLOWS):
                targets.add(target)
        result[component] = sorted(targets)
    return result


def verify(root):
    root = Path(root)
    targets = workflow_targets(json.loads((root/'release-please-config.json').read_text()))
    release = {'permission-contents': 'write', 'permission-pull-requests': 'write'}
    monorepo = dict(release)
    if any(targets.values()):
        monorepo['permission-workflows'] = 'write'
    expected = {
        ('release-self.yaml', 'release'): monorepo,
        ('release.yaml', 'release'): release,
        ('service-deploy.yaml', 'deploy'): {'permission-contents': 'write'},
    }
    observed = {}
    errors = []
    for file in sorted((root/'.github/workflows').glob('*')):
        if file.suffix not in {'.yaml', '.yml'} or not file.is_file():
            continue
        document = yaml.safe_load(file.read_text())
        for job_name, job in document.get('jobs', {}).items():
            for step in job.get('steps', []):
                if not step.get('uses', '').startswith('actions/create-github-app-token@'):
                    continue
                key = (file.name, job_name)
                if key in observed:
                    errors.append(file.name + ': duplicate token role')
                options = step.get('with', {})
                permissions = {name: value for name, value in options.items() if name.startswith('permission-')}
                observed[key] = permissions
                if key not in expected or permissions != expected[key]:
                    errors.append(file.name + ': token permissions differ from the required closed role')
                if key == ('service-deploy.yaml', 'deploy') and (
                        options.get('owner') != '${{ steps.destination.outputs.owner }}'
                        or options.get('repositories') != '${{ steps.destination.outputs.name }}'):
                    errors.append(file.name + ': GitOps token must be restricted to the approved destination')
    for key in expected.keys() - observed.keys():
        errors.append(key[0] + ': required token role is missing')
    return {'passed': not errors, 'workflowTargets': targets,
            'roles': [{'workflow': file, 'job': job, 'permissions': permissions}
                      for (file, job), permissions in sorted(observed.items())], 'errors': errors}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', nargs='?', choices=['verify', 'targets'], default='verify')
    parser.add_argument('--root', type=Path, default=ROOT)
    args = parser.parse_args()
    try:
        result = workflow_targets(json.load(sys.stdin)) if args.command == 'targets' else verify(args.root)
    except (ValueError, TypeError, AttributeError, OSError, yaml.YAMLError) as error:
        print(json.dumps({'passed': False, 'errors': [str(error)]}))
        return 1
    print(json.dumps(result, indent=2))
    return int(args.command == 'verify' and not result['passed'])


if __name__ == '__main__':
    raise SystemExit(main())

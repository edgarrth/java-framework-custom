"""Inventaría la propiedad de los pins y valida la adopción contra el historial de tags de workflow de confianza."""
import argparse
import json
from pathlib import Path
import re
import subprocess

import yaml

from workflow_sources import workflow_files
from internal_contracts import TAG

REPOSITORY = 'edgarrth/java-framework-custom'
VERSION = r'axiz-workflows-v[^\s\"\'<>),;]+'


def product_files(root):
    path = root / 'release-please-config.json'
    if not path.is_file():
        return set()
    package = json.loads(path.read_text()).get('packages', {}).get('.github', {})
    if package.get('component') != 'axiz-workflows':
        return set()
    result = set()
    for entry in package.get('extra-files', []):
        relative = entry if isinstance(entry, str) else entry['path']
        target = (root / '.github' / relative).resolve()
        if not target.is_relative_to(root.resolve()):
            raise ValueError('product pin file escapes the source root')
        result.add(target)
    return result


def checkout_refs(text):
    found, visited = [], set()
    def walk(node):
        if id(node) in visited:
            return
        visited.add(id(node))
        if isinstance(node, yaml.MappingNode):
            values = {key.value: value for key, value in node.value if isinstance(key, yaml.ScalarNode)}
            uses, options = values.get('uses'), values.get('with')
            if isinstance(uses, yaml.ScalarNode) and uses.value.startswith('actions/checkout@') and isinstance(options, yaml.MappingNode):
                fields = {key.value: value for key, value in options.value if isinstance(key, yaml.ScalarNode)}
                repository, ref = fields.get('repository'), fields.get('ref')
                internal = repository is None or isinstance(repository, yaml.ScalarNode) and repository.value in {REPOSITORY, '${{ github.repository }}'}
                if internal and isinstance(ref, yaml.ScalarNode) and ref.value.startswith('axiz-workflows-'):
                    found.append((ref.start_mark.line + 1, ref.value))
            for _, value in node.value:
                walk(value)
        elif isinstance(node, yaml.SequenceNode):
            for value in node.value:
                walk(value)
    walk(yaml.compose(text))
    return found


def inventory(root, paths=None):
    root = root.resolve()
    products = product_files(root)
    selected = set(workflow_files(root)) | products if paths is None else set(paths)
    if paths is None:
        selected.update(p for p in root.rglob('renovate.json')
                        if not any(part in {'target', '.git', 'node_modules', '.venv'} for part in p.parts))
    rows = []
    for path in sorted(selected):
        if not path.is_file() or not path.resolve().is_relative_to(root):
            continue
        text = path.read_text()
        owner = 'release-please' if path.resolve() in products else 'renovate'
        for number, line in enumerate(text.splitlines(), 1):
            if line.lstrip().startswith('#'):
                continue
            found = []
            if owner == 'release-please':
                found = [('product', match[0]) for match in re.finditer(r'axiz-workflows-v[0-9]+\.[0-9]+\.[0-9]+', line)]
            else:
                patterns = [('uses', r'uses:\s*[\"\']?' + re.escape(REPOSITORY) + r'/[^\s@]+@([^\s\"\']+)'),
                            ('guard', r'AXIZ_WORKFLOWS_VERSION:\s*[\"\']?(' + VERSION + ')'),
                            ('preset', r'github>' + re.escape(REPOSITORY) + r'[^\s\"\']*#(' + VERSION + ')')]
                for kind, pattern in patterns:
                    found.extend((kind, match[1]) for match in re.finditer(pattern, line))
            for kind, version in found:
                rows.append({'path': str(path.relative_to(root)), 'line': number, 'kind': kind,
                             'version': version, 'owner': owner, 'marker': '# x-release-please-version' in line})
        if owner != 'release-please' and path.name.endswith(('.yaml', '.yml')) and 'actions/checkout@' in text:
            for number, version in checkout_refs(text):
                rows.append({'path': str(path.relative_to(root)), 'line': number, 'kind': 'ref',
                             'version': version, 'owner': owner, 'marker': '# x-release-please-version' in text.splitlines()[number-1]})
    return rows


def trusted_tags(root):
    def git(*args):
        result = subprocess.run(['git', '-C', str(root), *args], capture_output=True, text=True)
        if result.returncode:
            raise ValueError('complete trusted Git history is required to validate consumer releases')
        return result.stdout.strip()
    if Path(git('rev-parse', '--show-toplevel')).resolve() != root.resolve() or git('rev-parse', '--is-shallow-repository') != 'false':
        raise ValueError('complete trusted Git history is required to validate consumer releases')
    tags = git('tag', '--list', 'axiz-workflows-v*').splitlines()
    options = subprocess.run(['git', '-C', str(root), 'config', '--get-regexp', r'remote\..*\.tagOpt'], capture_output=True, text=True)
    if not tags and '--no-tags' in options.stdout:
        raise ValueError('a checkout suppressing tags cannot establish first publication')
    return tags


def validate_pins(root, contracts_root, *, paths=None, require_history=False):
    rows = inventory(root, paths)
    products = product_files(root)
    current = 'axiz-workflows-v' + (root / '.github/version.txt').read_text().strip() if products else None
    consumers = [row for row in rows if row['owner'] == 'renovate']
    errors = []
    bootstrap = False
    for row in rows:
        label = f"{row['path']}:{row['line']}"
        if not TAG.fullmatch(row['version']):
            errors.append(label + ': workflow pins require an exact v2 release')
        if row['owner'] == 'release-please' and (row['version'] != current or not row['marker']):
            errors.append(label + ': product pins must follow the candidate and carry the Release Please marker')
        if row['owner'] == 'renovate' and row['marker']:
            errors.append(label + ': consumer pins are owned by Renovate and cannot carry a Release Please marker')
    versions = {row['version'] for row in consumers}
    if len(versions) > 1:
        errors.append('consumer pins must adopt one workflow release together')
    if products and require_history and consumers:
        try:
            tags = trusted_tags(contracts_root)
            if not tags:
                bootstrap = current == 'axiz-workflows-v2.0.0' and versions == {current}
                if current != 'axiz-workflows-v2.0.0' or versions != {current}:
                    errors.append('first publication only permits the initial 2.0.0 candidate')
            else:
                for version in versions:
                    if version not in tags:
                        errors.append('consumer workflow release tag is unavailable: refs/tags/' + version)
                    else:
                        result = subprocess.run(['git', '-C', str(contracts_root), 'rev-parse', '--verify', 'refs/tags/' + version + '^{commit}'], capture_output=True)
                        if result.returncode:
                            errors.append('consumer workflow release tag does not identify a commit: ' + version)
        except ValueError as error:
            errors.append(str(error))
    return errors, bootstrap


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root', type=Path)
    parser.add_argument('--validated-history', action='store_true')
    args = parser.parse_args()
    if args.validated_history:
        errors, bootstrap = validate_pins(args.root, args.root, require_history=True)
        if errors:
            raise ValueError('; '.join(errors))
        print(json.dumps({'pins': inventory(args.root), 'tags': trusted_tags(args.root), 'bootstrap': bootstrap}, indent=2))
    else:
        print(json.dumps(inventory(args.root), indent=2))


if __name__ == '__main__':
    main()

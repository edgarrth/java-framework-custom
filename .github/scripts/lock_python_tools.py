#!/usr/bin/env python3
"""Resolve a tool's complete wheel-only dependency tree and pin official PyPI hashes."""
import argparse
import concurrent.futures
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import urllib.request


def render(report):
    def requirement(row):
        name, version = row['metadata']['name'], row['metadata']['version']
        if not re.fullmatch(r'[A-Za-z0-9_.-]+', name) or not re.fullmatch(r'[A-Za-z0-9.!+_-]+', version):
            raise ValueError('Invalid resolved package identity')
        with urllib.request.urlopen(f'https://pypi.org/pypi/{name}/{version}/json', timeout=30) as response:
            release = json.load(response)
        hashes = sorted({file['digests']['sha256'] for file in release['urls'] if file['packagetype'] == 'bdist_wheel'})
        actual = row['download_info']['archive_info']['hashes']['sha256']
        if actual not in hashes or any(not re.fullmatch(r'[0-9a-f]{64}', value) for value in hashes):
            raise ValueError('Resolved wheel is not identified by official release metadata')
        return name.lower(), name + '==' + version + ' \\\n' + ' \\\n'.join('    --hash=sha256:' + value for value in hashes)
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        rows = sorted(pool.map(requirement, report['install']))
    if len({name for name, _ in rows}) != len(rows):
        raise ValueError('Duplicate package in resolved toolchain')
    return '# Complete wheel-only toolchain; refresh with .github/scripts/lock_python_tools.py.\n' + '\n'.join(value for _, value in rows) + '\n'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix='axiz-tool-lock-') as temporary:
        report = Path(temporary) / 'report.json'
        subprocess.run(['python3', '-m', 'pip', 'install', '--dry-run', '--ignore-installed', '--only-binary=:all:', '--index-url', 'https://pypi.org/simple', '--report', str(report), '-r', str(args.input.resolve())], executable=sys.executable, shell=False, check=True)
        content = render(json.loads(report.read_text()))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(content)
    print('Locked wheel-only toolchain:', args.output)


if __name__ == '__main__':
    main()

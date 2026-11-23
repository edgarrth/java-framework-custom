"""Controles revisados, solo con la librería estándar, incrustados en los jobs privilegiados de
documentación.

En esos jobs nunca se importa código ejecutable desde una copia del repositorio ni desde un
artefacto descargado.
"""
import base64
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import tarfile
import tempfile
import urllib.error
import urllib.request

MAX_BYTES = 1024 * 1024 * 1024
MAX_FILES = 50000
SHA = re.compile(r'[a-f0-9]{40}')
DIGEST = re.compile(r'[a-f0-9]{64}')
RELEASE = re.compile(r'axiz-docs-v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)')


def digest(data):
    return hashlib.sha256(data).hexdigest()


def canonical(value):
    return (json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False) + '\n').encode()


def relative(name):
    p = PurePosixPath(name)
    if (not name or p.is_absolute() or str(p) != name or any(part in {'.', '..', '.git', '.github'} for part in p.parts)
            or any(ord(char) < 32 or char == '\\' for char in name)):
        raise ValueError('Unsafe documentation path')
    return name


def git(directory, *args, token=None, data=None):
    env = dict(os.environ, GIT_CONFIG_NOSYSTEM='1', GIT_CONFIG_GLOBAL='/dev/null', GIT_TERMINAL_PROMPT='0')
    if token:
        header = base64.b64encode(('x-access-token:' + token).encode()).decode()
        env.update(GIT_CONFIG_COUNT='1', GIT_CONFIG_KEY_0='http.https://github.com/.extraheader', GIT_CONFIG_VALUE_0='AUTHORIZATION: basic ' + header)
    result = subprocess.run(['git', '-c', 'core.hooksPath=/dev/null', '-C', str(directory), *args], input=data, capture_output=True, env=env)
    if result.returncode:
        raise ValueError('Documentation Git operation failed: ' + args[0])
    return result.stdout


def git_files(directory, commit):
    rows = []; total = 0
    for value in git(directory, 'ls-tree', '-rz', '--full-tree', commit).split(b'\0'):
        if not value: continue
        header, name = value.split(b'\t', 1)
        mode, kind, blob = header.decode().split()
        name = relative(name.decode('utf-8'))
        if kind != 'blob' or mode not in {'100644', '100755'}:
            raise ValueError('Site tree must contain regular files only')
        size = int(git(directory, 'cat-file', '-s', blob))
        total += size
        if total > MAX_BYTES or len(rows) >= MAX_FILES:
            raise ValueError('Site tree exceeds inspection limits')
        content = git(directory, 'cat-file', 'blob', blob)
        rows.append({'path': name, 'mode': mode, 'size': len(content), 'sha256': digest(content)})
    return sorted(rows, key=lambda row: row['path'])


def tar_files(path):
    if path.stat().st_size > MAX_BYTES:
        raise ValueError('Pages archive exceeds size limit')
    rows = []; total = 0
    with tarfile.open(path, 'r:') as archive:
        for entry in archive:
            name = entry.name.removeprefix('./')
            if entry.isdir():
                if name not in {'', '.'}: relative(name.rstrip('/'))
                continue
            name = relative(name)
            if not entry.isfile() or entry.issym() or entry.islnk():
                raise ValueError('Pages archive must not contain links or special files')
            total += entry.size
            if total > MAX_BYTES or len(rows) >= MAX_FILES:
                raise ValueError('Pages archive exceeds inspection limits')
            content = archive.extractfile(entry).read(entry.size + 1)
            if len(content) != entry.size:
                raise ValueError('Truncated Pages content')
            rows.append({'path': name, 'mode': '100755' if entry.mode & 0o111 else '100644', 'size': entry.size, 'sha256': digest(content)})
    if len({row['path'] for row in rows}) != len(rows):
        raise ValueError('Duplicate Pages path')
    return sorted(rows, key=lambda row: row['path'])


def metadata(directory, expected):
    path = Path(directory) / 'publication.json'
    if path.is_symlink() or not path.is_file() or path.stat().st_size > 8 * 1024 * 1024:
        raise ValueError('Invalid documentation publication record')
    raw = path.read_bytes()
    if digest(raw) != expected['manifest_sha256']:
        raise ValueError('Documentation publication hash mismatch')
    data = json.loads(raw)
    if set(data) != {'schema', 'source', 'base', 'commit', 'tree', 'files', 'bundle_sha256', 'pages'} or data['schema'] != 1:
        raise ValueError('Invalid documentation publication schema')
    source = data['source']
    if not isinstance(source, dict) or set(source) != {'repository','sha','tag','run_id','producer_attempt'}:
        raise ValueError('Invalid documentation source schema')
    wanted = {key: expected[key] for key in ('repository', 'sha', 'tag', 'run_id')}
    if any(source.get(key) != value for key, value in wanted.items()):
        raise ValueError('Publication source differs from the authorized workflow')
    if (not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', source['repository']) or not SHA.fullmatch(source['sha'])
            or not RELEASE.fullmatch(source['tag']) or not re.fullmatch(r'[1-9][0-9]*', source['run_id'])
            or not re.fullmatch(r'[1-9][0-9]*', source.get('producer_attempt', ''))):
        raise ValueError('Invalid documentation source identity')
    for field in ('commit', 'tree'):
        if not SHA.fullmatch(data[field]) or data[field] != expected[field]:
            raise ValueError('Documentation commit or tree identity mismatch')
    if data['base'] and not SHA.fullmatch(data['base']):
        raise ValueError('Invalid publication base')
    pages = data['pages']
    name = 'github-pages-' + source['run_id'] + '-' + source['producer_attempt']
    if (set(pages) != {'name', 'id', 'tar_sha256'} or pages['name'] != name or pages['name'] != expected['pages_name']
            or str(pages['id']) != expected['pages_id'] or not re.fullmatch(r'[1-9][0-9]*', str(pages['id']))
            or not DIGEST.fullmatch(pages['tar_sha256']) or pages['tar_sha256'] != expected['pages_sha256']):
        raise ValueError('Pages producer identity mismatch')
    rows = data['files']
    if not isinstance(rows, list) or not rows or len(rows) > MAX_FILES:
        raise ValueError('Invalid site inventory')
    for row in rows:
        if not isinstance(row,dict) or set(row) != {'path', 'mode', 'size', 'sha256'} or row['mode'] not in {'100644', '100755'} or type(row['size']) is not int or row['size'] < 0 or not DIGEST.fullmatch(row['sha256']):
            raise ValueError('Invalid site inventory entry')
        relative(row['path'])
    if sum(row['size'] for row in rows) > MAX_BYTES or rows != sorted(rows, key=lambda row: row['path']) or len({row['path'] for row in rows}) != len(rows):
        raise ValueError('Invalid site inventory limits, order or duplicate path')
    if not DIGEST.fullmatch(data['bundle_sha256']):
        raise ValueError('Invalid bundle identity')
    return data


def verify_tar(path, data):
    if path.stat().st_size > MAX_BYTES or digest(path.read_bytes()) != data['pages']['tar_sha256'] or tar_files(path) != data['files']:
        raise ValueError('Actual Pages artifact differs from the approved Git tree')


def import_bundle(directory, destination, data):
    bundle = Path(directory) / 'site.bundle'
    if bundle.is_symlink() or not bundle.is_file() or bundle.stat().st_size > MAX_BYTES or digest(bundle.read_bytes()) != data['bundle_sha256']:
        raise ValueError('Documentation bundle identity mismatch')
    Path(destination).mkdir()
    git(destination, 'init', '--bare', '--quiet')
    heads = git(destination, 'bundle', 'list-heads', str(bundle)).decode().strip().splitlines()
    if heads != [data['commit'] + ' refs/heads/gh-pages']:
        raise ValueError('Documentation bundle contains unexpected refs')
    git(destination, 'bundle', 'verify', str(bundle))
    git(destination, 'fetch', '--quiet', str(bundle), 'refs/heads/gh-pages:refs/heads/gh-pages')
    if git(destination, 'rev-parse', data['commit'] + '^{tree}').decode().strip() != data['tree'] or git_files(destination, data['commit']) != data['files']:
        raise ValueError('Bundled Git tree differs from the approved site')
    if data['base']:
        git(destination, 'merge-base', '--is-ancestor', data['base'], data['commit'])


def api(repository, path, token, *, missing=False):
    request = urllib.request.Request('https://api.github.com/repos/' + repository + '/' + path,
        headers={'Authorization': 'Bearer ' + token, 'Accept': 'application/vnd.github+json', 'X-GitHub-Api-Version': '2022-11-28'})
    try:
        with urllib.request.urlopen(request, timeout=30) as response: return json.load(response)
    except urllib.error.HTTPError as error:
        if missing and error.code == 404: return None
        raise ValueError('Documentation API request failed with status ' + str(error.code)) from None


def head(repository, token):
    value = api(repository, 'git/ref/heads/gh-pages', token, missing=True)
    return value['object']['sha'] if value else ''


def verify_artifact(data, token):
    item = api(data['source']['repository'], 'actions/artifacts/' + str(data['pages']['id']), token)
    if (item.get('expired') is not False or str(item.get('id')) != str(data['pages']['id']) or item.get('name') != data['pages']['name']
            or str(item.get('workflow_run', {}).get('id')) != data['source']['run_id']
            or item.get('workflow_run', {}).get('head_sha') != data['source']['sha']):
        raise ValueError('Pages artifact is expired or belongs to another producer')
    expiry=item.get('expires_at')
    if not isinstance(expiry,str) or datetime.fromisoformat(expiry.replace('Z','+00:00')) <= datetime.now(timezone.utc):
        raise ValueError('Pages artifact has no valid unexpired retention')


def expected_environment():
    expected = json.loads(os.environ['PUBLICATION_OUTPUTS'])
    expected.update(repository=os.environ['GITHUB_REPOSITORY'], sha=os.environ['GITHUB_SHA'], tag=os.environ['GITHUB_REF_NAME'], run_id=os.environ['GITHUB_RUN_ID'])
    return expected


def operation():
    expected = expected_environment()
    state = Path(os.environ['DOCS_STATE_DIR']).resolve()
    data = metadata(state, expected)
    token = os.environ['GH_TOKEN']
    verify_artifact(data, token)
    if os.environ['DOCS_OPERATION'] == 'publish':
        with tempfile.TemporaryDirectory(prefix='axiz-docs-writer-') as temporary:
            bare = Path(temporary) / 'repository'
            import_bundle(state, bare, data)
            if head(data['source']['repository'], token) != data['base']:
                raise ValueError('Publication base is obsolete; regenerate all jobs for this tag')
            destination = 'https://github.com/' + data['source']['repository'] + '.git'
            git(bare, 'push', '--porcelain', '--force-with-lease=refs/heads/gh-pages:' + data['base'], destination, 'refs/heads/gh-pages:refs/heads/gh-pages', token=token)
        print('Published the verified documentation commit')
    elif os.environ['DOCS_OPERATION'] == 'verify-pages':
        path = Path(os.environ['PAGES_DATA_DIR']) / 'artifact.tar'
        if path.is_symlink() or not path.is_file(): raise ValueError('Missing Pages artifact')
        verify_tar(path, data)
        if head(data['source']['repository'], token) != data['commit']:
            raise ValueError('Pages artifact is obsolete; the current branch needs its own verified deployment')
        print('Pages artifact identity, content and current publication verified')
    else:
        raise ValueError('Unknown documentation control operation')


if __name__ == '__main__':
    operation()

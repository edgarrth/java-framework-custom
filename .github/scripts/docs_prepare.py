#!/usr/bin/env python3
"""Genera la documentación versionada en una copia aislada de Git, sin credenciales de escritura remota."""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

from docs_publish_control import RELEASE, SHA, canonical, digest, git, git_files, metadata, tar_files


def outputs(values):
    target = Path(os.environ['GITHUB_OUTPUT'])
    with target.open('a') as stream:
        for key, value in values.items():
            value = str(value)
            if '\n' in value or '\r' in value: raise ValueError('Invalid workflow output')
            stream.write(key + '=' + value + '\n')


def source_environment():
    return {'repository': os.environ['GITHUB_REPOSITORY'], 'sha': os.environ['GITHUB_SHA'],
            'tag': os.environ['GITHUB_REF_NAME'], 'run_id': os.environ['GITHUB_RUN_ID'],
            'producer_attempt': os.environ['GITHUB_RUN_ATTEMPT']}


def prepare(root, destination, source, *, generate=True, mike='mike'):
    root, destination = Path(root).resolve(), Path(destination).resolve()
    match = RELEASE.fullmatch(source['tag'])
    if not match or destination.is_relative_to(root): raise ValueError('Invalid documentation release or output path')
    if git(root, 'rev-parse', 'HEAD').decode().strip() != source['sha']:
        raise ValueError('Source checkout does not match the authorized tag commit')
    if git(root, 'rev-parse', 'refs/tags/'+source['tag']+'^{commit}').decode().strip() != source['sha']:
        raise ValueError('Documentation release tag differs from the authorized source')
    destination.mkdir(parents=True, exist_ok=True)
    state, site = destination/'state', destination/'site'
    state.mkdir(); site.mkdir()
    base = git(root, 'for-each-ref', '--format=%(objectname)', 'refs/remotes/origin/gh-pages').decode().strip()
    with tempfile.TemporaryDirectory(prefix='axiz-docs-build-') as temporary:
        work = Path(temporary)/'checkout'
        git(root, 'clone', '--quiet', '--no-local', str(root), str(work))
        git(work, 'checkout', '--quiet', '--detach', source['sha'])
        git(work, 'config', 'user.name', 'axiz-docs[bot]')
        git(work, 'config', 'user.email', 'axiz-docs@users.noreply.github.com')
        if base: git(work, 'fetch', '--quiet', str(root), base + ':refs/heads/gh-pages')
        docs = work/'axiz-docs'
        if generate:
            subprocess.run(['python3', 'scripts/sync_reference.py', '--framework', '../axiz-framework', '--cli-help', 'scripts/cli-help.txt'], cwd=docs, check=True)
            subprocess.run(['python3', 'scripts/dashboards.py'], cwd=docs, check=True)
        existing = json.loads(git(work, 'show', 'gh-pages:versions.json')) if base else []
        if not isinstance(existing,list):raise ValueError('Invalid published versions inventory')
        seen=set()
        for row in existing:
            version=row.get('version','');properties=row.get('properties',{})
            previous=RELEASE.fullmatch(properties.get('axiz_tag',''))
            sha=properties.get('axiz_source_sha','')
            if (not re.fullmatch(r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)',version,re.ASCII)
                    or version in seen or not previous or not SHA.fullmatch(sha)
                    or tuple(map(int,previous.groups()))[:2]!=tuple(map(int,version.split('.')))):
                raise ValueError('Published documentation lacks a canonical verified release identity')
            if git(root,'rev-parse','refs/tags/'+properties['axiz_tag']+'^{commit}').decode().strip()!=sha:
                raise ValueError('Published documentation release provenance does not match its tag')
            seen.add(version)
        release = tuple(map(int, match.groups())); line = str(release[0]) + '.' + str(release[1])
        known = {row['version']: row for row in existing}
        if line in known:
            properties = known[line].get('properties', {})
            previous = RELEASE.fullmatch(properties.get('axiz_tag', ''))
            if not previous: raise ValueError('Existing documentation has no verified release identity')
            if release < tuple(map(int, previous.groups())): raise ValueError('An older patch cannot overwrite published documentation')
            if release == tuple(map(int, previous.groups())) and properties.get('axiz_source_sha') != source['sha']:
                raise ValueError('A published documentation tag changed its source commit')
        command = [mike, 'deploy', '--allow-empty', '--alias-type', 'redirect', '--update-aliases',
                   '--prop-set', 'axiz_tag='+json.dumps(source['tag']), '--prop-set', 'axiz_source_sha='+json.dumps(source['sha']), line]
        subprocess.run(command, cwd=docs, check=True)
        current = json.loads(git(work, 'show', 'gh-pages:versions.json'))
        latest = max((row['version'] for row in current), key=lambda value: tuple(map(int, value.split('.'))))
        subprocess.run([mike, 'alias', '--allow-empty', '--alias-type', 'redirect', '--update-aliases', latest, 'latest'], cwd=docs, check=True)
        subprocess.run([mike, 'set-default', '--allow-empty', 'latest'], cwd=docs, check=True)
        current = json.loads(git(work, 'show', 'gh-pages:versions.json'))
        record = next(row for row in current if row['version'] == line)
        if record.get('properties', {}).get('axiz_tag') != source['tag'] or record['properties'].get('axiz_source_sha') != source['sha']:
            raise ValueError('Published versions.json did not preserve the release identity')
        commit = git(work, 'rev-parse', 'refs/heads/gh-pages').decode().strip()
        tree = git(work, 'rev-parse', commit+'^{tree}').decode().strip()
        files = git_files(work, commit)
        git(work, 'bundle', 'create', str(state/'site.bundle'), 'refs/heads/gh-pages')
                # Las rutas las verificó git_files. Solo se extraen archivos regulares de Git, sin enlaces.
        for row in files:
            target = site/row['path']; target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(git(work, 'show', commit+':'+row['path']))
            target.chmod(0o755 if row['mode']=='100755' else 0o644)
        data = {'schema':1, 'source':source, 'base':base, 'commit':commit, 'tree':tree, 'files':files,
                'bundle_sha256':digest((state/'site.bundle').read_bytes()),
                'pages':{'name':'github-pages-'+source['run_id']+'-'+source['producer_attempt'],'id':'','tar_sha256':''}}
        (state/'publication.json').write_bytes(canonical(data))
    return data


def attest(destination, tar, artifact_id):
    destination, tar = Path(destination), Path(tar)
    path = destination/'state/publication.json'; data = json.loads(path.read_bytes())
    if tar_files(tar) != data['files']: raise ValueError('Official Pages packaging changed the approved content')
    data['pages'].update(id=str(artifact_id), tar_sha256=digest(tar.read_bytes()))
    path.write_bytes(canonical(data))
    values = {'manifest_sha256':digest(path.read_bytes()), 'commit':data['commit'], 'tree':data['tree'],
              'pages_name':data['pages']['name'], 'pages_id':str(artifact_id), 'pages_sha256':data['pages']['tar_sha256']}
    expected = {**values, **{key:data['source'][key] for key in ('repository','sha','tag','run_id')}}
    metadata(path.parent, expected)
    return values


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    sub=parser.add_subparsers(dest='command',required=True)
    generate=sub.add_parser('prepare');generate.add_argument('--root',type=Path,default=Path.cwd());generate.add_argument('--output',type=Path,required=True)
    sign=sub.add_parser('attest');sign.add_argument('--output',type=Path,required=True);sign.add_argument('--tar',type=Path,required=True);sign.add_argument('--artifact-id',required=True)
    args=parser.parse_args()
    if args.command=='prepare':
        data=prepare(args.root,args.output,source_environment());outputs({'pages_name':data['pages']['name']})
    else:
        outputs(attest(args.output,args.tar,args.artifact_id))


if __name__=='__main__':main()

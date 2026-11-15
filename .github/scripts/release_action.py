#!/usr/bin/env python3
"""Prepare immutable upstream metadata, then verify release-planner parity without network."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import urllib.request
import yaml

POLICY = Path('.github/policies/release-action-v2.json')
PACKAGES = (Path('.github/scripts/release-version'), Path('axiz-framework/.github/scripts/release-version'))
REPOSITORY = 'googleapis/release-please-action'
WORKFLOWS = (Path('.github/workflows/release.yaml'), Path('.github/workflows/release-self.yaml'))


def digest(data): return hashlib.sha256(data).hexdigest()


def reference(root):
    commits = set()
    for workflow in WORKFLOWS:
        document = yaml.safe_load((Path(root)/workflow).read_text())
        refs = [step['uses'] for job in document['jobs'].values() for step in job.get('steps',[]) if step.get('uses','').startswith(REPOSITORY+'@')]
        if len(refs)!=1 or not re.fullmatch(re.escape(REPOSITORY)+r'@[a-f0-9]{40}', refs[0]):
            raise ValueError('Each release role must use one exact reviewed release action commit')
        commits.add(refs[0].split('@')[1])
    if len(commits)!=1:
        raise ValueError('Both release roles must use the same reviewed release action commit')
    return commits.pop()


def download(commit):
    files={}
    for name in ('package-lock.json','action.yml'):
        url='https://raw.githubusercontent.com/'+REPOSITORY+'/'+commit+'/'+name
        with urllib.request.urlopen(url,timeout=30) as response:content=response.read(2*1024*1024+1)
        if len(content)>2*1024*1024:raise ValueError('Upstream action metadata exceeds limit')
        files[name]=content
    return files


def registration(commit, files):
    lock=json.loads(files['package-lock.json']);metadata=yaml.safe_load(files['action.yml'])
    version=lock['packages']['node_modules/release-please']['version']
    if not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+',version) or metadata['runs']['using']!='node24':
        raise ValueError('Expected a published Node 24 action with an exact resolved library')
    if not {'token','config-file','manifest-file'}<=set(metadata['inputs']):
        raise ValueError('Release action input contract changed')
    return {'schema':1,'repository':REPOSITORY,'commit':commit,'releasePlease':version,
            'lockSha256':digest(files['package-lock.json']),'metadataSha256':digest(files['action.yml'])}


def cache_directory(cache, spec):
    if (set(spec)!={'schema','repository','commit','releasePlease','lockSha256','metadataSha256'} or spec['schema']!=1
            or not re.fullmatch(r'[a-f0-9]{40}',spec['commit']) or not re.fullmatch(r'[a-f0-9]{64}',spec['lockSha256'])
            or not re.fullmatch(r'[a-f0-9]{64}',spec['metadataSha256'])):
        raise ValueError('Invalid reviewed release action registration')
    return Path(cache)/spec['repository']/spec['commit']/spec['lockSha256']


def prepare(root, cache):
    spec=json.loads((Path(root)/POLICY).read_text())
    if spec['repository']!=REPOSITORY or spec['commit']!=reference(root):raise ValueError('Stale or unrelated release action registration')
    files=download(spec['commit'])
    if registration(spec['commit'],files)!=spec:raise ValueError('Downloaded action differs from the reviewed registration')
    directory=cache_directory(cache,spec);directory.mkdir(parents=True,exist_ok=True)
    for name,content in files.items():(directory/name).write_bytes(content)
    return verify(root,cache)


def verify(root,cache):
    root=Path(root);spec=json.loads((root/POLICY).read_text())
    if spec.get('repository')!=REPOSITORY or spec.get('commit')!=reference(root):raise ValueError('Stale or unrelated release action registration')
    directory=cache_directory(cache,spec)
    files={}
    for name in ('package-lock.json','action.yml'):
        path=directory/name
        if path.is_symlink() or not path.resolve().is_relative_to(Path(cache).resolve()) or not path.is_file() or path.stat().st_size>2*1024*1024:
            raise ValueError('Prepare the reviewed action metadata before offline checks')
        files[name]=path.read_bytes()
    if registration(spec['commit'],files)!=spec:raise ValueError('Cached action metadata checksum or version mismatch')
    for relative in PACKAGES:
        manifest=json.loads((root/relative/'package.json').read_text());lock=json.loads((root/relative/'package-lock.json').read_text())
        if (manifest.get('private') is not True or manifest.get('dependencies') or manifest['devDependencies']['release-please']!=spec['releasePlease']
                or lock['packages']['node_modules/release-please']['version']!=spec['releasePlease']
                or lock['packages']['']['devDependencies']['release-please']!=spec['releasePlease']):
            raise ValueError('Both local planners must match the reviewed release action exactly')
    return {'passed':True,'actionCommit':spec['commit'],'releasePlease':spec['releasePlease'],'localPlanners':len(PACKAGES),'networkUsed':False}


def refresh(root,cache):
    root=Path(root)
    version=(root/'.github/scripts/release-version/.node-version').read_text().strip()
    if subprocess.check_output(['node','--version'],text=True).strip()!='v'+version:
        raise ValueError('Use the pinned Node and its bundled npm before refreshing locks')
    commit=reference(root);files=download(commit);spec=registration(commit,files)
    for relative in PACKAGES:
        path=root/relative/'package.json';manifest=json.loads(path.read_text())
        manifest.setdefault('devDependencies',{}).update(manifest.pop('dependencies',{}))
        manifest['devDependencies']['release-please']=spec['releasePlease']
        path.write_text(json.dumps(manifest,indent=2)+'\n')
        subprocess.run(['npm','install','--package-lock-only','--include=dev','--ignore-scripts','--no-audit','--no-fund','--prefix',str(root/relative)],check=True)
    (root/POLICY).write_text(json.dumps(spec,indent=2)+'\n')
    directory=cache_directory(cache,spec);directory.mkdir(parents=True,exist_ok=True)
    for name,content in files.items():(directory/name).write_bytes(content)
    return verify(root,cache)


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('command',choices=['prepare','verify','refresh'])
    parser.add_argument('--root',type=Path,default=Path(__file__).resolve().parents[2]);parser.add_argument('--cache',type=Path,required=True)
    args=parser.parse_args();result=globals()[args.command](args.root,args.cache);print(json.dumps(result,indent=2))


if __name__=='__main__':main()

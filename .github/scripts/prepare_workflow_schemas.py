#!/usr/bin/env python3
"""Prepare checksum-pinned local CRD schemas before running the offline policy executor."""
import argparse
import hashlib
import json
import re
from pathlib import Path
import urllib.request


def verify(root,directory):
    spec=json.loads((Path(root)/'.github/gitops/crd-schemas.json').read_text())
    names={row['path'] for row in spec['files']}
    actual={str(path.relative_to(directory)) for path in Path(directory).rglob('*.json') if path.is_file()}
    if not names or len(names)!=len(spec['files']) or names!=actual:raise ValueError('Offline schema cache must contain exactly the pinned files')
    for row in spec['files']:
        target=Path(directory)/row['path']
        if not target.resolve().is_relative_to(Path(directory).resolve()) or target.is_symlink():raise ValueError('Unsafe schema path')
        if hashlib.sha256(target.read_bytes()).hexdigest()!=row['sha256']:raise ValueError('CRD schema checksum mismatch')
    return spec


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--root',type=Path,default=Path(__file__).resolve().parents[2]);parser.add_argument('--output',type=Path,required=True);args=parser.parse_args()
    spec=json.loads((args.root/'.github/gitops/crd-schemas.json').read_text())
    if not re.fullmatch(r'[a-f0-9]{40}',spec['sourceCommit']):raise ValueError('Expected an immutable schema source commit')
    for row in spec['files']:
        target=args.output/row['path']
        if not target.resolve().is_relative_to(args.output.resolve()) or target.is_symlink():raise ValueError('Unsafe schema output path')
        expected='https://raw.githubusercontent.com/datreeio/CRDs-catalog/'+spec['sourceCommit']+'/'+row['path']
        if row['url']!=expected:raise ValueError('Schema URL does not identify the reviewed source commit')
        data=urllib.request.urlopen(row['url'],timeout=30).read()
        if hashlib.sha256(data).hexdigest()!=row['sha256']:raise ValueError('Downloaded schema checksum mismatch')
        target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(data)
    verify(args.root,args.output)
    print('Prepared checksum-pinned offline CRD schemas:',len(spec['files']))


if __name__=='__main__':main()

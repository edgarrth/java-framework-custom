#!/usr/bin/env python3
"""Cada tag concreto de workflow tiene un único actualizador o una declaración exacta de datos independientes."""
import argparse
from collections import Counter
import json
from pathlib import Path
import re
import subprocess
import sys

sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'actions/check-workflow-security'))
from workflow_pins import inventory

POLICY=Path('.github/policies/workflow-release-literals.json')
LITERAL=re.compile(r'axiz-workflows-v[0-9]+\.[0-9]+\.[0-9]+')


def unowned(root):
    root=Path(root).resolve()
    top=subprocess.check_output(['git','-C',str(root),'rev-parse','--show-toplevel'],text=True).strip()
    if Path(top).resolve()!=root:raise ValueError('Literal inventory requires its own Git source root')
    files=set(subprocess.check_output(['git','-C',str(root),'ls-files','-co','--exclude-standard','-z']).decode().split('\0'))
    owners=Counter((row['path'],row['line'],row['version']) for row in inventory(root))
    config=root/'release-please-config.json'
    packages=json.loads(config.read_text()).get('packages',{}) if config.exists() else {}
    changelogs={str(Path(directory)/entry.get('changelog-path','CHANGELOG.md')) for directory,entry in packages.items()}
    rows=[]
    for name in sorted(files):
        if not name or name==str(POLICY) or name in changelogs or Path(name).name in {'.release-please-manifest.json','release-please-config.json'}:continue
        path=root/name
        if not path.exists():continue
        if not path.resolve().is_relative_to(root):raise ValueError('Literal source escapes the checkout: '+name)
        try:text=path.read_text(encoding='utf-8')
        except UnicodeError:continue
        for number,line in enumerate(text.splitlines(),1):
            for match in LITERAL.finditer(line):
                key=(name,number,match[0])
                if owners[key]:owners[key]-=1
                else:rows.append({'path':name,'line':number,'value':match[0]})
    return rows


def check(root):
    root=Path(root);policy=json.loads((root/POLICY).read_text());errors=[]
    if set(policy)!={'schema','exceptions'} or policy['schema']!=1:return ['Unexpected literal policy schema']
    actual=Counter((row['path'],row['value']) for row in unowned(root));declared={}
    for entry in policy['exceptions']:
        if set(entry)!={'path','value','count','purpose'} or not entry.get('purpose') or type(entry.get('count')) is not int or entry['count']<=0 or not LITERAL.fullmatch(entry.get('value','')):
            errors.append('Invalid independent-literal declaration');continue
        path=Path(entry['path'])
        if path.is_absolute() or '..' in path.parts or str(path)!=entry['path'] or path==POLICY or LITERAL.search(entry['path']) or LITERAL.search(entry['purpose']):
            errors.append('Unsafe or recursive literal declaration');continue
        key=(entry['path'],entry['value'])
        if key in declared:errors.append('Duplicate literal declaration: '+entry['path'])
        declared[key]=entry['count']
    for key,count in actual.items():
        if declared.get(key)!=count:errors.append(f'{key[0]}: unclassified or changed independent literal {key[1]} ({count} occurrences)')
    for key,count in declared.items():
        if actual.get(key)!=count:errors.append('Unused or changed independent literal declaration: '+key[0])
    return errors


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('root',type=Path,nargs='?',default=Path(__file__).resolve().parents[2]);args=parser.parse_args()
    errors=check(args.root)
    for error in errors:print(error)
    if not errors:print('Workflow release literals have exactly one reviewed owner')
    return int(bool(errors))


if __name__=='__main__':raise SystemExit(main())

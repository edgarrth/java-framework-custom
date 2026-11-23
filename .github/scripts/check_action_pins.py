#!/usr/bin/env python3
"""Inventario completo y sin conexión de las acciones de terceros, los tags publicados revisados y los pins legibles."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import sys
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'.github/actions/check-workflow-security'))
from workflow_sources import workflow_files
POLICY=Path('.github/policies/action-pins-v2.json')
REFERENCE=re.compile(r'^[ \t]*-?[ \t]*uses:[ \t]*[\"\']?([A-Za-z0-9_.-]+/[A-Za-z0-9_./-]+)@([^\s\"\']+)[\"\']?([^\n]*)$',re.M|re.ASCII)
def inventory(root):
    pins=[]
    for path in sorted(workflow_files(root)):
        for match in REFERENCE.finditer(path.read_text()):
            action,sha,tail=match.groups()
            annotation=re.fullmatch(r'[ \t]+#[ \t]*(v[0-9]+\.[0-9]+\.[0-9]+)[ \t]*',tail,re.ASCII)
            tag=annotation[1] if annotation else None
            if action.startswith('edgarrth/java-framework-custom/'):continue
            pins.append({'path':path.relative_to(root).as_posix(),'line':path.read_text().count('\n',0,match.start())+1,'action':action,'sha':sha,'tag':tag})
    return pins

def check(root):
    pins=inventory(root);policy=json.loads((root/POLICY).read_text());errors=[]
    if set(policy)!={'schema','pins'} or policy['schema']!=1:raise ValueError('Invalid action pin policy')
    registered={(row['action'],row['sha']):row for row in policy['pins']}
    if len(registered)!=len(policy['pins']):errors.append('Duplicate action pin registration')
    used=set()
    for row in pins:
        key=(row['action'],row['sha']);registration=registered.get(key)
        if not re.fullmatch('[a-f0-9]{40}',row['sha']) or not row['tag']:errors.append(row['path']+': third-party action needs an exact SHA and published full version comment')
        if not registration or registration['tag']!=row['tag'] or registration['repository']!='/'.join(row['action'].split('/')[:2]):errors.append(row['path']+': action SHA/tag differs from the reviewed official tag registration')
        used.add(key)
    if used!=set(registered):errors.append('Action pin registry has missing or unused entries')
    return {'passed':not errors,'errors':errors,'pins':pins}

def refresh(root):
    pins=inventory(root);tags={};rows={}
    for pin in pins:
        repo='/'.join(pin['action'].split('/')[:2])
        if not pin['tag'] or not re.fullmatch('[a-f0-9]{40}',pin['sha']):raise ValueError('Annotate exact full versions before refreshing pins')
        if repo not in tags:
            text=subprocess.run(['git','ls-remote','--tags','https://github.com/'+repo+'.git'],capture_output=True,text=True,check=True).stdout
            tags[repo]={ref:sha for sha,ref in (line.split() for line in text.splitlines())}
        ref='refs/tags/'+pin['tag'];sha=tags[repo].get(ref+'^{}',tags[repo].get(ref))
        if sha!=pin['sha']:raise ValueError('Action SHA does not match the official published version: '+pin['action'])
        rows[(pin['action'],pin['sha'])]={'action':pin['action'],'repository':repo,'sha':sha,'tag':pin['tag']}
    (root/POLICY).write_text(json.dumps({'schema':1,'pins':[rows[key] for key in sorted(rows)]},indent=2)+'\n')

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--root',type=Path,default=ROOT);parser.add_argument('--refresh',action='store_true');args=parser.parse_args()
    if args.refresh:refresh(args.root)
    result=check(args.root);print(json.dumps(result,indent=2));return int(not result['passed'])
if __name__=='__main__':raise SystemExit(main())

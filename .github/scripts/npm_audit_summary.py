#!/usr/bin/env python3
"""Summarize affected npm packages and resolve advisory references through the via graph."""
import argparse
from collections import Counter
import json
from datetime import datetime, timezone
import hashlib
import subprocess
from pathlib import Path

def summarize(audit):
    packages=audit.get('vulnerabilities')
    if not isinstance(packages,dict):raise ValueError('Expected npm audit v2 vulnerability graph')
    distinct={};rows=[]
    def references(name,chain):
        if name in chain:return []
        if name not in packages:raise ValueError('Unresolved npm advisory package reference: '+name)
        result=[]
        for via in packages[name].get('via',[]):
            if isinstance(via,str):result.extend(references(via,[*chain,name]))
            elif isinstance(via,dict) and via.get('source') and via.get('url','').startswith('https://'):
                advisory={key:via[key] for key in ('source','name','title','url','severity','range') if key in via}
                distinct[str(via['source'])]=advisory
                result.append({'advisory':advisory,'via':[*chain,name]})
            else:raise ValueError('Invalid npm advisory reference')
        return result
    for name,entry in sorted(packages.items()):
        resolved=references(name,[])
        if not resolved:raise ValueError('Affected npm package has no resolvable advisory: '+name)
        # Retain different dependency paths, but remove duplicate graph edges.
        unique={json.dumps(row,sort_keys=True):row for row in resolved}
        rows.append({'package':name,'severity':entry['severity'],'isDirect':entry['isDirect'],
                     'range':entry.get('range'),'fixAvailable':entry.get('fixAvailable'),
                     'references':sorted(unique.values(),key=lambda row:(str(row['advisory']['source']),row['via']))})
    return {'schema':1,'npmAuditReportVersion':audit.get('auditReportVersion'),'affectedPackageCount':len(rows),
            'uniqueAdvisoryCount':len(distinct),'affectedPackagesBySeverity':dict(Counter(row['severity'] for row in rows)),
            'uniqueAdvisoriesBySeverity':dict(Counter(row['severity'] for row in distinct.values())),
            'note':'Package counts and unique advisory counts are different; via records the transitive provenance for each affected package.',
            'packages':rows,'advisories':[distinct[key] for key in sorted(distinct)]}

LOCKFILES = (
    '.github/scripts/release-version/package-lock.json',
    'axiz-framework/.github/scripts/release-version/package-lock.json',
    '.github/scripts/docs-tools/package-lock.json',
)
SEVERITY = {'info':0, 'low':1, 'moderate':2, 'high':3, 'critical':4}

def checksum(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def collect(root, output):
    root, output = Path(root).resolve(), Path(output).resolve()
    if output.is_relative_to(root):
        raise ValueError('Prepare raw npm audit inputs outside the source checkout')
    expected=(root/'.github/scripts/release-version/.node-version').read_text().strip()
    if subprocess.check_output(['node','--version'],text=True).strip() != 'v'+expected:
        raise ValueError('Use the pinned Node and its bundled npm for audit preparation')
    records=[]
    for relative in LOCKFILES:
        lock=root/relative
        before=checksum(lock)
        result=subprocess.run(['npm','audit','--package-lock-only','--include=dev','--ignore-scripts','--json'],
                              cwd=lock.parent,capture_output=True,text=True,check=False,timeout=120)
        data=json.loads(result.stdout)
        if (result.returncode not in (0,1) or 'error' in data or data.get('auditReportVersion')!=2
                or not isinstance(data.get('vulnerabilities'),dict) or checksum(lock)!=before):
            raise ValueError('npm audit did not produce a verified report for '+relative)
        records.append({'path':relative,'lockSha256':before,'audit':data})
    value={'schema':1,'preparedAt':datetime.now(timezone.utc).isoformat(),'locks':records}
    output.parent.mkdir(parents=True,exist_ok=True)
    output.write_text(json.dumps(value,indent=2)+'\n')
    return value

def aggregate(prepared, root):
    if set(prepared)!={'schema','preparedAt','locks'} or prepared['schema']!=1:
        raise ValueError('Invalid prepared npm audit bundle')
    records=prepared['locks']
    if (not isinstance(records,list) or len(records)!=len(LOCKFILES)
            or {row.get('path') for row in records}!=set(LOCKFILES)):
        raise ValueError('The report must cover exactly all three current npm locks')
    locks=[];advisories={};names=set();pairs=0
    for record in records:
        if set(record)!={'path','lockSha256','audit'} or checksum(Path(root)/record['path'])!=record['lockSha256']:
            raise ValueError('Prepared npm audit input does not match the current lock')
        if record['audit'].get('auditReportVersion')!=2:raise ValueError('Unsupported npm audit report version')
        summary=summarize(record['audit'])
        if any(row['severity'] not in SEVERITY for row in summary['packages']):raise ValueError('Unknown npm package severity')
        locks.append({'path':record['path'],'sha256':record['lockSha256'],**summary})
        names.update(row['package'] for row in summary['packages']);pairs+=summary['affectedPackageCount']
        for advisory in summary['advisories']:
            url=advisory['url'];level=advisory['severity']
            if level not in SEVERITY:raise ValueError('Unknown npm advisory severity')
            item=advisories.setdefault(url,{'url':url,'name':advisory.get('name'),'title':advisory.get('title'),
                                          'severity':level,'sourceIds':[],'locks':[]})
            if SEVERITY[level]>SEVERITY[item['severity']]:item['severity']=level
            item['sourceIds']=sorted(set([*item['sourceIds'],advisory['source']]))
            item['locks']=sorted(set([*item['locks'],record['path']]))
    passed=all(not any(row['affectedPackagesBySeverity'].get(level,0) for level in ('moderate','high','critical')) for row in locks)
    passed=passed and not any(SEVERITY[row['severity']]>=SEVERITY['moderate'] for row in advisories.values())
    return {'schema':3,'preparedAt':prepared['preparedAt'],'passedMediumHighCriticalThreshold':passed,'locks':locks,
            'affectedLockPackagePairs':pairs,'uniqueAffectedPackageNames':len(names),'uniqueAdvisoryCount':len(advisories),
            'uniqueAdvisoriesBySeverity':dict(Counter(row['severity'] for row in advisories.values())),
            'advisories':[advisories[key] for key in sorted(advisories)],
            'note':'Each lock retains its own affected-package counts. Cross-lock advisories are deduplicated by URL; package names and lock/package pairs are distinct counts.'}

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root',type=Path,default=Path(__file__).resolve().parents[2])
    sub=parser.add_subparsers(dest='command',required=True)
    prepare=sub.add_parser('collect');prepare.add_argument('--output',type=Path,required=True)
    report=sub.add_parser('report');report.add_argument('--input',type=Path,required=True);report.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    if args.command=='collect':
        collect(args.root,args.output);print('Prepared all three npm audit reports without modifying locks');return 0
    if args.input.stat().st_size>20*1024*1024:raise ValueError('npm audit input exceeds inspection limit')
    result=aggregate(json.loads(args.input.read_text()),args.root)
    args.output.write_text(json.dumps(result,indent=2)+'\n')
    print('All three npm locks covered; unique advisories:',result['uniqueAdvisoryCount'])
    return int(not result['passedMediumHighCriticalThreshold'])
if __name__=='__main__':raise SystemExit(main())

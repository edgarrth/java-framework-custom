#!/usr/bin/env python3
"""Refresh reviewed privileged role fingerprints explicitly; never invoked to adopt changes by CI."""
import argparse
import json
from pathlib import Path
import sys
import yaml
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'.github/actions/check-workflow-security'))
from privileged_jobs import fingerprint, normalized_document, POLICY
ROLES={'lib-build.yaml':['sign'],'service-build.yaml':['publish','sign'],'native.yaml':['sign'],
       'lib-deploy.yaml':['deploy'],'service-deploy.yaml':['deploy'],
       'docs-docs.yaml':['publish','deploy-pages'],'security.yaml':['publish-sarif'],
       'pr.yaml':['publish-sarif'],'release.yaml':['release'],'release-self.yaml':['release']}
def content(root=ROOT):
    rows=[]
    for filename,names in ROLES.items():
        doc=yaml.safe_load((root/'.github/workflows'/filename).read_text())
        for name in names:
            rows.append({'workflow':filename,'job':name,'workflowSha256':fingerprint(normalized_document(doc)),
                         'jobSha256':fingerprint(doc['jobs'][name]),'reason':'Reviewed immutable role and complete workflow context; no consumer policy can authorize new privileged execution'})
    return json.dumps({'schema':2,'roles':rows},indent=2)+'\n'
def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--write',action='store_true');args=parser.parse_args()
    text=content()
    if args.write:POLICY.write_text(text)
    elif POLICY.read_text()!=text:raise SystemExit('Privileged roles changed: review the workflow and explicitly refresh trusted policy')
    print('Closed privileged roles match the reviewed workflows')
if __name__=='__main__':main()

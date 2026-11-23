"""Roles revisados cerrados: los permisos y la emisión de tokens de App deben coincidir con los contratos de workflow de confianza."""
import hashlib
import json
from pathlib import Path
import re

from internal_contracts import TAG, TAG_PATTERN

POLICY=Path(__file__).resolve().parents[2]/'policies/privileged-jobs-v2.json'
BUILD={'lib-build.yaml','service-build.yaml','native.yaml'}
DEPLOY={'lib-deploy.yaml','service-deploy.yaml'}
NORMALIZED='__IMMUTABLE_WORKFLOWS_RELEASE__'
TOKEN=re.compile(r'(?<![A-Za-z0-9_.-])'+TAG_PATTERN+r'(?![A-Za-z0-9_.-])',re.ASCII)

def fingerprint(value):
    # Las claves JSON son strings en la representación YAML normalizada (incluida la clave on de YAML 1.1).
    text=json.dumps(value,sort_keys=True,separators=(',',':'))
    return hashlib.sha256(TOKEN.sub(NORMALIZED,text).encode()).hexdigest()

def normalized_document(document):
    return {str(key):value for key,value in document.items()}

def privileged(job):
    permissions=job.get('permissions',{})
    return (isinstance(permissions,dict) and 'write' in permissions.values()
            or any('create-github-app-token@' in step.get('uses','') for step in job.get('steps',[])))

def validate(document):
    errors=[]
    if document.get('permissions')!={}:errors.append('workflow permissions must start empty; grant rights per job')
    policy=json.loads(POLICY.read_text())
    if set(policy)!={'schema','roles'} or policy['schema']!=2:raise ValueError('Unreviewed privileged role policy')
    workflow_hash=fingerprint(normalized_document(document))
    for name,job in document.get('jobs',{}).items():
        permissions=job.get('permissions',{})
        if not isinstance(permissions,dict):
            errors.append(name+': permissions must be an explicit least-privilege map');continue
        if not privileged(job):continue
        ref=job.get('uses','')
        match=re.fullmatch(r'edgarrth/java-framework-custom/\.github/workflows/([a-z-]+\.yaml)@('+TAG_PATTERN+r')',ref,re.ASCII)
        if match:
            target=match.group(1)
            delegated={**{key:{'contents':'read','actions':'read','id-token':'write','packages':'read'} for key in BUILD},
                       **{key:{'contents':'write','actions':'read','id-token':'write','packages':'read'} for key in DEPLOY},
                       'pr.yaml':{'contents':'read','pull-requests':'read','actions':'read','security-events':'write','packages':'read'},
                       'release.yaml':{'contents':'write'}}
            delegated['service-build.yaml']['packages']='write'
            delegated['lib-deploy.yaml']['packages']='write'
            if target in delegated and permissions==delegated[target]:continue
            errors.append(name+': delegated permissions differ from the reviewed reusable role');continue
        if 'env' in document or 'defaults' in document:
            errors.append(name+': privileged jobs cannot inherit ambient environment or command defaults')
        wanted=[role for role in policy['roles'] if role['job']==name and role['workflowSha256']==workflow_hash and role['jobSha256']==fingerprint(job)]
        if len(wanted)!=1:
            errors.append(name+': privileged actions, scripts, arguments, paths, environment, dependencies or workflow context differ from the closed role contract')
    return errors

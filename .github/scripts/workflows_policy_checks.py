#!/usr/bin/env python3
"""One offline command inventory for CI and each isolated release/adoption state."""
import argparse
from collections import deque
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

from prepare_workflow_schemas import verify as verify_schemas

# Lifecycle entrypoints are deliberately outside this list: they call this base executor.
COMMANDS = [
    ('npm-audit-report', ['{python}', '.github/scripts/npm_audit_summary.py', 'report', '--input', '{npm_audit_input}', '--output', '{output}/npm-audit-summary.json']),
    ('action-pins', ['{python}', '.github/scripts/check_action_pins.py']),
    ('release-action-parity', ['{python}', '.github/scripts/release_action.py', 'verify', '--cache', '{release_action_cache}']),
    ('release-permissions', ['{python}', '.github/scripts/release_permissions.py']),
    ('docs-controls', ['{python}', '.github/scripts/render_docs_controls.py', '--check']),
    ('privileged-roles', ['{python}', '.github/scripts/record_privileged_roles.py']),
    ('versions', ['{python}', '.github/actions/check-release-versions/check_release_versions.py', '.']),
    ('manifest-tests', ['{python}', '-m', 'unittest', 'discover', '-s', '.github/actions/build-manifest']),
    ('workflow-tests', ['{python}', '-m', 'unittest', 'discover', '-s', '.github/actions/check-workflow-security']),
    ('script-tests', ['{python}', '-m', 'unittest', 'discover', '-s', '.github/scripts', '-p', 'test_*.py']),
    ('contracts', ['{python}', '.github/actions/check-workflow-security/check_workflow_security.py', '.', '--scope', 'all', '--contracts-root', '.']),
    ('demo', ['{python}', '.github/scripts/check_demo_workflows.py', '.']),
    ('documentation', ['{python}', '.github/scripts/check_documentation.py']),
    ('literals', ['{python}', '.github/scripts/workflow_release_literals.py', '.']),
    ('library-contracts', ['{python}', '.github/actions/check-workflow-security/check_pipeline_contracts.py', '--build', '.github/workflows/lib-build.yaml', '--deploy', '.github/workflows/lib-deploy.yaml']),
    ('service-contracts', ['{python}', '.github/actions/check-workflow-security/check_pipeline_contracts.py', '--build', '.github/workflows/service-build.yaml', '--deploy', '.github/workflows/service-deploy.yaml']),
    *[(name+'-tests',['{python}','-m','unittest','discover','-s','.github/actions/'+name,'-p','test_*.py']) for name in ('setup-maven','check-encapsulation','check-sbom','check-mutations')],
    ('actionlint', ['{actionlint}']),
    ('kyverno', ['{python}', '.github/scripts/test_gitops_policies.py', '--kyverno', '{kyverno}', '--output', '{output}/kyverno']),
    ('kubeconform', ['{kubeconform}', '-strict', '-summary', '-schema-location', '{schemas}/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json', '{output}/kyverno/rendered/argocd', '{output}/kyverno/rendered/policies']),
    ('presets', ['{python}', '.github/scripts/build_renovate_presets.py']),
    ('renovate-config', ['{renovate}', '--strict', 'renovate.json', '.github/renovate/default.json', '.github/renovate/platform.json', '.github/renovate/service.json']),
    ('renovate-adoption', ['node', '.github/scripts/release-version/test-workflow-adoption.cjs']),
    ('release-please', ['node', '.github/scripts/release-version/test-monorepo.cjs']),
    ('gitleaks-canaries', ['{python}', '.github/actions/check-workflow-security/check_gitleaks.py', '--configuration', '.gitleaks.toml']),
]


def arguments(parser):
    parser.add_argument('--root',type=Path,default=Path(__file__).resolve().parents[2])
    parser.add_argument('--output',type=Path,required=True)
    for name in ('actionlint','kyverno','kubeconform'):
        parser.add_argument('--'+name,default=os.environ.get('AXIZ_'+name.upper(),shutil.which(name)))
    parser.add_argument('--schemas',type=Path,default=os.environ.get('AXIZ_CRD_SCHEMAS'))


def execute(root,output,tools):
    root,output=Path(root).resolve(),Path(output).resolve()
    if output.is_relative_to(root):raise ValueError('Policy outputs must remain outside the source checkout')
    output.mkdir(parents=True,exist_ok=True)
    verify_schemas(root,tools['schemas'])
    values={'npm_audit_input':os.environ.get('AXIZ_NPM_AUDIT_INPUT'),'release_action_cache':os.environ.get('AXIZ_RELEASE_ACTION_CACHE'),**tools,'python':sys.executable,'output':str(output),'schemas':str(Path(tools['schemas']).resolve()),'renovate':str(root/'.github/scripts/release-version/node_modules/.bin/renovate-config-validator')}
    if any(not value for value in values.values()):raise ValueError('Prepare all pinned tools and schemas before offline checks')
    env={**os.environ,'PATH':str(Path(sys.executable).parent)+os.pathsep+os.environ.get('PATH',''),
         'AXIZ_POLICY_PYTHON':sys.executable,'PYTHONDONTWRITEBYTECODE':'1','GIT_CEILING_DIRECTORIES':str(root.parent),'SEMGREP_ENABLE_VERSION_CHECK':'0'}
    results=[]
    for name,command in COMMANDS:
        argv=[]
        for item in command:
            for key,value in values.items():item=item.replace('{'+key+'}',str(value))
            argv.append(item)
        with (output/(name+'.log')).open('w') as log:
            result=subprocess.run(argv,cwd=root,env=env,stdout=log,stderr=subprocess.STDOUT)
        results.append({'name':name,'passed':result.returncode==0,'exitCode':result.returncode,'log':name+'.log'})
        print(('PASS ' if result.returncode==0 else 'FAIL ')+name,flush=True)
        if result.returncode:
            print('::group::'+name+' diagnostics ('+str(output/(name+'.log'))+')',flush=True)
            with (output/(name+'.log')).open(errors='replace') as log:
                print(''.join(deque(log,maxlen=100)),end='',flush=True)
            print('\n::endgroup::',flush=True)
            break
    summary={'passed':len(results)==len(COMMANDS) and all(row['passed'] for row in results),'checks':results}
    (output/'result.json').write_text(json.dumps(summary,indent=2)+'\n')
    return summary


def main():
    parser=argparse.ArgumentParser(description=__doc__);arguments(parser);args=parser.parse_args()
    return int(not execute(args.root,args.output,{name:getattr(args,name) for name in ('actionlint','kyverno','kubeconform','schemas')})['passed'])


if __name__=='__main__':raise SystemExit(main())

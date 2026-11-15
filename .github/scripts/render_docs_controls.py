#!/usr/bin/env python3
"""Embed the reviewed stdlib control in privileged jobs; --check never rewrites policy."""
import argparse
import json
from pathlib import Path
import re
import yaml

ROOT=Path(__file__).resolve().parents[2]
WORKFLOW=Path('.github/workflows/docs-docs.yaml')
class Dumper(yaml.SafeDumper):pass
Dumper.add_representer(str,lambda dumper,value:dumper.represent_scalar('tag:yaml.org,2002:str',value,style='|' if '\n' in value else None))
def render(root=ROOT):
    records=json.loads((root/'.github/policies/action-pins-v2.json').read_text())['pins']
    pins={row['action']:(row['sha'],row['tag']) for row in records}
    if len(pins)!=len(records):raise ValueError('Duplicate action source registration')
    def action(name,**fields):return {'uses':name+'@'+pins[name][0],**fields}
    policy=json.loads((root/'.github/policies/required-checks-v2.json').read_text())
    source=(root/'.github/scripts/docs_publish_control.py').read_text()
    control={'name':'Verify reviewed publication data','shell':'bash','env':{
        'PUBLICATION_OUTPUTS':'${{ toJSON(needs.prepare.outputs) }}',
        'DOCS_STATE_DIR':'${{ runner.temp }}/docs-state','GH_TOKEN':'${{ github.token }}'},
        'run':"set -euo pipefail\npython3 - <<'AXIZ_DOCS_CONTROL'\n"+source+'\nAXIZ_DOCS_CONTROL\n'}
    checkout=action('actions/checkout',**{'with':{'persist-credentials':False,'fetch-depth':0,'fetch-tags':True}})
    python=action('actions/setup-python',**{'with':{'python-version':'3.13'}})
    install={'name':'Install verified documentation wheels','run':'python3 -m venv "$RUNNER_TEMP/docs-python"\n"$RUNNER_TEMP/docs-python/bin/pip" install --require-hashes --only-binary=:all: -r axiz-docs/requirements.txt\necho "$RUNNER_TEMP/docs-python/bin" >> "$GITHUB_PATH"\n'}
    tag="startsWith(github.ref, 'refs/tags/axiz-docs-v')"
    build={'permissions':{'contents':'read'},'runs-on':'ubuntu-24.04','timeout-minutes':20,'steps':[checkout,python,install,
        action('actions/setup-node',**{'with':{'node-version-file':'.github/scripts/release-version/.node-version'}}),
        {'name':'Install locked Markdown validator without scripts','run':'npm ci --prefix .github/scripts/docs-tools --include=dev --ignore-scripts --no-audit --no-fund'},
        {'name':'Reference pages and dashboards are current','working-directory':'axiz-docs','run':'python scripts/sync_reference.py --framework ../axiz-framework --cli-help scripts/cli-help.txt\npython scripts/dashboards.py\nif [[ "$GITHUB_REF" != refs/tags/axiz-docs-v* ]]; then git diff --exit-code -- docs dashboards; fi\n'},
        {'name':'Markdown lint','working-directory':'axiz-docs','run':'../.github/scripts/docs-tools/node_modules/.bin/markdownlint-cli2 "docs/**/*.md" "README.md"'},
        {'name':'Dashboards are valid JSON','working-directory':'axiz-docs','run':'for f in dashboards/*.json; do python -m json.tool "$f" > /dev/null; done'},
        {'name':'Strict documentation build','working-directory':'axiz-docs','run':'mkdocs build --strict'}]}
    prepare={'needs':'build','if':tag,'permissions':{'contents':'read'},'runs-on':'ubuntu-24.04','timeout-minutes':25,
        'outputs':{key:'${{ steps.attest.outputs.'+key+' }}' for key in ('manifest_sha256','commit','tree','pages_name','pages_id','pages_sha256')},
        'steps':[checkout,python,install,{'name':'Generate site in an isolated Git copy without pushing','id':'prepare','run':'python .github/scripts/docs_prepare.py prepare --output "$RUNNER_TEMP/docs-publication"'},
        action('actions/upload-pages-artifact',id='pages',**{'with':{'name':'${{ steps.prepare.outputs.pages_name }}','path':'${{ runner.temp }}/docs-publication/site','retention-days':30,'include-hidden-files':True}}),
        {'name':'Attest the actual official Pages tar','id':'attest','env':{'PAGES_ID':'${{ steps.pages.outputs.artifact_id }}'},'run':'python .github/scripts/docs_prepare.py attest --output "$RUNNER_TEMP/docs-publication" --tar "$RUNNER_TEMP/artifact.tar" --artifact-id "$PAGES_ID"'},
        action('actions/upload-artifact',id='state',**{'with':{'name':'docs-publication-${{ github.run_id }}-${{ github.run_attempt }}','path':'${{ runner.temp }}/docs-publication/state','retention-days':30,'if-no-files-found':'error'}})]}
    prepare['outputs']['state_id']='${{ steps.state.outputs.artifact-id }}'
    download=action('actions/download-artifact',**{'with':{'artifact-ids':'${{ needs.prepare.outputs.state_id }}','path':'${{ runner.temp }}/docs-state'}})
    publish={'needs':'prepare','if':tag,'runs-on':'ubuntu-24.04','timeout-minutes':15,'environment':'docs-publication',
        'permissions':{'contents':'write','actions':'read'},'steps':[download,{**control,'env':{**control['env'],'DOCS_OPERATION':'publish'}}]}
    pages={'needs':['prepare','publish'],'if':tag,'runs-on':'ubuntu-24.04','timeout-minutes':15,
        'environment':{'name':'github-pages','url':'${{ steps.deployment.outputs.page_url }}'},
        'permissions':{'contents':'read','actions':'read','pages':'write','id-token':'write'},
        'steps':[download,action('actions/download-artifact',**{'with':{'artifact-ids':'${{ needs.prepare.outputs.pages_id }}','path':'${{ runner.temp }}/docs-pages'}}),
            {**control,'env':{**control['env'],'DOCS_OPERATION':'verify-pages','PAGES_DATA_DIR':'${{ runner.temp }}/docs-pages'}},
            action('actions/deploy-pages',id='deployment',**{'with':{'artifact_name':'${{ needs.prepare.outputs.pages_name }}'}})]}
    gate={'name':'axiz-docs-site-pr','if':"${{ always() && github.event_name == 'pull_request' }}",'needs':['build','prepare','publish','deploy-pages'],
        'runs-on':'ubuntu-24.04','timeout-minutes':2,'permissions':{},'steps':[{'name':'Require every pull request job','shell':'bash',
            'env':{'NEEDS':'${{ toJSON(needs) }}','SKIPPABLE':'["deploy-pages","prepare","publish"]'},'run':policy['verdictScript']}]}
    document={'name':'docs-docs','on':{'pull_request':None,'push':{'branches':['main'],'tags':['axiz-docs-v*']},'workflow_dispatch':None},'permissions':{},
        'concurrency':{'group':"${{ startsWith(github.ref, 'refs/tags/axiz-docs-v') && 'docs-publication' || format('docs-ci-{0}', github.ref) }}",'cancel-in-progress':"${{ github.event_name == 'pull_request' }}"},
        'jobs':{'build':build,'prepare':prepare,'publish':publish,'deploy-pages':pages,'docs-site-pr-check':gate}}
    content=yaml.dump(document,Dumper=Dumper,sort_keys=False,width=150)
    for name,(sha,version) in pins.items():content=content.replace('uses: '+name+'@'+sha+'\n','uses: '+name+'@'+sha+' # '+version+'\n')
    return '# Generated by .github/scripts/render_docs_controls.py; review its source and the closed privileged roles.\n'+content

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--check',action='store_true');args=parser.parse_args()
    content=render();path=ROOT/WORKFLOW
    if args.check:
        if path.read_text()!=content:raise SystemExit('Documentation controls differ from the reviewed source; regenerate and review')
        print('Documentation controls match their reviewed source')
    else:path.write_text(content)
if __name__=='__main__':main()

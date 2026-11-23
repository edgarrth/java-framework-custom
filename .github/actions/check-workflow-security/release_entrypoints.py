#!/usr/bin/env python3
"""Planes de release reales sin conexión, adopción real y verificaciones completas de política en copias aisladas de Git."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import tempfile
import unittest

import yaml

from internal_contracts import ROOT
from workflow_pins import inventory

CHECKS=Path('.github/actions/check-workflow-security')
OPTIONS=None


def git(root,*args):
    return subprocess.run(['git','-C',str(root),*args],check=True,capture_output=True,text=True)


def fingerprint(root):
    records=[]
    names=set(git(root,'ls-files','-co','--exclude-standard','-z').stdout.split('\0'))
    for name in sorted(names):
        if not name:continue
        path=root/name
        if path.is_symlink():records.append((name,'link',os.readlink(path)))
        elif path.is_file():records.append((name,path.stat().st_mode,hashlib.sha256(path.read_bytes()).hexdigest()))
        else:records.append((name,'deleted'))
    return hashlib.sha256(json.dumps(records,separators=(',',':')).encode()).hexdigest()


def snapshot(root,message):
    git(root,'add','-A')
    git(root,'-c','user.name=Axiz local fixture','-c','user.email=fixture@example.test','-c','commit.gpgsign=false','-c','core.hooksPath=/dev/null','commit','--quiet','--allow-empty','-m',message)


def clone_workspace(source,target):
    subprocess.run(['git','clone','--quiet','--no-local',str(source),str(target)],check=True,capture_output=True)
    git(target,'remote','remove','origin')
    for name in set(git(source,'ls-files','-co','--exclude-standard','-z').stdout.split('\0')):
        if not name:continue
        original,copy=source/name,target/name
        if original.is_symlink():
            copy.parent.mkdir(parents=True,exist_ok=True)
            if copy.exists() or copy.is_symlink():copy.unlink()
            copy.symlink_to(os.readlink(original))
        elif original.is_file():
            copy.parent.mkdir(parents=True,exist_ok=True)
            if copy.is_symlink():copy.unlink()
            shutil.copy2(original,copy)
        elif copy.is_file() or copy.is_symlink():copy.unlink()
    modules=target/'.github/scripts/release-version/node_modules'
    modules.symlink_to(source/'.github/scripts/release-version/node_modules',target_is_directory=True)
    exclude=target/'.git/info/exclude'
    exclude.write_text(exclude.read_text()+'\n/.github/scripts/release-version/node_modules\n')
    if git(target,'check-ignore',str(modules.relative_to(target))).returncode:raise ValueError('Toolchain link must never enter the fixture Git history')
    readme=target/'.github/WORKFLOWS.md';readme.write_text(readme.read_text()+'\n<!-- Local release lifecycle snapshot -->\n')
    snapshot(target,'fix: snapshot current workflow lifecycle')


class ReleaseEntrypointTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.before=fingerprint(ROOT)
        cls.temporary=tempfile.TemporaryDirectory(prefix='axiz-release-cycle-')
        cls.addClassCleanup(cls.temporary.cleanup)
        cls.base=Path(cls.temporary.name);cls.output=OPTIONS.output.resolve()
        if cls.output.is_relative_to(ROOT):raise ValueError('Lifecycle output must remain outside the source checkout')
        cls.output.mkdir(parents=True,exist_ok=True)
        cls.results=[];repo=cls.base/'workspace';clone_workspace(ROOT,repo)
        cls.env={**os.environ,'AXIZ_POLICY_PYTHON':sys.executable,'PYTHONDONTWRITEBYTECODE':'1','GIT_CEILING_DIRECTORIES':str(cls.base)}
        cls.initial=cls.base/'initial';shutil.copytree(repo,cls.initial,symlinks=True)
        cls.published_versions=set(git(repo,'tag','--list','axiz-workflows-v*').stdout.splitlines())
        if not cls.published_versions:
            cls.apply_plan(repo,'bootstrap-plan')
            first='axiz-workflows-v'+(repo/'.github/version.txt').read_text().strip()
            snapshot(repo,'chore: initial workflow release');git(repo,'tag',first)
            path=repo/'.github/WORKFLOWS.md';path.write_text(path.read_text()+'\n<!-- Next local patch -->\n');snapshot(repo,'fix: exercise a real workflow patch')
        cls.previous={row['version'] for row in inventory(repo) if row['owner']=='renovate'}
        if len(cls.previous)!=1:raise ValueError('The lifecycle starts from one adopted workflow release')
        cls.previous=next(iter(cls.previous))
        cls.previous_archive=cls.archive(repo,cls.previous,'previous-release')
        cls.consumer_bytes={row['path']:(repo/row['path']).read_bytes() for row in inventory(repo) if row['owner']=='renovate'}
        plan=cls.apply_plan(repo,'candidate-plan');cls.candidate_tag='axiz-workflows-v'+plan['version']
        if cls.candidate_tag==cls.previous or cls.candidate_tag in git(repo,'tag','--list').stdout.splitlines():raise ValueError('The candidate must be a different, not-yet-published workflow tag')
        for name,content in cls.consumer_bytes.items():
            if (repo/name).read_bytes()!=content:raise ValueError('Product release plan modified a consumer')
        snapshot(repo,'chore: workflow release candidate')
        cls.candidate=cls.base/'candidate';shutil.copytree(repo,cls.candidate,symlinks=True)
        git(repo,'tag',cls.candidate_tag)
        cls.published=cls.base/'published';shutil.copytree(repo,cls.published,symlinks=True)
        cls.current_archive=cls.archive(repo,cls.candidate_tag,'current-release')
        script=repo/'.github/scripts/release-version/test-workflow-adoption.cjs'
        with (cls.output/'adoption.log').open('w') as log:
            completed=subprocess.run(['node',str(script),str(repo),cls.candidate_tag],env=cls.env,stdout=log,stderr=subprocess.STDOUT)
        if completed.returncode:raise ValueError('Real Renovate adoption failed; see adoption.log')
        after=[row for row in inventory(repo) if row['owner']=='renovate']
        if len(after)!=sum(1 for row in inventory(cls.published) if row['owner']=='renovate') or {row['version'] for row in after}!={cls.candidate_tag}:
            raise ValueError('Adoption failed to change the complete consumer inventory')
        cls.adopted=repo;cls.consumer_count=len(after)
        cls.prepare_shell()

    @classmethod
    def apply_plan(cls,repo,label):
                # El planificador compartido forma parte de la cadena de herramientas verificada; toda lectura de datos apunta a repo.
        script=ROOT/'.github/scripts/release-version/release-plan.cjs'
        completed=subprocess.run(['node',str(script),str(repo)],env=getattr(cls,'env',os.environ),capture_output=True,text=True)
        (cls.output/(label+'.log')).write_text(completed.stdout+completed.stderr)
        if completed.returncode:raise ValueError('Real Release Please plan failed: '+label)
        row=next(line for line in completed.stdout.splitlines() if line.startswith('AXIZ_RELEASE_PLAN_RESULT='))
        return json.loads(row.split('=',1)[1])

    @classmethod
    def archive(cls,repo,tag,label):
        archive=cls.base/(label+'.tar');target=cls.base/label;target.mkdir()
        git(repo,'archive','--format=tar','--output='+str(archive),'refs/tags/'+tag)
        with tarfile.open(archive) as source:source.extractall(target,filter='data')
        return target

    @classmethod
    def prepare_shell(cls):
        cls.runner=cls.base/'runner';cls.bin=cls.base/'bin';cls.bin.mkdir()
        python=cls.bin/'python3';python.write_text('#!/bin/sh\n[ "$1" = -m ] && [ "$2" = venv ]\n');python.chmod(0o700)
        body='''#!/bin/sh
if [ "$1" = -m ] && [ "$2" = pip ]; then
  shift 2
  hashes=false; requirements=false
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --require-hashes) hashes=true ;;
      -r) shift; test -f "$1" || exit 94; requirements=true ;;
    esac
    shift
  done
  "$hashes" && "$requirements"
  exit $?
fi
exec "$AXIZ_TEST_PYTHON" "$@"
'''
        for name in ('axiz-workflow-policy','axiz-pipeline-contracts'):
            directory=cls.runner/name/'bin';directory.mkdir(parents=True)
            for filename,content in [('python',body),('pip','#!/bin/sh\nexec "$(dirname "$0")/python" -m pip "$@"\n')]:
                path=directory/filename;path.write_text(content);path.chmod(0o700)

    def setUp(self):
        self.repo=self.base/self._testMethodName;shutil.copytree(self.candidate,self.repo,symlinks=True)

    def cli(self,source,contracts=None,scope='all'):
        contracts=contracts or source
        return subprocess.run([sys.executable,str(contracts/CHECKS/'check_workflow_security.py'),str(source),'--scope',scope,'--contracts-root',str(contracts)],env=self.env,capture_output=True,text=True)

    def shell(self,body,cwd,contracts):
        env={**self.env,'RUNNER_TEMP':str(self.runner),'GITHUB_ACTION_PATH':str(contracts/CHECKS),'AXIZ_TEST_PYTHON':sys.executable,'PATH':str(self.bin)+os.pathsep+os.environ['PATH']}
        return subprocess.run(['bash','-euo','pipefail','-c',body],cwd=cwd,env=env,capture_output=True,text=True)

    def wrapper(self,source,contracts):
        action=yaml.safe_load((contracts/CHECKS/'action.yaml').read_text())
        return self.shell(action['runs']['steps'][0]['run'],source,contracts)

    def tools(self,source,contracts):
        workflow=yaml.safe_load((source/'.github/workflows/tools-ci.yaml').read_text());steps=workflow['jobs']['pipeline-contracts']['steps'];step=steps[-1]
        target=source/steps[1]['with']['path'];target.parent.mkdir(parents=True,exist_ok=True)
        if target.exists():shutil.rmtree(target)
        shutil.copytree(contracts,target,symlinks=True)
        return self.shell(step['run'],source/step['working-directory'],target)

    def test_bootstrap_and_published_archive_keep_history_boundary(self):
        full=self.cli(self.initial);self.assertEqual(0,full.returncode,full.stderr)
        self.assertNotEqual(0,self.cli(self.previous_archive).returncode)
        wrapper=self.wrapper(self.initial,self.previous_archive);self.assertEqual(0,wrapper.returncode,wrapper.stderr)

    def test_complete_policy_passes_product_publication_window_and_actual_adoption(self):
        for label,state in [('release-pr',self.candidate),('post-tag',self.published),('adoption-pr',self.adopted)]:
            output=self.output/label
            command=[sys.executable,str(state/'.github/scripts/workflows_policy_checks.py'),'--root',str(state),'--output',str(output)]
            for name in ('actionlint','kyverno','kubeconform','schemas'):command+=['--'+name,str(getattr(OPTIONS,name))]
            with (self.output/(label+'.log')).open('w') as log:r=subprocess.run(command,env=self.env,stdout=log,stderr=subprocess.STDOUT)
            self.assertEqual(0,r.returncode,'Complete policy failed for '+label+'; see '+str(output))
            result=json.loads((output/'result.json').read_text());self.assertTrue(result['passed']);self.results.append({'state':label,'checks':len(result['checks']),'passed':True})
        self.assertNotEqual(self.previous,self.candidate_tag)
        for name,content in self.consumer_bytes.items():self.assertEqual(content,(self.candidate/name).read_bytes())
        self.assertEqual({self.candidate_tag},{row['version'] for row in inventory(self.adopted) if row['owner']=='renovate'})

    def test_missing_markers_mixed_consumers_and_unowned_literals_fail(self):
        path=self.repo/'.github/workflows/native.yaml';original=path.read_text();path.write_text(original.replace(' # x-release-please-version','',1))
        self.assertNotEqual(0,self.cli(self.repo).returncode);path.write_text(original)
        path=self.repo/'.github/workflows/docs-pr.yaml';original=path.read_text();path.write_text(original.replace(self.previous,self.candidate_tag,1))
        self.assertNotEqual(0,self.cli(self.repo).returncode);path.write_text(original)
        (self.repo/'unowned-literal.txt').write_text(self.candidate_tag)
        result=subprocess.run([sys.executable,str(self.repo/'.github/scripts/workflow_release_literals.py'),str(self.repo)],env=self.env,capture_output=True,text=True)
        self.assertNotEqual(0,result.returncode);self.assertIn('unclassified',result.stdout)

    def test_fixture_preserves_version_only_changes_and_rejects_mixture_or_structure(self):
        sys.path.insert(0,str(ROOT/'.github/scripts'))
        from check_demo_workflows import TEMPLATE,fixture_errors
        path=self.repo/TEMPLATE;original=path.read_text();changed=original.replace(self.previous,self.candidate_tag);path.write_text(changed)
        self.assertEqual([],fixture_errors(self.repo))
        path.write_text(changed.replace(self.candidate_tag,self.previous,1))
        with self.assertRaises(ValueError):fixture_errors(self.repo)
        path.write_text(original+'\n# structural fixture drift\n');self.assertTrue(fixture_errors(self.repo))

    def test_published_wrappers_and_shallow_tools_use_exact_release_metadata(self):
        before=self.wrapper(self.repo,self.previous_archive);self.assertEqual(0,before.returncode,before.stderr)
        shallow=self.base/'shallow-contracts'
        subprocess.run(['git','clone','--quiet','--depth','1','--no-tags','--branch',self.previous,self.published.as_uri(),str(shallow)],check=True,capture_output=True)
        tools=self.tools(self.repo,shallow);self.assertEqual(0,tools.returncode,tools.stderr);self.assertEqual(3,tools.stdout.count('Build and deploy have independent contracts'))
        adopted=self.base/'adopted-metadata';shutil.copytree(self.adopted,adopted,symlinks=True)
        contract=adopted/'.github/workflows/service-deploy.yaml';original=contract.read_text()
        contract.write_text(original.replace('    inputs:\n','    inputs:\n      fixture-only: {type: string, required: true}\n',1))
                # Una verificación de todo el árbol cierra también el contexto del workflow privilegiado, incluidas las entradas.
        # El wrapper ejecutable debe seguir resolviendo el contrato publicado, no metadatos mutables.
        full=self.cli(adopted);self.assertNotEqual(0,full.returncode);self.assertIn('closed role contract',full.stderr)
        wrapper=self.wrapper(adopted,self.current_archive);self.assertEqual(0,wrapper.returncode,wrapper.stderr)
        contract.write_text(original)
        action=adopted/'.github/actions/resolve-service-build/action.yaml'
        action.write_text(action.read_text().replace('inputs:\n','inputs:\n  fixture-only: {description: Synthetic required input, required: true}\n',1))
        full=self.cli(adopted);self.assertEqual(0,full.returncode,full.stderr)
        wrapper=self.wrapper(adopted,self.current_archive);self.assertEqual(0,wrapper.returncode,wrapper.stderr)

    def test_extractor_coverage_product_ownership_and_noop_adoption_are_rejected(self):
        script=self.repo/'.github/scripts/release-version/test-workflow-adoption.cjs'
        def run(target=None):return subprocess.run(['node',str(script),str(self.repo),*([target] if target else [])],env=self.env,capture_output=True,text=True)
        tools=self.repo/'.github/workflows/tools-ci.yaml';original=tools.read_text();tools.write_text(original.replace('        repository: edgarrth/java-framework-custom\n',''))
        result=run();self.assertNotEqual(0,result.returncode);self.assertIn('Each pin needs one effective extractor',result.stderr);tools.write_text(original)
        path=self.repo/'renovate.json';original=path.read_text();content=json.loads(original);content['packageRules'][0]['enabled']=True;path.write_text(json.dumps(content))
        result=run();self.assertNotEqual(0,result.returncode);self.assertIn('must not own product',result.stderr);path.write_text(original)
        result=run(self.previous);self.assertNotEqual(0,result.returncode);self.assertIn('Adoption must change',result.stderr)

    @classmethod
    def tearDownClass(cls):
        after=fingerprint(ROOT)
        state={'passed':len(cls.results)==3 and cls.before==after,'sourceUnchanged':cls.before==after,'sourceFingerprint':after,'previous':cls.previous,'candidate':cls.candidate_tag,'consumerPinsChanged':cls.consumer_count,'states':cls.results,'networkUsed':False,'projectGitModified':False,'wrapperEnvironment':'Reused installed Python; venv/pip setup mocked with requirement/hash flags checked'}
        (cls.output/'result.json').write_text(json.dumps(state,indent=2)+'\n')
        if cls.before!=after:raise AssertionError('Source workspace changed during lifecycle regression')


def main():
    global OPTIONS, ROOT
    sys.path.insert(0,str(ROOT/'.github/scripts'))
    from workflows_policy_checks import arguments
    parser=argparse.ArgumentParser(description=__doc__);arguments(parser);OPTIONS=parser.parse_args()
    ROOT=OPTIONS.root.resolve()
    result=unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(ReleaseEntrypointTest))
    summary=OPTIONS.output/'result.json'
    state=json.loads(summary.read_text()) if summary.exists() else {}
    state.update(passed=result.wasSuccessful() and state.get('passed',False),tests=result.testsRun,failures=len(result.failures),errors=len(result.errors))
    summary.parent.mkdir(parents=True,exist_ok=True);summary.write_text(json.dumps(state,indent=2)+'\n')
    return int(not result.wasSuccessful())


if __name__=='__main__':raise SystemExit(main())

import copy
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch

import docs_prepare as prepare
import docs_publish_control as control
from render_docs_controls import render, WORKFLOW, ROOT

MIKE=str(Path(sys.executable).parent/'mike')
class DocumentationPublicationTest(unittest.TestCase):
    def setUp(self):
        self.temporary=tempfile.TemporaryDirectory(prefix='axiz-docs-contract-');self.addCleanup(self.temporary.cleanup)
        self.base=Path(self.temporary.name);self.repo=self.base/'source';self.repo.mkdir()
        control.git(self.repo,'init','--quiet');control.git(self.repo,'config','user.name','Axiz fixture');control.git(self.repo,'config','user.email','fixture@example.test')
        docs=self.repo/'axiz-docs';(docs/'docs').mkdir(parents=True)
        (docs/'mkdocs.yml').write_text('site_name: Fixture\nstrict: true\n')
        (docs/'docs/index.md').write_text('# Fixture\n\nDocumentation publication.\n')
        control.git(self.repo,'add','.');control.git(self.repo,'commit','--quiet','-m','fixture')
        self.sha=control.git(self.repo,'rev-parse','HEAD').decode().strip()
        self.source={'repository':'example/framework','sha':self.sha,'tag':'axiz-docs-v2.1.0','run_id':'123','producer_attempt':'1'}
    def publication(self,tag=None,attempt='1'):
        source={**self.source,'tag':tag or self.source['tag'],'producer_attempt':attempt}
        destination=self.base/('out-'+str(len(list(self.base.glob('out-*')))))
        if not control.git(self.repo,'for-each-ref','--format=%(objectname)','refs/tags/'+source['tag']).strip():
            control.git(self.repo,'tag',source['tag'])
        data=prepare.prepare(self.repo,destination,source,generate=False,mike=MIKE)
        tar=destination/'artifact.tar'
        # Execute the GNU tar packaging used by the pinned official uploader on Ubuntu,
        # with include-hidden-files=true (hence no dotfile exclusion).
        subprocess.run(['tar','--dereference','--hard-dereference','--directory',str(destination/'site'),
                        '-cf',str(tar),'--exclude=.git','--exclude=.github','.'],check=True)
        values=prepare.attest(destination,tar,'456')
        expected={**values,**{key:source[key] for key in ('repository','sha','tag','run_id')}}
        return destination,data,expected
    def import_and_advance(self,destination,data):
        target=self.base/('import-'+str(len(list(self.base.glob('import-*')))))
        control.import_bundle(destination/'state',target,data)
        control.git(self.repo,'fetch','--quiet',str(target),'refs/heads/gh-pages:refs/remotes/origin/gh-pages')
        return target
    def test_first_publication_has_exact_bundle_tar_and_no_source_mutation(self):
        destination,data,expected=self.publication()
        accepted=control.metadata(destination/'state',expected)
        control.verify_tar(destination/'artifact.tar',accepted)
        self.import_and_advance(destination,data)
        self.assertEqual(self.sha,control.git(self.repo,'rev-parse','HEAD').decode().strip())
        self.assertEqual('',control.git(self.repo,'status','--porcelain').decode())
        self.assertEqual('',data['base'])
        self.assertIn('latest/index.html',[row['path'] for row in data['files']])
    def test_latest_preserves_newer_minor_and_older_patch_is_rejected(self):
        a,data,_=self.publication('axiz-docs-v2.3.2');self.import_and_advance(a,data)
        b,second,_=self.publication('axiz-docs-v2.1.5');target=self.import_and_advance(b,second)
        versions=json.loads(control.git(target,'show','gh-pages:versions.json'))
        self.assertEqual('2.3',next(row['version'] for row in versions if 'latest' in row['aliases']))
        self.assertEqual({'2.1','2.3'},{row['version'] for row in versions})
        self.assertTrue(all(row['mode']=='100644' for row in second['files']))
        with self.assertRaisesRegex(ValueError,'older patch'):self.publication('axiz-docs-v2.3.1')
    def test_historical_pages_and_cname_survive(self):
        a,data,_=self.publication();target=self.import_and_advance(a,data)
        tree=self.base/'pages';control.git(self.repo,'clone','--quiet',str(target),str(tree))
        control.git(tree,'checkout','--quiet','gh-pages');control.git(tree,'config','user.name','Fixture');control.git(tree,'config','user.email','fixture@example.test')
        (tree/'CNAME').write_text('docs.example.test\n');(tree/'.nojekyll').touch();control.git(tree,'add','.');control.git(tree,'commit','--quiet','-m','site configuration')
        control.git(self.repo,'fetch','--quiet',str(tree),'refs/heads/gh-pages:refs/remotes/origin/gh-pages')
        b,second,_=self.publication('axiz-docs-v2.2.0')
        self.assertTrue((b/'site/CNAME').is_file());self.assertTrue((b/'site/.nojekyll').is_file())
        self.assertTrue((b/'site/2.1/index.html').is_file())
    def test_tamper_bundle_manifest_and_tar_fail_closed(self):
        destination,data,expected=self.publication()
        state=destination/'state';p=state/'publication.json';original=p.read_bytes();p.write_bytes(original+b' ')
        with self.assertRaisesRegex(ValueError,'hash'):control.metadata(state,expected)
        p.write_bytes(original)
        bundle=state/'site.bundle';bundle.write_bytes(bundle.read_bytes()+b'changed')
        with self.assertRaisesRegex(ValueError,'bundle'):control.import_bundle(state,self.base/'bad',data)
        tar=destination/'artifact.tar';tar.write_bytes(tar.read_bytes()+b'changed')
        with self.assertRaisesRegex(ValueError,'Pages'):control.verify_tar(tar,json.loads(original))
    def test_tar_rejects_links_traversal_duplicates_and_special_files(self):
        for name,kind,repeat in [('link',tarfile.SYMTYPE,False),('../escape',tarfile.REGTYPE,False),('file',tarfile.REGTYPE,True),('pipe',tarfile.FIFOTYPE,False)]:
            with self.subTest(name=name):
                path=self.base/'bad.tar'
                with tarfile.open(path,'w') as archive:
                    entry=tarfile.TarInfo(name);entry.type=kind;entry.linkname='target'
                    archive.addfile(entry,io.BytesIO())
                    if repeat:archive.addfile(entry,io.BytesIO())
                with self.assertRaises(ValueError):control.tar_files(path)
    def test_artifact_expired_absent_wrong_run_and_source_are_rejected(self):
        _,data,_=self.publication();data['pages'].update(id='456')
        item={'id':456,'name':data['pages']['name'],'expired':False,'expires_at':'2999-01-01T00:00:00Z','workflow_run':{'id':123,'head_sha':self.sha}}
        with patch.object(control,'api',return_value=item):control.verify_artifact(data,'fixture')
        for mutation in [{'expired':True},{'expires_at':'2000-01-01T00:00:00Z'},{'name':'github-pages'},{'workflow_run':{'id':999,'head_sha':self.sha}},{'workflow_run':{'id':123,'head_sha':'a'*40}},{'id':789}]:
            with patch.object(control,'api',return_value={**item,**mutation}),self.assertRaises(ValueError):control.verify_artifact(data,'fixture')
        with patch.object(control,'api',side_effect=ValueError('missing')),self.assertRaises(ValueError):control.verify_artifact(data,'fixture')
    def test_pages_retry_uses_original_attempt_and_regeneration_creates_new_identity(self):
        a,data,expected=self.publication()
        with patch.dict(os.environ,{'PUBLICATION_OUTPUTS':json.dumps(expected),'GITHUB_REPOSITORY':self.source['repository'],'GITHUB_SHA':self.sha,'GITHUB_REF_NAME':self.source['tag'],'GITHUB_RUN_ID':'123','GITHUB_RUN_ATTEMPT':'2'}):
            again=control.expected_environment();self.assertEqual('github-pages-123-1',again['pages_name'])
            control.metadata(a/'state',again)
        b,_,regenerated=self.publication(attempt='2')
        self.assertEqual('github-pages-123-2',regenerated['pages_name'])
        with self.assertRaises(ValueError):control.metadata(b/'state',expected)
    def test_cas_rejects_obsolete_base_and_regeneration_preserves_ancestry(self):
        a,data,_=self.publication();self.import_and_advance(a,data)
        b,second,_=self.publication('axiz-docs-v2.2.0');approved=self.import_and_advance(b,second)
        destination=self.base/'remote.git';control.git(self.repo,'init','--quiet','--bare',str(destination))
        control.git(approved,'push','--quiet',str(destination),'refs/heads/gh-pages:refs/heads/gh-pages')
        stale=self.base/'stale';control.import_bundle(a/'state',stale,data)
        with self.assertRaisesRegex(ValueError,'push'):
            control.git(stale,'push','--porcelain','--force-with-lease=refs/heads/gh-pages:',str(destination),'refs/heads/gh-pages:refs/heads/gh-pages')
        c,third,_=self.publication('axiz-docs-v2.4.0');regenerated=self.import_and_advance(c,third)
        control.git(regenerated,'push','--porcelain','--force-with-lease=refs/heads/gh-pages:'+second['commit'],str(destination),'refs/heads/gh-pages:refs/heads/gh-pages')
        self.assertEqual(third['commit'],control.git(destination,'rev-parse','refs/heads/gh-pages').decode().strip())
    def test_tag_ascii_canonical_and_source_identity(self):
        for tag in ['axiz-docs-v02.1.0','axiz-docs-v2.01.0','axiz-docs-v2.1.00','axiz-docs-v2.1.٠','axiz-docs-v2.1.0\n']:
            with self.subTest(tag=tag),self.assertRaises(ValueError):self.publication(tag)
    def test_workflow_is_exactly_generated_and_has_closed_write_boundaries(self):
        self.assertEqual((ROOT/WORKFLOW).read_text(),render())
        import yaml
        doc=yaml.safe_load(render());jobs=doc['jobs']
        self.assertIn('docs-publication',doc['concurrency']['group'])
        for name in ('publish','deploy-pages'):
            for step in jobs[name]['steps']:
                self.assertNotIn('actions/checkout@',step.get('uses',''))
                self.assertNotIn('pip install',step.get('run',''))
        self.assertEqual({'contents':'read'},jobs['prepare']['permissions'])
        self.assertEqual('${{ needs.prepare.outputs.pages_name }}',jobs['deploy-pages']['steps'][-1]['with']['artifact_name'])


class DocumentationControlExecutionTest(unittest.TestCase):
    setUp=DocumentationPublicationTest.setUp
    publication=DocumentationPublicationTest.publication
    # Exercise the actual entrypoint with API data fixtures. No production remote is contacted.
    def test_pages_retry_rechecks_metadata_tar_and_current_head(self):
        destination,data,expected=self.publication()
        artifact={'id':456,'name':expected['pages_name'],'expired':False,'expires_at':'2999-01-01T00:00:00Z','workflow_run':{'id':123,'head_sha':self.sha}}
        current=data['commit']
        def api(repository,path,token,**kwargs):
            return artifact if path.startswith('actions/artifacts/') else {'object':{'sha':current}}
        env={'PUBLICATION_OUTPUTS':json.dumps(expected),'GITHUB_REPOSITORY':self.source['repository'],'GITHUB_SHA':self.sha,'GITHUB_REF_NAME':self.source['tag'],'GITHUB_RUN_ID':'123','GITHUB_RUN_ATTEMPT':'2','DOCS_STATE_DIR':str(destination/'state'),'PAGES_DATA_DIR':str(destination),'DOCS_OPERATION':'verify-pages','GH_TOKEN':'fixture'}
        with patch.dict(os.environ,env),patch.object(control,'api',side_effect=api):
            control.operation()
            current='a'*40
            with self.assertRaisesRegex(ValueError,'obsolete'):control.operation()
            current=data['commit'];artifact['expired']=True
            with self.assertRaisesRegex(ValueError,'expired'):control.operation()
    def test_publisher_rejects_stale_base_before_attempting_push(self):
        destination,data,expected=self.publication()
        env={'PUBLICATION_OUTPUTS':json.dumps(expected),'GITHUB_REPOSITORY':self.source['repository'],'GITHUB_SHA':self.sha,'GITHUB_REF_NAME':self.source['tag'],'GITHUB_RUN_ID':'123','DOCS_STATE_DIR':str(destination/'state'),'DOCS_OPERATION':'publish','GH_TOKEN':'fixture'}
        real_git=control.git;pushes=[]
        def git(directory,*args,**kwargs):
            if args[0]=='push':pushes.append(args);raise AssertionError('A stale base must never push')
            return real_git(directory,*args,**kwargs)
        with patch.dict(os.environ,env),patch.object(control,'verify_artifact'),patch.object(control,'head',return_value='a'*40),patch.object(control,'git',side_effect=git):
            with self.assertRaisesRegex(ValueError,'base is obsolete'):control.operation()
        self.assertEqual([],pushes)


class DocumentationUpdateAuthorityTest(unittest.TestCase):
    def test_registry_update_keeps_generation_separate_from_privileged_role_approval(self):
        import shutil
        sys.path.insert(0,str(ROOT/'.github/actions/check-workflow-security'))
        from privileged_jobs import validate
        import yaml
        baseline=render();self.assertEqual([],validate(yaml.safe_load(baseline)))
        policy=ROOT/'.github/policies/privileged-jobs-v2.json';before=policy.read_bytes()
        with tempfile.TemporaryDirectory(prefix='axiz-docs-source-policy-') as folder:
            fixture=Path(folder)
            for relative in ['.github/policies/action-pins-v2.json','.github/policies/required-checks-v2.json','.github/scripts/docs_publish_control.py']:
                target=fixture/relative;target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(ROOT/relative,target)
            path=fixture/'.github/policies/action-pins-v2.json';data=json.loads(path.read_text())
            next(row for row in data['pins'] if row['action']=='actions/download-artifact')['sha']='a'*40
            path.write_text(json.dumps(data));candidate=render(fixture)
            self.assertNotEqual(baseline,candidate)
            self.assertTrue(any('closed role contract' in error for error in validate(yaml.safe_load(candidate))))
        self.assertEqual(before,policy.read_bytes())

if __name__=='__main__':unittest.main()

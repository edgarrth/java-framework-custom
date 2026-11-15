import copy
import json
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch

import release_action as action
ROOT=Path(__file__).resolve().parents[2]
class ReleaseActionParityTest(unittest.TestCase):
    def setUp(self):
        self.temporary=tempfile.TemporaryDirectory(prefix='axiz-action-parity-');self.addCleanup(self.temporary.cleanup)
        self.root=Path(self.temporary.name)/'source';self.cache=Path(self.temporary.name)/'cache'
        self.spec=json.loads((ROOT/action.POLICY).read_text())
        for relative in [action.POLICY,*action.WORKFLOWS,*[p/name for p in action.PACKAGES for name in ('package.json','package-lock.json')]]:
            target=self.root/relative;target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(ROOT/relative,target)
        # Tests synthesize the exact registered bytes from the prepared immutable tool input.
        import os
        cache=os.environ.get('AXIZ_RELEASE_ACTION_CACHE')
        if not cache:raise RuntimeError('Prepare AXIZ_RELEASE_ACTION_CACHE before offline policy tests')
        prepared=action.cache_directory(Path(cache),self.spec)
        self.files={name:(prepared/name).read_bytes() for name in ('package-lock.json','action.yml')}
    def prepare(self):
        with patch.object(action,'download',return_value=self.files):return action.prepare(self.root,self.cache)
    def test_preparation_and_offline_validation_have_distinct_network_boundaries(self):
        self.assertTrue(self.prepare()['passed'])
        with patch.object(action,'download',side_effect=AssertionError('Network forbidden')):
            result=action.verify(self.root,self.cache)
        self.assertFalse(result['networkUsed']);self.assertEqual(2,result['localPlanners'])
    def test_missing_cache_and_changed_cached_bytes_fail(self):
        with self.assertRaises(ValueError):action.verify(self.root,self.cache)
        self.prepare();directory=action.cache_directory(self.cache,self.spec)
        path=directory/'package-lock.json';path.write_bytes(path.read_bytes()+b' ')
        with self.assertRaisesRegex(ValueError,'checksum'):action.verify(self.root,self.cache)
    def test_download_is_verified_against_the_reviewed_registration(self):
        files=dict(self.files);files['action.yml']+=b'\n# changed\n'
        with patch.object(action,'download',return_value=files),self.assertRaisesRegex(ValueError,'registration'):action.prepare(self.root,self.cache)
    def test_stale_sha_or_unrelated_registration_is_rejected(self):
        for field,value in [('commit','a'*40),('repository','other/action'),('releasePlease','99.0.0'),('lockSha256','b'*64)]:
            path=self.root/action.POLICY;path.write_text(json.dumps({**self.spec,field:value}))
            with self.subTest(field=field),patch.object(action,'download',return_value=self.files),self.assertRaises(ValueError):action.prepare(self.root,self.cache)
    def test_each_planner_lock_and_manifest_is_checked_independently(self):
        self.prepare()
        for relative in action.PACKAGES:
            for filename in ('package.json','package-lock.json'):
                path=self.root/relative/filename;original=path.read_bytes();data=json.loads(original)
                if filename=='package.json':data['devDependencies']['release-please']='99.0.0'
                else:data['packages']['node_modules/release-please']['version']='99.0.0'
                path.write_text(json.dumps(data))
                with self.subTest(package=str(relative),file=filename),self.assertRaisesRegex(ValueError,'planners'):action.verify(self.root,self.cache)
                path.write_bytes(original)
    def test_metadata_contract_requires_node24(self):
        files=dict(self.files);files['action.yml']=files['action.yml'].replace(b'node24',b'node20')
        with self.assertRaisesRegex(ValueError,'Node 24'):action.registration(self.spec['commit'],files)
    def test_release_roles_cannot_drift_to_different_action_versions(self):
        path=self.root/action.WORKFLOWS[1]
        path.write_text(path.read_text().replace(self.spec['commit'],'a'*40))
        with self.assertRaisesRegex(ValueError,'same reviewed'):action.reference(self.root)

if __name__=='__main__':unittest.main()

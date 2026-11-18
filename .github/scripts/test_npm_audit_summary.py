import unittest
from npm_audit_summary import summarize
class NpmAdvisoryGraphTest(unittest.TestCase):
    def row(self,via):return {'via':via,'severity':'moderate','isDirect':False}
    def test_transitive_references_keep_path_and_count_unique_advisories(self):
        advisory={'source':123,'url':'https://github.com/advisories/GHSA-example','severity':'moderate'}
        result=summarize({'auditReportVersion':2,'vulnerabilities':{'a':self.row(['b']),'b':self.row(['c']),'c':self.row([advisory])}})
        self.assertEqual(3,result['affectedPackageCount']);self.assertEqual(1,result['uniqueAdvisoryCount'])
        self.assertEqual(['a','b','c'],result['packages'][0]['references'][0]['via'])
    def test_cycle_with_real_advisory_terminates_and_retains_provenance(self):
        advisory={'source':123,'url':'https://github.com/advisories/GHSA-example','severity':'moderate'}
        result=summarize({'vulnerabilities':{'a':self.row(['b']),'b':self.row(['a',advisory])}})
        self.assertEqual(1,result['uniqueAdvisoryCount']);self.assertTrue(all(row['references'] for row in result['packages']))
    def test_dangling_and_advisory_free_cycles_are_rejected(self):
        for data in [{'a':self.row(['missing'])},{'a':self.row(['b']),'b':self.row(['a'])}]:
            with self.assertRaises(ValueError):summarize({'vulnerabilities':data})

class CompleteNpmAuditReportTest(unittest.TestCase):
    def prepared(self,root):
        from npm_audit_summary import LOCKFILES,checksum
        import json
        advisory={'source':123,'name':'shared','title':'Shared advisory','url':'https://github.com/advisories/GHSA-example','severity':'moderate'}
        records=[]
        for relative in LOCKFILES:
            path=root/relative;path.parent.mkdir(parents=True,exist_ok=True);path.write_text('{}')
            records.append({'path':relative,'lockSha256':checksum(path),'audit':{'auditReportVersion':2,'vulnerabilities':{'shared':{'via':[dict(advisory)],'severity':'moderate','isDirect':False}}}})
        return {'schema':1,'preparedAt':'2026-11-14T00:00:00Z','locks':records}
    def test_three_locks_keep_provenance_and_deduplicate_advisories(self):
        import tempfile
        from pathlib import Path
        from npm_audit_summary import aggregate
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);data=self.prepared(root)
            data['locks'][1]['audit']['vulnerabilities']['shared']['via'][0]['source']=456
            result=aggregate(data,root)
            self.assertEqual(3,len(result['locks']));self.assertEqual(3,result['affectedLockPackagePairs'])
            self.assertEqual(1,result['uniqueAffectedPackageNames']);self.assertEqual(1,result['uniqueAdvisoryCount'])
            self.assertEqual([123,456],result['advisories'][0]['sourceIds']);self.assertFalse(result['passedMediumHighCriticalThreshold'])
    def test_missing_duplicate_extra_and_stale_lock_reports_are_rejected(self):
        import copy,tempfile
        from pathlib import Path
        from npm_audit_summary import aggregate
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);data=self.prepared(root)
            for rows in [data['locks'][:2],data['locks']+[data['locks'][0]],data['locks'][:2]+[data['locks'][0]]]:
                changed=copy.deepcopy(data);changed['locks']=rows
                with self.assertRaises(ValueError):aggregate(changed,root)
            (root/data['locks'][0]['path']).write_text('{"changed":true}')
            with self.assertRaisesRegex(ValueError,'current lock'):aggregate(data,root)
    def test_medium_high_critical_findings_in_any_lock_fail_the_threshold(self):
        import tempfile
        from pathlib import Path
        from npm_audit_summary import aggregate
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);data=self.prepared(root)
            for record in data['locks']:
                record['audit']['vulnerabilities']={}
            self.assertTrue(aggregate(data,root)['passedMediumHighCriticalThreshold'])
            for record in data['locks']:
                for severity in ('moderate','high','critical'):
                    with self.subTest(path=record['path'], severity=severity):
                        row={'via':[{'source':123,'url':'https://github.com/advisories/GHSA-example','severity':severity}],
                             'severity':severity,'isDirect':False}
                        record['audit']['vulnerabilities']={'shared':row}
                        self.assertFalse(aggregate(data,root)['passedMediumHighCriticalThreshold'])
                        row['severity']='low'
                        self.assertFalse(aggregate(data,root)['passedMediumHighCriticalThreshold'])
                        record['audit']['vulnerabilities']={}
    def test_collection_audits_all_locks_without_installation_or_lock_changes(self):
        import json,tempfile
        from pathlib import Path
        from types import SimpleNamespace
        from unittest.mock import patch
        from npm_audit_summary import collect,LOCKFILES
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder)/'source';data=self.prepared(root)
            (root/'.github/scripts/release-version/.node-version').write_text('24.22.3\n')
            responses=[SimpleNamespace(returncode=1,stdout=json.dumps(row['audit'])) for row in data['locks']]
            with patch('npm_audit_summary.subprocess.check_output',return_value='v24.22.3\n'),patch('npm_audit_summary.subprocess.run',side_effect=responses) as run:
                result=collect(root,Path(folder)/'audit.json')
            self.assertEqual(list(LOCKFILES),[row['path'] for row in result['locks']])
            for call in run.call_args_list:
                self.assertEqual(['npm','audit'],call.args[0][:2]);self.assertIn('--package-lock-only',call.args[0]);self.assertIn('--include=dev',call.args[0]);self.assertIn('--ignore-scripts',call.args[0])
    def test_collection_rejects_a_failed_audit_and_does_not_publish_partial_input(self):
        import json,tempfile
        from pathlib import Path
        from types import SimpleNamespace
        from unittest.mock import patch
        from npm_audit_summary import collect
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder)/'source';self.prepared(root);(root/'.github/scripts/release-version/.node-version').write_text('24.22.3\n')
            target=Path(folder)/'audit.json'
            with patch('npm_audit_summary.subprocess.check_output',return_value='v24.22.3\n'),patch('npm_audit_summary.subprocess.run',return_value=SimpleNamespace(returncode=1,stdout=json.dumps({'error':{'code':'REGISTRY_UNAVAILABLE'}}))):
                with self.assertRaises(ValueError):collect(root,target)
            self.assertFalse(target.exists())

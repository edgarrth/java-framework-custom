import json
from pathlib import Path
import tempfile
import unittest
from check_action_pins import check,POLICY
class ActionPinPolicyTest(unittest.TestCase):
    def test_complete_inventory_rejects_unannotated_mismatched_and_unused_registration(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);(root/'.github/workflows').mkdir(parents=True);(root/POLICY).parent.mkdir()
            file=root/'.github/workflows/example.yaml';sha='a'*40
            pin={'action':'actions/example','repository':'actions/example','sha':sha,'tag':'v1.2.3'}
            (root/POLICY).write_text(json.dumps({'schema':1,'pins':[pin]}))
            text='jobs:\n  check:\n    steps:\n      - uses: actions/example@'+sha+' # v1.2.3\n'
            file.write_text(text);self.assertTrue(check(root)['passed'])
            for bad in [text.replace(' # v1.2.3',''),text.replace('# v1.2.3','# v1.2.4'),text+text.replace('# v1.2.3','# unsupported'),text.replace(sha,'main'),'jobs: {}\n']:
                file.write_text(bad);self.assertFalse(check(root)['passed'])

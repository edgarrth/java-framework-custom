import json
from pathlib import Path
import subprocess
import tempfile
import unittest

from workflow_release_literals import POLICY, check


class WorkflowReleaseLiteralTest(unittest.TestCase):
    def test_unclassified_changed_and_unused_declarations_fail(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);subprocess.run(['git','init','--quiet',str(root)],check=True,capture_output=True)
            (root/POLICY).parent.mkdir(parents=True)
            data={'schema':1,'exceptions':[]};(root/POLICY).write_text(json.dumps(data))
            value='axiz-workflows-v'+'2.8.7';path=root/'fixture.txt';path.write_text(value)
            self.assertTrue(check(root))
            entry={'path':'fixture.txt','value':value,'count':1,'purpose':'Independent test data'}
            data['exceptions']=[entry];(root/POLICY).write_text(json.dumps(data));self.assertEqual([],check(root))
            path.write_text(value+' '+value);self.assertTrue(check(root))
            path.unlink();self.assertTrue(check(root))
            data['exceptions']=[entry,entry];(root/POLICY).write_text(json.dumps(data));self.assertTrue(check(root))

import sys
from pathlib import Path
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'actions/check-workflow-security'))
from internal_contracts import TAG
import workflow_pins
import check_pipeline_contracts
import check_workflow_security
from workflow_release_normalization import normalize
class CanonicalWorkflowTagTest(unittest.TestCase):
    def test_all_validators_share_ascii_canonical_full_value_grammar(self):
        for module in (workflow_pins,check_pipeline_contracts,check_workflow_security):self.assertIs(TAG,module.TAG)
        for value in ['axiz-workflows-v2.0.0','axiz-workflows-v2.10.200']:self.assertIsNotNone(TAG.fullmatch(value))
        for value in ['axiz-workflows-v02.0.0','axiz-workflows-v2.01.0','axiz-workflows-v2.0.00','axiz-workflows-v2.١.0','axiz-workflows-v2.1.０','axiz-workflows-v2.1.0\n','prefixaxiz-workflows-v2.1.0','axiz-workflows-v1.0.0']:
            with self.subTest(value=value):self.assertIsNone(TAG.fullmatch(value))

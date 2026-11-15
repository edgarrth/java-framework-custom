import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import test_gitops_policies


class PolicyRunnerTest(unittest.TestCase):
    def test_unverified_executable_is_rejected_before_execution(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            binary = root / 'kyverno'
            binary.write_text('not the pinned executable')
            arguments = ['test_gitops_policies.py', '--kyverno', str(binary), '--output', str(root / 'results')]
            with patch('sys.argv', arguments), patch.object(test_gitops_policies.subprocess, 'check_output') as run:
                with self.assertRaisesRegex(ValueError, 'binary checksum'):
                    test_gitops_policies.main()
                run.assert_not_called()
            self.assertFalse((root / 'results').exists())

import json
import subprocess
import tempfile
import unittest
from pathlib import Path

from protected import POLICY, from_base
from test_evaluate import policy


class ProtectedHistoryTest(unittest.TestCase):
    def test_head_cannot_approve_itself_and_deletion_cannot_restart_history(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)

            def git(*args):
                return subprocess.check_output(["git", "-C", str(root), "-c", "user.name=Policy Test",
                                                "-c", "user.email=policy-test@example.invalid", *args], text=True).strip()

            git("init", "--quiet")
            path = root / POLICY
            path.parent.mkdir(parents=True)
            original = policy()
            path.write_text(json.dumps(original))
            git("add", POLICY)
            git("commit", "--quiet", "-m", "Synthetic protected inventory")
            base = git("rev-parse", "HEAD")
            proposed = policy()
            proposed["findings"][0]["status"] = "resolved"
            path.write_text(json.dumps(proposed))
            git("add", POLICY)
            git("commit", "--quiet", "-m", "Synthetic unapproved head")
            accepted, history = from_base(root, base)
            self.assertEqual(accepted, original)
            self.assertEqual(len(history), 1)
            self.assertEqual(accepted["findings"][0]["status"], "open")
            proposed["findings"] = []
            path.write_text(json.dumps(proposed))
            git("add", POLICY)
            git("commit", "--quiet", "-m", "Synthetic deletion attempt")
            with self.assertRaisesRegex(ValueError, "cannot be deleted"):
                from_base(root, git("rev-parse", "HEAD"))

    def test_missing_base_inventory_and_nonimmutable_reference_fail_closed(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(ValueError):
                from_base(Path(tmp), "main")
            with self.assertRaises(ValueError):
                from_base(Path(tmp), "1" * 40)


if __name__ == "__main__":
    unittest.main()

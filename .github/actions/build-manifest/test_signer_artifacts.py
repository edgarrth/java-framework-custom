import copy
from pathlib import Path
import stat
import tempfile
import unittest
import zipfile
import warnings

from signer_artifacts import binding, extract


class SignerArtifactTest(unittest.TestCase):
    def test_artifact_is_bound_to_current_sha_run_and_producer_interval(self):
        receipt = {"run_id": 123, "source": {"sha": "a" * 40}}
        item = {"id": 456, "name": "unsigned-2", "expired": False, "size_in_bytes": 42,
                "digest": "sha256:" + "b" * 64, "created_at": "2026-11-13T01:01:00Z",
                "workflow_run": {"id": 123, "head_sha": "a" * 40}}
        def verify(value):
            return binding(value, receipt, "unsigned-2", "2026-11-13T01:00:00Z", "2026-11-13T01:02:00Z")
        self.assertEqual(456, verify(item)["id"])
        for key, value in [("name", "unsigned-1"), ("expired", True), ("digest", "invalid"),
                           ("created_at", "2026-11-13T00:59:00Z"), ("created_at", None), ("workflow_run", None),
                           ("workflow_run", {"id": 122, "head_sha": "a" * 40})]:
            wrong = copy.deepcopy(item)
            wrong[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                verify(wrong)

    def test_rejects_duplicate_names_and_contradictory_entry_types_before_extraction(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with warnings.catch_warnings():
                warnings.simplefilter("ignore", UserWarning)
                with zipfile.ZipFile(root / "input.zip", "w") as archive:
                    archive.writestr("artifact/file", "first")
                    archive.writestr("artifact/file", "second")
            with self.assertRaises(ValueError):
                extract(root / "input.zip", root / "data")
            self.assertFalse((root / "data").exists())
        for name, mode in [("file", stat.S_IFDIR), ("directory/", stat.S_IFREG)]:
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                with zipfile.ZipFile(root / "input.zip", "w") as archive:
                    entry = zipfile.ZipInfo(name)
                    entry.external_attr = (mode | 0o777) << 16
                    archive.writestr(entry, "payload")
                with self.assertRaises(ValueError):
                    extract(root / "input.zip", root / "data")
                self.assertFalse((root / "data").exists())

    def test_rejects_traversal_symlinks_duplicate_files_and_never_makes_data_executable(self):
        for name, mode in [("../escape", stat.S_IFREG), ("/escape", stat.S_IFREG),
                           ("script.py", stat.S_IFLNK), ("a\\escape", stat.S_IFREG)]:
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                archive = root / "input.zip"
                with zipfile.ZipFile(archive, "w") as output:
                    entry = zipfile.ZipInfo(name)
                    entry.external_attr = (mode | 0o777) << 16
                    output.writestr(entry, "payload")
                with self.assertRaises(ValueError):
                    extract(archive, root / "data")
                self.assertFalse((root / "data").exists())
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with zipfile.ZipFile(root / "input.zip", "w") as archive:
                archive.writestr("artifact/script.py", "data only")
            extract(root / "input.zip", root / "data")
            self.assertEqual(0, (root / "data/artifact/script.py").stat().st_mode & 0o111)

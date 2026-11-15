from pathlib import Path
import shutil
import tempfile
import unittest
from build_renovate_presets import ROOT, build


class PublishedPresetTest(unittest.TestCase):
    def test_published_variants_match_their_single_source(self):
        self.assertEqual([],build(ROOT))

    def test_a_changed_published_variant_requires_regeneration(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            for relative in ('.github/renovate','.github/scripts/renovate-presets'):
                shutil.copytree(ROOT/relative,root/relative)
            file=root/'.github/renovate/service.json';file.write_text('{}')
            self.assertTrue(build(root))
            self.assertEqual([],build(root,write=True))
            self.assertEqual([],build(root))

from pathlib import Path
import tempfile
import unittest

from check_documentation import validate_links


class DocumentationLinksTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        (self.root/'docs').mkdir()
        (self.root/'docs/guide.md').write_text('# Guide\n')
        (self.root/'docs/image.svg').write_text('<svg/>')

    def validate(self, text):
        (self.root/'README.md').write_text(text)
        return validate_links(self.root, ['README.md'])

    def test_relative_files_directories_images_and_fragments(self):
        self.assertEqual([], self.validate('[Guide](docs/guide.md#section) [Folder](docs/) ![Image](docs/image.svg) [Here](#section)'))

    def test_missing_reference_style_link_is_checked(self):
        errors = self.validate('[Guide][ref]\n\n[ref]: docs/missing.md\n')
        self.assertEqual(1, len(errors))
        self.assertEqual('docs/missing.md', errors[0]['link'])

    def test_code_examples_and_remote_urls_do_not_require_local_files(self):
        self.assertEqual([], self.validate('`[Example](missing.md)`\n\n```markdown\n[Example](missing.md)\n```\n\n[Web](https://example.test/guide)'))

    def test_encoded_filenames_and_repository_root_links(self):
        (self.root/'docs/my guide.md').write_text('# Guide')
        self.assertEqual([], self.validate('[Guide](docs/my%20guide.md) [Root](/docs/guide.md)'))

    def test_links_and_symlinks_cannot_escape_the_source_tree(self):
        self.assertTrue(self.validate('[Outside](../outside.md)'))
        (self.root/'outside.md').symlink_to('/etc/hosts')
        self.assertTrue(self.validate('[Outside](outside.md)'))
        self.assertTrue(validate_links(self.root, ['outside.md']))

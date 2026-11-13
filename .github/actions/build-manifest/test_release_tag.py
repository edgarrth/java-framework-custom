import unittest

from release_tag import MONOREPO, release_tag


class ReleaseTagTest(unittest.TestCase):
    def data(self, repository, directory):
        return {"version": "2.0.0", "source": {"repository": repository},
                "parameters": {"project_directory": directory}}

    def test_monorepo_components_have_independent_tag_names(self):
        for directory in ("axiz-framework", "axiz-tools"):
            self.assertEqual(directory + "-v2.0.0", release_tag(self.data(MONOREPO, directory)))
        for directory in (".", "axiz-demo", "../axiz-framework", "axiz-tools/axiz-cli"):
            with self.subTest(directory=directory), self.assertRaises(ValueError):
                release_tag(self.data(MONOREPO, directory))

    def test_consumer_repository_uses_its_own_release_convention(self):
        self.assertEqual("v2.0.0", release_tag(self.data("example/consumer-library", ".")))

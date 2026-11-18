import json
from pathlib import Path
import re
import unittest
import yaml
ROOT=Path(__file__).resolve().parents[2]
class LockedToolchainsTest(unittest.TestCase):
    def test_python_tool_locks_have_exact_versions_and_hashes_for_the_complete_closures(self):
        for path,tool,version in [('axiz-docs/requirements.txt','mkdocs','1.6.1'),('.github/requirements/semgrep.txt','semgrep','1.180.0')]:
            text=(ROOT/path).read_text();entries=re.findall(r'^([A-Za-z0-9_.-]+)==([^\s]+) \\\n((?:    --hash=sha256:[a-f0-9]{64}(?: \\)?\n)+)',text,re.M)
            self.assertGreater(len(entries),20)
            self.assertEqual(len(entries),len(re.findall(r'^[A-Za-z0-9_.-]+==',text,re.M)))
            self.assertIn((tool,version),[(name.lower(),v) for name,v,_ in entries])
    def test_markdown_validator_has_exact_lock_integrity_and_shares_the_node_pin(self):
        path=ROOT/'.github/scripts/docs-tools';manifest=json.loads((path/'package.json').read_text());lock=json.loads((path/'package-lock.json').read_text())
        self.assertTrue(manifest['private']);self.assertEqual({'markdownlint':'0.42.1','yaml':'2.9.1'},manifest['devDependencies'])
        self.assertEqual(manifest['devDependencies'],lock['packages']['']['devDependencies'])
        self.assertFalse(any(name.endswith('/picomatch') for name in lock['packages']))
        self.assertTrue(all(row.get('integrity') for name,row in lock['packages'].items() if name))
        docs=yaml.safe_load((ROOT/'.github/workflows/docs-docs.yaml').read_text())
        node=next(s for s in docs['jobs']['build']['steps'] if s.get('uses','').startswith('actions/setup-node@'))
        self.assertEqual('.github/scripts/release-version/.node-version',node['with']['node-version-file'])
        framework=yaml.safe_load((ROOT/'.github/workflows/framework-ci.yaml').read_text())
        node=next(s for s in framework['jobs']['samples']['steps'] if s.get('uses','').startswith('actions/setup-node@'))
        self.assertEqual(node['with']['node-version-file'],docs['jobs']['build']['steps'][3]['with']['node-version-file'])

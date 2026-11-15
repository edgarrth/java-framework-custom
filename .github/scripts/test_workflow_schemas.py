import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from prepare_workflow_schemas import verify


class OfflineSchemaTest(unittest.TestCase):
    def test_checksum_and_path_escape_fail_before_schema_validation(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);(root/'.github/gitops').mkdir(parents=True)
            schema=root/'schemas/fixture.json';schema.parent.mkdir();schema.write_text('{}')
            config={'schema':1,'files':[{'path':'fixture.json','sha256':hashlib.sha256(schema.read_bytes()).hexdigest()}]}
            path=root/'.github/gitops/crd-schemas.json';path.write_text(json.dumps(config));self.assertEqual(config,verify(root,schema.parent))
            schema.write_text('{"tampered":true}')
            with self.assertRaises(ValueError):verify(root,schema.parent)
            config['files'][0]['path']='../outside.json';path.write_text(json.dumps(config))
            with self.assertRaises(ValueError):verify(root,schema.parent)

import copy
import hashlib
import json
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from unittest.mock import patch

import deploy_record
import jsonschema
import promote_maven
import release_manifest
from test_manifest import fixture


class PromotionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.files, self.uploads = {}, []
        self.reject = None
        self.redirect = False
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                if owner.redirect:
                    self.send_response(302)
                    self.send_header("Location", "http://127.0.0.1:1/credentials-must-not-be-forwarded")
                    self.end_headers()
                    return
                data = owner.files.get(self.path)
                self.send_response(200 if data is not None else 404)
                self.end_headers()
                if data is not None:
                    self.wfile.write(data)

            def do_PUT(self):
                data = self.rfile.read(int(self.headers["Content-Length"]))
                if owner.reject == self.path:
                    self.send_response(503)
                elif self.path in owner.files:
                    self.send_response(409)
                else:
                    owner.files[self.path] = data
                    owner.uploads.append(self.path)
                    self.send_response(201)
                self.end_headers()

            def log_message(self, *args):
                pass

        self.server = HTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self.close)
        self.client = promote_maven.Nexus(f"http://127.0.0.1:{self.server.server_port}", "test", "test", allow_local_test=True)
        self.data = copy.deepcopy(fixture())
        first = self.data["artifacts"]["maven"][0]
        second = dict(first, extension="pom", path=first["path"].removesuffix("jar") + "pom")
        self.data["artifacts"]["maven"].append(second)
        for i, item in enumerate(self.data["artifacts"]["maven"]):
            payload = f"actual candidate bytes {i}".encode()
            item["sha256"] = hashlib.sha256(payload).hexdigest()
            file = self.root / "maven" / item["path"]
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_bytes(payload)

    def close(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)

    def url(self, item):
        return "/repository/maven-releases/" + item["path"]

    def test_promotes_exact_bytes_and_redeployment_is_idempotent(self):
        self.assertEqual(2, promote_maven.promote(self.data, self.root, self.client))
        self.assertEqual(0, promote_maven.promote(self.data, self.root, self.client))
        self.assertEqual(2, len(self.uploads))
        for item in self.data["artifacts"]["maven"]:
            self.assertEqual(item["sha256"], hashlib.sha256(self.files[self.url(item)]).hexdigest())

    def test_conflict_in_later_file_aborts_before_any_write(self):
        second = self.data["artifacts"]["maven"][1]
        self.files[self.url(second)] = b"another candidate with the same coordinates"
        with self.assertRaisesRegex(ValueError, "different bytes"):
            promote_maven.promote(self.data, self.root, self.client)
        self.assertEqual([], self.uploads)

    def test_corrupt_later_local_file_aborts_before_any_write(self):
        second = self.data["artifacts"]["maven"][1]
        (self.root / "maven" / second["path"]).write_bytes(b"modified after verification")
        with self.assertRaisesRegex(ValueError, "staging changed"):
            promote_maven.promote(self.data, self.root, self.client)
        self.assertEqual([], self.uploads)

    def test_interrupted_promotion_can_resume_without_replacing_an_uploaded_file(self):
        self.reject = self.url(self.data["artifacts"]["maven"][1])
        with self.assertRaisesRegex(ValueError, "503"):
            promote_maven.promote(self.data, self.root, self.client)
        self.assertEqual(1, len(self.uploads))
        self.reject = None
        self.assertEqual(1, promote_maven.promote(self.data, self.root, self.client))
        self.assertEqual(2, len(self.uploads))

    def test_rejects_redirects_and_plaintext_production_connections(self):
        self.redirect = True
        with self.assertRaisesRegex(ValueError, "redirects"):
            promote_maven.promote(self.data, self.root, self.client)
        self.assertEqual([], self.uploads)
        with self.assertRaisesRegex(ValueError, "HTTPS"):
            promote_maven.Nexus("http://nexus.example", "test", "test")


class DeployRecordTest(unittest.TestCase):
    def test_uses_verified_build_source_and_real_environment_approvals(self):
        history = [{"state": "approved", "user": {"login": "reviewer"}, "environments": [{"name": "prod"}]}]
        approved = deploy_record.approvals(history, "prod", "example/payments", 321)
        record = deploy_record.record(fixture(), "c" * 64, "prod", "https://nexus.example/repository/maven-releases",
                                      321, "initial-deployment", [{"name": "nexus-credentials", "ref": "environment:prod/NEXUS_PUBLISH"}], approved, "succeeded")
        self.assertEqual("a" * 40, record["source"]["sha"])
        self.assertEqual(321, record["run_id"])
        self.assertEqual("reviewer", record["approvals"][0]["actor"])
        with self.assertRaises(ValueError):
            deploy_record.approvals(history, "qa", "example/payments", 321)
        with self.assertRaises(ValueError):
            deploy_record.approvals([], "prod", "example/payments", 321)
        with self.assertRaises(jsonschema.ValidationError):
            deploy_record.record(fixture(), "c" * 64, "prod", "destination", 321, "not-a-manifest",
                                 [{"name": "credential", "ref": "environment:prod/CREDENTIAL"}], approved, "succeeded")


class ReleaseManifestTest(unittest.TestCase):
    def test_draft_preflight_does_not_write_and_partial_uploads_can_resume(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            data = fixture()
            data["source"]["repository"] = "axiz/framework"
            payload = json.dumps(data).encode()
            (root / "build-manifest.json").write_bytes(payload)
            (root / "build-manifest.sigstore.json").write_bytes(b"signature")
            expected = hashlib.sha256(payload).hexdigest()
            release = {"tag_name": "v2.0.0", "draft": True, "assets": [{"name": f"build-manifest-{expected}.json", "id": 1}]}
            with patch.object(release_manifest, "api", return_value=release), patch.object(release_manifest, "asset", return_value=payload), patch.object(release_manifest.subprocess, "run") as run:
                self.assertTrue(release_manifest.retain("axiz/framework", "v2.0.0", expected, root))
                run.assert_not_called()
                release_manifest.retain("axiz/framework", "v2.0.0", expected, root, publish=True)
                uploads = [call.args[0] for call in run.call_args_list if "upload" in call.args[0]]
                self.assertEqual(1, len(uploads))
                self.assertTrue(any(path.endswith(f"build-manifest-{expected}.sigstore.json") for path in uploads[0]))
                self.assertNotIn("--clobber", uploads[0])

    def test_published_missing_or_conflicting_assets_fail_without_any_write(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            data = fixture()
            data["source"]["repository"] = "axiz/framework"
            payload = json.dumps(data).encode()
            (root / "build-manifest.json").write_bytes(payload)
            (root / "build-manifest.sigstore.json").write_bytes(b"signature")
            expected = hashlib.sha256(payload).hexdigest()
            release = {"tag_name": "v2.0.0", "draft": False, "assets": []}
            with patch.object(release_manifest, "api", return_value=release), patch.object(release_manifest.subprocess, "run") as run:
                with self.assertRaisesRegex(ValueError, "lacks"):
                    release_manifest.retain("axiz/framework", "v2.0.0", expected, root, publish=True)
                run.assert_not_called()
            release["draft"] = True
            release["assets"] = [{"name": f"build-manifest-{expected}.json", "id": 1}]
            with patch.object(release_manifest, "api", return_value=release), patch.object(release_manifest, "asset", return_value=b"different"), patch.object(release_manifest.subprocess, "run") as run:
                with self.assertRaisesRegex(ValueError, "cannot be overwritten"):
                    release_manifest.retain("axiz/framework", "v2.0.0", expected, root, publish=True)
                run.assert_not_called()


if __name__ == "__main__":
    unittest.main()

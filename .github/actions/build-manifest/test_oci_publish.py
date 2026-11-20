import hashlib
import io
import json
from pathlib import Path
import tarfile
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import parse_qs, urlsplit

import oci_publish as oci


def image_entries(platform="linux/amd64"):
    layer = b"opaque compressed layer bytes"
    config = json.dumps(dict(zip(("os", "architecture"), platform.split("/")))).encode()
    def descriptor(content, media):
        return {"digest": oci.digest(content), "size": len(content), "mediaType": media}
    content = json.dumps({"schemaVersion": 2, "mediaType": oci.MANIFEST,
        "config": descriptor(config, oci.CONFIG),
        "layers": [descriptor(layer, "application/vnd.oci.image.layer.v1.tar+gzip")]}).encode()
    entries = {"oci-layout": b'{"imageLayoutVersion":"1.0.0"}',
               "index.json": json.dumps({"schemaVersion": 2, "manifests": [descriptor(content, oci.MANIFEST)]}).encode()}
    entries.update({"blobs/sha256/" + oci.digest(value)[7:]: value for value in (config, layer, content)})
    return entries


def write_archive(path, entries):
    with tarfile.open(path, "w") as archive:
        for name, content in entries.items():
            entry = tarfile.TarInfo(name)
            entry.size = len(content)
            archive.addfile(entry, io.BytesIO(content))


class ArchiveTest(unittest.TestCase):
    def test_excessive_json_nesting_is_a_validation_error(self):
        with self.assertRaisesRegex(ValueError, "nesting"):
            oci.document(b'{"nested":' + b'[' * 2000 + b'0' + b']' * 2000 + b'}')

    def test_preserves_manifest_and_layer_bytes_and_rejects_wrong_platform(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write_archive(root / "image.tar", image_entries())
            descriptor, file, blobs = oci.archive(root / "image.tar", root / "valid", "linux/amd64")
            self.assertEqual(descriptor["digest"], oci.digest(file.read_bytes()))
            self.assertEqual(2, len(blobs))
            with self.assertRaisesRegex(ValueError, "platform"):
                oci.archive(root / "image.tar", root / "wrong", "linux/arm64")

    def test_tampering_traversal_foreign_content_and_duplicate_keys_are_rejected(self):
        for kind in ("tamper", "escape", "extra", "json"):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                entries = image_entries()
                if kind == "tamper":
                    name = next(n for n in entries if n.startswith("blobs/"))
                    entries[name] += b"modified"
                elif kind == "escape":
                    entries["../outside"] = b"escape"
                elif kind == "extra":
                    content = b"unreferenced"
                    entries["blobs/sha256/" + oci.digest(content)[7:]] = content
                else:
                    entries["index.json"] = b'{"schemaVersion":2,"schemaVersion":2,"manifests":[]}'
                write_archive(root / "image.tar", entries)
                with self.assertRaises(ValueError):
                    oci.archive(root / "image.tar", root / "output", "linux/amd64")
                self.assertFalse((root / "outside").exists())

    def test_invalid_json_types_are_validation_failures(self):
        cases = [('manifests', None), ('manifests', 1), ('manifests', [False]),
                 ('os', []), ('architecture', 1), ('layers', [None]), ('layers', ['layer']),
                 ('layer_media', {}), ('digest', []), ('digest', None)]
        for field, value in cases:
            with self.subTest(field=field, value=value), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                entries = image_entries()
                index = json.loads(entries['index.json'])
                original_manifest = 'blobs/sha256/' + index['manifests'][0]['digest'][7:]
                metadata = json.loads(entries.pop(original_manifest))
                if field in ('os', 'architecture'):
                    original_config = 'blobs/sha256/' + metadata['config']['digest'][7:]
                    config = json.loads(entries.pop(original_config))
                    config[field] = value
                    encoded = json.dumps(config).encode()
                    metadata['config'].update(digest=oci.digest(encoded), size=len(encoded))
                    entries['blobs/sha256/' + oci.digest(encoded)[7:]] = encoded
                elif field == 'layers': metadata['layers'] = value
                elif field == 'layer_media': metadata['layers'][0]['mediaType'] = value
                encoded = json.dumps(metadata).encode()
                entries['blobs/sha256/' + oci.digest(encoded)[7:]] = encoded
                index['manifests'][0].update(digest=oci.digest(encoded), size=len(encoded))
                if field == 'manifests': index['manifests'] = value
                elif field == 'digest': index['manifests'][0]['digest'] = value
                entries['index.json'] = json.dumps(index).encode()
                write_archive(root / 'image.tar', entries)
                with self.assertRaises(ValueError):
                    oci.archive(root / 'image.tar', root / 'output', 'linux/amd64')

    def test_archive_links_are_never_followed(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            with tarfile.open(root / "image.tar", "w") as archive:
                entry = tarfile.TarInfo("oci-layout")
                entry.type = tarfile.SYMTYPE
                entry.linkname = "/etc/passwd"
                archive.addfile(entry)
            with self.assertRaisesRegex(ValueError, "entry"):
                oci.archive(root / "image.tar", root / "output", "linux/amd64")


class RegistryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.files, self.writes = {}, []
        self.escape = False
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def reply(self, status, content=b"", **headers):
                self.send_response(status)
                for key, value in headers.items():
                    self.send_header(key, value)
                self.end_headers()
                self.wfile.write(content)

            def authenticated(self):
                if self.headers.get("Authorization") != "Bearer scoped-token":
                    self.reply(401)
                    return False
                return True

            def do_GET(self):
                if self.path.startswith("/token?"):
                    owner.assertTrue(self.headers.get("Authorization", "").startswith("Basic "))
                    owner.assertEqual(["repository:example/builds/service:pull,push"],
                                      parse_qs(urlsplit(self.path).query)["scope"])
                    self.reply(200, b'{"token":"scoped-token"}')
                elif self.authenticated():
                    value = owner.files.get(self.path)
                    self.reply(404 if value is None else 200, value or b"")

            def do_POST(self):
                if self.authenticated():
                    location = "http://127.0.0.1:1/exfiltrate" if owner.escape else "/v2/example/builds/service/blobs/uploads/fixture"
                    self.reply(202, Location=location)

            def do_PUT(self):
                if not self.authenticated():
                    return
                content = self.rfile.read(int(self.headers["Content-Length"]))
                wanted = oci.digest(content)
                if "/blobs/uploads/" in self.path:
                    owner.assertEqual([wanted], parse_qs(urlsplit(self.path).query)["digest"])
                owner.files[self.path] = content
                owner.writes.append(self.path)
                self.reply(201, **{"Docker-Content-Digest": wanted})

        self.server = HTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self.close)
        self.client = oci.Registry("example/builds/service", "fixture", "fixture",
                                   origin=f"http://127.0.0.1:{self.server.server_port}", local_test=True)

    def close(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(5)

    def images(self, platforms):
        result = []
        for platform in platforms:
            name = platform.replace("/", "-")
            path = self.root / (name + ".tar")
            write_archive(path, image_entries(platform))
            result.append(oci.archive(path, self.root / name, platform))
        return result

    def test_actual_http_preserves_single_and_multi_platform_manifests(self):
        images = self.images(["linux/amd64", "linux/arm64"])
        single = oci.publish(self.client, images[:1], "single")
        self.assertEqual(images[0][0]["digest"], single)
        combined = oci.publish(self.client, images, "multi")
        content = self.files["/v2/example/builds/service/manifests/multi"]
        self.assertEqual(combined, oci.digest(content))
        self.assertEqual([image[0] for image in images], json.loads(content)["manifests"])
        before = len(self.writes)
        self.assertEqual(combined, self.client.put_manifest("multi", content, oci.INDEX))
        self.assertEqual(before, len(self.writes))
        with self.assertRaisesRegex(ValueError, "different bytes"):
            self.client.put_manifest("multi", b"modified", oci.INDEX)

    def test_upload_location_cannot_receive_credentials_at_another_origin(self):
        self.escape = True
        with self.assertRaisesRegex(ValueError, "escaped"):
            oci.publish(self.client, self.images(["linux/amd64"]), "image")
        self.assertEqual([], self.writes)

    def test_mutated_blob_is_rejected_before_upload(self):
        images = self.images(["linux/amd64"])
        blob = images[0][2][0]
        blob.chmod(0o600)
        blob.write_bytes(b"changed")
        with self.assertRaisesRegex(ValueError, "changed"):
            oci.publish(self.client, images, "image")
        self.assertEqual([], self.writes)


if __name__ == "__main__":
    unittest.main()

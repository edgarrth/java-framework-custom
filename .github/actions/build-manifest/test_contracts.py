import tempfile
import unittest
from pathlib import Path

import check_render
import contracts


class ContractsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / ".axiz").mkdir()
        (self.root / "src/main/asyncapi").mkdir(parents=True)
        self.path = self.root / "src/main/asyncapi/asyncapi.yaml"
        self.path.write_text("asyncapi: 3.0.0\ninfo: {title: Payment, version: 1.0.0}\nchannels: {}\n")

    def test_asyncapi_snapshot_comparison_is_semantic_and_fails_on_unsynchronized_changes(self):
        snapshot = self.root / ".axiz/asyncapi.snapshot.yaml"
        snapshot.write_text("channels: {}\ninfo:\n  version: 1.0.0\n  title: Payment\nasyncapi: 3.0.0\n")
        self.assertEqual({"asyncapi": "src/main/asyncapi/asyncapi.yaml"}, contracts.contracts(self.root, "."))
        self.path.write_text(self.path.read_text().replace("1.0.0", "2.0.0"))
        with self.assertRaisesRegex(ValueError, "synchronize"):
            contracts.contracts(self.root, ".")

    def test_asyncapi_requires_a_generation_snapshot(self):
        with self.assertRaisesRegex(ValueError, "Missing AsyncAPI generation snapshot"):
            contracts.contracts(self.root, ".")

    def test_refuses_ambiguous_sources_and_symlinked_snapshots(self):
        (self.root / "docs").mkdir()
        (self.root / "docs/asyncapi.yaml").write_text(self.path.read_text())
        with self.assertRaisesRegex(ValueError, "Ambiguous"):
            contracts.contracts(self.root, ".")
        (self.root / "docs/asyncapi.yaml").unlink()
        (self.root / ".axiz/asyncapi.snapshot.yaml").symlink_to(self.path)
        with self.assertRaisesRegex(ValueError, "Symlink"):
            contracts.contracts(self.root, ".")

    def test_rendered_chart_cannot_ignore_the_selected_registry_or_digest(self):
        digest = "sha256:" + "a" * 64
        container = {"name": "payments", "image": "registry.example:5443/builds/payments:readable@" + digest}
        deployment = {"kind": "Deployment", "spec": {"template": {"spec": {"containers": [container]}}}}
        check_render.check([deployment], "payments", "registry.example:5443/builds/payments", digest)
        container["name"] = "legacy-payments-workload"
        check_render.check([deployment], "payments", "registry.example:5443/builds/payments", digest)
        container["name"] = "payments"
        for image in ("registry.example:5443/builds/payments:latest", "other.example/payments@" + digest,
                      "registry.example:5443/builds/payments@sha256:" + "b" * 64):
            container["image"] = image
            with self.assertRaisesRegex(ValueError, "ignores"):
                check_render.check([deployment], "payments", "registry.example:5443/builds/payments", digest)
        with self.assertRaisesRegex(ValueError, "does not render"):
            check_render.check([{"kind": "ConfigMap"}], "payments", "registry.example/payments", digest)


if __name__ == "__main__":
    unittest.main()

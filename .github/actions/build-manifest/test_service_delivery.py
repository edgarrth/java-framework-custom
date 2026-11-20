import copy
import io
import json
import os
import subprocess
from unittest.mock import patch
from pathlib import Path
import tarfile
import tempfile
import unittest

import service_delivery
from build_destination import BuildDestination
from test_manifest import fixture
from test_oci_publish import image_entries, write_archive


class ServiceDeliveryTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.data = fixture("service")
        content = self.root / "content"
        (content / "images").mkdir(parents=True)
        (content / "chart").mkdir()
        write_archive(content / "images/linux-amd64.tar", image_entries())
        with tarfile.open(content / "chart/payments-2.0.0.tgz", "w:gz") as chart:
            data = b"apiVersion: v2\nname: payments\nversion: 2.0.0\nappVersion: 2.0.0\n"
            entry = tarfile.TarInfo("payments/Chart.yaml")
            entry.size = len(data)
            chart.addfile(entry, io.BytesIO(data))
        reports = self.root / "reports"
        reports.mkdir()
        (reports / "verify.log").write_text("test evidence\n")
        self.transfer = self.root / "transfer"
        service_delivery.stage(self.data, content, reports, self.transfer)

    def test_validates_complete_tested_product_without_running_its_content(self):
        images, chart = service_delivery.validate_transfer(self.transfer, self.data, self.root / "validated")
        self.assertEqual(1, len(images))
        self.assertEqual({"os": "linux", "architecture": "amd64"}, images[0][0]["platform"])
        self.assertEqual("payments-2.0.0.tgz", chart.name)

    def test_changed_report_or_image_and_uninventoried_scripts_are_rejected(self):
        for name in ("reports/verify.log", "images/linux-amd64.tar", "run-me.sh"):
            with self.subTest(name=name):
                path = self.transfer / name
                before = path.read_bytes() if path.exists() else None
                path.write_bytes(b"modified")
                with self.assertRaises(ValueError):
                    service_delivery.validate_transfer(self.transfer, self.data, self.root / "invalid")
                if before is None:
                    path.unlink()
                else:
                    path.write_bytes(before)

    def test_another_commit_or_platform_cannot_reuse_the_transfer(self):
        for key in ("source", "parameters"):
            data = copy.deepcopy(self.data)
            if key == "source":
                data[key]["sha"] = "b" * 40
            else:
                data[key]["platforms"] = ["linux/arm64"]
            with self.subTest(key=key), self.assertRaisesRegex(ValueError, "differs"):
                service_delivery.validate_transfer(self.transfer, data, self.root / "invalid")

    def test_cli_credentials_are_private_and_removed_on_success_and_failure(self):
        digest = "sha256:" + "d" * 64
        destination = BuildDestination("ghcr.io", "example/release", "example/payments",
                                       "example/gitops", ("payments",))
        outside = self.root / "user-config"
        outside.mkdir()
        (outside / "config.json").write_text("existing docker configuration")
        helm_existing = outside / "helm.json"
        helm_existing.write_text("existing helm configuration")
        for fail in (False, True):
            with self.subTest(fail=fail):
                credential_roots = set()
                workspace = self.root / ("failure" if fail else "success")
                workspace.mkdir()
                env = {"RUNNER_TEMP": str(workspace), "REGISTRY_USERNAME": "test-user",
                       "REGISTRY_PASSWORD": "test-private-password", "DOCKER_CONFIG": str(outside),
                       "HELM_REGISTRY_CONFIG": str(helm_existing)}

                def environment(kwargs):
                    self.assertGreater(kwargs["timeout"], 0)
                    self.assertLessEqual(kwargs["timeout"], 180)
                    private = kwargs["env"]
                    self.assertNotIn("REGISTRY_PASSWORD", private)
                    self.assertNotIn("REGISTRY_USERNAME", private)
                    docker = Path(private["DOCKER_CONFIG"])
                    helm = Path(private["HELM_REGISTRY_CONFIG"])
                    self.assertTrue(docker.is_relative_to(workspace))
                    self.assertEqual(docker.parent, helm.parent)
                    self.assertEqual(0o700, docker.stat().st_mode & 0o777)
                    self.assertEqual(0o700, docker.parent.stat().st_mode & 0o777)
                    credential_roots.add(docker.parent)
                    return docker, helm

                def run(command, **kwargs):
                    docker, helm = environment(kwargs)
                    self.assertNotIn("test-private-password", command)
                    if "login" in command:
                        self.assertEqual(b"test-private-password", kwargs["input"])
                        target = docker / "config.json" if command[0] == "docker" else helm
                        target.write_text("test-private-password")
                    if command[:2] == ["helm", "push"] and fail:
                        raise subprocess.CalledProcessError(1, command)
                    return subprocess.CompletedProcess(command, 0, "Digest: " + digest + "\n", "")

                def output(command, **kwargs):
                    environment(kwargs)
                    if command[:3] == ["helm", "show", "chart"]:
                        return "name: payments\nversion: 2.0.0\n"
                    if command[-1] == "{{json .Manifest}}":
                        return json.dumps({"digest": digest})
                    return json.dumps({"manifest": {"digest": digest},
                                       "image": {"os": "linux", "architecture": "amd64"}})

                with patch.dict(os.environ, env), \
                        patch.object(service_delivery.authority, "obtain", return_value={"producer_job": "job"}), \
                        patch.object(service_delivery, "from_environment", return_value=destination), \
                        patch.object(service_delivery, "download", return_value=self.transfer), \
                        patch.object(service_delivery.oci_publish, "Registry"), \
                        patch.object(service_delivery.oci_publish, "publish", return_value=digest), \
                        patch.object(service_delivery.subprocess, "run", side_effect=run), \
                        patch.object(service_delivery.subprocess, "check_output", side_effect=output):
                    if fail:
                        with self.assertRaises(subprocess.CalledProcessError):
                            service_delivery.publish(copy.deepcopy(self.data), {}, workspace, workspace / "published")
                    else:
                        service_delivery.publish(copy.deepcopy(self.data), {}, workspace, workspace / "published")
                        self.assertTrue((workspace / "published/metadata.json").is_file())
                self.assertTrue(credential_roots)
                self.assertTrue(all(not path.exists() for path in credential_roots))
                self.assertEqual("existing docker configuration", (outside / "config.json").read_text())
                self.assertEqual("existing helm configuration", helm_existing.read_text())


if __name__ == "__main__":
    unittest.main()

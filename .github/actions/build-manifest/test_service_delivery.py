import copy
import io
from pathlib import Path
import tarfile
import tempfile
import unittest

import service_delivery
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


if __name__ == "__main__":
    unittest.main()

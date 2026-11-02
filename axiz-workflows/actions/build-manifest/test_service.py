import copy
import tempfile
import unittest
from pathlib import Path

import manifest
import service
from test_manifest import fixture


class ServiceMetadataTest(unittest.TestCase):
    def test_final_build_version_changes_only_the_service_not_its_framework_parent(self):
        data = self.parameters()
        pom = self.root / "pom.xml"
        pom.write_text('''<project xmlns="http://maven.apache.org/POM/4.0.0">
          <!-- source comment -->
          <parent><groupId>pe.axiz</groupId><artifactId>axiz-parent-service</artifactId><version>2.0.0</version></parent>
          <artifactId>payments</artifactId><version>0.1.0-SNAPSHOT</version>
          <dependencies><dependency><groupId>example</groupId><artifactId>library</artifactId><version>1.2.3</version></dependency></dependencies>
        </project>''')
        data["version"] = "1.3.0"
        service.set_project_version(data, self.root)
        project = service.ET.parse(pom).getroot()
        self.assertEqual("1.3.0", project.findtext("m:version", namespaces=service.NS))
        self.assertEqual("2.0.0", project.findtext("m:parent/m:version", namespaces=service.NS))
        self.assertEqual("1.2.3", project.findtext("m:dependencies/m:dependency/m:version", namespaces=service.NS))
        self.assertIn("source comment", pom.read_text())

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "pom.xml").write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"><artifactId>payments</artifactId></project>')
        (self.root / "deploy/helm/payments").mkdir(parents=True)
        (self.root / "deploy/helm/payments/Chart.yaml").write_text("name: payments\nversion: 0.1.0\n")
        self.base = "registry.example/java@sha256:" + "1" * 64
        self.data = fixture("service")
        self.data["dependencies"] = {"axiz_version": "2.0.0"}

    def parameters(self, **changes):
        params = dict(name="payments", module=".", chart="deploy/helm/payments", variant="jvm",
                      platforms=["linux/amd64"], base_image=self.base)
        params.update(changes)
        return service.parameters(self.data, self.root, **params)

    def test_service_parameters_are_separate_from_maven_library_parameters(self):
        data = self.parameters()
        self.assertEqual("service", data["kind"])
        self.assertNotIn("profiles", data["parameters"])
        self.assertNotIn("maven_args", data["parameters"])
        self.assertEqual("sha256:" + "1" * 64, data["dependencies"]["base_image"])

    def test_rejects_unsafe_paths_and_unpinned_or_inconsistent_inputs(self):
        for changes in ({"module": "../outside"}, {"chart": "/tmp/chart"}, {"base_image": "java:latest"},
                        {"base_image": "scratch"}, {"name": "different"}, {"variant": "unknown"},
                        {"platforms": ["linux/amd64", "linux/amd64"]}, {"variant": "native", "platforms": ["linux/amd64", "linux/arm64"]}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                self.parameters(**changes)
        self.parameters(variant="native", base_image="scratch")

    def test_does_not_follow_a_chart_metadata_symlink_outside_the_checkout(self):
        chart = self.root / "deploy/helm/payments/Chart.yaml"
        chart.unlink()
        chart.symlink_to(self.root / "pom.xml")
        with self.assertRaisesRegex(ValueError, "Symlink"):
            self.parameters()

    def test_records_the_actual_multi_platform_index_and_rejects_partial_or_unexpected_platforms(self):
        data = self.parameters(platforms=["linux/amd64", "linux/arm64"])
        descriptor = {"manifest": {"digest": "sha256:" + "2" * 64, "manifests": [
            {"platform": {"os": "linux", "architecture": "amd64"}, "digest": "sha256:" + "3" * 64},
            {"platform": {"os": "linux", "architecture": "arm64"}, "digest": "sha256:" + "4" * 64}]}}
        artifact = service.image(data, "registry.example/builds/payments", "source-label", "sha256:" + "2" * 64, descriptor)
        self.assertEqual({"linux/amd64", "linux/arm64"}, set(artifact["platforms"]))
        wrong = copy.deepcopy(descriptor)
        wrong["manifest"]["manifests"].pop()
        with self.assertRaisesRegex(ValueError, "platforms differ"):
            service.image(data, artifact["repository"], artifact["tag"], artifact["digest"], wrong)
        with self.assertRaisesRegex(ValueError, "differs from the image"):
            service.image(data, artifact["repository"], artifact["tag"], "sha256:" + "9" * 64, descriptor)

    def test_single_image_architecture_and_chart_content_are_bound_to_the_build(self):
        data = self.parameters()
        image = service.image(data, "registry.example/builds/payments", "source-label", "sha256:" + "2" * 64,
                              {"manifest": {"digest": "sha256:" + "2" * 64}, "image": {"os": "linux", "architecture": "amd64"}})
        chart = service.chart(data, "oci://registry.example/build-charts/payments", "sha256:" + "3" * 64,
                              {"digest": "sha256:" + "3" * 64}, {"name": "payments", "version": "2.0.0"})
        data["build"]["workflow"]["path"] = ".github/workflows/service-build.yaml"
        data["artifacts"] = {"image": image, "chart": chart}
        data["parameters_sha256"] = manifest.canonical_hash(data["parameters"])
        manifest.validate(data)
        with self.assertRaisesRegex(ValueError, "architecture differs"):
            service.image(data, image["repository"], image["tag"], image["digest"],
                          {"manifest": {"digest": image["digest"]}, "image": {"os": "linux", "architecture": "arm64"}})
        with self.assertRaisesRegex(ValueError, "metadata differs"):
            service.chart(data, chart["oci"], chart["digest"], {"digest": chart["digest"]}, {"name": "payments", "version": "1.0.0"})


if __name__ == "__main__":
    unittest.main()

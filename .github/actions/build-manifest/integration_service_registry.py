#!/usr/bin/env python3
"""Opt-in OCI round trip against an existing ephemeral loopback registry (Docker and Helm required)."""
import argparse
import hashlib
import json
import re
import subprocess
import tempfile
from pathlib import Path
from unittest.mock import patch

import yaml

import manifest
import service
import service_deploy
from test_manifest import fixture


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--registry", required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    if not re.fullmatch(r"127\.0\.0\.1:[0-9]+", args.registry):
        parser.error("This test only writes to an ephemeral 127.0.0.1 registry")
    receipts = []
    real_output = subprocess.check_output

    def local_helm(command, **kwargs):
        # Only this opt-in loopback fixture permits unencrypted OCI transport.
        if command[:3] == ["helm", "show", "chart"]:
            command = [*command, "--plain-http"]
        return real_output(command, **kwargs)

    with tempfile.TemporaryDirectory(prefix="axiz-oci-roundtrip-") as temporary:
        root = Path(temporary)
        (root / "Dockerfile").write_text("FROM scratch\nCOPY content.txt /content.txt\n")
        (root / "content.txt").write_text("Axiz OCI verification fixture; not a service image.\n")
        chart_dir = root / "payments"
        chart_dir.mkdir()
        (chart_dir / "Chart.yaml").write_text("apiVersion: v2\nname: payments\nversion: 2.0.0\nappVersion: 2.0.0\n")
        (chart_dir / "values.yaml").write_text("replicaCount: 1\n")
        subprocess.run(["helm", "package", str(chart_dir), "--destination", str(root)], check=True)
        subprocess.run(["helm", "push", str(root / "payments-2.0.0.tgz"), "oci://" + args.registry + "/build-charts", "--plain-http"], check=True)
        chart_ref = args.registry + "/build-charts/payments:2.0.0"
        chart_descriptor = service.inspect(chart_ref, "{{json .Manifest}}")
        chart_digest = chart_descriptor["digest"]
        chart_metadata = yaml.safe_load(real_output(["helm", "show", "chart", "oci://" + chart_ref, "--plain-http"], text=True))
        for platforms in [["linux/amd64"], ["linux/amd64", "linux/arm64"]]:
            data = fixture("service")
            data["parameters"]["platforms"] = platforms
            data["parameters_sha256"] = manifest.canonical_hash(data["parameters"])
            tag = "single" if len(platforms) == 1 else "multi"
            repository = args.registry + "/builds/payments"
            # No RUN instruction: each architecture can be built without emulation or changing
            # the host's Docker image store. The multi-platform product is a real registry index.
            references = []
            for platform in platforms:
                reference = repository + ":" + tag + "-" + platform.split("/")[1]
                subprocess.run(["docker", "buildx", "build", "--platform", platform, "--provenance=false", "--push",
                                "--tag", reference, "--metadata-file", str(root / "build.json"), str(root)], check=True)
                references.append(reference)
            if len(platforms) > 1:
                subprocess.run(["docker", "buildx", "imagetools", "create", "--tag", repository + ":" + tag, *references], check=True)
                image_digest = service.inspect(repository + ":" + tag, "{{json .Manifest}}")["digest"]
            else:
                built = json.loads((root / "build.json").read_text())
                image_digest = built["containerimage.digest"]
            data["artifacts"] = {
                "image": service.image(data, repository, tag, image_digest, service.inspect(repository + "@" + image_digest)),
                "chart": service.chart(data, "oci://" + args.registry + "/build-charts/payments", chart_digest, chart_descriptor, chart_metadata)}
            manifest.validate(data)
            with patch.object(subprocess, "check_output", side_effect=local_helm):
                service_deploy.verify_products(data)
            receipts.append({"platforms": platforms, "parameters_sha256": data["parameters_sha256"], "artifacts": data["artifacts"]})
        if receipts[0]["artifacts"]["image"]["digest"] == receipts[1]["artifacts"]["image"]["digest"]:
            raise AssertionError("Adding the arm64 image did not change the actual index digest")
        if receipts[0]["parameters_sha256"] == receipts[1]["parameters_sha256"]:
            raise AssertionError("Effective platform parameters did not change their canonical identity")
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(receipts, indent=2) + "\n")
    print("Real single-platform and multi-platform image/chart round trips passed; receipt SHA256:",
          hashlib.sha256(args.report.read_bytes()).hexdigest())


if __name__ == "__main__":
    main()

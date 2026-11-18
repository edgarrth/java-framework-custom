#!/usr/bin/env python3
"""Prove that the effective gitleaks configuration still detects secret canaries."""
import argparse
import json
import secrets
import subprocess
import tempfile
from pathlib import Path

IMAGE = "ghcr.io/gitleaks/gitleaks:v8.30.1@sha256:c00b6bd0aeb3071cbcb79009cb16a60dd9e0a7c60e2be9ab65d25e6bc8abbb7f"


def check(configuration):
    configuration = configuration.resolve(strict=True)
    with tempfile.TemporaryDirectory(prefix="axiz-gitleaks-canary-") as directory:
        root = Path(directory)
        api = root / "docs/api/public-api.json"
        api.parent.mkdir(parents=True)
        digest = secrets.token_hex(32)
        # Synthetic random data, never a real account credential. Reports are always redacted.
        canary = secrets.token_urlsafe(32)
        api.write_text('"keySha256": "' + digest + '"\n'
                       '"api_key": "' + canary + '"\n'
                       '"keySha256": "' + digest + '", "api_key": "' + canary + '"\n')
        outside = root / "outside.json"
        outside.write_text('"api_key": "' + canary + '"\n')
        registry = root / "registry.env"
        registry.write_text("".join(name + '="' + canary + '"\n' for name in
                            ("PACKAGES_READ_TOKEN", "GITHUB_TOKEN", "GHCR_PASSWORD", "NEXUS_PASSWORD")))
        result = subprocess.run([
            "docker", "run", "--rm", "--network", "none", "-w", "/scan",
            "-v", str(root) + ":/scan", "-v", str(configuration) + ":/configuration.toml:ro",
            IMAGE, "dir", ".", "--config", "/configuration.toml", "--redact",
            "--report-format", "json", "--report-path", "/scan/report.json",
        ], capture_output=True, text=True, timeout=120, check=False)
        report = root / "report.json"
        if result.returncode != 1 or not report.is_file():
            raise RuntimeError("Secret canary scanner did not produce the expected failure/report")
        findings = json.loads(report.read_text())
        locations = {(item["File"].removeprefix("./"), item["StartLine"]) for item in findings}
        required = {("docs/api/public-api.json", 2),
                    ("docs/api/public-api.json", 3),
                    ("outside.json", 1),
                    *(("registry.env", line) for line in range(1, 5))}
        if not required.issubset(locations):
            raise RuntimeError("The effective gitleaks configuration hid a secret canary")
        forbidden = {("docs/api/public-api.json", 1)}
        if forbidden.intersection(locations):
            raise RuntimeError("A canonical metadata digest was not narrowly recognized")
        print("gitleaks canaries passed: digests allowed; secrets in, beside and outside metadata detected")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--configuration", type=Path, default=Path(".gitleaks.toml"))
    check(parser.parse_args().configuration)

#!/usr/bin/env python3
"""Require each service workload to use the approved repository and content digest."""
import argparse
from pathlib import Path

import yaml


def check(documents, service, repository, digest):
    matched = 0
    for resource in documents:
        if not isinstance(resource, dict):
            continue
        kind = resource.get("kind")
        if kind in {"Deployment", "StatefulSet", "DaemonSet", "Job"}:
            spec = resource.get("spec", {}).get("template", {}).get("spec", {})
        elif kind == "CronJob":
            spec = resource.get("spec", {}).get("jobTemplate", {}).get("spec", {}).get("template", {}).get("spec", {})
        else:
            continue
        for container in spec.get("containers", []):
            reference = container.get("image", "")
            image, separator, content_digest = reference.partition("@")
            # A readable tag before @digest is allowed, but cannot select the content.
            image_repository = image.rsplit("/", 1)[0] + "/" + image.rsplit("/", 1)[1].split(":", 1)[0] if "/" in image else image.split(":", 1)[0]
            # nameOverride can preserve an existing workload identity during a service rename.
            if container.get("name") != service and image_repository != repository:
                continue
            if separator != "@" or content_digest != digest or image_repository != repository:
                raise ValueError("Rendered service workload ignores the approved image repository or digest")
            matched += 1
    if matched == 0:
        raise ValueError("Chart does not render a workload using the approved service image")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("rendered", type=Path)
    parser.add_argument("--service", required=True)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--digest", required=True)
    args = parser.parse_args()
    check(yaml.safe_load_all(args.rendered.read_text()), args.service, args.repository, args.digest)
    print("Rendered workload uses the approved content digest")


if __name__ == "__main__":
    main()

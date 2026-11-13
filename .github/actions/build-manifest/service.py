#!/usr/bin/env python3
"""Bind service build parameters and actual registry descriptors to the manifest."""
import argparse
import json
import re
import subprocess
from defusedxml import ElementTree as ET
from xml.dom import Node
from defusedxml import minidom
from pathlib import Path, PurePosixPath

import yaml

from manifest import read_json, safe_file
from maven_version import update as update_reactor_version

DIGEST = re.compile(r"sha256:[0-9a-f]{64}")
REPOSITORY = re.compile(r"[a-z0-9][a-z0-9._:-]*(?:/[a-z0-9][a-z0-9._-]*)+")
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def directory(root, relative):
    name = PurePosixPath(relative)
    if not relative or name.is_absolute() or ".." in name.parts or str(name) != relative or "\\" in relative:
        raise ValueError("Module and chart must be canonical relative source paths")
    path = Path(root) / relative
    if not path.is_dir() or not path.resolve().is_relative_to(Path(root).resolve()):
        raise ValueError("Build directory is absent or outside the source checkout")
    if any((Path(root) / Path(*name.parts[:i])).is_symlink() for i in range(1, len(name.parts) + 1)):
        raise ValueError("Module and chart directories may not traverse symlinks")
    return path


def effective_parameters(data, name, module, chart, variant, platforms, base_image):
    if not re.fullmatch(r"[a-z][a-z0-9]*(?:-[a-z0-9]+)*", name):
        raise ValueError("Invalid service capability name")
    if variant not in {"jvm", "native"}:
        raise ValueError("Service variant must be jvm or native")
    if (not isinstance(platforms, list) or not platforms or len(set(platforms)) != len(platforms)
            or any(p not in {"linux/amd64", "linux/arm64"} for p in platforms)):
        raise ValueError("Explicit unique linux/amd64 or linux/arm64 platforms are required")
    if variant == "native" and len(platforms) != 1:
        raise ValueError("Native builds select one runner architecture per build")
    if "axiz_version" not in data.get("dependencies", {}):
        raise ValueError("A service declares its exact Axiz dependency version")
    if base_image == "scratch":
        if variant != "native":
            raise ValueError("JVM images require a runtime base image pinned by digest")
        base_digest = "scratch"
    elif re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:/-]*@sha256:[0-9a-f]{64}", base_image) and "://" not in base_image:
        base_digest = base_image.rsplit("@", 1)[1]
    else:
        raise ValueError("The base image must be an explicit registry reference pinned by digest")
    for relative in (module, chart):
        path = PurePosixPath(relative)
        if not relative or path.is_absolute() or ".." in path.parts or str(path) != relative or "\\" in relative:
            raise ValueError("Module and chart must be canonical relative source paths")
    data["kind"] = "service"
    data["parameters"] = {"service": name, "module": module, "jdk": "25", "platforms": platforms,
                          "variant": variant, "chart": chart}
    data["dependencies"]["base_image"] = base_digest
    return data


def parameters(data, root, name, module, chart, variant, platforms, base_image):
    effective_parameters(data, name, module, chart, variant, platforms, base_image)
    module_dir, chart_dir = directory(root, module), directory(root, chart)
    project = ET.parse(safe_file(Path(root), (PurePosixPath(module) / "pom.xml").as_posix())).getroot()
    if project.findtext("m:artifactId", namespaces=NS) != name:
        raise ValueError("Service name and module artifactId must match")
    chart_data = yaml.safe_load(safe_file(Path(root), (PurePosixPath(chart) / "Chart.yaml").as_posix()).read_text())
    if chart_data.get("name") != name:
        raise ValueError("Service name and packaged chart name must match")
    return data


def digest(value):
    if not isinstance(value, str) or not DIGEST.fullmatch(value):
        raise ValueError("Missing or invalid content digest from the registry")
    return value


def image(data, repository, tag, expected_digest, inspected):
    if not REPOSITORY.fullmatch(repository):
        raise ValueError("Invalid image repository")
    descriptor = inspected["manifest"]
    if digest(descriptor.get("digest")) != digest(expected_digest):
        raise ValueError("The registry image differs from the image built by Jib")
    wanted = set(data["parameters"]["platforms"])
    result = {"repository": repository, "digest": expected_digest, "tag": tag}
    children = descriptor.get("manifests")
    if children:
        platforms = {}
        for child in children:
            if child.get("annotations", {}).get("vnd.docker.reference.type") == "attestation-manifest":
                continue
            platform = child.get("platform", {})
            key = platform.get("os", "") + "/" + platform.get("architecture", "")
            if key in platforms:
                raise ValueError("Duplicate platform in the image index")
            platforms[key] = digest(child.get("digest"))
        if set(platforms) != wanted:
            raise ValueError("Image index platforms differ from the effective build parameters")
        result["platforms"] = platforms
    else:
        config = inspected["image"]
        if len(wanted) != 1 or config.get("os", "") + "/" + config.get("architecture", "") not in wanted:
            raise ValueError("Single-image architecture differs from the selected build platform")
    return result


def chart(data, oci, expected_digest, descriptor, metadata):
    if not oci.startswith("oci://") or not REPOSITORY.fullmatch(oci[6:]):
        raise ValueError("Invalid OCI chart repository")
    if digest(descriptor.get("digest")) != digest(expected_digest):
        raise ValueError("Registry chart digest differs from the package pushed by the build")
    if metadata.get("name") != data["parameters"]["service"] or str(metadata.get("version")) != data["version"]:
        raise ValueError("Packaged chart metadata differs from the approved service and version")
    return {"name": metadata["name"], "version": data["version"], "oci": oci, "digest": expected_digest}


def inspect(reference, expression="{{json .}}"):
    return json.loads(subprocess.check_output(["docker", "buildx", "imagetools", "inspect", reference,
                                              "--format", expression], text=True))


def set_project_version(data, root):
    """Apply final reactor coordinates while preserving external parents and dependencies."""
    path = safe_file(Path(root), (PurePosixPath(data["parameters"]["module"]) / "pom.xml").as_posix())
    document = minidom.parseString(path.read_bytes())
    try:
        versions = [node for node in document.documentElement.childNodes
                    if node.nodeType == Node.ELEMENT_NODE and node.localName == "version"]
        if len(versions) != 1:
            raise ValueError("Service source must declare its own project version")
    finally:
        document.unlink()
    update_reactor_version(root, data["version"])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    version = commands.add_parser("set-version")
    version.add_argument("--metadata", type=Path, required=True)
    version.add_argument("--root", type=Path, default=Path("."))
    artifacts = commands.add_parser("artifacts")
    artifacts.add_argument("--metadata", type=Path, required=True)
    artifacts.add_argument("--image-repository", required=True)
    artifacts.add_argument("--image-tag", required=True)
    artifacts.add_argument("--image-digest", required=True)
    artifacts.add_argument("--chart-oci", required=True)
    artifacts.add_argument("--chart-digest", required=True)
    args = parser.parse_args()
    data = read_json(args.metadata)
    if args.command == "set-version":
        set_project_version(data, args.root)
        return
    image_ref = args.image_repository + "@" + digest(args.image_digest)
    chart_ref = args.chart_oci.removeprefix("oci://") + "@" + digest(args.chart_digest)
    chart_metadata = yaml.safe_load(subprocess.check_output(["helm", "show", "chart", "oci://" + chart_ref], text=True))
    data["artifacts"] = {
        "image": image(data, args.image_repository, args.image_tag, args.image_digest, inspect(image_ref)),
        "chart": chart(data, args.chart_oci, args.chart_digest, inspect(chart_ref, "{{json .Manifest}}"), chart_metadata)}
    args.metadata.write_text(json.dumps(data, indent=2) + "\n")


if __name__ == "__main__":
    main()

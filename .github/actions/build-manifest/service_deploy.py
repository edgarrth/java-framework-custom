#!/usr/bin/env python3
"""Verify immutable service products and prepare GitOps input without rebuilding them."""
import argparse
import base64
import hashlib
import json
import re
import subprocess
from pathlib import Path
from urllib.parse import quote

import rfc8785
import yaml

from manifest import read_json, verify
import service


def raw_manifest(reference, expected):
    raw = subprocess.check_output(["docker", "buildx", "imagetools", "inspect", reference, "--raw"])
    # buildx releases may append one display newline to the original manifest.
    for content in (raw, raw.removesuffix(b"\n")):
        if "sha256:" + hashlib.sha256(content).hexdigest() == expected:
            return json.loads(content)
    raise ValueError("Registry manifest bytes differ from the approved content digest")


def verify_products(data):
    if data["kind"] != "service":
        raise ValueError("Service deployment requires a service build")
    image = data["artifacts"]["image"]
    reference = image["repository"] + "@" + image["digest"]
    raw_manifest(reference, image["digest"])
    actual = service.image(data, image["repository"], image["tag"], image["digest"], service.inspect(reference))
    if actual != image:
        raise ValueError("Image platform content differs from the signed build")
    for expected in image.get("platforms", {}).values():
        raw_manifest(image["repository"] + "@" + expected, expected)
    chart = data["artifacts"]["chart"]
    chart_ref = chart["oci"].removeprefix("oci://") + "@" + chart["digest"]
    raw_manifest(chart_ref, chart["digest"])
    metadata = yaml.safe_load(subprocess.check_output(["helm", "show", "chart", "oci://" + chart_ref], text=True))
    if service.chart(data, chart["oci"], chart["digest"], service.inspect(chart_ref, "{{json .Manifest}}"), metadata) != chart:
        raise ValueError("Chart metadata differs from the signed build")


def api(path):
    return json.loads(subprocess.check_output(["gh", "api", path], text=True))


def values(data, environment, revision):
    if environment not in {"dev", "qa", "prod"} or not re.fullmatch(r"[0-9a-f]{40}", revision):
        raise ValueError("Values require a supported environment and a full immutable commit SHA")
    repository = data["source"]["repository"]
    if api(f"repos/{repository}/commits/{revision}")["sha"] != revision:
        raise ValueError("Values revision did not resolve to the approved commit")
    chart = data["parameters"]["chart"]
    paths = [chart + "/values.yaml", chart + f"/values-{environment}.yaml"]
    refs = []
    for path in paths:
        blob = api(f"repos/{repository}/contents/{quote(path, safe='/')}?ref={revision}")
        if blob.get("type") != "file" or blob.get("encoding") != "base64" or blob.get("path") != path:
            raise ValueError("Values must be regular versioned files, not symlinks or directories")
        content = base64.b64decode(blob["content"].replace("\n", ""), validate=True)
        if not isinstance(yaml.safe_load(content), dict):
            raise ValueError("Values must contain a YAML mapping")
        refs.append({"name": "values:" + path, "ref": f"https://github.com/{repository}/blob/{revision}/{path}#sha256={hashlib.sha256(content).hexdigest()}"})
    return {"repoURL": "https://github.com/" + repository, "revision": revision, "files": paths}, refs


def desired(data, manifest_hash, environment, source, record_hash, run):
    return {"service": data["parameters"]["service"], "environment": environment,
            "manifest_sha256": manifest_hash, "deploy_record_sha256": record_hash,
            "chart": data["artifacts"]["chart"], "image": data["artifacts"]["image"],
            "values": source, "run": run}


def synced(state, expected):
    """A healthy previous deployment is insufficient: match both immutable sources and the image."""
    spec = state.get("spec", {})
    sources = spec.get("sources", [])
    if len(sources) != 2:
        return False
    chart, values_source = sources
    if (chart.get("repoURL") != expected["chart"]["oci"] or chart.get("targetRevision") != expected["chart"]["digest"]
            or chart.get("path") != "." or values_source.get("repoURL") != expected["values"]["repoURL"]
            or values_source.get("targetRevision") != expected["values"]["revision"] or values_source.get("ref") != "values"):
        return False
    helm = chart.get("helm", {})
    parameters = {item["name"]: item["value"] for item in helm.get("parameters", [])}
    if (parameters.get("image.repository") != expected["image"]["repository"]
            or parameters.get("image.digest") != expected["image"]["digest"]
            or parameters.get("image.tag") != expected["image"]["tag"]
            or helm.get("valueFiles") != ["$values/" + path for path in expected["values"]["files"]]):
        return False
    annotations = state.get("metadata", {}).get("annotations", {})
    if (annotations.get("axiz.pe/build-manifest-sha256") != expected["manifest_sha256"]
            or annotations.get("axiz.pe/deploy-record-sha256") != expected["deploy_record_sha256"]):
        return False
    status = state.get("status", {})
    sync = status.get("sync", {})
    if (sync.get("status") != "Synced" or status.get("health", {}).get("status") != "Healthy"
            or sync.get("revisions") != [expected["chart"]["digest"], expected["values"]["revision"]]):
        return False
    return status.get("operationState", {}).get("phase") in {None, "Succeeded"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle", type=Path, required=True)
    parser.add_argument("--manifest-sha256", required=True)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--environment", required=True)
    parser.add_argument("--values-revision", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--configuration", type=Path, required=True)
    args = parser.parse_args()
    data = verify(args.bundle / "build-manifest.json", args.manifest_sha256, args.bundle, args.repository)
    verify_products(data)
    source, refs = values(data, args.environment, args.values_revision)
    args.output.write_bytes(rfc8785.dumps({"manifest": data, "values": source}))
    args.configuration.write_bytes(rfc8785.dumps(refs))


if __name__ == "__main__":
    main()

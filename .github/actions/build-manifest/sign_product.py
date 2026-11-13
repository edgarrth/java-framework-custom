#!/usr/bin/env python3
"""Isolated signer: reconstruct identity, verify Actions authority and sign data without executing it."""
import argparse
import base64
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import tempfile
from urllib.parse import quote

from defusedxml import ElementTree as ET
import rfc8785
import yaml

import authority
import build_bundle
import manifest
import native_components
import prepare
from service import effective_parameters
from signer_artifacts import download

TAG = "axiz-workflows-v2.0.0"  # x-release-please-version


def expected(kind, public, environment, claims, workflow_sha):
    source = public["source-sha"]
    if source != environment["GITHUB_SHA"]:
        raise ValueError("Signer source must equal the Actions source SHA")
    run_id, attempt = int(environment["GITHUB_RUN_ID"]), int(environment["GITHUB_RUN_ATTEMPT"])
    if (claims.get("run_id") != str(run_id) or claims.get("run_attempt") != str(attempt)):
        raise ValueError("OIDC token belongs to another run or attempt")
    data = prepare.inputs(public["version"], source, environment["GITHUB_REPOSITORY"],
                          json.loads(public.get("maven-args", "[]")), public.get("axiz-version", ""),
                          public.get("candidate", ""), public.get("project-directory", "."))
    if kind == "service":
        effective_parameters(data, public["service"], public.get("module", "."), public["chart"],
                             public.get("variant", "jvm"), json.loads(public.get("platforms", '["linux/amd64"]')),
                             public["base-image"])
    elif kind in {"maven-library", "native-component"}:
        spec = (public.get("native-module", ""), public.get("native-binary", ""),
                json.loads(public.get("native-platforms", "[]")))
        if kind == "native-component":
            spec = (public["module"], public["binary"], sorted(native_components.PLATFORMS))
        if any(spec):
            data["parameters"]["native"] = native_components.specification(*spec)
    else:
        raise ValueError("Unknown signer product kind")
    candidate_keys = ("candidate-repository", "candidate-build-run-id", "candidate-build-run-attempt", "candidate-manifest-sha256")
    candidate = [public.get(key, "") for key in candidate_keys]
    if any(candidate):
        if (not all(candidate) or "axiz_version" not in data.get("dependencies", {})
                or not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", candidate[0])
                or not all(re.fullmatch(r"[1-9][0-9]*", value) for value in candidate[1:3])
                or not re.fullmatch(r"[0-9a-f]{64}", candidate[3])):
            raise ValueError("Candidate identity must be complete and explicit")
        data["dependencies"]["axiz_build"] = dict(zip(
            ("repository", "run_id", "run_attempt", "manifest_sha256"),
            (candidate[0], int(candidate[1]), int(candidate[2]), candidate[3])))
    data["build"] = {"run_id": run_id, "run_attempt": attempt, "workflow": prepare.workflow_identity(
        claims, data["source"]["repository"], source, workflow_sha, kind=kind, tag=TAG)}
    return data


def source_file(data, relative):
    path = PurePosixPath(relative)
    if path.is_absolute() or ".." in path.parts or str(path) != relative or "\\" in relative:
        raise ValueError("Unsafe source path")
    response = prepare.api(f"repos/{data['source']['repository']}/contents/{quote(relative, safe='/')}?ref={data['source']['sha']}")
    if response.get("type") != "file" or response.get("encoding") != "base64" or not 0 < response.get("size", 0) <= 4 * 1024 * 1024:
        raise ValueError("Expected a bounded source file at the exact source SHA")
    content = base64.b64decode("".join(response["content"].split()), validate=True)
    if len(content) != response["size"]:
        raise ValueError("Source content differs from its declared size")
    return content


def compare(uploaded, expected_data):
    for key in ("schema", "kind", "source", "build", "parameters", "version"):
        if uploaded.get(key) != expected_data.get(key):
            raise ValueError("Worker metadata differs from trusted signer context: " + key)
    for key in ("dependencies", "candidate"):
        if uploaded.get(key) != expected_data.get(key):
            raise ValueError("Worker dependency/candidate identity differs from signer inputs")


def signed_native(data, bundle):
    native_components.validate_manifest(data, bundle)
    for target in data["parameters"].get("native", {}).get("platforms", []):
        directory = bundle / "reports/native" / target
        subprocess.run(["cosign", "verify-blob", "--bundle", str(manifest.safe_file(directory, "native-component.sigstore.json")),
                        "--certificate-identity", "https://github.com/edgarrth/java-framework-custom/.github/workflows/native.yaml@" + data["build"]["workflow"]["ref"],
                        "--certificate-oidc-issuer", "https://token.actions.githubusercontent.com",
                        "--certificate-github-workflow-repository", data["source"]["repository"],
                        "--certificate-github-workflow-sha", data["source"]["sha"],
                        str(manifest.safe_file(directory, "native-component.json"))], check=True, timeout=90)


def signature(path):
    subprocess.run(["cosign", "sign-blob", "--yes", "--bundle", str(path.with_name(path.stem + ".sigstore.json")),
                    str(path)], check=True, timeout=180)


def product(data, public, temporary, output):
    kind, attempt = data["kind"], data["build"]["run_attempt"]
    if kind == "service":
        job = "service:" + data["parameters"]["service"] + ":" + data["parameters"]["variant"]
        name = f"unsigned-service-{public['service']}-{public.get('variant', 'jvm')}-{attempt}"
        pom = ET.fromstring(source_file(data, str(PurePosixPath(public.get("module", ".")) / "pom.xml")))
        chart = yaml.safe_load(source_file(data, str(PurePosixPath(public["chart"]) / "Chart.yaml")))
        if pom.findtext("{*}artifactId") != public["service"] or chart.get("name") != public["service"]:
            raise ValueError("Service/chart name differs from immutable source")
    else:
        job = "maven:" + data["parameters"]["project_directory"]
        name = f"unsigned-maven-{attempt}"
    receipt = authority.obtain(data["source"], data["build"]["run_id"], attempt, job, kind)
    bundle = download(receipt, name, receipt["producer_job"], temporary / "unsigned")
    path = manifest.safe_file(bundle, "build-manifest.json")
    if path.stat().st_size > 4 * 1024 * 1024:
        raise ValueError("Worker manifest exceeds its metadata budget")
    uploaded = manifest.read_json(path)
    compare(uploaded, data)
    manifest.validate(uploaded, bundle, require_authority=False)
    if kind == "maven-library":
        signed_native(uploaded, bundle)
    shutil.copytree(bundle, output)
    authority_path = output / "reports/actions-authority.json"
    authority_path.write_bytes(rfc8785.dumps(receipt))
    build_bundle.complete(uploaded, output)
    uploaded["gates"].append({"name": "producer-authority", "result": "passed", "evidence": {
        "path": "reports/actions-authority.json", "sha256": manifest.sha256(authority_path.read_bytes())}})
    manifest.validate(uploaded, output)
    location = output / "build-manifest.json"
    location.write_bytes(rfc8785.dumps(uploaded))
    if kind == "service":
        service_signatures(uploaded, public, output)
    signature(location)
    if kind == "service":
        from service_build_proof import create
        proof = output.parent / "service-build-proof"
        proof.mkdir(exist_ok=False)
        (proof / "service-build-proof.json").write_bytes(rfc8785.dumps(create(
            output, manifest.sha256(location.read_bytes()), data["source"]["repository"])))
        signature(proof / "service-build-proof.json")
    if "GITHUB_OUTPUT" in os.environ:
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as stream:
            stream.write("manifest-sha256=" + manifest.sha256(location.read_bytes()) + "\n")


def service_signatures(data, public, bundle):
    from service import image, inspect
    repository = data["artifacts"]["image"]["repository"]
    from build_destination import from_environment
    destination = from_environment()
    expected_repository = destination.image(data["source"]["repository"], public["service"])
    tag = f"{data['source']['sha']}-{data['build']['run_id']}-{data['build']['run_attempt']}-{data['parameters']['variant']}"
    chart = data["artifacts"]["chart"]
    expected_chart = destination.chart(data["source"]["repository"], public["service"],
        data["build"]["run_id"], data["build"]["run_attempt"], data["parameters"]["variant"])
    if chart["oci"] != expected_chart or chart["name"] != public["service"] or chart["version"] != data["version"]:
        raise ValueError("Chart is not the exact source service/version and producer registry destination")
    if repository != expected_repository or data["artifacts"]["image"]["tag"] != tag:
        raise ValueError("Service image is not the exact producer registry/name/tag")
    subprocess.run(["docker", "login", destination.registry, "--username", os.environ["REGISTRY_USERNAME"], "--password-stdin"],
                   input=os.environ["REGISTRY_PASSWORD"].encode(), check=True, timeout=60)
    descriptor = inspect(repository + ":" + tag)
    expected_digest = data["artifacts"]["image"]["digest"]
    if image(data, repository, tag, expected_digest, descriptor) != data["artifacts"]["image"]:
        raise ValueError("Registry product differs from signed service platform descriptors")
    reference = repository + "@" + expected_digest
    subprocess.run(["cosign", "sign", "--yes", reference], check=True, timeout=180)
    subprocess.run(["cosign", "attest", "--yes", "--type", "cyclonedx", "--predicate", str(bundle / "reports/bom.json"), reference],
                   check=True, timeout=180)
    predicate = bundle / "reports/provenance.predicate.json"
    predicate.write_bytes(rfc8785.dumps(manifest.read_json(bundle / "reports/provenance.intoto.json")["predicate"]))
    subprocess.run(["cosign", "attest", "--yes", "--type", "https://slsa.dev/provenance/v1", "--predicate", str(predicate), reference],
                   check=True, timeout=180)
    subprocess.run(["cosign", "attest", "--yes", "--type", "custom", "--predicate", str(bundle / "build-manifest.json"), reference],
                   check=True, timeout=180)


def native(data, public, temporary, output):
    data["kind"] = "native-component"
    spec = data["parameters"]["native"]
    source_path = PurePosixPath(public.get("project-directory", ".")) / spec["module"] / "pom.xml"
    pom = ET.fromstring(source_file(data, source_path.as_posix()))
    coordinate = {"groupId": pom.findtext("{*}groupId") or pom.findtext("{*}parent/{*}groupId"),
                  "artifactId": pom.findtext("{*}artifactId"), "version": data["version"]}
    output.mkdir()
    for target in spec["platforms"]:
        receipt = authority.obtain(data["source"], data["build"]["run_id"], data["build"]["run_attempt"], "native:" + target, "native-component")
        name = f"unsigned-native-{data['build']['run_attempt']}-{target}"
        directory = download(receipt, name, receipt["producer_job"], temporary / target)
        component = manifest.read_json(manifest.safe_file(directory, "native-component.json"))
        native_components.verify_component(component, directory, data, spec, target, require_signature=False)
        if component["coordinate"] != coordinate:
            raise ValueError("Native artifact coordinate differs from its immutable source module")
        destination = output / f"native-component-{data['build']['run_attempt']}-{target}"
        shutil.copytree(directory, destination)
        smoke = destination / "smoke.log"
        smoke.write_bytes(smoke.read_bytes() + b"\nActions producer authority:\n" + rfc8785.dumps(receipt) + b"\n")
        component["smoke"]["sha256"] = manifest.sha256(smoke.read_bytes())
        (destination / "native-component.json").write_bytes(rfc8785.dumps(component))
        signature(destination / "native-component.json")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--kind", choices=("maven-library", "service", "native-component"), required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists() or args.output.is_symlink():
        raise ValueError("Signer output must be a fresh directory")
    public = json.loads(os.environ["BUILD_INPUTS_JSON"])
    data = expected(args.kind, public, os.environ, prepare.oidc_claims(), prepare.resolve_workflow(TAG))
    with tempfile.TemporaryDirectory(prefix="axiz-signer-", dir=os.environ["RUNNER_TEMP"]) as temporary:
        if args.kind == "native-component":
            native(data, public, Path(temporary), args.output)
        else:
            product(data, public, Path(temporary), args.output)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Mandatory API gate: reviewed first-release signatures or a genuine published v2 baseline."""
import argparse
import base64
import hashlib
import json
import os
import re
import subprocess
import sys
import urllib.parse
import urllib.request
from defusedxml import ElementTree as ET
import zipfile
from pathlib import Path

from maven_reactor import NS, text, modules

INTERNAL = {"axiz-architecture-tests", "axiz-store-integration-tests"}


def sha(data):
    return hashlib.sha256(data).hexdigest()


def public_api(jar):
    """Inspect actual class-file signatures; do not compare a JAR to itself as a baseline."""
    with zipfile.ZipFile(jar) as archive:
        names = sorted(name[:-6].replace("/", ".") for name in archive.namelist()
                       if name.endswith(".class") and not name.endswith(("package-info.class", "module-info.class"))
                       and not name.startswith("META-INF/"))
    signatures = []
    for offset in range(0, len(names), 64):
        output = subprocess.run(["javap", "-protected", "-constants", "-classpath", str(jar), *names[offset:offset + 64]],
                                check=True, capture_output=True, text=True, timeout=90).stdout
        for section in re.split(r'(?m)^Compiled from .*\n', output):
            lines = [line.strip() for line in section.splitlines() if line.strip()]
            if lines and lines[0].startswith(("public ", "protected ")):
                signatures.append("\n".join([lines[0], *sorted(lines[1:-1]), lines[-1]]))
    return "\n\n".join(signatures) + "\n"


def published_versions(nexus, coordinates, opener=urllib.request.urlopen):
    """Query only final releases of this product. Candidates and local M2 are never baselines."""
    parsed = urllib.parse.urlsplit(nexus)
    if parsed.scheme not in {"http", "https"} or not parsed.netloc or parsed.query or parsed.fragment or parsed.username:
        raise ValueError("Invalid final publication Nexus URL")
    metadata_request = urllib.request.Request(nexus.rstrip("/") + "/service/rest/v1/repositories/maven-releases")
    username, password = os.getenv("NEXUS_USERNAME"), os.getenv("NEXUS_PASSWORD")
    if username and password:
        metadata_request.add_header("Authorization", "Basic " + base64.b64encode(f"{username}:{password}".encode()).decode())
    with opener(metadata_request, timeout=30) as response:
        repository = json.load(response)
    if not isinstance(repository, dict) or any(repository.get(key) != value for key, value in
            {"name": "maven-releases", "format": "maven2", "type": "hosted"}.items()):
        raise ValueError("Final publication repository is missing or ambiguous")
    token, seen, versions = None, set(), set()
    while True:
        params = {"repository": "maven-releases", "group": "pe.axiz"}
        if token:
            params["continuationToken"] = token
        request = urllib.request.Request(nexus.rstrip("/") + "/service/rest/v1/search?" + urllib.parse.urlencode(params))
        username, password = os.getenv("NEXUS_USERNAME"), os.getenv("NEXUS_PASSWORD")
        if username and password:
            request.add_header("Authorization", "Basic " + base64.b64encode(f"{username}:{password}".encode()).decode())
        # Errors, invalid JSON, incomplete pagination and ambiguous responses fail closed.
        with opener(request, timeout=30) as response:
            data = json.load(response)
        if not isinstance(data, dict) or not isinstance(data.get("items"), list) or "continuationToken" not in data:
            raise ValueError("Invalid response from final publication repository")
        for item in data["items"]:
            if not isinstance(item, dict) or not all(key in item for key in ("repository", "group", "name", "version")):
                raise ValueError("Incomplete publication metadata")
            if not all(isinstance(item[key], str) and item[key].strip()
                       for key in ("repository", "group", "name", "version")):
                raise ValueError("Invalid publication metadata")
            if item["repository"] != "maven-releases":
                raise ValueError("Publication search returned a different repository")
            if item["group"] == "pe.axiz" and item["name"] in coordinates and isinstance(item["version"], str) and item["version"].startswith("2."):
                versions.add(item["version"])
        token = data["continuationToken"]
        if token is None:
            return versions
        if not isinstance(token, str) or not token or token in seen:
            raise ValueError("Invalid publication pagination")
        seen.add(token)


def validate_first_release(policy, versions):
    if policy != {"schema": 1, "mode": "first-release", "version": "2.0.0", "baseline": None}:
        raise ValueError("First-release mode is restricted to the initial 2.0.0 contract")
    if versions:
        raise ValueError("First-release mode is forbidden: this product already has published v2 releases")


def release_baseline(root, nexus, version, directory, opener=urllib.request.urlopen):
    """Pin actual final-repository bytes outside Maven's candidate/local cache."""
    directory.mkdir(parents=True, exist_ok=True)
    records = {}
    for artifact, (_, packaging, _) in modules(root).items():
        if packaging == "pom" or artifact in INTERNAL:
            continue
        if not re.fullmatch(r"[a-z0-9][a-z0-9-]*", artifact):
            raise ValueError("Invalid framework coordinate")
        filename = f"{artifact}-{version}.jar"
        url = nexus.rstrip("/") + f"/repository/maven-releases/pe/axiz/{artifact}/{version}/{filename}"
        request = urllib.request.Request(url)
        username, password = os.getenv("NEXUS_USERNAME"), os.getenv("NEXUS_PASSWORD")
        if username and password:
            request.add_header("Authorization", "Basic " + base64.b64encode(f"{username}:{password}".encode()).decode())
        with opener(request, timeout=30) as response:
            data = response.read(50 * 1024 * 1024 + 1)
        if len(data) > 50 * 1024 * 1024:
            raise ValueError("Baseline JAR exceeds the bounded download size")
        path = directory / filename
        path.write_bytes(data)
        with zipfile.ZipFile(path) as archive:
            if archive.testzip() is not None:
                raise ValueError("Invalid published baseline JAR: " + artifact)
        records[artifact] = sha(data)
    (directory / "baseline.json").write_text(json.dumps({"schema": 1, "version": version,
        "origin": nexus.rstrip("/") + "/repository/maven-releases", "artifacts": records}, indent=2) + "\n")


def verify_baseline(root, nexus, version, directory):
    data = json.loads((directory / "baseline.json").read_text())
    expected = {artifact for artifact, (_, packaging, _) in modules(root).items()
                if packaging != "pom" and artifact not in INTERNAL}
    if data.get("schema") != 1 or data.get("version") != version or data.get("origin") != nexus.rstrip("/") + "/repository/maven-releases" or set(data.get("artifacts", {})) != expected:
        raise ValueError("Missing or invalid published baseline evidence")
    for artifact, digest in data["artifacts"].items():
        if sha((directory / f"{artifact}-{version}.jar").read_bytes()) != digest:
            raise ValueError("Published baseline was modified: " + artifact)


def inventory(root, version, output):
    output.mkdir(parents=True, exist_ok=True)
    entries = {}
    for artifact, (directory, packaging, project) in sorted(modules(root).items()):
        skip = text(project, "m:properties/m:axiz.api.skip") == "true"
        if artifact in INTERNAL:
            if not skip or text(project, "m:properties/m:maven.deploy.skip") != "true" or any(
                    p.name != "package-info.java" for p in (directory / "src/main/java").rglob("*.java")):
                raise ValueError("Only unpublished test-only modules may skip: " + artifact)
            continue
        if skip:
            raise ValueError("Published API cannot skip the gate: " + artifact)
        if packaging == "pom":
            continue
        jar = directory / "target" / f"{artifact}-{version}.jar"
        signatures = public_api(jar)
        (output / f"{artifact}.txt").write_text(signatures)
        entries[artifact] = {"apiSha256": sha(signatures.encode()), "jarSha256": sha(jar.read_bytes())}
    if not entries:
        raise ValueError("No published API artifacts were inspected")
    return {"schema": 1, "version": version, "artifacts": entries}


def verify_reviewed_signatures(root, approved):
    for artifact, record in approved["artifacts"].items():
        if not re.fullmatch(r"[a-z0-9][a-z0-9-]*", artifact):
            raise ValueError("Invalid reviewed API coordinate")
        signature = root / "docs/api/signatures" / f"{artifact}.txt"
        if sha(signature.read_bytes()) != record["apiSha256"]:
            raise ValueError("Reviewed API signatures differ from their inventory: " + artifact)


def check_signatures(observed, approved):
    if approved.get("schema") != 1 or approved.get("version") != observed["version"]:
        raise ValueError("Reviewed API inventory version/schema does not match")
    if set(observed["artifacts"]) != set(approved.get("artifacts", {})):
        raise ValueError("Published module set differs from the reviewed API inventory")
    for artifact, item in observed["artifacts"].items():
        if item["apiSha256"] != approved["artifacts"][artifact]["apiSha256"]:
            raise ValueError("Unreviewed public/protected API: " + artifact)


def check_baseline_version(baseline, version):
    if not re.fullmatch(r"2\.\d+\.\d+", baseline) or tuple(map(int, baseline.split("."))) >= tuple(map(int, version.split("."))):
        raise ValueError("Baseline must be a genuinely earlier published v2 release")


def check_comparisons(root, baseline, version, baseline_directory=None):
    check_baseline_version(baseline, version)
    for artifact, (directory, packaging, _) in modules(root).items():
        if packaging == "pom" or artifact in INTERNAL:
            continue
        jar = directory / "target" / f"{artifact}-{version}.jar"
        report = directory / "target/japicmp/api-compat.xml"
        if not jar.is_file() or not report.is_file() or report.stat().st_mtime_ns < jar.stat().st_mtime_ns:
            raise ValueError("Missing/stale mandatory API comparison: " + artifact)
        if baseline_directory is not None:
            previous = baseline_directory / f"{artifact}-{baseline}.jar"
            if not previous.is_file() or report.stat().st_mtime_ns < previous.stat().st_mtime_ns:
                raise ValueError("API comparison predates its pinned baseline: " + artifact)
        result = ET.parse(report).getroot()
        expected = {"oldVersion": baseline, "newVersion": version, "oldJar": f"{artifact}-{baseline}.jar",
                    "newJar": jar.name, "ignoreMissingClasses": "false", "accessModifier": "PROTECTED"}
        if any(result.get(key) != value for key, value in expected.items()):
            raise ValueError("API report does not compare the required published artifacts: " + artifact)
        for node in result.iter():
            if node.get("binaryCompatible") == "false" or node.get("sourceCompatible") == "false":
                raise ValueError("Incompatible public API: " + artifact)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path("."))
    parser.add_argument("--output", type=Path, default=Path("target/api-evidence"))
    parser.add_argument("--nexus-url", default=os.getenv("NEXUS_URL"))
    preparation = parser.add_mutually_exclusive_group()
    preparation.add_argument("--inventory-only", action="store_true", help="Local review preparation only; never a passing release gate")
    preparation.add_argument("--prepare-baseline", action="store_true", help="Pin the actual published baseline before Maven comparison; never passes the gate alone")
    parser.add_argument("--baseline-directory", type=Path, default=Path("target/api-baseline"))
    args = parser.parse_args()
    root = args.root.resolve()
    args.output.mkdir(parents=True, exist_ok=True)
    status = {"schema": 1, "passed": False, "commit": os.getenv("GITHUB_SHA"), "run": os.getenv("GITHUB_RUN_ID"),
              "attempt": os.getenv("GITHUB_RUN_ATTEMPT"), "preparationOnly": args.inventory_only or args.prepare_baseline}
    try:
        policy = json.loads((root / "docs/api/release-policy.json").read_text())
        status["policy"] = policy
        version = policy["version"]
        if not re.fullmatch(r"2\.\d+\.\d+", version):
            raise ValueError("Invalid v2 target version")
        if policy["mode"] not in {"first-release", "published-baseline"}:
            raise ValueError("Unknown API gate mode")
        if not args.inventory_only:
            if not args.nexus_url:
                raise ValueError("NEXUS_URL is mandatory; repository lookup must not be skipped")
            versions = published_versions(args.nexus_url, set(modules(root)))
            status["publishedVersions"] = sorted(versions)
            if policy["mode"] == "first-release":
                validate_first_release(policy, versions)
            elif policy["baseline"] not in versions:
                raise ValueError("Required v2 baseline is not published in maven-releases")
        if args.prepare_baseline:
            if policy["mode"] != "published-baseline":
                raise ValueError("Baseline preparation requires published-baseline mode")
            check_baseline_version(policy["baseline"], version)
            release_baseline(root, args.nexus_url, policy["baseline"], args.baseline_directory)
            print("Published baseline pinned from final repository; API gate not yet passed")
            return 0
        observed = inventory(root, version, args.output / "signatures")
        (args.output / "observed.json").write_text(json.dumps(observed, indent=2) + "\n")
        if args.inventory_only:
            print("Review preparation only: signatures and artifact hashes exported; release gate NOT passed")
        else:
            approved = json.loads((root / "docs/api/public-api.json").read_text())
            verify_reviewed_signatures(root, approved)
            check_signatures(observed, approved)
            if policy["mode"] == "published-baseline":
                verify_baseline(root, args.nexus_url, policy["baseline"], args.baseline_directory)
                check_comparisons(root, policy["baseline"], version, args.baseline_directory)
            status["passed"] = True
            print("API gate passed: final publication repository verified, every published API inspected")
    except (OSError, ValueError, KeyError, TypeError, ET.ParseError, subprocess.SubprocessError, zipfile.BadZipFile) as failure:
        status["error"] = str(failure)
        print("::error::" + str(failure), file=sys.stderr)
    finally:
        (args.output / "result.json").write_text(json.dumps(status, indent=2) + "\n")
    return int(not (status["passed"] or args.inventory_only and "error" not in status))


if __name__ == "__main__":
    sys.exit(main())

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


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, file, code, message, headers, url):
        raise ValueError("Registry redirects cannot receive publication credentials")


def registry_repository(url):
    parsed = urllib.parse.urlsplit(url)
    if (parsed.scheme != "https" or parsed.netloc != "maven.pkg.github.com"
            or not re.fullmatch(r"/[a-z0-9][a-z0-9-]*/[A-Za-z0-9][A-Za-z0-9_.-]*/?", parsed.path)
            or parsed.query or parsed.fragment):
        raise ValueError("Invalid GitHub Maven publication URL")
    return parsed.path.strip("/")


def github_json(path, opener):
    token = os.getenv("PACKAGES_TOKEN")
    if not token:
        raise ValueError("PACKAGES_TOKEN with read:packages is mandatory")
    request = urllib.request.Request("https://api.github.com/" + path, headers={
        "Authorization": "Bearer " + token, "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28"})
    with opener(request, timeout=30) as response:
        return json.load(response)


def package_pages(path, opener):
    seen = set()
    for page in range(1, 10001):
        separator = "&" if "?" in path else "?"
        data = github_json(path + separator + f"per_page=100&page={page}", opener)
        if not isinstance(data, list) or len(data) > 100:
            raise ValueError("Invalid publication pagination")
        for item in data:
            if not isinstance(item, dict) or type(item.get("id")) is not int or item["id"] <= 0:
                raise ValueError("Incomplete publication metadata")
            if item["id"] in seen:
                raise ValueError("Repeated publication pagination")
            seen.add(item["id"])
            yield item
        if len(data) < 100:
            return
    raise ValueError("Publication pagination exceeds its bound")


def published_versions(registry, coordinates, opener=None):
    """Authenticate and enumerate GitHub Packages; 401/403/404 never mean an empty registry."""
    if opener is None:
        opener = urllib.request.build_opener(NoRedirect()).open
    repository = registry_repository(registry)
    info = github_json("repos/" + repository, opener)
    if (not isinstance(info, dict) or info.get("full_name", "").lower() != repository.lower()
            or info.get("owner", {}).get("type") not in {"User", "Organization"}):
        raise ValueError("Final publication repository is missing or ambiguous")
    owner = repository.split("/")[0]
    prefix = ("orgs/" if info["owner"]["type"] == "Organization" else "users/") + owner + "/packages"
    versions = set()
    for package in package_pages(prefix + "?package_type=maven", opener):
        if (package.get("package_type") != "maven" or not isinstance(package.get("name"), str)
                or not isinstance(package.get("repository"), dict)
                or not isinstance(package["repository"].get("full_name"), str)):
            raise ValueError("Invalid publication metadata")
        if package["repository"]["full_name"].lower() != repository.lower():
            continue
        if package["name"] not in {"pe.axiz." + name for name in coordinates}:
            continue
        name = urllib.parse.quote(package["name"], safe="")
        for version in package_pages(prefix + "/maven/" + name + "/versions?state=active", opener):
            value = version.get("name")
            if not isinstance(value, str) or not value.strip():
                raise ValueError("Invalid publication version metadata")
            if value.startswith("2."):
                versions.add(value)
    return versions


def validate_first_release(policy, versions):
    if policy != {"schema": 1, "mode": "first-release", "version": "2.0.0", "baseline": None}:
        raise ValueError("First-release mode is restricted to the initial 2.0.0 contract")
    if versions:
        raise ValueError("First-release mode is forbidden: this product already has published v2 releases")


def release_baseline(root, registry, version, directory, opener=None):
    """Pin actual final-repository bytes outside Maven's candidate/local cache."""
    registry_repository(registry)
    if opener is None:
        opener = urllib.request.build_opener(NoRedirect()).open
    directory.mkdir(parents=True, exist_ok=True)
    records = {}
    for artifact, (_, packaging, _) in modules(root).items():
        if packaging == "pom" or artifact in INTERNAL:
            continue
        if not re.fullmatch(r"[a-z0-9][a-z0-9-]*", artifact):
            raise ValueError("Invalid framework coordinate")
        filename = f"{artifact}-{version}.jar"
        url = registry.rstrip("/") + f"/pe/axiz/{artifact}/{version}/{filename}"
        request = urllib.request.Request(url)
        username, password = os.getenv("PACKAGES_USERNAME"), os.getenv("PACKAGES_TOKEN")
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
        "origin": registry.rstrip("/"), "artifacts": records}, indent=2) + "\n")


def verify_baseline(root, registry, version, directory):
    data = json.loads((directory / "baseline.json").read_text())
    expected = {artifact for artifact, (_, packaging, _) in modules(root).items()
                if packaging != "pom" and artifact not in INTERNAL}
    if data.get("schema") != 1 or data.get("version") != version or data.get("origin") != registry.rstrip("/") or set(data.get("artifacts", {})) != expected:
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
    parser.add_argument("--packages-url", default=os.getenv("PACKAGES_URL"))
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
            if not args.packages_url:
                raise ValueError("PACKAGES_URL is mandatory; repository lookup must not be skipped")
            versions = published_versions(args.packages_url, set(modules(root)))
            status["publishedVersions"] = sorted(versions)
            if policy["mode"] == "first-release":
                validate_first_release(policy, versions)
            elif policy["baseline"] not in versions:
                raise ValueError("Required v2 baseline is not published in GitHub Packages")
        if args.prepare_baseline:
            if policy["mode"] != "published-baseline":
                raise ValueError("Baseline preparation requires published-baseline mode")
            check_baseline_version(policy["baseline"], version)
            release_baseline(root, args.packages_url, policy["baseline"], args.baseline_directory)
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
                verify_baseline(root, args.packages_url, policy["baseline"], args.baseline_directory)
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

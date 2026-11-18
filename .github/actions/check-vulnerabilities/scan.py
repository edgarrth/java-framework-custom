#!/usr/bin/env python3
"""Run complete pinned scans, bind their evidence, and apply protected risk decisions."""
import argparse
import hashlib
import json
import os
import re
import subprocess
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import quote

from evaluate import DIGEST, SEVERITIES, evaluate, read_json
from protected import from_base, git


def review_protection(repository, branch):
    """Confirm mandatory code-owner review; the protected bit alone is insufficient."""
    endpoint = "repos/" + repository
    result = subprocess.run(["gh", "api", "--paginate", "--slurp", endpoint + "/rules/branches/"
                             + quote(branch, safe="") + "?per_page=100"],
                            capture_output=True, text=True, check=True, timeout=30)
    pages = json.loads(result.stdout)
    if not isinstance(pages, list) or len(pages) > 10 or any(not isinstance(p, list) for p in pages):
        raise ValueError("Incomplete active branch rule evidence")
    for rule in (rule for page in pages for rule in page):
        parameters = rule.get("parameters", {})
        if (rule.get("type") == "pull_request"
                and parameters.get("require_code_owner_review") is True
                and parameters.get("required_approving_review_count", 0) >= 1):
            return {"kind": "active-ruleset", "rule": rule}
    # Classic protection needs Administration:read. An unreadable response cannot authorize risk.
    result = subprocess.run(["gh", "api", endpoint + "/branches/" + quote(branch, safe="") + "/protection"],
                            capture_output=True, text=True, check=True, timeout=30)
    protection = json.loads(result.stdout)
    reviews = protection.get("required_pull_request_reviews", {})
    if (reviews.get("require_code_owner_reviews") is not True
            or reviews.get("required_approving_review_count", 0) < 1):
        raise ValueError("Protected policy requires mandatory code-owner approval")
    return {"kind": "classic-protection", "reviews": reviews}


def protected_base(root, repository, branch, requested):
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository) or not branch:
        raise ValueError("Trusted repository and protected branch context are required")
    result = subprocess.run(["gh", "api", "repos/" + repository + "/branches/" + quote(branch, safe="")],
                            capture_output=True, text=True, check=True, timeout=30)
    evidence = json.loads(result.stdout)
    if evidence.get("protected") is not True or evidence.get("name") != branch:
        raise ValueError("The policy branch is not protected according to GitHub")
    current = evidence.get("commit", {}).get("sha", "")
    if not re.fullmatch(r"[0-9a-f]{40}", current):
        raise ValueError("Missing protected commit identity")
    base = requested or current
    if not re.fullmatch(r"[0-9a-f]{40}", base):
        raise ValueError("Invalid base commit")
    git(root, "merge-base", "--is-ancestor", base, current)
    return base, {"repository": repository, "branch": branch, "protected": True,
                  "base": base, "observedHead": current, "reviewProtection": review_protection(repository, branch)}


def planned_scans(root, mode, scope, metadata=None, sbom=None):
    """Build the complete expected set from trusted build metadata, never from submitted reports."""
    scans = []
    commit = git(root, "rev-parse", "HEAD").strip()
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError("The scanned checkout needs an exact source commit")
    # This is a commit identity binding, not a claim to hash all generated/untracked build files.
    source_digest = "sha256:" + hashlib.sha256(("git-commit:" + commit).encode()).hexdigest()
    if mode in {"source", "service"}:
        scans.append({"kind": "source", "scope": scope + ":source", "artifact": str(root),
                      "digest": source_digest, "platform": "", "sourceCommit": commit})
    if mode == "sbom":
        path = Path(sbom or "")
        if not path.is_absolute():
            path = root / path
        document = read_json(path)
        if document.get("bomFormat") != "CycloneDX" or not document.get("components"):
            raise ValueError("A complete nonempty CycloneDX SBOM is required")
        scans.append({"kind": "sbom", "scope": scope + ":sbom", "artifact": str(path), "platform": "",
                      "digest": "sha256:" + hashlib.sha256(path.read_bytes()).hexdigest(), "sourceCommit": commit})
    if mode == "service":
        if not metadata or metadata.get("source", {}).get("sha") != commit:
            raise ValueError("Service metadata does not match the scanned checkout")
        image = metadata["artifacts"]["image"]
        repository = image["repository"]
        if not re.fullmatch(r"[a-z0-9][a-z0-9._:-]*(?:/[a-z0-9][a-z0-9._-]*)+", repository):
            raise ValueError("Invalid registry image repository")
        platforms = metadata["parameters"]["platforms"]
        if not platforms or len(set(platforms)) != len(platforms):
            raise ValueError("Explicit unique image platforms are required")
        if image.get("platforms") and set(image["platforms"]) != set(platforms):
            raise ValueError("Image manifests do not cover every expected platform")
        for platform in platforms:
            if platform not in {"linux/amd64", "linux/arm64"}:
                raise ValueError("Unsupported image platform")
            digest = image.get("platforms", {}).get(platform, image["digest"])
            if not DIGEST.fullmatch(digest):
                raise ValueError("Invalid immutable image digest")
            scans.append({"kind": "image", "scope": scope + ":" + platform,
                          "artifact": repository + "@" + digest, "digest": digest, "platform": platform,
                          "sourceCommit": commit})
    if not scans:
        raise ValueError("No scan scope selected")
    return scans


def run_scans(scans, output):
    version = subprocess.run(["trivy", "--version", "--format", "json"], capture_output=True,
                             text=True, check=True, timeout=30)
    if json.loads(version.stdout).get("Version") != "0.76.2":
        raise ValueError("The scanner must be the pinned Trivy 0.76.2")
    empty_config, empty_ignore = output / "scanner-config.yaml", output / "scanner-ignore"
    # Build tools are executable supply-chain dependencies, even when marked dev-only.
    empty_config.write_text("pkg:\n  include-dev-deps: true\n")
    empty_ignore.write_text("")
    descriptors = []
    for index, scan in enumerate(scans):
        name = f"scan-{index}-{scan['kind']}.json"
        path = output / name
        command = ["trivy", {"source": "fs", "sbom": "sbom", "image": "image"}[scan["kind"]],
                   "--config", str(empty_config), "--ignorefile", str(empty_ignore),
                   "--severity", ",".join(sorted(SEVERITIES)), "--ignore-unfixed=false",
                   "--exit-code", "0", "--format", "json", "--output", str(path), "--timeout", "10m"]
        if scan["kind"] != "sbom":
            command += ["--scanners", "vuln"]
        if scan["kind"] == "image":
            command += ["--platform", scan["platform"]]
        command.append(scan["artifact"])
        environment = {k: v for k, v in os.environ.items() if not k.startswith("TRIVY_")}
        # Only registry transport credentials/cache survive; a PR cannot set severity/skip filters.
        for key in ("TRIVY_USERNAME", "TRIVY_PASSWORD", "TRIVY_CACHE_DIR"):
            if key in os.environ:
                environment[key] = os.environ[key]
        with (output / (name + ".log")).open("w") as log:
            result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, env=environment,
                                    check=False, timeout=660)
        if result.returncode != 0:
            raise ValueError("Scanner execution failed; see preserved scanner diagnostics")
        descriptor = {k: v for k, v in scan.items() if k != "sourceCommit"}
        descriptor.update(path=name, sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
                          scanner="trivy", scannerVersion="0.76.2", exitCode=0,
                          severities=sorted(SEVERITIES))
        descriptors.append(descriptor)
    return {"schema": 1, "reports": descriptors}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", required=True)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--branch", required=True)
    parser.add_argument("--base", default="")
    parser.add_argument("--mode", required=True, choices=["source", "sbom", "service"])
    parser.add_argument("--scope", required=True)
    parser.add_argument("--metadata")
    parser.add_argument("--sbom")
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    root, output = Path(args.root).resolve(), Path(args.output).resolve()
    if not output.is_relative_to(root):
        raise ValueError("Keep scan evidence inside the checkout")
    output.mkdir(parents=True, exist_ok=True)
    # Do not let submitted reports or symlinked output files influence a fresh trusted run.
    if any(output.iterdir()):
        raise ValueError("Use an empty dedicated scan evidence directory")
    try:
        base, provenance = protected_base(root, args.repository, args.branch, args.base)
        policy, history = from_base(root, base)
        metadata = read_json(args.metadata) if args.metadata else None
        scans = planned_scans(root, args.mode, args.scope, metadata, args.sbom)
        provenance["scans"] = scans
        (output / "provenance.json").write_text(json.dumps(provenance, indent=2) + "\n")
        expected = run_scans(scans, output)
        (output / "expected.json").write_text(json.dumps(expected, indent=2) + "\n")
        # Retain release context for audit; MEDIUM/HIGH/CRITICAL always block any release.
        release = bool(metadata and re.fullmatch(r"[0-9]+\.[0-9]+\.0", metadata.get("version", "")))
        result = evaluate(policy, history, expected, output, datetime.now(timezone.utc), release)
    except (ValueError, OSError, KeyError, TypeError, subprocess.SubprocessError) as failure:
        result = {"schema": 2, "decision": "ERROR", "reason": str(failure)}
    (output / "decision.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print("Vulnerability policy: " + result["decision"])
    return 0 if result["decision"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())

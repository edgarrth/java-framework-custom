#!/usr/bin/env python3
"""Validate explicit secrets and the narrowly scoped immutable Axiz release exception."""
import argparse
from pathlib import Path
import re
import sys

import yaml

from privileged_jobs import validate as privileged_errors

GUARD = "axiz-workflows-trust"
PREFIX = "edgarrth/java-framework-custom/"
VERIFY = '''set -euo pipefail
release=$(gh api "repos/edgarrth/java-framework-custom/releases/tags/$AXIZ_WORKFLOWS_VERSION")
jq -e --arg tag "$AXIZ_WORKFLOWS_VERSION" '.tag_name == $tag and .immutable == true and .draft == false' <<< "$release" >/dev/null'''
TOKEN = "${{ secrets.AXIZ_WORKFLOWS_READ_TOKEN || github.token }}"
NEXUS = {"NEXUS_URL", "NEXUS_USERNAME", "NEXUS_PASSWORD"}
READ = {"AXIZ_WORKFLOWS_READ_TOKEN"}
CONTRACTS = {
    "pr.yaml": NEXUS | READ | {"AXIZ_NAMING_GUARD"},
    "lib-build.yaml": READ | {"NEXUS_URL", "NEXUS_READ_USERNAME", "NEXUS_READ_PASSWORD", "SONAR_HOST_URL", "SONAR_TOKEN", "CANDIDATE_READ_TOKEN"},
    "lib-deploy.yaml": READ | {"BUILD_READ_TOKEN", "NEXUS_URL", "NEXUS_PUBLISH_USERNAME", "NEXUS_PUBLISH_PASSWORD"},
    "service-build.yaml": READ | {"CANDIDATE_READ_TOKEN", "NEXUS_URL", "NEXUS_READ_USERNAME", "NEXUS_READ_PASSWORD", "BUILD_DESTINATION", "REGISTRY_BUILD_USERNAME", "REGISTRY_BUILD_PASSWORD"},
    "service-deploy.yaml": READ | {"BUILD_READ_TOKEN", "REGISTRY_READ_USERNAME", "REGISTRY_READ_PASSWORD", "GITOPS_APP_ID", "GITOPS_APP_PRIVATE_KEY", "ARGOCD_AUTH_TOKEN"},
    "native.yaml": READ | {"NEXUS_URL", "NEXUS_READ_USERNAME", "NEXUS_READ_PASSWORD", "CANDIDATE_READ_TOKEN"},
    "native-nightly.yaml": NEXUS | READ,
    "release.yaml": {"RELEASE_APP_ID", "RELEASE_APP_PRIVATE_KEY"},
}


def guarded_condition(condition):
    prefix = "${{ needs.axiz-workflows-trust.result == 'success' && ("
    suffix = ") }}"
    if not condition.startswith(prefix) or not condition.endswith(suffix):
        return False
    depth, quoted = 0, False
    for char in condition[len(prefix):-len(suffix)]:
        if char == "'":
            quoted = not quoted
        elif not quoted:
            if char == "(":
                depth += 1
            elif char == ")":
                depth -= 1
                if depth < 0:
                    return False
    return depth == 0 and not quoted


def validate(document):
    errors = privileged_errors(document)
    jobs = document.get("jobs", {})
    guard = jobs.get(GUARD)
    for name, job in jobs.items():
        secrets = job.get("secrets", {})
        if secrets == "inherit":
            errors.append(f"{name}: inherited secrets are forbidden")
        for call in [job, *job.get("steps", [])]:
            ref = call.get("uses", "")
            if not ref or ref.startswith("./"):
                continue
            target, separator, version = ref.rpartition("@")
            if not separator:
                errors.append(f"{name}: missing immutable action reference: {ref}")
                continue
            internal = target.startswith(PREFIX)
            if internal and "/.github/workflows/" in target:
                contract = CONTRACTS.get(target.rsplit("/", 1)[-1])
                if contract is None:
                    errors.append(f"{name}: unreviewed reusable workflow contract: {target}")
                elif not isinstance(secrets, dict) or set(secrets) - contract:
                    errors.append(f"{name}: secrets exceed the called workflow contract")
            if re.fullmatch(r"[a-f0-9]{40}", version):
                continue
            if not internal or not re.fullmatch(r"axiz-workflows-v\d+\.\d+\.\d+", version):
                errors.append(f"{name}: pin external actions by SHA and internal releases by exact version")
                continue
            needs = job.get("needs", [])
            needs = [needs] if isinstance(needs, str) else needs
            if GUARD not in needs:
                errors.append(f"{name}: no direct dependency on {GUARD}")
            condition = job.get("if")
            # Custom conditions must put the success check outside the entire original expression.
            if condition and not guarded_condition(str(condition)):
                errors.append(f"{name}: condition may bypass failed release verification")
            expected = {
                "runs-on": "ubuntu-24.04", "timeout-minutes": 3,
                "permissions": {"contents": "read"},
                "steps": [{"name": "Verify immutable Axiz workflow release", "env": {
                    "GH_TOKEN": TOKEN, "GH_HOST": "github.com", "AXIZ_WORKFLOWS_VERSION": version,
                }, "shell": "bash", "run": VERIFY + "\n"}],
            }
            if guard != expected:
                errors.append(f"{name}: release verification differs from the reviewed fail-closed job")
    return sorted(set(errors))


def validate_monorepo_file(path, document, version):
    """File-specific release boundaries supplement the reusable-workflow security policy."""
    errors = []
    text = path.read_text()
    expected = "axiz-workflows-v" + version
    for line in text.splitlines():
        match = re.search(r"uses:\s*(" + re.escape(PREFIX) + r"[^\s]+)", line)
        if match and (match[1].rsplit("@", 1)[-1] != expected or "# x-release-please-version" not in line):
            errors.append("internal references must use " + expected + " # x-release-please-version")
    trigger = document.get("on", document.get(True, {})) or {}
    push = trigger.get("push", {}) or {} if isinstance(trigger, dict) else {}
    tags = push.get("tags", []) if isinstance(push, dict) else []
    if path.name == "demo-ci.yaml" and "push" in trigger and (tags or "tags-ignore" in push or not push.get("branches")):
        errors.append("demo-ci must not subscribe to tag pushes")
    component = {"framework-ci.yaml": "axiz-framework", "tools-ci.yaml": "axiz-tools"}.get(path.name)
    if component and set(tags) != {component + "-v*", "candidate/" + component + "/**"}:
        errors.append("product tags must belong exclusively to " + component)
    return errors


def workflow_files(root):
    for path in root.rglob("*"):
        if not path.is_file() or any(p in {"target", ".git", "node_modules", ".venv"} for p in path.parts):
            continue
        if path.name.endswith((".yaml", ".yml", ".yaml.mustache", ".yml.mustache")):
            if ".github/workflows" in str(path) or "templates" in path.parts or path.name in {"action.yaml", "action.yml"}:
                yield path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path, nargs="?", default=Path("."))
    args = parser.parse_args()
    failed = False
    checked = 0
    version = (args.root / ".github/version.txt").read_text().strip() if (args.root / ".github/version.txt").is_file() else None
    for path in workflow_files(args.root):
        text = path.read_text()
        if ("jobs:" not in text and "runs:" not in text) or "uses:" not in text:
            continue
        # Mustache workflows switch delimiters; GitHub expressions remain literal YAML values.
        text = re.sub(r"^\{\{=<% %>=\}\}\s*", "", text)
        text = re.sub(r"^\s*<%[#/^][^\n]+%>\s*$", "", text, flags=re.M)
        try:
            document = yaml.safe_load(text)
            evaluated = {"permissions": {}, "jobs": {"composite": {"steps": document["runs"].get("steps", [])}}} if "runs" in document else document
            errors = validate(evaluated)
            if version and args.root / ".github" in path.parents:
                errors.extend(validate_monorepo_file(path, document, version))
        except (yaml.YAMLError, AttributeError, TypeError) as exc:
            errors = [f"invalid workflow: {exc}"]
        checked += 1
        for error in errors:
            print(f"{path}: {error}", file=sys.stderr)
            failed = True
    print(f"Checked workflow security in {checked} files")
    return int(failed)


if __name__ == "__main__":
    sys.exit(main())

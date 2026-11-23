#!/usr/bin/env python3
"""Valida los secretos explícitos y la excepción de release inmutable de Axiz, de alcance acotado."""
import argparse
import json
from pathlib import Path
import re
import sys

import yaml

from privileged_jobs import validate as privileged_errors
from internal_contracts import ROOT, TAG, validate_call
from workflow_sources import executable_files, read_document, workflow_files
from workflow_pins import product_files, validate_pins
from required_checks import token_permissions

GUARD = "axiz-workflows-trust"
PREFIX = "edgarrth/java-framework-custom/"
VERIFY = '''set -euo pipefail
release=$(gh api "repos/edgarrth/java-framework-custom/releases/tags/$AXIZ_WORKFLOWS_VERSION")
jq -e --arg tag "$AXIZ_WORKFLOWS_VERSION" '.tag_name == $tag and .immutable == true and .draft == false' <<< "$release" >/dev/null'''
TOKEN = "${{ secrets.AXIZ_WORKFLOWS_READ_TOKEN || github.token }}"
PACKAGES = {"PACKAGES_READ_USERNAME", "PACKAGES_READ_TOKEN"}
READ = {"AXIZ_WORKFLOWS_READ_TOKEN"}
CONTRACTS = {
    "pr.yaml": PACKAGES | READ | {"AXIZ_NAMING_GUARD"},
    "lib-build.yaml": READ | {"PACKAGES_READ_USERNAME", "PACKAGES_READ_TOKEN", "SONAR_HOST_URL", "SONAR_TOKEN", "CANDIDATE_READ_TOKEN"},
    "lib-deploy.yaml": READ | {"BUILD_READ_TOKEN"},
    "service-build.yaml": READ | {"CANDIDATE_READ_TOKEN", "PACKAGES_READ_USERNAME", "PACKAGES_READ_TOKEN", "BUILD_DESTINATION"},
    "service-deploy.yaml": READ | {"BUILD_READ_TOKEN", "BUILD_DESTINATION", "GITOPS_APP_CLIENT_ID", "GITOPS_APP_PRIVATE_KEY", "ARGOCD_AUTH_TOKEN"},
    "native.yaml": READ | {"PACKAGES_READ_USERNAME", "PACKAGES_READ_TOKEN", "CANDIDATE_READ_TOKEN"},
    "native-nightly.yaml": PACKAGES | READ,
    "release.yaml": {"RELEASE_APP_CLIENT_ID", "RELEASE_APP_PRIVATE_KEY"},
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


def validate(document, contracts_root=ROOT, *, snapshot=True, permission_exceptions=()):
    errors = privileged_errors(document) + token_permissions(document, permission_exceptions)
    jobs = document.get("jobs", {})
    guard = jobs.get(GUARD)
    for name, job in jobs.items():
        secrets = job.get("secrets", {})
        if secrets == "inherit":
            errors.append(f"{name}: inherited secrets are forbidden")
        for call in [job, *job.get("steps", [])]:
            errors.extend(f"{name}: {error}" for error in validate_call(call, contracts_root, snapshot=snapshot))
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
            if not internal or not TAG.fullmatch(version):
                errors.append(f"{name}: pin external actions by SHA and internal releases by exact version")
                continue
            needs = job.get("needs", [])
            needs = [needs] if isinstance(needs, str) else needs
            if GUARD not in needs:
                errors.append(f"{name}: no direct dependency on {GUARD}")
            condition = job.get("if")
                        # Las condiciones personalizadas deben colocar la verificación de éxito fuera de toda la expresión original.
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


def checkout_reference_errors(text, expected):
    errors = []
    lines = text.splitlines()
    visited = set()
    def walk(node):
        if id(node) in visited:
            return
        visited.add(id(node))
        if isinstance(node, yaml.MappingNode):
            fields = {key.value: value for key, value in node.value if isinstance(key, yaml.ScalarNode)}
            uses, options = fields.get("uses"), fields.get("with")
            if isinstance(uses, yaml.ScalarNode) and uses.value.startswith("actions/checkout@") and isinstance(options, yaml.MappingNode):
                values = {key.value: value for key, value in options.value if isinstance(key, yaml.ScalarNode)}
                repository, ref = values.get("repository"), values.get("ref")
                internal = repository is None or repository.value in {
                    "edgarrth/java-framework-custom", "${{ github.repository }}"}
                if internal and isinstance(ref, yaml.ScalarNode) and ref.value.startswith("axiz-workflows-"):
                    if ref.value != expected or "# x-release-please-version" not in lines[ref.start_mark.line]:
                        errors.append("internal checkout refs must use " + expected + " # x-release-please-version")
            for _, value in node.value:
                walk(value)
        elif isinstance(node, yaml.SequenceNode):
            for value in node.value:
                walk(value)
    walk(yaml.compose(text))
    return errors


def validate_monorepo_file(path, document, version, managed=True):
    """Los límites de release específicos de cada archivo complementan la política de seguridad de los workflows reutilizables."""
    errors = []
    text = path.read_text()
    expected = "axiz-workflows-v" + version
    for line in text.splitlines() if managed else []:
        match = re.search(r"uses:\s*(" + re.escape(PREFIX) + r"[^\s]+)", line)
        if match and (match[1].rsplit("@", 1)[-1] != expected or "# x-release-please-version" not in line):
            errors.append("internal references must use " + expected + " # x-release-please-version")
    if managed:
        errors.extend(checkout_reference_errors(text, expected))
    trigger = document.get("on", document.get(True, {})) or {}
    push = trigger.get("push", {}) or {} if isinstance(trigger, dict) else {}
    tags = push.get("tags", []) if isinstance(push, dict) else []
    if path.name == "demo-ci.yaml" and "push" in trigger and (tags or "tags-ignore" in push or not push.get("branches")):
        errors.append("demo-ci must not subscribe to tag pushes")
    component = {"framework-ci.yaml": "axiz-framework", "tools-ci.yaml": "axiz-tools"}.get(path.name)
    if component and set(tags) != {component + "-v*", "candidate/" + component + "/**"}:
        errors.append("product tags must belong exclusively to " + component)
    return errors


def check_tree(root, contracts_root=ROOT, *, scope="all"):
    root, contracts_root = Path(root).resolve(), Path(contracts_root).resolve()
    managed = product_files(root)
    if scope not in {"all", "executable"}:
        raise ValueError("scope must be all or executable")
    selected, diagnostics = executable_files(root)
    if scope == "all":
        selected = sorted(set(selected) | set(workflow_files(root)))
    pin_errors, bootstrap = validate_pins(root, contracts_root,
                                        paths=None if scope == "all" else selected,
                                        require_history=scope == "all")
    diagnostics.extend(pin_errors)
    permission_policy = contracts_root / '.github/policies/required-checks-v2.json'
    permission_records = json.loads(permission_policy.read_text())['permissionExceptions'] if permission_policy.is_file() else []
    checked = 0
    internal_calls = 0
    version = (root / ".github/version.txt").read_text().strip() if (root / ".github/version.txt").is_file() else None
    for path in selected:
        if not path.resolve().is_relative_to(root):
            diagnostics.append(str(path.relative_to(root)) + ": workflow source escapes the checkout")
            continue
        try:
            text = path.read_text()
            if "jobs:" not in text and "runs:" not in text:
                continue
            document = read_document(path)
            evaluated = {"permissions": {}, "jobs": {"composite": {"steps": document["runs"].get("steps", [])}}} if "runs" in document else document
            for job in evaluated.get("jobs", {}).values():
                internal_calls += sum(call.get("uses", "").startswith(PREFIX)
                                      for call in [job, *job.get("steps", [])])
            errors = validate(evaluated, contracts_root,
                              permission_exceptions={(row['scope'], row['permission']) for row in permission_records if row['path'] == str(path.relative_to(root)) and row.get('reason')},
                              snapshot=not managed or scope == "executable" or path.resolve() in managed or bootstrap)
            if version and managed and root / ".github" in path.parents:
                errors.extend(validate_monorepo_file(path, document, version, path.resolve() in managed))
        except (OSError, yaml.YAMLError, AttributeError, TypeError) as exc:
            errors = [f"invalid workflow: {exc}"]
        checked += 1
        for error in errors:
            diagnostics.append(f"{path.relative_to(root)}: {error}")
    return diagnostics, checked, internal_calls


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path, nargs="?", default=Path("."), help="Source tree to scan")
    parser.add_argument("--contracts-root", type=Path, default=ROOT,
                        help="Trusted Axiz contracts and Git history (default: this script's release)")
    parser.add_argument("--scope", choices=["all", "executable"], required=True,
                        help="Full monorepo including templates, or root execution and its local calls")
    args = parser.parse_args()
    errors, checked, internal_calls = check_tree(args.root, args.contracts_root, scope=args.scope)
    for error in errors:
        print(error, file=sys.stderr)
    print(f"Checked workflow security in {checked} files ({internal_calls} internal call contracts)")
    return int(bool(errors))


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""Keep reusable build and deploy workflows independent, with distinct credentials and inputs."""
import argparse
import re
import sys
from pathlib import Path

import yaml
from internal_contracts import TAG, validate_call, workflow_call

BUILD_CALL = re.compile(r"/(?:lib-build|service-build|native)\.yaml@")
DEPLOY_CALL = re.compile(r"/(?:lib-deploy|service-deploy)\.yaml@")
COMPILE = re.compile(r"(?:^|\s)(?:\./mvnw|mvn|mvnw)(?:\s|$)|jib:build|native:compile|native:test|docker\s+build|helm\s+package", re.M)


def calls(workflow):
    for job in workflow.get("jobs", {}).values():
        yield job
        yield from job.get("steps", [])


def validate(build, deploy):
    errors = []
    build_contract = workflow_call(build)
    deploy_contract = workflow_call(deploy)
    for name in ("build-run-id", "build-run-attempt", "manifest-sha256", "environment"):
        value = deploy_contract.get("inputs", {}).get(name, {})
        if value.get("required") is not True or "default" in value:
            errors.append(f"deploy: {name} must be an explicit required input without a default")
    if "manifest-sha256" not in build_contract.get("outputs", {}):
        errors.append("build: external manifest identity output is required")
    for name in build_contract.get("secrets", {}):
        if any(part in name for part in ("PUBLISH", "GITOPS", "ARGOCD")):
            errors.append(f"build: destination credential {name} is forbidden")
    for name in deploy_contract.get("secrets", {}):
        if name.startswith("REGISTRY_BUILD_"):
            errors.append(f"deploy: build registry write credential {name} is forbidden")
    for permissions in [build.get("permissions", {}), *(job.get("permissions", {}) for job in build.get("jobs", {}).values())]:
        if permissions == "write-all" or (isinstance(permissions, dict) and permissions.get("contents") == "write"):
            errors.append("build: contents write permission is forbidden")
    for name, job in build.get("jobs", {}).items():
        if job.get("permissions", {}).get("packages") == "write":
            for step in job.get("steps", []):
                if (COMPILE.search(step.get("run", "")) or "/setup-maven@" in step.get("uses", "")
                        or re.search(r"docker\s+run|java\s+-jar|npm\s+(?:run|ci|install)", step.get("run", ""))):
                    errors.append(name + ": package publication cannot execute project build/application code")
    for call in calls(build):
        if DEPLOY_CALL.search(str(call.get("uses", ""))):
            errors.append("build: may not invoke a deploy workflow")
        if re.search(r"gh\s+release\s+(?:create|upload|edit)|git\s+push|argocd\s+app\s+sync|helm\s+upgrade", call.get("run", "")):
            errors.append("build: release or environment mutation is forbidden")
    for call in calls(deploy):
        if BUILD_CALL.search(str(call.get("uses", ""))) or "/setup-maven@" in str(call.get("uses", "")):
            errors.append("deploy: may not invoke a build or set up Maven")
        if COMPILE.search(call.get("run", "")):
            errors.append("deploy: compiling or regenerating product artifacts is forbidden")
        if re.search(r"docker\s+(?:image\s+)?push|helm\s+push|cosign\s+(?:sign|attest)(?:\s|$)|oras\s+(?:push|copy)|skopeo\s+copy", call.get("run", "")):
            errors.append("deploy: writing to the build artifact registry is forbidden")
    return sorted(set(errors))


def validate_consumer(document, workflows, phase):
    """Validate actual consumer contracts against the selected immutable workflow source."""
    errors = []
    for name, job in document.get("jobs", {}).items():
        reference = job.get("uses", "")
        if not reference.startswith("edgarrth/java-framework-custom/.github/workflows/"):
            continue
        _, separator, revision = reference.rpartition("@")
        if not separator or not TAG.fullmatch(revision):
            errors.append(f"{name}: consumer must use an exact v2 workflow release")
            continue
        errors.extend(f"{name}: {error}" for error in validate_call(job, Path(workflows).parent.parent))
    for call in calls(document):
        reference = call.get("uses", "")
        command = call.get("run", "")
        if phase == "build":
            if DEPLOY_CALL.search(reference):
                errors.append("build: may not invoke a deploy workflow")
            if re.search(r"gh\s+release\s+(?:create|upload|edit)|git\s+push|argocd\s+app\s+sync|helm\s+upgrade", command):
                errors.append("build: release or environment mutation is forbidden")
            for key in call.get("secrets", {}) if isinstance(call.get("secrets", {}), dict) else []:
                if any(part in key for part in ("PUBLISH", "GITOPS", "ARGOCD")):
                    errors.append(f"build: destination credential {key} is forbidden")
        elif BUILD_CALL.search(reference) or "/setup-maven@" in reference or COMPILE.search(command):
            errors.append("deploy: building product artifacts is forbidden")
    if phase == "build":
        for permissions in [document.get("permissions", {}), *(job.get("permissions", {}) for job in document.get("jobs", {}).values())]:
            if permissions == "write-all" or isinstance(permissions, dict) and permissions.get("contents") == "write":
                errors.append("build: contents write permission is forbidden")
    return sorted(set(errors))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build", type=Path)
    parser.add_argument("--deploy", type=Path)
    parser.add_argument("--consumer", type=Path)
    parser.add_argument("--phase", choices=["build", "deploy"])
    parser.add_argument("--workflows", type=Path)
    args = parser.parse_args()
    if args.consumer:
        if not args.phase or not args.workflows or args.build or args.deploy:
            parser.error("--consumer requires --phase and --workflows, without --build/--deploy")
        errors = validate_consumer(yaml.safe_load(args.consumer.read_text()), args.workflows, args.phase)
    else:
        if not args.build or not args.deploy or args.phase or args.workflows:
            parser.error("Reusable validation requires --build and --deploy")
        errors = validate(yaml.safe_load(args.build.read_text()), yaml.safe_load(args.deploy.read_text()))
    for error in errors:
        print("::error::" + error)
    if not errors:
        print("Build and deploy have independent contracts and credential boundaries")
    return int(bool(errors))


if __name__ == "__main__":
    sys.exit(main())

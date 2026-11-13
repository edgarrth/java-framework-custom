#!/usr/bin/env python3
"""Require framework gates in build and a separate deployment with an external producer identity."""
import re
import sys
from pathlib import Path

import yaml

GATES = {"build", "wrapper", "samples", "api-compat", "native-reactive"}
DEPLOY = re.compile(r"/(?:lib-deploy|lib-promote|service-deploy|service-cd)\.yaml@")


def check(build_path, deploy_path):
    build = yaml.safe_load(Path(build_path).read_text())
    deploy = yaml.safe_load(Path(deploy_path).read_text())
    errors = []
    jobs = build.get("jobs", {})
    if GATES - set(jobs):
        errors.append("framework build is missing mandatory gates: " + ", ".join(sorted(GATES - set(jobs))))
    if "/lib-build.yaml@" not in jobs.get("build", {}).get("uses", ""):
        errors.append("build must use the reusable lib-build contract")
    if jobs.get("native-reactive", {}).get("uses") != "./.github/workflows/framework-native-reactive.yaml":
        errors.append("native-reactive must execute the generated service JVM/native acceptance workflow")
    for name, job in jobs.items():
        if DEPLOY.search(job.get("uses", "")):
            errors.append(f"build job {name} invokes deployment in the producer run")
        if name in GATES and ("if" in job or job.get("continue-on-error")):
            errors.append(f"mandatory gate {name} may not be conditional or ignore failures")
    calls = [job for job in deploy.get("jobs", {}).values() if "/lib-deploy.yaml@" in job.get("uses", "")]
    if len(calls) != 1:
        errors.append("deploy must call exactly one independent lib-deploy workflow")
    for job in calls:
        for name in ("build-run-id", "build-run-attempt", "manifest-sha256", "environment", "destination", "previous-manifest-sha256"):
            if job.get("with", {}).get(name) != "${{ inputs." + name + " }}":
                errors.append(f"deployment must forward the explicit {name} input")
    triggers = deploy.get("on", deploy.get(True, {}))
    inputs = triggers.get("workflow_dispatch", {}).get("inputs", {})
    for name in ("build-run-id", "build-run-attempt", "manifest-sha256"):
        if inputs.get(name, {}).get("required") is not True or "default" in inputs.get(name, {}):
            errors.append(f"deployment identity {name} must be explicit and required")
    for job in deploy.get("jobs", {}).values():
        for step in [job, *job.get("steps", [])]:
            if re.search(r"/(?:lib-build|lib-publish)\.yaml@|/setup-maven@", step.get("uses", "")):
                errors.append("deployment must not invoke a build or configure Maven")
            if re.search(r"(?:^|\s)(?:mvn|\./mvnw)(?:\s|$)", step.get("run", "")):
                errors.append("deployment must not execute Maven")
    return errors


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("Usage: check-publish-gates.py ci.yaml deploy.yaml")
    problems = check(*sys.argv[1:])
    for error in problems:
        print("::error::" + error)
    if problems:
        raise SystemExit(1)
    print("All framework gates belong to the completed producer; deployment has an independent explicit identity")

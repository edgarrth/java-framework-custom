"""Closed positive contracts for OIDC jobs, loaded from this immutable action's reviewed policy."""
import json
from pathlib import Path
import re

POLICY = Path(__file__).resolve().parents[2] / "policies/privileged-jobs-v2.json"
BUILD = {"lib-build.yaml", "service-build.yaml", "native.yaml"}
DEPLOY = {"lib-deploy.yaml", "service-deploy.yaml"}


def validate(document):
    errors = []
    if document.get("permissions") != {}:
        errors.append("workflow permissions must start empty; grant rights per job")
    policy = json.loads(POLICY.read_text())
    guard = document.get("jobs", {}).get("axiz-workflows-trust", {})
    steps = guard.get("steps", [])
    version = steps[0].get("env", {}).get("AXIZ_WORKFLOWS_VERSION", "") if steps else ""
    for name, job in document.get("jobs", {}).items():
        permissions = job.get("permissions", {})
        if permissions == "write-all" or not isinstance(permissions, dict):
            errors.append(name + ": permissions must be an explicit least-privilege map")
            continue
        if permissions.get("security-events") == "write":
            calls = [job, *job.get("steps", [])]
            if not any("github/codeql-action/upload-sarif@" in call.get("uses", "")
                       or "/.github/workflows/pr.yaml@" in call.get("uses", "") for call in calls):
                errors.append(name + ": security-events write belongs only to SARIF publishers")
        if permissions.get("id-token") != "write":
            continue
        ref = job.get("uses")
        if ref:
            match = re.fullmatch(r"edgarrth/java-framework-custom/\.github/workflows/([a-z-]+\.yaml)@axiz-workflows-v2\.\d+\.\d+", ref)
            if not match or match.group(1) not in BUILD | DEPLOY:
                errors.append(name + ": OIDC may only be delegated to reviewed build/sign or promotion workflows")
                continue
            wanted = {"contents": "write" if match.group(1) in DEPLOY else "read", "actions": "read", "id-token": "write"}
            if permissions != wanted:
                errors.append(name + ": delegated OIDC permissions differ from the called role contract")
            continue
        if "env" in document or "defaults" in document:
            errors.append(name + ": privileged jobs cannot inherit ambient environment or command defaults")
        if job.get("runs-on") != "ubuntu-24.04":
            errors.append(name + ": privileged runner must be literal ubuntu-24.04")
        if not re.fullmatch(r"axiz-workflows-v2\.\d+\.\d+", version):
            errors.append(name + ": privileged job lacks an exact immutable workflow release guard")
            continue
        normalized = json.loads(json.dumps(job).replace(version, "__IMMUTABLE_WORKFLOWS_RELEASE__"))
        roles = [value for key, value in policy.items() if (key in BUILD) == (name == "sign")]
        if name not in {"sign", "deploy"} or normalized not in roles:
            errors.append(name + ": privileged actions, scripts, arguments, paths, environment or dependencies differ from the closed role contract")
    return errors

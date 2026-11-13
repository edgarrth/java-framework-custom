#!/usr/bin/env python3
"""Signed auxiliary reference for automatic service deployment; not a third build-manifest kind."""
import argparse
import base64
import io
import json
import os
import re
import stat
import subprocess
import zipfile
from pathlib import Path, PurePosixPath
from urllib.parse import quote

import rfc8785

from build_destination import GITOPS_STATE_ROOT
from manifest import read_json, safe_file, verify
from prepare import resolve_workflow
import asyncapi_contract

SCHEMA = "axiz.service-build-proof/v1"
PROOF = "service-build-proof.json"
SIGNATURE = "service-build-proof.sigstore.json"
LIMIT = 1024 * 1024


def api(path, token=None):
    env = dict(os.environ, GH_HOST="github.com")
    if token is not None:
        env["GH_TOKEN"] = token
    return json.loads(subprocess.check_output(["gh", "api", path], text=True, env=env, timeout=45))


def positive(value):
    if type(value) is not int or value < 1:
        raise ValueError("Expected a positive GitHub execution identity")
    return value


def identity(repository, service, module):
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ValueError("Invalid source repository")
    if not re.fullmatch(r"[a-z][a-z0-9]*(?:-[a-z0-9]+)*", service):
        raise ValueError("Invalid expected service")
    path = PurePosixPath(module)
    if not module or path.is_absolute() or ".." in path.parts or str(path) != module or "\\" in module:
        raise ValueError("Expected a canonical relative source module")


def create(bundle, expected, repository):
    data = verify(bundle / "build-manifest.json", expected, bundle, repository)
    if data["kind"] != "service":
        raise ValueError("Automatic service deployment requires a service build")
    return {"schema": SCHEMA, "manifest_sha256": expected, "source": data["source"],
            "build": data["build"], "service": data["parameters"]["service"],
            "module": data["parameters"]["module"]}


def producer(repository, run_id, attempt, branch="main", workflow="ci.yaml"):
    """Re-read Actions authority; reject forks, PRs, stale attempts and stale source commits."""
    positive(run_id)
    positive(attempt)
    if not re.fullmatch(r"[A-Za-z0-9_-]+\.ya?ml", workflow) or not re.fullmatch(r"[A-Za-z0-9_-]+", branch):
        raise ValueError("Invalid automatic producer policy")
    run = api(f"repos/{repository}/actions/runs/{run_id}")
    if (run.get("id") != run_id or run.get("run_attempt") != attempt
            or run.get("repository", {}).get("full_name") != repository
            or run.get("head_repository", {}).get("full_name") != repository
            or run.get("status") != "completed" or run.get("conclusion") != "success"
            or run.get("event") not in {"push", "workflow_dispatch"}
            or run.get("path", "").split("@", 1)[0] != ".github/workflows/" + workflow):
        raise ValueError("Automatic deployment requires the current successful trusted producer attempt")
    expected_workflow = api(f"repos/{repository}/actions/workflows/{workflow}")
    if run.get("workflow_id") != expected_workflow.get("id") or not run.get("workflow_id"):
        raise ValueError("Unexpected producer workflow identity")
    source = run.get("head_sha", "")
    head_branch = run.get("head_branch", "")
    if not re.fullmatch(r"[0-9a-f]{40}", source) or not (head_branch == branch or re.fullmatch(r"(?:axiz-(?:framework|tools)-)?v[0-9]+\.[0-9]+\.[0-9]+", head_branch)):
        raise ValueError("Producer must build the approved branch or a final version tag")
    current = api(f"repos/{repository}/branches/{branch}")
    if current.get("protected") is not True or current.get("commit", {}).get("sha") != source:
        raise ValueError("Automatic producer source is no longer the approved branch head; use explicit manual promotion")
    return run


def download_proof(repository, run_id, attempt, service, destination):
    name = f"service-build-proof-{service}-{attempt}"
    matches = []
    page = 1
    while True:
        data = api(f"repos/{repository}/actions/runs/{run_id}/artifacts?per_page=100&page={page}")
        rows = data["artifacts"]
        matches.extend(item for item in rows if item.get("name") == name)
        if len(rows) < 100:
            break
        page += 1
        if page > 100:
            raise ValueError("Excessive producer artifact pagination")
    if not matches:
        return False  # A successful CI verification without a product build has no deploy candidate.
    if len(matches) != 1 or matches[0].get("expired") is not False:
        raise ValueError("Missing, expired or ambiguous service build proof")
    artifact = matches[0]
    positive(artifact["id"])
    if type(artifact.get("size_in_bytes")) is not int or not 0 < artifact["size_in_bytes"] < 3 * LIMIT:
        raise ValueError("Service proof archive exceeds its size budget")
    raw = subprocess.check_output(["gh", "api", f"repos/{repository}/actions/artifacts/{artifact['id']}/zip"], timeout=45)
    if len(raw) > 3 * LIMIT:
        raise ValueError("Oversized service proof archive")
    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        entries = archive.infolist()
        if len(entries) != 2 or {entry.filename for entry in entries} != {PROOF, SIGNATURE}:
            raise ValueError("Proof archive must contain exactly its reference and signature")
        for entry in entries:
            if entry.file_size > LIMIT or stat.S_ISLNK(entry.external_attr >> 16):
                raise ValueError("Unsafe service proof archive entry")
        destination.mkdir(parents=True, exist_ok=False)
        for entry in entries:
            (destination / entry.filename).write_bytes(archive.read(entry))
    return True


def verify_proof(directory, repository, source_sha, run_id, attempt, service, module, tag="axiz-workflows-v2.0.0"):
    identity(repository, service, module)
    positive(run_id)
    positive(attempt)
    if not re.fullmatch(r"[0-9a-f]{40}", source_sha):
        raise ValueError("Expected an immutable producer source SHA")
    workflow_sha = resolve_workflow(tag)
    proof = safe_file(directory, PROOF)
    signature = safe_file(directory, SIGNATURE)
    if proof.stat().st_size > LIMIT or signature.stat().st_size > LIMIT:
        raise ValueError("Oversized service proof")
    # Authenticate before interpreting or exposing the manifest hash. No self-supplied checksum
    # becomes approval: this is an independent signed statement of the immutable build workflow.
    subprocess.run(["cosign", "verify-blob", "--bundle", str(signature),
                    "--certificate-identity", "https://github.com/edgarrth/java-framework-custom/.github/workflows/service-build.yaml@refs/tags/" + tag,
                    "--certificate-oidc-issuer", "https://token.actions.githubusercontent.com",
                    "--certificate-github-workflow-repository", repository,
                    "--certificate-github-workflow-sha", source_sha, str(proof)], check=True, timeout=45)
    data = read_json(proof)
    expected_build = {"run_id": run_id, "run_attempt": attempt, "workflow": {
        "repository": "edgarrth/java-framework-custom", "path": ".github/workflows/service-build.yaml",
        "ref": "refs/tags/" + tag, "sha": workflow_sha}}
    if (set(data) != {"schema", "manifest_sha256", "source", "build", "service", "module"}
            or data["schema"] != SCHEMA or data["source"] != {"repository": repository, "sha": source_sha}
            or data["build"] != expected_build or data["service"] != service or data["module"] != module
            or not isinstance(data["manifest_sha256"], str) or not re.fullmatch(r"[0-9a-f]{64}", data["manifest_sha256"])
            or type(data["build"]["run_id"]) is not int or type(data["build"]["run_attempt"]) is not int
            or proof.read_bytes() != rfc8785.dumps(data)):
        raise ValueError("Signed service proof does not match the approved producer/service/module")
    return data["manifest_sha256"]


def predecessor(repository, branch, service, environment, token):
    identity(repository, service, ".")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository) or environment not in {"dev", "qa", "prod"}:
        raise ValueError("Invalid GitOps destination")
    if not re.fullmatch(r"[A-Za-z0-9_./-]+", branch) or branch.startswith(("/", ".")) or ".." in branch:
        raise ValueError("Invalid GitOps branch")
    ref = api(f"repos/{repository}/git/ref/heads/{quote(branch, safe='')}", token).get("object", {})
    if ref.get("type") != "commit" or not re.fullmatch(r"[0-9a-f]{40}", ref.get("sha", "")):
        raise ValueError("GitOps branch did not resolve to a commit")
    # Walk Git trees at an immutable commit: a missing path is distinct from an API/auth error.
    commit = api(f"repos/{repository}/git/commits/{ref['sha']}", token)
    tree = commit["tree"]["sha"]
    parts = [GITOPS_STATE_ROOT, environment, service + ".yaml"]
    for index, part in enumerate(parts):
        listing = api(f"repos/{repository}/git/trees/{tree}", token)
        if listing.get("truncated") is not False:
            raise ValueError("Truncated GitOps tree")
        matches = [entry for entry in listing["tree"] if entry["path"] == part]
        if not matches:
            return "initial-deployment"
        if len(matches) != 1:
            raise ValueError("Ambiguous GitOps predecessor")
        entry = matches[0]
        if index < len(parts) - 1:
            if entry.get("type") != "tree" or entry.get("mode") != "040000":
                raise ValueError("GitOps path traverses a symlink or non-directory")
            tree = entry["sha"]
        elif entry.get("type") != "blob" or entry.get("mode") not in {"100644", "100755"}:
            raise ValueError("GitOps predecessor must be a regular file")
    blob = api(f"repos/{repository}/git/blobs/{entry['sha']}", token)
    if blob.get("encoding") != "base64" or not 0 < blob.get("size", 0) <= LIMIT:
        raise ValueError("Invalid GitOps predecessor blob")
    content = base64.b64decode(blob["content"].replace("\n", ""), validate=True)
    if len(content) != blob["size"]:
        raise ValueError("GitOps predecessor size mismatch")
    data = asyncapi_contract.load(content.decode("utf-8"))
    if (not isinstance(data, dict) or data.get("service") != service or data.get("environment") != environment
            or not re.fullmatch(r"[0-9a-f]{64}", str(data.get("manifest_sha256")))):
        raise ValueError("GitOps predecessor does not describe this service/environment")
    return data["manifest_sha256"]


def output(name, value):
    if "\n" in str(value) or "\r" in str(value):
        raise ValueError("Unsafe Actions output")
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as stream:
        stream.write(name + "=" + str(value) + "\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    produce = sub.add_parser("create")
    produce.add_argument("--bundle", type=Path, required=True)
    produce.add_argument("--manifest-sha256", required=True)
    produce.add_argument("--repository", required=True)
    produce.add_argument("--output", type=Path, required=True)
    consume = sub.add_parser("resolve")
    for name in ("repository", "service", "module", "gitops-repository"):
        consume.add_argument("--" + name, required=True)
    consume.add_argument("--environment", default="dev", choices=["dev", "qa", "prod"])
    consume.add_argument("--gitops-branch", default="main")
    consume.add_argument("--workflows-tag", default="axiz-workflows-v2.0.0")
    consume.add_argument("--directory", type=Path, required=True)
    args = parser.parse_args()
    if args.command == "create":
        data = create(args.bundle, args.manifest_sha256, args.repository)
        args.output.mkdir(parents=True, exist_ok=False)
        (args.output / PROOF).write_bytes(rfc8785.dumps(data))
        return
    identity(args.repository, args.service, args.module)
    event = read_json(os.environ["GITHUB_EVENT_PATH"])
    run = event.get("workflow_run", {})
    if event.get("action") != "completed" or event.get("repository", {}).get("full_name") != args.repository:
        raise ValueError("Expected a completed workflow_run in this repository")
    run_id, attempt = positive(run.get("id")), positive(run.get("run_attempt"))
    authority = producer(args.repository, run_id, attempt)
    if run.get("head_sha") != authority["head_sha"]:
        raise ValueError("Workflow event source differs from Actions authority")
    if not download_proof(args.repository, run_id, attempt, args.service, args.directory):
        output("eligible", "false")
        return
    expected = verify_proof(args.directory, args.repository, authority["head_sha"], run_id, attempt,
                            args.service, args.module, args.workflows_tag)
    token = os.environ.get("GITOPS_READ_TOKEN", "")
    if not token:
        raise ValueError("Automatic deployment requires a read-only GitOps credential")
    previous = predecessor(args.gitops_repository, args.gitops_branch, args.service, args.environment, token)
    for name, value in {"eligible": "true", "manifest-sha256": expected, "build-run-id": run_id,
                        "build-run-attempt": attempt, "values-revision": authority["head_sha"],
                        "previous-manifest-sha256": previous, "environment": args.environment}.items():
        output(name, value)


if __name__ == "__main__":
    main()

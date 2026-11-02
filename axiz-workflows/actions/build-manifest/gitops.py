#!/usr/bin/env python3
"""Write a verified deployment and signed audit into GitOps with an optimistic concurrency check."""
import argparse
import base64
import hashlib
import os
import re
import subprocess
import tempfile
from pathlib import Path

import yaml

from manifest import read_json, safe_file
from service_deploy import desired


def git(root, *args, env=None):
    return subprocess.check_output(["git", "-C", str(root), *args], text=True, env=env).strip()


def regular_target(root, relative):
    """Existing parents are never followed through a symlink, including a missing target."""
    target = Path(root) / relative
    for parent in [target, *target.parents]:
        if parent == Path(root):
            break
        if parent.is_symlink():
            raise ValueError("GitOps files cannot traverse symlinks")
    return target


def current(root, relative):
    path = regular_target(root, relative)
    return yaml.safe_load(safe_file(Path(root), relative).read_text()) if path.exists() else None


def apply(root, data, manifest_hash, environment, source, previous, record, signature, run, attempt, outcome=False):
    service = data["parameters"]["service"]
    if not re.fullmatch(r"[a-z][a-z0-9]*(?:-[a-z0-9]+)*", service) or environment not in {"dev", "qa", "prod"}:
        raise ValueError("Invalid GitOps destination")
    if not re.fullmatch(r"[0-9a-f]{64}", manifest_hash) or run < 1 or attempt < 1:
        raise ValueError("Invalid deployment identity")
    record_bytes = Path(record).read_bytes()
    audit = read_json(record)
    if (audit["manifest_sha256"] != manifest_hash or audit["target"]["environment"] != environment
            or audit["run_id"] != run or audit["source"] != data["source"]
            or audit["previous_manifest_sha256"] != previous
            or (audit["result"] == "requested") == outcome):
        raise ValueError("Signed record does not describe this deployment")
    relative = f"environments/{environment}/{service}.yaml"
    present = current(root, relative)
    actual_previous = present.get("manifest_sha256") if present else "initial-deployment"
    if outcome:
        if actual_previous != manifest_hash or present.get("run") != run or present.get("attempt") != attempt:
            raise ValueError("The active deployment changed before recording the outcome")
    elif actual_previous != previous:
        raise ValueError("The deployed manifest changed since approval; approve the current predecessor before retrying")
    kind = "outcome" if outcome else "request"
    audit_path = f"deployments/{environment}/{service}/{run}-{attempt}/{kind}.json"
    for relative_path, content in [(audit_path, record_bytes), (audit_path + ".sigstore.json", Path(signature).read_bytes())]:
        target = regular_target(root, relative_path)
        if target.exists() and target.read_bytes() != content:
            raise ValueError("An existing deployment audit cannot be overwritten")
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(content)
    if outcome:
        entry = present
        entry["outcome_record_sha256"] = hashlib.sha256(record_bytes).hexdigest()
        entry["result"] = audit["result"]
    else:
        entry = desired(data, manifest_hash, environment, source, hashlib.sha256(record_bytes).hexdigest(), run)
        entry["attempt"] = attempt
    target = regular_target(root, relative)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(yaml.safe_dump(entry, sort_keys=False), encoding="utf-8")
    return relative, audit_path, audit_path + ".sigstore.json"


def publish(remote, branch, prepared, manifest_hash, environment, previous, record, signature, run, attempt, token, outcome=False):
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", remote) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._/-]*", branch) or ".." in branch:
        raise ValueError("Invalid GitOps repository or branch")
    env = dict(os.environ, GIT_TERMINAL_PROMPT="0", GIT_CONFIG_NOSYSTEM="1", GIT_CONFIG_GLOBAL=os.devnull,
               GIT_CONFIG_COUNT="2", GIT_CONFIG_KEY_0="http.https://github.com/.extraheader",
               GIT_CONFIG_VALUE_0="AUTHORIZATION: basic " + base64.b64encode(("x-access-token:" + token).encode()).decode(),
               GIT_CONFIG_KEY_1="core.hooksPath", GIT_CONFIG_VALUE_1=os.devnull)
    # Authentication is process-local: neither the clone URL nor .git/config stores the token.
    with tempfile.TemporaryDirectory(prefix="axiz-gitops-") as temporary:
        root = Path(temporary) / "repository"
        subprocess.run(["git", "clone", "--quiet", "--depth", "1", "--branch", branch,
                        "https://github.com/" + remote + ".git", str(root)], check=True, env=env)
        for _ in range(5):
            paths = apply(root, prepared["manifest"], manifest_hash, environment, prepared["values"], previous,
                          record, signature, run, attempt, outcome)
            git(root, "add", "--", *paths, env=env)
            if not git(root, "diff", "--cached", "--name-only", env=env):
                return git(root, "rev-parse", "HEAD", env=env)
            git(root, "-c", "user.name=axiz-deploy[bot]", "-c", "user.email=axiz-deploy@users.noreply.github.com",
                "commit", "--quiet", "-m", f"deploy({environment}): {prepared['manifest']['parameters']['service']}\n\n"
                f"Build-manifest-SHA256: {manifest_hash}\nDeploy-record-SHA256: {hashlib.sha256(Path(record).read_bytes()).hexdigest()}", env=env)
            pushed = subprocess.run(["git", "-C", str(root), "push", "--quiet", "origin", "HEAD:refs/heads/" + branch], env=env)
            if pushed.returncode == 0:
                return git(root, "rev-parse", "HEAD", env=env)
            git(root, "fetch", "--quiet", "origin", "refs/heads/" + branch, env=env)
            git(root, "reset", "--hard", "FETCH_HEAD", env=env)
        raise ValueError("GitOps push did not succeed after five concurrency retries")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--branch", required=True)
    parser.add_argument("--prepared", type=Path, required=True)
    parser.add_argument("--manifest-sha256", required=True)
    parser.add_argument("--environment", required=True)
    parser.add_argument("--previous-manifest", required=True)
    parser.add_argument("--record", type=Path, required=True)
    parser.add_argument("--signature", type=Path, required=True)
    parser.add_argument("--outcome", action="store_true")
    args = parser.parse_args()
    commit = publish(args.repository, args.branch, read_json(args.prepared), args.manifest_sha256, args.environment,
                     args.previous_manifest, args.record, args.signature, int(os.environ["GITHUB_RUN_ID"]),
                     int(os.environ["GITHUB_RUN_ATTEMPT"]), os.environ["GITOPS_TOKEN"], args.outcome)
    if "GITHUB_OUTPUT" in os.environ:
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as stream:
            stream.write("commit=" + commit + "\n")


if __name__ == "__main__":
    main()

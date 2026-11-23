#!/usr/bin/env python3
"""Lee el inventario y el historial de identidades inmutables exclusivamente desde el commit base protegido."""
import argparse
import json
import re
import subprocess
from datetime import datetime, timezone
from pathlib import Path

from evaluate import preserve_history, read_json, unique, validate_policy

POLICY = ".github/policies/vulnerabilities-v2.json"
MAX_HISTORY = 10000


def git(root, *arguments, missing=False):
    result = subprocess.run(["git", "-C", str(root), *arguments], check=False,
                            capture_output=True, text=True, timeout=60)
    if result.returncode:
        if missing:
            return None
        raise ValueError("Cannot read protected policy history")
    return result.stdout


def from_base(root, base):
    if not re.fullmatch(r"[0-9a-f]{40}", base):
        raise ValueError("The protected base must be an exact commit from trusted workflow context")
    if git(root, "rev-parse", base + "^{commit}").strip() != base:
        raise ValueError("Invalid protected commit")
    raw = git(root, "show", base + ":" + POLICY)
    policy = json.loads(raw, object_pairs_hook=unique)
    now = datetime.now(timezone.utc)
    validate_policy(policy, now)
    commits = git(root, "log", "--full-history", "--format=%H", "--max-count=" + str(MAX_HISTORY + 1), base, "--", POLICY).splitlines()
    if len(commits) > MAX_HISTORY:
        raise ValueError("History exceeds the bounded audit window; consolidate it with reviewed evidence")
    history = []
    seen = set()
    total = len(raw)
    for commit in commits:
        previous = git(root, "show", commit + ":" + POLICY, missing=True)
        if previous is None:
            continue  # Un commit de borrado no elimina del git log su evidencia anterior.
        total += len(previous)
        if total > 64 * 1024 * 1024:
            raise ValueError("Protected policy history exceeds the bounded input size")
        document = json.loads(previous, object_pairs_hook=unique)
        validate_policy(document, now)
        for finding in document["findings"]:
            fingerprint = json.dumps(finding, sort_keys=True)
            if fingerprint not in seen:
                history.append(finding)
                seen.add(fingerprint)
    preserve_history(policy["findings"], history)
    return policy, history


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--base", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--proposed", help="Validate a proposed update, without granting it approval")
    args = parser.parse_args()
    policy, history = from_base(Path(args.repository), args.base)
    if args.proposed:
        proposed = read_json(args.proposed)
        validate_policy(proposed, datetime.now(timezone.utc))
        preserve_history(proposed["findings"], history)
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=True)
    (output / "policy.json").write_text(json.dumps(policy, indent=2) + "\n", encoding="utf-8")
    (output / "history.json").write_text(json.dumps(history, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()

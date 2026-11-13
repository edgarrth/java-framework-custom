#!/usr/bin/env python3
"""Write a schema-validated deployment record from verified source and real environment approvals."""
import argparse
import datetime
import json
import os
from pathlib import Path

import jsonschema
import rfc8785

from manifest import SCHEMA_ROOT, read_json, verify


def approvals(history, environment, repository, run_id):
    approved = []
    for review in history:
        if review.get("state") == "approved" and any(env.get("name") == environment for env in review.get("environments", [])):
            approved.append({"actor": review["user"]["login"],
                             "reference": f"https://github.com/{repository}/actions/runs/{run_id}#environment-{environment}"})
    if not approved:
        raise ValueError("A recorded GitHub environment approval is required before deployment")
    return sorted({(item["actor"], item["reference"]) for item in approved})


def record(manifest, manifest_hash, environment, destination, run_id, previous, configuration, approved, result):
    data = {"schema": "axiz.deploy-record/v1", "manifest_sha256": manifest_hash,
            "source": manifest["source"], "target": {"environment": environment, "repository": destination},
            "run_id": run_id, "previous_manifest_sha256": previous, "configuration_refs": configuration,
            "approvals": [{"actor": actor, "reference": reference} for actor, reference in approved],
            "result": result, "timestamp": datetime.datetime.now(datetime.timezone.utc).isoformat()}
    schema = read_json(SCHEMA_ROOT / "deploy-record-v1.schema.json")
    jsonschema.Draft202012Validator(schema, format_checker=jsonschema.FormatChecker()).validate(data)
    return data


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle", type=Path, required=True)
    parser.add_argument("--manifest-sha256", required=True)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--environment", required=True)
    parser.add_argument("--destination", required=True)
    parser.add_argument("--previous-manifest", required=True)
    parser.add_argument("--approvals", type=Path, required=True)
    parser.add_argument("--configuration", type=Path, required=True)
    parser.add_argument("--result", choices=["requested", "succeeded", "failed", "rolled-back"], required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    manifest = verify(args.bundle / "build-manifest.json", args.manifest_sha256, args.bundle, args.repository)
    run = int(os.environ["GITHUB_RUN_ID"])
    reviewed = approvals(read_json(args.approvals), args.environment, os.environ["GITHUB_REPOSITORY"], run)
    data = record(manifest, args.manifest_sha256, args.environment, args.destination, run, args.previous_manifest,
                  read_json(args.configuration), reviewed, args.result)
    args.output.write_bytes(rfc8785.dumps(data))


if __name__ == "__main__":
    main()

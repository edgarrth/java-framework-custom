#!/usr/bin/env python3
"""Fails when a workflow could publish without every gate: the job that promotes to Nexus must
need every other job of the pipeline (except the pull-request checks), and no build job may
publish on its own. Usage: check-publish-gates.py <workflow.yaml>..."""
import sys

import yaml

PROMOTE = "lib-promote.yaml"
NOT_GATES = {"pr"}


def check(path):
    errors = []
    jobs = yaml.safe_load(open(path, encoding="utf-8"))["jobs"]
    promoters = [n for n, j in jobs.items() if PROMOTE in str(j.get("uses", ""))]
    if not promoters:
        errors.append(f"{path}: no job promotes with {PROMOTE}")
    for name, job in jobs.items():
        if "lib-publish.yaml" in str(job.get("uses", "")) and job.get("with", {}).get("publish"):
            errors.append(f"{path}: job {name} publishes from the build itself")
    for name in promoters:
        needs = jobs[name].get("needs", [])
        needs = {needs} if isinstance(needs, str) else set(needs)
        upstream = {n for n in jobs if n not in NOT_GATES and n != name}
        # deployment jobs that themselves depend on the promotion are not gates
        upstream = {n for n in upstream if name not in str(jobs[n].get("needs", ""))}
        # gates are the jobs the build verification consists of: everything that is not a
        # deployment of an already verified image
        gates = {n for n in upstream if not n.startswith("initializr-")}
        missing = sorted(gates - needs)
        if missing:
            errors.append(f"{path}: job {name} publishes without waiting for {missing}")
    return errors


if __name__ == "__main__":
    problems = [e for p in sys.argv[1:] for e in check(p)]
    for e in problems:
        print("::error::" + e)
    if problems:
        sys.exit(1)
    print("publication waits for every gate")

"""Autoridad de Actions para un firmante aislado; los reportes subidos nunca establecen el éxito de un job."""
import re

from prepare import api

GATES = {
    "maven-library": ["Compile, test, quality, mutation and local Maven staging",
                      "Verify mutation workers and encapsulation", "SBOM of the complete staged reactor",
                      "Scan the dependency inventory", "Record exact artifacts, evidence and parameters",
                      "Retain unsigned Maven product"],
    "service-compile": ["Source contracts and generated snapshots", "Final version, quality, tests and native verification",
                        "Package the previously verified image and chart once", "Scan source, dependency bytes and configuration",
                        "Scan every local image archive", "Retain tested service transfer", "Upload tested service transfer"],
    "service": ["Publish verified OCI bytes from the successful worker", "Scan published source and install the pinned scanner",
                "Scan every platform of the exact image digest", "Complete vulnerability evidence and protected risk deadlines",
                "Record service content, platform digests and evidence", "Retain unsigned service product"],
    "native-component": ["Compile and smoke the native executable", "Retain unsigned native component"],
}


def validate(run, jobs, source, run_id, attempt, job_name, kind):
    if (type(run_id) is not int or run_id < 1 or type(attempt) is not int or attempt < 1
            or not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", source["repository"])
            or not re.fullmatch(r"[0-9a-f]{40}", source["sha"]) or kind not in GATES):
        raise ValueError("Invalid signer authority context")
    if (run.get("id") != run_id or run.get("run_attempt") != attempt
            or run.get("head_sha") != source["sha"]
            or run.get("repository", {}).get("full_name") != source["repository"]
            or run.get("head_repository", {}).get("full_name") != source["repository"]
            or run.get("event") not in {"push", "workflow_dispatch"}
            or run.get("status") != "in_progress"):
        raise ValueError("Actions run is not this active signer invocation and source")
    selected = [job for job in jobs if job.get("name", "").split(" / ")[-1] == job_name]
    if len(selected) != 1:
        raise ValueError("Missing or ambiguous expected producer job")
    job = selected[0]
    if (job.get("run_id") != run_id or job.get("run_attempt") != attempt
            or job.get("head_sha") != source["sha"] or job.get("status") != "completed"
            or job.get("conclusion") != "success" or type(job.get("id")) is not int):
        raise ValueError("Expected producer job did not succeed for this SHA/run/attempt")
    steps = job.get("steps", [])
    approved = []
    for name in GATES[kind]:
        matches = [step for step in steps if step.get("name") == name]
        if (len(matches) != 1 or matches[0].get("status") != "completed"
                or matches[0].get("conclusion") != "success"):
            raise ValueError("Required Actions gate was missing, skipped or failed: " + name)
        approved.append({key: matches[0][key] for key in ("name", "number", "status", "conclusion")})
    return {"schema": "axiz.actions-authority/v1", "source": source, "run_id": run_id,
            "run_attempt": attempt, "producer_job": {"id": job["id"], "name": job["name"],
            "head_sha": job["head_sha"], "conclusion": job["conclusion"], "steps": approved,
            "started_at": job.get("started_at"), "completed_at": job.get("completed_at")}}


def obtain(source, run_id, attempt, job_name, kind):
    repository = source["repository"]
    run = api(f"repos/{repository}/actions/runs/{run_id}")
    jobs = []
    for page in range(1, 101):
        rows = api(f"repos/{repository}/actions/runs/{run_id}/attempts/{attempt}/jobs?per_page=100&page={page}")["jobs"]
        jobs.extend(rows)
        if len(rows) < 100:
            break
    else:
        raise ValueError("Producer job pagination exceeds its bound")
    return validate(run, jobs, source, run_id, attempt, job_name, kind)

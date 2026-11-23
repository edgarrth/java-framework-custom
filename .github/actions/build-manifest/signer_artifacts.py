"""Descarga los artefactos del productor de la ejecución actual como datos acotados, nunca como scripts o ejecutables."""
import hashlib
import re
import stat
import subprocess
import zipfile
from datetime import datetime
from pathlib import Path, PurePosixPath

from prepare import api

LIMIT = 2 * 1024 * 1024 * 1024
FILE_LIMIT = 512 * 1024 * 1024


def binding(item, receipt, name, started, completed):
    run = item.get("workflow_run", {})
    if not isinstance(run, dict) or not all(isinstance(value, str) for value in (item.get("created_at"), started, completed)):
        raise ValueError("Artifact and producer timestamps must be explicit UTC timestamps")
    if (item.get("name") != name or item.get("expired") is not False
            or type(item.get("id")) is not int or item["id"] < 1
            or type(item.get("size_in_bytes")) is not int or not 0 < item["size_in_bytes"] <= LIMIT
            or run.get("id") != receipt["run_id"] or run.get("head_sha") != receipt["source"]["sha"]
            or not re.fullmatch(r"sha256:[0-9a-f]{64}", item.get("digest", ""))):
        raise ValueError("Artifact is not the expected bounded producer artifact")
    created = datetime.fromisoformat(item["created_at"].replace("Z", "+00:00"))
    first = datetime.fromisoformat(started.replace("Z", "+00:00"))
    last = datetime.fromisoformat(completed.replace("Z", "+00:00"))
    if any(time.tzinfo is None for time in (created, first, last)) or not first <= created <= last:
        raise ValueError("Artifact was not created within the successful producer job")
    return {key: item[key] for key in ("id", "name", "digest", "size_in_bytes", "created_at")}


def extract(archive, destination):
    if destination.exists() or destination.is_symlink():
        raise ValueError("Signer data destination must be new")
    with zipfile.ZipFile(archive) as source:
        entries = source.infolist()
        if not entries or len(entries) > 100000 or sum(entry.file_size for entry in entries) > LIMIT:
            raise ValueError("Artifact exceeds the extraction budget")
        names = set()
        for entry in entries:
            name = entry.filename.removesuffix("/") if entry.is_dir() else entry.filename
            path = PurePosixPath(name)
            mode = stat.S_IFMT(entry.external_attr >> 16)
            if (not name or name in names or path.is_absolute() or ".." in path.parts
                    or "\\" in name or str(path) != name or entry.flag_bits & 1
                    or mode not in ({0, stat.S_IFDIR} if entry.is_dir() else {0, stat.S_IFREG})
                    or entry.file_size > FILE_LIMIT):
                raise ValueError("Unsafe, duplicated or oversized artifact entry")
            names.add(name)
        destination.mkdir(parents=True)
        for entry in entries:
            path = destination / entry.filename
            if entry.is_dir():
                path.mkdir(parents=True, exist_ok=True)
                continue
            path.parent.mkdir(parents=True, exist_ok=True)
            written = 0
            with source.open(entry) as incoming, path.open("xb") as outgoing:
                while block := incoming.read(1024 * 1024):
                    written += len(block)
                    if written > FILE_LIMIT:
                        raise ValueError("Artifact entry exceeds its streaming budget")
                    outgoing.write(block)
            path.chmod(0o600)  # Incluso los scripts subidos y los bytes nativos siguen siendo datos no ejecutables.


def download(receipt, artifact_name, job, destination):
    repository = receipt["source"]["repository"]
    run_id = receipt["run_id"]
    matches = []
    for page in range(1, 101):
        rows = api(f"repos/{repository}/actions/runs/{run_id}/artifacts?per_page=100&page={page}")["artifacts"]
        matches.extend(row for row in rows if row.get("name") == artifact_name)
        if len(rows) < 100:
            break
    else:
        raise ValueError("Producer artifact pagination exceeds its bound")
    if len(matches) != 1:
        raise ValueError("Missing or ambiguous expected artifact")
    item = matches[0]
    proof = binding(item, receipt, artifact_name, job["started_at"], job["completed_at"])
    archive = destination.with_suffix(".zip")
    if archive.exists() or archive.is_symlink():
        raise ValueError("Signer archive destination must be new")
    with archive.open("xb") as output:
        subprocess.run(["gh", "api", f"repos/{repository}/actions/artifacts/{item['id']}/zip"],
                       stdout=output, check=True, timeout=180)
    if archive.stat().st_size > LIMIT:
        raise ValueError("Producer archive exceeds its byte budget")
    with archive.open("rb") as stream:
        actual = hashlib.file_digest(stream, "sha256").hexdigest()
    if "sha256:" + actual != proof["digest"]:
        raise ValueError("Downloaded artifact differs from the Actions API digest")
    extract(archive, destination)
    receipt["artifact"] = proof
    return destination

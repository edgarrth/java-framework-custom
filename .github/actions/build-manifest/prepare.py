#!/usr/bin/env python3
"""Resuelve los parámetros públicos del build y la identidad inmutable real del workflow reutilizable."""
import argparse
import base64
import json
import os
import re
import subprocess
import urllib.parse
import urllib.request
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from workflow_release import TAG

from manifest import validate_maven_arguments, verify, read_json


def inputs(version, source_sha, repository, maven_args, axiz_version="", candidate="", project_directory="."):
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version):
        raise ValueError("Build version must be the final Maven release coordinate, not an RC or SNAPSHOT")
    if not re.fullmatch(r"[0-9a-f]{40}", source_sha):
        raise ValueError("Build source must be a full commit SHA")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ValueError("Invalid source repository")
    if (not isinstance(project_directory, str) or not re.fullmatch(r"[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*|\.", project_directory)
            or ".." in Path(project_directory).parts or str(Path(project_directory)) != project_directory):
        raise ValueError("Project directory must be a canonical relative checkout path")
    validate_maven_arguments(maven_args)
    data = {"schema": "axiz.build-manifest/v1", "kind": "maven-library", "version": version,
            "source": {"repository": repository, "sha": source_sha},
            "parameters": {"jdk": "25", "profiles": ["quality", "release"], "modules": ["."], "maven_args": maven_args, "project_directory": project_directory}}
    if axiz_version:
        if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", axiz_version):
            raise ValueError("Invalid Axiz dependency version")
        data["dependencies"] = {"axiz_version": axiz_version}
    if candidate:
        if not re.fullmatch(re.escape(version) + r"/[1-9][0-9]*", candidate):
            raise ValueError("Candidate must be <final-version>/<attempt>")
        data["candidate"] = candidate
    return data


def workflow_identity(claims, repository, source_sha, workflow_sha, kind="maven-library", *, tag):
    if not isinstance(tag, str) or not TAG.fullmatch(tag):
        raise ValueError("Expected an explicit immutable v2 workflow release")
    producers = {"maven-library": "lib-build", "service": "service-build", "native-component": "native"}
    if kind not in producers:
        raise ValueError("Unknown build producer")
    path = ".github/workflows/" + producers[kind] + ".yaml"
    expected = f"edgarrth/java-framework-custom/{path}@refs/tags/{tag}"
    if (claims.get("job_workflow_ref") != expected or claims.get("job_workflow_sha") != workflow_sha
            or claims.get("repository") != repository or claims.get("sha") != source_sha
            or not re.fullmatch(r"[0-9a-f]{40}", workflow_sha)):
        raise ValueError("OIDC producer claims differ from the immutable workflow release and source commit")
    return {"repository": "edgarrth/java-framework-custom", "path": path, "ref": "refs/tags/" + tag, "sha": workflow_sha}


def bind_candidate(data, candidate, candidate_hash):
    if bool(candidate) != bool(candidate_hash):
        raise ValueError("Candidate bundle and external identity must be supplied together")
    if not candidate:
        return
    path = Path(candidate) / "build-manifest.json"
    source = read_json(path)["source"]["repository"]
    dependency = verify(path, candidate_hash, Path(candidate), source)
    if dependency["kind"] != "maven-library" or dependency["version"] != data.get("dependencies", {}).get("axiz_version"):
        raise ValueError("The framework candidate version must equal the declared Axiz dependency")
    data["dependencies"]["axiz_build"] = {"repository": source, "run_id": dependency["build"]["run_id"],
                                           "run_attempt": dependency["build"]["run_attempt"], "manifest_sha256": candidate_hash}


def api(path):
    return json.loads(subprocess.check_output(["gh", "api", path], text=True))


def resolve_workflow(tag):
    if not TAG.fullmatch(tag):
        raise ValueError("Expected immutable v2 workflow release")
    release = api("repos/edgarrth/java-framework-custom/releases/tags/" + tag)
    if release.get("tag_name") != tag or release.get("immutable") is not True or release.get("draft") is not False:
        raise ValueError("Workflow release must exist, be immutable and not be a draft")
    obj = api("repos/edgarrth/java-framework-custom/git/ref/tags/" + tag)["object"]
    # Los tags anotados pueden apuntar a otros tags; se rechazan los ciclos en lugar de iterar indefinidamente.
    seen = set()
    while obj["type"] == "tag":
        if obj["sha"] in seen:
            raise ValueError("Cyclic annotated workflow tag")
        seen.add(obj["sha"])
        obj = api("repos/edgarrth/java-framework-custom/git/tags/" + obj["sha"])["object"]
    if obj["type"] != "commit":
        raise ValueError("Workflow tag does not resolve to a commit")
    return obj["sha"]


def oidc_claims():
    # Los claims se obtienen directamente de GitHub mediante TLS autenticado. Los consumidores verifican
    # de forma independiente la firma de cosign; decodificar un JWT descargado nunca es una verificación de firma.
    url = os.environ["ACTIONS_ID_TOKEN_REQUEST_URL"]
    if urllib.parse.urlsplit(url).scheme != "https":
        raise ValueError("OIDC request must use TLS")
    request = urllib.request.Request(url + ("&" if "?" in url else "?") + "audience=sigstore", headers={
        "Authorization": "Bearer " + os.environ["ACTIONS_ID_TOKEN_REQUEST_TOKEN"]})
    with urllib.request.urlopen(request, timeout=30) as response:
        token = json.load(response)["value"]
    payload = token.split(".")[1]
    return json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--arguments", type=Path, required=True)
    args = parser.parse_args()
    data = inputs(os.environ["BUILD_VERSION"], os.environ["SOURCE_SHA"], os.environ["GITHUB_REPOSITORY"],
                  json.loads(os.environ.get("MAVEN_ARGUMENTS", "[]")), os.environ.get("AXIZ_VERSION", ""),
                  os.environ.get("CANDIDATE", ""), os.environ.get("PROJECT_DIRECTORY", "."))
    from service import directory
    project = directory(Path(os.environ["GITHUB_WORKSPACE"]), data["parameters"]["project_directory"])
    os.chdir(project)
    kind = os.environ.get("BUILD_KIND", "maven-library")
    native = (os.environ.get("NATIVE_MODULE", ""), os.environ.get("NATIVE_BINARY", ""),
              json.loads(os.environ.get("NATIVE_PLATFORMS", "[]")))
    if any(native):
        from native_components import specification
        if kind not in {"maven-library", "native-component"}:
            raise ValueError("Native CLI components are only supported for Maven libraries")
        data["parameters"]["native"] = specification(*native)
    elif kind == "native-component":
        raise ValueError("Native component parameters are required")
    if kind == "service":
        from service import parameters
        if data["parameters"]["maven_args"]:
            raise ValueError("Services do not accept undeclared Maven flags")
        parameters(data, Path.cwd(), os.environ["SERVICE"], os.environ["MODULE"], os.environ["CHART"],
                   os.environ["VARIANT"], json.loads(os.environ["PLATFORMS"]), os.environ["BASE_IMAGE"])
    elif kind not in {"maven-library", "native-component"}:
        raise ValueError("Unknown build product kind")
    actual_sha = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    if actual_sha != data["source"]["sha"] or actual_sha != os.environ["GITHUB_SHA"]:
        raise ValueError("The checked-out build commit must equal the producer run commit")
    candidate = os.environ.get("CANDIDATE_BUNDLE", "")
    candidate_hash = os.environ.get("CANDIDATE_MANIFEST_SHA256", "")
    if candidate and Path("platform/axiz-dependencies/pom.xml").is_file():
        raise ValueError("A framework source build cannot consume another framework candidate")
    bind_candidate(data, candidate, candidate_hash)
    tag = os.environ["WORKFLOWS_TAG"]
    # La preparación del worker no tiene privilegios. Son metadatos sin firmar, no una atestación OIDC.
    # Un firmante separado debe establecer la identidad y los gates desde su propio contexto de confianza.
    paths = {"maven-library": "lib-build", "service": "service-build", "native-component": "native"}
    identity = {"repository": "edgarrth/java-framework-custom", "path": ".github/workflows/" + paths[kind] + ".yaml",
                "ref": "refs/tags/" + tag, "sha": resolve_workflow(tag)}
    data["build"] = {"workflow": identity, "run_id": int(os.environ["GITHUB_RUN_ID"]), "run_attempt": int(os.environ["GITHUB_RUN_ATTEMPT"])}
    args.output.write_text(json.dumps(data, indent=2) + "\n")
    args.arguments.write_bytes(b"".join(value.encode() + b"\0" for value in data["parameters"].get("maven_args", [])))


if __name__ == "__main__":
    main()

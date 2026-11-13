#!/usr/bin/env python3
"""Build and verify the immutable build identity. Signature verification belongs to the consuming workflow."""
import argparse
import hashlib
import json
import re
from pathlib import Path, PurePosixPath

import jsonschema
import rfc8785

SCHEMA_ROOT = Path(__file__).resolve().parents[2] / "schemas"
WORKER_GATES = frozenset({"tests", "quality", "mutation", "encapsulation", "sbom", "scan"})
REQUIRED_GATES = WORKER_GATES | {"producer-authority"}


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def canonical_hash(value):
    return sha256(rfc8785.dumps(value))


def read_json(path):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError(f"Duplicate JSON key: {key}")
            result[key] = value
        return result
    return json.loads(Path(path).read_text(encoding="utf-8"), object_pairs_hook=pairs,
                      parse_constant=lambda value: (_ for _ in ()).throw(ValueError(f"Invalid JSON number: {value}")))


def safe_file(root, relative):
    name = PurePosixPath(relative)
    if name.is_absolute() or ".." in name.parts or "\\" in relative or str(name) != relative:
        raise ValueError(f"Unsafe staged path: {relative}")
    file = root / relative
    if any((root / Path(*name.parts[:i])).is_symlink() for i in range(1, len(name.parts) + 1)):
        raise ValueError(f"Symlink in staged path: {relative}")
    if not file.is_file() or not file.resolve().is_relative_to(root.resolve()):
        raise ValueError(f"Missing staged file: {relative}")
    return file


def verify_maven_files(artifacts, root):
    root = Path(root)
    paths = {artifact["path"] for artifact in artifacts}
    actual = {p.relative_to(root).as_posix() for p in root.rglob("*") if p.is_file() or p.is_symlink()}
    if actual != paths | {"SHA256SUMS"}:
        raise ValueError(f"Unmanifested or missing Maven files: {sorted(actual ^ (paths | {'SHA256SUMS'}))}")
    lines = []
    for artifact in sorted(artifacts, key=lambda item: item["path"]):
        path = artifact["path"]
        if sha256(safe_file(root, path).read_bytes()) != artifact["sha256"]:
            raise ValueError(f"Artifact content hash mismatch: {path}")
        lines.append(artifact["sha256"] + "  " + path + "\n")
    if safe_file(root, "SHA256SUMS").read_text(encoding="utf-8") != "".join(lines):
        raise ValueError("SHA256SUMS and Maven artifact inventory differ")


def validate_maven_arguments(arguments):
    # Extra flags cannot silently narrow tests, alter publication, inject credentials or bypass gates.
    allowed = re.compile(r"^-D(?:user\.timezone=[A-Za-z0-9_+:/.-]+|file\.encoding=UTF-8|"
                         r"project\.build\.outputTimestamp=[0-9]+|axiz\.it\.requireDocker=true|"
                         r"axiz\.api\.baseline=[0-9]+\.[0-9]+\.[0-9]+)$")
    if not isinstance(arguments, list) or any(not isinstance(arg, str) or not allowed.fullmatch(arg) for arg in arguments):
        raise ValueError("Maven arguments must use the public build parameter allowlist; gates cannot be overridden")


def validate(manifest, bundle=None, *, require_authority=True):
    schema = read_json(SCHEMA_ROOT / "build-manifest-v1.schema.json")
    jsonschema.Draft202012Validator(schema).validate(manifest)
    if canonical_hash(manifest["parameters"]) != manifest["parameters_sha256"]:
        raise ValueError("Effective parameter hash does not match RFC 8785 canonical parameters")
    kind = manifest["kind"]
    workflow = manifest["build"]["workflow"]["path"]
    expected = ".github/workflows/lib-build.yaml" if kind == "maven-library" else ".github/workflows/service-build.yaml"
    if workflow != expected:
        raise ValueError("Build kind does not match its trusted producer workflow")
    gates = manifest["gates"]
    names = [gate["name"] for gate in gates]
    required = (REQUIRED_GATES if require_authority else WORKER_GATES) | ({"contracts", "chart"} if kind == "service" else set())
    if len(set(names)) != len(names) or not required.issubset(names):
        raise ValueError(f"Required gates are missing or duplicated: {sorted(required - set(names))}")
    if kind == "maven-library":
        args = manifest["parameters"]["maven_args"]
        validate_maven_arguments(args)
        if "quality" not in manifest["parameters"]["profiles"]:
            raise ValueError("The quality profile is required")
        paths = set()
        for artifact in manifest["artifacts"]["maven"]:
            if artifact["version"] != manifest["version"]:
                raise ValueError("Artifact version differs from the final manifest version")
            classifier = "-" + artifact["classifier"] if "classifier" in artifact else ""
            coordinate_path = (artifact["groupId"].replace(".", "/") + "/" + artifact["artifactId"] + "/" + artifact["version"]
                               + "/" + artifact["artifactId"] + "-" + artifact["version"] + classifier + "." + artifact["extension"])
            if artifact["path"] != coordinate_path or artifact["path"] in paths:
                raise ValueError("Staged path does not match unique Maven coordinates")
            paths.add(artifact["path"])
        if bundle is not None:
            verify_maven_files(manifest["artifacts"]["maven"], Path(bundle) / "maven")
        from native_components import validate_manifest
        validate_manifest(manifest, bundle)
    if kind == "service":
        image = manifest["artifacts"]["image"]
        platforms = manifest["parameters"]["platforms"]
        platform_digests = image.get("platforms")
        if len(platforms) > 1 and platform_digests is None:
            raise ValueError("Multi-platform images require an index and every platform digest")
        if platform_digests is not None and set(platform_digests) != set(platforms):
            raise ValueError("Image platform digests must match effective platform parameters exactly")
    if bundle is not None:
        allowed = {"reports", "build-manifest.json", "build-manifest.sigstore.json"}
        if kind == "maven-library":
            allowed.add("maven")
        for path in Path(bundle).iterdir():
            if path.name not in allowed:
                raise ValueError(f"Unexpected build bundle entry: {path.name}")
        if any(path.is_symlink() for path in Path(bundle).rglob("*")):
            raise ValueError("Build bundles may not contain symlinks")
        for evidence in [manifest["sbom"], manifest["provenance"], *(gate["evidence"] for gate in gates)]:
            if sha256(safe_file(Path(bundle), evidence["path"]).read_bytes()) != evidence["sha256"]:
                raise ValueError(f"Evidence content hash mismatch: {evidence['path']}")
    return manifest


def verify(path, expected_sha256, bundle, repository, source_sha=None):
    if not re.fullmatch(r"[0-9a-f]{64}", expected_sha256):
        raise ValueError("The expected manifest SHA-256 must come from the trusted build output or approval")
    path = Path(path)
    if sha256(path.read_bytes()) != expected_sha256:
        raise ValueError("Manifest differs from the externally supplied SHA-256")
    manifest = validate(read_json(path), bundle)
    if manifest["source"]["repository"] != repository:
        raise ValueError("Unexpected source repository")
    if source_sha is not None and manifest["source"]["sha"] != source_sha:
        raise ValueError("Producer run commit differs from the signed manifest source")
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["seal", "verify"])
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--bundle", type=Path, required=True)
    parser.add_argument("--expected-sha256")
    parser.add_argument("--repository", required=True)
    parser.add_argument("--source-sha")
    args = parser.parse_args()
    if args.command == "verify":
        if args.expected_sha256 is None:
            parser.error("verify requires --expected-sha256 from outside the downloaded archive")
        verify(args.manifest, args.expected_sha256, args.bundle, args.repository, args.source_sha)
    else:
        manifest = read_json(args.manifest)
        manifest["parameters_sha256"] = canonical_hash(manifest["parameters"])
        validate(manifest, args.bundle)
        args.manifest.write_bytes(rfc8785.dumps(manifest))
    print(sha256(args.manifest.read_bytes()))


if __name__ == "__main__":
    main()

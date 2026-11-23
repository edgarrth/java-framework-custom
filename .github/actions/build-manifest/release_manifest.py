#!/usr/bin/env python3
"""Conserva los bytes originales del manifiesto firmado en la release final, incluidos los reintentos seguros."""
import argparse
import hashlib
import json
import os
import subprocess
import tempfile
from pathlib import Path

from manifest import read_json
from native_components import release_assets


def api(path):
    return json.loads(subprocess.check_output(["gh", "api", path], text=True))


def asset(repository, identifier):
    return subprocess.check_output(["gh", "api", f"repos/{repository}/releases/assets/{identifier}",
                                    "-H", "Accept: application/octet-stream"])


def retain(repository, tag, manifest_hash, bundle, publish=False):
    if hashlib.sha256((Path(bundle) / "build-manifest.json").read_bytes()).hexdigest() != manifest_hash:
        raise ValueError("Retained manifest differs from the externally approved identity")
    data = read_json(Path(bundle) / "build-manifest.json")
    if data["kind"] != "maven-library" or tag != "v" + data["version"] or data["source"]["repository"] != repository:
        raise ValueError("Release tag or repository differs from the verified Maven build")
    release = api(f"repos/{repository}/releases/tags/{tag}")
    if release["tag_name"] != tag:
        raise ValueError("Unexpected release tag")
    expected = {f"build-manifest-{manifest_hash}.json": (Path(bundle) / "build-manifest.json").read_bytes(),
                f"build-manifest-{manifest_hash}.sigstore.json": (Path(bundle) / "build-manifest.sigstore.json").read_bytes(),
                **release_assets(data, bundle)}
    if len({item["name"] for item in release["assets"]}) != len(release["assets"]):
        raise ValueError("Release contains duplicated asset names")
    existing = {item["name"]: item["id"] for item in release["assets"]}
    if any(name.startswith("build-manifest-") and name not in expected for name in existing):
        raise ValueError("Release already retains a different approved build manifest")
    missing = []
    for name, content in expected.items():
        if name in existing:
            if asset(repository, existing[name]) != content:
                raise ValueError("Release asset differs from the verified build; it cannot be overwritten")
        elif release["draft"]:
            missing.append((name, content))
        else:
            raise ValueError("Published release lacks the verified signed manifest; prepare it as a draft before promotion")
    if publish and release["draft"]:
                # Un intento previo completado parcialmente se compara y se reutiliza, nunca se sobrescribe.
        with tempfile.TemporaryDirectory(prefix="axiz-release-manifest-") as temporary:
            files = []
            for name, content in missing:
                target = Path(temporary) / name
                target.write_bytes(content)
                files.append(str(target))
            if files:
                subprocess.run(["gh", "release", "upload", tag, *files, "--repo", repository], check=True)
        subprocess.run(["gh", "release", "edit", tag, "--draft=false", "--repo", repository], check=True)
    return release["draft"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--manifest-sha256", required=True)
    parser.add_argument("--bundle", type=Path, required=True)
    parser.add_argument("--publish", action="store_true")
    args = parser.parse_args()
    draft = retain(args.repository, args.tag, args.manifest_sha256, args.bundle, args.publish)
    if "GITHUB_OUTPUT" in os.environ:
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as stream:
            stream.write("draft=" + str(draft).lower() + "\n")


if __name__ == "__main__":
    main()

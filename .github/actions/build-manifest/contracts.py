#!/usr/bin/env python3
"""Valida los contratos fuente con la versión exacta de los estándares de Axiz y rechaza snapshots generados obsoletos."""
import argparse
import os
import re
import subprocess
import zipfile
from pathlib import Path, PurePosixPath

import asyncapi_contract

from manifest import read_json, safe_file
from service import directory


def contracts(root, module):
    directory(root, module)
    found = {}
    for kind in ("openapi", "asyncapi"):
        candidates = [PurePosixPath(module) / "src/main" / kind / (kind + ".yaml"),
                      PurePosixPath(module) / "docs" / (kind + ".yaml")]
        existing = [path.as_posix() for path in candidates if (root / path).exists() or (root / path).is_symlink()]
        if len(existing) > 1:
            raise ValueError(f"Ambiguous {kind} source: choose one canonical contract location")
        if not existing:
            continue
        path = existing[0]
        source = asyncapi_contract.load(safe_file(root, path).read_text())
        if not isinstance(source, dict) or kind not in source:
            raise ValueError(f"Invalid {kind} document")
        if kind == "asyncapi":
            asyncapi_contract.events(source)
        snapshot = (PurePosixPath(module) / ".axiz" / (kind + ".snapshot.yaml")).as_posix()
        if (root / snapshot).exists() or (root / snapshot).is_symlink():
            previous = asyncapi_contract.load(safe_file(root, snapshot).read_text())
            if not asyncapi_contract.equal(previous, source):
                raise ValueError(f"{kind} contract and generated snapshot differ; synchronize and review the generated changes")
        elif kind == "asyncapi":
            raise ValueError("Missing AsyncAPI generation snapshot; run axiz contract sync --type asyncapi")
        found[kind] = path
    return found


def git_text(root, revision, path):
        # Una ruta ausente es legítima; el historial ausente, los objetos corruptos o las revisiones inválidas no lo son.
    subprocess.run(["git", "-C", str(root), "cat-file", "-e", revision + "^{commit}"], check=True,
                   stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    entry = subprocess.check_output(["git", "-C", str(root), "ls-tree", revision, "--", path], text=True)
    if not entry:
        return None
    mode, kind, _ = entry.split(None, 3)[:3]
    if mode not in {"100644", "100755"} or kind != "blob":
        raise ValueError("Previous contract must be a regular Git blob")
    return subprocess.check_output(["git", "-C", str(root), "show", revision + ":" + path], text=True)


def compatibility(root, module, found, baseline):
    if not re.fullmatch(r"[0-9a-f]{40}", baseline):
        raise ValueError("AsyncAPI baseline must be an immutable full Git commit")
    candidates = [PurePosixPath(module) / "src/main/asyncapi/asyncapi.yaml",
                  PurePosixPath(module) / "docs/asyncapi.yaml"]
    previous = [text for path in candidates if (text := git_text(root, baseline, path.as_posix())) is not None]
    if len(previous) > 1:
        raise ValueError("Ambiguous previous AsyncAPI contract")
    if not previous:
        print("AsyncAPI: first contract publication at this service path")
        return
    before = asyncapi_contract.load(previous[0])
    if "asyncapi" not in found:
        if asyncapi_contract.events(before)["send"]:
            raise ValueError("Published AsyncAPI contract removed; preserve the previous types during migration")
        return
    after = asyncapi_contract.load(safe_file(root, found["asyncapi"]).read_text())
    failures = asyncapi_contract.compare(before, after)
    if failures:
        raise ValueError("Incompatible published AsyncAPI types; introduce .vN+1 in parallel: " + ", ".join(failures))
    print("AsyncAPI published types compatible with", baseline)


def baseline(root, explicit):
    if explicit:
        return explicit
        # Un build por tag o manual compara igualmente la versión publicada previa. En repositorios sin
    # ninguna release se usa el padre del código fuente; un commit raíz es la única excepción sin historial.
    tags = subprocess.check_output(["git", "-C", str(root), "tag", "--merged", "HEAD", "--sort=-version:refname"], text=True)
    head = subprocess.check_output(["git", "-C", str(root), "rev-parse", "HEAD"], text=True).strip()
    for tag in tags.splitlines():
        if re.fullmatch(r"v?[0-9]+\.[0-9]+\.[0-9]+", tag):
            commit = subprocess.check_output(["git", "-C", str(root), "rev-parse", tag + "^{commit}"], text=True).strip()
            if commit != head:
                return commit
    parents = subprocess.check_output(["git", "-C", str(root), "rev-list", "--parents", "-n", "1", "HEAD"], text=True).split()
    return parents[1] if len(parents) > 1 else None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--metadata", type=Path, required=True)
    parser.add_argument("--root", type=Path, default=Path("."))
    parser.add_argument("--rules", type=Path, required=True)
    parser.add_argument("--base-sha", default="", help="Immutable PR target or previous push commit")
    args = parser.parse_args()
    data = read_json(args.metadata)
    found = contracts(args.root, data["parameters"]["module"])
    previous = baseline(args.root, args.base_sha)
    if previous:
        compatibility(args.root, data["parameters"]["module"], found, previous)
    else:
        print("Initial Git commit: no previous AsyncAPI contract")
    if found:
        args.rules.mkdir(parents=True, exist_ok=False)
        subprocess.run(["mvn", "-B", "-ntp", "-f", str(args.root / data["parameters"]["module"] / "pom.xml"),
                        "org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy",
                        "-Dartifact=pe.axiz:axiz-code-standards:" + data["dependencies"]["axiz_version"] + ":jar",
                        "-DoutputDirectory=" + str(args.rules.resolve())], check=True)
        jar = args.rules / ("axiz-code-standards-" + data["dependencies"]["axiz_version"] + ".jar")
        with zipfile.ZipFile(jar) as archive:
            for name in archive.namelist():
                if name.startswith("axiz/spectral/") and name.endswith(".yaml"):
                    relative = PurePosixPath(name)
                    if ".." in relative.parts or relative.is_absolute():
                        raise ValueError("Unsafe ruleset archive entry")
                    target = args.rules / relative
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(archive.read(name))
        for kind, path in found.items():
            rules = args.rules / "axiz/spectral" / ("axiz-" + kind + ".yaml")
            if not rules.is_file():
                raise ValueError(f"Exact Axiz standards artifact lacks {kind} rules")
            subprocess.run(["npx", "--yes", "@stoplight/spectral-cli@6.15.0", "lint", "-r", str(rules), path,
                            "--fail-severity", "error"], cwd=args.root, check=True)
            print(kind, path, "snapshot consistent; exact ruleset", data["dependencies"]["axiz_version"])
    else:
        print("No OpenAPI or AsyncAPI contract is declared in the service's conventional source locations")
    if "GITHUB_OUTPUT" in os.environ:
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as stream:
            for kind, path in found.items():
                stream.write(kind + "=" + path + "\n")


if __name__ == "__main__":
    main()

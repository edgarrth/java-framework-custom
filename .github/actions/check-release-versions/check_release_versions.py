#!/usr/bin/env python3
"""Verifica el estado central de Release Please y las versiones independientes de los componentes del monorepo."""
import json
from pathlib import Path
import re
import sys

from defusedxml import ElementTree as ET


VERSION = re.compile(r"([0-9]+)\.([0-9]+)\.([0-9]+)(?:-SNAPSHOT)?")


def version(value):
    match = VERSION.fullmatch(value)
    if match is None:
        raise ValueError("Invalid component version")
    return tuple(int(part) for part in match.groups())


def check(root):
    root = Path(root).resolve()
    config = json.loads((root / "release-please-config.json").read_text())
    manifest = json.loads((root / ".release-please-manifest.json").read_text())
    packages = config["packages"]
    if set(manifest) - set(packages):
        raise ValueError("Release state contains a component outside the central configuration")
    monorepo = ".github" in packages
    if monorepo and not re.fullmatch(r"[a-f0-9]{40}", config.get("bootstrap-sha", "")):
        raise ValueError("Initial history requires a fixed full bootstrap SHA")
    checked = []
    for directory, component in packages.items():
        relative = Path(directory)
        if relative.is_absolute() or ".." in relative.parts or (directory == "." and monorepo):
            raise ValueError("Each release must own a distinct canonical component directory")
        location = root / relative
        if not location.is_dir() or not location.resolve().is_relative_to(root):
            raise ValueError("Component is outside the monorepo")
        current = (location / "version.txt").read_text().strip()
        expected = manifest.get(directory, component.get("initial-version"))
        if current != expected:
            raise ValueError(f"{directory}: version.txt differs from released/initial version {expected}")
        version(current)
        pom = location / "pom.xml"
        if pom.exists():
            project = ET.parse(pom).getroot()
            coordinate = project.findtext("{*}properties/{*}revision") or project.findtext("{*}version")
            if coordinate is None or version(coordinate) < version(current):
                raise ValueError(f"{directory}: source Maven version is behind the release state")
        checked.append({"path": directory, "component": component.get("component", location.name), "version": current})
    return checked


if __name__ == "__main__":
    print(json.dumps({"components": check(sys.argv[1] if len(sys.argv) > 1 else ".")}, indent=2))

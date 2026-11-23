#!/usr/bin/env python3
"""Selecciona el tag de release a partir del proyecto fuente verificado, nunca del nombre de archivo de un artefacto."""
import argparse
import re
from pathlib import Path

from manifest import read_json

MONOREPO = "edgarrth/java-framework-custom"
COMPONENTS = {"axiz-framework": "axiz-framework", "axiz-tools": "axiz-tools"}


def release_tag(manifest):
    version = manifest["version"]
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version):
        raise ValueError("Final release version is required")
    if manifest["source"]["repository"] == MONOREPO:
        component = COMPONENTS.get(manifest["parameters"].get("project_directory"))
        if component is None:
            raise ValueError("A monorepo library release requires an explicit publishable component")
        return component + "-v" + version
    return "v" + version


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    print(release_tag(read_json(parser.parse_args().manifest)))

#!/usr/bin/env python3
"""Verify JPMS identities in final reactor JARs, never in source properties alone."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import sys
import zipfile

from defusedxml import ElementTree as ET
from maven_reactor import modules, text


def main_attributes(jar):
    with zipfile.ZipFile(jar) as archive:
        entries = [entry for entry in archive.infolist() if entry.filename == "META-INF/MANIFEST.MF"]
        if len(entries) != 1 or entries[0].file_size > 65536:
            raise ValueError("Missing, duplicated or oversized JAR manifest: " + str(jar))
        lines = archive.read(entries[0]).decode("utf-8").splitlines()
    attributes = {}
    key = None
    for line in lines:
        if not line:
            break  # Named-entry sections cannot override main JAR attributes.
        if line.startswith(" "):
            if key is None:
                raise ValueError("Invalid manifest continuation")
            attributes[key] += line[1:]
            continue
        key, separator, value = line.partition(": ")
        key = key.lower()
        if not separator or key in attributes:
            raise ValueError("Malformed or duplicated manifest attribute")
        attributes[key] = value
    return attributes


def verify(roots):
    identities = {}
    coordinates = set()
    records = []
    for root in roots:
        root = root.resolve()
        project = ET.parse(root / "pom.xml").getroot()
        version = text(project, "m:properties/m:revision") or text(project, "m:version")
        for artifact, (directory, packaging, pom) in sorted(modules(root).items()):
            if packaging != "jar":
                continue
            if artifact in coordinates:
                raise ValueError("Repeated reactor JAR coordinate: " + artifact)
            coordinates.add(artifact)
            declared_version = text(pom, "m:version") or version
            if declared_version == "${revision}":
                declared_version = version
            if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*", declared_version):
                raise ValueError("Unresolved JAR version: " + artifact)
            jar = directory / "target" / f"{artifact}-{declared_version}.jar"
            actual = main_attributes(jar).get("automatic-module-name")
            application = text(pom, "m:parent/m:artifactId") == "axiz-parent-service"
            if application:
                if actual is not None:
                    raise ValueError("Service JAR must not declare Automatic-Module-Name: " + artifact)
            else:
                expected = text(pom, "m:properties/m:axiz.module.name")
                if not re.fullmatch(r"pe\.axiz(?:\.[a-z][a-z0-9]*)+", expected):
                    raise ValueError("Library must declare its own valid JPMS identity: " + artifact)
                if actual != expected:
                    raise ValueError("Final JAR module identity differs from its declaration: " + artifact)
                if actual in identities:
                    raise ValueError("Duplicate JPMS identity: " + actual + " in " + identities[actual] + " and " + artifact)
                identities[actual] = artifact
            with jar.open("rb") as stream:
                checksum = hashlib.file_digest(stream, "sha256").hexdigest()
            records.append({"artifact": artifact, "version": declared_version, "jar": str(jar),
                            "module": actual, "application": application, "sha256": checksum})
    if not records:
        raise ValueError("No final reactor JARs were checked")
    return {"schema": 1, "passed": True, "jars": records}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, action="append", required=True,
                        help="Reactor or service root; repeat to check framework, tools and services together")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = verify(args.root)
        print(f"Final JAR module identities: {len(result['jars'])} checked")
    except (OSError, ValueError, ET.ParseError, zipfile.BadZipFile) as failure:
        result = {"schema": 1, "passed": False, "error": str(failure)}
        print("::error::" + str(failure), file=sys.stderr)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n")
    return int(not result["passed"])


if __name__ == "__main__":
    sys.exit(main())

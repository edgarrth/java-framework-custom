#!/usr/bin/env python3
"""Select actual generated service JARs and run the packaged IRSA acceptance in CI."""
import json
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

root = Path(__file__).resolve().parents[2]
jars = []
for manifest in ("target/samples/modules.json", "target/audit-samples/modules.json"):
    for relative in json.loads((root / manifest).read_text()):
        project = (root / relative).resolve()
        project.relative_to(root)
        pom = ET.parse(project / "pom.xml").getroot()
        if pom.findtext("{*}parent/{*}artifactId") != "axiz-parent-service":
            continue  # Library and starter reactors are not executable service consumers.
        candidates = []
        for jar in (project / "target").glob("*.jar"):
            with zipfile.ZipFile(jar) as archive:
                if any(name.startswith("BOOT-INF/lib/") for name in archive.namelist()):
                    candidates.append(jar)
        if len(candidates) != 1:
            raise ValueError(f"Exactly one packaged service JAR is required: {project}")
        jars.extend(candidates)
if not jars:
    raise ValueError("No generated service consumers were packaged")
raise SystemExit(subprocess.run([sys.executable, str(Path(__file__).with_name("check-packaged-irsa.py")),
                                *map(str, jars), "--output", str(root / "target/generated-packaged-irsa.json")],
                               cwd=root).returncode)

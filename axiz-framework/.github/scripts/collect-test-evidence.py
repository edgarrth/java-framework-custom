#!/usr/bin/env python3
"""Recolecta el XML de TEST de los módulos declarados del reactor de Maven, excluyendo fixtures copiados o anidados."""
import argparse
import hashlib
import json
from defusedxml import ElementTree as ET
from pathlib import Path


def reactor(project):
    seen = set()

    def visit(directory):
        directory = directory.resolve()
        if directory in seen:
            raise ValueError(f"Duplicate reactor module: {directory}")
        seen.add(directory)
        pom = ET.parse(directory / "pom.xml").getroot()
        yield directory
        for module in pom.findall("{*}modules/{*}module"):
            yield from visit(directory / module.text.strip())

    return list(visit(project))


def reports(project, relative_to=None, allow_empty=False):
    project = Path(project).resolve()
    root = Path(relative_to).resolve() if relative_to else project
    counts = dict.fromkeys(("tests", "failures", "errors", "skipped"), 0)
    hashes = {}
    harness = 0
    for module in reactor(project):
        for tool in ("surefire-reports", "failsafe-reports"):
            for report in sorted((module / "target" / tool).glob("TEST-*.xml")):
                suite = ET.parse(report).getroot()
                # Maven escribe testsuite; se acepta un envoltorio testsuites sin contarlo dos veces.
                suites = [suite] if suite.tag == "testsuite" else list(suite.iter("testsuite"))
                for entry in suites:
                    for key in counts:
                        counts[key] += int(entry.get(key, "0"))
                    # Surefire incluye los tipos de los parámetros inyectados en los nombres de los métodos de JUnit.
                    harness += sum(test.get("name", "").partition("(")[0] == "verifiesFrameworkOwnedAuditTransport"
                                   for test in entry.findall("testcase"))
                hashes[str(report.relative_to(root))] = hashlib.sha256(report.read_bytes()).hexdigest()
    if not hashes and not allow_empty:
        raise ValueError(f"No test reports in reactor: {project}")
    return {"counts": counts, "frameworkHarnessTests": harness,
            "consumerTests": counts["tests"] - harness, "reportSha256": hashes}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("project", type=Path)
    parser.add_argument("--relative-to", type=Path)
    parser.add_argument("--allow-empty", action="store_true")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    result = reports(args.project, args.relative_to, args.allow_empty)
    content = json.dumps(result, indent=2) + "\n"
    if args.output:
        args.output.write_text(content)
    else:
        print(content, end="")
    return int(any(result["counts"][key] for key in ("failures", "errors", "skipped")))


if __name__ == "__main__":
    raise SystemExit(main())

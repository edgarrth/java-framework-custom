#!/usr/bin/env python3
"""Compare every published JAR's japicmp report with the reviewed 2.0 API inventory."""
import argparse
import hashlib
import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

NS = {"m": "http://maven.apache.org/POM/4.0.0"}
INTERNAL_TESTS = {"axiz-architecture-tests", "axiz-store-integration-tests"}
VOLATILE = {"oldLineNumber", "newLineNumber"}


def text(node, path, default=""):
    return node.findtext(path, default=default, namespaces=NS).strip()


def shape(element):
    """Line numbers and XML formatting are not part of an API; parameter order is."""
    attrs = sorted((key, value) for key, value in element.attrib.items() if key not in VOLATILE)
    children = [shape(child) for child in element]
    if element.tag not in {"parameters", "genericTemplates", "genericTypes", "oldGenericTypes", "newGenericTypes"}:
        children.sort(key=lambda child: json.dumps(child, sort_keys=True))
    return [element.tag, attrs, children]


def fingerprint(element):
    return hashlib.sha256(json.dumps(shape(element), separators=(",", ":")).encode()).hexdigest()


def load_inventory(path):
    blocks = re.findall(r"```json\s*\n(.*?)\n```", path.read_text(encoding="utf-8"), re.S)
    if len(blocks) != 1:
        raise ValueError("API inventory must contain exactly one JSON block")
    data = json.loads(blocks[0])
    if set(data) != {"schema", "baseline", "version", "changes"} or data["schema"] != 1:
        raise ValueError("Invalid API inventory schema")
    return data


def collect(root, baseline, report_name="api-compat"):
    errors, changes, classes, published = [], [], {}, {}
    renames = json.loads((root / "docs/migration/2.0/renames.json").read_text())
    predecessors = {item["newArtifact"]: item["oldArtifact"] for item in renames["modules"]}
    new_modules = {line.strip() for line in (root / "docs/migration/2.0/new-modules.txt").read_text().splitlines()
                   if line.strip() and not line.startswith("#")}
    seen = set()
    for pom in sorted(root.rglob("pom.xml")):
        if any(part in {"target", "src", ".git"} for part in pom.relative_to(root).parts):
            continue
        project = ET.parse(pom).getroot()
        artifact = text(project, "m:artifactId")
        seen.add(artifact)
        if text(project, "m:packaging") == "pom":
            continue
        skip = text(project, "m:properties/m:axiz.api.skip") == "true"
        if artifact in INTERNAL_TESTS:
            if not skip or text(project, "m:properties/m:maven.deploy.skip") != "true" or any(
                    path.name != "package-info.java" for path in (pom.parent / "src/main/java").rglob("*.java")):
                errors.append(f"{artifact}: only unpublished test-only modules may skip API comparison")
            continue
        if skip:
            errors.append(f"{artifact}: a published API comparison may not be skipped")
        previous = text(project, "m:properties/m:axiz.api.baseline.artifactId", artifact)
        if previous != predecessors.get(artifact, artifact):
            errors.append(f"{artifact}: predecessor differs from the approved coordinate mapping")
        jar = pom.parent / "target" / f"{artifact}-2.0.0.jar"
        report = pom.parent / "target/japicmp" / f"{report_name}.xml"
        if artifact in new_modules:
            if artifact in predecessors:
                errors.append(f"{artifact}: a renamed module has a predecessor and is not new")
            continue
        published[artifact] = report
        if not jar.is_file() or not report.is_file():
            errors.append(f"{artifact}: missing current JAR or mandatory japicmp report")
            continue
        if report.stat().st_mtime_ns < jar.stat().st_mtime_ns:
            errors.append(f"{artifact}: API report predates the built JAR")
        result = ET.parse(report).getroot()
        expected = {"oldVersion": baseline, "newVersion": "2.0.0", "oldJar": f"{previous}-{baseline}.jar",
                    "newJar": jar.name, "ignoreMissingClasses": "false", "accessModifier": "PROTECTED"}
        for name, value in expected.items():
            if result.get(name) != value:
                errors.append(f"{artifact}: report {name} is not {value}")
        for cls in result.findall("classes/class"):
            name = cls.attrib["fullyQualifiedName"]
            classes[(artifact, name)] = cls
            if cls.get("changeStatus") != "NEW" and (cls.get("binaryCompatible") == "false" or cls.get("sourceCompatible") == "false"):
                changes.append({"artifact": artifact, "class": name, "sha256": fingerprint(cls)})
    if not new_modules <= seen:
        errors.append(f"New-module inventory contains absent modules: {sorted(new_modules - seen)}")
    return errors, changes, classes, published


def check(root, baseline, report_name="api-compat"):
    inventory = load_inventory(root / "docs/migration/2.0/api-changes.md")
    errors, observed, classes, _ = collect(root, baseline, report_name)
    if inventory["baseline"] != baseline or inventory["version"] != "2.0.0":
        errors.append("API inventory does not authorize this baseline/target pair")
    allowed = {}
    for change in inventory["changes"]:
        if not set(change) <= {"artifact", "class", "sha256", "reason", "replacement"} or not change.get("reason"):
            errors.append("API inventory entry lacks a documented reason or has unknown fields")
            continue
        key = (change["artifact"], change["class"])
        if key in allowed:
            errors.append(f"Duplicate API inventory entry: {key}")
        allowed[key] = change
        replacement = change.get("replacement")
        if replacement:
            target = classes.get((replacement["artifact"], replacement["class"]))
            if target is None or fingerprint(target) != replacement["sha256"]:
                errors.append(f"{key}: relocated public API differs from its reviewed replacement")
    observed_keys = set()
    for change in observed:
        key = (change["artifact"], change["class"])
        observed_keys.add(key)
        if key not in allowed or allowed[key]["sha256"] != change["sha256"]:
            errors.append(f"Unreviewed API break: {key[0]}:{key[1]} ({change['sha256']})")
    for key in allowed.keys() - observed_keys:
        errors.append(f"Stale API inventory entry, re-review required: {key}")
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path("."))
    parser.add_argument("--baseline", required=True)
    parser.add_argument("--report-name", default="api-compat")
    parser.add_argument("--observed", type=Path, help="Write findings for review outside the inventory; never approves them")
    args = parser.parse_args()
    if not re.fullmatch(r"1\.\d+\.\d+", args.baseline):
        parser.error("The 2.0 migration gate requires a released 1.x baseline")
    root = args.root.resolve()
    if args.observed:
        errors, changes, _, _ = collect(root, args.baseline, args.report_name)
        args.observed.write_text(json.dumps({"errors": errors, "changes": changes}, indent=2) + "\n")
    errors = check(root, args.baseline, args.report_name)
    for error in errors:
        print("::error::" + error)
    if not errors:
        print("Every published API comparison matches the reviewed 2.0 migration inventory")
    return int(bool(errors))


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""Contratos de capa: cada módulo pertenece al reactor y su nombre y parent coinciden con su capa."""
import sys
import re
from defusedxml import ElementTree as ET
from pathlib import Path

NS = {"m": "http://maven.apache.org/POM/4.0.0"}
NATIVE_REACTIVE = {"axiz-kit-r2dbc", "axiz-adapter-inbox-r2dbc", "axiz-adapter-outbox-r2dbc", "axiz-adapter-sequence-r2dbc"}
REACTIVE_CONTRACTS = {"axiz-kit-outbox-reactor": "axiz-core-messaging", "axiz-kit-audit-reactor": "axiz-core-audit"}
REACTIVE_MODULES = NATIVE_REACTIVE | set(REACTIVE_CONTRACTS)


def value(element, path):
    return element.findtext(path, default="", namespaces=NS).strip()


def validate(directory):
    root = Path(directory).resolve()
    errors, reactor, artifacts, native = [], set(), {}, set()
    module_names = {}

    def visit(pom):
        pom = pom.resolve()
        if not pom.is_relative_to(root) or pom in reactor:
            errors.append(f"Invalid, repeated or cyclic reactor module: {pom}")
            return
        if not pom.is_file():
            errors.append(f"Missing reactor module: {pom}")
            return
        reactor.add(pom)
        project = ET.parse(pom).getroot()
        for module in project.findall("m:modules/m:module", NS):
            visit(pom.parent / module.text.strip() / "pom.xml")

    visit(root / "pom.xml")
    for pom in sorted(root.rglob("pom.xml")):
        rel = pom.relative_to(root)
        if any(part in {"target", "src", ".git"} for part in rel.parts):
            continue
        project = ET.parse(pom).getroot()
        artifact = value(project, "m:artifactId")
        if artifact in artifacts:
            errors.append(f"Duplicate artifact {artifact}: {artifacts[artifact]}, {rel}")
        artifacts[artifact] = rel
        if pom.resolve() not in reactor:
            errors.append(f"Module outside reactor: {rel}")
        if value(project, "m:packaging") == "pom":
            continue
        layer = rel.parts[0]
        module_name = value(project, "m:properties/m:axiz.module.name")
        if not re.fullmatch(r"pe\.axiz(?:\.[a-z][a-z0-9]*)+", module_name):
            errors.append(f"{rel}: internal JAR needs its own pe.axiz.* module name")
        elif module_name in module_names:
            errors.append(f"Duplicate module name {module_name}: {module_names[module_name]}, {rel}")
        else:
            module_names[module_name] = rel
        parent = value(project, "m:parent/m:artifactId")
        expected = None
        if layer == "core":
            expected = ("axiz-core-", "axiz-parent-core")
        elif layer == "adapters":
            expected = ("axiz-adapter-", "axiz-parent-technology")
        elif layer == "kits":
            expected = ("axiz-kit-", "axiz-parent-technology")
        elif layer in {"structures", "extensions"}:
            prefix = "axiz-structure-" if layer == "structures" else "axiz-extension-"
            expected = (prefix, "axiz-parent-api" if artifact.endswith("-api") else "axiz-parent-spring")
        elif layer == "spring":
            expected = ("axiz-", "axiz-parent-spring")
        if expected and (not artifact.startswith(expected[0]) or parent != expected[1]):
            errors.append(f"{rel}: expected {expected[0]}* with parent {expected[1]}")
        flag = value(project, "m:properties/m:axiz.technology.reactive-native")
        if flag == "true":
            native.add(artifact)
        if layer in {"adapters", "kits"}:
            for dependency in project.findall("m:dependencies/m:dependency", NS):
                group = value(dependency, "m:groupId")
                if (group in {"io.projectreactor", "org.reactivestreams"}
                        and value(dependency, "m:scope") != "test" and artifact not in REACTIVE_MODULES):
                    errors.append(f"{rel}: direct reactive dependency outside the closed technology list")
                if artifact in REACTIVE_CONTRACTS and value(dependency, "m:scope") != "test":
                    coordinate = (group, value(dependency, "m:artifactId"))
                    if coordinate not in {("io.projectreactor", "reactor-core"), ("pe.axiz", REACTIVE_CONTRACTS[artifact])}:
                        errors.append(f"{rel}: provider/extra dependency in neutral Reactor contract: {coordinate}")
    if native != REACTIVE_MODULES:
        errors.append(f"Reactive technology modules must be exactly {sorted(REACTIVE_MODULES)}; found {sorted(native)}")
    return errors, len(reactor)


if __name__ == "__main__":
    findings, count = validate(sys.argv[1] if len(sys.argv) > 1 else ".")
    for finding in findings:
        print(f"::error::{finding}")
    if not findings:
        print(f"Layer structure consistent ({count} reactor modules; four R2DBC modules and two neutral Reactor contracts)")
    sys.exit(bool(findings))

#!/usr/bin/env python3
"""Every third-party library has one version, defined in one place.

Fails when:
- a module declares a version for a third-party dependency (it must come from axiz-dependencies,
  the BOMs it imports, or a property of the root POM for plugin dependencies);
- a module declares the same dependency twice;
- a version property is defined in more than one POM with different values (the root POM needs a
  few versions for its plugins that axiz-dependencies also manages: they must stay equal);
- a structure/extension starter (structures/*, extensions/*) brings a connection pool on its own. Persistence is the service's
  choice: a pool on the classpath makes Spring Boot require a database, so a service on DynamoDB, or
  without persistence, would not start. The batch style is exempt (Spring Batch keeps its job
  repository in Aurora, which the batch archetype always has).

Usage: check-dependency-governance.py [repository root ...]   (default: current directory)
"""
import glob
import os
import sys
from defusedxml import ElementTree as ET
from collections import defaultdict

NS = {"m": "http://maven.apache.org/POM/4.0.0"}
# POMs that own versions: the dependency catalogue and the root build POM (plugin dependencies)
OWNERS = ("platform/axiz-dependencies/pom.xml", "pom.xml")
# artifacts that make Spring Boot create a DataSource or a ConnectionFactory
POOLS = {"spring-boot-starter-jdbc", "spring-boot-starter-data-jpa", "spring-boot-starter-data-r2dbc",
         "HikariCP", "r2dbc-pool"}
STYLES_WITH_DATABASE = {"axiz-structure-batch-spring-boot-starter"}


def text(element, tag):
    found = element.find("m:" + tag, NS)
    return found.text.strip() if found is not None and found.text else None


def runtime_dependencies(project):
    """(groupId, artifactId) of the dependencies a consumer inherits: not optional, not test/provided."""
    result = []
    for dep in project.findall("m:dependencies/m:dependency", NS):
        if text(dep, "optional") == "true" or text(dep, "scope") in ("test", "provided"):
            continue
        result.append((text(dep, "groupId"), text(dep, "artifactId")))
    return result


def check_style_starters(modules, errors):
    for artifact, (path, deps) in sorted(modules.items()):
        if not (("/structures/" in path or "/extensions/" in path) and artifact.endswith("-spring-boot-starter")):
            continue
        if artifact in STYLES_WITH_DATABASE:
            continue
        seen, stack, trail = set(), [(d, [artifact]) for d in deps], None
        while stack:
            (group, name), via = stack.pop()
            if name in seen:
                continue
            seen.add(name)
            if name in POOLS:
                trail = " -> ".join(via + [name])
                break
            if group and group.startswith("pe.axiz") and name in modules:
                stack += [(d, via + [name]) for d in modules[name][1]]
        if trail:
            errors.append(f"{path}: the structure/extension starter brings a connection pool ({trail}); make that "
                          "dependency optional and let the service choose its persistence")


def main(roots):
    errors = []
    properties = defaultdict(list)
    modules = {}
    for root in roots:
        for path in sorted(glob.glob(os.path.join(root, "**", "pom.xml"), recursive=True)):
            relative = os.path.relpath(path, root)
            if "/target/" in path or "/src/" in path:
                continue
            # Trusted build POM/report, after executing the same checkout; no request XML is parsed.
            # nosemgrep: python.lang.security.use-defused-xml-parse.use-defused-xml-parse
            project = ET.parse(path).getroot()
            modules[text(project, "artifactId")] = (path, runtime_dependencies(project))
            props = project.find("m:properties", NS)
            if props is not None:
                for prop in props:
                    name = prop.tag.split("}")[1]
                    if name.endswith(".version") and prop.text and "${" not in prop.text:
                        properties[name].append((path, prop.text.strip()))
            owner = relative in OWNERS
            sections = [project.find("m:dependencies", NS)] + [
                p.find("m:dependencies", NS) for p in project.findall("m:profiles/m:profile", NS)
            ]
            for section in [s for s in sections if s is not None]:
                seen = set()
                for dep in section.findall("m:dependency", NS):
                    key = (text(dep, "groupId"), text(dep, "artifactId"), text(dep, "classifier"),
                           text(dep, "type") or "jar")
                    if key in seen:
                        errors.append(f"{path}: {key[0]}:{key[1]} declared twice")
                    seen.add(key)
                    if key[0] and not key[0].startswith("pe.axiz") and text(dep, "version"):
                        errors.append(
                            f"{path}: {key[0]}:{key[1]} has its own version; manage it in "
                            "platform/axiz-dependencies/pom.xml")
            if not owner:
                for dep in project.findall("m:dependencyManagement/m:dependencies/m:dependency", NS):
                    group = text(dep, "groupId")
                    if group and not group.startswith("pe.axiz") and text(dep, "scope") != "import":
                        errors.append(
                            f"{path}: manages {group}:{text(dep, 'artifactId')}; third-party versions "
                            "belong to platform/axiz-dependencies/pom.xml")
    check_style_starters(modules, errors)
    for name, definitions in properties.items():
        if len({value for _, value in definitions}) > 1:
            where = ", ".join(f"{p}={v}" for p, v in definitions)
            errors.append(f"property {name} has different values: {where}")
    for error in errors:
        print(f"::error::{error}")
    if errors:
        return 1
    print(f"dependency governance ok ({len(properties)} version properties)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:] or ["."]))

#!/usr/bin/env python3
"""Asigna las coordenadas finales del reactor conservando los parents externos y las versiones de las dependencias."""
import argparse
import re
from pathlib import Path
from xml.dom import Node
from defusedxml import minidom

from manifest import safe_file


def elements(node, name):
    return [child for child in node.childNodes if child.nodeType == Node.ELEMENT_NODE and child.localName == name]


def child(node, name):
    found = elements(node, name)
    if len(found) > 1:
        raise ValueError("Duplicate Maven " + name)
    return found[0] if found else None


def text(node, name):
    found = child(node, name)
    return "" if found is None else "".join(part.data for part in found.childNodes if part.nodeType == Node.TEXT_NODE).strip()


def replace(node, value):
    for part in list(node.childNodes):
        node.removeChild(part)
    node.appendChild(node.ownerDocument.createTextNode(value))


def set_version(project, value):
    version = child(project, "version")
    if version is None:
        artifact = child(project, "artifactId")
        version = project.ownerDocument.createElementNS(project.namespaceURI, "version")
        project.insertBefore(version, artifact.nextSibling)
    replace(version, value)


def update(root, version):
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version):
        raise ValueError("Reactor version must be a final release coordinate")
    root = Path(root).resolve()
    documents = {}

    def read(relative):
        relative = relative.as_posix()
        if relative in documents:
            raise ValueError("A reactor module is declared more than once")
        path = safe_file(root, relative)
        document = minidom.parseString(path.read_bytes())
        documents[relative] = document
        project = document.documentElement
        modules = child(project, "modules")
        if modules is not None:
            for module in elements(modules, "module"):
                name = "".join(node.data for node in module.childNodes if node.nodeType == Node.TEXT_NODE).strip()
                if not name:
                    raise ValueError("Empty Maven module path")
                read(Path(relative).parent / name / "pom.xml")

    try:
        read(Path("pom.xml"))
        coordinates = {}
        for document in documents.values():
            project = document.documentElement
            parent = child(project, "parent")
            group = text(project, "groupId") or (text(parent, "groupId") if parent else "")
            artifact = text(project, "artifactId")
            if not group or not artifact or "${" in group + artifact or (group, artifact) in coordinates:
                raise ValueError("Reactor groupId/artifactId must be explicit, unique coordinates")
            coordinates[(group, artifact)] = project
        planned = {}

        def planned_version(coordinate, parents):
            if coordinate in planned:
                return planned[coordinate]
            if coordinate in parents:
                raise ValueError("Cyclic Maven parent hierarchy")
            project = coordinates[coordinate]
            parent = child(project, "parent")
            parent_key = (text(parent, "groupId"), text(parent, "artifactId")) if parent else None
            inherited = planned_version(parent_key, parents | {coordinate}) if parent_key in coordinates else version
            own = text(project, "version")
            properties = child(project, "properties")
            revision = child(properties, "revision") if properties else None
            if own == "${revision}" and (revision is not None or inherited == "${revision}"):
                planned[coordinate] = own
            elif not own and parent_key in coordinates:
                planned[coordinate] = inherited
            else:
                planned[coordinate] = version
            return planned[coordinate]

        for coordinate in coordinates:
            planned_version(coordinate, set())
                # Se planifica cada edición antes de modificar un archivo. Los parents y BOM externos de Axiz permanecen fijados.
        for document in documents.values():
            project = document.documentElement
            parent = child(project, "parent")
            group = text(project, "groupId") or (text(parent, "groupId") if parent else "")
            parent_key = (text(parent, "groupId"), text(parent, "artifactId")) if parent else None
            properties = child(project, "properties")
            revision = child(properties, "revision") if properties else None
            if revision is not None:
                replace(revision, version)
            if child(project, "version") is not None or parent_key not in coordinates:
                set_version(project, planned[(group, text(project, "artifactId"))])
            for reference in project.getElementsByTagNameNS(project.namespaceURI, "dependency"):
                dependency_group = text(reference, "groupId")
                if dependency_group in {"${project.groupId}", "${pom.groupId}"}:
                    dependency_group = group
                if (dependency_group, text(reference, "artifactId")) in coordinates:
                    declared = child(reference, "version")
                    if declared is not None and declared.firstChild is not None:
                        replace(declared, version)
            if parent_key in coordinates:
                set_version(parent, planned[parent_key])
        for relative, document in documents.items():
            (root / relative).write_bytes(document.toxml(encoding="UTF-8"))
        return sorted(coordinates)
    finally:
        for document in documents.values():
            document.unlink()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path("."))
    parser.add_argument("--version", required=True)
    args = parser.parse_args()
    for group, artifact in update(args.root, args.version):
        print(f"Final reactor coordinate: {group}:{artifact}:{args.version}")


if __name__ == "__main__":
    main()

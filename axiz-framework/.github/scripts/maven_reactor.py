"""Inventory the actual Maven reactor, excluding stale target folders and unrelated POM files."""
from defusedxml import ElementTree as ET

NS = {"m": "http://maven.apache.org/POM/4.0.0"}

def text(node, path, default=""):
    return node.findtext(path, default, NS).strip()


def modules(root):
    """Follow the actual reactor, never accidentally approve samples or stale target POMs."""
    found = {}
    def visit(pom):
        project = ET.parse(pom).getroot()
        artifact = text(project, "m:artifactId")
        if artifact in found:
            raise ValueError("Duplicate reactor artifact: " + artifact)
        found[artifact] = (pom.parent, text(project, "m:packaging", "jar"), project)
        for module in project.findall("m:modules/m:module", NS):
            visit((pom.parent / module.text / "pom.xml").resolve())
    visit(root / "pom.xml")
    return found


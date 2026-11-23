#!/usr/bin/env python3
"""Genera los settings de Maven sin guardar valores de credenciales; aísla los candidatos verificados."""
import argparse
import os
import re
import shutil
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path
from urllib.parse import urlsplit


def create(settings, packages_url, temporary, candidate=None, expected_hash=None, repository=None):
    url = urlsplit(packages_url)
    if (url.scheme != "https" or url.netloc != "maven.pkg.github.com"
            or not re.fullmatch(r"/[a-z0-9][a-z0-9-]*/[A-Za-z0-9][A-Za-z0-9_.-]*/?", url.path)
            or url.query or url.fragment):
        raise ValueError("GitHub Packages requires an HTTPS owner/repository URL without credentials")
    requested = [bool(candidate), bool(expected_hash), bool(repository)]
    if any(requested) and not all(requested):
        raise ValueError("Candidate bundle, external manifest hash and source repository must be supplied together")
    root = ET.Element("settings")
    profiles = ET.SubElement(root, "profiles")
    active = ET.SubElement(root, "activeProfiles")
    profile = ET.SubElement(profiles, "profile")
    ET.SubElement(profile, "id").text = "axiz-github"
    repositories = {section: ET.SubElement(profile, section)
                    for section in ["repositories", "pluginRepositories"]}
    candidate_copy = local = None
    if candidate is not None:
        sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "build-manifest"))
        from manifest import verify, verify_maven_files
        manifest = verify(Path(candidate) / "build-manifest.json", expected_hash, Path(candidate), repository)
        if manifest["kind"] != "maven-library":
            raise ValueError("Only Maven builds can be used as a candidate repository")
        temporary = Path(temporary)
        temporary.mkdir(parents=True, exist_ok=True)
        isolated = Path(tempfile.mkdtemp(prefix="axiz-candidate-", dir=temporary))
        candidate_copy = isolated / "repository"
        shutil.copytree(Path(candidate) / "maven", candidate_copy)
        # Se revalidan los bytes copiados antes de permitir que Maven resuelva algo a partir de ellos.
        verify_maven_files(manifest["artifacts"]["maven"], candidate_copy)
        for path in candidate_copy.rglob("*"):
            path.chmod(0o555 if path.is_dir() else 0o444)
        candidate_copy.chmod(0o555)
        local = isolated / "m2"
        local.mkdir()
        ET.SubElement(root, "localRepository").text = str(local)
        for section, element in [("repositories", "repository"), ("pluginRepositories", "pluginRepository")]:
            candidate_repo = ET.SubElement(repositories[section], element)
            ET.SubElement(candidate_repo, "id").text = "axiz-candidate"
            ET.SubElement(candidate_repo, "url").text = candidate_copy.as_uri()
            for policy in ["releases", "snapshots"]:
                policy_node = ET.SubElement(candidate_repo, policy)
                ET.SubElement(policy_node, "enabled").text = "true"
                ET.SubElement(policy_node, "updatePolicy").text = "always"
                ET.SubElement(policy_node, "checksumPolicy").text = "fail"
    # Packages es un repositorio Maven alojado, no un proxy. Maven Central se mantiene disponible.
    for section, element in [("repositories", "repository"), ("pluginRepositories", "pluginRepository")]:
        remote = ET.SubElement(repositories[section], element)
        ET.SubElement(remote, "id").text = "github"
        ET.SubElement(remote, "url").text = packages_url.rstrip("/")
        for policy in ["releases", "snapshots"]:
            node = ET.SubElement(remote, policy)
            ET.SubElement(node, "enabled").text = "true"
            ET.SubElement(node, "checksumPolicy").text = "fail"
    ET.SubElement(active, "activeProfile").text = "axiz-github"
    servers = ET.SubElement(root, "servers")
    # Las credenciales de build son de solo lectura; los workflows de despliegue usan credenciales de destino separadas.
    for server_id in ["github"]:
        server = ET.SubElement(servers, "server")
        ET.SubElement(server, "id").text = server_id
        ET.SubElement(server, "username").text = "${env.PACKAGES_USERNAME}"
        ET.SubElement(server, "password").text = "${env.PACKAGES_TOKEN}"
    ET.indent(root)
    settings = Path(settings)
    settings.parent.mkdir(parents=True, exist_ok=True)
    settings.write_bytes(ET.tostring(root, encoding="utf-8", xml_declaration=True))
    settings.chmod(0o600)
    return candidate_copy, local


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--settings", type=Path, required=True)
    parser.add_argument("--temporary", type=Path, required=True)
    parser.add_argument("--packages-url", required=True)
    parser.add_argument("--candidate", type=Path)
    parser.add_argument("--manifest-sha256")
    parser.add_argument("--repository")
    args = parser.parse_args()
    candidate, local = create(args.settings, args.packages_url, args.temporary,
                              args.candidate, args.manifest_sha256, args.repository)
    if "GITHUB_OUTPUT" in os.environ:
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
            output.write(f"settings={args.settings}\n")
            if local is not None:
                output.write(f"local-repository={local}\ncandidate-repository={candidate}\n")


if __name__ == "__main__":
    main()

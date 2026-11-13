#!/usr/bin/env python3
"""Generate Maven settings without storing credential values; isolate verified candidates."""
import argparse
import os
import shutil
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path
from urllib.parse import urlsplit


def create(settings, nexus_url, temporary, candidate=None, expected_hash=None, repository=None):
    url = urlsplit(nexus_url)
    if url.scheme not in {"https", "http"} or not url.netloc or url.username or url.password or url.query or url.fragment:
        raise ValueError("Nexus must be an HTTP(S) base URL without embedded credentials, query or fragment")
    requested = [bool(candidate), bool(expected_hash), bool(repository)]
    if any(requested) and not all(requested):
        raise ValueError("Candidate bundle, external manifest hash and source repository must be supplied together")
    root = ET.Element("settings")
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
        # Revalidate the copied bytes before letting Maven resolve anything from them.
        verify_maven_files(manifest["artifacts"]["maven"], candidate_copy)
        for path in candidate_copy.rglob("*"):
            path.chmod(0o555 if path.is_dir() else 0o444)
        candidate_copy.chmod(0o555)
        local = isolated / "m2"
        local.mkdir()
        ET.SubElement(root, "localRepository").text = str(local)
        profiles = ET.SubElement(root, "profiles")
        profile = ET.SubElement(profiles, "profile")
        ET.SubElement(profile, "id").text = "axiz-candidate"
        for section, element in [("repositories", "repository"), ("pluginRepositories", "pluginRepository")]:
            candidate_repo = ET.SubElement(ET.SubElement(profile, section), element)
            ET.SubElement(candidate_repo, "id").text = "axiz-candidate"
            ET.SubElement(candidate_repo, "url").text = candidate_copy.as_uri()
            for policy in ["releases", "snapshots"]:
                policy_node = ET.SubElement(candidate_repo, policy)
                ET.SubElement(policy_node, "enabled").text = "true"
                ET.SubElement(policy_node, "updatePolicy").text = "always"
                ET.SubElement(policy_node, "checksumPolicy").text = "fail"
        ET.SubElement(ET.SubElement(root, "activeProfiles"), "activeProfile").text = "axiz-candidate"
    mirror = ET.SubElement(ET.SubElement(root, "mirrors"), "mirror")
    ET.SubElement(mirror, "id").text = "nexus"
    ET.SubElement(mirror, "mirrorOf").text = "*,!axiz-candidate" if candidate is not None else "*"
    ET.SubElement(mirror, "url").text = nexus_url.rstrip("/") + "/repository/maven-public/"
    servers = ET.SubElement(root, "servers")
    # Build credentials are read-only; deploying workflows use separate destination credentials.
    for server_id in ["nexus", "nexus-releases", "nexus-snapshots"]:
        server = ET.SubElement(servers, "server")
        ET.SubElement(server, "id").text = server_id
        ET.SubElement(server, "username").text = "${env.NEXUS_USERNAME}"
        ET.SubElement(server, "password").text = "${env.NEXUS_PASSWORD}"
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
    parser.add_argument("--nexus-url", required=True)
    parser.add_argument("--candidate", type=Path)
    parser.add_argument("--manifest-sha256")
    parser.add_argument("--repository")
    args = parser.parse_args()
    candidate, local = create(args.settings, args.nexus_url, args.temporary,
                              args.candidate, args.manifest_sha256, args.repository)
    if "GITHUB_OUTPUT" in os.environ:
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
            output.write(f"settings={args.settings}\n")
            if local is not None:
                output.write(f"local-repository={local}\ncandidate-repository={candidate}\n")


if __name__ == "__main__":
    main()

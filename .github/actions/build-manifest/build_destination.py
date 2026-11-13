#!/usr/bin/env python3
"""One explicit build destination shared by producer, isolated signer and GitOps rendering."""
import argparse
from dataclasses import dataclass
import json
import os
from pathlib import Path
import re

import yaml

SEGMENT = r"[a-z0-9]+(?:[._-][a-z0-9]+)*"
REPOSITORY = r"[A-Za-z0-9][A-Za-z0-9_.-]*/[A-Za-z0-9][A-Za-z0-9_.-]*"
GITOPS_STATE_ROOT = "environments"


@dataclass(frozen=True)
class BuildDestination:
    registry: str
    namespace: str
    repository: str
    gitops_repository: str
    services: tuple[str, ...]

    @classmethod
    def parse(cls, raw):
        value = json.loads(raw)
        keys = {"registry", "namespace", "repository", "gitops_repository", "services"}
        if not isinstance(value, dict) or set(value) != keys:
            raise ValueError("Build destination requires explicit registry, namespace, repository, gitops_repository and services")
        registry = value["registry"]
        if not isinstance(registry, str) or not re.fullmatch(r"[a-z0-9]+(?:[.-][a-z0-9]+)*(?::[1-9][0-9]{0,4})?", registry):
            raise ValueError("Registry must be a host[:port], not a URL or namespace")
        if ":" in registry and int(registry.rsplit(":", 1)[1]) > 65535:
            raise ValueError("Registry port is out of range")
        namespace = value["namespace"]
        if not isinstance(namespace, str) or not re.fullmatch(SEGMENT + r"(?:/" + SEGMENT + r")*", namespace):
            raise ValueError("Namespace must be an explicit relative registry path")
        for key in ("repository", "gitops_repository"):
            if not isinstance(value[key], str) or not re.fullmatch(REPOSITORY, value[key]):
                raise ValueError(key + " must be an exact GitHub owner/repository")
        services = value["services"]
        if (not isinstance(services, list) or not services or any(not isinstance(s, str) or not re.fullmatch(r"[a-z][a-z0-9]*(?:-[a-z0-9]+)*", s) for s in services)
                or len(services) != len(set(services))):
            raise ValueError("Services must be a nonempty explicit list of distinct service names")
        if any(token in str(value).lower() for token in ("unconfigured", "change_me", "<", ">", ".invalid")):
            raise ValueError("Build destination contains an unconfigured marker")
        return cls(registry, namespace, value["repository"], value["gitops_repository"], tuple(services))

    def require_source(self, repository, service):
        if repository != self.repository or service not in self.services:
            raise ValueError("Source repository or service is outside the approved build destination")

    def image(self, repository, service):
        self.require_source(repository, service)
        return f"{self.registry}/{self.namespace}/builds/{repository.lower()}/{service}"

    def chart(self, repository, service, run, attempt, variant):
        self.require_source(repository, service)
        if not all(re.fullmatch(r"[1-9][0-9]*", str(v)) for v in (run, attempt)) or variant not in {"jvm", "native"}:
            raise ValueError("Chart destination requires a valid run, attempt and variant")
        return f"oci://{self.registry}/{self.namespace}/build-charts/{repository.lower()}/{run}/{attempt}/{variant}/{service}"

    def render(self, templates, output):
        """Render deployment templates; callers must supply this same configuration to CI."""
        documents = {}
        for relative in ("argocd/project", "argocd/applicationset", "policies/verify-images"):
            documents[relative] = yaml.safe_load((templates / (relative + ".yaml.template")).read_text())
        project = documents["argocd/project"]["spec"]
        charts = [f"oci://{self.registry}/{self.namespace}/build-charts/{self.repository.lower()}/*/*/{variant}/{service}"
                  for service in self.services for variant in ("jvm", "native")]
        project["sourceRepos"] = sorted(set(["https://github.com/" + self.repository,
            "https://github.com/" + self.gitops_repository, *charts]))
        generator = documents["argocd/applicationset"]["spec"]["generators"][0]["git"]
        generator["repoURL"] = "https://github.com/" + self.gitops_repository
        generator["files"] = [{"path": GITOPS_STATE_ROOT + "/*/*.yaml"}]
        images = [self.image(self.repository, service) for service in self.services]
        policy = documents["policies/verify-images"]["spec"]["rules"]
        policy[0]["verifyImages"][0]["imageReferences"] = [image + "@sha256:*" for image in images]
        policy[1]["validate"] = {
            "message": "Every container must use an approved service image pinned by digest.",
            "foreach": [{"list": "request.object.spec." + containers,
                "anyPattern": [{"image": image + "@sha256:*"} for image in images]}
                for containers in ("containers", "initContainers", "ephemeralContainers")]}
        for relative, document in documents.items():
            path = output / (relative + ".yaml")
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(yaml.safe_dump(document, sort_keys=False))
        return documents


def from_environment():
    return BuildDestination.parse(os.environ["BUILD_DESTINATION"])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=("coordinates", "render"))
    parser.add_argument("--configuration", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    destination = BuildDestination.parse(args.configuration.read_text()) if args.configuration else from_environment()
    if args.operation == "render":
        if args.output is None:
            parser.error("render requires --output")
        destination.render(Path(__file__).resolve().parents[2] / "gitops", args.output)
    else:
        repository = os.environ["GITHUB_REPOSITORY"]
        service = os.environ["SERVICE"]
        image = destination.image(repository, service)
        chart = destination.chart(repository, service, os.environ["GITHUB_RUN_ID"], os.environ["GITHUB_RUN_ATTEMPT"], os.environ["VARIANT"])
        print("REGISTRY_AUTHORITY=" + destination.registry)
        print("IMAGE_REPOSITORY=" + image)
        print("CHART_STORE=" + chart.rsplit("/", 1)[0])


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Promueve bytes de Maven verificados; nunca invoca Maven ni regenera metadatos del producto."""
import argparse
import base64
import hashlib
import json
import os
import re
import tempfile
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

from manifest import read_json, safe_file, verify


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, file, code, message, headers, url):
        raise ValueError("Repository redirects are not allowed during artifact promotion")


class GitHubMaven:
    def __init__(self, base, username, password, allow_local_test=False):
        parts = urllib.parse.urlsplit(base)
        if (parts.scheme != "https" and not (allow_local_test and parts.hostname in {"127.0.0.1", "localhost"})):
            raise ValueError("GitHub Packages promotion requires HTTPS")
        if not parts.netloc or parts.username or parts.password or parts.query or parts.fragment:
            raise ValueError("Invalid GitHub Packages base URL")
        if not allow_local_test and (parts.netloc != "maven.pkg.github.com" or
                not re.fullmatch(r"/[a-z0-9][a-z0-9-]*/[A-Za-z0-9][A-Za-z0-9_.-]*/?", parts.path)):
            raise ValueError("Publication must target a GitHub Maven owner/repository")
        if not username or not password:
            raise ValueError("GitHub Packages credentials are required")
        self.base = base.rstrip("/")
        self.authorization = "Basic " + base64.b64encode((username + ":" + password).encode()).decode()
        self.opener = urllib.request.build_opener(NoRedirect())

    def url(self, path):
        return self.base + "/" + urllib.parse.quote(path, safe="/")

    def current_hash(self, path):
        request = urllib.request.Request(self.url(path), headers={"Authorization": self.authorization})
        try:
            with self.opener.open(request, timeout=60) as response:
                digest = hashlib.sha256()
                for chunk in iter(lambda: response.read(1024 * 1024), b""):
                    digest.update(chunk)
                return digest.hexdigest()
        except urllib.error.HTTPError as error:
            if error.code == 404:
                return None
            raise ValueError(f"GitHub Packages preflight failed with HTTP {error.code}") from None

    def upload(self, path, content):
        # Una instantánea privada y verificada permanece abierta durante toda la subida: ninguna ruta se vuelve a abrir.
        content.seek(0, os.SEEK_END)
        size = content.tell()
        content.seek(0)
        request = urllib.request.Request(self.url(path), data=content, method="PUT", headers={
            "Authorization": self.authorization, "Content-Length": str(size),
            "Content-Type": "application/octet-stream", "If-None-Match": "*"})
        try:
            with self.opener.open(request, timeout=120):
                pass
        except urllib.error.HTTPError as error:
            raise ValueError(f"GitHub Packages upload failed with HTTP {error.code}; retry the same verified build") from None


def promote(data, bundle, registry):
    if data["kind"] != "maven-library" or "-" in data["version"]:
        raise ValueError("Only final Maven release coordinates can be promoted")
    pending = []
    # Se completa la verificación previa de cada archivo antes de subir cualquier archivo.
    for item in data["artifacts"]["maven"]:
        local = safe_file(Path(bundle) / "maven", item["path"])
        if hashlib.sha256(local.read_bytes()).hexdigest() != item["sha256"]:
            raise ValueError("Verified staging changed before promotion")
        current = registry.current_hash(item["path"])
        if current is not None and current != item["sha256"]:
            raise ValueError(f"Immutable artifact already exists with different bytes: {item['path']}")
        if current is None:
            pending.append(item)
    for item in sorted(pending, key=lambda item: (item["path"].endswith(".pom"), item["path"])):
        file = safe_file(Path(bundle) / "maven", item["path"])
        # Se copian y se calcula el hash de los mismos bytes en un archivo privado sin enlace. Modificar el
        # inodo original o su entrada de directorio después de la verificación no puede cambiar el cuerpo del PUT.
        with (tempfile.TemporaryDirectory(prefix="axiz-maven-", dir=os.environ.get("RUNNER_TEMP")) as temporary,
              tempfile.TemporaryFile(dir=temporary) as snapshot):
            checksum = hashlib.sha256()
            with file.open("rb") as original:
                while block := original.read(1024 * 1024):
                    checksum.update(block)
                    snapshot.write(block)
            if checksum.hexdigest() != item["sha256"]:
                raise ValueError("Verified staging changed before promotion")
            snapshot.seek(0)
            registry.upload(item["path"], snapshot)
        if registry.current_hash(item["path"]) != item["sha256"]:
            raise ValueError("GitHub Packages stored content differs from the approved build")
    # Las subidas de Maven a GitHub no son atómicas entre archivos: nunca se confirma una publicación parcial.
    # Reintentar este mismo build verificado completa los archivos faltantes sin modificar los bytes existentes.
    for item in data["artifacts"]["maven"]:
        if registry.current_hash(item["path"]) != item["sha256"]:
            raise ValueError("Maven publication is incomplete; retry the same verified build")
    return len(pending)


def publication_url(base, repository):
    """Vincula el destino de publicación al repositorio de origen autenticado."""
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9-]*/[A-Za-z0-9][A-Za-z0-9_.-]*", repository):
        raise ValueError("Invalid publication source repository")
    expected = "https://maven.pkg.github.com/" + repository.lower()
    if base.rstrip("/").lower() != expected:
        raise ValueError("Maven publication destination differs from the source repository")
    return expected


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle", type=Path, required=True)
    parser.add_argument("--manifest-sha256", required=True)
    parser.add_argument("--repository", required=True)
    args = parser.parse_args()
    data = verify(args.bundle / "build-manifest.json", args.manifest_sha256, args.bundle, args.repository)
    registry = GitHubMaven(publication_url(os.environ["PACKAGES_URL"], args.repository),
                           os.environ["PACKAGES_USERNAME"], os.environ["PACKAGES_TOKEN"])
    print(json.dumps({"uploaded": promote(data, args.bundle, registry), "source": data["source"],
                      "version": data["version"]}, sort_keys=True))


if __name__ == "__main__":
    main()

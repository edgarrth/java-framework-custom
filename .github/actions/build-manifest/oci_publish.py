"""Valida los archivos OCI como datos y sube sus blobs originales sin ejecutar imágenes."""
import base64
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import tarfile
import urllib.error
import urllib.parse
import urllib.request

import rfc8785

MANIFEST = "application/vnd.oci.image.manifest.v1+json"
INDEX = "application/vnd.oci.image.index.v1+json"
CONFIG = "application/vnd.oci.image.config.v1+json"
LIMIT = 2 * 1024 * 1024 * 1024
JSON_LIMIT = 4 * 1024 * 1024
DIGEST = re.compile(r"sha256:[0-9a-f]{64}")


def digest(content):
    return "sha256:" + hashlib.sha256(content).hexdigest()


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate OCI JSON key")
        result[key] = value
    return result


def document(content):
    if len(content) > JSON_LIMIT:
        raise ValueError("OCI JSON exceeds its budget")
    try:
        result = json.loads(content, object_pairs_hook=unique_object)
    except RecursionError:
        raise ValueError("OCI JSON nesting exceeds its budget") from None
    if not isinstance(result, dict):
        raise ValueError("OCI metadata must be an object")
    pending = [(result, 0)]
    while pending:
        value, depth = pending.pop()
        if depth > 64:
            raise ValueError("OCI JSON nesting exceeds its budget")
        if isinstance(value, dict):
            pending.extend((child, depth + 1) for child in value.values())
        elif isinstance(value, list):
            pending.extend((child, depth + 1) for child in value)
    return result


def metadata_file(path):
    with path.open("rb") as stream:
        return document(stream.read(JSON_LIMIT + 1))


def archive(path, destination, platform):
    """Extrae solo archivos OCI regulares validados; los archivos de las capas siguen siendo datos opacos."""
    if platform not in {"linux/amd64", "linux/arm64"} or destination.exists():
        raise ValueError("Expected a supported platform and a fresh OCI destination")
    destination.mkdir(parents=True)
    seen, total = set(), 0
    with tarfile.open(path, "r:*") as source:
        for entry in source:
            name = entry.name.removesuffix("/")
            relative = PurePosixPath(name)
            total += entry.size
            if (name in seen or str(relative) != name or relative.is_absolute() or ".." in relative.parts
                    or "\\" in name or len(seen) >= 100000 or total > LIMIT or entry.size < 0):
                raise ValueError("Unsafe or oversized OCI archive")
            seen.add(name)
            if entry.isdir() and name in {"blobs", "blobs/sha256"}:
                continue
            if not entry.isfile() or not (name in {"index.json", "oci-layout"}
                    or re.fullmatch(r"blobs/sha256/[0-9a-f]{64}", name)):
                raise ValueError("Unexpected OCI archive entry")
            target = destination / name
            target.parent.mkdir(parents=True, exist_ok=True)
            checksum = hashlib.sha256()
            with source.extractfile(entry) as incoming, target.open("xb") as outgoing:
                while block := incoming.read(1024 * 1024):
                    checksum.update(block)
                    outgoing.write(block)
            if name.startswith("blobs/") and checksum.hexdigest() != relative.name:
                raise ValueError("OCI blob digest differs from its bytes")
            target.chmod(0o400)
    if metadata_file(destination / "oci-layout") != {"imageLayoutVersion": "1.0.0"}:
        raise ValueError("Unsupported OCI layout")
    index = metadata_file(destination / "index.json")
    if (index.get("schemaVersion") != 2 or not isinstance(index.get("manifests"), list)
            or len(index["manifests"]) != 1):
        raise ValueError("Each build archive must contain exactly one image")

    def blob(descriptor, media=None):
        if (not isinstance(descriptor, dict) or not isinstance(descriptor.get("digest"), str)
                or not DIGEST.fullmatch(descriptor["digest"])
                or type(descriptor.get("size")) is not int or descriptor["size"] < 0
                or "urls" in descriptor or media and descriptor.get("mediaType") != media):
            raise ValueError("Invalid OCI descriptor")
        file = destination / "blobs/sha256" / descriptor["digest"][7:]
        if not file.is_file() or file.stat().st_size != descriptor["size"]:
            raise ValueError("Missing or truncated OCI blob")
        return file

    descriptor = index["manifests"][0]
    manifest_path = blob(descriptor, MANIFEST)
    manifest = metadata_file(manifest_path)
    if manifest.get("schemaVersion") != 2 or manifest.get("mediaType") != MANIFEST:
        raise ValueError("Unsupported OCI image manifest")
    config_path = blob(manifest.get("config"), CONFIG)
    config = metadata_file(config_path)
    if (not isinstance(config.get("os"), str) or not isinstance(config.get("architecture"), str)
            or config["os"] + "/" + config["architecture"] != platform):
        raise ValueError("OCI image differs from the requested platform")
    layers = manifest.get("layers")
    if not isinstance(layers, list) or not layers:
        raise ValueError("Image must contain layers")
    blobs = [config_path]
    for layer in layers:
        if (not isinstance(layer, dict) or not isinstance(layer.get("mediaType"), str)
                or layer["mediaType"] not in {"application/vnd.oci.image.layer.v1.tar+gzip",
                                           "application/vnd.oci.image.layer.v1.tar",
                                           "application/vnd.oci.image.layer.v1.tar+zstd"}):
            raise ValueError("Unsupported or external image layer")
        blobs.append(blob(layer))
    expected = {file.name for file in [manifest_path, *blobs]}
    if {file.name for file in (destination / "blobs/sha256").iterdir()} != expected:
        raise ValueError("OCI archive contains unreferenced content")
    return {"mediaType": MANIFEST, "digest": descriptor["digest"], "size": descriptor["size"],
            "platform": dict(zip(("os", "architecture"), platform.split("/")))}, manifest_path, blobs


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, file, code, message, headers, url):
        raise ValueError("OCI publication redirects are forbidden")


class Registry:
    def __init__(self, repository, username, password, *, origin="https://ghcr.io", local_test=False):
        if not re.fullmatch(r"[a-z0-9]+(?:[._-][a-z0-9]+)*(?:/[a-z0-9]+(?:[._-][a-z0-9]+)*)+", repository):
            raise ValueError("Invalid OCI repository")
        if origin != "https://ghcr.io" and not (local_test and re.fullmatch(r"http://127\.0\.0\.1:[0-9]+", origin)):
            raise ValueError("OCI publication must target GHCR over TLS")
        if not username or not password:
            raise ValueError("GHCR credentials are required")
        self.origin, self.repository = origin, repository
        self.base = origin + "/v2/" + repository
        self.opener = urllib.request.build_opener(NoRedirect())
        query = urllib.parse.urlencode({"service": "ghcr.io", "scope": "repository:" + repository + ":pull,push"})
        auth = "Basic " + base64.b64encode((username + ":" + password).encode()).decode()
        request = urllib.request.Request(origin + "/token?" + query, headers={"Authorization": auth})
        with self.opener.open(request, timeout=30) as response:
            token = document(response.read(JSON_LIMIT + 1)).get("token")
        if not isinstance(token, str) or not token or any(c in token for c in "\r\n"):
            raise ValueError("Missing registry access token")
        self.authorization = "Bearer " + token

    def request(self, url, method="GET", data=None, headers=None):
        if not url.startswith(self.base + "/"):
            raise ValueError("Registry upload escaped its authorized repository")
        request = urllib.request.Request(url, data=data, method=method,
            headers={"Authorization": self.authorization, **(headers or {})})
        return self.opener.open(request, timeout=300)

    def upload_blob(self, file):
        with file.open("rb") as content:
            wanted = "sha256:" + hashlib.file_digest(content, "sha256").hexdigest()
        if wanted[7:] != file.name:
            raise ValueError("OCI content changed before publication")
        query = urllib.parse.urlencode({"mount": wanted, "from": self.repository})
        with self.request(self.base + "/blobs/uploads/?" + query, "POST", b"") as response:
            if response.status == 201:
                if response.headers.get("Docker-Content-Digest") != wanted:
                    raise ValueError("Registry did not confirm the mounted blob digest")
                return
            if response.status != 202:
                raise ValueError("Registry did not open a blob upload")
            location = urllib.parse.urljoin(self.origin, response.headers.get("Location", ""))
        parsed = urllib.parse.urlsplit(location)
        if (not location.startswith(self.base + "/blobs/uploads/") or parsed.fragment
                or parsed.username or parsed.password or ".." in parsed.path.split("/") or "%" in parsed.path):
            raise ValueError("Registry upload escaped its authorized repository")
        separator = "&" if "?" in location else "?"
        with file.open("rb") as content, self.request(location + separator + urllib.parse.urlencode({"digest": wanted}),
                "PUT", content, {"Content-Length": str(file.stat().st_size),
                                 "Content-Type": "application/octet-stream"}) as response:
            if response.status != 201 or response.headers.get("Docker-Content-Digest") != wanted:
                raise ValueError("Registry did not confirm the original blob digest")

    def put_manifest(self, reference, content, media):
        if not (DIGEST.fullmatch(reference) or re.fullmatch(r"[a-zA-Z0-9_][a-zA-Z0-9._-]{0,127}", reference)):
            raise ValueError("Invalid OCI manifest reference")
        url = self.base + "/manifests/" + reference
        try:
            with self.request(url, headers={"Accept": media}) as response:
                existing = response.read(JSON_LIMIT + 1)
            if existing != content:
                raise ValueError("Immutable OCI reference already contains different bytes")
            return digest(content)
        except urllib.error.HTTPError as error:
            if error.code != 404:
                raise
        with self.request(url, "PUT", content, {"Content-Type": media}) as response:
            if response.status != 201 or response.headers.get("Docker-Content-Digest") != digest(content):
                raise ValueError("Registry did not confirm the original manifest digest")
        with self.request(url, headers={"Accept": media}) as response:
            if response.read(JSON_LIMIT + 1) != content:
                raise ValueError("Stored image manifest differs from the approved bytes")
        return digest(content)


def publish(registry, images, tag):
    """Todos los archivos deben haber superado la validación antes de invocar esta fase de mutación."""
    if not images or len(images) != len({json.dumps(image[0]["platform"], sort_keys=True) for image in images}):
        raise ValueError("Expected distinct, nonempty image platforms")
    for descriptor, file, blobs in images:
        for blob in blobs:
            registry.upload_blob(blob)
        content = file.read_bytes()
        if digest(content) != descriptor["digest"]:
            raise ValueError("Image manifest changed before publication")
        registry.put_manifest(descriptor["digest"], content, MANIFEST)
    if len(images) == 1:
        content, media = images[0][1].read_bytes(), MANIFEST
    else:
        content = rfc8785.dumps({"schemaVersion": 2, "mediaType": INDEX,
                                "manifests": [image[0] for image in images]})
        media = INDEX
    return registry.put_manifest(tag, content, media)

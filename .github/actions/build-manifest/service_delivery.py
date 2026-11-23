"""Transfiere un servicio probado como datos a un publicador de GHCR aislado; nunca ejecuta código del proyecto."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tarfile
import tempfile

import rfc8785
import yaml

import authority
from build_destination import from_environment
import manifest
import oci_publish
import prepare
from sign_product import compare, expected, TAG
from signer_artifacts import download


def file_hash(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def stage(metadata, content, reports, output):
    if output.exists():
        raise ValueError("Service transfer must use a fresh directory")
    shutil.copytree(content, output, symlinks=True)
    shutil.copytree(reports, output / "reports", symlinks=True)
    files = {}
    for path in sorted(output.rglob("*")):
        if path.is_symlink():
            raise ValueError("Service transfer cannot contain symbolic links")
        if path.is_file():
            relative = path.relative_to(output).as_posix()
            manifest.safe_file(output, relative)
            files[relative] = {"sha256": file_hash(path), "size": path.stat().st_size}
    if not files:
        raise ValueError("Service transfer is empty")
    (output / "transfer.json").write_bytes(rfc8785.dumps({"schema": 1, "metadata": metadata, "files": files}))


def validate_transfer(root, data, destination):
    transfer = manifest.read_json(manifest.safe_file(root, "transfer.json"))
    if set(transfer) != {"schema", "metadata", "files"} or transfer["schema"] != 1:
        raise ValueError("Unsupported service transfer")
    compare(transfer["metadata"], data)
    files = transfer["files"]
    actual = {p.relative_to(root).as_posix() for p in root.rglob("*") if p.is_file() or p.is_symlink()}
    if not isinstance(files, dict) or actual != {*files, "transfer.json"}:
        raise ValueError("Service transfer file inventory differs")
    platforms = data["parameters"]["platforms"]
    image_files = {"images/" + platform.replace("/", "-") + ".tar" for platform in platforms}
    chart_file = f"chart/{data['parameters']['service']}-{data['version']}.tgz"
    if {name for name in files if not name.startswith("reports/")} != image_files | {chart_file}:
        raise ValueError("Service transfer differs from the expected images/chart")
    for name, record in files.items():
        path = manifest.safe_file(root, name)
        if (not isinstance(record, dict) or set(record) != {"sha256", "size"}
                or path.stat().st_size != record["size"] or file_hash(path) != record["sha256"]):
            raise ValueError("Service transfer bytes changed")
    images = [oci_publish.archive(manifest.safe_file(root, "images/" + platform.replace("/", "-") + ".tar"),
              destination / platform.replace("/", "-"), platform) for platform in platforms]
    chart = manifest.safe_file(root, chart_file)
    with tarfile.open(chart, "r:gz") as archive:
        names, total, metadata = set(), 0, None
        for entry in archive:
            total += entry.size
            if (len(names) > 10000 or total > 32 * 1024 * 1024 or entry.name in names
                    or not entry.name.startswith(data["parameters"]["service"] + "/")
                    or ".." in Path(entry.name).parts or "\\" in entry.name
                    or not (entry.isfile() or entry.isdir())):
                raise ValueError("Unsafe packaged chart")
            names.add(entry.name)
            if entry.name == data["parameters"]["service"] + "/Chart.yaml":
                if entry.size > 1024 * 1024:
                    raise ValueError("Chart metadata exceeds its bound")
                metadata = yaml.safe_load(archive.extractfile(entry).read())
        if (not isinstance(metadata, dict) or metadata.get("name") != data["parameters"]["service"]
                or str(metadata.get("version")) != data["version"]
                or str(metadata.get("appVersion")) != data["version"]):
            raise ValueError("Chart differs from the expected service/version")
    return images, chart


def publish(data, public, root, output):
    """Actions certifica al worker; los archivos nunca establecen que las pruebas terminaron con éxito."""
    service, variant = data["parameters"]["service"], data["parameters"]["variant"]
    receipt = authority.obtain(data["source"], data["build"]["run_id"], data["build"]["run_attempt"],
                               f"compile-service:{service}:{variant}", "service-compile")
    destination = from_environment()
    if (destination.registry != "ghcr.io" or destination.namespace.split("/")[0]
            != data["source"]["repository"].split("/")[0].lower()):
        raise ValueError("GHCR publication must stay within the source owner's namespace")
    image = destination.image(data["source"]["repository"], service)
    chart_ref = destination.chart(data["source"]["repository"], service, data["build"]["run_id"],
                                  data["build"]["run_attempt"], variant)
    tag = f"{data['source']['sha']}-{data['build']['run_id']}-{data['build']['run_attempt']}-{variant}"
    if (root / "target").exists() or output.exists():
        raise ValueError("Isolated publisher requires fresh evidence paths")
    output.mkdir(parents=True)
    with tempfile.TemporaryDirectory(prefix="axiz-service-data-", dir=os.environ["RUNNER_TEMP"]) as temporary:
        temporary = Path(temporary)
        # Las credenciales de la CLI son privadas de esta invocación y se eliminan tanto si termina bien como si falla.
        credentials = temporary / "credentials"
        credentials.mkdir(mode=0o700)
        docker_config = credentials / "docker"
        docker_config.mkdir(mode=0o700)
        cli_env = {**{key: value for key, value in os.environ.items()
                       if key in {"PATH", "HOME", "TMPDIR", "LANG", "LC_ALL", "SSL_CERT_FILE", "SSL_CERT_DIR"}}, "DOCKER_CONFIG": str(docker_config),
                   "HELM_REGISTRY_CONFIG": str(credentials / "helm-registry.json")}
        transfer = download(receipt, f"service-transfer-{service}-{variant}-{data['build']['run_attempt']}",
                            receipt["producer_job"], temporary / "transfer")
        images, chart = validate_transfer(transfer, data, temporary / "oci")
        # Toda la validación termina antes de obtener acceso de escritura al registry o de subir bytes.
        client = oci_publish.Registry(image.removeprefix("ghcr.io/"),
                                      os.environ["REGISTRY_USERNAME"], os.environ["REGISTRY_PASSWORD"])
        image_digest = oci_publish.publish(client, images, tag)
        subprocess.run(["helm", "registry", "login", "ghcr.io", "--username", os.environ["REGISTRY_USERNAME"],
                        "--password-stdin"], input=os.environ["REGISTRY_PASSWORD"].encode(), check=True, timeout=60, env=cli_env)
        result = subprocess.run(["helm", "push", str(chart), chart_ref.rsplit("/", 1)[0]],
                                check=True, text=True, capture_output=True, timeout=180, env=cli_env)
        match = re.findall(r"(?m)^Digest: (sha256:[0-9a-f]{64})$", result.stdout + result.stderr)
        if len(match) != 1:
            raise ValueError("Missing published chart identity")
        subprocess.run(["docker", "login", "ghcr.io", "--username", os.environ["REGISTRY_USERNAME"],
                        "--password-stdin"], input=os.environ["REGISTRY_PASSWORD"].encode(), check=True, timeout=60, env=cli_env)
        from service import image as image_record, chart as chart_record, inspect
        metadata = yaml.safe_load(subprocess.check_output(["helm", "show", "chart", chart_ref + "@" + match[0]], text=True, timeout=120, env=cli_env))
        data["artifacts"] = {
            "image": image_record(data, image, tag, image_digest, inspect(image + "@" + image_digest, env=cli_env)),
            "chart": chart_record(data, chart_ref, match[0], inspect(chart_ref[6:] + "@" + match[0], "{{json .Manifest}}", env=cli_env), metadata)}
        shutil.copytree(transfer / "reports", root / "target/build-reports")
        (root / "target/build-reports/compile-authority.json").write_bytes(rfc8785.dumps(receipt))
        (output / "metadata.json").write_bytes(rfc8785.dumps(data))
        if "GITHUB_OUTPUT" in os.environ:
            with open(os.environ["GITHUB_OUTPUT"], "a") as stream:
                stream.write(f"metadata={output / 'metadata.json'}\nimage={image}@{image_digest}\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=("stage", "publish"))
    parser.add_argument("--metadata", type=Path)
    parser.add_argument("--content", type=Path)
    parser.add_argument("--reports", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.operation == "stage":
        if not all((args.metadata, args.content, args.reports)):
            parser.error("stage requires metadata, content and reports")
        stage(manifest.read_json(args.metadata), args.content, args.reports, args.output)
    else:
        public = json.loads(os.environ["BUILD_INPUTS_JSON"])
        data = expected("service", public, os.environ, prepare.oidc_claims(), prepare.resolve_workflow(TAG))
        publish(data, public, Path(os.environ["GITHUB_WORKSPACE"]), args.output)


if __name__ == "__main__":
    main()

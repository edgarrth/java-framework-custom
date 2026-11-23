#!/usr/bin/env python3
"""Vincula los componentes nativos de la CLI a un único build de Maven firmado antes de la promoción independiente."""
import argparse
import io
import json
import os
import platform
import re
import shutil
import subprocess
import tarfile
from defusedxml import ElementTree as ET
import zipfile
from pathlib import Path, PurePosixPath

import rfc8785

from manifest import read_json, safe_file, sha256

PLATFORMS = {"linux-x64": ("Linux", "x86_64", "tar.gz"),
             "macos-arm64": ("Darwin", "arm64", "tar.gz"),
             "windows-x64": ("Windows", "AMD64", "zip")}
NATIVE_KEYS = {"module", "binary", "platforms"}
MAX_ARCHIVE = 512 * 1024 * 1024


def specification(module, binary, platforms):
    if not isinstance(module, str) or not module or "\\" in module or PurePosixPath(module).is_absolute() or ".." in PurePosixPath(module).parts:
        raise ValueError("Native module must be relative to the reactor")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*|\.", module):
        raise ValueError("Invalid native module")
    if str(PurePosixPath(module)) != module:
        raise ValueError("Native module must be a canonical relative path")
    if not isinstance(binary, str) or not re.fullmatch(r"[A-Za-z][A-Za-z0-9_-]*", binary):
        raise ValueError("Invalid native binary name")
    if (not isinstance(platforms, list) or not platforms or any(not isinstance(item, str) for item in platforms)
            or len(set(platforms)) != len(platforms) or not set(platforms) <= PLATFORMS.keys()):
        raise ValueError("Native platforms must be distinct supported targets")
    return {"module": module, "binary": binary, "platforms": sorted(platforms)}


def coordinate(root, module, version):
    project = ET.fromstring(safe_file(root, str(PurePosixPath(module) / "pom.xml")).read_bytes())
    group = project.findtext("{*}groupId") or project.findtext("{*}parent/{*}groupId")
    artifact = project.findtext("{*}artifactId")
    if not group or not re.fullmatch(r"[A-Za-z0-9_.-]+", group) or not artifact or not re.fullmatch(r"[A-Za-z0-9_.-]+", artifact):
        raise ValueError("Native module must have literal Maven coordinates")
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version):
        raise ValueError("Native coordinate must be a final version")
    return {"groupId": group, "artifactId": artifact, "version": version}


def target_binary(path, target):
    if path.is_symlink() or not path.is_file() or path.stat().st_size > MAX_ARCHIVE:
        raise ValueError("Native executable is missing, oversized or a symlink")
    data = path.read_bytes()
    binary_architecture(data, target)
    return data


def binary_architecture(data, target):
    if target == "linux-x64":
        valid = data.startswith(b"\x7fELF\x02\x01\x01") and len(data) >= 64 and int.from_bytes(data[18:20], "little") == 62
    elif target == "macos-arm64":
        valid = len(data) >= 32 and data.startswith(b"\xcf\xfa\xed\xfe") and int.from_bytes(data[4:8], "little") == 0x100000c
    elif target == "windows-x64":
        offset = int.from_bytes(data[60:64], "little") if len(data) >= 64 else 0
        valid = data.startswith(b"MZ") and data[offset:offset + 6] == b"PE\0\0\x64\x86"
    else:
        raise ValueError("Unsupported native executable platform")
    if not valid:
        raise ValueError("Native executable architecture differs from its declared platform")


def archive_bytes(data, binary, target):
    output = io.BytesIO()
    if target == "windows-x64":
        with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            item = zipfile.ZipInfo(binary + ".exe", (2000, 1, 1, 0, 0, 0))
            item.external_attr = 0o100755 << 16
            archive.writestr(item, data)
    else:
        import gzip
        with gzip.GzipFile(fileobj=output, mode="wb", mtime=0, filename="") as compressed:
            with tarfile.open(fileobj=compressed, mode="w", format=tarfile.USTAR_FORMAT) as archive:
                item = tarfile.TarInfo(binary)
                item.size = len(data)
                item.mode = 0o755
                item.mtime = 0
                archive.addfile(item, io.BytesIO(data))
    return output.getvalue()


def create(root, metadata, spec, target, output):
    if target not in spec["platforms"] or (platform.system(), platform.machine()) != PLATFORMS[target][:2]:
        raise ValueError("Native build runner differs from the requested platform")
    executable = spec["binary"] + (".exe" if target == "windows-x64" else "")
    path = safe_file(root, str(PurePosixPath(spec["module"]) / "target" / executable))
    data = target_binary(path, target)
        # Los comandos de smoke no heredan credenciales del repositorio.
    allowed = {"PATH", "JAVA_HOME", "SYSTEMROOT", "SystemRoot", "COMSPEC", "WINDIR", "TMP", "TEMP", "TMPDIR", "HOME", "USERPROFILE", "LANG"}
    environment = {key: value for key, value in os.environ.items() if key in allowed}
    commands = [[str(path), "--version"]]
    if spec["binary"] == "axiz":
        commands.append([str(path), "catalog"])
    results = []
    for command in commands:
        result = subprocess.run(command, cwd=root, env=environment, check=True, capture_output=True, timeout=45)
        results.append(result.stdout + result.stderr)
    if spec["binary"] == "axiz" and not re.search(rb"(?m)^axiz " + re.escape(metadata["version"].encode("ascii")) + rb"(?:\s|$)", results[0]):
        raise ValueError("Native CLI version differs from the Maven build")
    extension = PLATFORMS[target][2]
    packaged = archive_bytes(data, spec["binary"], target)
    smoke = b"\n".join(results)
    if not smoke.strip() or len(smoke) > 4 * 1024 * 1024:
        raise ValueError("Native smoke evidence is empty or oversized")
    output.mkdir(parents=True, exist_ok=False)
    (output / ("archive." + extension)).write_bytes(packaged)
    (output / "smoke.log").write_bytes(smoke)
    component = {"schema": "axiz.native-component/v1", "source": metadata["source"], "build": metadata["build"],
                 "dependencies": metadata.get("dependencies", {}), "version": metadata["version"],
                 "parameters": metadata["parameters"], "candidate": metadata.get("candidate", ""),
                 "native": {"module": spec["module"], "binary": spec["binary"], "platform": target},
                 "coordinate": coordinate(root, spec["module"], metadata["version"]),
                 "archive": {"path": "archive." + extension, "sha256": sha256(packaged)},
                 "smoke": {"path": "smoke.log", "sha256": sha256(smoke)}}
    (output / "native-component.json").write_bytes(rfc8785.dumps(component))
    return component


def verify_identity(component, expected, spec, target):
    if set(component) != {"schema", "source", "build", "dependencies", "version", "parameters", "candidate", "native", "coordinate", "archive", "smoke"}:
        raise ValueError("Unknown native component fields")
    if component["schema"] != "axiz.native-component/v1" or component["source"] != expected["source"] or component["version"] != expected["version"] or component["dependencies"] != expected.get("dependencies", {}):
        raise ValueError("Native component source/version/dependencies differ from the Maven build")
    if component["parameters"] != expected["parameters"] or component["candidate"] != expected.get("candidate", ""):
        raise ValueError("Native component effective parameters differ from the Maven build")
    if component["native"] != {"module": spec["module"], "binary": spec["binary"], "platform": target}:
        raise ValueError("Native component specification differs from the Maven build")
    identity = dict(expected["build"]["workflow"], path=".github/workflows/native.yaml")
    if component["build"] != dict(expected["build"], workflow=identity):
        raise ValueError("Native component must belong to the same producer run and immutable workflow release")
    if component["archive"]["path"] != "archive." + PLATFORMS[target][2] or component["smoke"]["path"] != "smoke.log":
        raise ValueError("Unexpected native component file")
    for descriptor in [component["archive"], component["smoke"]]:
        if set(descriptor) != {"path", "sha256"} or not re.fullmatch(r"[0-9a-f]{64}", descriptor["sha256"]):
            raise ValueError("Invalid native component file identity")


def verify_component(component, directory, expected, spec, target, *, require_signature=True):
    verify_identity(component, expected, spec, target)
    if safe_file(directory, "native-component.json").read_bytes() != rfc8785.dumps(component):
        raise ValueError("Native component metadata must be canonical JSON")
    for descriptor in [component["archive"], component["smoke"]]:
        path = safe_file(directory, descriptor["path"])
        if path.stat().st_size > MAX_ARCHIVE or sha256(path.read_bytes()) != descriptor["sha256"]:
            raise ValueError("Native component bytes differ from their signed identity")
    if not safe_file(directory, "smoke.log").read_bytes().strip():
        raise ValueError("Native smoke evidence is empty")
    expected_files = {component["archive"]["path"], "smoke.log", "native-component.json"}
    if require_signature:
        expected_files.add("native-component.sigstore.json")
    if {path.name for path in directory.iterdir()} != expected_files or any(path.is_symlink() for path in directory.rglob("*")):
        raise ValueError("Unexpected native component bundle contents")
    verify_archive(safe_file(directory, component["archive"]["path"]), spec["binary"], target)


def verify_archive(path, binary, target):
    executable = binary + (".exe" if target == "windows-x64" else "")
    if target == "windows-x64":
        with zipfile.ZipFile(path) as archive:
            entries = archive.infolist()
            if len(entries) != 1 or entries[0].filename != executable or entries[0].file_size > MAX_ARCHIVE:
                raise ValueError("Native archive must contain exactly its declared executable")
            if entries[0].external_attr >> 16 != 0o100755:
                raise ValueError("Native archive executable must be a regular executable file")
            data = archive.read(entries[0])
    else:
        with tarfile.open(path, mode="r:gz") as archive:
            entry = archive.next()
            if (entry is None or entry.name != executable or not entry.isfile() or entry.size > MAX_ARCHIVE
                    or entry.mode != 0o755 or entry.linkname):
                raise ValueError("Native archive must contain exactly its declared executable")
            with archive.extractfile(entry) as stream:
                data = stream.read(MAX_ARCHIVE + 1)
            if archive.next() is not None:
                raise ValueError("Native archive contains additional files")
    binary_architecture(data, target)


def new_file(root, relative):
    """Verifica previamente las rutas sin seguir ni siquiera un enlace colgante y sin sobrescribir evidencia."""
    if root.is_symlink() or not root.is_dir():
        raise ValueError("Native staging root must be a directory, not a symlink")
    name = PurePosixPath(relative)
    if name.is_absolute() or ".." in name.parts or "\\" in relative or str(name) != relative:
        raise ValueError("Unsafe native staging destination")
    for index in range(1, len(name.parts) + 1):
        path = root.joinpath(*name.parts[:index])
        if path.is_symlink() or (index < len(name.parts) and path.exists() and not path.is_dir()):
            raise ValueError("Unsafe native staging destination")
    destination = root / relative
    if destination.exists():
        raise ValueError("Native Maven artifact or evidence already exists")
    return destination


def compile_native(root, metadata):
    from manifest import validate_maven_arguments
    spec = metadata["parameters"]["native"]
    if spec != specification(spec["module"], spec["binary"], spec["platforms"]):
        raise ValueError("Invalid native compilation parameters")
    arguments = metadata["parameters"]["maven_args"]
    validate_maven_arguments(arguments)
    wrapper = root / ("mvnw.cmd" if os.name == "nt" else "mvnw")
    executable = str(wrapper.resolve()) if wrapper.is_file() else shutil.which("mvn")
    if not executable:
        raise ValueError("Maven executable is required")
    subprocess.run([executable, "-B", "-ntp", "-Pnative", "-pl", spec["module"], "-am", "package", *arguments],
                   cwd=root, check=True)


def merge(root, metadata_path, components, staging, reports):
    data = read_json(metadata_path)
    spec = data["parameters"]["native"]
    if set(spec) != NATIVE_KEYS or specification(spec["module"], spec["binary"], spec["platforms"]) != spec:
        raise ValueError("Invalid native Maven build parameters")
    own_coordinate = coordinate(root, spec["module"], data["version"])
    if components.is_symlink() or not components.is_dir():
        raise ValueError("Native component root must be a directory")
    new_file(reports, "native.json")
    planned = []
    for target in spec["platforms"]:
        directory = components / f"native-component-{data['build']['run_attempt']}-{target}"
        if directory.is_symlink() or not directory.is_dir():
            raise ValueError("Missing native platform directory or symlink")
        component = read_json(safe_file(directory, "native-component.json"))
        verify_component(component, directory, data, spec, target)
        if component["coordinate"] != own_coordinate:
            raise ValueError("Native component Maven coordinate differs from its source module")
        workflow = data["build"]["workflow"]
        subprocess.run(["cosign", "verify-blob", "--bundle", str(safe_file(directory, "native-component.sigstore.json")),
                        "--certificate-identity", "https://github.com/edgarrth/java-framework-custom/.github/workflows/native.yaml@" + workflow["ref"],
                        "--certificate-oidc-issuer", "https://token.actions.githubusercontent.com",
                        "--certificate-github-workflow-repository", data["source"]["repository"],
                        "--certificate-github-workflow-sha", data["source"]["sha"],
                        str(directory / "native-component.json")], check=True)
        group = own_coordinate["groupId"].replace(".", "/")
        artifact = own_coordinate["artifactId"]
        prefix = f"{group}/{artifact}/{data['version']}/{artifact}-{data['version']}"
        safe_file(staging, prefix + ".pom")  # Solo se enriquece una coordenada que Maven ya construyó.
        relative = prefix + "-native-" + target + "." + PLATFORMS[target][2]
        new_file(staging, relative)
        new_file(staging, relative + ".sha256")
        for name in ("native-component.json", "native-component.sigstore.json", "smoke.log"):
            new_file(reports, "native/" + target + "/" + name)
        planned.append((target, component, directory, relative))
    if {item.name for item in components.iterdir()} != {f"native-component-{data['build']['run_attempt']}-{target}" for target in spec["platforms"]}:
        raise ValueError("Unexpected native platform bundle")
        # Se verifica previamente cada plataforma y firma antes de modificar el staging de Maven.
    records = []
    for target, component, directory, relative in planned:
        source = safe_file(directory, component["archive"]["path"])
        destination = staging / relative
        shutil.copyfile(source, destination)
        destination.with_name(destination.name + ".sha256").write_text(component["archive"]["sha256"] + "\n")
        evidence = {}
        for name in ["native-component.json", "native-component.sigstore.json", "smoke.log"]:
            path = reports / "native" / target / name
            path.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(safe_file(directory, name), path)
            evidence[name] = {"path": "reports/native/" + target + "/" + name, "sha256": sha256(path.read_bytes())}
        records.append({"platform": target, "artifact": relative, "evidence": evidence})
    (reports / "native.json").write_bytes(rfc8785.dumps({"schema": "axiz.native-verification/v1", "components": records}))
    return records


def validate_manifest(data, bundle=None):
    """Verifica la cadena de evidencia sellada por la firma del build principal, sin confiar en archivos adicionales."""
    spec = data["parameters"].get("native")
    gates = [gate for gate in data["gates"] if gate["name"] == "native"]
    if spec is None:
        if gates:
            raise ValueError("Native gate without native parameters")
        return []
    if spec != specification(spec["module"], spec["binary"], spec["platforms"]):
        raise ValueError("Invalid canonical native parameters")
    if len(gates) != 1 or gates[0]["evidence"]["path"] != "reports/native.json":
        raise ValueError("Native build requires its verification gate")
    artifacts = data["artifacts"]["maven"]
    selected = [item for item in artifacts if item.get("classifier", "").startswith("native-")]
    expected = {(target, extension) for target in spec["platforms"]
                for extension in (PLATFORMS[target][2], PLATFORMS[target][2] + ".sha256")}
    actual = {(item["classifier"].removeprefix("native-"), item["extension"]) for item in selected}
    if actual != expected or len(selected) != len(expected):
        raise ValueError("Native artifact platforms differ from the declared build")
    coordinates = {(item["groupId"], item["artifactId"], item["version"]) for item in selected}
    if len(coordinates) != 1:
        raise ValueError("Native components must enrich one Maven coordinate")
    gav = dict(zip(("groupId", "artifactId", "version"), next(iter(coordinates))))
    if not any(all(item[key] == value for key, value in gav.items()) and item["extension"] == "pom"
               and "classifier" not in item for item in artifacts):
        raise ValueError("Native artifacts require their built Maven POM")
    if bundle is None:
        return selected
    bundle = Path(bundle)
    proof_path = safe_file(bundle, "reports/native.json")
    if sha256(proof_path.read_bytes()) != gates[0]["evidence"]["sha256"]:
        raise ValueError("Native verification evidence changed after sealing")
    proof = read_json(proof_path)
    if set(proof) != {"schema", "components"} or proof["schema"] != "axiz.native-verification/v1":
        raise ValueError("Invalid native verification evidence")
    if [record["platform"] for record in proof["components"]] != spec["platforms"]:
        raise ValueError("Missing or duplicated native verification platform")
    for record in proof["components"]:
        target = record["platform"]
        item = next(item for item in selected if item["classifier"] == "native-" + target
                    and item["extension"] == PLATFORMS[target][2])
        if set(record) != {"platform", "artifact", "evidence"} or record["artifact"] != item["path"]:
            raise ValueError("Native evidence artifact differs from the final inventory")
        files = {"native-component.json", "native-component.sigstore.json", "smoke.log"}
        if set(record["evidence"]) != files:
            raise ValueError("Incomplete native component evidence")
        for name, descriptor in record["evidence"].items():
            if (set(descriptor) != {"path", "sha256"} or descriptor["path"] != "reports/native/" + target + "/" + name
                    or sha256(safe_file(bundle, descriptor["path"]).read_bytes()) != descriptor["sha256"]):
                raise ValueError("Native component evidence changed after verification")
        component = read_json(safe_file(bundle, record["evidence"]["native-component.json"]["path"]))
        verify_identity(component, data, spec, target)
        if component["coordinate"] != gav or component["archive"]["sha256"] != item["sha256"]:
            raise ValueError("Native signed component differs from the final Maven artifact")
        smoke = safe_file(bundle, record["evidence"]["smoke.log"]["path"]).read_bytes()
        if not smoke.strip() or sha256(smoke) != component["smoke"]["sha256"]:
            raise ValueError("Native smoke evidence differs from the signed component")
        archive = safe_file(bundle / "maven", item["path"])
        if sha256(archive.read_bytes()) != item["sha256"]:
            raise ValueError("Native Maven archive content hash mismatch")
        if safe_file(bundle / "maven", item["path"] + ".sha256").read_text() != item["sha256"] + "\n":
            raise ValueError("Native Maven checksum differs from the archive")
    return selected


def release_assets(data, bundle):
    """Expone las descargas por plataforma solo para bytes nativos vinculados al manifiesto principal aprobado."""
    items = validate_manifest(data, bundle)
    if not items:
        return {}
    result = {}
    binary = data["parameters"]["native"]["binary"]
    for item in items:
        if item["extension"].endswith(".sha256"):
            continue
        target = item["classifier"].removeprefix("native-")
        name = f"{binary}-v{data['version']}-{target}.{item['extension']}"
        result[name] = safe_file(Path(bundle) / "maven", item["path"]).read_bytes()
        result[name + ".sha256"] = (item["sha256"] + "  " + name + "\n").encode()
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=["compile", "create", "merge"])
    parser.add_argument("--root", type=Path, default=Path.cwd())
    parser.add_argument("--metadata", type=Path, required=True)
    parser.add_argument("--module")
    parser.add_argument("--binary")
    parser.add_argument("--platform")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--components", type=Path)
    parser.add_argument("--staging", type=Path)
    parser.add_argument("--reports", type=Path)
    args = parser.parse_args()
    if args.mode == "compile":
        compile_native(args.root, read_json(args.metadata))
    elif args.mode == "create":
        if not all((args.module, args.binary, args.platform, args.output)):
            parser.error("create requires --module, --binary, --platform and --output")
        metadata = read_json(args.metadata)
        spec = metadata["parameters"]["native"]
        if spec != specification(spec["module"], spec["binary"], spec["platforms"]) or args.module != spec["module"] or args.binary != spec["binary"]:
            parser.error("create arguments must match the bound native specification")
        create(args.root, metadata, spec, args.platform, args.output)
    else:
        if not all((args.components, args.staging, args.reports)):
            parser.error("merge requires --components, --staging and --reports")
        merge(args.root, args.metadata, args.components, args.staging, args.reports)


if __name__ == "__main__":
    main()

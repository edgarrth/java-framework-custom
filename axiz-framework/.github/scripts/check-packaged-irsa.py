#!/usr/bin/env python3
"""Check final Boot JAR STS closure and resolve IRSA from a non-Kafka consumer against local STS."""
import argparse
import hashlib
import json
import subprocess
import tempfile
import zipfile
from pathlib import Path
from irsa_fixture import IrsaFixture

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("jars", type=Path, nargs="+")
parser.add_argument("--output", type=Path, required=True)
args = parser.parse_args()
results = {}
probed = False
for jar in args.jars:
    with zipfile.ZipFile(jar) as archive:
        libs = [name for name in archive.namelist() if name.startswith("BOOT-INF/lib/") and name.endswith(".jar")]
        sts = [name for name in libs if Path(name).name.startswith("sts-")]
        if len(sts) != 1:
            raise ValueError(f"Exactly one runtime STS module is required: {jar}")
        kafka = any(Path(name).name.startswith("kafka-clients-") for name in libs)
        result = {"jarSha256": hashlib.sha256(jar.read_bytes()).hexdigest(), "sts": sts[0], "kafka": kafka,
                  "credentialRoute": "framework policy" if any(Path(name).name.startswith("axiz-adapter-aws-") for name in libs)
                  else "SDK web identity; Spring Cloud AWS bootstrap dependency closure only"}
        results[str(jar)] = result
        if kafka:
            continue
        with tempfile.TemporaryDirectory(prefix="axiz-packaged-irsa-") as directory:
            extracted = Path(directory)
            for name in libs:
                (extracted / Path(name).name).write_bytes(archive.read(name))
            fixture = IrsaFixture()
            try:
                for success in (True, False):
                    fixture.succeed = success
                    before = fixture.sts_calls
                    command = ["java", "-cp", str(extracted / "*"), str(Path(__file__).with_name("IrsaPackagedProbe.java").resolve()), str(success).lower()]
                    child = subprocess.run(command, env=fixture.environment(), text=True,
                                           stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=60)
                    if child.returncode or fixture.sts_calls <= before or fixture.imds_calls or fixture.invalid_requests:
                        raise RuntimeError(f"Packaged IRSA check failed for {jar}: {child.stdout}")
                result.update(temporaryCredentials=True, invalidIrsaRejected=True,
                              stsRequests=fixture.sts_calls, imdsRequests=fixture.imds_calls)
                probed = True
            finally:
                fixture.close()
if not probed:
    raise ValueError("A real packaged consumer without Kafka is required")
args.output.write_text(json.dumps({"passed": True, "artifacts": results}, indent=2) + "\n")
print(f"PASS STS in {len(results)} final JARs; no-Kafka IRSA resolves and fails closed using local STS")

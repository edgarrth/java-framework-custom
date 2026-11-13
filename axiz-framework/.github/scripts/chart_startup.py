"""Boot the actual service JAR using bootstrap variables rendered by its Helm chart."""
from contextlib import closing
import base64
import hashlib
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request

import yaml

from irsa_fixture import IrsaFixture


def render(chart, values, path):
    path.write_text(yaml.safe_dump(values))
    rendered = subprocess.check_output(["helm", "template", "beneficiaries", str(chart), "-f", str(path)], text=True, timeout=30)
    documents = [row for row in yaml.safe_load_all(rendered) if row and row.get("kind") == "Deployment"]
    if len(documents) != 1:
        raise ValueError("Exactly one chart workload is required")
    entries = documents[0]["spec"]["template"]["spec"]["containers"][0]["env"]
    environment = {}
    for entry in entries:
        if "value" not in entry or entry["name"] in environment:
            raise ValueError("Startup fixture requires unique concrete chart variables")
        environment[entry["name"]] = str(entry["value"])
    if "SPRING_CONFIG_IMPORT" in environment:
        raise ValueError("Chart proof must not inject the Parameter Store import")
    if any("-Daxiz.config." in environment.get(key, "") for key in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS")):
        raise ValueError("Chart proof must use the exported bootstrap variables")
    return environment


def wait(process, port, healthy, log):
    deadline = time.monotonic() + (90 if healthy else 45)
    while time.monotonic() < deadline:
        if process.poll() is not None:
            if healthy or process.returncode == 0:
                raise RuntimeError("Unexpected packaged startup exit; see " + str(log))
            return
        if healthy:
            try:
                with urllib.request.urlopen(f"http://127.0.0.1:{port}/actuator/health", timeout=2) as response:
                    if json.load(response).get("status") == "UP":
                        return
            except (OSError, ValueError):
                pass
        time.sleep(0.25)
    raise TimeoutError("Packaged chart startup did not reach its required outcome; see " + str(log))


def verify_chart_startup(jar, chart, endpoint, output):
    results = []
    cases = ["chart-bootstrap", "chart-missing-selector", "chart-late-selector", "chart-local-profile"]
    for case in cases:
        with closing(IrsaFixture()) as identity, tempfile.TemporaryDirectory(prefix="axiz-chart-jar-") as directory:
            root = Path(directory)
            secrets = root / "secrets"
            secrets.mkdir()
            (secrets / "cursor-key-v1").write_text(base64.b64encode(os.urandom(32)).decode())
            with socket.socket() as reservation:
                reservation.bind(("127.0.0.1", 0))
                port = reservation.getsockname()[1]
            fixture = identity.environment()
            process_variables = {key: value for key, value in fixture.items() if key.startswith(("AWS_", "AXIZ_"))}
            process_variables.update({
                "SERVER_ADDRESS": "127.0.0.1", "SERVER_PORT": str(port),
                "AXIZ_SECRETS_DIRECTORY": str(secrets), "AXIZ_AWS_ENDPOINT": endpoint,
                "AXIZ_AWS_REGION": "us-east-1", "SPRING_CLOUD_AWS_REGION_STATIC": "us-east-1",
                "SPRING_CLOUD_AWS_ENDPOINT": endpoint,
                "SPRING_CLOUD_AWS_PARAMETERSTORE_ENDPOINT": f"http://127.0.0.1:{identity.server.server_port}",
                "MANAGEMENT_TRACING_ENABLED": "false", "MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED": "false",
                "MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENABLED": "false", "AXIZ_LOGGING_JSON": "false",
            })
            # The chart itself exports the environment; a duplicate env entry would hide a broken contract.
            process_variables.pop("AXIZ_CONFIG_ENVIRONMENT")
            remote = case in {"chart-bootstrap", "chart-missing-selector"}
            if case == "chart-late-selector":
                late = root / "late.yaml"
                late.write_text("axiz.config.parameter-store.enabled: true\n")
                process_variables["SPRING_CONFIG_ADDITIONAL_LOCATION"] = late.as_uri()
            if case == "chart-local-profile":
                process_variables["SPRING_PROFILES_ACTIVE"] = "local"
            values = {"environment": "test", "javaOpts": "-Xms64m -Xmx384m",
                      "image": {"digest": "sha256:" + "a" * 64},
                      "configBootstrap": {"parameterStore": {"enabled": remote,
                         "application": "bootstrap-fixture", "prefix": "/axiz-proof"}},
                      "env": [{"name": key, "value": value} for key, value in process_variables.items()]}
            rendered = render(chart, values, root / "values.yaml")
            if case == "chart-missing-selector":
                rendered.pop("AXIZ_CONFIG_PARAMETER_STORE_APPLICATION")
            if case == "chart-local-profile":
                rendered.pop("AXIZ_CONFIG_ENVIRONMENT")  # Explicit application-local.yaml supplies local.
            environment = {key: value for key, value in fixture.items() if not key.startswith(("AWS_", "AXIZ_"))}
            environment.update(rendered)
            command = ["java", "-jar", str(jar)]  # No --spring.config.import, system selector or extra application argument.
            log_path = output / (case + ".log")
            healthy = case in {"chart-bootstrap", "chart-local-profile"}
            with log_path.open("w") as log:
                # A fixed java -jar command, real local JAR and only OS/IRSA + rendered chart variables.
                # nosemgrep: python.lang.security.audit.dangerous-subprocess-use-tainted-env-args.dangerous-subprocess-use-tainted-env-args
                process = subprocess.Popen(command, env=environment, stdout=log, stderr=subprocess.STDOUT)
                try:
                    wait(process, port, healthy, log_path)
                    if case == "chart-bootstrap":
                        if not identity.ssm_paths or {path.rstrip("/") + "/" for path in identity.ssm_paths} != {"/axiz-proof/test/bootstrap-fixture/"}:
                            raise AssertionError("Parameter Store path did not come from the rendered bootstrap selectors")
                    elif identity.ssm_calls:
                        raise AssertionError("No remote Parameter Store load was permitted for " + case)
                    if not healthy and identity.sts_calls:
                        raise AssertionError("Invalid selectors must fail before identity I/O")
                    if identity.invalid_requests or identity.imds_calls:
                        raise AssertionError("Invalid signed request or forbidden metadata fallback")
                finally:
                    if process.poll() is None:
                        process.terminate()
                        try:
                            process.wait(timeout=15)
                        except subprocess.TimeoutExpired:
                            process.kill()
                            process.wait(timeout=5)
            results.append({"case": case, "passed": True, "stsRequests": identity.sts_calls,
                            "ssmRequests": identity.ssm_calls, "ssmPaths": identity.ssm_paths,
                            "imdsRequests": identity.imds_calls, "log": log_path.name,
                            "logSha256": hashlib.sha256(log_path.read_bytes()).hexdigest()})
    return results

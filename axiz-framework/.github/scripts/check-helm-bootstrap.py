#!/usr/bin/env python3
"""Renderiza los charts del framework y de la demo; verifica los selectores de bootstrap y la selección versionada de ConfigMaps."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import yaml

class UniqueKeys(yaml.SafeLoader):
    pass

def mapping(loader, node, deep=False):
    result = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node, deep=deep)
        if key in result:
            raise ValueError("Duplicate YAML key: " + str(key))
        result[key] = loader.construct_object(value_node, deep=deep)
    return result

UniqueKeys.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, mapping)

def render(chart, settings, reject=False):
    arguments = ["helm", "template", "bootstrap-check", str(chart)]
    for key, value in {"image.digest": "sha256:" + "a" * 64, **settings}.items():
        arguments.extend(["--set-string", key + "=" + value])
    result = subprocess.run(arguments, capture_output=True, text=True, timeout=30, check=False)
    if reject:
        assert result.returncode != 0, "Invalid chart configuration was accepted"
        return None
    assert result.returncode == 0, result.stderr
    documents = list(yaml.load_all(result.stdout, Loader=UniqueKeys))
    workloads = [document for document in documents if document and document["kind"] in {"Deployment", "CronJob"}]
    assert len(workloads) == 1, "Exactly one workload is required"
    workload = workloads[0]
    return workload["spec"]["template"] if workload["kind"] == "Deployment" else workload["spec"]["jobTemplate"]["spec"]["template"]

def verify(chart):
    render(chart, {"environment": ""}, reject=True)
    base = {"environment": "qa", "sharedConfigMap": ""}
    pod = render(chart, base)
    environment = {entry["name"]: entry.get("value") for entry in pod["spec"]["containers"][0]["env"]}
    assert environment["AXIZ_CONFIG_ENVIRONMENT"] == "qa"
    assert environment["SPRING_APPLICATION_NAME"]
    assert "AXIZ_CONFIG_PARAMETER_STORE_APPLICATION" not in environment
    assert "config-common" not in {volume["name"] for volume in pod["spec"]["volumes"]}
    assert "checksum/config-common" not in pod["metadata"]["annotations"]
    for name in ["axiz-common-2-0-0", "axiz-common-2-0-1"]:
        selected = render(chart, {**base, "sharedConfigMap": name})
        volume = next(volume for volume in selected["spec"]["volumes"] if volume["name"] == "config-common")
        assert volume["configMap"] == {"name": name}, "A selected CM must not be optional"
        assert selected["metadata"]["annotations"]["checksum/config-common"] == hashlib.sha256(name.encode()).hexdigest()
    remote = {**base, "configBootstrap.parameterStore.enabled": "true"}
    render(chart, remote, reject=True)
    configured = render(chart, {**remote, "configBootstrap.parameterStore.application": "bootstrap-fixture"})
    environment = {entry["name"]: entry.get("value") for entry in configured["spec"]["containers"][0]["env"]}
    assert environment["AXIZ_CONFIG_PARAMETER_STORE_ENABLED"] == "true"
    assert environment["AXIZ_CONFIG_PARAMETER_STORE_APPLICATION"] == "bootstrap-fixture"
    assert environment["AXIZ_CONFIG_PARAMETER_STORE_PREFIX"] == "/axiz"
    return {"chart": str(chart), "cases": 7, "passed": True}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--framework", type=Path, default=Path("."))
    parser.add_argument("--demo", type=Path, default=Path("../axiz-demo"))
    args = parser.parse_args()
    framework, demo = args.framework.resolve(), args.demo.resolve()
    charts = sorted(demo.glob("services/*/deploy/helm/*/Chart.yaml"))
    assert len(charts) == 9, "All nine demo charts are required"
    results = []
    with tempfile.TemporaryDirectory(prefix="axiz-helm-bootstrap-") as directory:
        for role, service, workload in [("server", "payment-orders", "deployment.yaml"), ("batch", "settlement-batch", "cronjob.yaml")]:
            chart = Path(directory) / role
            templates = chart / "templates"
            templates.mkdir(parents=True)
            (chart / "Chart.yaml").write_text("apiVersion: v2\nname: bootstrap-fixture\nversion: 2.0.0\nappVersion: 2.0.0\n")
            template_root = framework / "codegen/axiz-codegen-templates/src/main/resources/axiz/templates/service"
            manifest = yaml.load((template_root / "manifest.yaml").read_text(), Loader=UniqueKeys)
            active = {"archetype.deployment": role == "server", "archetype.cronjob": role == "batch"}
            for item in manifest["files"]:
                template = item["template"]
                if not template.startswith("static/helm/") or template.endswith("/helmignore"):
                    continue
                if "when" in item and not active[item["when"]]:
                    continue
                shutil.copyfile(template_root / template, templates / Path(template).name)
            # Fixture de valores concretos; las plantillas de recursos y la selección de roles provienen del manifiesto del SDK.
            values = yaml.load((demo / f"services/{service}/deploy/helm/{service}/values.yaml").read_text(), Loader=UniqueKeys)
            values.setdefault("monitoring", {"prometheusRules": {"enabled": False}})
            (chart / "values.yaml").write_text(yaml.safe_dump(values))
            result = verify(chart)
            result["chart"] = "canonical-framework-" + role
            results.append(result)
        results.extend(verify(chart.parent) for chart in charts)
    common = yaml.load((demo / "deploy/platform/axiz-common.yaml").read_text(), Loader=UniqueKeys)
    assert common["kind"] == "ConfigMap" and common["immutable"] is True
    assert common["metadata"]["name"] == "axiz-common-2-0-0"
    print(json.dumps({"charts": len(results), "cases": sum(result["cases"] for result in results), "results": results}, indent=2))

if __name__ == "__main__":
    main()

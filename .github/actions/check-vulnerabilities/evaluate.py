#!/usr/bin/env python3
"""Tratamiento que falla de forma cerrada de la evidencia completa del escáner y del historial protegido de vulnerabilidades.

Quien invoca aporta una política e historial protegidos y descriptores de artefactos esperados de confianza.
Las excepciones de la rama head nunca se usan para autorizar ese mismo build. Sin red ni commits automáticos.
"""
import argparse
import hashlib
import json
import re
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

UTC = timezone.utc
SEVERITIES = {"UNKNOWN", "LOW", "MEDIUM", "HIGH", "CRITICAL"}
STAMP = re.compile(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z")
SCAN_STAMP = re.compile(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})")
DIGEST = re.compile(r"sha256:[0-9a-f]{64}")
FINDING_KEYS = {
    "id", "vulnerabilityId", "aliases", "component", "affectedVersions", "artifactScopes",
    "firstSeen", "owner", "acknowledgedAt", "triageDue", "dueAt", "nextMinorAt",
    "applicability", "contextualRisk", "knownExploited", "treatment", "status", "evidence",
    "exceptions",
}
EXCEPTION_KEYS = {
    "id", "reason", "mitigation", "evidence", "approvedBy", "approval", "approvedAt",
    "expiresAt", "affectedVersions", "artifactScopes",
}


def unique(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"Duplicate JSON key: {key}")
        result[key] = value
    return result


def read_json(path):
    path = Path(path)
    if path.is_symlink() or not path.is_file() or path.stat().st_size > 64 * 1024 * 1024:
        raise ValueError("Missing, redirected or oversized JSON evidence")
    return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique,
                      parse_constant=lambda _: (_ for _ in ()).throw(ValueError("Nonfinite JSON")))


def fields(value, required, optional=()):
    if not isinstance(value, dict) or set(value) - set(required) - set(optional) or set(required) - set(value):
        raise ValueError("Missing or unknown policy/evidence fields")


def stamp(value):
    if not isinstance(value, str) or not STAMP.fullmatch(value):
        raise ValueError("Timestamps must be explicit UTC ISO instants")
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def scan_stamp(value):
    """Trivy emite RFC3339 con el desfase horario del runner; los instantes de la política siguen exigiendo UTC."""
    if not isinstance(value, str) or not SCAN_STAMP.fullmatch(value):
        raise ValueError("Scanner timestamps must contain an explicit RFC3339 offset")
    if value.endswith("-00:00"):
        raise ValueError("An unknown scanner timezone is not evidence of an instant")
    if not value.endswith("Z") and (int(value[-5:-3]) > 23 or int(value[-2:]) > 59):
        raise ValueError("Invalid scanner timezone offset")
    return datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone(UTC)


def strings(value, allow_empty=False):
    if (not isinstance(value, list) or (not value and not allow_empty)
            or any(not isinstance(v, str) or not v.strip() or len(v) > 2048 or "*" in v for v in value)
            or len(set(value)) != len(value)):
        raise ValueError("Unique explicit nonempty values are required; wildcards are prohibited")
    return value


def text(value):
    strings([value])
    return value


def component(value):
    text(value)
    if not value.startswith("pkg:") or len(value) > 2048:
        raise ValueError("A package URL is required for component identity")
        # Se conservan el ecosistema y el namespace del paquete; los calificadores de versión o de distribución no pueden reiniciar la antigüedad del riesgo.
    coordinate = value.split("?", 1)[0].split("#", 1)[0]
    return coordinate.rsplit("@", 1)[0] if coordinate.rfind("@") > coordinate.rfind("/") else coordinate


def identifiers(finding):
    return {finding["vulnerabilityId"].upper(), *(v.upper() for v in finding["aliases"])}


def business_due(start, count, calendar):
    current = start
    holidays = set(calendar["holidays"])
    while count:
        current += timedelta(days=1)
        if current.isoweekday() not in calendar["weekend"] and current.date().isoformat() not in holidays:
            count -= 1
    return current


def validate_policy(policy, now):
    fields(policy, {"schema", "calendar", "authorizedOwners", "findings"})
    if policy["schema"] != 2 or not isinstance(policy["findings"], list):
        raise ValueError("Unsupported vulnerability policy schema")
    fields(policy["calendar"], {"weekend", "holidays"})
    calendar = policy["calendar"]
    if calendar["weekend"] != [6, 7]:
        raise ValueError("The declared UTC business calendar uses Saturday/Sunday weekends")
    strings(calendar["holidays"], allow_empty=True)
    for day in calendar["holidays"]:
        if date.fromisoformat(day).isoformat() != day:
            raise ValueError("Invalid calendar holiday")
    strings(policy["authorizedOwners"])
    seen = set()
    for finding in policy["findings"]:
        fields(finding, FINDING_KEYS)
        for key in ("id", "vulnerabilityId", "owner"):
            text(finding[key])
        if finding["id"] in seen:
            raise ValueError("Duplicate finding id")
        seen.add(finding["id"])
        strings(finding["aliases"], allow_empty=True)
        strings(finding["affectedVersions"])
        strings(finding["artifactScopes"])
        strings(finding["evidence"])
        if finding["component"] != component(finding["component"]):
            raise ValueError("Inventory component must be a version-independent package URL")
        first, triage, due = (stamp(finding[key]) for key in ("firstSeen", "triageDue", "dueAt"))
        if (first > now or not first <= triage <= business_due(first, 10, calendar)
                or not first < due <= first + timedelta(days=90)):
            raise ValueError("Finding dates exceed the acknowledgement/triage/remediation policy")
        if finding["nextMinorAt"] is not None and due > stamp(finding["nextMinorAt"]):
            raise ValueError("Treatment cannot outlive the next planned minor release")
        ack = finding["acknowledgedAt"]
        if ack is not None and not first <= stamp(ack) <= min(now, business_due(first, 5, calendar)):
            raise ValueError("Invalid or late acknowledgement")
        if finding["applicability"] not in {"pending", "applicable", "not-applicable"}:
            raise ValueError("Invalid applicability")
        if finding["contextualRisk"] not in {"pending", "low", "medium", "high", "critical", "urgent"}:
            raise ValueError("Invalid contextual risk")
        if type(finding["knownExploited"]) is not bool or finding["status"] not in {"open", "resolved"}:
            raise ValueError("Invalid exploitation/status field")
        text(finding["treatment"])
        if not isinstance(finding["exceptions"], list):
            raise ValueError("Exceptions must be explicit records")
        exception_ids = set()
        for exception in finding["exceptions"]:
            fields(exception, EXCEPTION_KEYS)
            for key in ("id", "reason", "mitigation", "approvedBy", "approval"):
                text(exception[key])
            if exception["id"] in exception_ids:
                raise ValueError("Duplicate exception id")
            exception_ids.add(exception["id"])
            strings(exception["evidence"])
            if exception["approvedBy"] not in policy["authorizedOwners"]:
                raise ValueError("Unauthorized exception owner")
            for key in ("affectedVersions", "artifactScopes"):
                if not set(strings(exception[key])) <= set(finding[key]):
                    raise ValueError("Exception scope exceeds the finding")
            if not first <= stamp(exception["approvedAt"]) <= now:
                raise ValueError("Invalid exception approval instant")
            if not stamp(exception["approvedAt"]) < stamp(exception["expiresAt"]) <= first + timedelta(days=90):
                raise ValueError("Exception expiry exceeds the hard treatment limit")


def related(rows, ids, package):
    """Cierre transitivo de alias a lo largo del historial protegido; los ids de fila y las versiones no son identidad."""
    matches = []
    remaining = [r for r in rows if component(r["component"]) == package]
    known = set(ids)
    while True:
        found = [r for r in remaining if identifiers(r) & known]
        if not found:
            return matches
        for row in found:
            known |= identifiers(row)
            remaining.remove(row)
            matches.append(row)


def preserve_history(current, history):
    """Valida por separado las actualizaciones de política propuestas; no conceden autorización de entrega."""
    for old in history:
        matches = related(current, identifiers(old), component(old["component"]))
        if not matches:
            raise ValueError("Protected finding history cannot be deleted or lose its alias identity")
        if min(stamp(r["firstSeen"]) for r in matches) > stamp(old["firstSeen"]):
            raise ValueError("A renamed/moved finding cannot reset first detection")


def scan_findings(report, descriptor, now):
    if (not isinstance(report, dict) or report.get("SchemaVersion") != 2
            or report.get("ArtifactName") != descriptor["artifact"]
            or report.get("ArtifactType") not in {"filesystem", "cyclonedx", "container_image"}
            or not isinstance(report.get("Results"), list)):
        raise ValueError("Missing/incomplete Trivy report or artifact mismatch")
    expected_type = {"source": "filesystem", "sbom": "cyclonedx", "image": "container_image"}[descriptor["kind"]]
    if report["ArtifactType"] != expected_type:
        raise ValueError("Scanner artifact type mismatch")
    created = scan_stamp(report.get("CreatedAt"))
    if created > now + timedelta(minutes=5) or now - created > timedelta(hours=24):
        raise ValueError("Stale or future scan evidence")
    if descriptor["kind"] == "image":
        if not descriptor["artifact"].endswith("@" + descriptor["digest"]):
            raise ValueError("Image must be scanned by its exact digest")
        config = report.get("Metadata", {}).get("ImageConfig", {})
        platform = config.get("os", "") + "/" + config.get("architecture", "")
        if platform != descriptor["platform"]:
            raise ValueError("Image platform mismatch or missing platform evidence")
    for target in report["Results"]:
        if not isinstance(target, dict) or not target.get("Target"):
            raise ValueError("Malformed scanner target")
        for vulnerability in target.get("Vulnerabilities") or []:
            if not isinstance(vulnerability, dict) or vulnerability.get("Severity") not in SEVERITIES:
                raise ValueError("Malformed vulnerability severity")
            identifier = text(vulnerability.get("VulnerabilityID"))
            package = vulnerability.get("PkgIdentifier", {}).get("PURL")
            if package is None:
                                # Ningún fallback ambiguo por nombre de paquete puede autorizar una excepción.
                package = "pkg:unresolved/" + text(vulnerability.get("PkgName"))
            aliases = vulnerability.get("VendorIDs") or []
            strings(aliases, allow_empty=True)
            yield {"vulnerabilityId": identifier, "aliases": aliases, "component": component(package),
                   "version": text(vulnerability.get("InstalledVersion")), "scope": descriptor["scope"],
                   "severity": vulnerability["Severity"],
                   "firstObserved": created.isoformat().replace("+00:00", "Z")}


def decide(vulnerability, policy, history, now, minor_release=False):
    # MEDIUM, HIGH y CRITICAL bloquean siempre, también en releases. Los registros de riesgo
    # existentes se validan y conservan, pero no autorizan estas severidades.
    severity = vulnerability["severity"]
    if severity in {"MEDIUM", "HIGH", "CRITICAL"}:
        return "BLOCK", "medium-high-critical"
    if severity != "LOW":
        return "INCOMPLETE", "unclassified-severity"
    matches = related(policy["findings"], identifiers(vulnerability), vulnerability["component"])
    if any(r["contextualRisk"] in {"urgent", "high", "critical"}
           or (r["knownExploited"] and r["applicability"] != "not-applicable") for r in matches):
        return "BLOCK", "urgent-context"
    return "PASS", "low-inventory"


def evaluate(policy, history, expected, root, now, minor_release=False):
    validate_policy(policy, now)
    fields(expected, {"schema", "reports"})
    if expected["schema"] != 1 or not isinstance(expected["reports"], list) or not expected["reports"]:
        raise ValueError("At least one expected report is required")
    outcomes, seen = [], set()
    for descriptor in expected["reports"]:
        fields(descriptor, {"path", "sha256", "scope", "artifact", "kind", "digest", "platform",
                            "scanner", "scannerVersion", "exitCode", "severities"})
        if (descriptor["scanner"] != "trivy" or descriptor["scannerVersion"] != "0.76.2"
                or type(descriptor["exitCode"]) is not int or descriptor["exitCode"] != 0
                or set(descriptor["severities"]) != SEVERITIES
                or not DIGEST.fullmatch(descriptor["digest"])
                or descriptor["kind"] not in {"source", "sbom", "image"}):
            raise ValueError("Failed, filtered or unsupported scan evidence")
        identity = (descriptor["scope"], descriptor["kind"], descriptor["platform"])
        if identity in seen:
            raise ValueError("Duplicate expected report scope")
        seen.add(identity)
        path = Path(root) / descriptor["path"]
        if Path(descriptor["path"]).is_absolute() or not path.resolve().is_relative_to(Path(root).resolve()):
            raise ValueError("Report path escapes its evidence root")
        report = read_json(path)
        if hashlib.sha256(path.read_bytes()).hexdigest() != descriptor["sha256"]:
            raise ValueError("Report digest mismatch")
        for vulnerability in scan_findings(report, descriptor, now):
            decision, reason = decide(vulnerability, policy, history, now, minor_release)
            earlier = related(history + policy["findings"], identifiers(vulnerability), vulnerability["component"])
            first = min([stamp(vulnerability["firstObserved"]), *(stamp(r["firstSeen"]) for r in earlier)])
            outcomes.append({**vulnerability, "firstSeen": first.isoformat().replace("+00:00", "Z"),
                             "decision": decision, "reason": reason})
    verdict = "PASS" if all(r["decision"] == "PASS" for r in outcomes) else "BLOCK"
    return {"schema": 2, "decision": verdict, "evaluatedAt": now.isoformat(),
            "reportCount": len(seen), "findings": outcomes,
            "proposals": [r for r in outcomes if r["decision"] == "INCOMPLETE"]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--policy", required=True)
    parser.add_argument("--history", required=True, help="Protected historical finding rows")
    parser.add_argument("--expected", required=True)
    parser.add_argument("--reports", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--minor-release", action="store_true")
    args = parser.parse_args()
        # Sin opción --now controlada por quien invoca: los plazos usan el reloj del runner.
    now = datetime.now(UTC)
    try:
        historical = read_json(args.history)
        if not isinstance(historical, list):
            raise ValueError("Protected history must be a list")
        result = evaluate(read_json(args.policy), historical, read_json(args.expected),
                          args.reports, now, args.minor_release)
    except (ValueError, OSError, KeyError, TypeError) as failure:
        result = {"schema": 2, "decision": "ERROR", "reason": str(failure)}
    Path(args.output).write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    return 0 if result["decision"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())

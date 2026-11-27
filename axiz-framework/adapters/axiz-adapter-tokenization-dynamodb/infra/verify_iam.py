#!/usr/bin/env python3
"""Ejercita las políticas del vault renderizadas con el simulador real de AWS IAM; sin mutar recursos."""
import argparse
import json
import re
import subprocess
from pathlib import Path


def aws(*args):
    response = subprocess.run(["aws", *args, "--output", "json", "--no-cli-pager",
                               "--cli-connect-timeout", "10", "--cli-read-timeout", "30"],
                              capture_output=True, text=True, check=True, timeout=60)
    return json.loads(response.stdout)


def render(value, substitutions):
    if isinstance(value, dict):
        if set(value) == {"Ref"}:
            return substitutions[value["Ref"]]
        if value == {"Fn::GetAtt": ["VaultTable", "Arn"]}:
            return substitutions["TableArn"]
        return {k: render(v, substitutions) for k, v in value.items()}
    return [render(v, substitutions) for v in value] if isinstance(value, list) else value


def verify(args):
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,32}", args.envelope_route):
        raise ValueError("Invalid wrapping route identity")
    if args.storage_key_arn == args.envelope_key_arn:
        raise ValueError("Storage and envelope keys must be distinct")
    envelope_context = "axiz-keyring-v1/" + args.envelope_route + "/cHVycG9zZTp0b2tlbi12YXVsdA"
    identity = aws("sts", "get-caller-identity", "--region", args.region)
    account = identity["Account"]
    partition = identity["Arn"].split(":")[1]
    if not re.fullmatch(r"[0-9]{12}", account) or not re.fullmatch(r"[A-Za-z0-9_.-]{3,255}", args.table):
        raise ValueError("Invalid account or table identity")
    table = f"arn:{partition}:dynamodb:{args.region}:{account}:table/{args.table}"
    document = json.loads(Path(__file__).with_name("vault.cloudformation.json").read_text())
    policies = {name: render(resource["Properties"]["PolicyDocument"], {
        "TableArn": table, "StorageKeyArn": args.storage_key_arn,
        "EnvelopeKeyArns": [args.envelope_key_arn], "EnvelopeKeyContexts": [envelope_context]})
        for name, resource in document["Resources"].items() if resource["Type"] == "AWS::IAM::ManagedPolicy"}
    cases = []
    for name in ("OperationalPolicy", "MaintenancePolicy", "RetentionPolicy"):
        for action, key, transaction, allowed in (
            ("GetItem", "C#example", False, True),
            ("GetItem", "T#example", True, True),
            ("PutItem", "T#example", True, name != "RetentionPolicy"),
            ("PutItem", "T#example", False, False),
            ("PutItem", "C#example", True, name == "MaintenancePolicy"),
            ("PutItem", "R#example", True, name == "MaintenancePolicy"),
            ("DeleteItem", "T#example", True, True),
            ("DeleteItem", "T#example", False, False),
            ("DeleteItem", "C#example", True, False),
            ("DeleteItem", "I#example", True, False),
            ("DeleteItem", "R#example", True, False),
            ("Scan", None, False, name != "OperationalPolicy"),
            ("UpdateTable", None, False, False),
            ("UpdateTimeToLive", None, False, False),
            ("DeleteTable", None, False, False),
            ("UpdateItem", "T#example", True, False),
        ):
            contexts = []
            if key:
                contexts.append({"ContextKeyName": "dynamodb:LeadingKeys", "ContextKeyValues": [key],
                                 "ContextKeyType": "stringList"})
            if transaction:
                contexts.append({"ContextKeyName": "dynamodb:EnclosingOperation", "ContextKeyValues": [
                    "TransactGetItems" if action == "GetItem" else "TransactWriteItems"], "ContextKeyType": "string"})
            request = {"PolicyInputList": [json.dumps(policies[name])], "ActionNames": ["dynamodb:" + action],
                       "ResourceArns": [table], "ContextEntries": contexts}
            reply = aws("iam", "simulate-custom-policy", "--cli-input-json", json.dumps(request))
            results = reply.get("EvaluationResults", [])
            if len(results) != 1 or "EvalDecision" not in results[0]:
                raise ValueError("Incomplete IAM simulation response")
            actual = results[0]["EvalDecision"]
            if actual not in {"allowed", "implicitDeny", "explicitDeny"}:
                raise ValueError("Unknown IAM simulation decision")
            cases.append({"policy": name, "action": action, "key": key, "transaction": transaction,
                          "expectedAllowed": allowed, "decision": actual,
                          "passed": (actual == "allowed") == allowed})
    for action, key, context, allowed in (
        ("GenerateDataKey", args.envelope_key_arn, envelope_context, True),
        ("Decrypt", args.envelope_key_arn, envelope_context, True),
        ("Decrypt", args.storage_key_arn, envelope_context, False),
        ("Decrypt", args.envelope_key_arn, None, False),
        ("Decrypt", args.envelope_key_arn, "purpose:token-vault", False),
        ("Decrypt", args.envelope_key_arn, envelope_context + "-other", False),
        ("GenerateDataKey", args.envelope_key_arn, "purpose:outbox", False),
        ("ScheduleKeyDeletion", args.envelope_key_arn, envelope_context, False),
        ("PutKeyPolicy", args.envelope_key_arn, envelope_context, False),
    ):
        contexts = [] if context is None else [{"ContextKeyName": "kms:EncryptionContext:axiz-context",
            "ContextKeyValues": [context], "ContextKeyType": "string"}]
        request = {"PolicyInputList": [json.dumps(policies["EnvelopeUsePolicy"])],
                   "ActionNames": ["kms:" + action], "ResourceArns": [key], "ContextEntries": contexts}
        reply = aws("iam", "simulate-custom-policy", "--cli-input-json", json.dumps(request))
        results = reply.get("EvaluationResults", [])
        if len(results) != 1 or results[0].get("EvalDecision") not in {"allowed", "implicitDeny", "explicitDeny"}:
            raise ValueError("Incomplete KMS simulation response")
        actual = results[0]["EvalDecision"]
        cases.append({"policy": "EnvelopeUsePolicy", "action": action, "resource": key,
                      "context": context, "expectedAllowed": allowed, "decision": actual,
                      "passed": (actual == "allowed") == allowed})
    return {"account": account, "tableArn": table, "simulationOnly": True,
            "passed": all(case["passed"] for case in cases), "cases": cases}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("region", "table", "storage-key-arn", "envelope-key-arn", "envelope-route", "output"):
        parser.add_argument("--" + name, required=True)
    args = parser.parse_args()
    try:
        result = verify(args)
    except (OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        result = {"passed": False, "error": type(error).__name__, "message": "AWS simulation did not complete"}
    Path(args.output).write_text(json.dumps(result, indent=2) + "\n")
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())

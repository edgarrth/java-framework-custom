"""Read internal call contracts from the reviewed release, shared by policy and pipeline gates."""
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys

import yaml

PREFIX = "edgarrth/java-framework-custom/"
ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / '.github/actions'))
from workflow_release import TAG, TAG_PATTERN


def workflow_call(document):
    return document.get("on", document.get(True, {})).get("workflow_call", {})


def contract_fields(call, contract, reusable):
    errors = []
    categories = [("inputs", call.get("with", {}))]
    if reusable:
        categories.append(("secrets", call.get("secrets", {})))
    for category, supplied in categories:
        declared = contract.get(category, {})
        if not isinstance(supplied, dict):
            errors.append(f"{category} must be explicit")
            continue
        for name in sorted(set(supplied) - set(declared)):
            errors.append(f"unknown {category} entry {name}")
        for name, definition in declared.items():
            if definition.get("required") and name not in supplied and "default" not in definition:
                errors.append(f"required {category} entry {name} is missing")
    return errors


def local_contract(reference, root=ROOT, *, snapshot=True):
    target, separator, revision = reference.rpartition("@")
    if not separator:
        raise ValueError("internal call requires an immutable reference")
    relative = PurePosixPath(target.removeprefix(PREFIX))
    if relative.is_absolute() or any(p in {".", ".."} for p in relative.parts):
        raise ValueError("invalid internal contract path")
    reusable = relative.parts[:2] == (".github", "workflows")
    action = relative.parts[:2] == (".github", "actions")
    if not (reusable or action):
        raise ValueError("unknown internal contract location")
    candidates = [relative] if reusable else [relative / "action.yaml", relative / "action.yml"]
    root = Path(root)
    current = "axiz-workflows-v" + (root / ".github/version.txt").read_text().strip()
    if not TAG.fullmatch(current):
        raise ValueError("trusted contract version must be an exact v2 release")
    if revision == current and snapshot:
        texts = []
        for candidate in candidates:
            path = root / candidate
            if not path.resolve().is_relative_to(root.resolve()):
                raise ValueError("internal contract escapes the reviewed root")
            if path.is_file():
                texts.append(path.read_text())
    else:
        if re.fullmatch(r"[0-9a-f]{40}", revision):
            object_name = revision
        elif TAG.fullmatch(revision):
            object_name = "refs/tags/" + revision
        else:
            raise ValueError("internal reference requires an exact v2 version or commit SHA")
        # An action archive can sit inside another Git checkout. Never borrow that history.
        top = subprocess.run(["git", "-C", str(root), "rev-parse", "--show-toplevel"],
                             capture_output=True, text=True)
        if top.returncode or Path(top.stdout.strip()).resolve() != root.resolve():
            raise ValueError("trusted contract history unavailable for " + revision)
        exists = subprocess.run(["git", "-C", str(root), "rev-parse", "--verify",
                                 object_name + "^{commit}"], capture_output=True, text=True)
        if exists.returncode:
            raise ValueError("trusted contract revision unavailable: " + object_name)
        texts = []
        for candidate in candidates:
            result = subprocess.run(["git", "-C", str(root), "show", "--no-ext-diff",
                                     "--no-textconv", object_name + ":" + str(candidate)],
                                    capture_output=True, text=True)
            if result.returncode == 0:
                texts.append(result.stdout)
    if len(texts) != 1:
        raise ValueError("missing or ambiguous internal contract metadata: " + str(relative))
    metadata = yaml.safe_load(texts[0])
    contract = workflow_call(metadata) if reusable else metadata
    if not isinstance(contract, dict) or reusable and not contract:
        raise ValueError("invalid internal contract metadata: " + str(relative))
    return contract, reusable


def validate_call(call, root=ROOT, *, snapshot=True):
    reference = call.get("uses", "")
    if not reference.startswith(PREFIX):
        return []
    try:
        contract, reusable = local_contract(reference, root, snapshot=snapshot)
        return contract_fields(call, contract, reusable)
    except (OSError, ValueError, TypeError, AttributeError, yaml.YAMLError) as error:
        return [str(error)]

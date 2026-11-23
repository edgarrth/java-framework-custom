"""Selecciona las fuentes de workflow y sigue las llamadas locales sin salir del checkout revisado."""
from collections import deque
from pathlib import Path
import re

import yaml

IGNORED = {"target", ".git", "node_modules", ".venv"}


def read_document(path):
    text = path.read_text()
    text = re.sub(r"^\{\{=<% %>=\}\}\s*", "", text)
    text = re.sub(r"^\s*<%[#/^][^\n]+%>\s*$", "", text, flags=re.M)
    return yaml.safe_load(text)


def workflow_files(root):
    for path in root.rglob("*"):
        # La evidencia de auditoría local contiene copias generadas; las llamadas locales ejecutables se siguen verificando.
        if path.relative_to(root).parts[0] == "_validacion-framework-axiz":
            continue
        if not path.is_file() or any(p in IGNORED for p in path.parts):
            continue
        if path.name.endswith((".yaml", ".yml", ".yaml.mustache", ".yml.mustache")):
            if ".github/workflows" in str(path) or "templates" in path.parts or path.name in {"action.yaml", "action.yml"}:
                yield path


def local_calls(document):
    for job in document.get("jobs", {}).values():
        yield job.get("uses", ""), True
        for step in job.get("steps", []):
            yield step.get("uses", ""), False
    for step in document.get("runs", {}).get("steps", []):
        yield step.get("uses", ""), False


def executable_files(root):
    """Entrypoints de workflow y las acciones o workflows locales que invocan de forma transitiva."""
    root = root.resolve()
    seeds, seed_errors = [], []
    for path in (root / ".github/workflows").glob("*"):
        if path.suffix not in {".yaml", ".yml"} or not path.is_file():
            continue
        if not path.resolve().is_relative_to(root):
            seed_errors.append(str(path.relative_to(root)) + ": workflow source escapes the checkout")
            continue
        try:
            document = read_document(path)
            trigger = document.get("on", document.get(True))
            if isinstance(trigger, str):
                events = {trigger}
            elif isinstance(trigger, (dict, list)) and trigger:
                events = set(trigger)
            else:
                raise ValueError("workflow must declare trigger events")
            if events - {"workflow_call"}:
                seeds.append(path)
        except (OSError, ValueError, AttributeError, TypeError, yaml.YAMLError) as error:
            seed_errors.append(str(path.relative_to(root)) + ": " + str(error))
    queue = deque(seeds)
    visited, graph, errors = set(), {}, seed_errors
    while queue:
        original = queue.popleft()
        path = original.resolve()
        if not path.is_relative_to(root):
            errors.append(str(original.relative_to(root)) + ": workflow source escapes the checkout")
            continue
        if path in visited:
            continue
        visited.add(path)
        graph.setdefault(path, set())
        try:
            document = read_document(path)
            if not isinstance(document, dict):
                raise ValueError("workflow/action must be a YAML mapping")
            for reference, reusable in local_calls(document):
                if not reference.startswith("./"):
                    continue
                target = (root / reference).resolve()
                if not target.is_relative_to(root):
                    raise ValueError("local call escapes the checkout: " + reference)
                if reusable:
                    if target.parent != root / ".github/workflows" or target.suffix not in {".yaml", ".yml"} or not target.is_file():
                        raise ValueError("local reusable workflow is missing or outside .github/workflows: " + reference)
                    metadata = target
                else:
                    candidates = [target / "action.yaml", target / "action.yml"]
                    if any(not p.resolve().is_relative_to(root) for p in candidates):
                        raise ValueError("local action metadata escapes the checkout: " + reference)
                    present = [p.resolve() for p in candidates if p.is_file()]
                    if len(present) != 1:
                        raise ValueError("missing or ambiguous local action metadata: " + reference)
                    metadata = present[0]
                    if not isinstance(read_document(metadata).get("runs"), dict):
                        raise ValueError("local action metadata requires runs: " + reference)
                graph[path].add(metadata)
                queue.append(metadata)
        except (OSError, ValueError, AttributeError, TypeError, yaml.YAMLError) as error:
            errors.append(str(path.relative_to(root)) + ": " + str(error))
    # El algoritmo de Kahn detecta ciclos sin recursión y sin seguir repetidamente una llamada compuesta.
    indegree = {path: 0 for path in visited}
    for targets in graph.values():
        for target in targets:
            indegree[target] = indegree.get(target, 0) + 1
    ready = deque(path for path, count in indegree.items() if count == 0)
    processed = 0
    while ready:
        path = ready.popleft()
        processed += 1
        for target in graph.get(path, ()):
            indegree[target] -= 1
            if indegree[target] == 0:
                ready.append(target)
    if processed != len(indegree):
        errors.append("cyclic local workflow/action calls are forbidden")
    return sorted(visited), errors

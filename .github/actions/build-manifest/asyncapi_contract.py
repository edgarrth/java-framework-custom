"""Compatibilidad conservadora y sin conexión de AsyncAPI 3 para CloudEvents versionados."""
import re

import yaml

MAX_SIZE = 2 * 1024 * 1024
DOCUMENTATION = {"title", "description", "summary", "examples", "example", "$comment", "externalDocs", "tags"}


class ContractLoader(yaml.SafeLoader):
    def construct_mapping(self, node, deep=False):
        self.flatten_mapping(node)
        result = {}
        for key_node, value_node in node.value:
            key = self.construct_object(key_node, deep=deep)
            if not isinstance(key, str) or key in result:
                raise ValueError("Contract keys must be unique strings")
            result[key] = self.construct_object(value_node, deep=deep)
        return result


# Se conservan los ejemplos que contienen fechas, igual que el lector SafeYaml del framework.
ContractLoader.yaml_implicit_resolvers = {
    key: [(tag, regex) for tag, regex in rules if tag != "tag:yaml.org,2002:timestamp"]
    for key, rules in yaml.SafeLoader.yaml_implicit_resolvers.items()
}


def load(text):
    if len(text) > MAX_SIZE:
        raise ValueError("Contract exceeds size limit")
    try:
        if sum(isinstance(token, yaml.tokens.AliasToken) for token in yaml.scan(text)) > 20:
            raise ValueError("Contract exceeds YAML alias limit")
        return yaml.load(text, Loader=ContractLoader)
    except (yaml.YAMLError, RecursionError) as error:
        raise ValueError("Invalid contract YAML") from error


def mapping(value):
    if not isinstance(value, dict) or any(not isinstance(key, str) for key in value):
        raise ValueError("AsyncAPI object must be a string-keyed mapping")
    return value


def reference(root, ref):
    if not isinstance(ref, str) or not ref.startswith("#/"):
        raise ValueError("Only local AsyncAPI references are supported")
    value = root
    for key in ref[2:].split("/"):
        key = key.replace("~1", "/").replace("~0", "~")
        if key not in mapping(value):
            raise ValueError("Unresolved AsyncAPI reference: " + ref)
        value = value[key]
    return value


def resolve(root, value):
    visited = set()
    node = mapping(value)
    while "$ref" in node:
        ref = node["$ref"]
        if not isinstance(ref, str) or ref in visited or len(visited) >= 64:
            raise ValueError("Cyclic or excessive AsyncAPI reference")
        if len(node) != 1:
            raise ValueError("Flatten AsyncAPI reference siblings before generation")
        visited.add(ref)
        node = mapping(reference(root, ref))
    return node


def expand(root, node, visited=None, budget=None, depth=0):
    visited = set() if visited is None else visited
    budget = [0] if budget is None else budget
    budget[0] += 1
    if budget[0] > 100_000 or depth > 64:
        raise ValueError("AsyncAPI expansion limit exceeded")
    if isinstance(node, dict):
        if "$ref" in node:
            ref = node["$ref"]
            if not isinstance(ref, str) or ref in visited or len(node) != 1:
                raise ValueError("Recursive AsyncAPI reference or reference siblings")
            return expand(root, reference(root, ref), visited | {ref}, budget, depth + 1)
        return {key: expand(root, item, visited, budget, depth + 1) for key, item in mapping(node).items()}
    if isinstance(node, list):
        return [expand(root, item, visited, budget, depth + 1) for item in node]
    return node


def events(root):
    mapping(root)
    if not re.fullmatch(r"3\.\d+\.\d+", str(root.get("asyncapi"))):
        raise ValueError("Only AsyncAPI 3.x contracts are supported")
    mapping(root.get("info"))
    result = {"send": {}, "receive": {}}
    definitions = {}
    expansion_budget = [0]
    for raw_operation in mapping(root.get("operations", {})).values():
        operation = resolve(root, raw_operation)
        action = operation.get("action")
        if action not in result or "traits" in operation:
            raise ValueError("AsyncAPI operation must declare send/receive and flatten traits")
        channel_ref = mapping(operation.get("channel")).get("$ref", "")
        if not isinstance(channel_ref, str) or not channel_ref.startswith("#/channels/") or "/" in channel_ref[11:]:
            raise ValueError("Operation must reference a local channel")
        channel = resolve(root, operation["channel"])
        messages = mapping(channel.get("messages"))
        selected = operation.get("messages")
        if selected is None:
            selected = list(messages.values())
        else:
            if not isinstance(selected, list):
                raise ValueError("Operation messages must be a nonempty list")
            prefix = channel_ref + "/messages/"
            for item in selected:
                ref = mapping(item).get("$ref", "")
                if not isinstance(ref, str) or not ref.startswith(prefix) or "/" in ref[len(prefix):]:
                    raise ValueError("Operation message must belong to its channel")
        if not selected:
            raise ValueError("Channel has no selected messages")
        for item in selected:
            message = expand(root, resolve(root, item), budget=expansion_budget)
            name = message.get("name")
            if not isinstance(name, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]*\.v[1-9][0-9]*", name):
                raise ValueError("Message name must end in a CloudEvents.vN type")
            if "traits" in message or mapping(message.get("payload")).get("type") != "object":
                raise ValueError("Flatten message traits and declare an explicit JSON object payload")
            message.setdefault("contentType", root.get("defaultContentType", "application/json"))
            if name in definitions and not equal(definitions[name], message):
                raise ValueError("Conflicting event definitions: " + name)
            definitions[name] = message
            result[action][name] = message
    return result


def equal(left, right, depth=0):
    """Los tipos escalares de JSON importan: Python no debe equiparar true con 1 en enums o valores por defecto."""
    if depth > 64:
        raise ValueError("Contract comparison nesting limit exceeded")
    if type(left) is not type(right):
        return False
    if isinstance(left, dict):
        return left.keys() == right.keys() and all(equal(left[key], right[key], depth + 1) for key in left)
    if isinstance(left, list):
        return len(left) == len(right) and all(equal(a, b, depth + 1) for a, b in zip(left, right))
    return left == right


def compare(previous, current):
    """Devuelve las ubicaciones incompatibles. Los tipos de evento nuevos y los campos opcionales son compatibles."""
    before, after = events(previous)["send"], events(current)["send"]
    failures = []

    def node(old, new, path, schema=False):
        if equal(old, new):
            return
        if not isinstance(old, dict) or not isinstance(new, dict):
            failures.append(path)
            return
        if not schema:
            for key in old.keys() | new.keys():
                if key not in DOCUMENTATION:
                    node(old.get(key), new.get(key), path + "/" + key, key in {"payload", "headers"})
            return
        old_properties, new_properties = old.get("properties", {}), new.get("properties", {})
        old_required, new_required = old.get("required", []), new.get("required", [])
        if set(old_required) != set(new_required):
            failures.append(path + "/required")
        for key in old_properties.keys() | new_properties.keys():
            location = path + "/properties/" + key
            if key not in new_properties or (key not in old_properties and key in new_required):
                failures.append(location)
            elif key in old_properties:
                node(old_properties[key], new_properties[key], location, True)
        for key in old.keys() | new.keys():
            if key in DOCUMENTATION | {"properties", "required"}:
                continue
            if key == "items":
                node(old.get(key), new.get(key), path + "/items", True)
            elif not equal(old.get(key), new.get(key)):
                failures.append(path + "/" + key)

    for name, message in before.items():
        if name not in after:
            failures.append(name + " (published type removed)")
        else:
            node(message, after[name], name)
    return sorted(failures)

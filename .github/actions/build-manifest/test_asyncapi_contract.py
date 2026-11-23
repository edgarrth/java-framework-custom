import copy
import subprocess
import tempfile
import unittest
from pathlib import Path

import asyncapi_contract as api
import contracts


def document():
    return {
        "asyncapi": "3.0.0", "info": {"title": "Orders", "version": "1.0.0"},
        "channels": {"orders": {"address": "orders", "messages": {"created": {"$ref": "#/components/messages/created"}}}},
        "operations": {
            "publish": {"action": "send", "channel": {"$ref": "#/channels/orders"}},
            "consume": {"action": "receive", "channel": {"$ref": "#/channels/orders"},
                        "messages": [{"$ref": "#/channels/orders/messages/created"}]},
        },
        "components": {"messages": {"created": {
            "name": "pe.axiz.order.created.v1", "payload": {"$ref": "#/components/schemas/Created"}}},
            "schemas": {"Created": {"type": "object", "required": ["id"], "properties": {
                "id": {"type": "string"}, "lines": {"type": "array", "items": {
                    "type": "object", "properties": {"amount": {"type": "number"}}}}}}}},
    }


class AsyncApiTest(unittest.TestCase):
    def test_only_selected_send_types_affect_compatibility(self):
        before = document()
        after = copy.deepcopy(before)
        after["components"]["schemas"]["Created"]["properties"]["note"] = {"type": "string"}
        self.assertEqual([], api.compare(before, after))
        self.assertEqual({"pe.axiz.order.created.v1"}, set(api.events(before)["receive"]))
        after["components"]["schemas"]["Created"]["properties"]["id"] = {"type": "integer"}
        self.assertTrue(api.compare(before, after))
        before["operations"].pop("publish")
        self.assertEqual([], api.compare(before, after))

    def test_required_removed_retyped_and_nested_fields_break(self):
        before = document()
        for mutate in (
            lambda schema: schema["required"].append("lines"),
            lambda schema: schema["properties"].pop("id"),
            lambda schema: schema["properties"]["id"].update(format="uuid"),
            lambda schema: schema["properties"]["lines"]["items"]["properties"]["amount"].update(type="string"),
            lambda schema: schema.update(additionalProperties=False),
        ):
            after = copy.deepcopy(before)
            mutate(after["components"]["schemas"]["Created"])
            self.assertTrue(api.compare(before, after))
        after = copy.deepcopy(before)
        after["operations"].pop("publish")
        self.assertTrue(api.compare(before, after))

    def test_version_two_must_coexist_with_version_one(self):
        before, after = document(), document()
        second = copy.deepcopy(after["components"]["messages"]["created"])
        second["name"] = "pe.axiz.order.created.v2"
        after["components"]["messages"]["createdV2"] = second
        after["channels"]["orders"]["messages"]["createdV2"] = {"$ref": "#/components/messages/createdV2"}
        self.assertEqual([], api.compare(before, after))
        after["operations"].pop("consume")
        after["channels"]["orders"]["messages"].pop("created")
        self.assertTrue(api.compare(before, after))

    def test_invalid_references_and_ambiguous_yaml_fail_closed(self):
        for ref in ("https://example.test/payload", "#/missing", "#/components/messages/created/payload"):
            value = document()
            value["components"]["messages"]["created"]["payload"] = {"$ref": ref}
            with self.assertRaises(ValueError):
                api.events(value)
        value = document()
        value["operations"]["consume"]["messages"] = [{"$ref": "#/components/messages/created"}]
        with self.assertRaisesRegex(ValueError, "belong"):
            api.events(value)
        with self.assertRaisesRegex(ValueError, "unique"):
            api.load("asyncapi: 3.0.0\nasyncapi: 3.1.0\n")
        with self.assertRaisesRegex(ValueError, "size"):
            api.load("a" * (api.MAX_SIZE + 1))
        self.assertEqual("2026-11-01", api.load("date: 2026-11-01")["date"])

    def test_git_baseline_catches_a_rewritten_source_and_snapshot(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            def git(*args):
                return subprocess.check_output(["git", "-C", str(root), *args], text=True).strip()
            git("init", "-q")
            git("config", "user.name", "Local contract test")
            git("config", "user.email", "local-test@example.invalid")
            path = root / "src/main/asyncapi/asyncapi.yaml"
            path.parent.mkdir(parents=True)
            text = __import__("yaml").safe_dump(document())
            path.write_text(text)
            git("add", ".")
            git("commit", "-qm", "Original published event")
            before = git("rev-parse", "HEAD")
            snapshot = root / ".axiz/asyncapi.snapshot.yaml"
            snapshot.parent.mkdir()
                        # Los dos documentos actuales coinciden, pero el esquema de transmisión rompe la v1 publicada.
            path.write_text(text.replace("type: string", "type: integer"))
            snapshot.write_text(path.read_text())
            found = contracts.contracts(root, ".")
            with self.assertRaisesRegex(ValueError, "Incompatible"):
                contracts.compatibility(root, ".", found, before)
            with self.assertRaisesRegex(ValueError, "removed"):
                contracts.compatibility(root, ".", {}, before)
            path.write_text(text)
            contracts.compatibility(root, ".", found, before)
            self.assertIsNone(contracts.baseline(root, ""))
            git("tag", "v1.0.0")
            git("add", ".")
            git("commit", "-qm", "Generation snapshot")
            self.assertEqual(before, contracts.baseline(root, ""))
            with self.assertRaisesRegex(ValueError, "immutable"):
                contracts.compatibility(root, ".", found, "HEAD")
            with self.assertRaises(subprocess.CalledProcessError):
                contracts.compatibility(root, ".", found, "f" * 40)

    def test_reference_expansion_budget_is_shared_by_all_operations(self):
        value = document()
        operation = {"action": "send", "channel": {"$ref": "#/channels/orders"}}
        value["operations"] = {"publish" + str(index): copy.deepcopy(operation) for index in range(10_000)}
        with self.assertRaisesRegex(ValueError, "expansion limit"):
            api.events(value)

    def test_boolean_enum_changes_are_not_hidden_by_python_numeric_equality(self):
        before, after = document(), document()
        before["components"]["schemas"]["Created"]["properties"]["id"]["enum"] = [1]
        after["components"]["schemas"]["Created"]["properties"]["id"]["enum"] = [True]
        self.assertTrue(api.compare(before, after))

    def test_git_baseline_refuses_a_symlink_in_the_old_tree(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            for args in (["init", "-q"], ["config", "user.name", "Local"], ["config", "user.email", "local@example.invalid"]):
                subprocess.run(["git", "-C", temp, *args], check=True)
            path = root / "src/main/asyncapi/asyncapi.yaml"
            path.parent.mkdir(parents=True)
            path.symlink_to("../../../../outside.yaml")
            subprocess.run(["git", "-C", temp, "add", "."], check=True)
            subprocess.run(["git", "-C", temp, "commit", "-qm", "Unsafe baseline"], check=True)
            sha = subprocess.check_output(["git", "-C", temp, "rev-parse", "HEAD"], text=True).strip()
            with self.assertRaisesRegex(ValueError, "regular Git blob"):
                contracts.compatibility(root, ".", {}, sha)


if __name__ == "__main__":
    unittest.main()

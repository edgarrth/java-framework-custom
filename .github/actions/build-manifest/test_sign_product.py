import copy
import unittest
from unittest.mock import patch
import base64

import manifest
import sign_product
from test_manifest import fixture


class SignProductTest(unittest.TestCase):
    def context(self, kind="maven-library"):
        workflow = "service-build" if kind == "service" else "native" if kind == "native-component" else "lib-build"
        public = {"version": "2.0.0", "source-sha": "a" * 40, "project-directory": "axiz-framework"}
        environment = {"GITHUB_SHA": "a" * 40, "GITHUB_REPOSITORY": "example/framework",
                       "GITHUB_RUN_ID": "123", "GITHUB_RUN_ATTEMPT": "2"}
        claims = {"sha": "a" * 40, "repository": "example/framework", "run_id": "123", "run_attempt": "2",
                  "job_workflow_ref": "edgarrth/java-framework-custom/.github/workflows/" + workflow + ".yaml@refs/tags/" + sign_product.TAG,
                  "job_workflow_sha": "b" * 40}
        return public, environment, claims

    def test_reconstructs_identity_and_parameters_without_a_consumer_checkout(self):
        public, environment, claims = self.context()
        data = sign_product.expected("maven-library", public, environment, claims, "b" * 40)
        self.assertEqual(2, data["build"]["run_attempt"])
        self.assertEqual("axiz-framework", data["parameters"]["project_directory"])
        self.assertEqual("b" * 40, data["build"]["workflow"]["sha"])
        sign_product.compare(copy.deepcopy(data), data)
        for key in ["version", "source", "build", "parameters"]:
            wrong = copy.deepcopy(data)
            wrong[key] = "uploaded value"
            with self.subTest(key=key), self.assertRaises(ValueError):
                sign_product.compare(wrong, data)

    def test_rejects_a_different_token_attempt_workflow_or_source(self):
        for key, value in [("run_id", "122"), ("run_attempt", "1"), ("job_workflow_sha", "c" * 40),
                           ("job_workflow_ref", "example/framework/.github/workflows/evil.yaml@refs/heads/main"),
                           ("sha", "c" * 40)]:
            public, environment, claims = self.context()
            claims[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                sign_product.expected("maven-library", public, environment, claims, "b" * 40)

    def test_worker_reports_cannot_substitute_for_producer_authority(self):
        data = fixture()
        data["gates"] = [gate for gate in data["gates"] if gate["name"] != "producer-authority"]
        manifest.validate(data, require_authority=False)
        with self.assertRaisesRegex(ValueError, "producer-authority"):
            manifest.validate(data)

    def test_native_components_are_bound_to_all_three_declared_targets(self):
        public, environment, claims = self.context("native-component")
        public.update(module="axiz-cli", binary="axiz")
        data = sign_product.expected("native-component", public, environment, claims, "b" * 40)
        self.assertEqual(["linux-x64", "macos-arm64", "windows-x64"], data["parameters"]["native"]["platforms"])

    def test_source_file_is_fetched_by_the_immutable_source_commit_and_rejects_links(self):
        public, environment, claims = self.context()
        data = sign_product.expected("maven-library", public, environment, claims, "b" * 40)
        response = {"type": "file", "encoding": "base64", "size": 6, "content": base64.b64encode(b"source").decode()}
        with patch.object(sign_product.prepare, "api", return_value=response) as api:
            self.assertEqual(b"source", sign_product.source_file(data, "axiz-framework/pom.xml"))
        self.assertIn("ref=" + "a" * 40, api.call_args.args[0])
        with patch.object(sign_product.prepare, "api", return_value=dict(response, type="symlink")), self.assertRaises(ValueError):
            sign_product.source_file(data, "axiz-framework/pom.xml")
        with self.assertRaises(ValueError):
            sign_product.source_file(data, "../script.py")

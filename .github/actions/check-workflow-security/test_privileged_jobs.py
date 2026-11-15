import copy
from pathlib import Path
import unittest
import yaml

from check_workflow_security import validate

ROOT = Path(__file__).resolve().parents[2]


class PrivilegedJobsTest(unittest.TestCase):
    def workflow(self, name="lib-build"):
        return yaml.safe_load((ROOT / "workflows" / (name + ".yaml")).read_text())

    def test_reviewed_roles_and_native_matrix_have_no_worker_oidc(self):
        for name in ["lib-build", "service-build", "native", "lib-deploy", "service-deploy"]:
            document = self.workflow(name)
            with self.subTest(workflow=name):
                self.assertEqual([], validate(document))
                if "build" in document["jobs"]:
                    self.assertNotIn("id-token", document["jobs"]["build"]["permissions"])

    def test_rejects_compile_cache_consumer_checkout_and_arbitrary_actions(self):
        for step in [{"run": "mvn verify"}, {"run": "python uploaded/script.py"},
                     {"run": "python native_components.py compile"},
                     {"uses": "actions/cache@" + "a" * 40},
                     {"uses": "actions/checkout@" + "a" * 40, "with": {"ref": "${{ inputs.source-sha }}"}}]:
            document = self.workflow()
            document["jobs"]["sign"]["steps"].append(step)
            with self.subTest(step=step):
                self.assertTrue(validate(document))

    def test_rejects_parameterized_runner_ambient_oidc_and_changed_checkout_source(self):
        for field, value in [("runs-on", "${{ inputs.runner }}"), ("if", "${{ always() }}"),
                             ("needs", ["axiz-workflows-trust"])]:
            document = self.workflow()
            document["jobs"]["sign"][field] = value
            with self.subTest(field=field):
                self.assertTrue(validate(document))
        document = self.workflow()
        document["permissions"] = {"id-token": "write"}
        self.assertTrue(validate(document))
        document = self.workflow()
        document["jobs"]["sign"]["steps"][0]["with"]["repository"] = "other/source"
        self.assertTrue(validate(document))

    def test_no_artifact_can_supply_signer_executables_controls_or_another_run(self):
        document = self.workflow()
        original = document["jobs"]["sign"]
        for mutation in [
            lambda job: job["steps"].append({"uses": "actions/download-artifact@" + "a" * 40, "with": {"run-id": "999"}}),
            lambda job: job["steps"][2]["env"].update(BUILD_INPUTS_JSON="${{ needs.build.outputs.parameters }}"),
            lambda job: job.update(env={"PYTHONPATH": "uploaded"}),
            lambda job: job.update(defaults={"run": {"working-directory": "uploaded"}})]:
            document = self.workflow()
            mutation(document["jobs"]["sign"])
            self.assertTrue(validate(document))
        document = self.workflow()
        document["env"] = {"BASH_ENV": "uploaded/bootstrap.sh"}
        self.assertTrue(validate(document))


class AllPrivilegedRolesTest(unittest.TestCase):
    def test_docs_sarif_and_release_are_closed_in_their_complete_workflow_context(self):
        for file in ['docs-docs','security','pr','release','release-self']:
            document=yaml.safe_load((ROOT/'workflows'/(file+'.yaml')).read_text())
            with self.subTest(workflow=file):
                self.assertEqual([],validate(document))
                for name,job in document['jobs'].items():
                    if 'write' not in job.get('permissions',{}).values():continue
                    changed=copy.deepcopy(document)
                    if 'steps' in job:changed['jobs'][name]['steps'].append({'run':'python downloaded/arbitrary.py'})
                    else:changed['jobs'][name]['uses']='./.github/workflows/unreviewed.yaml'
                    self.assertTrue(validate(changed))
                changed=copy.deepcopy(document);changed['name']='counterfeit-context'
                self.assertTrue(validate(changed))
    def test_a_sarif_uploader_does_not_authorize_arbitrary_code_or_an_app_token(self):
        for permissions,step in [({'contents':'write'},{'run':'echo arbitrary'}),
                                  ({'security-events':'write'},{'uses':'github/codeql-action/upload-sarif@'+'a'*40}),
                                  ({},{'uses':'actions/create-github-app-token@'+'a'*40})]:
            document={'permissions':{},'jobs':{'unknown':{'runs-on':'ubuntu-24.04','permissions':permissions,'steps':[step]}}}
            self.assertTrue(validate(document))

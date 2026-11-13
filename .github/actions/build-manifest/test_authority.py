import copy
import unittest
from unittest.mock import patch

import authority


def fixture():
    source = {"repository": "example/framework", "sha": "a" * 40}
    run = {"id": 123, "run_attempt": 2, "head_sha": source["sha"], "status": "in_progress",
           "event": "workflow_dispatch", "repository": {"full_name": source["repository"]},
           "head_repository": {"full_name": source["repository"]}}
    job = {"id": 456, "name": "sdk / maven:axiz-framework", "run_id": 123, "run_attempt": 2,
           "head_sha": source["sha"], "status": "completed", "conclusion": "success",
           "steps": [{"name": name, "number": number, "status": "completed", "conclusion": "success"}
                     for number, name in enumerate(authority.GATES["maven-library"], 1)]}
    return run, job, source


class AuthorityTest(unittest.TestCase):
    def test_uses_actions_authority_for_the_exact_producer(self):
        run, job, source = fixture()
        receipt = authority.validate(run, [job], source, 123, 2, "maven:axiz-framework", "maven-library")
        self.assertEqual(456, receipt["producer_job"]["id"])
        self.assertEqual(2, receipt["run_attempt"])

    def test_fabricated_pass_logs_do_not_establish_any_authority(self):
        run, job, source = fixture()
        job["conclusion"] = "failure"
        job["reports"] = {name: "PASSED" for name in authority.GATES["maven-library"]}
        with self.assertRaises(ValueError):
            authority.validate(run, [job], source, 123, 2, "maven:axiz-framework", "maven-library")

    def test_rejects_another_sha_run_attempt_fork_or_producer(self):
        for key, value in [("run_id", 999), ("run_attempt", 1), ("head_sha", "b" * 40),
                           ("conclusion", "cancelled"), ("name", "sdk / maven:other")]:
            run, job, source = fixture()
            job[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                authority.validate(run, [job], source, 123, 2, "maven:axiz-framework", "maven-library")
        run, job, source = fixture()
        run["head_repository"] = {"full_name": "fork/framework"}
        with self.assertRaises(ValueError):
            authority.validate(run, [job], source, 123, 2, "maven:axiz-framework", "maven-library")

    def test_every_required_gate_is_unique_and_successful_not_skipped(self):
        for state in ["skipped", "failure", "cancelled", "missing", "duplicate"]:
            run, job, source = fixture()
            if state == "missing":
                job["steps"].pop()
            elif state == "duplicate":
                job["steps"].append(copy.deepcopy(job["steps"][0]))
            else:
                job["steps"][0]["conclusion"] = state
            with self.subTest(state=state), self.assertRaises(ValueError):
                authority.validate(run, [job], source, 123, 2, "maven:axiz-framework", "maven-library")

    def test_obtains_attempt_specific_jobs_instead_of_upload_metadata(self):
        run, job, source = fixture()
        with patch.object(authority, "api", side_effect=[run, {"jobs": [job]}]) as api:
            authority.obtain(source, 123, 2, "maven:axiz-framework", "maven-library")
        self.assertIn("/attempts/2/jobs?", api.call_args_list[1].args[0])

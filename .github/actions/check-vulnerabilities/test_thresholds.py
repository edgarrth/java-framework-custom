"""Mantiene los puntos de entrada reutilizables de CI al menos tan estrictos como el evaluador compartido de vulnerabilidades."""
import os
from pathlib import Path
import subprocess
import unittest

import yaml


ROOT = Path(__file__).resolve().parents[3]


class WorkflowThresholdTest(unittest.TestCase):
    def test_dependency_review_cannot_be_weakened_by_a_caller(self):
        workflow = yaml.safe_load((ROOT / '.github/workflows/pr.yaml').read_text())
                # El cargador YAML 1.1 de PyYAML convierte la clave "on" de GitHub Actions sin comillas en True.
        trigger = workflow.get('on', workflow.get(True))
        self.assertEqual('medium', trigger['workflow_call']['inputs']['fail-severity']['default'])
        steps = workflow['jobs']['hygiene']['steps']
        guard = next(step for step in steps if step.get('name') == 'Enforce minimum vulnerability threshold')
        review = next(step for step in steps if step.get('uses', '').startswith('actions/dependency-review-action@'))
        self.assertLess(steps.index(guard), steps.index(review))
        self.assertEqual('${{ inputs.fail-severity }}', guard['env']['FAIL_SEVERITY'])
        self.assertEqual('${{ inputs.fail-severity }}', review['with']['fail-on-severity'])
        self.assertNotIn('continue-on-error', guard)
        self.assertNotIn('if', guard)
        for severity in ('low', 'medium', 'high', 'critical', '', 'unknown', 'medium; exit 0'):
            with self.subTest(severity=severity):
                result = subprocess.run(['/bin/bash', '-eu', '-c', guard['run']],
                                        env={'PATH': os.defpath, 'FAIL_SEVERITY': severity},
                                        capture_output=True, text=True, timeout=5, check=False)
                self.assertEqual(severity in {'low', 'medium'}, result.returncode == 0)

    def test_source_sbom_and_image_scans_include_medium_and_fail(self):
        for filename in ('pr.yaml', 'security.yaml', 'lib-build.yaml', 'service-build.yaml'):
            with self.subTest(workflow=filename):
                workflow = yaml.safe_load((ROOT / '.github/workflows' / filename).read_text())
                scans = [step for job in workflow['jobs'].values() for step in job.get('steps', [])
                         if step.get('uses', '').startswith('aquasecurity/trivy-action@')]
                self.assertTrue(scans)
                for scan in scans:
                    self.assertTrue({'MEDIUM', 'HIGH', 'CRITICAL'} <= set(scan['with']['severity'].split(',')))
                    self.assertEqual('1', str(scan['with']['exit-code']))
                    self.assertNotIn('continue-on-error', scan)
                    self.assertNotEqual('true', str(scan['with'].get('ignore-unfixed', '')).lower())
        service = (ROOT / '.github/workflows/service-build.yaml').read_text()
        self.assertIn('trivy image --platform "$platform" --exit-code 1 --severity MEDIUM,HIGH,CRITICAL', service)


if __name__ == '__main__':
    unittest.main()

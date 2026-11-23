#!/usr/bin/env python3
"""Ejercita sin conexión las políticas renderizadas completas; las verificaciones excluidas u omitidas nunca cuentan como cobertura."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys

import yaml

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / '.github/actions/build-manifest'))
from build_destination import BuildDestination


def verified_binary(path):
    binary = path.resolve()
    pin = json.loads((ROOT / '.github/gitops/kyverno.json').read_text())
    with binary.open('rb') as stream:
        actual = hashlib.file_digest(stream, 'sha256').hexdigest()
    if actual != pin['binary_sha256']:
        raise ValueError('Kyverno executable does not match the trusted binary checksum')
    return binary


def result_rows(output):
    rows = []
    decoder = json.JSONDecoder()
    for match in re.finditer(r'^\[\s*$', output, re.MULTILINE):
        value, _ = decoder.raw_decode(output[match.start():])
        if not isinstance(value, list) or any(not isinstance(row, dict) for row in value):
            raise ValueError('Unexpected Kyverno result structure')
        rows.extend(value)
    return rows


def evaluate(binary, root, log):
    process = subprocess.run(['kyverno', 'test', str(root), '--output-format', 'json',
                              '--remove-color', '--require-tests'], executable=str(binary),
                             text=True, capture_output=True)
    log.write_text(process.stdout + process.stderr)
    expected = []
    for path in sorted(root.rglob('kyverno-test.yaml')):
        for case in yaml.safe_load(path.read_text())['results']:
            for resource in case['resources']:
                expected.append((case['policy'], case['rule'], case['kind'], resource))
    executed = set()
    for row in result_rows(process.stdout):
        if row.get('REASON') == 'Ok' and row.get('RESULT') == 'Pass':
            parts = row['RESOURCE'].split('/')
            executed.add((row['POLICY'], row['RULE'], parts[-3], parts[-1]))
    missing = sorted(set(expected) - executed)
    if process.returncode or missing or not expected:
        raise ValueError(f'Kyverno admission checks failed or were not executed: {missing}; see {log}')
    return {'cases': len(expected), 'executed': sorted(executed),
            'excludedRowsNotCounted': sum(row.get('REASON') == 'Excluded' for row in result_rows(process.stdout))}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--kyverno', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    binary = verified_binary(args.kyverno)
    version = json.loads((ROOT / '.github/gitops/kyverno.json').read_text())['version']
    # argv fijo; el ejecutable es el archivo verificado por checksum, nunca un comando de shell ni una búsqueda en PATH.
    observed = subprocess.check_output(['kyverno', 'version'], executable=str(binary), text=True)
    if not re.search(r'^Version: ' + re.escape(version) + r'$', observed, re.MULTILINE):
        raise ValueError('Kyverno CLI does not match the pinned policy version')
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    rendered = output / 'rendered'
    configuration = BuildDestination.parse((ROOT / '.github/gitops/fixtures/build-configuration.json').read_text())
    configuration.render(ROOT / '.github/gitops', rendered)
    shutil.copytree(ROOT / '.github/gitops/tests', rendered / 'tests', dirs_exist_ok=True)
    results = evaluate(binary, rendered / 'tests', output / 'kyverno-results.log')
    # Demuestra que este gate falla si se pierde la autogeneración del controlador, aunque las verificaciones de Pod pasen.
    path = rendered / 'policies/verify-images.yaml'
    original = path.read_text()
    policy = yaml.safe_load(original)
    policy['metadata']['annotations']['pod-policies.kyverno.io/autogen-controllers'] = 'none'
    path.write_text(yaml.safe_dump(policy, sort_keys=False))
    detected = False
    try:
        evaluate(binary, rendered / 'tests', output / 'autogen-regression.log')
    except ValueError:
        detected = True
    finally:
        path.write_text(original)
    if not detected:
        raise ValueError('Autogeneration regression was not detected')
    results.update(version=version, passed=True, autogenRegressionDetected=True,
                   signatureVerification='Configuration checked separately; no registry access or cryptographic verification in this offline matrix')
    (output / 'result.json').write_text(json.dumps(results, indent=2) + '\n')
    print(json.dumps(results, indent=2))


if __name__ == '__main__':
    main()

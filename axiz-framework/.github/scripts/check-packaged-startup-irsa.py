#!/usr/bin/env python3
"""Start the actual beneficiaries Boot JAR with production starters and managed AWS identity."""
from contextlib import closing
import argparse
import hashlib
import base64
import os
import json
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path
from irsa_fixture import IrsaFixture
from chart_startup import verify_chart_startup


def docker(*arguments):
    return subprocess.check_output(['docker', *arguments], text=True).strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--chart', type=Path, default=Path(__file__).resolve().parents[3] / 'axiz-demo/services/beneficiaries/deploy/helm/beneficiaries')
    args = parser.parse_args()
    jar = args.jar.resolve()
    if not jar.is_file() or not jar.name.startswith('beneficiaries-'):
        raise ValueError('Use the packaged beneficiaries demonstration service')
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    container = docker('run', '-d', '--rm', '-p', '127.0.0.1::4566',
                       '-e', 'SERVICES=dynamodb,ssm', 'localstack/localstack:4.9')
    try:
        port = int(docker('port', container, '4566/tcp').rsplit(':', 1)[1])
        endpoint = f'http://127.0.0.1:{port}'
        deadline = time.monotonic() + 90
        while True:
            try:
                with urllib.request.urlopen(endpoint + '/_localstack/health', timeout=2) as response:
                    health = json.load(response)
                if health.get('services', {}).get('dynamodb') in ('available', 'running'):
                    break
            except (OSError, ValueError):
                pass
            if time.monotonic() >= deadline:
                raise TimeoutError('Local AWS dependencies did not become ready')
            time.sleep(0.5)
        results = []
        for mode in ('valid', 'invalid', 'incomplete', 'static-conflict', 'prod-plaintext'):
            with closing(IrsaFixture()) as identity, tempfile.TemporaryDirectory(prefix='axiz-packaged-') as directory:
                identity.succeed = mode != 'invalid'
                secret = Path(directory) / 'cursor-key-v1'
                secret.write_text(base64.b64encode(os.urandom(32)).decode())
                environment = identity.environment()
                if mode == 'incomplete':
                    environment.pop('AWS_WEB_IDENTITY_TOKEN_FILE')
                with socket.socket() as reservation:
                    reservation.bind(('127.0.0.1', 0))
                    service_port = reservation.getsockname()[1]
                command = ['java', '-jar', str(jar), '--server.address=127.0.0.1',
                           f'--server.port={service_port}', '--axiz.config.environment=test',
                           '--axiz.transport.allow-plain-http=true', '--axiz.logging.json=false',
                           f'--axiz.secrets.directory={directory}', f'--axiz.aws.endpoint={endpoint}',
                           '--axiz.aws.region=us-east-1', '--spring.cloud.aws.region.static=us-east-1',
                           f'--spring.cloud.aws.endpoint={endpoint}',
                           '--spring.config.import=aws-parameterstore:/fixture/',
                           '--spring.cloud.aws.parameterstore.enabled=true',
                           f'--spring.cloud.aws.parameterstore.endpoint=http://127.0.0.1:{identity.server.server_port}',
                           '--management.tracing.enabled=false', '--management.otlp.metrics.export.enabled=false',
                           '--management.opentelemetry.tracing.export.otlp.enabled=false']
                if mode == 'static-conflict':
                    command += ['--spring.cloud.aws.credentials.access-key=static-fixture',
                                '--spring.cloud.aws.credentials.secret-key=static-fixture']
                if mode == 'prod-plaintext':
                    command += ['--axiz.config.environment=prod']
                log_path = output / f'packaged-startup-{mode}.log'
                with log_path.open('w') as log:
                    # Validated local JAR, fixed JVM options, argv list and sanitized IRSA fixture environment.
                    # nosemgrep: python.lang.security.audit.dangerous-subprocess-use-tainted-env-args.dangerous-subprocess-use-tainted-env-args
                    process = subprocess.Popen(command, env=environment, stdout=log, stderr=subprocess.STDOUT)
                    try:
                        if mode == 'valid':
                            deadline = time.monotonic() + 90
                            while True:
                                if process.poll() is not None:
                                    raise RuntimeError(f'Packaged service exited before health UP; see {log_path}')
                                try:
                                    with urllib.request.urlopen(f'http://127.0.0.1:{service_port}/actuator/health', timeout=2) as response:
                                        if json.load(response).get('status') == 'UP':
                                            break
                                except (OSError, ValueError):
                                    pass
                                if time.monotonic() >= deadline:
                                    raise TimeoutError(f'Packaged health did not become UP; see {log_path}')
                                time.sleep(0.25)
                            for probe in ('liveness', 'readiness'):
                                with urllib.request.urlopen(f'http://127.0.0.1:{service_port}/actuator/health/{probe}', timeout=5) as response:
                                    if json.load(response).get('status') != 'UP':
                                        raise RuntimeError(f'Packaged {probe} not UP')
                            if identity.sts_calls < 1 or identity.ssm_calls < 1:
                                raise RuntimeError('Packaged startup did not load real SSM using temporary STS identity')
                        else:
                            status = process.wait(timeout=60)
                            if status == 0:
                                raise RuntimeError(f'Packaged startup accepted {mode}')
                            content = log_path.read_text()
                            reason = {'invalid': 'InvalidIdentityToken', 'incomplete': 'IRSA requires both',
                                      'static-conflict': 'not a managed Axiz credential setting',
                                      'prod-plaintext': 'requires HTTPS'}[mode]
                            if reason not in content:
                                raise RuntimeError(f'Packaged rejection did not identify {mode}; see {log_path}')
                            if mode in ('incomplete', 'static-conflict', 'prod-plaintext') and identity.sts_calls:
                                raise RuntimeError(f'{mode} reached STS before validation')
                        if identity.imds_calls or identity.invalid_requests:
                            raise RuntimeError('Packaged startup escaped its intended identity')
                        results.append({'case': mode, 'passed': True, 'stsRequests': identity.sts_calls,
                                        'ssmRequests': identity.ssm_calls, 'imdsRequests': identity.imds_calls,
                                        'log': log_path.name, 'logSha256': hashlib.sha256(log_path.read_bytes()).hexdigest()})
                    finally:
                        if process.poll() is None:
                            process.terminate()
                            try:
                                process.wait(timeout=15)
                            except subprocess.TimeoutExpired:
                                process.kill()
                                process.wait(timeout=5)
        results.extend(verify_chart_startup(jar, args.chart.resolve(), endpoint, output))
        for record in results:
            record['logSha256'] = hashlib.sha256((output / record['log']).read_bytes()).hexdigest()
        (output / 'packaged-startup.json').write_text(json.dumps({
            'passed': True, 'artifact': jar.name, 'jarSha256': hashlib.sha256(jar.read_bytes()).hexdigest(),
            'runtime': 'java -jar with production starters; no test credential provider', 'cases': results,
        }, indent=2) + '\n')
        print('PASS: actual packaged demo startup, health/probes UP and managed IRSA rejection matrix')
    finally:
        subprocess.run(['docker', 'rm', '-f', container], check=False, stdout=subprocess.DEVNULL)


if __name__ == '__main__':
    main()

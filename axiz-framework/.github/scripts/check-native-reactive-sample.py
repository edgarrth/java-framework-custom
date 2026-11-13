#!/usr/bin/env python3
"""Boot a generated native service with owned local dependencies; require all health probes UP."""
import argparse
import re
from defusedxml import ElementTree as ET
from pathlib import Path
import hashlib
import json
import os
import socket
import subprocess
import time
import urllib.request
from irsa_fixture import IrsaFixture

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--module', type=Path, required=True)
parser.add_argument('--runtime', choices=('native', 'jvm-aot'), default='native')
args = parser.parse_args()
project = args.module.resolve()
artifact = ET.parse(project / 'pom.xml').getroot().findtext('{*}artifactId')
if artifact is None or re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]*', artifact) is None:
    raise ValueError('A literal Maven artifactId is required for the native acceptance fixture')
logs = project / 'target'
runtime_prefix = 'native-runtime' if args.runtime == 'native' else 'jvm-aot-runtime'
runtime_name = 'native ELF; no JVM process' if args.runtime == 'native' else 'JVM with Spring AOT enabled'
if args.runtime == 'native':
    binary = logs / artifact
    if not binary.is_file() or binary.read_bytes()[:4] != b'\x7fELF':
        raise ValueError('Compile the ELF native service before checking its runtime')
    command = [str(binary)]
else:
    jars = [jar for jar in logs.glob(artifact + '-*.jar')
            if not jar.name.endswith(('-sources.jar', '-javadoc.jar'))]
    if len(jars) != 1:
        raise ValueError('Package the service with its AOT classes before the JVM AOT precheck')
    binary = jars[0]
    command = ['java', '-Dspring.aot.enabled=true', '-Djava.security.egd=file:/dev/./urandom', '-jar', str(binary)]
containers = []
process = None
service_log = None
irsa = IrsaFixture()

def docker(*args):
    return subprocess.check_output(['docker', *args], text=True).strip()

def container_port(container, port):
    return int(docker('port', container, f'{port}/tcp').rsplit(':', 1)[1])

try:
    postgres = docker('run', '-d', '--rm', '-p', '127.0.0.1::5432',
                      '-e', 'POSTGRES_USER=app', '-e', 'POSTGRES_PASSWORD=native-acceptance-only',
                      '-e', 'POSTGRES_DB=app', 'postgres:17.6-alpine')
    containers.append(postgres)
    localstack = docker('run', '-d', '--rm', '-p', '127.0.0.1::4566',
                        '-e', 'SERVICES=sqs,secretsmanager,ssm', 'localstack/localstack:4.9')
    containers.append(localstack)
    pg_port = container_port(postgres, 5432)
    aws_port = container_port(localstack, 4566)
    aws_endpoint = f'http://127.0.0.1:{aws_port}'
    deadline = time.monotonic() + 90
    while True:
        pg_ready = subprocess.run(['docker', 'exec', postgres, 'pg_isready', '-U', 'app'],
                                  stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0
        aws_ready = False
        try:
            with urllib.request.urlopen(aws_endpoint + '/_localstack/health', timeout=2) as response:
                health = json.load(response)
            aws_ready = health.get('services', {}).get('sqs') in ('available', 'running')
        except (OSError, ValueError):
            pass
        if pg_ready and aws_ready:
            break
        if time.monotonic() >= deadline:
            raise TimeoutError('Native runtime dependencies did not become ready')
        time.sleep(0.5)
    docker('exec', localstack, 'awslocal', 'sqs', 'create-queue',
           '--queue-name', 'native-acceptance-audit')
    with socket.socket() as reservation:
        reservation.bind(('127.0.0.1', 0))
        service_port = reservation.getsockname()[1]
    environment = irsa.environment()
    # Framework-owned clients must discover STS in this ELF and resolve web identity, not static keys.
    # The ELF receives no PRNG flag: its compiled default must come from the parent.
    args = [*command, '--axiz.acceptance.default-identity-probe=true', '--server.address=127.0.0.1', f'--server.port={service_port}',
            f'--axiz.aws.endpoint={aws_endpoint}', '--axiz.aws.region=us-east-1',
            f'--axiz.audit.sqs.queue-url={aws_endpoint}/000000000000/native-acceptance-audit',
            f'--spring.cloud.aws.endpoint={aws_endpoint}', '--spring.cloud.aws.region.static=us-east-1',
            f'--spring.r2dbc.url=r2dbc:postgresql://127.0.0.1:{pg_port}/app',
            '--spring.r2dbc.username=app', '--spring.r2dbc.password=native-acceptance-only',
            '--spring.r2dbc.properties.sslMode=disable',
            '--axiz.transport.allow-insecure-data-stores=true',
            '--spring.flyway.jdbc-properties.sslmode=disable',
            f'--spring.flyway.url=jdbc:postgresql://127.0.0.1:{pg_port}/app',
            '--spring.flyway.user=app', '--spring.flyway.password=native-acceptance-only']
    service_log = (logs / f'{runtime_prefix}-service.log').open('w')
    started = time.monotonic()
    # Owned JVM/ELF from the validated project; argv list, no shell; OS allowlist plus local IRSA fixture only.
    # nosemgrep: python.lang.security.audit.dangerous-subprocess-use-tainted-env-args.dangerous-subprocess-use-tainted-env-args
    process = subprocess.Popen(args, cwd=project, env=environment,
                               stdout=service_log, stderr=subprocess.STDOUT)
    deadline = time.monotonic() + 90
    while True:
        if process.poll() is not None:
            raise RuntimeError(f'Native service exited: inspect {service_log.name}')
        try:
            with urllib.request.urlopen(f'http://127.0.0.1:{service_port}/actuator/health', timeout=2) as response:
                health = json.load(response)
            if health.get('status') == 'UP':
                break
        except (OSError, ValueError):
            pass
        if time.monotonic() >= deadline:
            raise TimeoutError(f'Native health did not become UP: inspect {service_log.name}')
        time.sleep(0.5)
    for endpoint in ('liveness', 'readiness'):
        with urllib.request.urlopen(f'http://127.0.0.1:{service_port}/actuator/health/{endpoint}', timeout=5) as response:
            if json.load(response).get('status') != 'UP':
                raise RuntimeError(f'Native {endpoint} probe is not UP')
    if irsa.sts_calls < 1 or irsa.imds_calls or irsa.invalid_requests:
        raise RuntimeError(f'Native IRSA did not resolve through STS exclusively: '
                           f'sts={irsa.sts_calls}, imds={irsa.imds_calls}, invalid={irsa.invalid_requests}')
    if 'AXIZ_D3_AUDIT_PROVIDER_USED' not in (logs / f'{runtime_prefix}-service.log').read_text():
        raise RuntimeError('Native audit did not use the qualified application credentials provider')
    deadline = time.monotonic() + 30
    while 'AXIZ_I2_DEFAULT_CONTEXT_VALIDATED' not in (logs / f'{runtime_prefix}-service.log').read_text():
        if process.poll() is not None or time.monotonic() >= deadline:
            raise RuntimeError('Native default-context identity/cleanup probe did not complete')
        time.sleep(0.2)
    if 'AXIZ_K1_AUTOMATIC_GLOBAL_REJECTED' not in (logs / f'{runtime_prefix}-service.log').read_text():
        raise RuntimeError('Native AOT did not preserve rejection of an automatic foreign global')
    elapsed = round(time.monotonic() - started, 3)
    process.terminate()
    process.wait(timeout=15)
    irsa.succeed = False
    previous_sts = irsa.sts_calls
    with (logs / f'{runtime_prefix}-invalid-irsa.log').open('w') as rejected_log:
        # Owned executable; sanitized fixture environment, argv list and no shell.
        # nosemgrep: python.lang.security.audit.dangerous-subprocess-use-tainted-env-args.dangerous-subprocess-use-tainted-env-args
        process = subprocess.Popen(args, cwd=project, env=environment,
                                   stdout=rejected_log, stderr=subprocess.STDOUT)
        try:
            status = process.wait(timeout=60)
        except subprocess.TimeoutExpired as failure:
            raise RuntimeError('Native service did not reject invalid IRSA during startup') from failure
    rejected = (logs / f'{runtime_prefix}-invalid-irsa.log').read_text()
    if (status == 0 or not any(reason in rejected for reason in ('InvalidIdentityToken', 'fixture denied'))
            or irsa.sts_calls <= previous_sts or irsa.imds_calls):
        raise RuntimeError('Native invalid-IRSA check did not fail closed through STS')
    evidence = {'binary_sha256': hashlib.sha256(binary.read_bytes()).hexdigest(),
                'health': 'UP', 'liveness': 'UP', 'readiness': 'UP',
                'elapsed_to_health_seconds': elapsed,
                'postgres_image': 'postgres:17.6-alpine', 'aws_emulator': 'localstack/localstack:4.9',
                'runtime': runtime_name,
                'credentials': 'IRSA via local STS; no static AWS credentials in environment',
                'sts_requests': irsa.sts_calls, 'imds_requests': irsa.imds_calls,
                'valid_irsa_sts_requests': previous_sts,
                'invalid_irsa_sts_requests': irsa.sts_calls - previous_sts,
                'invalid_irsa_exit_code': status,
                'qualified_application_provider_used': True,
                'default_context_identity_validated': True,
                'automatic_global_rejected_after_aot': True,
                'invalid_irsa_rejected_at_startup': True}
    (logs / f'{runtime_prefix}-result.json').write_text(json.dumps(evidence, indent=2) + '\n')
    print(f'PASS: {runtime_name}: health, liveness, readiness and identity provenance with PostgreSQL and LocalStack')
finally:
    if process is not None and process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)
    if service_log is not None:
        service_log.close()
    irsa.close()
    for container in reversed(containers):
        subprocess.run(['docker', 'rm', '-f', container], stdout=subprocess.DEVNULL,
                       stderr=subprocess.DEVNULL, check=False)

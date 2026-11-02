#!/usr/bin/env python3
"""Boot a generated native service with owned local dependencies; require all health probes UP."""
import argparse
import re
import xml.etree.ElementTree as ET
from pathlib import Path
import hashlib
import json
import os
import socket
import subprocess
import time
import urllib.request

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--module', type=Path, required=True)
args = parser.parse_args()
project = args.module.resolve()
artifact = ET.parse(project / 'pom.xml').getroot().findtext('{*}artifactId')
if artifact is None or re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]*', artifact) is None:
    raise ValueError('A literal Maven artifactId is required for the native acceptance fixture')
logs = project / 'target'
binary = logs / artifact
if not binary.is_file() or binary.read_bytes()[:4] != b'\x7fELF':
    raise ValueError('Compile the ELF native service before checking its runtime')
containers = []
process = None
service_log = None

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
    with socket.socket() as reservation:
        reservation.bind(('127.0.0.1', 0))
        service_port = reservation.getsockname()[1]
    environment = os.environ.copy()
    environment.update(AWS_ACCESS_KEY_ID='test', AWS_SECRET_ACCESS_KEY='test', AWS_REGION='us-east-1',
                       AWS_CONFIG_FILE='/dev/null', AWS_SHARED_CREDENTIALS_FILE='/dev/null',
                       AWS_EC2_METADATA_DISABLED='true')
    environment.pop('AWS_PROFILE', None)
    environment.pop('AWS_SESSION_TOKEN', None)
    environment.pop('NEXUS_USERNAME', None)
    environment.pop('NEXUS_PASSWORD', None)
    # Deliberately omit runtime PRNG flags: the parent must configure the compiled default.
    args = [str(binary), '--server.address=127.0.0.1', f'--server.port={service_port}',
            f'--axiz.aws.endpoint={aws_endpoint}', '--axiz.aws.region=us-east-1',
            f'--spring.cloud.aws.endpoint={aws_endpoint}', '--spring.cloud.aws.region.static=us-east-1',
            '--spring.cloud.aws.credentials.access-key=test', '--spring.cloud.aws.credentials.secret-key=test',
            f'--spring.r2dbc.url=r2dbc:postgresql://127.0.0.1:{pg_port}/app',
            '--spring.r2dbc.username=app', '--spring.r2dbc.password=native-acceptance-only',
            '--spring.r2dbc.properties.sslMode=disable',
            f'--spring.flyway.url=jdbc:postgresql://127.0.0.1:{pg_port}/app',
            '--spring.flyway.user=app', '--spring.flyway.password=native-acceptance-only']
    service_log = (logs / 'native-runtime-service.log').open('w')
    started = time.monotonic()
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
    evidence = {'binary_sha256': hashlib.sha256(binary.read_bytes()).hexdigest(),
                'health': 'UP', 'liveness': 'UP', 'readiness': 'UP',
                'elapsed_to_health_seconds': round(time.monotonic() - started, 3),
                'postgres_image': 'postgres:17.6-alpine', 'aws_emulator': 'localstack/localstack:4.9',
                'runtime': 'native ELF; no JVM process'}
    (logs / 'native-runtime-result.json').write_text(json.dumps(evidence, indent=2) + '\n')
    print('PASS: generated native service health, liveness and readiness UP with real PostgreSQL and LocalStack')
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
    for container in reversed(containers):
        subprocess.run(['docker', 'rm', '-f', container], stdout=subprocess.DEVNULL,
                       stderr=subprocess.DEVNULL, check=False)

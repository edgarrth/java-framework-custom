#!/usr/bin/env python3
"""Ejercita la ingesta OTLP y las consultas de Grafana contra la imagen LGTM local real."""
import argparse
import base64
import json
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path


def docker(*args):
    return subprocess.check_output(['docker', *args], text=True, stderr=subprocess.STDOUT, timeout=90).strip()


def request(url, payload=None, authenticated=False):
    headers = {'Content-Type': 'application/json'}
    if authenticated:
                # Un contenedor de desarrollo desechable y solo de loopback; sin credenciales externas.
        headers['Authorization'] = 'Basic ' + base64.b64encode(b'admin:local-only').decode()
    body = json.dumps(payload).encode() if payload is not None else None
    with urllib.request.urlopen(urllib.request.Request(url, body, headers), timeout=10) as response:
        return json.load(response)


def eventually(operation, predicate, seconds=90):
    deadline = time.monotonic() + seconds
    last = None
    while time.monotonic() < deadline:
        try:
            last = operation()
            if predicate(last):
                return last
        except (OSError, ValueError) as error:
            last = str(error)
        time.sleep(1)
    raise RuntimeError(f'Observability assertion timed out: {last}')


def verify(image, report):
    name = 'axiz-otel-probe-' + uuid.uuid4().hex[:12]
    evidence = {'image': image, 'passed': False}
    try:
        docker('run', '--detach', '--name', name, '--read-only', '--cap-drop', 'ALL',
               '--security-opt', 'no-new-privileges', '--tmpfs', '/tmp:rw,nosuid,mode=1777',
               '--tmpfs', '/data:rw,nosuid,uid=10001,gid=10001,mode=0750',
               '-p', '127.0.0.1::3000', '-p', '127.0.0.1::4318', image)
        state = json.loads(docker('inspect', name))[0]
        evidence['image_id'] = state['Image']
        if state['Config']['User'] != '10001:10001':
            raise ValueError('LGTM must run as its dedicated non-root user')
        ports = state['NetworkSettings']['Ports']
        grafana = 'http://127.0.0.1:' + ports['3000/tcp'][0]['HostPort']
        otlp = 'http://127.0.0.1:' + ports['4318/tcp'][0]['HostPort']
        evidence['health'] = eventually(lambda: request(grafana + '/api/health'),
                                       lambda value: value.get('database') == 'ok')
        now = str(time.time_ns())
        trace = uuid.uuid4().hex
        span = uuid.uuid4().hex[:16]
        marker = 'axiz-otlp-' + uuid.uuid4().hex
        resource = {'attributes': [{'key': 'service.name', 'value': {'stringValue': 'axiz-image-probe'}}]}
        traces = {'resourceSpans': [{'resource': resource, 'scopeSpans': [{'spans': [{
            'traceId': trace, 'spanId': span, 'name': marker, 'kind': 1,
            'startTimeUnixNano': now, 'endTimeUnixNano': str(int(now) + 1000000),
            'status': {'code': 1}}]}]}]}
        metrics = {'resourceMetrics': [{'resource': resource, 'scopeMetrics': [{'metrics': [{
            'name': 'axiz_recipe_value', 'gauge': {'dataPoints': [{
                'timeUnixNano': now, 'asDouble': 7.0}]}}]}]}]}
        logs = {'resourceLogs': [{'resource': resource, 'scopeLogs': [{'logRecords': [{
            'timeUnixNano': now, 'observedTimeUnixNano': now, 'severityNumber': 9,
            'severityText': 'INFO', 'body': {'stringValue': marker},
            'traceId': trace, 'spanId': span}]}]}]}
        for signal, body in [('traces', traces), ('metrics', metrics), ('logs', logs)]:
            response = request(otlp + '/v1/' + signal, body)
            if response.get('partialSuccess'):
                raise ValueError(f'{signal} was partially rejected: {response}')
        proxy = grafana + '/api/datasources/proxy/uid/'
        evidence['metrics'] = eventually(
            lambda: request(proxy + 'prometheus/api/v1/query?query=axiz_recipe_value', authenticated=True),
            lambda value: any(float(row['value'][1]) == 7.0 for row in value.get('data', {}).get('result', [])))
        query = urllib.parse.urlencode({'query': '{service_name="axiz-image-probe"} |= "' + marker + '"'})
        evidence['logs'] = eventually(
            lambda: request(proxy + 'loki/loki/api/v1/query_range?' + query, authenticated=True),
            lambda value: any(marker in row[1] for stream in value.get('data', {}).get('result', [])
                              for row in stream.get('values', [])))
        evidence['traces'] = eventually(
            lambda: request(proxy + 'tempo/api/traces/' + trace, authenticated=True),
            lambda value: marker in json.dumps(value))
        evidence['passed'] = True
    finally:
        try:
            evidence['container_logs'] = docker('logs', name)
        finally:
            subprocess.run(['docker', 'rm', '-f', name], check=False, capture_output=True, timeout=30)
            report.parent.mkdir(parents=True, exist_ok=True)
            report.write_text(json.dumps(evidence, indent=2) + '\n')
    print('LGTM: non-root, read-only; OTLP traces, metrics and logs ingested and queried through Grafana')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('image')
    parser.add_argument('--report', type=Path, required=True)
    arguments = parser.parse_args()
    verify(arguments.image, arguments.report)

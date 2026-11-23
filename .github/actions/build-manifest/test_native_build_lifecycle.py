import json
import os
from pathlib import Path
import platform
import subprocess
import tempfile
import unittest

import yaml

ROOT = Path(__file__).resolve().parents[2]


class NativeBuildLifecycleTest(unittest.TestCase):
    def test_native_compilation_consumes_verified_outputs_without_repeating_the_lifecycle(self):
        document = yaml.safe_load((ROOT / 'workflows/service-build.yaml').read_text())
        step = next(step for step in document['jobs']['build']['steps']
                    if 'profiles=quality' in step.get('run', '') and 'install' in step['run'])
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            binary = root / 'bin'
            binary.mkdir()
            mvn = binary / 'mvn'
            mvn.write_text('''#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
args=sys.argv[1:]
log=Path(os.environ['LIFECYCLE_LOG'])
with log.open('a') as stream: stream.write(json.dumps(args)+'\\n')
state=Path('verified')
# Una invocación native:compile hace fork de package y volvería a ejecutar las pruebas tras los proxies generados por AOT.
if state.exists() and (any(arg in {'compile','test','package','verify','install'} for arg in args) or 'native:compile' in args):
    sys.exit('Verified package must not be rebuilt while native compilation consumes its AOT output')
if 'install' in args: state.write_text('verified')
Path('service/target').mkdir(parents=True, exist_ok=True)
Path('service/target/bom.json').write_text('{}')
''')
            mvn.chmod(0o700)
            python = binary / 'policy-python'
            python.write_text('#!/bin/sh\nexit 0\n')
            python.chmod(0o700)
            environment = dict(os.environ, PATH=str(binary) + os.pathsep + os.environ['PATH'],
                               MODULE='service', VARIANT='native',
                               PLATFORMS=json.dumps(['linux/amd64' if platform.machine() == 'x86_64' else 'linux/arm64']),
                               BASE_IMAGE='registry.example/java@sha256:' + 'a' * 64,
                               PYTHON=str(python), TOOLS='/trusted/tools', METADATA='metadata.json',
                               LIFECYCLE_LOG=str(root / 'commands.jsonl'))
            result = subprocess.run(['bash', '-euo', 'pipefail', '-c', step['run']], cwd=root,
                                    env=environment, text=True, capture_output=True)
            self.assertEqual(0, result.returncode, result.stderr)
            commands = [json.loads(line) for line in (root / 'commands.jsonl').read_text().splitlines()]
            install = next(i for i, command in enumerate(commands) if 'install' in command)
            native = next(i for i, command in enumerate(commands) if 'native:compile-no-fork' in command)
            self.assertLess(install, native)
            self.assertIn('native:test', commands[native])
            self.assertTrue((root / 'target/build-reports/bom.json').is_file())

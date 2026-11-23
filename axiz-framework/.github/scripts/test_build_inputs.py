"""Regresión: los archivos locales ignorados y el chmod del árbol de trabajo no deben ocultar checkouts rotos."""
import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('build_inputs', Path(__file__).with_name('check-build-inputs.py'))
policy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(policy)


class BuildInputsTest(unittest.TestCase):
    def test_generated_projects_override_global_maven_ignores_without_tracking_outputs(self):
        template = Path(__file__).resolve().parents[2] / 'codegen/axiz-codegen-templates/src/main/resources/axiz/templates/service/static/gitignore'
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / 'project'
            root.mkdir()
            ignored = Path(directory) / 'global-ignore'
            ignored.write_text('.mvn/\n')
            def git(*args):
                return subprocess.check_output(['git', '-C', str(root), '-c', f'core.excludesFile={ignored}', *args], stderr=subprocess.DEVNULL)
            git('init', '-q')
            (root / '.gitignore').write_text(template.read_text())
            for name in (*policy.CONFIG, 'target/output.jar', 'local/secrets/password', '.env'):
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text('fixture\n')
            git('add', '.')
            tracked = set(git('ls-files').decode().splitlines())
            self.assertTrue(set(policy.CONFIG).issubset(tracked))
            self.assertFalse({'target/output.jar', 'local/secrets/password', '.env'} & tracked)

    def test_missing_inputs_permissions_and_untracked_reactor_modules(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            def git(*args):
                return subprocess.check_output(['git', '-C', directory, *args], stderr=subprocess.DEVNULL)
            git('init', '-q')
            (root / 'pom.xml').write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"><modules><module>coverage</module></modules></project>')
            (root / 'coverage').mkdir()
            (root / 'coverage/pom.xml').write_text('<project/>')
            (root / 'mvnw').write_text('#!/bin/sh\nexit 0\n')
            (root / 'mvnw').chmod(0o755)
            for name in policy.CONFIG:
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text('test input\n')
            git('add', 'pom.xml', 'mvnw')
            git('update-index', '--chmod=-x', 'mvnw')
            errors = policy.check(root)
            self.assertTrue(any('100755' in error for error in errors))
            self.assertTrue(any('.mvn/jvm.config' in error for error in errors))
            self.assertTrue(any('coverage/pom.xml' in error for error in errors))
            git('add', '-f', *policy.CONFIG, 'coverage/pom.xml')
            git('update-index', '--chmod=+x', 'mvnw')
            self.assertEqual([], policy.check(root))
            (root / 'coverage/pom.xml').unlink()
            self.assertTrue(policy.check(root))

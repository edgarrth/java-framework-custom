#!/usr/bin/env python3
"""Check the demo deploy callers against the current Mustache template and project manifests."""
import argparse
import hashlib
from pathlib import Path

import chevron
from chevron.tokenizer import tokenize
import yaml

from workflow_release_normalization import normalize, specification

ROOT = Path(__file__).resolve().parents[2]
TEMPLATE = Path('axiz-framework/codegen/axiz-codegen-templates/src/main/resources/axiz/templates/service/github/deploy.yaml.mustache')

FIXTURE = Path('axiz-framework/codegen/axiz-codegen/src/test/resources/workflow-contracts/deploy-rendering.yaml')


def render_template(template, context):
    # TemplateSet configures escapeHTML(false); Chevron's default is HTML escaping.
    tokens = [('no escape' if kind == 'variable' else kind, value) for kind, value in tokenize(template)]
    return chevron.render(tokens, context)


def fixture_errors(root):
    fixture = yaml.safe_load((root / FIXTURE).read_text())
    source = (root / TEMPLATE).read_bytes()
    spec = specification(root)
    if fixture.get('normalization') != spec:
        return ['Deploy fixture normalization differs from the shared specification']
    normalized = normalize(source, spec)
    if hashlib.sha256(normalized.encode('utf-8')).hexdigest() != fixture['normalizedTemplateSha256']:
        return ['Deploy template changed: refresh the fixture with the built Axiz generator']
    return [item['id'] + ': lightweight rendering differs from the real generator fixture'
            for item in fixture['cases'] if normalize(render_template(source.decode('utf-8'), item['context']), spec) != item['rendered']]


def rendered_deploy(root, project):
    manifest = yaml.safe_load((project / '.axiz/project.yaml').read_text())
    module = manifest.get('module', '.')
    context = {'project': {'name': manifest['name'], 'moduleYaml': "'" + module.replace("'", "''") + "'"}}
    return render_template((root / TEMPLATE).read_text(), context)


def check(root):
    errors = []
    projects = sorted((root / 'axiz-demo/services').glob('*/.axiz/project.yaml'))
    if not projects:
        return ['No demo project manifests found']
    for manifest in projects:
        project = manifest.parent.parent
        actual = project / '.github/workflows/deploy.yaml'
        if not actual.is_file() or yaml.safe_load(actual.read_text()) != yaml.safe_load(rendered_deploy(root, project)):
            errors.append(str(actual.relative_to(root)) + ': deploy differs from the current rendered template')
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root', type=Path, nargs='?', default=ROOT)
    args = parser.parse_args()
    errors = fixture_errors(args.root) + check(args.root)
    for error in errors:
        print(error)
    if not errors:
        print('All demo deployment workflows match the rendered template')
    return int(bool(errors))


if __name__ == '__main__':
    raise SystemExit(main())

"""Fixtures estructurales independientes de la versión; los pins efectivos de despliegue se mantienen exactos."""
from pathlib import Path
import re
import sys
import yaml

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'actions'))
from workflow_release import CONTRACT, normalization

SPEC = Path('axiz-framework/codegen/axiz-codegen/src/test/resources/workflow-contracts/release-normalization.yaml')


def specification(root):
    return normalization(root)


def normalize(value, spec):
    if isinstance(value, bytes):value = value.decode('utf-8', errors='strict')
    if spec['token'] in value:raise ValueError('Normalization token already present')
    pattern = re.compile(spec['pattern'])
    versions = set(pattern.findall(value))
    if len(versions) != 1:raise ValueError('Expected one consistent canonical workflow release')
    return pattern.sub(lambda _: spec['token'], value)

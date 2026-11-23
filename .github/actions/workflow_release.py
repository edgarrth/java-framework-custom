"""Una única gramática canónica de release v2, independiente de la versión de release mutable."""
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
CONTRACT = Path('.github/policies/workflow-release-v2.json')


def specification(root=ROOT):
    data = json.loads((Path(root) / CONTRACT).read_text(encoding='utf-8'))
    if data.get('schema') != 1 or set(data) != {'schema', 'pattern', 'normalization'}:
        raise ValueError('Invalid workflow release grammar contract')
    return data


TAG_PATTERN = specification()['pattern']
TAG = re.compile(TAG_PATTERN, re.ASCII)


def normalization(root=ROOT):
    data = specification(root)
    rule = data['normalization']
    return {'token': rule['token'], 'pattern': rule['prefix'] + data['pattern'] + rule['suffix']}

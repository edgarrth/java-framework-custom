#!/usr/bin/env python3
"""Keep demo and generated Docker recipes identical to the test infrastructure artifact."""
import argparse
from pathlib import Path

from update_wiremock_image import check as check_wiremock

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'axiz-framework/quality/axiz-test-containers/src/main/resources/pe/axiz/quality/containers/images'
DESTINATIONS = (
    ROOT / 'axiz-demo/local/images',
    ROOT / 'axiz-framework/codegen/axiz-codegen-templates/src/main/resources/axiz/templates/service/local/images',
)


def sync(check=False):
    check_wiremock()
    mismatches = []
    for name in ('postgres', 'valkey', 'kafka', 'wiremock', 'keycloak'):
        data = (SOURCE / name / 'Dockerfile').read_bytes()
        for destination in DESTINATIONS:
            target = destination / name / 'Dockerfile'
            if not target.is_file() or target.read_bytes() != data:
                mismatches.append(str(target.relative_to(ROOT)))
                if not check:
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(data)
    if check and mismatches:
        raise SystemExit('Container recipe drift: ' + ', '.join(mismatches))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    sync(parser.parse_args().check)

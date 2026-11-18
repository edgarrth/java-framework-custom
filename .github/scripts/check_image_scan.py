#!/usr/bin/env python3
"""Reject empty or mismatched image scans; no findings alone is not evidence of coverage."""
import argparse
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import re


def validate(report, expected_image, now=None, maven_lock=None):
    now = now or datetime.now(timezone.utc)
    if (report.get('SchemaVersion') != 2 or report.get('ArtifactType') != 'container_image'
            or report.get('ArtifactName') != expected_image):
        raise ValueError('The scan does not identify the expected container image')
    if not re.fullmatch(r'sha256:[0-9a-f]{64}', report.get('Metadata', {}).get('ImageID', '')):
        raise ValueError('The scan has no immutable local image identity')
    instant = datetime.fromisoformat(report['CreatedAt'].replace('Z', '+00:00'))
    if instant.tzinfo is None or not -timedelta(minutes=5) <= now - instant <= timedelta(hours=6):
        raise ValueError('The scan timestamp is absent, stale or in the future')
    results = report.get('Results')
    if not isinstance(results, list) or not results:
        raise ValueError('No component inventory: a packed or unsupported binary is not a clean scan')
    count = 0
    for result in results:
        packages = result.get('Packages', [])
        if not isinstance(packages, list):
            raise ValueError('Invalid component inventory')
        for package in packages:
            if not package.get('Name') or not package.get('Version'):
                raise ValueError('A component has no name or version')
        count += len(packages)
        for finding in result.get('Vulnerabilities', []):
            if finding.get('Severity') not in ('LOW',):
                raise ValueError('An unresolved or unclassified vulnerability blocks the image')
    if not count:
        raise ValueError('The scan inventoried no components')
    if maven_lock is not None:
        validate_maven_inventory(results, maven_lock)
    return count


def validate_maven_inventory(results, lock):
    if lock.get('schema') != 1 or not lock.get('artifacts'):
        raise ValueError('A populated Maven dependency lock is required')
    expected = {(f'{item["group"]}:{item["name"]}', item['version']) for item in lock['artifacts']}
    found = {(item['Name'], item['Version']) for result in results if result.get('Type') == 'jar'
             for item in result.get('Packages', [])}
    missing = expected - found
    if missing:
        raise ValueError('The image scan omits locked Maven dependencies: ' + repr(sorted(missing)))
    names = {name for name, _ in expected}
    obsolete = {(name, version) for name, version in found if name in names and (name, version) not in expected}
    if obsolete or any(name == 'org.wiremock:wiremock-standalone' for name, _ in found):
        raise ValueError('The image contains obsolete or opaque Maven dependencies')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('report', type=Path)
    parser.add_argument('image')
    parser.add_argument('--maven-lock', type=Path)
    args = parser.parse_args()
    try:
        lock = json.loads(args.maven_lock.read_text()) if args.maven_lock else None
        count = validate(json.loads(args.report.read_text()), args.image, maven_lock=lock)
    except (OSError, ValueError, TypeError, KeyError, AttributeError) as failure:
        raise SystemExit(f'Image security evidence rejected: {failure}') from failure
    print(f'Image inventory: {count} components; no blocking findings')


if __name__ == '__main__':
    main()

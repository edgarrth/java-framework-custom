#!/usr/bin/env python3
"""Reject empty or mismatched image scans; no findings alone is not evidence of coverage."""
import argparse
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import re
from urllib.request import Request, urlopen


def github_compare(repository, fix, revision):
    if not re.fullmatch(r'https://github.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository):
        raise ValueError('Source verification requires a GitHub repository')
    path = repository.removeprefix('https://github.com/')
    request = Request(f'https://api.github.com/repos/{path}/compare/{fix}...{revision}',
                      headers={'Accept': 'application/vnd.github+json', 'User-Agent': 'axiz-image-gate'})
    token = os.environ.get('GITHUB_TOKEN')
    if token:
        request.add_header('Authorization', 'Bearer ' + token)
    with urlopen(request, timeout=30) as response:
        return json.load(response)


def github_advisory(ghsa):
    if not re.fullmatch(r'GHSA-[a-z0-9]{4}-[a-z0-9]{4}-[a-z0-9]{4}', ghsa):
        raise ValueError('Invalid GitHub advisory identifier')
    request = Request(f'https://api.github.com/advisories/{ghsa}',
                      headers={'Accept': 'application/vnd.github+json', 'User-Agent': 'axiz-image-gate'})
    token = os.environ.get('GITHUB_TOKEN')
    if token:
        request.add_header('Authorization', 'Bearer ' + token)
    with urlopen(request, timeout=30) as response:
        return json.load(response)


def verify_fixed_ancestry(report, vex, expected_image_id, compare=github_compare, advisory=github_advisory):
    """Recompute ancestry upstream and bind the proof to the image inspected in this build."""
    if expected_image_id and report['Metadata']['ImageID'] != expected_image_id:
        raise ValueError('The scan does not match the immutable identity of the built image')
    modified = [item for result in report.get('Results', [])
                for item in result.get('ExperimentalModifiedFindings', [])]
    if not modified:
        return
    if not expected_image_id:
        raise ValueError('Fixed findings require the immutable identity of the built image')
    for statement in vex['statements']:
        evidence = statement['x_axiz_evidence']
        ghsa = statement['vulnerability']['@id'].removeprefix('https://github.com/advisories/')
        published = advisory(ghsa)
        references = {url.rstrip('/') for url in published.get('references', [])}
        if (published.get('ghsa_id') != ghsa
                or published.get('cve_id') != statement['vulnerability']['name']
                or evidence['patch'] not in references
                or evidence['advisory'].rstrip('/') not in references):
            raise ValueError('Upstream advisory does not associate this CVE with the claimed patch')
        for product in statement['products']:
            if source_repository(product['@id'], evidence['revision']) != evidence['repository']:
                raise ValueError('The verified repository does not own the inventoried component')
        actual = compare(evidence['repository'], evidence['fix'], evidence['revision'])
        if (actual.get('status') != 'ahead' or actual.get('behind_by') != 0
                or actual.get('ahead_by', 0) <= 0
                or actual.get('merge_base_commit', {}).get('sha') != evidence['fix']):
            raise ValueError('Upstream source does not contain the claimed vulnerability fix')


def source_repository(purl, revision):
    match = re.fullmatch(
        r'pkg:golang/github\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)(?:/[^@]+)?@[^@]+-([0-9a-f]{12})', purl)
    if not match or not re.fullmatch(r'[0-9a-f]{40}', revision) or match[3] != revision[:12]:
        raise ValueError('An exact Go pseudo-version and its full source commit are required')
    return f'https://github.com/{match[1]}/{match[2]}'


def validate(report, expected_image, now=None, maven_lock=None, required_types=(), vex=None, go_sources=None):
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
    workspace = workspace_components(report, go_sources) if go_sources is not None else set()
    fixed = fixed_components(vex, now) if vex is not None else set()
    for result in results:
        packages = result.get('Packages', [])
        if not isinstance(packages, list):
            raise ValueError('Invalid component inventory')
        for package in packages:
            # Go source builds include an unversioned main-module graph node. It is
            # not a dependency: the recipe pins its source archive and SHA-256.
            # Retain it in the report, but never count it as versioned inventory.
            if (result.get('Type') == 'gobinary' and package.get('Relationship') == 'root'
                    and package.get('Name') and not package.get('Version')
                    and package.get('DependsOn')):
                continue
            if not package.get('Name') or not package.get('Version'):
                if (result.get('Type') != 'gobinary' or package.get('Version')
                        or (result.get('Target'), package.get('Name')) not in workspace):
                    raise ValueError('A component has no name or version or verified workspace source')
            count += 1
        for finding in result.get('Vulnerabilities', []):
            if finding.get('Severity') not in ('LOW',):
                raise ValueError('An unresolved or unclassified vulnerability blocks the image')
        for modified in result.get('ExperimentalModifiedFindings', []):
            finding = modified.get('Finding', {})
            key = (finding.get('VulnerabilityID'), finding.get('PkgIdentifier', {}).get('PURL'))
            if (modified.get('Type') != 'vulnerability' or modified.get('Status') != 'fixed'
                    or key not in fixed):
                raise ValueError('A suppressed finding has no reviewed exact-version fix')
            if not any(package.get('Identifier', {}).get('PURL') == key[1]
                       for package in packages):
                raise ValueError('A fixed component is missing from the original inventory')
    if not count:
        raise ValueError('The scan inventoried no components')
    for required in required_types:
        if not any(result.get('Type') == required and any(p.get('Name') and p.get('Version')
                   for p in result.get('Packages', [])) for result in results):
            raise ValueError('The image scan omits required component type: ' + required)
    if maven_lock is not None:
        validate_maven_inventory(results, maven_lock)
    return count


def workspace_components(report, source_bytes):
    """Go workspace modules belong to the pinned source release, not a separate module tag."""
    import hashlib
    labels = report.get('Metadata', {}).get('ImageConfig', {}).get('config', {}).get('Labels', {})
    if labels.get('pe.axiz.go-sources-sha256') != hashlib.sha256(source_bytes).hexdigest():
        raise ValueError('The source inventory does not match the built image')
    result = set()
    for source in json.loads(source_bytes):
        name, version = source['name'], source['version']
        owner = 'prometheus' if name == 'prometheus' else 'grafana'
        if (not re.fullmatch(r'[a-z]+', name) or not re.fullmatch(r'\d+\.\d+\.\d+', version)
                or not re.fullmatch(r'[0-9a-f]{64}', source['sha256'])
                or source['url'] != f'https://codeload.github.com/{owner}/{name}/tar.gz/refs/tags/v{version}'):
            raise ValueError('Invalid versioned source archive')
        for module in source.get('workspaceModules', []):
            path = module['path']
            if (not module['module'].startswith(f'github.com/grafana/{name}/')
                    or not re.fullmatch(r'[a-zA-Z0-9_/-]+', path)
                    or path.startswith('/') or '..' in path.split('/')
                    or not re.fullmatch(r'[0-9a-f]{64}', module['goModSha256'])):
                raise ValueError('Invalid source workspace module')
            for identity in (module['module'], './' + path):
                key = ('usr/local/bin/' + name, identity)
                if key in result:
                    raise ValueError('Duplicate source workspace module')
                result.add(key)
    return result


def fixed_components(vex, now):
    """Accept only exact-version fixes with source ancestry evidence, never risk waivers."""
    if (vex.get('@context') != 'https://openvex.dev/ns/v0.2.0'
            or not vex.get('author') or not vex.get('statements')):
        raise ValueError('Invalid reviewed OpenVEX document')
    issued = datetime.fromisoformat(vex['timestamp'].replace('Z', '+00:00'))
    review = datetime.fromisoformat(vex['x_axiz_review_by'].replace('Z', '+00:00'))
    if (issued.tzinfo is None or review.tzinfo is None or issued > now or now >= review
            or not timedelta(0) < review - issued <= timedelta(days=60)):
        raise ValueError('The VEX source review is expired or not yet valid')
    fixed = set()
    for statement in vex['statements']:
        evidence = statement.get('x_axiz_evidence', {})
        fix = evidence.get('fix', '')
        revision = evidence.get('revision', '')
        if (statement.get('status') != 'fixed' or not statement.get('impact_statement')
                or not re.fullmatch(r'[0-9a-f]{40}', fix)
                or not re.fullmatch(r'[0-9a-f]{40}', revision)
                or evidence.get('merge_base') != fix or evidence.get('behind_by') != 0
                or evidence.get('status') != 'ahead' or evidence.get('ahead_by', 0) <= 0
                or not evidence.get('repository', '').startswith('https://github.com/')):
            raise ValueError('VEX requires a source revision containing the upstream fix')
        cve = statement['vulnerability']['name']
        if not re.fullmatch(r'CVE-\d{4}-\d{4,}', cve) or not statement.get('products'):
            raise ValueError('VEX requires a specific vulnerability and product')
        advisory_id = statement['vulnerability'].get('@id', '')
        if (not re.fullmatch(r'https://github.com/advisories/GHSA-[a-z0-9]{4}-[a-z0-9]{4}-[a-z0-9]{4}', advisory_id)
                or evidence.get('advisory_patch_reference') != advisory_id
                or evidence.get('patch') != evidence['repository'] + '/commit/' + fix
                or not re.fullmatch(r'https://[A-Za-z0-9.-]+/[^\s?#]+', evidence.get('advisory', ''))):
            raise ValueError('VEX requires exact advisory and patch references')
        for product in statement['products']:
            purl = product.get('@id', '')
            if (not purl.startswith('pkg:golang/') or '@' not in purl
                    or source_repository(purl, revision) != evidence['repository'] or '*' in purl
                    or product.get('subcomponents')):
                raise ValueError('VEX must identify the exact fixed Go pseudo-version')
            key = (cve, purl)
            if key in fixed:
                raise ValueError('Duplicate VEX fix')
            fixed.add(key)
    return fixed


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
    parser.add_argument('--require-type', action='append', default=[])
    parser.add_argument('--vex', type=Path)
    parser.add_argument('--go-sources', type=Path)
    parser.add_argument('--expected-image-id')
    args = parser.parse_args()
    try:
        lock = json.loads(args.maven_lock.read_text()) if args.maven_lock else None
        vex = json.loads(args.vex.read_text()) if args.vex else None
        report = json.loads(args.report.read_text())
        count = validate(report, args.image, maven_lock=lock,
                         required_types=args.require_type, vex=vex,
                         go_sources=args.go_sources.read_bytes() if args.go_sources else None)
        verify_fixed_ancestry(report, vex, args.expected_image_id)
    except (OSError, ValueError, TypeError, KeyError, AttributeError) as failure:
        raise SystemExit(f'Image security evidence rejected: {failure}') from failure
    print(f'Image inventory: {count} components; no blocking findings')


if __name__ == '__main__':
    main()

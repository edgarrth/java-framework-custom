#!/usr/bin/env python3
"""The aggregated coverage report covers the whole framework.

  check-coverage-aggregate.py <framework root>
      every module with production code is a `provided` dependency of axiz-coverage-report
      (JaCoCo's report-aggregate takes classes only from compile, runtime and provided
      dependencies; a `test` dependency adds execution data and nothing to measure it against)
      test-only modules must also be included as `test` dependencies for their execution data
  check-coverage-aggregate.py --report <jacoco.xml>
      an aggregated report holds classes and counters (an empty one fails)
"""
import os
import re
import sys
from defusedxml import ElementTree as ET

REPORT_POM = 'quality/axiz-coverage-report/pom.xml'


def modules_with_code(root, source_directory='main'):
    found = set()
    for dirpath, dirnames, files in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in ('target', '.git', 'node_modules', 'templates')]
        if 'pom.xml' not in files or os.path.abspath(dirpath) == os.path.abspath(root):
            continue
        with open(os.path.join(dirpath, 'pom.xml'), encoding='utf-8') as source:
            pom = source.read()
        own = re.sub(r'<parent>.*?</parent>', '', pom, flags=re.S)
        packaging = re.search(r'<packaging>(.*?)</packaging>', own)
        if packaging and packaging.group(1) != 'jar':
            continue
        main = os.path.join(dirpath, 'src', source_directory, 'java')
        has_code = any(f.endswith('.java') and f not in ('package-info.java', 'module-info.java')
                       for _, _, fs in os.walk(main) for f in fs)
        if has_code:
            found.add(re.search(r'<artifactId>(.*?)</artifactId>', own).group(1))
    return found


def check_inventory(root):
    pom = ET.parse(os.path.join(root, REPORT_POM)).getroot()
    deps = {dependency.findtext('{*}artifactId'): dependency.findtext('{*}scope', 'compile')
            for dependency in pom.findall('{*}dependencies/{*}dependency')
            if dependency.findtext('{*}groupId') == 'pe.axiz'}
    errors = 0
    production = modules_with_code(root)
    for artifact in sorted(production):
        if artifact not in deps:
            print(f'::error::{REPORT_POM}: {artifact} has code but is not in the aggregated report')
            errors += 1
        elif deps[artifact] != 'provided':
            print(f'::error::{REPORT_POM}: {artifact} has scope {deps[artifact]}, use provided '
                  '(test dependencies give no classes to the report)')
            errors += 1
    for artifact in sorted(modules_with_code(root, 'test') - production):
        if deps.get(artifact) != 'test':
            print(f'::error::{REPORT_POM}: test-only module {artifact} must be included '
                  'with scope test so its execution data reaches the report')
            errors += 1
    print(f'{len(deps)} modules in the aggregated report, {errors} problems')
    return errors


def check_report(path, root=None):
    if not os.path.isfile(path):
        print(f'::error::{path} missing')
        return 1
    # Trusted build POM/report, after executing the same checkout; no request XML is parsed.
    # nosemgrep: python.lang.security.use-defused-xml-parse.use-defused-xml-parse
    tree = ET.parse(path).getroot()
    packages = tree.findall('.//package')
    lines = [c for c in tree.findall('counter') if c.get('type') == 'LINE']
    covered = sum(int(c.get('covered', 0)) + int(c.get('missed', 0)) for c in lines)
    if not packages or covered == 0:
        print(f'::error::{path}: the aggregated report has no classes or no line counters')
        return 1
    groups = tree.findall('group')
    errors = 0
    if not groups:
        print(f'::error::{path}: no module groups; aggregate percentages cannot replace module gates')
        return 1
    names = [group.get('name') for group in groups]
    if len(names) != len(set(names)):
        print('::error::Duplicate module groups in coverage report')
        errors += 1
    if root is not None:
        for missing in sorted(modules_with_code(root) - set(names)):
            print(f'::error::Missing coverage module {missing}')
            errors += 1
    for group in groups:
        counters = {counter.get('type'): counter for counter in group.findall('counter')}
        for kind, minimum in (('LINE', 80), ('BRANCH', 70)):
            counter = counters.get(kind)
            if counter is None:
                continue  # Ports with no executable instructions/branches have no denominator.
            hit, missed = int(counter.get('covered')), int(counter.get('missed'))
            if hit < 0 or missed < 0:
                raise ValueError('Negative coverage counter')
            total = hit + missed
            if total and hit * 100 < minimum * total:
                print(f'::error::{group.get("name")}: {kind} {hit}/{total} below {minimum}%')
                errors += 1
    print(f'{len(groups)} modules, {len(packages)} packages, {covered} lines; {errors} coverage violations')
    return errors


if __name__ == '__main__':
    if len(sys.argv) in (3, 4) and sys.argv[1] == '--report':
        sys.exit(1 if check_report(sys.argv[2], sys.argv[3] if len(sys.argv) == 4 else None) else 0)
    sys.exit(1 if check_inventory(sys.argv[1] if len(sys.argv) > 1 else '.') else 0)

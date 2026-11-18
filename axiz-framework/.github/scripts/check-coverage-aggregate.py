#!/usr/bin/env python3
"""The aggregated coverage report covers the whole framework.

  check-coverage-aggregate.py <framework root>
      every module with production code is a `provided` dependency of axiz-coverage-report
      (JaCoCo's report-aggregate takes classes only from compile, runtime and provided
      dependencies; a `test` dependency adds execution data and nothing to measure it against)
  check-coverage-aggregate.py --report <jacoco.xml>
      an aggregated report holds classes and counters (an empty one fails)
"""
import os
import re
import sys
from defusedxml import ElementTree as ET

REPORT_POM = 'quality/axiz-coverage-report/pom.xml'


def modules_with_code(root):
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
        main = os.path.join(dirpath, 'src', 'main', 'java')
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
    for artifact in sorted(modules_with_code(root)):
        if artifact not in deps:
            print(f'::error::{REPORT_POM}: {artifact} has code but is not in the aggregated report')
            errors += 1
        elif deps[artifact] != 'provided':
            print(f'::error::{REPORT_POM}: {artifact} has scope {deps[artifact]}, use provided '
                  '(test dependencies give no classes to the report)')
            errors += 1
    print(f'{len(deps)} modules in the aggregated report, {errors} problems')
    return errors


def check_report(path):
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
    print(f'{len(packages)} packages, {covered} lines in the aggregated report')
    return 0


if __name__ == '__main__':
    if len(sys.argv) == 3 and sys.argv[1] == '--report':
        sys.exit(1 if check_report(sys.argv[2]) else 0)
    sys.exit(1 if check_inventory(sys.argv[1] if len(sys.argv) > 1 else '.') else 0)

"""Fail on encapsulation findings of any reported priority and on broad detector exclusions."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

PATTERNS = {'EI_EXPOSE_REP', 'EI_EXPOSE_REP2', 'CT_CONSTRUCTOR_THROW'}


def filter_errors(path):
    # Trusted checked-in build configuration, never service/request XML.
    # Trusted build POM/report, after executing the same checkout; no request XML is parsed.
    # nosemgrep: python.lang.security.use-defused-xml-parse.use-defused-xml-parse
    root = ET.parse(path).getroot()
    for node in root.iter():
        node.tag = node.tag.rsplit('}', 1)[-1]
    errors = []
    for match in root.findall('Match'):
        # Generated-code exceptions must use the exact Axiz marker or known generated paths.
        children = list(match)
        if len(children) == 1 and (
                (children[0].tag == 'Annotation' and children[0].get('name') == 'pe.axiz.core.commons.Generated')
                or (children[0].tag == 'Source' and children[0].get('name') == r'~.*[\\/]generated-sources[\\/].*')
                or (children[0].tag == 'Class' and children[0].get('name') == r'~.*__(BeanDefinitions|BeanFactoryRegistrations|TestContext\d+_.*)')):
            continue
        bug = match.find('Bug')
        if bug is None or set(bug.attrib) != {'pattern'} or len(children) != 3:
            errors.append('exclusions must name bug patterns, an exact class and a member')
            continue
        cls = match.find('Class')
        member = match.find('Method')
        if member is None:
            member = match.find('Field')
        if (cls is None or member is None or not cls.get('name') or not member.get('name')
                or cls.get('name', '').startswith('~') or member.get('name', '').startswith('~')
                or any(n.tag not in {'Match', 'Class', 'Bug', 'Field', 'Method'} for n in match.iter())):
            errors.append('encapsulation exclusions require an exact class and member')
        if 'CT_CONSTRUCTOR_THROW' in bug.get('pattern', '') and (
                member is None or member.tag != 'Method' or member.get('name') != '<init>'
                or 'params' not in member.attrib or member.get('returns') != 'void'):
            errors.append('constructor exclusions require an exact constructor signature')
    return errors


def check(root):
    root = Path(root)
    failures = []
    files = sorted(root.glob('**/target/spotbugsXml.xml'))
    if not files:
        failures.append('No SpotBugs reports found: run Maven with -Pquality first')
    for path in files:
        # Local SpotBugs output produced by the preceding build, not external XML input.
        # Trusted build POM/report, after executing the same checkout; no request XML is parsed.
        # nosemgrep: python.lang.security.use-defused-xml-parse.use-defused-xml-parse
        for bug in ET.parse(path).getroot().findall('BugInstance'):
            if bug.get('type') in PATTERNS:
                failures.append(f"{path}: {bug.get('type')} in {bug.find('Class').get('classname')}")
    filters = list(root.glob('**/src/main/resources/axiz/spotbugs/exclude.xml'))
    filters += [p for p in root.glob('**/spotbugs-exclude.xml') if 'target' not in p.parts]
    for path in filters:
        failures.extend(f'{path}: {error}' for error in filter_errors(path))
    for failure in failures:
        print(f'::error::{failure}')
    print(f'Encapsulation: {len(files)} SpotBugs reports, {len(failures)} errors')
    return int(bool(failures))


if __name__ == '__main__':
    sys.exit(check(sys.argv[1]))

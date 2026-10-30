"""PIT thresholds must not hide a broken worker JVM. Timeouts can legitimately detect loops."""
from collections import Counter
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

ERRORS = {'RUN_ERROR', 'MEMORY_ERROR', 'NOT_STARTED', 'STARTED'}


def check(root):
    totals = Counter()
    files = sorted(Path(root).glob('**/target/pit-reports/mutations.xml'))
    for path in files:
        # Trusted build POM/report, after executing the same checkout; no request XML is parsed.
        # nosemgrep: python.lang.security.use-defused-xml-parse.use-defused-xml-parse
        statuses = Counter(m.get('status') for m in ET.parse(path).getroot().findall('mutation'))
        totals.update(statuses)
        errors = {k: v for k, v in statuses.items() if k in ERRORS}
        if errors:
            print(f'::error::{path}: failed mutation processes: {errors}')
    print(f'Mutation reports: {len(files)}; statuses: {dict(totals)}')
    return 1 if any(totals[s] for s in ERRORS) else 0


if __name__ == '__main__':
    sys.exit(check(sys.argv[1]))

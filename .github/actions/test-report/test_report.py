"""Summary of the Surefire and Failsafe XML reports under the working directory (Markdown)."""

import glob
import os
import subprocess
from defusedxml import ElementTree as ET
from collections import defaultdict


def jdk() -> str:
    try:
        out = subprocess.run(["java", "-version"], capture_output=True, text=True, check=False)
        lines = [line for line in (out.stderr or out.stdout).splitlines() if not line.startswith("Picked up")]
        return lines[0].strip()
    except (OSError, IndexError):
        return "unknown"


def main() -> None:
    totals = defaultdict(lambda: {"tests": 0, "failures": 0, "errors": 0, "skipped": 0})
    for path in glob.glob("**/target/*-reports/TEST-*.xml", recursive=True):
        kind = "IT" if "/failsafe-reports/" in path else "unit"
        module = path.split("/target/")[0] or "."
        try:
            # Trusted build POM/report, after executing the same checkout; no request XML is parsed.
            # nosemgrep: python.lang.security.use-defused-xml-parse.use-defused-xml-parse
            root = ET.parse(path).getroot()
        except ET.ParseError:
            totals[(module, kind)]["errors"] += 1
            continue
        suites = [root] if root.tag == "testsuite" else root.findall("testsuite")
        for s in suites:
            for key in ("tests", "failures", "errors", "skipped"):
                totals[(module, kind)][key] += int(s.get(key, "0") or 0)

    print("### Test results")
    print()
    print(f"- commit: `{os.environ.get('GITHUB_SHA', 'local')}`")
    print(f"- JDK: `{jdk()}`")
    print(f"- command: `{os.environ.get('COMMAND', '')}`")
    print()
    if not totals:
        print("No Surefire or Failsafe reports: no test ran (or the build stopped before them).")
        return
    passed_all = sum(t["tests"] - t["failures"] - t["errors"] - t["skipped"] for t in totals.values())
    failed_all = sum(t["failures"] + t["errors"] for t in totals.values())
    skipped_all = sum(t["skipped"] for t in totals.values())
    print(f"**{passed_all} passed, {failed_all} failed, {skipped_all} skipped**")
    print()
    print("| Module | Kind | Tests | Passed | Failed | Errors | Skipped |")
    print("|---|---|---:|---:|---:|---:|---:|")
    for (module, kind), t in sorted(totals.items(), key=lambda i: (-(i[1]["failures"] + i[1]["errors"]), i[0])):
        passed = t["tests"] - t["failures"] - t["errors"] - t["skipped"]
        print(f"| {module} | {kind} | {t['tests']} | {passed} | {t['failures']} | {t['errors']} | {t['skipped']} |")


if __name__ == "__main__":
    main()

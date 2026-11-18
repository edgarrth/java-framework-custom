"""Require executed, successful architecture and self-check cases from fresh Surefire output."""
import argparse
import json
from pathlib import Path
import xml.etree.ElementTree as ET

REQUIRED = ("ArchitectureTest", "ArchitectureSelfCheckTest")


def prepare(module: Path) -> None:
    """Remove only prior generated Surefire XML; preserve other build evidence and inputs."""
    module = module.resolve()
    directory = module / "target" / "surefire-reports"
    if directory.resolve() != directory or directory.is_symlink():
        raise ValueError("Surefire report directory must not redirect outside the module")
    for report in directory.glob("TEST-*.xml"):
        report.unlink()


def evaluate(module: Path) -> dict:
    directory = module.resolve() / "target" / "surefire-reports"
    results = {name: {"executed": 0, "skipped": 0, "failed": 0} for name in REQUIRED}
    reports = sorted(directory.glob("TEST-*.xml"))
    if not reports:
        raise ValueError("Surefire reports are absent; architecture execution is unproven")
    for report in reports:
        if report.is_symlink() or report.resolve().parent != directory:
            raise ValueError("Surefire report must be a regular file inside its report directory")
        if report.stat().st_size > 16 * 1024 * 1024:
            raise ValueError("Surefire report exceeds the parsing limit")
        text = report.read_bytes()
        if b"<!DOCTYPE" in text.upper() or b"<!ENTITY" in text.upper():
            raise ValueError("DTD/entity declarations are forbidden in Surefire reports")
        root = ET.fromstring(text)
        if root.tag not in ("testsuite", "testsuites"):
            raise ValueError("Unexpected Surefire document")
        for case in root.iter("testcase"):
            name = case.get("classname", "").rsplit(".", 1)[-1]
            if name not in results:
                continue
            if not case.get("name"):
                raise ValueError("Architecture test case has no name")
            result = results[name]
            if case.find("skipped") is not None:
                result["skipped"] += 1
            elif case.find("failure") is not None or case.find("error") is not None:
                result["failed"] += 1
            else:
                result["executed"] += 1
        for suite in root.iter("testsuite"):
            name = suite.get("name", "").rsplit(".", 1)[-1]
            if name in results:
                for attr in ("failures", "errors", "skipped"):
                    if int(suite.get(attr, "0")) != 0:
                        raise ValueError(f"{name} reports {attr}; architecture gate failed")
    for name, result in results.items():
        if result["executed"] < 1 or result["skipped"] or result["failed"]:
            raise ValueError(f"{name} must execute at least one case with no failures or omissions")
    return {"schema": "axiz.architecture-execution/v1", "decision": "PASS", "tests": results}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("module", type=Path)
    parser.add_argument("--prepare", action="store_true")
    args = parser.parse_args()
    try:
        if args.prepare:
            prepare(args.module)
            return
        print(json.dumps(evaluate(args.module), sort_keys=True))
    except (ValueError, OSError, ET.ParseError) as failure:
        raise SystemExit(f"Architecture execution rejected: {failure}") from None


if __name__ == "__main__":
    main()

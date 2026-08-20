#!/usr/bin/env python3
"""Fail if a Gradle test task produced zero test cases.

Gradle prints `Task :core:test NO-SOURCE` and exits 0 when a module has no tests. That is a
zero denominator: nothing was examined. This project does not allow a zero denominator to be
reported as green, so this turns it into a build failure.

Denominator reported: number of JUnit XML result files parsed, and total test cases in them.
"""
from __future__ import annotations

import argparse
import glob
import os
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--min-tests", type=int, default=1)
    ap.add_argument("--root", default=ROOT)
    args = ap.parse_args()

    pattern = os.path.join(args.root, "**", "build", "test-results", "**", "TEST-*.xml")
    files = sorted(glob.glob(pattern, recursive=True))

    total = failures = errors = skipped = 0
    for f in files:
        try:
            r = ET.parse(f).getroot()
        except ET.ParseError:
            continue
        suites = [r] if r.tag == "testsuite" else list(r.iter("testsuite"))
        for s in suites:
            total += int(s.get("tests", 0))
            failures += int(s.get("failures", 0))
            errors += int(s.get("errors", 0))
            skipped += int(s.get("skipped", 0))

    print(f"test-result files parsed : {len(files)}")
    print(f"test cases executed      : {total}")
    print(f"  failures={failures} errors={errors} skipped={skipped}")

    if total < args.min_tests:
        print(f"\nZERO_DENOMINATOR: {total} test cases ran (min {args.min_tests}). "
              f"A suite that ran nothing is NOT-MEASURED, not green.", file=sys.stderr)
        return 1
    if failures or errors:
        print("\nTest failures present.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

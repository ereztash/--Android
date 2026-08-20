#!/usr/bin/env python3
"""GATE-XML-1 -- every XML resource in the repository parses.

This exists because it caught a real bug during M2. An `AndroidManifest.xml` comment used
`--` as an em dash, which XML forbids inside a comment. The manifest merger's error was a bare
"Error parsing AndroidManifest.xml" with no line number, and it surfaced only after several
minutes of build, behind an unrelated dependency-resolution failure that masked it entirely.

A 200 ms check at the top of the build says exactly which file and which line.
"""
from __future__ import annotations

import argparse
import os
import sys
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gatelib import Detector, Finding, GateResult, report  # noqa: E402

DEFAULT_EXCLUDE_DIRS = {".git", ".gradle", ".idea", ".kotlin", "build", "out"}


def scan(root: str, exclude_controls: bool) -> Detector:
    det = Detector(name="xml", unit="XML files", denominator=0)
    excludes = set(DEFAULT_EXCLUDE_DIRS)
    if exclude_controls:
        excludes.add(os.path.join("tools", "positive_controls"))
    for dirpath, dirnames, filenames in os.walk(root):
        rel_dir = os.path.relpath(dirpath, root)
        rel_dir = "" if rel_dir == "." else rel_dir
        dirnames[:] = [
            d for d in dirnames
            if d not in excludes
            and os.path.normpath(os.path.join(rel_dir, d)) not in excludes
        ]
        for fn in filenames:
            if not fn.endswith(".xml"):
                continue
            path = os.path.join(dirpath, fn)
            det.denominator += 1
            try:
                ET.parse(path)
            except ET.ParseError as exc:
                det.findings.append(Finding(
                    "xml", os.path.relpath(path, root), getattr(exc, "position", (0, 0))[0],
                    str(exc), "xml.parse_error"))
    if det.denominator == 0:
        det.notes.append("no XML files found; NOT-MEASURED, not PASS")
    return det


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--strict", action="store_true")
    ap.add_argument("--include-controls", action="store_true",
                    help="also scan tools/positive_controls (used by the control run)")
    args = ap.parse_args()
    result = GateResult(
        gate="GATE-XML-1",
        description="every XML resource parses",
        detectors=[scan(os.path.abspath(args.root), not args.include_controls)],
        not_covered=[
            "Well-formedness only. Does not validate against the Android resource schema, "
            "check that referenced resources exist, or verify attribute semantics -- aapt2 "
            "does that later in the build.",
            "Binary XML inside a built APK is not checked here; see GATE-MANIFEST-1.",
        ],
    )
    return report(result, args.json, args.strict)


if __name__ == "__main__":
    raise SystemExit(main())

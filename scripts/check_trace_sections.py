#!/usr/bin/env python3
"""GATE-TRACE-1 -- the benchmark measures section names the app actually emits.

`TraceSectionMetric` given a section name that nothing emits reports **zero measurements, not
an error**. A latency run producing no data looks, in a summary, exactly like a latency run
that passed. That failure mode is silent, permanent, and would make the entire M7 harness
decorative.

So the two sides are pinned together: the constants `CorrectionController` emits and the
constants `KeystrokeLatencyBenchmark` asks for must be the same set. Renaming either alone
fails the build.

Denominator: the number of section names found on each side.
"""
from __future__ import annotations

import argparse
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gatelib import Detector, Finding, GateResult, report  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

EMITTER = os.path.join(
    ROOT, "app", "src", "main", "kotlin", "com", "hebrewime", "ime", "correction",
    "CorrectionController.kt",
)
CONSUMER = os.path.join(
    ROOT, "benchmark", "src", "main", "kotlin", "com", "hebrewime", "benchmark",
    "KeystrokeLatencyBenchmark.kt",
)

# Both sides declare their names as `const val ... = "HebrewIme.something"`.
NAME_RE = re.compile(r'"(HebrewIme\.[A-Za-z0-9_]+)"')


def names_in(path: str) -> set[str]:
    if not os.path.isfile(path):
        return set()
    return set(NAME_RE.findall(open(path, encoding="utf-8").read()))


def scan(emitter: str, consumer: str, inject: bool) -> Detector:
    det = Detector(name="trace_sections", unit="trace section names", denominator=0)

    emitted = names_in(emitter)
    requested = names_in(consumer)
    if inject:
        # PLANTED DEFECT: the benchmark asks for a section the app never emits, which is what
        # a rename on one side alone looks like.
        requested = {n + "_RENAMED" for n in requested}

    if not emitted:
        det.notes.append(f"no section names found in {os.path.relpath(emitter, ROOT)}; "
                         "NOT-MEASURED")
        return det
    if not requested:
        det.notes.append(f"no section names found in {os.path.relpath(consumer, ROOT)}; "
                         "NOT-MEASURED")
        return det

    det.denominator = len(emitted | requested)
    det.notes.append(f"app emits: {sorted(emitted)}")
    det.notes.append(f"benchmark requests: {sorted(requested)}")

    for name in sorted(requested - emitted):
        det.findings.append(Finding(
            "trace_sections", os.path.relpath(consumer, ROOT), 0,
            f"benchmark measures '{name}' but the app never emits it -- "
            f"TraceSectionMetric would silently report zero measurements",
            "trace.unmatched_request"))
    for name in sorted(emitted - requested):
        det.findings.append(Finding(
            "trace_sections", os.path.relpath(emitter, ROOT), 0,
            f"app emits '{name}' but no benchmark measures it",
            "trace.unmeasured_section"))
    return det


NOT_COVERED = [
    "Matches names as string literals. A section name assembled at runtime would not be seen.",
    "Does not prove the sections are actually entered when a key is pressed -- only that both "
    "sides agree on the names. Whether the traced code path runs needs a device.",
    "Does not check that the benchmark's host app is installed, that the IME is enabled and "
    "selected, or that any of it produces a number. All of that is NOT RUN.",
]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--strict", action="store_true")
    ap.add_argument("--inject-defect", action="store_true",
                    help="PLANT A DEFECT: rename the benchmark's requested sections.")
    args = ap.parse_args()

    result = GateResult(
        gate="GATE-TRACE-1",
        description="the benchmark measures trace sections the app actually emits",
        detectors=[scan(EMITTER, CONSUMER, args.inject_defect)],
        not_covered=NOT_COVERED,
    )
    return report(result, args.json, args.strict)


if __name__ == "__main__":
    raise SystemExit(main())

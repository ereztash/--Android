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


# A traced region: `Trace.beginSection(...)` through its matching `Trace.endSection()`.
# Anything that suspends in between is the defect.
BEGIN_RE = re.compile(r"Trace\.beginSection\s*\(")
END_RE = re.compile(r"Trace\.endSection\s*\(")
# Kotlin has no marker at a call site, so this looks for the calls that actually suspend in
# this file. Named explicitly rather than guessed: a heuristic over every call would flag the
# whole file, and a gate that cries wolf gets suppressed.
SUSPENDING_CALLS = re.compile(
    r"\b(readPersonalDictionary|readUserModel|withContext|delay|await|join)\s*\(")


def scan_balance(path: str, inject: bool) -> Detector:
    """No suspending call may sit inside a traced region.

    ### Why this gate exists
    `Trace.beginSection`/`endSection` are **per-thread**. `CorrectionController.warmUp` runs on
    `Dispatchers.Default` and opened a section around the whole load, with
    `readPersonalDictionary()` and `readUserModel()` -- both `suspend` -- inside it. A coroutine
    that suspends can resume on a different worker, so `endSection()` could run on a thread that
    never called `beginSection()`: the original thread's section stays open forever and the
    resuming thread closes one it does not own. Every measurement after it in the same trace is
    then wrong, not just this one.

    `GATE-TRACE-1` could not see this. It checks that both sides agree on the section NAMES,
    which they did, perfectly, the whole time.

    Found by Android lint's `UnclosedTrace`. This gate exists so the next traced region is
    checked by the build rather than by whoever remembers to read a lint report.
    """
    det = Detector(name="trace_balance", unit="traced regions", denominator=0)
    if not os.path.isfile(path):
        det.notes.append("emitter not found; NOT-MEASURED")
        return det
    text = open(path, encoding="utf-8").read()
    if inject:
        # PLANTED DEFECT: the exact shape that was there, restored.
        text += ("\n    private suspend fun broken() {\n"
                 "        Trace.beginSection(TRACE_LOAD)\n"
                 "        try { readUserModel() } finally { Trace.endSection() }\n    }\n")
    stripped = re.sub(r"/\*.*?\*/", "", text, flags=re.DOTALL)
    stripped = re.sub(r"//.*", "", stripped)

    starts = [m.end() for m in BEGIN_RE.finditer(stripped)]
    ends = [m.start() for m in END_RE.finditer(stripped)]
    det.denominator = len(starts)
    if len(starts) != len(ends):
        det.findings.append(Finding(
            "trace_balance", os.path.relpath(path, ROOT), 0,
            f"{len(starts)} beginSection calls and {len(ends)} endSection calls. An "
            f"unbalanced trace section corrupts every measurement after it.",
            "trace.unbalanced"))
        return det

    for begin, end in zip(starts, ends):
        if end < begin:
            continue
        region = stripped[begin:end]
        for m in SUSPENDING_CALLS.finditer(region):
            line = stripped[:begin + m.start()].count("\n") + 1
            det.findings.append(Finding(
                "trace_balance", os.path.relpath(path, ROOT), line,
                f"{m.group(1)}() suspends, and it sits inside a traced region. Trace sections "
                f"are per-thread; a coroutine that resumes on another worker will call "
                f"endSection on a thread that never called beginSection.",
                "trace.suspend_inside_section"))
    det.notes.append(f"{len(starts)} traced regions, none containing a suspending call")
    return det


NOT_COVERED = [
    "Matches names as string literals. A section name assembled at runtime would not be seen.",
    "Does not prove the sections are actually entered when a key is pressed -- only that both "
    "sides agree on the names. Whether the traced code path runs needs a device.",
    "Does not check that the benchmark's host app is installed, that the IME is enabled and "
    "selected, or that any of it produces a number. All of that is NOT RUN.",
    "The balance check knows which calls suspend from a NAMED LIST. A new suspending helper "
    "called inside a traced region would not be seen until its name is added -- Kotlin marks "
    "suspension at the declaration, not the call site, and a heuristic over every call would "
    "flag the whole file and get itself suppressed.",
    "Checks one file, the only one that emits sections today. It does not scan the tree.",
]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--strict", action="store_true")
    ap.add_argument("--inject-defect", choices=["names", "balance"],
                    help="PLANT A DEFECT. Positive control; must go red.")
    args = ap.parse_args()

    result = GateResult(
        gate="GATE-TRACE-1",
        description="the benchmark measures trace sections the app actually emits, and no traced region contains a suspending call",
        detectors=[
            scan(EMITTER, CONSUMER, args.inject_defect == "names"),
            scan_balance(EMITTER, args.inject_defect == "balance"),
        ],
        not_covered=NOT_COVERED,
    )
    return report(result, args.json, args.strict)


if __name__ == "__main__":
    raise SystemExit(main())

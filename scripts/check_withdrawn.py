#!/usr/bin/env python3
"""GATE-WITHDRAWN-1 — a layer withdrawn on evidence stays withdrawn.

### Why this exists
Two layers in this project have been withdrawn against a **pre-registered** stopping rule:

  * the **distance-2 (skip) table** — 0.11 recall points for 387,300 bytes;
  * the **adjacent real-word error detector** — precision bounded at [12.5%, 39.7%] against a
    rule, registered before the labels existed, that calls anything under 40% withdrawable.

Both withdrawals live as an *absence*: a constructor that is no longer called, a default left
at `null`, a config value left at 0. **An absence is the easiest thing in a codebase to undo by
accident.** Someone adding a feature, resolving a merge, or "restoring" what looks like a
missing wire-up would put either back with no test going red, because nothing asserts the
silence.

`docs/DEFINITION_OF_DONE.md` P7 exists to say that no shipped feature sits below its own
stopping rule. This gate is what makes P7 checkable instead of remembered.

### What it does NOT cover
It reads the shipped source. It cannot see a layer re-enabled through reflection, through a
build variant it does not scan, or through a constructor renamed to something this file does
not know about.
"""
from __future__ import annotations

import argparse
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gatelib import Detector, Finding, GateResult, report  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# The shipped path. `:core` deliberately still constructs these -- the measurements have to stay
# reproducible -- so scanning the whole tree would flag the evidence for the withdrawal.
SHIPPED = os.path.join(ROOT, "app", "src", "main")

WITHDRAWN = [
    {
        "name": "RealWordErrorDetector",
        "pattern": re.compile(r"\bRealWordErrorDetector\s*\("),
        "why": "precision bounded at [12.5%, 39.7%] on 320 blind-labelled firings against a "
               "40% rule registered before the labels existed; 4.83 false alarms per true "
               "positive; withdrawn 2026-08-25. See docs/LABELING_LOG.md.",
    },
    {
        "name": "skipgram table",
        "pattern": re.compile(r"\bskip\s*=|\bskipMargin\s*=\s*[1-9]"),
        "why": "0.11 recall points for 387,300 bytes, and it spoke twice in 1.8 million words "
               "of clean text. See docs/CONFUSION_MEASUREMENTS.md.",
    },
]


def scan(inject: bool) -> Detector:
    det = Detector(name="withdrawn_layers", unit="shipped Kotlin source file", denominator=0)
    files = []
    for dirpath, _dirs, names in os.walk(SHIPPED):
        for n in names:
            if n.endswith(".kt"):
                files.append(os.path.join(dirpath, n))
    files.sort()
    det.denominator = len(files)

    for path in files:
        text = open(path, encoding="utf-8").read()
        if inject and path.endswith("CorrectionController.kt"):
            text += "\n// planted control\nval x = RealWordErrorDetector(a.lexicon, a.bigrams)\n"
        # Comments are where the withdrawal is EXPLAINED, so they must not trip the gate.
        stripped = re.sub(r"/\*.*?\*/", "", text, flags=re.DOTALL)
        stripped = re.sub(r"//.*", "", stripped)
        for w in WITHDRAWN:
            for m in w["pattern"].finditer(stripped):
                line = stripped[:m.start()].count("\n") + 1
                det.findings.append(Finding(
                    "withdrawn_layers", os.path.relpath(path, ROOT), line,
                    f"{w['name']} is constructed in the shipped path. It was withdrawn: "
                    f"{w['why']} Re-enabling it is a decision that belongs in "
                    f"docs/DEFINITION_OF_DONE.md with a date and an owner, not in a diff.",
                    "withdrawn.reenabled"))
    if not det.findings:
        det.notes.append(
            f"{len(files)} shipped source files; neither withdrawn layer is constructed in any "
            f"of them. :core still constructs them and that is deliberate -- the measurements "
            f"stay reproducible.")
    return det


NOT_COVERED = [
    "Reads source. A layer re-enabled through reflection would not be seen.",
    "Scans app/src/main only. A layer re-enabled from another module that :app depends on, or "
    "from a build variant outside that tree, would not be seen.",
    "Matches constructor names as literals. A renamed or wrapped constructor would not be seen "
    "until its name is added here.",
    "Says nothing about whether withdrawing was RIGHT. It enforces a decision; the evidence for "
    "the decision is in docs/LABELING_LOG.md and docs/TYPED_REGISTER.md and this gate cannot "
    "check it.",
]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--strict", action="store_true")
    ap.add_argument("--inject-defect", choices=["reenable"],
                    help="PLANT A DEFECT. Positive control; must go red.")
    args = ap.parse_args()

    result = GateResult(
        gate="GATE-WITHDRAWN-1",
        description="a layer withdrawn against a pre-registered stopping rule is not constructed in the shipped path",
        detectors=[scan(args.inject_defect == "reenable")],
        not_covered=NOT_COVERED,
    )
    return report(result, args.json, args.strict)


if __name__ == "__main__":
    raise SystemExit(main())

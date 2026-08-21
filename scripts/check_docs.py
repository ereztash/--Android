#!/usr/bin/env python3
"""GATE-DOC-1 -- the readiness verdict may not contradict the QA matrix.

### Why this gate exists, and why an edit was not enough
`docs/RELEASE_READINESS.md` claimed *"Nothing has ever run on an Android device"* for four
milestones after it became false. That was corrected by hand, with a section explaining that a
stale pessimistic claim is not the safe direction.

**The same claim went stale again within two days**, in its corrected form: it said nothing built
since M9 had run on a device while `docs/QA_MATRIX.md` recorded M9, M10 and M11 rows as OBSERVED
from a second phone session.

The first fix changed the sentence. It did not change the mechanism that produced it -- a
document whose central claim must be kept in step with another document, by hand, by whoever
remembers. That mechanism produces the same failure as often as it is given the chance, so this
gate removes the remembering.

### What it checks
`RELEASE_READINESS.md` states a device claim of the form "nothing built since M<n> has run on a
device". `QA_MATRIX.md` marks rows OBSERVED. If any OBSERVED row belongs to milestone M<n> or
later, the two documents disagree and this fails.

### What it does NOT cover
See NOT_COVERED. It compares two documents, and it cannot tell whether either is true.
"""
from __future__ import annotations

import argparse
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gatelib import Detector, Finding, GateResult, report  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
READINESS = os.path.join(ROOT, "docs", "RELEASE_READINESS.md")
MATRIX = os.path.join(ROOT, "docs", "QA_MATRIX.md")

# The list RELEASE_READINESS.md publishes, between its markers.
BLOCKED_BLOCK_RE = re.compile(
    r"<!--\s*DEVICE-BLOCKED-BEGIN.*?-->(.*?)<!--\s*DEVICE-BLOCKED-END\s*-->", re.DOTALL)
ID_RE = re.compile(r"\b([A-Z][A-Z0-9]*(?:-[A-Z0-9]+)+)\b")

# The device-blocked table in QA_MATRIX.md, which is the source of truth.
BLOCKED_SECTION = "### Requires a physical Android device — still not exercised"
ROW_ID_RE = re.compile(r"^\|\s*([A-Z][A-Z0-9-]*)\s*\|", re.MULTILINE)

# A row marked OBSERVED anywhere in the matrix.
OBSERVED_ROW_RE = re.compile(r"^\|\s*([A-Z][A-Z0-9-]*)\s*\|.*OBSERVED", re.MULTILINE)


def check(root: str, inject: bool) -> Detector:
    det = Detector(name="doc_device_blocked", unit="device-blocked ids cross-checked",
                   denominator=0)
    readiness = os.path.join(root, "docs", "RELEASE_READINESS.md")
    matrix = os.path.join(root, "docs", "QA_MATRIX.md")
    if not (os.path.isfile(readiness) and os.path.isfile(matrix)):
        det.notes.append("one of the two documents is missing; NOT-MEASURED")
        return det

    readiness_text = open(readiness, encoding="utf-8").read()
    matrix_text = open(matrix, encoding="utf-8").read()

    block = BLOCKED_BLOCK_RE.search(readiness_text)
    if not block:
        det.notes.append(
            "RELEASE_READINESS.md publishes no DEVICE-BLOCKED list. NOT-MEASURED rather than "
            "PASS: a gate that passes because its subject was deleted is the failure this "
            "repository exists to refuse.")
        return det
    claimed = set(ID_RE.findall(block.group(1)))
    if inject:
        # PLANTED DEFECT: the readiness list still names a check the matrix now records as
        # done -- exactly the drift that produced the same stale sentence twice.
        claimed.add("M9-LAYOUT")

    if BLOCKED_SECTION not in matrix_text:
        det.notes.append(f"QA_MATRIX.md has no '{BLOCKED_SECTION}' section; NOT-MEASURED")
        return det
    body = matrix_text.split(BLOCKED_SECTION, 1)[1].split("### ", 1)[0]
    actual = {i for i in ROW_ID_RE.findall(body) if i != "ID"}
    observed = {m.group(1) for m in OBSERVED_ROW_RE.finditer(matrix_text)}

    det.denominator = len(claimed | actual)
    det.notes.append(f"{len(claimed)} ids claimed blocked in RELEASE_READINESS.md, "
                     f"{len(actual)} in QA_MATRIX.md")

    for missing in sorted(actual - claimed):
        det.findings.append(Finding(
            "doc_device_blocked", "docs/RELEASE_READINESS.md", 0,
            f"QA_MATRIX.md lists {missing} as device-blocked and the readiness document does "
            f"not. The verdict understates what is unverified.",
            "doc.blocked_list_incomplete"))
    for extra in sorted(claimed - actual):
        det.findings.append(Finding(
            "doc_device_blocked", "docs/RELEASE_READINESS.md", 0,
            f"the readiness document calls {extra} device-blocked, but QA_MATRIX.md does not. "
            f"The verdict is stale in the pessimistic direction, which is how the same "
            f"sentence went stale twice.",
            "doc.blocked_list_stale"))

    contradictory = sorted(claimed & observed)
    for row in contradictory:
        det.findings.append(Finding(
            "doc_device_blocked", "docs/RELEASE_READINESS.md", 0,
            f"{row} is called device-blocked while QA_MATRIX.md records it OBSERVED",
            "doc.blocked_but_observed"))
    return det


NOT_COVERED = [
    "Compares two documents against each other. It cannot tell whether EITHER is true -- only "
    "that they disagree.",
    "Checks the device-blocked LIST. It does not check any other claim in either document.",
    "Matches the ids between the markers. A list that is deleted entirely reports "
    "NOT-MEASURED rather than PASS, because a gate that passes when its subject disappears is "
    "the failure this repository exists to refuse.",
    "A QA row wrongly marked OBSERVED would make this gate agree with a false document. It "
    "checks consistency, never truth.",
    "Covers the device claim only. Every other assertion in RELEASE_READINESS.md is unchecked.",
]


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", default=ROOT)
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--strict", action="store_true")
    ap.add_argument("--inject-defect", choices=["stale"],
                    help="PLANT A DEFECT. Positive control; must go red.")
    args = ap.parse_args()

    result = GateResult(
        gate="GATE-DOC-1",
        description="the readiness verdict's device-blocked list matches the QA matrix",
        detectors=[check(args.root, args.inject_defect == "stale")],
        not_covered=NOT_COVERED,
    )
    return report(result, args.json, args.strict)


if __name__ == "__main__":
    raise SystemExit(main())

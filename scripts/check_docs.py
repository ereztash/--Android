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
import json
import os
import re
import subprocess
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


def check(root: str, defect: str | None) -> Detector:
    inject = defect == "stale"
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

    # QA_MATRIX.md against ITSELF. Added 2026-08-21 after a bullet reading "Latency, rotation,
    # TalkBack, Keystore and packet capture remain untested" was found sitting six lines under a
    # table that recorded M2-ROTATION as OBSERVED. This gate had run clean over that for a day,
    # because it only ever compared the matrix to the OTHER document. A file can contradict
    # itself, and this one did.
    self_blocked = set(actual)
    if defect == "selfcontradiction":
        # PLANTED DEFECT: a row left in the device-blocked table after it was marked OBSERVED
        # -- the direction that overstates what is unverified, and the one hand-editing produces
        # when a row is copied into the observed table and not deleted from the blocked one.
        self_blocked.add("M9-LAYOUT")
    for row in sorted(self_blocked & observed):
        det.findings.append(Finding(
            "doc_device_blocked", "docs/QA_MATRIX.md", 0,
            f"{row} is in QA_MATRIX.md's device-blocked table AND marked OBSERVED in the same "
            f"file. The matrix is the source of truth for the readiness verdict, so a matrix "
            f"that disagrees with itself makes both documents unciteable.",
            "doc.matrix_self_contradiction"))
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
    "Covers the device claim and the gate denominators only. Every other assertion in "
    "RELEASE_READINESS.md and QA_MATRIX.md is unchecked.",
    "The denominator check compares a published number against a live gate run. It says "
    "nothing about whether the gate examines the right files -- only that the matrix reports "
    "the count the gate actually produced.",
    "Compares TABLE ROWS. QA_MATRIX.md's PROSE is unchecked, and prose is where the last "
    "drift was found: a bullet listing rotation as untested, six lines under a table marking "
    "M2-ROTATION OBSERVED. Naming a check in a sentence is not a row, and nothing here reads "
    "sentences.",
    "A check that is in neither table is invisible to this gate. MI-HAPTIC shipped and was "
    "absent from the matrix entirely until a device session found it; an absent row cannot "
    "contradict anything, so absence is exactly what this gate cannot see.",
]


# QA_MATRIX.md publishes each gate's denominator in prose. Those numbers move every time a
# source file is added, and they have gone stale twice within a day of being corrected --
# once by the very commit that corrected the others. A number a human has to re-copy is a
# number that will be wrong, so this reads them back from the gates themselves.
#
# Each entry: the matrix row's leading cell, the substring that identifies the right row when
# one id has several rows, the script to run, the detector to read, and the regex that pulls
# the published count out of the row.
DENOMINATOR_ROWS = [
    ("GATE-API-1", "6 rules", "check_forbidden_api.py", "forbidden_api",
     re.compile(r"\|\s*(\d+) files, 6 rules\s*\|")),
    ("GATE-API-1", "getInitial", "check_forbidden_api.py", "forbidden_api",
     re.compile(r"\|\s*(\d+) files\s*\|")),
    ("GATE-API-1", "logcat", "check_forbidden_api.py", "forbidden_api",
     re.compile(r"\|\s*(\d+) files, production sources\s*\|")),
    ("GATE-CRYPTO-1", None, "check_forbidden_api.py", "forbidden_api",
     re.compile(r"\|\s*(\d+) files, 4 rules\s*\|")),
    ("GATE-LEARN-2", None, "check_learning.py", "learn_guard",
     re.compile(r"\|\s*(\d+) Kotlin source files\s*\|")),
]


def live_denominators(root: str) -> dict:
    """Run each gate once and read its detectors' denominators."""
    out = {}
    for script in {row[2] for row in DENOMINATOR_ROWS}:
        path = os.path.join(root, "scripts", script)
        if not os.path.isfile(path):
            continue
        cmd = [sys.executable, path, "--json"]
        if script == "check_learning.py":
            cmd += ["--root", root]
        proc = subprocess.run(cmd, capture_output=True, text=True)
        try:
            doc = json.loads(proc.stdout)
        except json.JSONDecodeError:
            continue
        for det in doc.get("detectors", []):
            out[(script, det["name"])] = det["denominator"]
    return out


# The TOTAL number of gates is published in two documents and in two languages, and NOTHING
# read it back. It went stale the moment GATE-WITHDRAWN-1 was added: both files still said 29
# while the runner defined 30, and `--fix-denominators` rewrote zero of them because no rule
# covered the number. That is the same defect this gate exists for, one level up -- the gate
# against stale counts had a stale count outside its own reach.
GATE_COUNT_FILES = {
    "docs/QA_MATRIX.md": re.compile(r"\*\*(\d+) gates\*\*"),
    "README.md": re.compile(r"(\d+) שערים"),
}


def live_gate_count(root: str) -> int | None:
    runner = os.path.join(root, "scripts", "run_gates.py")
    if not os.path.isfile(runner):
        return None
    return len(re.findall(r'"id": "GATE-', open(runner, encoding="utf-8").read()))


def check_gate_count(root: str, inject: bool) -> Detector:
    det = Detector(name="doc_gate_count", unit="published total-gate counts", denominator=0)
    live = live_gate_count(root)
    if live is None:
        det.notes.append("run_gates.py missing; NOT-MEASURED")
        return det
    for rel, rx in GATE_COUNT_FILES.items():
        path = os.path.join(root, rel)
        if not os.path.isfile(path):
            det.notes.append(f"{rel} missing; not counted")
            continue
        m = rx.search(open(path, encoding="utf-8").read())
        if not m:
            det.notes.append(f"{rel}: no published total-gate count found")
            continue
        det.denominator += 1
        published = int(m.group(1)) + (1 if inject else 0)
        det.notes.append(f"{rel}: says {published}, run_gates.py defines {live}")
        if published != live:
            det.findings.append(Finding(
                "doc_gate_count", rel, 0,
                f"{rel} publishes {published} gates; run_gates.py defines {live}. A total "
                f"copied by hand goes stale the next time a gate is added, and this one did.",
                "doc.stale_gate_count"))
    return det


def check_denominators(root: str, inject: bool) -> Detector:
    det = Detector(name="doc_denominators", unit="published gate denominators",
                   denominator=0)
    matrix = os.path.join(root, "docs", "QA_MATRIX.md")
    if not os.path.isfile(matrix):
        det.notes.append("QA_MATRIX.md missing; NOT-MEASURED")
        return det
    text = open(matrix, encoding="utf-8").read()
    live = live_denominators(root)
    if not live:
        det.notes.append("no gate produced a denominator; NOT-MEASURED")
        return det

    for gate, marker, script, detector, row_re in DENOMINATOR_ROWS:
        rows = [line for line in text.splitlines()
                if line.startswith(f"| {gate} |")
                and (marker is None or marker in line)]
        matched = [(line, row_re.search(line)) for line in rows]
        matched = [(line, m) for line, m in matched if m]
        if not matched:
            det.notes.append(f"{gate} ({marker or 'only row'}): no published denominator "
                             f"found in the matrix")
            continue
        expected = live.get((script, detector))
        if expected is None:
            det.notes.append(f"{gate}: {script}/{detector} produced no denominator")
            continue
        det.denominator += 1
        line, m = matched[0]
        published = int(m.group(1))
        if inject:
            # PLANTED DEFECT: what a stale hand-copied number looks like.
            published += 1
        det.notes.append(f"{gate} ({marker or 'only row'}): matrix says {published}, "
                         f"{script} counted {expected}")
        if published != expected:
            det.findings.append(Finding(
                "doc_denominators", "docs/QA_MATRIX.md", 0,
                f"{gate} publishes a denominator of {published}; {script}'s {detector} "
                f"detector counted {expected} on this tree. A denominator copied by hand "
                f"goes stale the next time a file is added, and this one has -- twice.",
                "doc.stale_denominator"))

    # GATE-DOC-1's OWN published denominator, which the loop above cannot reach: reading it
    # back the same way would mean running check_docs.py from inside check_docs.py. It is
    # derived from the matrix instead, in-process. It went stale on 2026-08-21 -- the row read
    # "20 ids" while the table below it listed 21 -- inside the one gate whose entire subject
    # is numbers going stale.
    if BLOCKED_SECTION in text:
        body = text.split(BLOCKED_SECTION, 1)[1].split("### ", 1)[0]
        counted = len({i for i in ROW_ID_RE.findall(body) if i != "ID"})
        rows = [line for line in text.splitlines() if line.startswith("| GATE-DOC-1 |")]
        m = re.search(r"\|\s*(\d+) ids \+ (\d+) denominators", rows[0]) if rows else None
        if m:
            det.denominator += 1
            published_ids = int(m.group(1)) + (1 if inject else 0)
            det.notes.append(f"GATE-DOC-1 (own row): matrix says {published_ids} ids, "
                             f"its device-blocked table lists {counted}")
            if published_ids != counted:
                det.findings.append(Finding(
                    "doc_denominators", "docs/QA_MATRIX.md", 0,
                    f"GATE-DOC-1's row publishes {published_ids} device-blocked ids while the "
                    f"table it describes lists {counted}. The gate against stale numbers "
                    f"published a stale number.",
                    "doc.stale_denominator"))
        else:
            det.notes.append("GATE-DOC-1 (own row): no published id count found in the matrix")
    return det


def rewrite_denominators(root: str) -> int:
    """Write the live denominators into QA_MATRIX.md's prose.

    Not a way to make the gate pass -- the gate still compares and still fails on a real
    disagreement. This exists because the number in the matrix is DERIVED prose whose only job
    is to be readable, and a derived number a human re-copies is a number that goes stale. It
    did twice in one afternoon, in the same session that added the check for it.

    CI never runs this. It is a developer convenience, invoked deliberately.
    """
    matrix = os.path.join(root, "docs", "QA_MATRIX.md")
    text = open(matrix, encoding="utf-8").read()
    live = live_denominators(root)
    changed = 0
    lines = text.splitlines(keepends=True)
    for i, line in enumerate(lines):
        for gate, marker, script, detector, row_re in DENOMINATOR_ROWS:
            if not line.startswith(f"| {gate} |"):
                continue
            if marker is not None and marker not in line:
                continue
            m = row_re.search(line)
            expected = live.get((script, detector))
            if not m or expected is None or int(m.group(1)) == expected:
                continue
            start, end = m.span(1)
            lines[i] = line[:start] + str(expected) + line[end:]
            line = lines[i]
            changed += 1
    text = "".join(lines)

    # GATE-DOC-1's own id count, derived from the table rather than from a gate run.
    if BLOCKED_SECTION in text:
        body = text.split(BLOCKED_SECTION, 1)[1].split("### ", 1)[0]
        counted = len({i for i in ROW_ID_RE.findall(body) if i != "ID"})
        new_text, n = re.subn(r"(\| GATE-DOC-1 \|.*?\|\s*)\d+( ids \+ )",
                              lambda mm: f"{mm.group(1)}{counted}{mm.group(2)}", text)
        if n and new_text != text:
            text = new_text
            changed += 1
        new_text, n = re.subn(r"(the same )\d+( ids against every OBSERVED row)",
                              lambda mm: f"{mm.group(1)}{counted}{mm.group(2)}", text)
        if n and new_text != text:
            text = new_text
            changed += 1

    open(matrix, "w", encoding="utf-8").write(text)
    return changed


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", default=ROOT)
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--strict", action="store_true")
    ap.add_argument("--inject-defect",
                    choices=["stale", "denominator", "selfcontradiction", "gatecount"],
                    help="PLANT A DEFECT. Positive control; must go red.")
    ap.add_argument("--fix-denominators", action="store_true",
                    help="rewrite the matrix's derived numbers from a live gate run. Never "
                         "run in CI; the gate itself still compares and still fails.")
    args = ap.parse_args()

    if args.fix_denominators:
        n = rewrite_denominators(args.root)
        print(f"rewrote {n} derived number(s) in docs/QA_MATRIX.md")
        return 0

    result = GateResult(
        gate="GATE-DOC-1",
        description="the readiness verdict's device-blocked list matches the QA matrix, and "
                    "the matrix's gate denominators match what the gates actually count",
        detectors=[
            check(args.root, args.inject_defect),
            check_denominators(args.root, args.inject_defect == "denominator"),
            check_gate_count(args.root, args.inject_defect == "gatecount"),
        ],
        not_covered=NOT_COVERED,
    )
    return report(result, args.json, args.strict)


if __name__ == "__main__":
    raise SystemExit(main())

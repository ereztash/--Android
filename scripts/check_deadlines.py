#!/usr/bin/env python3
"""GATE-DEADLINE-1 — an external deadline may not pass while its action is still open.

### The defect this exists for
`docs/OPERATOR_NOTICES.md` NOTICE 1 carried a headline reading **"URGENT, 11 DAYS"** and a body
reading **"Today is 2026-08-20"**. Read on 2026-08-27 the real figure was **four** days, and
nothing in the repository had noticed. The deadline in question -- Google Play's targetSdk 36
cutoff -- blocks *every* upload, so the number being wrong in the reassuring direction is the
worst direction available.

That is the same failure as a hand-copied gate denominator, which this repository has now had
to refresh six separate times. A number a person transcribes is a number that rots, and the
only durable fix is to stop transcribing it.

### What is checked, and why it is not a tautology
Two detectors.

**`deadline_passed`** -- carries the gate. For every notice that states a deadline and whose
status is still open, the deadline must not be in the past. This can only ever fail because
of the passage of time, which is precisely the property that makes it worth automating: no
commit causes it, so no code review would catch it.

**`deadline_not_transcribed`** -- no notice may hardcode a day count or a "today is" date.
This is the check that keeps the first one honest. Without it a reader could re-add
"11 DAYS" tomorrow and the passed-deadline check would still be green, because 11 DAYS is
prose and the machine-readable date underneath it would still be in the future.

So the gate enforces both halves: the date must be true, and the countdown must not exist in
a form that can drift away from it.
"""
from __future__ import annotations

import argparse
import datetime
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gatelib import Detector, Finding, GateResult, report  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
NOTICES = os.path.join(ROOT, "docs", "OPERATOR_NOTICES.md")

# A notice is OPEN unless its status says it is finished. Matched on the status line itself,
# anchored after "Status:", because a substring test reads "NOT DONE" as "DONE" -- which it did,
# silently marking every open notice closed and taking the gate's denominator to zero.
STATUS = re.compile(r"\*\*Status:\s*(?P<verdict>[A-Z ]+)")
DEADLINE = re.compile(r"\*\*Deadline (\d{4})-(\d{2})-(\d{2})\.?\*\*")
TRANSCRIBED = re.compile(r"today is\s+\d{4}-\d{2}-\d{2}|\b\d+\s+DAYS\b|\b\d+-day deadline\b",
                         re.IGNORECASE)


def notices(text: str) -> list[tuple[str, int, str, str]]:
    """Split into (title, start_line, body, live_body). One entry per '## NOTICE'.

    `live_body` drops blockquoted lines. A blockquote is commentary -- typically a record of
    what a notice USED to say -- and quoting a dead countdown must not read as carrying one.
    Without this the gate fails on the very correction that removed the countdown.
    """
    out, cur, live, start, title = [], [], [], 0, None
    for i, line in enumerate(text.splitlines(), 1):
        if line.startswith("## NOTICE"):
            if title is not None:
                out.append((title, start, "\n".join(cur), "\n".join(live)))
            title, start, cur, live = line.strip(), i, [], []
        elif title is not None:
            cur.append(line)
            if not line.lstrip().startswith(">"):
                live.append(line)
    if title is not None:
        out.append((title, start, "\n".join(cur), "\n".join(live)))
    return out


def scan(today: datetime.date, inject: str | None) -> list[Detector]:
    text = open(NOTICES, encoding="utf-8").read()
    if inject == "expired":
        # PLANTED: move a live deadline into the past without touching its status.
        text = text.replace("**Deadline 2026-08-31.**", "**Deadline 2020-01-01.**")
    if inject == "transcribe":
        # PLANTED: re-add the hand-written countdown the gate exists to forbid.
        text = text.replace("## NOTICE 1 — URGENT:", "## NOTICE 1 — URGENT, 11 DAYS:")

    items = notices(text)
    passed = Detector(name="deadline_passed", unit="open notices carrying a deadline",
                      denominator=0)
    written = Detector(name="deadline_not_transcribed", unit="notices scanned",
                       denominator=len(items))

    for title, line, body, live in items:
        if TRANSCRIBED.search(title) or TRANSCRIBED.search(live):
            hit = (TRANSCRIBED.search(title) or TRANSCRIBED.search(live)).group(0)
            written.findings.append(Finding(
                "deadline_not_transcribed", "docs/OPERATOR_NOTICES.md", line,
                f"{title!r} hardcodes {hit!r}. A transcribed countdown drifts from the date "
                f"it was computed from; state the deadline and let this gate do the "
                f"arithmetic.", "deadline.transcribed_countdown"))

        m = DEADLINE.search(body)
        if not m:
            continue
        st = STATUS.search(body)
        verdict = st.group("verdict").strip() if st else "OPEN"
        if not verdict.startswith("NOT") and verdict != "OPEN":
            continue
        passed.denominator += 1
        due = datetime.date(int(m[1]), int(m[2]), int(m[3]))
        left = (due - today).days
        if left < 0:
            passed.findings.append(Finding(
                "deadline_passed", "docs/OPERATOR_NOTICES.md", line,
                f"{title!r} states a deadline of {due} and its action is still open. That is "
                f"{-left} days ago. No commit causes this and no review would catch it.",
                "deadline.passed_while_open"))
        else:
            passed.notes.append(f"{title.split('—')[0].strip()}: {due}, {left} days from {today}")

    return [passed, written]


NOT_COVERED = [
    "Whether the deadline itself is real. The date is read from the document; nobody here can "
    "check Google Play's policy page.",
    "Deadlines that live anywhere other than docs/OPERATOR_NOTICES.md.",
    "Whether the operator ACTED. This reads a status line a person maintains by hand, so a "
    "notice finished in the world but not updated here still reads as open.",
]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--strict", action="store_true")
    ap.add_argument("--today", help="ISO date; defaults to the real today.")
    ap.add_argument("--inject-defect", choices=["expired", "transcribe"],
                    help="PLANT A DEFECT. Positive control; must go red.")
    args = ap.parse_args()
    today = (datetime.date.fromisoformat(args.today) if args.today
             else datetime.date.today())
    return report(GateResult(
        gate="GATE-DEADLINE-1",
        description="an external deadline does not pass while its action is still open, and no "
                    "notice hardcodes a countdown that can drift from it",
        detectors=scan(today, args.inject_defect),
        not_covered=NOT_COVERED,
    ), args.json, args.strict)


if __name__ == "__main__":
    raise SystemExit(main())

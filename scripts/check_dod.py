#!/usr/bin/env python3
"""GATE-DOD-1..4 -- the definition of done may not contain a criterion nobody can check.

### Why this gate exists
`docs/PATH_TO_PRODUCTION.md` was written because "production ready" was being carried as a
mood, and it replaced the mood with a list. A list still leaves one judgement being made from
memory: *when may an item be ticked?* `docs/DEFINITION_OF_DONE.md` answers that, and the moment
it did it became the third document in this repository whose central claims must be kept in
step with the other two by hand.

That mechanism has produced the same failure three times here already -- a device claim that
went stale twice, and a denominator that went stale inside the very gate written to catch stale
denominators. So the definition of done arrives with a gate attached rather than after one.

### What it checks
1. **GATE-DOD-1** -- every criterion cites *something*, states one of the four allowed states,
   and every WAIVED criterion names a waiver that exists with a date and an owner. A criterion
   that cites nothing can only be checked by its author, on the day they wrote it.
2. **GATE-DOD-2** -- everything cited resolves: a gate id the gate runner defines, a row id the
   QA matrix carries, or a file on disk. This is the drift half: a criterion whose evidence was
   renamed or deleted still reads as satisfied.
3. **GATE-DOD-3** -- no criterion claims MET while the QA matrix records its evidence as not
   run. `NOT RUN` is not `MET`, exactly as `NOT-MEASURED` is not `PASS`.
4. **GATE-DOD-4** -- the counts the file publishes are the ones its own tables contain,
   including the per-tier verdict. A number a human re-copies is a number that goes stale.

### What it does NOT cover
See NOT_COVERED. It compares documents against documents, and cannot tell whether any of them
is true.
"""
from __future__ import annotations

import argparse
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gatelib import Detector, Finding, GateResult, report  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

STATES = {"MET", "NOT MET", "NOT RUN", "WAIVED"}

TIER_RE = "<!--\\s*DOD-TIER-BEGIN:\\s*{t}\\s*-->(.*?)<!--\\s*DOD-TIER-END:\\s*{t}\\s*-->"
WAIVERS_RE = re.compile(
    r"<!--\s*DOD-WAIVERS-BEGIN\s*-->(.*?)<!--\s*DOD-WAIVERS-END\s*-->", re.DOTALL)
COUNTS_RE = re.compile(
    r"<!--\s*DOD-COUNTS-BEGIN.*?-->(.*?)<!--\s*DOD-COUNTS-END\s*-->", re.DOTALL)

CRITERION_ROW_RE = re.compile(r"^\|\s*([CRP]\d+)\s*\|")
BACKTICKED_RE = re.compile(r"`([^`]+)`")
GATE_ID_RE = re.compile(r"^GATE-[A-Z0-9]+-\d+$")
QA_ID_RE = re.compile(r"^[A-Z][A-Z0-9]*(?:-[A-Z0-9]+)+$")
WAIVER_ID_RE = re.compile(r"^WAIVER-\d+$")
RUN_GATES_ID_RE = re.compile(r'"id":\s*"(GATE-[A-Z0-9-]+)"')
MATRIX_ROW_ID_RE = re.compile(r"^\|\s*([A-Z][A-Z0-9-]*)\s*\|", re.MULTILINE)
OBSERVED_ROW_RE = re.compile(r"^\|\s*([A-Z][A-Z0-9-]*)\s*\|.*OBSERVED", re.MULTILINE)
DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")

# The QA matrix's NOT RUN section, minus the one subsection that records what a device DID
# show. Everything else under it is, by that document's own words, "not been exercised".
NOT_RUN_SECTION = "## NOT RUN"
OBSERVED_SUBSECTION = "OBSERVED ON A DEVICE"


def _cells(line: str) -> list[str]:
    return [c.strip() for c in line.strip().strip("|").split("|")]


def _clean(cell: str) -> str:
    return re.sub(r"[*`]", "", cell).strip()


class Criterion:
    def __init__(self, tier: str, cid: str, state: str, evidence: list[str], text: str):
        self.tier, self.id, self.state, self.evidence, self.text = tier, cid, state, evidence, text


def parse_criteria(text: str, tier: str) -> list[Criterion]:
    m = re.search(TIER_RE.format(t=tier), text, re.DOTALL)
    if not m:
        return []
    out = []
    for line in m.group(1).splitlines():
        rid = CRITERION_ROW_RE.match(line)
        if not rid:
            continue
        cells = _cells(line)
        if len(cells) < 4:
            continue
        out.append(Criterion(tier, rid.group(1), _clean(cells[2]).upper(),
                             BACKTICKED_RE.findall(cells[3]), cells[1]))
    return out


def parse_waivers(text: str) -> dict[str, dict]:
    m = WAIVERS_RE.search(text)
    if not m:
        return {}
    out = {}
    for line in m.group(1).splitlines():
        cells = _cells(line)
        if len(cells) < 5 or not WAIVER_ID_RE.match(_clean(cells[0])):
            continue
        out[_clean(cells[0])] = {"waives": _clean(cells[1]), "date": _clean(cells[2]),
                                 "owner": _clean(cells[3]), "reason": _clean(cells[4])}
    return out


def known_gate_ids(root: str, matrix_ids: set[str]) -> set[str]:
    """Gate ids the runner actually defines, plus those the matrix records.

    GATE-META-1 is not a spec entry -- it is run_gates.py's own NOT-A-GATE behaviour, recorded
    as a row in the matrix. A citation of it is a citation of something real, so the matrix is
    part of the authority here and not a fallback.
    """
    path = os.path.join(root, "scripts", "run_gates.py")
    ids = set()
    if os.path.isfile(path):
        ids |= set(RUN_GATES_ID_RE.findall(open(path, encoding="utf-8").read()))
    return ids | {i for i in matrix_ids if i.startswith("GATE-")}


def matrix_sets(root: str) -> tuple[set[str], set[str]]:
    """(every row id in the QA matrix, the ids it records as not run)."""
    path = os.path.join(root, "docs", "QA_MATRIX.md")
    if not os.path.isfile(path):
        return set(), set()
    text = open(path, encoding="utf-8").read()
    all_ids = {i for i in MATRIX_ROW_ID_RE.findall(text) if i != "ID"}
    observed = {m.group(1) for m in OBSERVED_ROW_RE.finditer(text)}

    not_run: set[str] = set()
    if NOT_RUN_SECTION in text:
        body = text.split(NOT_RUN_SECTION, 1)[1]
        for chunk in body.split("### ")[1:]:
            if chunk.startswith(OBSERVED_SUBSECTION):
                continue
            not_run |= {i for i in MATRIX_ROW_ID_RE.findall(chunk) if i != "ID"}
    return all_ids, (not_run - observed)


def resolves(token: str, root: str, gates: set[str], matrix_ids: set[str],
             waivers: dict) -> bool:
    if WAIVER_ID_RE.match(token):
        return token in waivers
    if GATE_ID_RE.match(token):
        return token in gates
    if "/" in token or token.endswith((".md", ".py", ".yml", ".kt", ".json")):
        return os.path.exists(os.path.join(root, token))
    if QA_ID_RE.match(token):
        return token in matrix_ids
    return False


def check_criteria(root: str, defect: str | None) -> Detector:
    det = Detector(name="dod_criteria", unit="done-criteria checked", denominator=0)
    path = os.path.join(root, "docs", "DEFINITION_OF_DONE.md")
    if not os.path.isfile(path):
        det.notes.append("docs/DEFINITION_OF_DONE.md is missing; NOT-MEASURED")
        return det
    text = open(path, encoding="utf-8").read()

    rows: list[Criterion] = []
    for tier in ("C", "R", "P"):
        rows += parse_criteria(text, tier)
    if not rows:
        det.notes.append(
            "no criterion rows found between the tier markers. NOT-MEASURED rather than PASS: "
            "a gate that passes because its subject was deleted is the failure this repository "
            "exists to refuse.")
        return det

    waivers = parse_waivers(text)
    matrix_ids, not_run = matrix_sets(root)
    gates = known_gate_ids(root, matrix_ids)

    if defect == "unfalsifiable":
        # PLANTED DEFECT: the shape a criterion takes when it is written to feel finished --
        # nothing cited, a state nobody defined, and a waiver that was decided in conversation
        # and never written down.
        rows.append(Criterion("C", "C99", "MOSTLY", [], "the app feels solid"))
        rows.append(Criterion("C", "C98", "WAIVED", ["WAIVER-99"], "waived in conversation"))
    if defect == "phantom":
        # PLANTED DEFECT: evidence that was renamed or deleted after the criterion was
        # written. The criterion still reads as satisfied and cites something by name.
        rows.append(Criterion("P", "P99", "MET", ["GATE-NOPE-9", "docs/NOT_A_FILE.md"],
                              "settled by a gate that no longer exists"))
    if defect == "metoverunrun":
        # PLANTED DEFECT: MET claimed over a check the matrix says has never run -- "ready
        # except for", written one row at a time.
        rows.append(Criterion("R", "R99", "MET", ["M7-LAT"], "latency is fine"))

    det.denominator = len(rows)
    det.notes.append(f"{len(rows)} criteria across 3 tiers; {len(waivers)} waivers; "
                     f"{len(gates)} known gate ids; {len(matrix_ids)} matrix row ids, "
                     f"{len(not_run)} of them recorded not run")

    for row in rows:
        where = f"{row.tier}: {row.id}"
        if row.state not in STATES:
            det.findings.append(Finding(
                "dod_criteria", "docs/DEFINITION_OF_DONE.md", 0,
                f"{where} is in state {row.state!r}, which is not one of "
                f"{sorted(STATES)}. A fifth state is where 'ready except for' gets back in.",
                "dod.unknown_state"))
        if not row.evidence:
            det.findings.append(Finding(
                "dod_criteria", "docs/DEFINITION_OF_DONE.md", 0,
                f"{where} cites no evidence at all. A criterion with nothing to cite can be "
                f"checked only by its author, on the day they wrote it.",
                "dod.no_evidence"))
        if row.state == "WAIVED":
            cited = [t for t in row.evidence if WAIVER_ID_RE.match(t)]
            if not cited:
                det.findings.append(Finding(
                    "dod_criteria", "docs/DEFINITION_OF_DONE.md", 0,
                    f"{where} is WAIVED and names no waiver. A waiver is a signature; "
                    f"silence is drift.",
                    "dod.unsigned_waiver"))
            for w in cited:
                entry = waivers.get(w)
                if entry is None:
                    det.findings.append(Finding(
                        "dod_criteria", "docs/DEFINITION_OF_DONE.md", 0,
                        f"{where} cites {w}, which is in no waivers table.",
                        "dod.unsigned_waiver"))
                elif not DATE_RE.match(entry["date"]) or not entry["owner"] \
                        or not entry["reason"]:
                    det.findings.append(Finding(
                        "dod_criteria", "docs/DEFINITION_OF_DONE.md", 0,
                        f"{w} carries date={entry['date']!r} owner={entry['owner']!r} and a "
                        f"{len(entry['reason'])}-character reason. A waiver without all three "
                        f"is an omission wearing a table row.",
                        "dod.unsigned_waiver"))
        for token in row.evidence:
            if not resolves(token, root, gates, matrix_ids, waivers):
                det.findings.append(Finding(
                    "dod_criteria", "docs/DEFINITION_OF_DONE.md", 0,
                    f"{where} cites `{token}`, which is not a gate the runner defines, a row "
                    f"the QA matrix carries, a waiver, or a file that exists.",
                    "dod.phantom_evidence"))
        if row.state == "MET":
            for token in row.evidence:
                if token in not_run:
                    det.findings.append(Finding(
                        "dod_criteria", "docs/DEFINITION_OF_DONE.md", 0,
                        f"{where} reads MET and cites {token}, which QA_MATRIX.md records as "
                        f"not run. NOT RUN is not MET.",
                        "dod.met_over_unrun"))
    return det


def derive_counts(text: str) -> dict[str, dict]:
    out = {}
    for tier in ("C", "R", "P"):
        rows = parse_criteria(text, tier)
        counts = {s: sum(1 for r in rows if r.state == s) for s in STATES}
        out[tier] = {
            "total": len(rows),
            "MET": counts["MET"],
            "NOT MET": counts["NOT MET"],
            "NOT RUN": counts["NOT RUN"],
            "WAIVED": counts["WAIVED"],
            # A tier is met when nothing in it is unmet or unrun. Waivers are counted in the
            # open and do not block; that is what makes them worth writing down.
            "verdict": "MET" if not counts["NOT MET"] and not counts["NOT RUN"] else "NOT MET",
        }
    return out


def parse_published(text: str) -> dict[str, dict]:
    m = COUNTS_RE.search(text)
    if not m:
        return {}
    out = {}
    for line in m.group(1).splitlines():
        cells = _cells(line)
        if len(cells) < 7:
            continue
        head = _clean(cells[0])
        tm = re.match(r"^([CRP])\b", head)
        if not tm:
            continue
        try:
            nums = [int(_clean(c)) for c in cells[1:6]]
        except ValueError:
            continue
        out[tm.group(1)] = {"total": nums[0], "MET": nums[1], "NOT MET": nums[2],
                            "NOT RUN": nums[3], "WAIVED": nums[4],
                            "verdict": _clean(cells[6]).upper()}
    return out


def check_counts(root: str, inject: bool) -> Detector:
    det = Detector(name="dod_counts", unit="published counts read back from the tables",
                   denominator=0)
    path = os.path.join(root, "docs", "DEFINITION_OF_DONE.md")
    if not os.path.isfile(path):
        det.notes.append("docs/DEFINITION_OF_DONE.md is missing; NOT-MEASURED")
        return det
    text = open(path, encoding="utf-8").read()
    derived = derive_counts(text)
    published = parse_published(text)
    if not published:
        det.notes.append(
            "the file publishes no counts between its markers; NOT-MEASURED rather than PASS")
        return det

    for tier, want in derived.items():
        got = published.get(tier)
        if got is None:
            det.findings.append(Finding(
                "dod_counts", "docs/DEFINITION_OF_DONE.md", 0,
                f"tier {tier} has criteria but no published row in the verdict table.",
                "dod.unpublished_tier"))
            continue
        det.notes.append(f"tier {tier}: published {got}, tables contain {want}")
        for key in ("total", "MET", "NOT MET", "NOT RUN", "WAIVED", "verdict"):
            det.denominator += 1
            claimed = got[key]
            if inject and key == "MET" and tier == "C":
                # PLANTED DEFECT: one hand-copied count off by one, the day after a criterion
                # changed state.
                claimed = claimed + 1
            if claimed != want[key]:
                det.findings.append(Finding(
                    "dod_counts", "docs/DEFINITION_OF_DONE.md", 0,
                    f"tier {tier} publishes {key} = {claimed!r}; its own table contains "
                    f"{want[key]!r}. A derived number a human re-copies goes stale, and this "
                    f"repository has watched it happen three times.",
                    "dod.stale_count"))
    return det


def fix_counts(root: str) -> int:
    """Rewrite the published verdict table from the tier tables.

    Not a way to make the gate pass -- the gate still compares, and still fails when a tier's
    contents and its published summary genuinely disagree. This exists because the summary is
    DERIVED prose whose only job is to be readable. CI never runs it.
    """
    path = os.path.join(root, "docs", "DEFINITION_OF_DONE.md")
    text = open(path, encoding="utf-8").read()
    derived = derive_counts(text)
    m = COUNTS_RE.search(text)
    if not m:
        return 0
    block, changed = m.group(1), 0
    lines = block.splitlines(keepends=True)
    for i, line in enumerate(lines):
        cells = _cells(line)
        if len(cells) < 7:
            continue
        tm = re.match(r"^([CRP])\b", _clean(cells[0]))
        if not tm or tm.group(1) not in derived:
            continue
        d = derived[tm.group(1)]
        new = (f"| {cells[0]} | {d['total']} | {d['MET']} | {d['NOT MET']} | "
               f"{d['NOT RUN']} | {d['WAIVED']} | **{d['verdict']}** |")
        if new.strip() != line.strip():
            lines[i] = new + "\n"
            changed += 1
    if changed:
        text = text[:m.start(1)] + "".join(lines) + text[m.end(1):]
        open(path, "w", encoding="utf-8").write(text)
    return changed


NOT_COVERED = [
    "Compares documents against documents. It cannot tell whether ANY of them is true -- only "
    "that they disagree.",
    "A criterion that is MISSING is invisible here, exactly as an absent QA row is invisible "
    "to GATE-DOC-1. MI-HAPTIC shipped and was in neither table until a device session found "
    "it, and no check can flag an absence.",
    "It cannot see a criterion stale in the PESSIMISTIC direction -- one reading NOT RUN whose "
    "evidence has since come back OBSERVED. Tier R is about one artifact, so a row there "
    "legitimately reads NOT RUN while the matrix records an older build as OBSERVED, and "
    "comparing them automatically would raise false alarms rather than catch drift. That "
    "direction is the one that went undetected here for four milestones.",
    "It checks that evidence EXISTS, never that the evidence supports the criterion. A "
    "criterion citing a real gate that measures something else entirely passes.",
    "A criterion wrongly marked MET, citing evidence the matrix wrongly records as passed, "
    "makes this gate agree with a false document. It checks consistency, never truth.",
    "It reads TABLE ROWS between markers. The prose of DEFINITION_OF_DONE.md is unchecked, and "
    "prose is where the last drift in this repository was found.",
    "The waiver check reads a date, an owner and a reason. It cannot tell whether the owner "
    "agreed, whether the reason is good, or whether the waiver should have expired.",
]


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", default=ROOT)
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--strict", action="store_true")
    ap.add_argument("--inject-defect",
                    choices=["unfalsifiable", "phantom", "metoverunrun", "count"],
                    help="PLANT A DEFECT. Positive control; must go red.")
    ap.add_argument("--fix-counts", action="store_true",
                    help="rewrite the derived verdict table from the tier tables. Never run "
                         "in CI; the gate itself still compares and still fails.")
    args = ap.parse_args()

    if args.fix_counts:
        print(f"rewrote {fix_counts(args.root)} derived row(s) in docs/DEFINITION_OF_DONE.md")
        return 0

    result = GateResult(
        gate="GATE-DOD-1",
        description="every done-criterion cites evidence that exists, states one of four "
                    "states, is not claimed MET over a check that never ran, and publishes "
                    "counts derived from its own tables",
        detectors=[
            check_criteria(args.root, args.inject_defect),
            check_counts(args.root, args.inject_defect == "count"),
        ],
        not_covered=NOT_COVERED,
    )
    return report(result, args.json, args.strict)


if __name__ == "__main__":
    raise SystemExit(main())

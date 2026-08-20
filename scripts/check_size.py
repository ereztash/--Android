#!/usr/bin/env python3
"""GATE-SIZE-1 -- the shipped artifact stays inside a budget that was written down first.

### Why a size gate at all
This app's whole value proposition is that it works with no network. That means everything it
knows has to be in the APK, and every accuracy improvement is paid for in megabytes: the bigram
table alone is 1.85 MB in the artifact, 36% of the release APK, bought for +11 points of
prefix-3 top-3 accuracy. That is a trade a person should make deliberately, once, with both
numbers in front of them -- not one that happens because a dependency got bigger or an asset
was regenerated at a lower prune threshold.

### The budget is a ceiling on a measured baseline, never a target
Every number in `tools/size_budget.json` was written **after** measuring the artifact, with a
stated headroom, and each entry records what it is protecting. Raising one is a deliberate act
that belongs in a commit of its own with the reason attached. Lowering the artifact under a
budget by deleting a feature is not "passing the gate" either -- the budget exists to make the
cost visible, in both directions.

### What this does not cover
Listed in NOT_COVERED below, and printed with every run.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gatelib import Detector, Finding, GateResult, report  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BUDGET = os.path.join(ROOT, "tools", "size_budget.json")


def measure(apk: str) -> dict[str, int]:
    """Sizes as they exist in the artifact, which is what a user downloads."""
    out = {"apk_total_bytes": os.path.getsize(apk)}
    dex = assets = 0
    with zipfile.ZipFile(apk) as z:
        for info in z.infolist():
            if info.filename.endswith(".dex"):
                dex += info.compress_size
            elif info.filename.startswith("assets/"):
                assets += info.compress_size
    out["dex_bytes"] = dex
    out["assets_bytes"] = assets
    return out


NOT_COVERED = [
    "Download size on Play is not APK size. Play re-signs and re-compresses, and an AAB "
    "splits per device. This measures the universal APK, which is an upper bound, not the "
    "number a user sees.",
    "Native libraries and per-ABI splits are not broken out; this app ships no native code, "
    "so the distinction has never mattered here and would need adding if it did.",
    "Runtime memory is a different question entirely and is not measured here. The trie is "
    "13.6 MB of heap and appears nowhere in these numbers.",
    "A budget says nothing about whether the bytes are worth having. That argument lives in "
    "docs/PREDICTION_MEASUREMENTS.md, next to the accuracy the assets buy.",
]


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--apk", default=os.path.join(
        ROOT, "app", "build", "outputs", "apk", "release", "app-release-unsigned.apk"))
    ap.add_argument("--budget", default=BUDGET)
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--strict", action="store_true")
    ap.add_argument("--inject-defect", choices=["total", "assets"],
                    help="PLANT A DEFECT. Positive control; every value must go red.")
    args = ap.parse_args()

    if not os.path.isfile(args.apk):
        return report(GateResult(
            gate="GATE-SIZE-1",
            description=f"APK not built: {os.path.relpath(args.apk, ROOT)}",
            detectors=[Detector(name="apk_size", unit="budget entries", denominator=0,
                                notes=[f"APK absent ({args.apk}); "
                                       f"run ./gradlew :app:assembleRelease"])],
            not_covered=NOT_COVERED,
        ), args.json, args.strict)

    budget = json.load(open(args.budget, encoding="utf-8"))["limits"]
    actual = measure(args.apk)
    if args.inject_defect == "total":
        actual["apk_total_bytes"] = int(actual["apk_total_bytes"] * 1.5)
    if args.inject_defect == "assets":
        actual["assets_bytes"] = int(actual["assets_bytes"] * 1.5)

    det = Detector(name="apk_size", unit="budget entries", denominator=len(budget))
    for key, entry in sorted(budget.items()):
        got = actual.get(key)
        if got is None:
            det.notes.append(f"{key}: budget entry has no measurement; not counted")
            det.denominator -= 1
            continue
        limit = entry["max_bytes"]
        # Reported as bytes remaining and as growth over the measurement, never as a bare
        # "headroom" percentage: that number means one thing against the limit and another
        # against the measurement, and the two differ by enough to mislead.
        room = limit - got
        growth = 100.0 * room / got if got else 0.0
        det.notes.append(
            f"{key}: {got:,} of {limit:,} bytes -- {room:,} left, room to grow {growth:.1f}% "
            f"-- {entry['why']}")
        if got > limit:
            det.findings.append(Finding(
                "apk_size", os.path.basename(args.apk), 0,
                f"{key} is {got:,} bytes, over the {limit:,} budget by {got - limit:,}. "
                f"Budget set for: {entry['why']}",
                "size.over_budget"))

    return report(GateResult(
        gate="GATE-SIZE-1",
        description=f"the release artifact stays inside its written-down budget "
                    f"({os.path.relpath(args.apk, ROOT)})",
        detectors=[det],
        not_covered=NOT_COVERED,
    ), args.json, args.strict)


if __name__ == "__main__":
    raise SystemExit(main())

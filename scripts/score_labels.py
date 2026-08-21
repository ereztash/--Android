#!/usr/bin/env python3
"""A1 tool 3 of 4 -- score one labelled batch against its key, under the rule fixed first.

Reads the JSON the labelling screen produces and the batch's answer key, and applies
docs/LABELING_PROTOCOL.md **as written**: the control bar, the abstention bar, the Wilson
interval, and the decision band. It computes nothing that document does not authorise.

### It is built to fail loudly
- Controls below the bar -> the batch is VOID and no precision is printed at all. Not
  "printed with a warning": a number that has been seen is a number that can be argued with,
  and the point of a void batch is that there is nothing to argue with.
- Abstentions above the bar -> NOT DECIDABLE, again with no precision printed.
- The key's recorded protocol hash is compared against the protocol file on disk. If the rule
  changed after the batch was cut, that is reported before anything else.

### Positive control
`--self-test` scores four synthetic label sets against the real key: one who is attentive and
mostly agrees, one who is not reading, one who is attentive and finds the real items
ambiguous, and one who is attentive and mostly disagrees. They must come back OK / VOID /
NOT DECIDABLE / withdraw-band respectively. A scorer that has never rejected anything has not
been shown to reject anything -- and one that can only print the favourable verdict is worse
than none.
"""

import argparse
import json
import math
import os
import random
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

CONTROL_RATE = 0.90       # "18 of 20" from docs/LABELING_PROTOCOL.md, expressed as the rate
                          # it always was so it scales with batch size
MAX_ABSTENTION = 0.30
MIN_SELF_AGREEMENT = 0.90      # four-way; governs the filtered precision figure
MIN_DIRECTION_STABILITY = 0.90 # amendment 2; governs whether the floor may be published
DECIDE_GOOD = 0.60        # Wilson lower bound
DECIDE_POOR = 0.40


def wilson(k, n, z=1.96):
    if n == 0:
        return (0.0, 0.0)
    p = k / n
    d = 1 + z * z / n
    centre = (p + z * z / (2 * n)) / d
    half = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / d
    return (max(0.0, centre - half), min(1.0, centre + half))


def score(key_doc, answers):
    by_id = {row["id"]: row for row in key_doc["key"]}
    unknown = [i for i in answers if i not in by_id]

    controls = {"correct": 0, "total": 0, "misses": []}
    real = {"suggestion": 0, "text": 0, "both": 0, "unclear": 0}
    by_path = {}
    repeats = {}
    times = []

    for item_id, given in answers.items():
        row = by_id.get(item_id)
        if row is None:
            continue
        choice = given["choice"] if isinstance(given, dict) else given
        if isinstance(given, dict) and isinstance(given.get("ms"), int):
            times.append(given["ms"])
        stratum = row["stratum"]

        if stratum == "injected":
            controls["total"] += 1
            # We injected the error, so the OTHER word is the original.
            ok = (choice == row["other_option"])
            controls["correct"] += 1 if ok else 0
            if not ok:
                controls["misses"].append((item_id, "injected", choice))
        elif stratum == "clean":
            controls["total"] += 1
            # The word in the text is the answer; "both fine" is acceptable here and only here.
            ok = (choice == row["text_option"] or choice == 3)
            controls["correct"] += 1 if ok else 0
            if not ok:
                controls["misses"].append((item_id, "clean", choice))
        else:
            bucket = ("suggestion" if choice == row["other_option"]
                      else "text" if choice == row["text_option"]
                      else "both" if choice == 3 else "unclear")
            if stratum.startswith("repeat:"):
                repeats[row["source_id"]] = bucket
                continue
            real[bucket] += 1
            path = row["path"]
            slot = by_path.setdefault(path, {"suggestion": 0, "text": 0, "abstain": 0})
            slot["suggestion" if bucket == "suggestion"
                 else "text" if bucket == "text" else "abstain"] += 1

    return {"controls": controls, "real": real, "by_path": by_path,
            "repeats": repeats, "times": times, "unknown": unknown}


def report(key_doc, answers, prior_answers=None):
    s = score(key_doc, answers)
    c, r = s["controls"], s["real"]
    decided = r["suggestion"] + r["text"]
    total = decided + r["both"] + r["unclear"]
    lines = []
    verdict = "OK"

    lines.append("=" * 72)
    lines.append(f"{key_doc['batch_id']}  seed {key_doc['seed']}  "
                 f"corpus {key_doc['corpus_sha256'][:12]}")

    on_disk = None
    protocol = os.path.join(ROOT, "docs", "LABELING_PROTOCOL.md")
    if os.path.isfile(protocol):
        import hashlib
        on_disk = hashlib.sha256(open(protocol, "rb").read()).hexdigest()
    # Drift is reported, never conflated with the scoring outcome. They are independent
    # facts: a batch cut under an older revision of the protocol can still be scored, and a
    # batch cut under the current one can still be void. An earlier version overwrote the
    # verdict with PROTOCOL-DRIFT, which made the self-test unable to reach any verdict at
    # all once the protocol was amended -- the check masking the thing it was meant to guard.
    if on_disk and on_disk != key_doc["protocol_sha256"]:
        lines.append("  !! docs/LABELING_PROTOCOL.md has CHANGED since this batch was cut.")
        lines.append("     The rule these labels were collected under is not the rule on "
                     "disk. Read the diff before reading anything below.")

    if s["unknown"]:
        lines.append(f"  !! {len(s['unknown'])} answers name items that are not in this key")
        verdict = "VOID"

    lines.append("")
    bar = math.ceil(CONTROL_RATE * c["total"]) if c["total"] else 0
    lines.append(f"  controls    : {c['correct']} / {c['total']} correct "
                 f"(bar: {bar}, {100*CONTROL_RATE:.0f}%)")
    for item_id, kind, choice in c["misses"][:8]:
        lines.append(f"                miss {item_id} [{kind}] answered {choice}")

    if s["times"]:
        t = sorted(s["times"])
        lines.append(f"  pace        : median {t[len(t)//2]/1000:.1f} s/item, "
                     f"{sum(t)/60000:.0f} min total over {len(t)} items")

    if c["total"] and c["correct"] < bar:
        lines.append("")
        lines.append("  VERDICT: VOID -- the labeller did not clear the control bar fixed in")
        lines.append("           docs/LABELING_PROTOCOL.md. No precision is computed, because")
        lines.append("           a number that has been seen is a number that can be argued")
        lines.append("           with. Re-run on a fresh batch.")
        lines.append("=" * 72)
        return "VOID", "\n".join(lines)

    abstention = (r["both"] + r["unclear"]) / total if total else 0.0
    lines.append(f"  abstentions : {r['both'] + r['unclear']} / {total} = "
                 f"{100*abstention:.1f}%  (bar: <= {100*MAX_ABSTENTION:.0f}%)")

    # Self-agreement first, because amendment 2 makes it decide what the BOUND may print.
    # It used to run last, after everything it governs had already been printed.
    four_way = direction = None
    reversals = boundary = tot = same = 0
    if prior_answers:
        decided_set = ("suggestion", "text")
        pairs_decided = 0
        for source_id, bucket in s["repeats"].items():
            before = prior_answers.get(source_id)
            if before is None:
                continue
            tot += 1
            if before == bucket:
                same += 1
                if before in decided_set:
                    pairs_decided += 1
            elif before in decided_set and bucket in decided_set:
                reversals += 1
                pairs_decided += 1
            else:
                boundary += 1
        if tot:
            four_way = same / tot
            lines.append("")
            lines.append(f"  four-way agreement  : {same} / {tot} = {100*four_way:.1f}%  "
                         f"(bar: >= {100*MIN_SELF_AGREEMENT:.0f}%)  -- governs the filtered figure")
            if pairs_decided:
                direction = 1 - reversals / pairs_decided
                lines.append(f"  direction stability : {pairs_decided - reversals} / "
                             f"{pairs_decided} = {100*direction:.1f}%  "
                             f"(bar: >= {100*MIN_DIRECTION_STABILITY:.0f}%)  -- governs the floor")
            else:
                lines.append("  direction stability : no pair was decided both times; "
                             "NOT MEASURED")
            lines.append(f"    reversals {reversals}, abstain-boundary moves {boundary}")

    # The bound, reported on EVERY batch including a NOT DECIDABLE one. It is not precision
    # under a favourable assumption: for any counts, a/n <= a/(a+b) <= (a+c+d)/n, so the
    # bracket always contains the filtered figure and is a strictly weaker claim than it.
    # Added after batch 001, in the open, for the reason recorded in the protocol.
    if total:
        floor_lo, floor_hi = wilson(r["suggestion"], total)
        ceil_k = r["suggestion"] + r["both"] + r["unclear"]
        ceil_lo, ceil_hi = wilson(ceil_k, total)
        lines.append("")
        lines.append(f"  BOUND over all {total} items, whatever the abstentions are:")
        if direction is not None and direction < MIN_DIRECTION_STABILITY:
            lines.append("    floor   WITHHELD -- direction stability is below its bar, and "
                         "the floor moves")
            lines.append("            only on a direction reversal. See amendment 2.")
        else:
            lines.append(f"    floor   {r['suggestion']:>3} / {total} = "
                         f"{100*r['suggestion']/total:5.1f}%   95% [{100*floor_lo:.1f}, "
                         f"{100*floor_hi:.1f}]   (every abstention a loss)")
        lines.append(f"    ceiling {ceil_k:>3} / {total} = {100*ceil_k/total:5.1f}%   "
                     f"95% [{100*ceil_lo:.1f}, {100*ceil_hi:.1f}]   (every abstention a win)")
        if ceil_hi < DECIDE_GOOD:
            lines.append(f"    even the ceiling's upper bound is below the {100*DECIDE_GOOD:.0f}% "
                         f"ship band. No resolution of the")
            lines.append("    abstentions puts this batch in that band.")

    if abstention > MAX_ABSTENTION:
        lines.append("")
        lines.append("  VERDICT: NOT DECIDABLE -- abstentions above the bar. Precision is not")
        lines.append("           computed on the decided subset, which is the easy half by")
        lines.append("           construction. The bound above stands; it uses every item.")
        lines.append("=" * 72)
        return "NOT-DECIDABLE", "\n".join(lines)

    if total == 0:
        lines.append("")
        lines.append("  This batch carries no fresh firings. It is a check on the INSTRUMENT")
        lines.append("  -- the labeller -- and not on the detector, so there is no precision")
        lines.append("  to report from it. The bars above are its whole output.")
        ok = (four_way is not None and four_way >= MIN_SELF_AGREEMENT
              and direction is not None and direction >= MIN_DIRECTION_STABILITY)
        if direction is not None and direction >= MIN_DIRECTION_STABILITY:
            lines.append("")
            lines.append("  VERDICT: direction stability CLEARS its bar. The floors from the")
            lines.append("           batches that carried real firings become publishable, and")
            lines.append("           docs/LABELING_PROTOCOL.md amendment 2 already fixed what")
            lines.append("           that means -- both lower bounds sit far below the 40%")
            lines.append("           decision floor.")
        elif direction is not None:
            lines.append("")
            lines.append("  VERDICT: direction stability FAILS. Nothing from any batch supports")
            lines.append("           a precision claim; A1 is NOT MEASURED and the question")
            lines.append("           needs a second annotator, not a fourth batch.")
        lines.append("=" * 72)
        return ("STABLE" if ok else "UNSTABLE"), "\n".join(lines)

    if decided == 0:
        lines.append("  VERDICT: NOT MEASURED -- no decided items.")
        lines.append("=" * 72)
        return "NOT-MEASURED", "\n".join(lines)

    if four_way is not None and four_way < MIN_SELF_AGREEMENT:
        lines.append("")
        lines.append("  VERDICT: NOISE -- four-way agreement below its bar. No precision")
        lines.append("           figure is published from this run. The bound above stands.")
        lines.append("=" * 72)
        return "NOISE", "\n".join(lines)

    lo, hi = wilson(r["suggestion"], decided)
    lines.append("")
    lines.append(f"  PRECISION   : {r['suggestion']} / {decided} = "
                 f"{100*r['suggestion']/decided:.1f}%")
    lines.append(f"  95% Wilson  : [{100*lo:.1f}%, {100*hi:.1f}%]")
    lines.append("")
    lines.append("  by evidence path (decided items only):")
    for path, slot in sorted(s["by_path"].items()):
        d = slot["suggestion"] + slot["text"]
        rate = f"{100*slot['suggestion']/d:.1f}%" if d else "n/a"
        lines.append(f"    {path:<12} {slot['suggestion']:>3} / {d:<3} = {rate:<7} "
                     f"({slot['abstain']} abstained)")

    band = ("SHIP AS IS" if lo >= DECIDE_GOOD
            else "TIGHTEN AND RE-MEASURE ON A FRESH BATCH" if lo >= DECIDE_POOR
            else "RAISE THE MARGIN SUBSTANTIALLY OR WITHDRAW")
    lines.append("")
    lines.append(f"  VERDICT: lower bound {100*lo:.1f}% -> {band}")
    lines.append("           (bands fixed in docs/LABELING_PROTOCOL.md before any label "
                 "existed)")
    lines.append("=" * 72)
    return verdict, "\n".join(lines)


def synthetic(key_doc, mode, seed=7):
    """Four labellers the scorer has to tell apart.

    The `unsure` one is deliberately ATTENTIVE on controls and abstains only on real items.
    An earlier version of it abstained everywhere and came back VOID rather than NOT
    DECIDABLE -- correctly, because "both fine" is a miss on an injected control by the rule
    fixed in the protocol. The rule was right and the control was wrong, so the control moved.
    """
    rng = random.Random(seed)
    out = {}
    for row in key_doc["key"]:
        control = row["stratum"] in ("clean", "injected")
        right_on_control = (row["text_option"] if row["stratum"] == "clean"
                            else row["other_option"])
        if mode == "perfect":
            choice = right_on_control if control else (
                row["other_option"] if rng.random() < 0.75 else row["text_option"])
        elif mode == "inattentive":
            choice = rng.choice([1, 2])
        elif mode == "unsure":
            # Reads carefully, and finds the real positions genuinely ambiguous. This is the
            # labeller the abstention bar exists for, and the one I actually expect.
            choice = right_on_control if control else (
                3 if rng.random() < 0.5 else row["other_option"])
        else:  # disagrees -- attentive, and the detector is mostly wrong
            choice = right_on_control if control else (
                row["other_option"] if rng.random() < 0.25 else row["text_option"])
        out[row["id"]] = {"choice": choice, "ms": rng.randint(3000, 20000)}
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--key", default=os.path.join(ROOT, "labeling", "batch-001.key.json"))
    ap.add_argument("--labels", help="the JSON the screen produced; '-' reads stdin")
    ap.add_argument("--prior", help="a buckets file from an earlier batch, for self-agreement")
    ap.add_argument("--emit-buckets",
                    help="write this batch's per-source-id outcomes, so the NEXT batch can "
                         "score self-agreement against it")
    ap.add_argument("--self-test", action="store_true",
                    help="POSITIVE CONTROL: score four synthetic labellers and require the "
                         "scorer to void one, call one undecidable, and send one to the "
                         "withdraw band")
    args = ap.parse_args()

    key_doc = json.load(open(args.key, encoding="utf-8"))

    if args.self_test:
        expected = {"perfect": "OK", "inattentive": "VOID",
                    "unsure": "NOT-DECIDABLE", "disagrees": "OK"}
        failures = []
        # The drift check has its own control: it must fire when the hash differs and stay
        # quiet when it does not.
        drifted = dict(key_doc, protocol_sha256="0" * 64)
        _, drift_text = report(drifted, synthetic(key_doc, "perfect"))
        if "has CHANGED since this batch was cut" not in drift_text:
            failures.append("drift: a mismatched protocol hash was not reported")
        undrifted = dict(key_doc, protocol_sha256=__import__("hashlib").sha256(
            open(os.path.join(ROOT, "docs", "LABELING_PROTOCOL.md"), "rb").read()).hexdigest())
        _, quiet_text = report(undrifted, synthetic(key_doc, "perfect"))
        if "has CHANGED since this batch was cut" in quiet_text:
            failures.append("drift: reported a change when the hash matches")
        for mode, want in expected.items():
            got, text = report(key_doc, synthetic(key_doc, mode))
            print(f"--- self-test: {mode} (expect {want}) -> {got}")
            print(text)
            if got != want:
                failures.append(f"{mode}: expected {want}, got {got}")
        if failures:
            print("SELF-TEST FAILED:", "; ".join(failures))
            return 1
        # AMENDMENT 2's paths need their own control. They are reached only by a batch of
        # repeats with no fresh firings, which the four synthetic labellers above never
        # produce, so without this the new code would ship unexercised.
        repeat_key = os.path.join(ROOT, "labeling", "batch-003.key.json")
        if os.path.isfile(repeat_key):
            rk = json.load(open(repeat_key, encoding="utf-8"))
            prior = {}
            for name in ("batch-001", "batch-002"):
                bp = os.path.join(ROOT, "labeling", "results", f"{name}.buckets.json")
                if os.path.isfile(bp):
                    prior.update(json.load(open(bp, encoding="utf-8"))["buckets"])
            for mode, want in (("stable", "STABLE"), ("reverser", "UNSTABLE")):
                rng2 = random.Random(11)
                ans = {}
                for row in rk["key"]:
                    if row["stratum"] == "clean":
                        c = row["text_option"]
                    elif row["stratum"] == "injected":
                        c = row["other_option"]
                    else:
                        was = prior.get(row["source_id"], "text")
                        if mode == "reverser" and was in ("suggestion", "text") \
                                and rng2.random() < 0.5:
                            was = "text" if was == "suggestion" else "suggestion"
                        c = {"suggestion": row["other_option"], "text": row["text_option"],
                             "both": 3, "unclear": 4}[was]
                    ans[row["id"]] = {"choice": c, "ms": 4000}
                got, text = report(rk, ans, prior)
                print(f"--- self-test: {mode} on a repeats-only batch (expect {want}) -> {got}")
                for line in text.splitlines():
                    if "stability" in line or "VERDICT" in line or "reversals" in line:
                        print("   ", line.strip())
                if got != want:
                    failures.append(f"{mode}: expected {want}, got {got}")
        else:
            print("--- self-test: no batch-003 key on disk; amendment 2's paths NOT EXERCISED")
            failures.append("amendment 2 paths were not exercised")

        # "disagrees" returning OK is not a pass on its own -- what matters is the BAND it
        # lands in. A scorer that can only ever print the favourable verdict is not a scorer.
        _, text = report(key_doc, synthetic(key_doc, "disagrees"))
        if "RAISE THE MARGIN" not in text:
            print("SELF-TEST FAILED: an attentive labeller who disagrees with the detector "
                  "three times in four did not land in the withdraw band. The decision band "
                  "cannot produce its own unfavourable verdict.")
            return 1
        print("SELF-TEST PASSED: the scorer voids an inattentive labeller, reports NOT "
              "DECIDABLE for one who abstains on the real items, reaches the withdraw "
              "band when the detector is mostly wrong, and reports protocol drift only when "
              "there is drift. It is capable of failing.")
        return 0

    if not args.labels:
        ap.error("--labels is required unless --self-test")
    raw = sys.stdin.read() if args.labels == "-" else open(
        args.labels, encoding="utf-8").read()
    payload = json.loads(raw)
    answers = payload.get("answers", payload)
    prior = None
    if args.prior:
        p = json.load(open(args.prior, encoding="utf-8"))
        prior = p.get("buckets")
    _, text = report(key_doc, answers, prior)
    print(text)

    if args.emit_buckets:
        by_id = {row["id"]: row for row in key_doc["key"]}
        buckets = {}
        for item_id, given in answers.items():
            row = by_id.get(item_id)
            if row is None or row["stratum"] != "real":
                continue
            choice = given["choice"] if isinstance(given, dict) else given
            buckets[row["source_id"]] = (
                "suggestion" if choice == row["other_option"]
                else "text" if choice == row["text_option"]
                else "both" if choice == 3 else "unclear")
        with open(args.emit_buckets, "w", encoding="utf-8") as fh:
            json.dump({"batch_id": key_doc["batch_id"], "buckets": buckets}, fh,
                      ensure_ascii=False, indent=1)
            fh.write("\n")
        print(f"wrote {len(buckets)} outcomes to {args.emit_buckets}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

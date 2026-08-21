#!/usr/bin/env python3
"""A1 tool 2 of 4 -- assemble one labelling batch, blinded, from the harvested pool.

Every random decision in the protocol lives here and nowhere else: which candidates are
drawn, in what order they are presented, and which of the two words is shown first. All of
them come from ONE seed, which is written into both outputs, so a batch is reproducible from
its own manifest.

### The blinding is a property of the output files, not of good intentions
Two files come out of a run:

  labeling/batch-NNN.json      what the screen loads. Options in random order, no answer,
                               no flag saying which word was in the text.
  labeling/batch-NNN.key.json  the answer key, the stratum of each item, and which evidence
                               path spoke. The screen never fetches it.

The screen is generated with the batch inlined, so it can be opened from a file:// URL or
published, and in neither case does it have anywhere to fetch an answer from.

### What it refuses to do
- Draw an item that appeared in an earlier batch, unless it is deliberately repeated for the
  self-agreement check with --repeat-from.
- Emit a batch whose stratum sizes disagree with docs/LABELING_PROTOCOL.md without --force,
  because the control bar in that document is stated as "18 of 20".

See docs/LABELING_PROTOCOL.md. That document was committed before this script existed.
"""

import argparse
import glob
import hashlib
import json
import os
import random
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LABEL_DIR = os.path.join(ROOT, "labeling")
CANDIDATES = os.path.join(LABEL_DIR, "candidates.jsonl")
RESULTS = os.path.join(LABEL_DIR, "results")
TEMPLATE = os.path.join(ROOT, "scripts", "label_screen_template.html")

# The protocol's batch shape. Changing these changes what the control bar means.
PROTOCOL_REAL = 80
PROTOCOL_CLEAN = 10
PROTOCOL_INJECTED = 10


def load_pool(path):
    manifest, pools = None, {"firing": [], "clean": [], "injected": []}
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            rec = json.loads(line)
            if rec["kind"] == "manifest":
                manifest = rec
            else:
                pools[rec["kind"]].append(rec)
    if manifest is None:
        sys.exit("candidates file has no manifest line; re-run the harvester")
    return manifest, pools


def previously_used(key_files):
    """Source ids already asked about, read from the KEY files.

    The presented files no longer carry a source id at all — it began with the stratum name
    ("firing-95-8", "clean-88-9"), so a presented file that included it told anyone who opened
    it which items were controls. The key is the only place the mapping lives.
    """
    used = set()
    for path in key_files:
        with open(path, encoding="utf-8") as fh:
            for row in json.load(fh)["key"]:
                used.add(row["source_id"])
    return used


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--batch", type=int, default=1, help="batch number, 1-based")
    ap.add_argument("--seed", type=int, default=None,
                    help="omit to derive it from the batch number, which keeps batches "
                         "reproducible without a seed to remember")
    ap.add_argument("--real", type=int, default=PROTOCOL_REAL)
    ap.add_argument("--clean", type=int, default=PROTOCOL_CLEAN)
    ap.add_argument("--injected", type=int, default=PROTOCOL_INJECTED)
    ap.add_argument("--repeat-from", nargs="*", default=None,
                    help="earlier batch KEY files. Their real items become the pool the "
                         "self-agreement repeats are drawn from, re-shuffled independently. "
                         "Each file's outcomes are read from the matching buckets file in "
                         "labeling/results/, which is what makes a stratified draw possible.")
    ap.add_argument("--repeat-decided", type=int, default=None,
                    help="how many repeats to draw from items DECIDED the first time. "
                         "Direction stability is only defined on pairs decided twice, so an "
                         "unstratified draw spends most of its items where it cannot be "
                         "measured. See amendment 2 in docs/LABELING_PROTOCOL.md.")
    ap.add_argument("--repeat-abstained", type=int, default=None,
                    help="how many repeats to draw from items ABSTAINED the first time")
    ap.add_argument("--force", action="store_true",
                    help="allow stratum sizes that differ from the protocol")
    args = ap.parse_args()

    if not args.force and (args.real, args.clean, args.injected) != (
            PROTOCOL_REAL, PROTOCOL_CLEAN, PROTOCOL_INJECTED):
        sys.exit("stratum sizes differ from docs/LABELING_PROTOCOL.md; pass --force and say "
                 "why in the commit message, because the '18 of 20' control bar is stated "
                 "against 20 controls")

    if not os.path.isfile(CANDIDATES):
        sys.exit(f"{CANDIDATES} missing; run ./gradlew :core:harvestLabelCandidates")

    manifest, pools = load_pool(CANDIDATES)
    seed = args.seed if args.seed is not None else 20260821 + args.batch
    rng = random.Random(seed)

    earlier = sorted(glob.glob(os.path.join(LABEL_DIR, "batch-*.key.json")))
    earlier = [p for p in earlier
               if os.path.basename(p) != f"batch-{args.batch:03d}.key.json"]
    used = previously_used(earlier)

    def draw(kind, n):
        available = [r for r in pools[kind] if r["id"] not in used]
        if len(available) < n:
            sys.exit(f"only {len(available)} unused {kind} candidates, need {n}")
        available.sort(key=lambda r: r["id"])          # deterministic before sampling
        return rng.sample(available, n)

    drawn = (
        [(r, "real") for r in draw("firing", args.real)]
        + [(r, "clean") for r in draw("clean", args.clean)]
        + [(r, "injected") for r in draw("injected", args.injected)]
    )

    repeats = []
    if args.repeat_from:
        # The KEY files, not the presented ones: only a key knows which pool record each item
        # came from, and the sentence is re-fetched from the pool so a repeat is rebuilt from
        # source rather than copied from a file that may have been edited by hand.
        by_source = {r["id"]: r for pool in pools.values() for r in pool}
        already_repeated = set()
        candidates, labels = [], {}
        for path in args.repeat_from:
            prior = json.load(open(path, encoding="utf-8"))
            for row in prior["key"]:
                if row["stratum"].startswith("repeat:"):
                    already_repeated.add(row["source_id"])
                elif row["stratum"] == "real":
                    candidates.append((row["source_id"], prior["batch_id"]))
            buckets_path = os.path.join(
                RESULTS, f"{prior['batch_id']}.buckets.json")
            if os.path.isfile(buckets_path):
                labels.update(json.load(open(buckets_path, encoding="utf-8"))["buckets"])

        # Every repeat must be a clean SECOND observation. An item already re-shown once
        # would be a third, which measures something else and would sit in the same counter.
        candidates = [c for c in candidates if c[0] not in already_repeated]

        want_dec = args.repeat_decided
        want_abs = args.repeat_abstained
        if want_dec is None and want_abs is None:
            # Unstratified, the original behaviour: 15% of the most recent prior batch.
            n_prior = sum(1 for c in candidates if c[1] == candidates[-1][1])
            want_dec, want_abs = 0, 0
            picked = rng.sample(sorted(candidates), max(1, round(0.15 * n_prior)))
        else:
            decided = sorted(c for c in candidates
                             if labels.get(c[0]) in ("suggestion", "text"))
            abstained = sorted(c for c in candidates
                               if labels.get(c[0]) in ("both", "unclear"))
            if not labels:
                sys.exit("a stratified repeat draw needs the earlier batches' buckets files; "
                         "run score_labels.py --emit-buckets for each of them first")
            for name, pool_, want in (("decided", decided, want_dec or 0),
                                      ("abstained", abstained, want_abs or 0)):
                if len(pool_) < want:
                    sys.exit(f"only {len(pool_)} {name} items available to repeat, need {want}")
            picked = (rng.sample(decided, want_dec or 0)
                      + rng.sample(abstained, want_abs or 0))

        for source_id, prior_batch in picked:
            rec = by_source.get(source_id)
            if rec is None:
                sys.exit(f"{source_id} is not in the current pool; the harvest was re-run "
                         f"against a different corpus and repeats would not be the same items")
            repeats.append((rec, "repeat:" + prior_batch))

    items, key = [], []
    order = drawn + repeats
    rng.shuffle(order)
    for n, (rec, stratum) in enumerate(order, start=1):
        # The two words, in an order the screen cannot undo and the key records.
        pair = [rec["typed"], rec["other"]]
        other_first = rng.random() < 0.5
        options = pair if not other_first else [pair[1], pair[0]]
        item_id = f"b{args.batch:03d}-{n:03d}"
        items.append({
            "id": item_id,
            "sentence": rec["sentence"],
            "position": rec["position"],
            "options": options,
            # No source id here. It begins with the stratum name, so carrying it in the file
            # the screen loads would tell anyone who opened that file which items are
            # controls — which is the whole blind, given away in a field nobody reads twice.
        })
        key.append({
            "id": item_id,
            "source_id": rec["id"],
            "stratum": stratum,
            "path": rec["path"],
            # Which numbered option is the word standing in the sentence, and which is
            # the other one. What "other" MEANS depends on the stratum -- the detector's
            # suggestion on a real item, the original on an injected one, a distractor the
            # detector never offered on a clean one -- and score_labels.py resolves it from
            # the stratum. No field here carries two meanings.
            "other_option": 1 if other_first else 2,
            "text_option": 2 if other_first else 1,
            "typed": rec["typed"],
            "other": rec["other"],
        })

    batch_id = f"batch-{args.batch:03d}"
    protocol_sha = hashlib.sha256(
        open(os.path.join(ROOT, "docs", "LABELING_PROTOCOL.md"), "rb").read()).hexdigest()

    presented = {
        "batch_id": batch_id,
        "seed": seed,
        "protocol": protocol_sha[:16],
        "items": items,
    }
    key_doc = {
        "batch_id": batch_id,
        "seed": seed,
        "protocol_sha256": protocol_sha,
        "corpus": manifest["corpus"],
        "corpus_sha256": manifest["corpus_sha256"],
        "pool": {k: len(v) for k, v in pools.items()},
        # The pool's evidence-path breakdown, recorded because it decides what a UNIFORM
        # sample can possibly be about. On clean conversational text the adjacent path is
        # 99.5% of all firings, so a batch drawn under the protocol measures that path and
        # says nothing about the two added in S1+P1.
        "firing_pool_by_path": {
            path: sum(1 for r in pools["firing"] if r["path"] == path)
            for path in sorted({r["path"] for r in pools["firing"]})
        },
        "strata": {
            "real": args.real, "clean": args.clean, "injected": args.injected,
            "repeat": len(repeats),
        },
        "excluded_batches": [os.path.basename(p) for p in earlier],
        "key": key,
    }

    os.makedirs(LABEL_DIR, exist_ok=True)
    out_json = os.path.join(LABEL_DIR, f"{batch_id}.json")
    out_key = os.path.join(LABEL_DIR, f"{batch_id}.key.json")
    for path, doc in ((out_json, presented), (out_key, key_doc)):
        with open(path, "w", encoding="utf-8") as fh:
            json.dump(doc, fh, ensure_ascii=False, indent=1)
            fh.write("\n")

    body = open(TEMPLATE, encoding="utf-8").read()
    title = f"תיוג אצווה {args.batch:03d}"
    body = (body.replace("__BATCH_JSON__", json.dumps(presented, ensure_ascii=False))
                .replace("__TITLE__", title))
    with open(os.path.join(LABEL_DIR, f"{batch_id}.artifact.html"), "w",
              encoding="utf-8") as fh:
        fh.write(body)
    with open(os.path.join(LABEL_DIR, f"{batch_id}.html"), "w", encoding="utf-8") as fh:
        fh.write('<!doctype html>\n<html lang="he" dir="rtl">\n<head>\n'
                 '<meta charset="utf-8">\n'
                 '<meta name="viewport" content="width=device-width, initial-scale=1">\n'
                 + body + "\n</body>\n</html>\n")

    print(f"{batch_id}: {len(items)} items "
          f"({args.real} real, {args.clean} clean, {args.injected} injected, "
          f"{len(repeats)} repeat), seed {seed}")
    print(f"  pool           : {len(pools['firing'])} firings available, "
          f"{len(used)} already used")
    print(f"  presented      : {os.path.relpath(out_json, ROOT)}")
    print(f"  answer key     : {os.path.relpath(out_key, ROOT)}")
    print(f"  screen (local) : labeling/{batch_id}.html")
    print(f"  screen (artifact): labeling/{batch_id}.artifact.html")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

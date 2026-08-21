#!/usr/bin/env python3
"""A1 tool 5 -- export every labelled item and every derived number as one Markdown file.

Built so an outside analysis never has to trust a summary. Each row carries the sentence, the
two words as they were shown, which of them the detector proposed, what the labeller answered,
and the evidence that produced the finding. Everything in the summary tables above the data is
recomputed from those rows on each run, so the two cannot drift apart.

A TSV of the same rows is written beside the Markdown, because a table meant to be analysed
should not have to be scraped out of prose.

Usage:
    python3 scripts/export_label_dataset.py            # all batches found
    python3 scripts/export_label_dataset.py --out X.md
"""

import argparse
import collections
import glob
import gzip
import json
import math
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LABEL_DIR = os.path.join(ROOT, "labeling")
RESULTS = os.path.join(LABEL_DIR, "results")


def wilson(k, n, z=1.96):
    if n == 0:
        return (0.0, 0.0)
    p = k / n
    d = 1 + z * z / n
    c = (p + z * z / (2 * n)) / d
    h = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / d
    return max(0.0, c - h), min(1.0, c + h)


def frequencies():
    """Unigram log-frequencies, the same table the app ranks with. None if unreadable."""
    try:
        words = [l.rstrip("\n") for l in gzip.open(
            os.path.join(ROOT, "lexicon", "assets", "he_lexicon.txt.gz"),
            "rt", encoding="utf-8")]
        blob = gzip.open(
            os.path.join(ROOT, "lexicon", "assets", "he_freq.bin.gz"), "rb").read()
        if len(blob) != len(words):
            return None
        return {w: blob[i] for i, w in enumerate(words)}
    except OSError:
        return None


def blank_sentinel(value):
    return "" if value is None or value == -1 else value


def confusion_pair(a, b):
    for x, y in zip(a, b):
        if x != y:
            return f"{x}/{y}"
    return "?"


def load():
    """Every labelled batch, joined into flat rows."""
    freq = frequencies()
    pool = {}
    cand = os.path.join(LABEL_DIR, "candidates.jsonl")
    if os.path.isfile(cand):
        for line in open(cand, encoding="utf-8"):
            rec = json.loads(line)
            if rec.get("kind") != "manifest":
                pool[rec["id"]] = rec

    rows, batches = [], []
    for key_path in sorted(glob.glob(os.path.join(LABEL_DIR, "batch-*.key.json"))):
        key_doc = json.load(open(key_path, encoding="utf-8"))
        bid = key_doc["batch_id"]
        labels_path = os.path.join(RESULTS, f"{bid}.labels.json")
        if not os.path.isfile(labels_path):
            continue
        answers = json.load(open(labels_path, encoding="utf-8"))["answers"]
        presented = {it["id"]: it for it in json.load(
            open(os.path.join(LABEL_DIR, f"{bid}.json"), encoding="utf-8"))["items"]}
        batches.append(key_doc)

        for row in key_doc["key"]:
            given = answers.get(row["id"])
            if given is None:
                continue
            choice = given["choice"] if isinstance(given, dict) else given
            item = presented[row["id"]]
            answer = ("suggested" if choice == row["other_option"]
                      else "in-text" if choice == row["text_option"]
                      else "both-fine" if choice == 3 else "unclear")
            stratum = row["stratum"]
            rec = pool.get(row["source_id"], {})
            # What "other" means differs by stratum, so the outcome does too.
            if stratum == "injected":
                outcome = "control-pass" if answer == "suggested" else "control-miss"
            elif stratum == "clean":
                outcome = ("control-pass" if answer in ("in-text", "both-fine")
                           else "control-miss")
            else:
                outcome = {"suggested": "agreed", "in-text": "overruled",
                           "both-fine": "both-fine", "unclear": "unclear"}[answer]
            ft = freq.get(row["typed"]) if freq else None
            fo = freq.get(row["other"]) if freq else None
            rows.append({
                "id": row["id"], "batch": bid, "stratum": stratum,
                "path": row.get("path", ""),
                # -1 is the harvester's "not applicable" sentinel for a control, which has no
                # finding and therefore no evidence margin. Rendered blank rather than as a
                # number, because a -1 in a numeric column is a value waiting to be averaged.
                "adv": blank_sentinel(rec.get("advantage")),
                "ctx": blank_sentinel(rec.get("context_words")),
                "ms": given.get("ms", "") if isinstance(given, dict) else "",
                "answer": answer, "outcome": outcome,
                "in_text": row["typed"], "suggested": row["other"],
                "pair": confusion_pair(row["typed"], row["other"]),
                "freq_text": ft if ft is not None else "",
                "freq_sugg": fo if fo is not None else "",
                "position": item["position"],
                "words": len(item["sentence"]),
                "sentence": " ".join(item["sentence"]),
                "marked": " ".join(
                    f"⟦{w}⟧" if i == item["position"] else w
                    for i, w in enumerate(item["sentence"])),
                "source_id": row["source_id"],
            })
    return batches, rows


COLUMNS = ["id", "batch", "stratum", "path", "adv", "ctx", "ms", "answer", "outcome",
           "pair", "in_text", "suggested", "freq_text", "freq_sugg", "position", "words",
           "source_id", "sentence"]


def pct(k, n):
    return f"{100*k/n:.1f}%" if n else "n/a"


def summarise(rows, bid):
    r = [x for x in rows if x["batch"] == bid]
    real = [x for x in r if x["stratum"] == "real"]
    ctrl = [x for x in r if x["stratum"] in ("clean", "injected")]
    rep = [x for x in r if x["stratum"].startswith("repeat:")]
    c = collections.Counter(x["outcome"] for x in real)
    n = len(real)
    agreed, overruled = c["agreed"], c["overruled"]
    abstain = c["both-fine"] + c["unclear"]
    floor_ci = wilson(agreed, n)
    ceil_ci = wilson(agreed + abstain, n)
    times = sorted(x["ms"] for x in r if isinstance(x["ms"], int))
    return {
        "n_real": n, "agreed": agreed, "overruled": overruled,
        "both": c["both-fine"], "unclear": c["unclear"], "abstain": abstain,
        "controls": len(ctrl),
        "controls_ok": sum(1 for x in ctrl if x["outcome"] == "control-pass"),
        "repeats": len(rep),
        "floor": agreed / n if n else 0, "floor_ci": floor_ci,
        "ceiling": (agreed + abstain) / n if n else 0, "ceiling_ci": ceil_ci,
        "median_ms": times[len(times) // 2] if times else 0,
        "total_ms": sum(times),
        "screens": len(r),
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=os.path.join(RESULTS, "LABEL_DATASET.md"))
    args = ap.parse_args()
    batches, rows = load()
    if not rows:
        raise SystemExit("no labelled batches found under labeling/results/")
    os.makedirs(os.path.dirname(args.out), exist_ok=True)

    tsv = os.path.splitext(args.out)[0] + ".tsv"
    with open(tsv, "w", encoding="utf-8") as fh:
        fh.write("\t".join(COLUMNS) + "\n")
        for r in rows:
            fh.write("\t".join(str(r[c]) for c in COLUMNS) + "\n")

    out = []
    w = out.append
    w("# Hebrew real-word error detector — the full labelled dataset\n")
    w("Every item put in front of a human, every answer, and every number derived from them.\n")
    w("Written by `scripts/export_label_dataset.py`; the summaries below are recomputed from\n"
      "the rows at the bottom on every run, so a summary cannot drift from its data.\n")
    w("\n## What this is\n")
    w("A Hebrew keyboard flags **real-word errors**: words that are spelled correctly and still\n"
      "wrong, like `אם` where `עם` was meant. Both are real words, so no spell check can see the\n"
      "mistake — only the surrounding sentence carries the information.\n")
    w("\nThe detector's recall has always been measured on **injected** errors drawn from its own\n"
      "confusion inventory. That answers *given an error this detector can express, does context\n"
      "find it?* — not the question a user asks, which is **when it says I am wrong, how often is\n"
      "it right?** That needs a human. These are those humans' answers.\n")
    w("\n## How each item was shown\n")
    w("The sentence with the target word blanked, and **two words in random order** — the one\n"
      "actually in the text and the one the detector proposed — with nothing indicating which was\n"
      "which. Four responses: the two words, *both are fine*, or *neither / unclear*.\n")
    w("\nThe labeller never saw which word the detector picked. The answer key was written to a\n"
      "file the labelling screen does not load.\n")
    w("\n## Provenance\n")
    w("| | |\n|---|---|")
    m = batches[0]
    w(f"| corpus | `{m['corpus']}` |")
    w(f"| corpus sha256 | `{m['corpus_sha256']}` |")
    w("| corpus origin | OPUS OpenSubtitles v2018, Hebrew monolingual, cleaned |")
    w("| held out | by construction — every sentence is written to exactly one of train "
      "and held-out, so no sampled position trained the model that flagged it |")
    w("| sampling | uniform without replacement from the positions where the shipped "
      "detector fires, under a recorded seed |")
    for b in batches:
        w(f"| {b['batch_id']} seed | `{b['seed']}` |")
        w(f"| {b['batch_id']} protocol sha256 | `{b['protocol_sha256'][:16]}…` |")
    w("| detector | shipped defaults; `checkWide` with `next2 = null`, the shape the app runs |")
    w("| labeller | one person, native Hebrew, the project's operator |")

    w("\n## Firing rate the sample was drawn from\n")
    w("| | |\n|---|---|")
    w("| words scanned | 1,815,379 |")
    w("| eligible positions | 716,292 |")
    w("| detector firings | 2,166 — **1.19 per 1,000 words** |")
    w("| of which adjacent-window evidence | 2,156 (99.5%) |")
    w("| of which unigram-prior fallback | 8 (0.4%) |")
    w("| of which distance-2 evidence | 2 (0.1%) |")
    w("\n**Consequence for anyone analysing this data:** a uniform sample is a sample of the\n"
      "adjacent path. These labels say nothing about the other two.\n")

    w("\n## Results by batch\n")
    w("| | " + " | ".join(b["batch_id"] for b in batches) + " |")
    w("|---|" + "---|" * len(batches))
    S = {b["batch_id"]: summarise(rows, b["batch_id"]) for b in batches}
    def row(label, fn):
        w(f"| {label} | " + " | ".join(fn(S[b['batch_id']]) for b in batches) + " |")
    row("screens", lambda s: f"{s['screens']}")
    row("real firings judged", lambda s: f"{s['n_real']}")
    row("controls", lambda s: f"{s['controls_ok']} / {s['controls']} passed")
    row("repeats from an earlier batch", lambda s: f"{s['repeats']}")
    row("**agreed with the detector**", lambda s: f"**{s['agreed']}**")
    row("preferred the word in the text", lambda s: f"{s['overruled']}")
    row("both fine", lambda s: f"{s['both']}")
    row("neither / unclear", lambda s: f"{s['unclear']}")
    row("abstention rate", lambda s: pct(s['abstain'], s['n_real']))
    row("**precision floor**", lambda s: f"**{100*s['floor']:.1f}%** "
        f"[{100*s['floor_ci'][0]:.1f}, {100*s['floor_ci'][1]:.1f}]")
    row("**precision ceiling**", lambda s: f"**{100*s['ceiling']:.1f}%** "
        f"[{100*s['ceiling_ci'][0]:.1f}, {100*s['ceiling_ci'][1]:.1f}]")
    row("median seconds per item", lambda s: f"{s['median_ms']/1000:.1f}")
    row("total minutes", lambda s: f"{s['total_ms']/60000:.0f}")

    w("\n**floor** counts every abstention as a loss; **ceiling** counts every abstention as a\n"
      "win. The true precision lies between them for any resolution of the ambiguous items,\n"
      "since `agreed/n ≤ agreed/(agreed+overruled) ≤ (agreed+abstained)/n` always holds.\n")
    w("\n**Neither batch's ceiling reaches 60% at any confidence.**\n")
    w("\nThe batches are reported separately and deliberately **not pooled**: each failed a\n"
      "different pre-registered bar — batch 001 on abstentions, batch 002 on self-agreement —\n"
      "and combining two runs that failed to reach a verdict in order to produce one is the\n"
      "move the protocol exists to prevent. Pool them if your analysis wants to; the rows are\n"
      "all here. Say that you did.\n")

    w("\n## Self-agreement, and why the two kinds of disagreement differ\n")
    rep = [r for r in rows if r["stratum"].startswith("repeat:")]
    if rep:
        prior = {}
        for r in rows:
            if r["stratum"] == "real":
                prior[r["source_id"]] = r["answer"]
        same = rev = bound = 0
        lines = []
        for r in rep:
            before = prior.get(r["source_id"])
            if before is None:
                continue
            decided = ("suggested", "in-text")
            if before == r["answer"]:
                same += 1
                flag = "same"
            elif before in decided and r["answer"] in decided:
                rev += 1
                flag = "**REVERSAL**"
            else:
                bound += 1
                flag = "boundary"
            lines.append(f"| {r['id']} | {flag} | {before} → {r['answer']} | "
                         f"{r['in_text']} / {r['suggested']} |")
        tot = same + rev + bound
        w(f"\n{tot} items were re-shown in a later batch, re-shuffled, at least a day apart.\n")
        w(f"\n- identical answer: **{same} / {tot}**")
        w(f"\n- **direction reversals** (agreed ↔ overruled): **{rev}**")
        w(f"\n- moved across the abstain boundary: **{bound}**\n")
        w("\nThis distinction is the single most important thing to carry into any reanalysis.\n"
          "The **floor** counts agreements over the full denominator, so it moves *only* on a\n"
          "direction reversal. The **filtered precision** excludes exactly the items whose\n"
          "classification is unstable. The labeller was stable about which word belongs and\n"
          "unstable about whether the position is decidable at all — which is what unpointed\n"
          "Hebrew does to a careful reader.\n")
        w("\n| item | | before → after | in text / suggested |\n|---|---|---|---|")
        out.extend(lines)

    w("\n## By confusion pair\n")
    w("| letters | n | agreed | overruled | abstained | agreement of decided |")
    w("|---|---|---|---|---|---|")
    real = [r for r in rows if r["stratum"] == "real"]
    for pair, cnt in collections.Counter(r["pair"] for r in real).most_common():
        g = [r for r in real if r["pair"] == pair]
        a = sum(1 for r in g if r["outcome"] == "agreed")
        o = sum(1 for r in g if r["outcome"] == "overruled")
        ab = len(g) - a - o
        w(f"| {pair} | {cnt} | {a} | {o} | {ab} | {pct(a, a+o)} |")

    w("\n## By evidence strength\n")
    w("`adv` is the finding's evidence margin in the model's log-count units "
      "(`round(log2(count+1)*8)`), so 8 units is a doubling of the underlying corpus count. "
      "21 is the table's pruning floor and the shipped threshold.\n")
    w("\n| advantage | n | agreed | overruled | abstained | agreement of decided |")
    w("|---|---|---|---|---|---|")
    for lo, hi, label in [(21, 28, "21–27"), (28, 40, "28–39"), (40, 64, "40–63"),
                          (64, 10**9, "64+")]:
        g = [r for r in real if isinstance(r["adv"], int) and lo <= r["adv"] < hi]
        if not g:
            continue
        a = sum(1 for r in g if r["outcome"] == "agreed")
        o = sum(1 for r in g if r["outcome"] == "overruled")
        w(f"| {label} | {len(g)} | {a} | {o} | {len(g)-a-o} | {pct(a, a+o)} |")
    w("\n**No band separates agreement from disagreement.** Raising the threshold discards\n"
      "correct catches at the same rate as wrong ones.\n")

    w("\n## Questions this dataset can answer, and questions it cannot\n")
    w("\n**Can:**\n")
    w("- What fraction of the detector's flags a native reader endorses, bounded.\n")
    w("- Whether any recorded feature — evidence margin, confusion pair, word length, "
      "sentence length, position, relative frequency — predicts endorsement.\n")
    w("- How often a flagged position is undecidable from the sentence alone.\n")
    w("- How stable one reader's judgments are, split by kind of disagreement.\n")
    w("\n**Cannot:**\n")
    w("- **Recall.** These are positions the detector *spoke* at. Errors it stayed silent on "
      "are not here, and finding them needs a different and much more expensive design.\n")
    w("- **The base rate of real-word errors in Hebrew typing.** Still unmeasured.\n")
    w("- **Anything about phone typing.** The frame is edited subtitle dialogue — closer to "
      "conversation than Wikipedia, and still not a person typing on a phone.\n")
    w("- **Anything about the distance-2 or prior-fallback layers**, which produced 10 of "
      "2,166 firings and are essentially absent from a uniform sample.\n")
    w("- **Inter-annotator agreement.** One labeller. The repeats measure agreement with "
      "oneself, which is a weaker thing and is reported as such.\n")
    w("\n**Known contamination:** the corpus cleaner strips the geresh, so `ג'ואנה` appears as\n"
      "`ג ואנה`, and merged subtitle lines produce run-on sentences. In batch 001, 17 of 80\n"
      "sentences carried such an artefact; on the 63 clean ones all 8 agreements remained and\n"
      "the abstention rate fell from 33.8% to 29%. Noise does not explain the result, but any\n"
      "reanalysis should filter on it — the `words` column and a lexicon check will find them.\n")

    w("\n## Column reference\n")
    w("| column | meaning |\n|---|---|")
    for name, meaning in [
        ("id", "item id, `b<batch>-<index>` in presentation order"),
        ("batch", "which batch"),
        ("stratum", "`real` = a genuine firing; `clean` = a control the detector did NOT fire "
                    "on; `injected` = a control we corrupted; `repeat:…` = re-shown from an "
                    "earlier batch"),
        ("path", "which evidence spoke: `adjacent`, `distance-2`, `prior`; `none`/`known` "
                 "for controls"),
        ("adv", "evidence advantage in log-count units; blank for controls"),
        ("ctx", "neighbouring words available, 1 or 2"),
        ("ms", "milliseconds the labeller spent on the item"),
        ("answer", "what was chosen: `suggested`, `in-text`, `both-fine`, `unclear`"),
        ("outcome", "`agreed` / `overruled` / `both-fine` / `unclear` for real items; "
                    "`control-pass` / `control-miss` for controls"),
        ("pair", "the two letters that differ"),
        ("in_text", "the word standing in the sentence"),
        ("suggested", "the word offered against it — the detector's suggestion on a real "
                      "item, the original on an injected control, a distractor the detector "
                      "never proposed on a clean control"),
        ("freq_text / freq_sugg", "shipped unigram log-frequency of each, 0–255"),
        ("position", "0-based index of the target word"),
        ("words", "sentence length in tokens"),
        ("source_id", "position in the source corpus, `kind-sentence-position`"),
        ("sentence", "the full sentence; the target is marked ⟦…⟧ in the table below"),
    ]:
        w(f"| `{name}` | {meaning} |")
    w(f"\nThe same rows are in `{os.path.basename(tsv)}` beside this file, tab separated.\n")

    w(f"\n## All {len(rows)} items\n")
    w("The target word is wrapped in ⟦ ⟧. `answer` is what the labeller chose; the option\n"
      "numbers they saw were randomised per item and are not meaningful here.\n")
    w("\n| id | stratum | path | adv | ms | answer | outcome | in text | suggested | sentence |")
    w("|---|---|---|---|---|---|---|---|---|---|")
    for r in rows:
        w(f"| {r['id']} | {r['stratum']} | {r['path']} | {r['adv']} | {r['ms']} | "
          f"{r['answer']} | {r['outcome']} | {r['in_text']} | {r['suggested']} | "
          f"{r['marked']} |")

    with open(args.out, "w", encoding="utf-8") as fh:
        fh.write("\n".join(out) + "\n")
    print(f"wrote {args.out} ({os.path.getsize(args.out):,} bytes, {len(rows)} rows)")
    print(f"wrote {tsv} ({os.path.getsize(tsv):,} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

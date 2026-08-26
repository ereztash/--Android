#!/usr/bin/env python3
"""GATE-CORPUS-1/2 — a corpus may not delete a character class without saying so.

### The defect this exists for
`build_subtitle_corpus.py` and `build_eval_corpus.py` keep only `[א-ת]+` runs. That decision
deleted **every** Latin character, digit, bracket, quote, geresh, gershayim and emoji from every
corpus this project measures on, and:

  * it was **never registered as a decision** anywhere;
  * **no gate noticed for four milestones**;
  * `H1` reported the consequence as `0.00%` and called it a hole in the evidence;
  * `A1` then measured what was behind the hole -- typed Hebrew carries **x604** the
    geresh/gershayim, **x27** the brackets and **x458** the emoji of transcribed Hebrew;
  * `B1` measured 12,000 lines of those corpora and found **exactly zero** direction divergence,
    because a corpus with no brackets in it cannot ever show a bracket problem.

Four documents, three experiments and one wrong headline number later, the root cause is a
one-line regex nobody wrote down.

### What is checked, and why it is not a tautology
Two detectors, and the second is the one that carries the gate.

**`corpus_alphabet`** — the declared `kept` set must equal the set of classes the artifact
*actually contains*. This is mechanical and catches drift: a builder whose filter changes, or a
corpus swapped underneath a declaration.

**`corpus_dropped_reason`** — every class the artifact does **not** contain must appear in
`dropped` **with a non-empty reason**. This is the part that cannot be derived from the bytes. A
machine can compute *which* classes are missing; only a person can say *why*, and the failure
this gate exists for was not that the absence was uncomputable -- it was that nobody had ever
been made to write the sentence.

So the declaration is verified exactly against the artifact, and the one field that carries
information a reader needs is the one field a machine cannot fill in.
"""
from __future__ import annotations

import argparse
import gzip
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gatelib import Detector, Finding, GateResult, report  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

CORPUS_DIRS = ["lexicon/eval", "lexicon/heldout"]

EMOJI = re.compile("[\U0001F000-\U0001FAFF☀-➿]")


def classify(ch: str) -> str:
    """An EXHAUSTIVE partition. 'Every class' is only well defined if nothing falls outside."""
    o = ord(ch)
    if ch.isspace():
        return "whitespace"
    if "א" <= ch <= "ת":
        return "hebrew_letter"
    if "֑" <= ch <= "ׇ":
        return "hebrew_point"
    if ch in "׳״־׀׃׆":
        return "hebrew_punct"
    if ("A" <= ch <= "Z") or ("a" <= ch <= "z"):
        return "latin_letter"
    if "0" <= ch <= "9":
        return "digit"
    if ch in "()[]{}<>":
        return "bracket"
    if ch in "\"'":
        return "quote"
    if 0x21 <= o <= 0x7e:
        return "ascii_punct"
    if EMOJI.match(ch):
        return "emoji"
    return "other"


ALL_CLASSES = ["whitespace", "hebrew_letter", "hebrew_point", "hebrew_punct", "latin_letter",
               "digit", "bracket", "quote", "ascii_punct", "emoji", "other"]


def present_classes(path: str) -> set[str]:
    with gzip.open(path, "rt", encoding="utf-8", errors="replace") as fh:
        seen: set[str] = set()
        for line in fh:
            for ch in line:
                seen.add(classify(ch))
                if len(seen) == len(ALL_CLASSES):
                    return seen
    return seen


def corpora() -> list[tuple[str, str, dict]]:
    """-> (relative path, artifact name, that directory's manifest)"""
    out = []
    for d in CORPUS_DIRS:
        full = os.path.join(ROOT, d)
        man_path = os.path.join(full, "MANIFEST.json")
        man = json.load(open(man_path, encoding="utf-8")) if os.path.isfile(man_path) else {}
        for name in sorted(os.listdir(full)) if os.path.isdir(full) else []:
            if name.endswith(".txt.gz"):
                out.append((os.path.join(d, name), name, man))
    return out


def declaration(man: dict, name: str) -> dict | None:
    return (man.get("alphabet") or {}).get(name)


def scan_kept(inject: bool) -> Detector:
    det = Detector(name="corpus_alphabet", unit="corpus artifact", denominator=0)
    for rel, name, man in corpora():
        det.denominator += 1
        present = present_classes(os.path.join(ROOT, rel))
        decl = declaration(man, name)
        if decl is None:
            det.findings.append(Finding(
                "corpus_alphabet", rel, 0,
                f"no alphabet declaration. The corpus contains {sorted(present)} and says "
                f"nothing about what it dropped. This is the state every corpus here was in "
                f"while `[א-ת]+` silently deleted the characters three experiments later "
                f"needed.",
                "corpus.undeclared"))
            continue
        kept = set(decl.get("kept", []))
        if inject:
            # PLANTED DEFECT: a declaration that no longer matches the bytes.
            kept = kept ^ {"digit"}
        if kept != present:
            det.findings.append(Finding(
                "corpus_alphabet", rel, 0,
                f"declared kept {sorted(kept)} but the artifact contains {sorted(present)}. "
                f"Missing from the declaration: {sorted(present - kept)}; declared but absent: "
                f"{sorted(kept - present)}.",
                "corpus.kept_mismatch"))
    if not det.findings:
        det.notes.append(f"{det.denominator} corpora; every declared kept-set equals what the "
                         f"artifact contains")
    return det


def scan_reasons(inject: bool) -> Detector:
    det = Detector(name="corpus_dropped_reason", unit="dropped character class", denominator=0)
    for rel, name, man in corpora():
        decl = declaration(man, name)
        if decl is None:
            continue  # corpus_alphabet already reports this; two findings for one cause is noise
        present = present_classes(os.path.join(ROOT, rel))
        dropped = decl.get("dropped", {})
        for cls in ALL_CLASSES:
            if cls in present:
                continue
            det.denominator += 1
            reason = dropped.get(cls)
            if inject and cls == sorted(set(ALL_CLASSES) - present)[0]:
                reason = ""  # PLANTED DEFECT: a class dropped with nothing said about it
            if not reason or not reason.strip():
                det.findings.append(Finding(
                    "corpus_dropped_reason", rel, 0,
                    f"the class '{cls}' is absent from this corpus and no reason is recorded. "
                    f"A machine can compute WHICH classes are missing; only a person can say "
                    f"WHY, and this project spent four milestones not saying it.",
                    "corpus.unexplained_drop"))
    if det.denominator and not det.findings:
        det.notes.append(f"{det.denominator} dropped classes across the corpora, each with a "
                         f"recorded reason")
    return det


NOT_COVERED = [
    "Checks artifacts, not builders. A builder whose filter changes is caught only after it "
    "writes a corpus whose contents no longer match the declaration.",
    "A reason is checked for existence, never for truth. 'Dropped because we felt like it' "
    "passes. The gate makes the sentence exist; it cannot make it honest.",
    "The class partition is fixed in this file. A distinction it does not draw -- Arabic vs "
    "Cyrillic, both 'other' -- is invisible.",
    "Covers lexicon/eval and lexicon/heldout. The shipped LEXICON is not checked, and it is "
    "Hebrew-letters-only: W1 measured 7.61% of typed tokens out of lexicon against 1.15% on "
    "transcribed text. That is a known gap, not an oversight.",
    "Says nothing about whether the kept alphabet is the RIGHT one for the question being "
    "asked. A1 and B1 are that argument and no gate can make it.",
]


def rewrite_declarations() -> int:
    """Fill in `kept` from the artifacts. **Refuses to invent a reason.**

    The same shape as `check_docs.py --fix-denominators`, and the same rule: never run in CI,
    and the gate still compares and still fails. It writes the mechanical field only. An
    unexplained drop stays a finding, because a generated reason would defeat the one detector
    that carries this gate.
    """
    written = 0
    for d in CORPUS_DIRS:
        full = os.path.join(ROOT, d)
        man_path = os.path.join(full, "MANIFEST.json")
        if not os.path.isfile(man_path):
            continue
        man = json.load(open(man_path, encoding="utf-8"))
        alpha = man.setdefault("alphabet", {})
        for name in sorted(os.listdir(full)):
            if not name.endswith(".txt.gz"):
                continue
            present = present_classes(os.path.join(full, name))
            entry = alpha.setdefault(name, {"kept": [], "dropped": {}})
            if entry.get("kept") != sorted(present):
                entry["kept"] = sorted(present)
                written += 1
            # Reasons are NOT generated. A class newly dropped arrives with no reason and the
            # gate says so, by name, until a person writes one.
            for cls in list(entry.get("dropped", {})):
                if cls in present:
                    del entry["dropped"][cls]
            for cls in ALL_CLASSES:
                if cls not in present:
                    entry.setdefault("dropped", {}).setdefault(cls, "")
        json.dump(man, open(man_path, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    return written


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--strict", action="store_true")
    ap.add_argument("--inject-defect", choices=["kept", "reason"],
                    help="PLANT A DEFECT. Positive control; must go red.")
    ap.add_argument("--fix-declarations", action="store_true",
                    help="rewrite each corpus's `kept` set from its bytes. Never run in CI. "
                         "Reasons are NOT generated -- that is the whole point of the gate.")
    args = ap.parse_args()

    if args.fix_declarations:
        n = rewrite_declarations()
        print(f"rewrote {n} kept-set(s). Reasons are never generated; run the gate to see "
              f"which drops still need one.")
        return 0

    result = GateResult(
        gate="GATE-CORPUS-1",
        description="every corpus declares the character classes it keeps and drops, the kept "
                    "set matches the artifact, and every drop carries a reason",
        detectors=[scan_kept(args.inject_defect == "kept"),
                   scan_reasons(args.inject_defect == "reason")],
        not_covered=NOT_COVERED,
    )
    return report(result, args.json, args.strict)


if __name__ == "__main__":
    raise SystemExit(main())

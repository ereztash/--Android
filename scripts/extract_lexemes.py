#!/usr/bin/env python3
"""G2 step 1 — pull the Hebrew noun and adjective paradigms out of the Wikidata lexeme dump.

### Why a dated dump and not `latest`
`GATE-LEX-2` pins every upstream source by byte count and sha256. `latest-lexemes.json.gz`
changes under that pin roughly every other day, so it cannot be pinned at all. The dated file
can: `wikidata-20260819-lexemes.json.gz`, 604,534,688 bytes, Last-Modified 2026-08-19.

### Licence
**CC0 1.0.** Wikidata's copyright page states verbatim that *"All structured data from the
main, Property, Lexeme, and EntitySchema namespaces is available under the Creative Commons CC0
License."* No attribution is required; `docs/LICENSES.md` credits Wikidata contributors anyway.

### What it writes
A small JSON cache — `lexicon/cache/he_noun_adj.json` — so that the 577 MB parse happens once
and the segmentation sweep can rerun in seconds. The cache is derived, gitignored, and carries
the dump's sha256 so a stale cache against a different dump is visible rather than silent.

### What it does NOT do
It does not touch the shipped lexicon or any asset. Nothing in the APK derives from this unless
a separate, later decision says so.
"""
from __future__ import annotations

import argparse
import collections
import gzip
import hashlib
import json
import os
import re
import sys
import unicodedata

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DUMP = os.path.join(ROOT, "lexicon/cache/wikidata-20260819-lexemes.json.gz")
OUT = os.path.join(ROOT, "lexicon/cache/he_noun_adj.json")

HEBREW_LANG = "Q9288"
CATEGORIES = {"Q1084": "noun", "Q34698": "adjective"}
NIQQUD_RE = re.compile(r"[֑-ׇ]")
HEBREW_ONLY_RE = re.compile(r"^[א-ת]+$")


def clean(s: str) -> str:
    return NIQQUD_RE.sub("", unicodedata.normalize("NFC", s or ""))


def sha256_of(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dump", default=DUMP)
    ap.add_argument("--out", default=OUT)
    args = ap.parse_args()

    if not os.path.isfile(args.dump):
        print(f"NOT-MEASURED: {args.dump} is absent. Fetch the dated dump first.", file=sys.stderr)
        return 2

    size = os.path.getsize(args.dump)
    print(f"dump {size:,} bytes; hashing", flush=True)
    digest = sha256_of(args.dump)
    print(f"sha256 {digest}", flush=True)

    lexemes: dict[str, dict] = {}
    seen = kept = 0
    cat_counts: collections.Counter = collections.Counter()

    with gzip.open(args.dump, "rt", encoding="utf-8") as f:
        for line in f:
            line = line.strip().rstrip(",")
            if not line or line in ("[", "]"):
                continue
            seen += 1
            if seen % 500_000 == 0:
                print(f"  {seen:,} lexemes read, {kept:,} Hebrew noun/adjective kept", flush=True)
            # Cheap reject before paying for json.loads on ~1.4M lexemes.
            if HEBREW_LANG not in line:
                continue
            try:
                lx = json.loads(line)
            except json.JSONDecodeError:
                continue
            if lx.get("language") != HEBREW_LANG:
                continue
            cat = lx.get("lexicalCategory")
            if cat not in CATEGORIES:
                continue
            lemma_field = (lx.get("lemmas") or {})
            lemma = ""
            for v in lemma_field.values():
                lemma = clean(v.get("value", ""))
                if lemma:
                    break
            if not HEBREW_ONLY_RE.match(lemma):
                continue

            forms = []
            for fm in lx.get("forms") or []:
                rep = ""
                for v in (fm.get("representations") or {}).values():
                    rep = clean(v.get("value", ""))
                    if rep:
                        break
                if not rep or not HEBREW_ONLY_RE.match(rep):
                    continue
                feats = "+".join(sorted(fm.get("grammaticalFeatures") or [])) or "NONE"
                forms.append([rep, feats])
            if not forms:
                continue
            kept += 1
            cat_counts[CATEGORIES[cat]] += 1
            lexemes[lx["id"]] = {"lemma": lemma, "category": CATEGORIES[cat], "forms": forms}

    total_forms = sum(len(v["forms"]) for v in lexemes.values())
    distinct_forms = len({f[0] for v in lexemes.values() for f in v["forms"]})
    distinct_feats = len({f[1] for v in lexemes.values() for f in v["forms"]})
    print()
    print(f"lexemes kept          {kept:,}   {dict(cat_counts)}")
    print(f"form entries          {total_forms:,}")
    print(f"distinct surface forms {distinct_forms:,}")
    print(f"distinct feature bundles {distinct_feats:,}")

    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump({
            "dump": os.path.basename(args.dump), "dump_bytes": size, "dump_sha256": digest,
            "licence": "CC0 1.0", "lexemes": lexemes,
        }, f, ensure_ascii=False)
    print(f"wrote {args.out} ({os.path.getsize(args.out):,} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""Build the Hebrew lexicon from two clean-licensed sources. Deterministic and checksummed.

Recipe (see docs/LICENSES.md for the licensing analysis that forces exactly these two):

  Source A  InflectedVerbsExtended.csv, CC BY 4.0, Eran Tomer via NNLP-IL/Hebrew-Resources.
            Take `vocalized_inflection`, NFC-normalise, strip U+0591..U+05C7, keep ^[A-T]+$
            over the Hebrew block. -> expected 102,239 distinct unvocalized forms.

  Source B  he_full.txt, CC BY-SA 4.0, OpenSubtitles/OPUS via hermitdave/FrequencyWords.
            Keep tokens matching ^[A-T]+$ with count >= 5. -> expected 298,162 forms.

  UNION -> expected 355,587 forms, of which 57,425 are verb forms present in A but absent
  from B. Those are the rare-but-valid conjugations a corpus-only list would flag as errors,
  which is exactly the failure users notice. They are NOT pruned.

TOLERANCE IS FIXED AT +/-1% BY THE BUILD SPEC AND IS NOT ADJUSTABLE FROM THE COMMAND LINE.
If a count lands outside the band the correct response is to report the conflict, not to widen
it. There is deliberately no --tolerance flag.

Determinism: output is sorted by code point and gzipped with mtime=0, so two runs on the same
inputs produce a byte-identical artifact. UTF-8 preserves code-point order, so the sorted blob
is also correctly ordered for byte-wise binary search on the Kotlin side.
"""
from __future__ import annotations

import argparse
import csv
import gzip
import hashlib
import io
import json
import os
import re
import sys
import unicodedata
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

RECIPE_VERSION = 1

SOURCES = {
    "A": {
        "name": "InflectedVerbsExtended.csv",
        "url": ("https://raw.githubusercontent.com/NNLP-IL/Hebrew-Resources/master/"
                "linguistic_resources/word_lists/hebrew_verbs_eran_tomer/"
                "InflectedVerbsExtended.csv"),
        "bytes": 17842473,
        "sha256": "818793894a360243d471e0f302494b245736189465c8e0258e5665335052a456",
        "license": "CC BY 4.0",
        "attribution": "Eran Tomer",
    },
    "B": {
        "name": "he_full.txt",
        "url": ("https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/"
                "content/2018/he/he_full.txt"),
        "bytes": 19215890,
        "sha256": "a69a3f390eb53183bf191c4eac18282592992aef9ff184c5dcf8919f5ea0f773",
        "license": "CC BY-SA 4.0",
        "attribution": "OpenSubtitles / OPUS, via hermitdave/FrequencyWords",
    },
}

EXPECTED = {"a": 102239, "b": 298162, "union": 355587, "a_only": 57425}
TOLERANCE = 0.01  # fixed by the build spec; not a tunable

MIN_COUNT_B = 5

# U+0591..U+05C7: te'amim, niqqud, and the few punctuation code points inside that block.
NIQQUD_RE = re.compile("[֑-ׇ]")
# U+05D0..U+05EA: the 27 Hebrew letters including the five final forms.
HEBREW_WORD_RE = re.compile("^[א-ת]+$")


class LexiconError(RuntimeError):
    pass


def sha256_bytes(b: bytes) -> str:
    return hashlib.sha256(b).hexdigest()


def fetch(key: str, cache_dir: str, override: str | None, inject: str | None,
          allow_unverified: bool) -> bytes:
    spec = SOURCES[key]
    if override:
        data = open(override, "rb").read()
        origin = override
    else:
        os.makedirs(cache_dir, exist_ok=True)
        path = os.path.join(cache_dir, spec["name"])
        if not os.path.isfile(path):
            print(f"  downloading {spec['name']} ...", file=sys.stderr)
            with urllib.request.urlopen(spec["url"], timeout=600) as r:
                data = r.read()
            with open(path, "wb") as fh:
                fh.write(data)
        else:
            data = open(path, "rb").read()
        origin = path

    if inject == "checksum" and key == "A":
        # PLANTED DEFECT (GATE-LEX-1a positive control): flip one byte so the digest changes.
        data = bytearray(data)
        data[len(data) // 2] ^= 0x01
        data = bytes(data)

    digest = sha256_bytes(data)
    size = len(data)
    ok = digest == spec["sha256"] and size == spec["bytes"]
    print(f"  source {key}: {origin}\n"
          f"    bytes  {size} (expected {spec['bytes']})\n"
          f"    sha256 {digest}\n"
          f"    expect {spec['sha256']}\n"
          f"    -> {'MATCH' if ok else 'MISMATCH'}", file=sys.stderr)
    if not ok and not allow_unverified:
        raise LexiconError(
            f"CHECKSUM_MISMATCH: source {key} ({spec['name']}) does not match the committed "
            f"manifest. Got sha256={digest} size={size}; expected sha256={spec['sha256']} "
            f"size={spec['bytes']}. Refusing to build a silently different lexicon.")
    return data


def parse_source_a(data: bytes, inject: str | None) -> tuple[set[str], dict]:
    """vocalized_inflection -> NFC -> strip niqqud -> keep pure Hebrew letters."""
    forms: set[str] = set()
    rows = rejected = 0
    text = data.decode("utf-8-sig")
    reader = csv.DictReader(io.StringIO(text))
    if reader.fieldnames is None or "vocalized_inflection" not in reader.fieldnames:
        raise LexiconError(
            f"SCHEMA_CHANGE: source A columns are {reader.fieldnames}, expected a "
            f"'vocalized_inflection' column.")
    word_re = HEBREW_WORD_RE
    if inject == "filter":
        # PLANTED DEFECT (GATE-LEX-1b positive control): drop the five final-form letters
        # from the accepted alphabet. Every word ending in a final form is silently lost.
        word_re = re.compile("^[א-יכלמנ-עפ"
                             "צ-ת]+$")
    for row in reader:
        rows += 1
        raw = row.get("vocalized_inflection") or ""
        w = NIQQUD_RE.sub("", unicodedata.normalize("NFC", raw))
        if word_re.match(w):
            forms.add(w)
        else:
            rejected += 1
    return forms, {"rows_read": rows, "rows_rejected": rejected}


def parse_source_b(data: bytes, inject: str | None) -> tuple[set[str], dict]:
    """`word count` per line; keep pure-Hebrew tokens with count >= MIN_COUNT_B."""
    forms: set[str] = set()
    lines = malformed = below_min = 0
    word_re = HEBREW_WORD_RE
    if inject == "filter":
        word_re = re.compile("^[א-יכלמנ-עפ"
                             "צ-ת]+$")
    for line in data.decode("utf-8").splitlines():
        lines += 1
        parts = line.split()
        if len(parts) != 2:
            malformed += 1
            continue
        word, count = parts
        try:
            n = int(count)
        except ValueError:
            malformed += 1
            continue
        if n < MIN_COUNT_B:
            below_min += 1
            continue
        if word_re.match(word):
            forms.add(word)
    return forms, {"lines_read": lines, "malformed": malformed, "below_min_count": below_min}


def check_counts(counts: dict[str, int]) -> list[str]:
    problems = []
    for key, expected in EXPECTED.items():
        got = counts[key]
        delta = (got - expected) / expected
        verdict = "OK" if abs(delta) <= TOLERANCE else "OUT OF TOLERANCE"
        print(f"  {key:<7} got={got:>7}  expected={expected:>7}  "
              f"delta={delta*100:+.3f}%  {verdict}", file=sys.stderr)
        if abs(delta) > TOLERANCE:
            problems.append(
                f"COUNT_OUT_OF_TOLERANCE: {key} = {got}, expected {expected} "
                f"(delta {delta*100:+.3f}%, band +/-{TOLERANCE*100:.0f}%)")
    return problems


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--cache-dir", default=os.path.join(ROOT, "lexicon", "cache"))
    ap.add_argument("--out", default=os.path.join(ROOT, "lexicon", "assets", "he_lexicon.txt.gz"))
    ap.add_argument("--manifest", default=os.path.join(ROOT, "lexicon", "MANIFEST.json"))
    ap.add_argument("--source-a"), ap.add_argument("--source-b")
    ap.add_argument("--allow-unverified-source", action="store_true",
                    help="skip the source checksum gate (used only by the count control)")
    ap.add_argument("--inject-defect", choices=["checksum", "filter", "nondeterminism"],
                    help="PLANT A DEFECT. Positive controls for GATE-LEX-1a/1b/2. "
                         "Every value here must make the build fail.")
    ap.add_argument("--dry-run", action="store_true", help="do not write outputs")
    args = ap.parse_args()

    print("=== GATE-LEX-1a: source integrity ===", file=sys.stderr)
    data_a = fetch("A", args.cache_dir, args.source_a, args.inject_defect,
                   args.allow_unverified_source)
    data_b = fetch("B", args.cache_dir, args.source_b, args.inject_defect,
                   args.allow_unverified_source)

    forms_a, stats_a = parse_source_a(data_a, args.inject_defect)
    forms_b, stats_b = parse_source_b(data_b, args.inject_defect)
    union = forms_a | forms_b
    a_only = forms_a - forms_b
    counts = {"a": len(forms_a), "b": len(forms_b),
              "union": len(union), "a_only": len(a_only)}

    print("=== GATE-LEX-1b: derived counts vs the build spec (band +/-1%) ===", file=sys.stderr)
    problems = check_counts(counts)
    if problems:
        for p in problems:
            print(f"\n{p}", file=sys.stderr)
        print("\nSTOP. Do not proceed with a silently different lexicon. Either the recipe "
              "drifted or a source changed upstream; report both numbers with the corpus hash "
              "beside each rather than adjusting the band.", file=sys.stderr)
        return 1

    ordered = sorted(union)
    if args.inject_defect == "nondeterminism":
        # PLANTED DEFECT (GATE-LEX-2 positive control): emit set-iteration order, which
        # varies with PYTHONHASHSEED, instead of sorted order.
        ordered = list(union)

    blob = ("\n".join(ordered) + "\n").encode("utf-8")
    buf = io.BytesIO()
    # mtime=0 so the gzip header carries no timestamp; without this every run differs.
    with gzip.GzipFile(fileobj=buf, mode="wb", compresslevel=9, mtime=0) as gz:
        gz.write(blob)
    packed = buf.getvalue()

    manifest = {
        "recipe_version": RECIPE_VERSION,
        "sources": {
            k: {"name": v["name"], "url": v["url"], "bytes": v["bytes"],
                "sha256": v["sha256"], "license": v["license"],
                "attribution": v["attribution"]}
            for k, v in SOURCES.items()
        },
        "filters": {
            "niqqud_stripped": "U+0591..U+05C7 after NFC",
            "accepted_alphabet": "U+05D0..U+05EA (27 Hebrew letters incl. final forms)",
            "source_b_min_count": MIN_COUNT_B,
        },
        "parse_stats": {"A": stats_a, "B": stats_b},
        "counts": counts,
        "expected_counts": EXPECTED,
        "tolerance": TOLERANCE,
        "output": {
            "path": os.path.relpath(args.out, ROOT),
            "word_count": len(ordered),
            "uncompressed_bytes": len(blob),
            "uncompressed_sha256": sha256_bytes(blob),
            "gzip_bytes": len(packed),
            "gzip_sha256": sha256_bytes(packed),
            "encoding": "UTF-8, newline-delimited, sorted by code point",
        },
        "license_of_derived_artifact": "CC BY-SA 4.0 (inherited from source B)",
    }

    print(f"\n  lexicon: {counts['union']} forms, "
          f"{len(blob)} bytes raw, {len(packed)} bytes gzipped", file=sys.stderr)
    print(f"  uncompressed sha256 {manifest['output']['uncompressed_sha256']}", file=sys.stderr)
    print(f"  gzip         sha256 {manifest['output']['gzip_sha256']}", file=sys.stderr)

    if not args.dry_run:
        os.makedirs(os.path.dirname(args.out), exist_ok=True)
        with open(args.out, "wb") as fh:
            fh.write(packed)
        with open(args.manifest, "w", encoding="utf-8") as fh:
            json.dump(manifest, fh, indent=2, ensure_ascii=False)
            fh.write("\n")
        print(f"  wrote {os.path.relpath(args.out, ROOT)} and "
              f"{os.path.relpath(args.manifest, ROOT)}", file=sys.stderr)

    print(json.dumps({"counts": counts,
                      "gzip_sha256": manifest["output"]["gzip_sha256"],
                      "uncompressed_sha256": manifest["output"]["uncompressed_sha256"]}))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except LexiconError as exc:
        print(f"\n{exc}", file=sys.stderr)
        raise SystemExit(1)

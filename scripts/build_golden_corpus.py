#!/usr/bin/env python3
"""Build the hash-locked golden corpora for correction accuracy.

Four corpora, built separately because they measure different things and merging them would
hide exactly the effect each is there to expose.

  A  uniform     Real Hebrew words, corrupted by ONE edit chosen uniformly from
                 {insert, delete, substitute, transpose}, with substitute characters drawn
                 uniformly from the 27 Hebrew letters. This is the headline accuracy number.
                 Adjacency gets no help here, which is the point.

  B  adjacency   The same words, corrupted by substituting a SAME-ROW HORIZONTAL NEIGHBOUR on
                 the Hebrew layout. This measures the adjacency discount against its own
                 assumption and is therefore partly circular by construction. It is reported
                 separately and never merged into A.

  C1 control-lex Words known to be in the lexicon, UNMODIFIED. The correction rate here must be
                 near zero or the accuracy numbers are an artifact.

  C2 control-real Raw held-out tokens, UNMODIFIED, whatever they are -- including the proper
                 nouns and rare terms the lexicon does not have. This is the realistic
                 false-correction rate a user would actually feel.

A generated typo that is ITSELF a real lexicon word is discarded and regenerated, and the
discard count is reported. "Correcting" such a form is not clearly right, and leaving it in
would depress the numbers for a reason that has nothing to do with the engine -- the same bias
the build spec warns about in §2.3.

Determinism: a fixed seed drives a counter-based generator built on sha256, not
`random.Random`, whose stream is a CPython implementation detail. Same inputs, same bytes,
every time and on every version.
"""
from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LEXICON = os.path.join(ROOT, "lexicon", "assets", "he_lexicon.txt.gz")
HELDOUT = os.path.join(ROOT, "lexicon", "heldout", "hewiki_sample.txt.gz")
OUT_DIR = os.path.join(ROOT, "lexicon", "golden")

SEED = "hebrew-ime-golden-v1"

HEBREW_LETTERS = [chr(c) for c in range(0x05D0, 0x05EA + 1)]

# The Hebrew layout's three letter rows, matching Layouts.hebrew in :core. Only same-row
# horizontal neighbours are used for corpus B: they are unambiguous under any geometry, so the
# Python and Kotlin notions of adjacency cannot silently diverge on them.
LAYOUT_ROWS = [
    "קראטוןםפ",
    "שדגכעיחלךף",
    "זסבהנמצתץ",
]

MIN_WORD_LENGTH = 4
TARGET_PAIRS = 4000
TARGET_CONTROL = 4000


def horizontal_neighbours() -> dict[str, list[str]]:
    out: dict[str, list[str]] = {}
    for row in LAYOUT_ROWS:
        for i, c in enumerate(row):
            n = []
            if i > 0:
                n.append(row[i - 1])
            if i + 1 < len(row):
                n.append(row[i + 1])
            out[c] = n
    return out


class Stream:
    """Counter-based deterministic randomness. sha256(seed || label || counter)."""

    def __init__(self, seed: str, label: str) -> None:
        self._seed = f"{seed}:{label}".encode()
        self._n = 0

    def _next_bytes(self) -> bytes:
        self._n += 1
        return hashlib.sha256(self._seed + self._n.to_bytes(8, "big")).digest()

    def below(self, n: int) -> int:
        return int.from_bytes(self._next_bytes()[:8], "big") % n

    def choice(self, seq):
        return seq[self.below(len(seq))]


def load_lexicon() -> set[str]:
    with gzip.open(LEXICON, "rb") as fh:
        return set(fh.read().decode("utf-8").split("\n")) - {""}


def load_heldout() -> list[str]:
    with gzip.open(HELDOUT, "rb") as fh:
        return [t for t in fh.read().decode("utf-8").split("\n") if t]


def corrupt_uniform(word: str, rng: Stream) -> str:
    op = rng.choice(["insert", "delete", "substitute", "transpose"])
    i = rng.below(len(word))
    if op == "insert":
        return word[:i] + rng.choice(HEBREW_LETTERS) + word[i:]
    if op == "delete":
        return word[:i] + word[i + 1:]
    if op == "substitute":
        c = rng.choice(HEBREW_LETTERS)
        return word[:i] + c + word[i + 1:]
    # transpose
    if len(word) < 2:
        return word
    i = min(i, len(word) - 2)
    return word[:i] + word[i + 1] + word[i] + word[i + 2:]


def corrupt_adjacent(word: str, rng: Stream, neighbours: dict[str, list[str]]) -> str | None:
    positions = [i for i, c in enumerate(word) if neighbours.get(c)]
    if not positions:
        return None
    i = rng.choice(positions)
    return word[:i] + rng.choice(neighbours[word[i]]) + word[i + 1:]


def write(path: str, lines: list[str]) -> dict:
    blob = ("\n".join(lines) + "\n").encode("utf-8")
    buf = gzip.compress(blob, compresslevel=9, mtime=0)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as fh:
        fh.write(buf)
    return {
        "path": os.path.relpath(path, ROOT),
        "entries": len(lines),
        "uncompressed_bytes": len(blob),
        "sha256": hashlib.sha256(blob).hexdigest(),
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out-dir", default=OUT_DIR)
    ap.add_argument("--pairs", type=int, default=TARGET_PAIRS)
    ap.add_argument("--controls", type=int, default=TARGET_CONTROL)
    ap.add_argument("--inject-defect", choices=["reseed"],
                    help="PLANT A DEFECT: change the seed, so the corpus hash must change.")
    args = ap.parse_args()

    seed = SEED + ("-RESEEDED" if args.inject_defect == "reseed" else "")

    lexicon = load_lexicon()
    heldout = load_heldout()
    neighbours = horizontal_neighbours()

    # Source words: held-out tokens that ARE real lexicon words and long enough to correct.
    # Drawn from held-out text rather than from the lexicon at large so the frequency profile
    # resembles real usage instead of the dictionary's tail.
    pick = Stream(seed, "pick")
    seen: set[str] = set()
    sources: list[str] = []
    for _ in range(args.pairs * 40):
        if len(sources) >= args.pairs:
            break
        t = pick.choice(heldout)
        if len(t) < MIN_WORD_LENGTH or t in seen or t not in lexicon:
            continue
        seen.add(t)
        sources.append(t)

    if len(sources) < args.pairs:
        print(f"SHORT_DENOMINATOR: only {len(sources)} source words, wanted {args.pairs}",
              file=sys.stderr)
        return 1

    # --- corpus A ------------------------------------------------------------------------
    rng_a = Stream(seed, "uniform")
    a_lines, a_discarded, a_failed = [], 0, 0
    for w in sources:
        for _attempt in range(40):
            typo = corrupt_uniform(w, rng_a)
            if typo == w or len(typo) < 2:
                continue
            if typo in lexicon:
                # A corruption that landed on another real word. Correcting it is not clearly
                # right, so it is discarded rather than counted as a miss.
                a_discarded += 1
                continue
            a_lines.append(f"{typo}\t{w}")
            break
        else:
            a_failed += 1

    # --- corpus B ------------------------------------------------------------------------
    rng_b = Stream(seed, "adjacency")
    b_lines, b_discarded, b_failed = [], 0, 0
    for w in sources:
        for _attempt in range(40):
            typo = corrupt_adjacent(w, rng_b, neighbours)
            if typo is None or typo == w:
                continue
            if typo in lexicon:
                b_discarded += 1
                continue
            b_lines.append(f"{typo}\t{w}")
            break
        else:
            b_failed += 1

    # --- controls ------------------------------------------------------------------------
    c1_lines = sources[: args.controls]

    pick_c2 = Stream(seed, "control-real")
    c2_seen: set[str] = set()
    c2_lines: list[str] = []
    for _ in range(args.controls * 40):
        if len(c2_lines) >= args.controls:
            break
        t = pick_c2.choice(heldout)
        if len(t) < MIN_WORD_LENGTH or t in c2_seen:
            continue
        c2_seen.add(t)
        c2_lines.append(t)

    out = args.out_dir
    manifest = {
        "seed": seed,
        "source_lexicon_sha256": hashlib.sha256(
            gzip.open(LEXICON, "rb").read()).hexdigest(),
        "source_heldout_sha256": hashlib.sha256(
            gzip.open(HELDOUT, "rb").read()).hexdigest(),
        "min_word_length": MIN_WORD_LENGTH,
        "source_words": len(sources),
        "corpora": {
            "A_uniform": write(os.path.join(out, "a_uniform.tsv.gz"), a_lines)
            | {"discarded_landed_on_real_word": a_discarded, "failed_to_corrupt": a_failed,
               "what": "one uniformly chosen edit; the unbiased accuracy number"},
            "B_adjacency": write(os.path.join(out, "b_adjacency.tsv.gz"), b_lines)
            | {"discarded_landed_on_real_word": b_discarded, "failed_to_corrupt": b_failed,
               "what": "same-row horizontal neighbour substitution; PARTLY CIRCULAR, never "
                       "merged into A"},
            "C1_control_lexicon": write(os.path.join(out, "c1_control_lexicon.txt.gz"),
                                        c1_lines)
            | {"what": "known-correct words, unmodified; correction rate must be ~0"},
            "C2_control_real": write(os.path.join(out, "c2_control_real.txt.gz"), c2_lines)
            | {"what": "raw held-out tokens, unmodified, including proper nouns the lexicon "
                       "lacks; the realistic false-correction rate"},
        },
        "known_limitations": [
            "Synthetic typos. Real typing errors are not uniformly distributed and include "
            "errors this generator cannot make, such as whole-word substitutions and "
            "phonetic spellings.",
            "Source words come from encyclopedic prose, so the register is wrong for a phone "
            "keyboard.",
            "Corpus B is partly circular by construction and must never be quoted as the "
            "engine's accuracy.",
        ],
    }
    with open(os.path.join(out, "MANIFEST.json"), "w", encoding="utf-8") as fh:
        json.dump(manifest, fh, indent=2, ensure_ascii=False)
        fh.write("\n")

    for name, c in manifest["corpora"].items():
        print(f"{name:<20} {c['entries']:>6} entries  sha256 {c['sha256'][:16]}...",
              file=sys.stderr)
    print(f"discarded (landed on a real word): A={a_discarded} B={b_discarded}",
          file=sys.stderr)
    print(json.dumps({k: v["sha256"] for k, v in manifest["corpora"].items()}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

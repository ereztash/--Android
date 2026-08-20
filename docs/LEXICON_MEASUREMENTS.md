# Lexicon measurements

Every number here is a claim about **exactly one thing**: this lexicon, on this corpus, with
this tokenizer. The corpus hash sits beside every table so no number can be quoted wider than
the measurement that produced it.

---

## 1. Reproducibility — exact

`scripts/build_lexicon.py`, run against the two upstream sources.

| Source | Asserted bytes | Measured | Asserted sha256 | Measured |
|---|---|---|---|---|
| A `InflectedVerbsExtended.csv` | 17,842,473 | 17,842,473 | `818793…52a456` | identical |
| B `he_full.txt` | 19,215,890 | 19,215,890 | `a69a3f…a0f773` | identical |

| Count | Build spec | Measured | Delta |
|---|---|---|---|
| A distinct unvocalized forms | 102,239 | **102,239** | **+0.000%** |
| B forms with count ≥ 5 | 298,162 | **298,162** | **+0.000%** |
| Union | 355,587 | **355,587** | **+0.000%** |
| A-only (verb forms absent from corpus) | 57,425 | **57,425** | **+0.000%** |

The ±1% reproducibility band was not needed. All four counts reproduce **exactly**.

Parse statistics (denominators): source A — 241,797 rows read, 657 rejected as not pure
Hebrew after point-stripping. Source B — 1,167,621 lines read, 0 malformed, 835,179 below the
count ≥ 5 threshold.

**Artifact:** 355,587 forms, 4,607,433 bytes raw, **950,114 bytes gzipped**.
- uncompressed sha256 `6fbc467ddd7801a1b2449c40d77abd797a7eb7e298001551c8e3887ee0648ec8`
- gzip sha256 `b69e8f62f64c64bbf0042c518901d597b1b51ce4c535716ee3f78e24a9b7c9c8`

Determinism verified: two runs produce byte-identical output. The `--inject-defect
nondeterminism` control produces different hashes under different `PYTHONHASHSEED` values,
confirming the check can detect the failure it exists to detect.

---

## 2. Coverage on held-out text

**Corpus:** 75,000 tokens / 22,804 types, from 331 articles across 4 randomly-selected
multistream blocks of `hewiki-20260801`.
**Corpus sha256 `02fe828ce7ef083ab5db1d602714989d06676c2dadf490376c88b40e30520515`.**

| MIN_STEM | token coverage | wrong-underline rate | type coverage | tokens hit / 75,000 |
|---|---|---|---|---|
| none (no stripper) | 94.61% | 5.39% | 86.90% | 70,960 |
| 2 | **96.84%** | 3.16% | 93.14% | 72,633 |
| 3 | 96.83% | 3.17% | 93.11% | 72,622 |
| 4 | **96.73%** | 3.27% | 92.82% | 72,544 |
| 5 | 96.36% | 3.64% | 91.88% | 72,270 |

### These numbers are ~1 point below the build spec's. Reported, not reconciled.

| | Build spec (72,585 tokens) | Measured here (75,000 tokens) | Difference |
|---|---|---|---|
| no stripper | 95.48% | 94.61% | −0.87 |
| MIN_STEM 2 | 97.89% | 96.84% | −1.05 |
| MIN_STEM 5 | 97.35% | 96.36% | −0.99 |

The spec's corpus is described only as "held-out live Hebrew Wikipedia text"; its dump date,
sampling method and tokenizer are not stated, so the gap **cannot be reconciled** and no
attempt was made to close it by adjusting anything. What can be said is that the *mechanism*
reproduces closely:

- The spec's headline finding — the stripper more than halves the wrong-underline rate —
  reproduces: **5.39% → 3.16%**, a 41% reduction (spec: 4.5% → 2.1%).
- The MIN_STEM 2→5 coverage cost is **0.48 points** here versus **0.54 points** in the spec.
  Two independently sampled corpora agreeing to within 0.06 points on a differential is
  strong evidence the implementation matches; the absolute offset is a property of the corpus.

The most likely cause of the offset is visible in the residuals below: this sample landed on
unusually technical material (geochemistry, cytology, German and Russian toponyms), which
loads it with proper nouns and rare borrowings. That is a limit of a 4-block sample, and it is
stated rather than sampled away.

### Cross-implementation agreement

`scripts/measure_coverage.py` (Python) and `PrefixStripper` + `HebrewLexicon` (Kotlin, what
actually ships) were run over the same 75,000 tokens at all five settings. They agree
**exactly, token for token, at every setting** — 70,960 / 72,633 / 72,622 / 72,544 / 72,270.
`HebrewLexiconTest.kotlinAgreesWithThePythonMeasurement` pins them together, so a divergence
between the measured path and the shipped path fails the build.

---

## 3. MIN_STEM = 4 is adopted, and what that costs

Adopted per the build spec. On the evidence here, moving 2 → 4 costs **0.11 points** of token
coverage (96.84% → 96.73%, 89 tokens out of 75,000). The spec's measurement — which this
project has not yet independently reproduced — puts the corresponding gain in typo rejection
at 79.6% → 88.4%.

**That typo-rejection number is NOT MEASURED here.** It requires a typo corpus and a correctly
constructed non-word control, and it is M5 work. Until then, MIN_STEM = 4 rests on the spec's
measurement for the benefit side and this project's measurement for the cost side, and it is
written that way rather than presented as one finding.

When MIN_STEM changes, all four columns get re-reported — coverage, recall, false-accept and
typo-rejection — before and after. Never only the column that improved.

---

## 4. What the residuals confirm

Build spec §2.4 predicts the remaining gap is **not** prefixes but proper nouns,
possessive-suffixed forms and rare adjectives. At MIN_STEM = 4, 1,637 distinct types /
2,456 tokens remain unrecognized. The top of that list:

| Tokens | Type | Category |
|---|---|---|
| 28 | אחמתא | proper noun (Ecbatana) |
| 25 | אלחנטי | proper noun (surname) |
| 23 | ליסיאנסקי | proper noun (Lisyansky) |
| 14 | אבנגרד | borrowing (avant-garde) |
| 13 | הרפז | proper noun (surname) |
| 11 | כלקופילים | rare technical (chalcophile) |
| 11 | פטמילך | proper noun |
| 9 | הציטוזול | rare technical (cytosol) |
| 9 | פרידריכשטראסה | proper noun (Friedrichstraße) |

**§2.4 is confirmed on this sample.** Effort belongs in a names list and a suffix layer, not
in more prefix work.

One tokenizer artifact worth naming: `תשנ` (10 tokens) is a Hebrew-numeral year whose geresh
the tokenizer strips, leaving a fragment that is not a word in any lexicon. It inflates the
miss rate slightly and is a limitation of the measurement, not of the lexicon.

---

## 5. Ktiv male / haser — the spec's concern does not apply to this lexicon

Build spec §2.5 warns that Hspell 1.4 (June 2017) is the last release under the **old**
spelling standard, so a Hspell-derived lexicon would miss post-reform spellings.

**This lexicon is not Hspell-derived.** Source B is a 2018 OpenSubtitles corpus, i.e.
contemporary usage. Checked against the built artifact:

| | Result |
|---|---|
| Pairs checked | 10 |
| Both spellings present | **10** |
| New only | 0 |
| Old only | 0 |
| Neither | 0 |

All ten of `אניה/אונייה`, `חכמה/חוכמה`, `צהריים/צוהריים`, `קרבן/קורבן`, `תכנית/תוכנית`,
`אמתי/אמיתי`, `לעתים/לעיתים`, `שער/שיער`, `ססמה/סיסמה`, `מיד/מייד` are present in **both**
spellings.

**Denominator: 10. These are exactly the ten examples the build spec names — not a random
sample.** This is suggestive, not conclusive: it shows the corpus-derived construction picks
up post-reform spellings naturally, and it does not establish a rate over all reform-affected
lemmas. A proper measurement needs a list of affected lemmas, which this project does not
have. **No inflation factor is assumed, because none has been measured.**

---

## 6. What these measurements do NOT cover

- **Coverage is not accuracy.** "Is this token in the lexicon?" is a different question from
  "does the right correction rank first?". Accuracy needs the golden corpus and is M5.
- **The corpus is encyclopedic prose.** No SMS register, no slang, no typos, and proper nouns
  over-represented relative to real typing. Coverage on keyboard input is **NOT MEASURED**.
- **4 blocks is a small sample.** Randomly selected and therefore unbiased in expectation, but
  4 blocks can land on unusual material — and on this evidence, did.
- **The tokenizer excludes** anything with maqaf, geresh, digits or Latin letters. Those
  tokens are not in the denominator at all, in either direction.
- **Typo rejection, recall and false-accept rates are NOT MEASURED here.** M5.
- **No on-device measurement.** Load time, memory and lookup latency on real hardware are
  **NOT RUN** — see `docs/QA_MATRIX.md`.

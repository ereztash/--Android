# Lexicon licensing

The single hardest constraint in this project is not size, morphology or latency. It is that
**essentially the entire Hebrew spellchecking ecosystem is one dataset — Hspell — and its
AGPLv3 covers the word list, not just the code.** `he_IL.aff` states this verbatim and the man
page repeats it. Most Hebrew wordlists in circulation are Hspell wearing a different hat, some
without saying so.

Shipping AGPLv3 data inside a closed APK would place the whole application under AGPLv3.

## BLOCKED — do not use

| Source | Size | Why blocked |
|---|---|---|
| `hunspell-he` / LibreOffice `he_IL` | 469,509 entries | Hspell 1.4 verbatim. AGPLv3 covers the data. |
| HebMorph | — | AGPLv3 **plus** a field-of-use limit: data licensed "ONLY for search by HebMorph". |
| `eyaler/hebrew_wordlists` | — | Most-starred Hebrew wordlist on GitHub. Its `LICENSE` file *is* Hspell's AGPL notice. |
| HeliBoard `main_he.dict` | 468,559 words | Built from `hebrew-hspell.txt.combined.new`, shipped with no license note. An unmarked AGPL landmine. |
| AOSP `iw_wordlist` | 94,799 forms (2014) | `NOTICE` line 192: "Includes Dictionaries (c) Lexiteria LLC. Used by permission." Third-party proprietary data; downstream sublicensing through Apache 2.0 is **UNVERIFIED**. Also far below the usability floor (§2.6). |
| MILA / Technion | — | GPLv3 **and** non-commercial. |
| Academy of the Hebrew Language | — | "Not for publication, subject to confidentiality, access via API." Structurally incompatible with embedding a lexicon in an APK. |
| Leipzig Corpora | — | Their own pages contradict each other on terms. **UNVERIFIED** — not built on. |
| `wordfreq` | — | One of five Hebrew sources is Twitter data under a Developer Agreement that never permitted redistributing derived corpora, and it cannot be separated. Project also sunset. |
| `dicta-il/wordlist` | 4,056,791 forms | The best Hebrew resource in existence, with explicit ktiv haser/male columns. **CC BY-NC 4.0** — blocked as published. See `docs/OPERATOR_NOTICES.md` NOTICE 2b. |

## USED — the clean path

### Source A — verbal backbone

- **URL:** `https://raw.githubusercontent.com/NNLP-IL/Hebrew-Resources/master/linguistic_resources/word_lists/hebrew_verbs_eran_tomer/InflectedVerbsExtended.csv`
- **License:** CC BY 4.0. The repository's `LICENSE` file was verified to be a genuine
  "Attribution 4.0 International" text.
- **Size:** 17,842,473 bytes — **verified byte-exact on 2026-08-20**
- **sha256:** `818793894a360243d471e0f302494b245736189465c8e0258e5665335052a456` — **verified exact**
- **Attribution required:** Eran Tomer.

### Source B — general vocabulary and frequency

- **URL:** `https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/he/he_full.txt`
- **License:** CC BY-SA 4.0 content, derived from the OpenSubtitles corpus via OPUS.
- **Size:** 19,215,890 bytes — **verified byte-exact on 2026-08-20**
- **sha256:** `a69a3f390eb53183bf191c4eac18282592992aef9ff184c5dcf8919f5ea0f773` — **verified exact**
- **Attribution required:** OpenSubtitles / OPUS.

### Why CC BY 4.0 also clears the EU Database Directive

CC BY 4.0 expressly licenses **sui generis database rights**, not only copyright. That matters
in the EU, where a word list can attract database rights independent of copyright. CC BY-SA 4.0
does the same.

### Attribution obligation, in full

One settings screen crediting:
1. **Eran Tomer** — inflected Hebrew verb list (CC BY 4.0)
2. **OpenSubtitles / OPUS**, via `hermitdave/FrequencyWords` (CC BY-SA 4.0)
3. Any source added later.

CC BY-SA 4.0's share-alike obligation attaches to the *database and adaptations of it*, not to
the application that queries it. The derived lexicon artifact and the script that builds it are
therefore published under CC BY-SA 4.0 in this repository (`lexicon/` and
`scripts/build_lexicon.py`), which discharges the obligation without infecting the app.

## Clean sources approved for later addition

| Source | License | Note |
|---|---|---|
| Hebrew Wikipedia dumps | CC BY-SA 4.0 | `dumps.wikimedia.org/hewiki/`, monthly |
| `data.gov.il` tagged corpus | `license_id: other-open`, `isopen: true` | |
| Project Ben-Yehuda | Explicit public domain | Good for literary/rare vocabulary |

## Rule for anyone adding a source

**STOP AND ASK THE OPERATOR if a source's license is unclear.** Do not "probably fine" a
lexicon. Check specifically whether the licence covers the *data* and not merely the code, and
whether any field-of-use restriction applies. Record the answer in this file with the URL,
byte count and sha256 before a single word from it enters the build.

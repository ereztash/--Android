# A2 — how to search for external sources, and what the search found

**The operator's standing belief, stated at the start of this line of work:** *the potential of
external information sources to improve this keyboard is completely unrealized.* The request
here was not for a list. It was for **how it is most correct to search**, so that what comes back
is external grounding rather than a plausible story.

So the method comes first, it is derived from failures this repository can document rather than
from good practice in general, and then it is run.

---

## The method

### Rule 0 — measure what the gap is made of before sourcing for it

`W1` measured that **7.61% of the tokens a person types** are outside the lexicon, against 1.15%
on transcribed dialogue. Everyone's first reading of that number, mine included, was: brand
names, English, emoji.

Measured (`scripts/measure_oov.py`), on 214,844 typed tokens:

| what the OOV actually is | occurrences | of OOV | of all tokens |
|---|---|---|---|
| **Hebrew word not in the lexicon** | 8,184 | **46.9%** | 3.81% |
| pure punctuation (`...`, `!!!`) | 3,853 | 22.1% | 1.79% |
| other (digits+letters, symbols) | 3,115 | 17.9% | 1.45% |
| Hebrew + geresh / quote (`צה״ל`, `ז״ל`) | 1,664 | 9.5% | 0.77% |
| **pure Latin word** | 425 | **2.4%** | 0.20% |
| pure digits | 142 | 0.8% | 0.07% |
| **emoji** | 44 | **0.3%** | 0.02% |
| mixed Hebrew+Latin | 16 | 0.1% | 0.01% |

**Latin and emoji together are 2.7% of the gap.** The intuitive answer was almost entirely
wrong, and a sourcing effort aimed at it would have been close to pure waste. That is what
Rule 0 buys, and it costs one afternoon.

### Rule 1 — check what you already have before searching outside

| lever | cost | buys, in points of typed tokens |
|---|---|---|
| **the prefix chain this repository already ships** | **0 bytes** | **2.04 pp** *(upper bound)* |
| **`""` → `״` normalisation** (the corpus writes the ASCII pair; the table holds the real character) | **0 bytes** | **0.38 pp** |
| hewiki title words, CC BY-SA 4.0 | **+123,346 forms, a 35% larger lexicon** | 0.39 pp |
| an English lexicon | large | 0.20 pp |
| emoji | small | 0.02 pp |

**The two largest levers need no external data at all, and together they are six times what the
best external source buys.** 53.6% of the Hebrew OOV is a prefix away from a word already in the
lexicon; 65.4% of the gershayim OOV is already in the shipped abbreviation table and is missed
only because the corpus spells the gershayim as two ASCII quotes.

The prefix figure is an **upper bound**, labelled the way `H1` labelled its own: `ומחמוד → חמוד`
"resolves" the name *Mahmoud* to the word for *cute*. Landing on a real word is not proof of the
right analysis.

### Rule 2 — search for the property, not for the product

`CORPUS_REGISTER` was built by searching for *Hebrew corpora*, and it found Hebrew corpora. It
never found the one corpus that turned out to be usable — Amram et al.'s **sentiment** set, which
is indexed as `hebrew_sentiment` and which no search for "Hebrew typed corpus" will ever return.
I found it while looking for something else.

The property needed was **typed by a human on a device**. Resources are named for the task they
were *built for*, never for the property they happen to carry. So the search term is not the
artifact — it is every task that *requires* that property: sentiment, hate speech, toxicity,
sarcasm, emotion, spam, forum QA, dialect identification, code-switching.

### Rule 3 — search the index the artifacts live in, not the web

The HuggingFace datasets API, the LDC catalogue, ELRA, CLARIN, OPUS, Zenodo. A web search
returns papers *about* resources; the index returns the resources.

### Rule 4 — loop until dry, and record the queries that found nothing

30 task-proxy queries against the datasets index returned **53 distinct datasets**, and **21 of
the 30 returned nothing not already seen**. That convergence is the stopping rule, and the
queries that came back empty are the finding — without them, *"no closer corpus exists"* goes
back to being available as an assumption, which `CORPUS_REGISTER` explicitly warns about.

**Hebrew has no dataset on that index under `hate`, `offensive`, `toxic`, `sarcasm`, `slang`,
`forum`, `comments`, `whatsapp`, `sms`, `spelling` or `grammar`.** In other languages every one
of those is a typed-text corpus. In Hebrew they do not exist.

### Rule 5 — read the tags, not the name

`ivrit-ai/eval-whatsapp` looks exactly like the thing this project needs. Its task tags are
`automatic-speech-recognition` and `text-to-speech`: it is **audio**, recorded over WhatsApp, not
typed WhatsApp messages. Under 1,000 items, custom licence. The name misleads and the tags do
not.

---

## The register

| source | licence | what it is | measured value here |
|---|---|---|---|
| **Amram et al., Ynet comments** | MIT | 12,804 typed comments | **already used.** `W1`'s slice — the only typed register found |
| **hewiki page titles** | CC BY-SA 4.0 | 660,367 titles → 201,887 Hebrew words, 123,346 not in the lexicon | **0.39 pp**, at a 35% larger lexicon. Catches רבלין, זועבי, אולמרט, יעלון |
| `dicta-il/hebrew-space-restoration-corpus` | **ODC-BY** | 1K–10K items, the dropped-space problem `build_subtitle_corpus.py` rejects 48% of lines for | **not measured.** The closest untried candidate |
| `HebArabNlpProject/HebrewSentiment`, `sepidmnorozy/Hebrew_sentiment`, 5 mteb variants | mixed | typed comments | **likely derivatives of Amram.** Not independent registers; treat as one source until shown otherwise |
| `ivrit-ai/*` (14 datasets) | custom "other" | speech, transcripts, Knesset audio | **wrong modality.** Transcription is the register `A1` measured as not sharing an alphabet with typing |
| `ivrit-ai/eval-whatsapp` | custom | **audio**, n<1K | ruled out by its tags, not its name |
| `guychuk/hebrew-hrm-corpus` | Apache-2.0 | text-generation / reasoning data | wrong register |
| OSCAR / CulturaX / HPLT / FineWeb-2 Hebrew | open | web crawl, keeps punctuation | **not measured.** Volume, not register — `H1` already showed volume is not the bottleneck |

---

## The finding that decides the question

**Measured, the unrealized potential is mostly internal.** Two levers inside this repository beat
the best external source by 6×, and the best external source costs a 35% larger lexicon for 0.39
points.

And the residual matters more than the levers. After the prefix path and the Wikipedia titles,
what is still out of lexicon is dominated by **misspellings**:

    היתבוללות   לגיטמציה   גיזענות   התבללות   זבוטינסקי   שנבחרתה   להיתבוללות

These are not missing vocabulary. **They are the errors the keyboard exists to correct**, and a
lexicon that absorbed them would teach itself to accept them. The OOV rate is therefore not a
number to drive to zero — some of it is the signal, not the noise, and no external source should
be bought to make it smaller.

---

## A correction, recorded rather than left to be found

My first pass reported that **0%** of the gershayim-bearing OOV was in the shipped abbreviation
table, and read that as a defect in a shipped asset. **It was a parse error of mine**: the table
is `bare<TAB>gershayim<TAB>frequency`, and I had compared the gershayim form against the column
of bare forms. Re-measured against the right column the figure is **65.4%**, the asset is
sound, and the real gap is a normalisation one — which is a code fix rather than a sourcing
problem, and a much better answer.

## What this does not say

The decomposition is measured on **Ynet comments**, which are typed and are not phone messaging.
`M10-REGISTER` stays **NOT MEASURED**, and every percentage on this page inherits that.

The searches covered one index thoroughly and named the others rather than exhausting them. LDC,
ELRA, CLARIN and Zenodo are **NOT SEARCHED**, and that is recorded here so it does not become an
assumption.

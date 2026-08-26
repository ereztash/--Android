# Clinical-linguistics transfer into the Hebrew keyboard

Purpose: transfer only defensible, testable insights from the uploaded 2020 speech-language pathology licensing summary into this repository. This is **not** a feature wishlist. Every candidate below separates: source claim -> mechanism -> user friction -> existing repo evidence -> falsifiable experiment -> adoption rule.

## Extraction protocol used

For every source claim:
1. Preserve the claim narrowly and record its source scope.
2. Identify the mechanism relevant to mobile Hebrew text entry.
3. Translate mechanism into a concrete user friction, not a feature.
4. Check whether this repository already measures or solves the friction.
5. Search external literature and keyboard issue trackers for convergent or contradictory evidence.
6. Propose the smallest experiment that could falsify the product implication.
7. Define an adoption rule before implementation.
8. If evidence cannot distinguish a change from a plausible alternative, record `NOT MEASURED` rather than adopting.

## Findings that survive transfer

### C1 — Hebrew correction cannot be treated as edit distance alone

**Source claim.** The source describes unpointed Hebrew as a deep orthography. Word identification can require grapheme-phoneme decoding, morphological root+pattern information, and morpho-syntactic/semantic context. It explicitly uses homography as an example: the same unpointed surface form can require context for interpretation.

**Repository fit.** This matches the repository's existing finding that the lexicon already knows almost all conversational surface forms while the hard problem is choosing among real-word neighbours. `FRICTION_INVENTORY.md` reports 99.16% conversational surface-form coverage and a very dense real-word-neighbour space; the withdrawn real-word layer had poor human-labelled precision.

**Product implication.** Do **not** revive a generic real-word autocorrect layer. The next useful correction experiment must be contextual discrimination between a **small preregistered confusion set**, where the intended alternatives are semantically/morpho-syntactically distinguishable.

**Experiment C1.** Build a held-out, human-labelled set of naturally occurring Hebrew confusion pairs/classes (not injected errors). Candidate families must be selected before labels are inspected. Compare:
- frequency-only baseline;
- current local context/bigram baseline;
- compact morpho-syntactic features that can run offline;
- optional small on-device model only if the previous arm leaves measurable headroom.

Primary metric: precision at user-visible suggestion firing. Secondary: recall and abstention. Do not auto-replace.

**Adoption rule.** Adopt only if a preregistered precision floor is cleared on natural typed text and the added latency/device-memory cost clears the current device budget. Otherwise keep the current tap-only correction architecture.

---

### C2 — Hebrew spelling errors are structurally asymmetric: root letters and function letters are not the same problem

**Source claim.** The source distinguishes root letters from function letters in Hebrew spelling acquisition. Function letters often have a transparent morphological role; root-letter spelling is less transparent and depends on root frequency, morphological family size, semantic relatedness, and word frequency. Homophony is named as a major factor.

**Mechanism.** A keyboard ranking candidate corrections purely by character distance treats two very different errors as equivalent:
- likely grammatical/morphological affix errors;
- uncertain lexical/root spelling errors involving homophonic letters.

**Product implication.** Error candidates should eventually carry an **error-mechanism tag** rather than only edit distance. This does not justify correction by itself; it just creates a representation that lets the product measure whether different classes need different ranking.

**Experiment C2.** On a natural typed-error corpus, blind-label errors into a small MECE taxonomy:
1. function-letter/morpheme error;
2. root-letter/homophonic error;
3. omission/addition/transposition;
4. keyboard-target error;
5. unknown/other.

Measure current top-1/top-3 accuracy per class before changing code. Only classes with enough n and a material baseline deficit become candidates for a specialized arm.

**Adoption rule.** No class-specific code until the taxonomy has inter-rater agreement and demonstrates a decision-changing performance gap.

---

### C3 — Suggestions should be evaluated as retrieval support, not only keystroke prediction

**Source claim.** The source distinguishes vocabulary breadth, depth, and lexical retrieval. It describes retrieval difficulty as potentially semantic or phonological and notes that different cue types can help. It also describes the mental lexicon as a network of syntagmatic, paradigmatic, thematic, and taxonomic relations.

**Mechanism.** A suggestion strip can help in two distinct ways:
- predict the exact next token;
- reduce lexical retrieval effort by surfacing a plausible intended word after partial input/context.

The repository currently evaluates mostly top-k completion/next-word accuracy. That metric cannot tell whether a suggestion was useful as a retrieval cue.

**Product implication.** Add a **measurement**, not a feature: distinguish `prediction hit` from `retrieval assist` in device telemetry that remains local. A retrieval assist is a suggestion selected after a pause/backspace/partial-word sequence where the selected word was not simply the highest-frequency completion at the earliest prefix.

**Experiment C3.** In a consenting local-only pilot, record aggregate counters only:
- suggestions shown;
- suggestions tapped;
- characters already entered at tap;
- pause before tap bucket;
- whether backspace occurred within the word before tap;
- whether selected suggestion changed the lexical item versus merely completing it.

No text leaves the device and raw text need not be persisted.

**Adoption rule.** Only use this to change ranking if retrieval-assist events are common enough to matter and a ranking arm improves them without harming ordinary completion.

---

### C4 — Cognitive load makes correction cost as important as raw accuracy

**Source claim.** The source describes writing as an interaction among language, spelling, attention, working memory, executive functions, transcription/typing, editing and monitoring. It notes that slow or effortful transcription can consume resources otherwise available for formulation.

**Mechanism.** A keyboard can reduce or increase writing load. A wrong suggestion that forces noticing, cursor movement and repair may cost more than several saved keystrokes.

**Repository fit.** The current architecture already refuses silent auto-replacement and requires a tap for text changes. That decision is consistent with minimizing high-cost false corrections.

**Product implication.** Preserve `no automatic replacement` as the default until correction cost is measured. Optimize **expected user repair cost**, not raw top-1.

**Experiment C4.** Device pilot metric:
`net assistance = accepted useful suggestions - weighted repair events`
where repair events include immediate backspace after suggestion tap, retyping, or undo. Pre-register weights from observed median interaction time rather than inventing them.

**Adoption rule.** A model/ranker that raises accuracy but worsens net assistance is rejected.

---

### C5 — Mixed-language typing is normal state, not an edge case

**Source claim.** The source treats bilingualism as dynamic: proficiency can differ by language/domain, and switching between information sets is a normal part of bilingual performance. This does not itself specify keyboard design.

**External/repo convergence.** The repository already found that typed Hebrew contains substantially more Latin/digits than transcribed corpora. External keyboard issue trackers contain Hebrew users asking for reliable Hebrew/English switching, physical-keyboard multilingual support, locale-aware symbols, and correct personal-dictionary language handling.

**Product implication.** Mixed-script continuity should be a first-class test surface. Do not infer automatic language detection from the clinical source; test specific frictions instead.

**Experiment C5.** Create a typed mixed-language benchmark preserving:
- Hebrew + English brand/name;
- Hebrew + number;
- URLs/emails;
- acronym/geresh/gershayim;
- punctuation/brackets around embedded LTR spans.

Measure cursor behaviour, deletion, suggestion continuity, language switching and round-trip text identity.

**Adoption rule.** Any feature that improves monolingual prediction but corrupts or materially degrades mixed-script input is rejected.

---

### C6 — Accessibility opportunity: support difficulty without diagnosing the user

**Source claim.** The source describes reading/writing difficulties involving phonological awareness, spelling, working memory, attention, lexical retrieval and written expression. It explicitly notes that typing can be an alternative mode of written production for some learners.

**Limit.** The source is educational/clinical material, not evidence that this keyboard treats dyslexia, dysgraphia, DLD, ADHD or any diagnosis. The product must not infer or label a condition from typing behaviour.

**Product implication.** Accessibility should be framed as reducing interaction cost for anyone, with optional user-controlled settings. Candidate measurements:
- target size/mis-tap rate;
- long-press burden;
- suggestion readability;
- time to recover from an error;
- TalkBack usability;
- ability to disable distracting prediction while retaining correction;
- ability to enlarge suggestion/key text without layout breakage.

**Experiment C6.** Human usability session with participants recruited by task difficulty rather than diagnosis. Compare task completion time, correction count and subjective effort across settings.

**Adoption rule.** Ship only settings that improve a measured accessibility task without making the default experience worse.

## Findings that do NOT justify implementation yet

1. **Morphology-aware generation/compression.** The clinical source supports the importance of morphology, but this repository already ran multiple morphology-related experiments that ended NOT ADOPTED. Re-open only for a measured friction, not because morphology is theoretically central.
2. **Niqqud.** The source explains its role in orthographic transparency. That does not prove enough users need niqqud entry to justify its build cost.
3. **Diagnosis/personalization from errors.** Do not infer dyslexia, DLD, ADHD, or cognitive state from keyboard behaviour.
4. **Semantic-network suggestion UI.** Lexical-network theory does not by itself justify synonym/category suggestion surfaces on a phone keyboard.
5. **Automatic context correction.** Deep orthography makes context more important; it does not make a contextual model trustworthy enough to alter text without user action.

## New measurement backlog, ordered by information value

| ID | Question | Why now | Requires |
|---|---|---|---|
| CL-1 | What natural Hebrew typing errors actually occur, by mechanism? | Current injected-error corpus cannot answer it | consenting typed-error corpus / human labels |
| CL-2 | Which error classes does the current ranker fail on? | Determines whether morphology/context earns code | CL-1 |
| CL-3 | How often are suggestions used for lexical retrieval rather than completion? | Current top-k metric misses a plausible user benefit | local device counters |
| CL-4 | What is the real cost of a wrong suggestion versus a saved keystroke? | Needed to optimize assistance rather than accuracy | device timing + repair events |
| CL-5 | Does mixed Hebrew-English input remain stable across real apps? | Existing corpora and competitor issues say this is high-friction | device matrix |
| CL-6 | Which optional UI changes reduce typing effort for users who struggle with written input? | Potential accessibility value, currently unmeasured | human usability session |

## Repository constraints carried forward

- No network capability is introduced by any item above.
- No raw typed text needs to leave the device.
- No automatic text replacement is proposed.
- `NOT MEASURED` remains distinct from failure and success.
- Every adopted change must have a positive control / falsifiable gate where technically possible.
- Clinical source claims remain source claims; product conclusions are separately labelled hypotheses until measured.

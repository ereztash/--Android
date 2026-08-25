# QA matrix

Status of every gate and check in the project. Four states only:

- **PASSED** — ran, with a stated denominator, and its positive control was demonstrated red.
- **MEASURED** — a number, with its denominator and the corpus hash it came from.
- **FAILED** — ran and did not meet its bar.
- **NOT RUN** — has not been exercised. Spelled out in full, never omitted.

**There is no "ready except for". While any row below is NOT RUN, the app is not
release-ready — and it is not described as release-ready anywhere in this repository.**

**Last updated: L1 (adaptive learning). Verdict: see [RELEASE_READINESS.md](RELEASE_READINESS.md) — NOT READY.**

---

## Environment these results came from

| | |
|---|---|
| Host | Linux 6.18.5, x86_64, 4 vCPU, 15 GiB RAM |
| JDK (build) | OpenJDK 17.0.19 via `jvmToolchain(17)`; host default is 21.0.10 |
| Gradle / AGP / Kotlin | 8.14.3 / 8.13.2 / 2.2.21 |
| Compose | BOM 2026.06.01 (Compose 1.11.4) — 2026.08.00 needs AGP 9.1+ and compileSdk 37 |
| Android SDK | Platform 36 (`platform-36_r02`), build-tools 36.0.0 |
| minSdk / targetSdk | **31** / 36 — verified in all four built APKs |
| **Device / emulator** | **NONE. No Android device or emulator exists in this environment, and none of the results below were obtained on one.** |

Scale: 44 production Kotlin files, 54 test files, **287 JVM tests**, 10 gate scripts,
**29 gates** — the number `scripts/run_gates.py` defines, counted from it rather than
remembered; this line read **18** while the runner defined 25 — 5 positive-control fixtures.
**Lint: 0 issues.**

---

## Where these gates are proven — and where they were not

**Two of the sixteen gates cannot be proven on a fresh clone**, because their positive control
needs the 37 MB of upstream lexicon sources, which are gitignored. With no sources there is
nothing to rebuild and nothing to corrupt, so the control cannot go red.

That is not a hole in the argument; it is the argument working. `run_gates.py` reports such a
gate as **NOT-MEASURED**, distinctly from `NOT-A-GATE` (a control that ran and stayed green)
and from `PASS`. Under `--strict` it is a failure.

| gate | proven on a fresh clone? | needs |
|---|---|---|
| GATE-LEX-1 `artifact` detector | yes | the repository only |
| GATE-LEX-1 `reproducibility` detector | **no** — NOT-MEASURED, gate reads PASS-PARTIAL | `lexicon/cache/` sources |
| GATE-LEX-2 | **no** — NOT-MEASURED | `lexicon/cache/` sources |
| the other 14 | yes | the repository, plus a built APK for six of them |

CI now warms that cache **best-effort** before running the gates, and prints whether each
source is present, so a NOT-MEASURED row is visible rather than inferred. An upstream host
being down does not fail the build — a red CI nobody can act on is a red CI everybody learns
to ignore.

**Observed on run 15** (commit `8586cc0`, the first fully green run in this project's history):
the fetch succeeded and both gates reported `reproducibility=1`, so all sixteen were proven on
the runner, not merely on the build host. That is the good case and not a guarantee: the row to
read on any given run is the one the table prints, not this sentence.

### The CI history, stated rather than quietly fixed

**CI was red on every push from M1 through M12.** The cause was this exact case: GATE-LEX-2's
control could not run on a runner, `run_gates.py` did not distinguish "could not run" from
"ran and stayed green", and reported `NOT-A-GATE`, failing the job. Every other job — unit
tests, assemble, lint — was green throughout, and the fifteen other gates passed with their
controls red.

Two things were true at once and only one of them was being said: the gates passed **locally**,
where the sources are cached, and the gate job failed **in CI**. Commit messages through M12
say "16 gates green, all controls red" without naming where. That was measured on the build
host, and it was not the whole picture.

`tools/gate_tests/test_gates.py` now moves a source aside and asserts the NOT-MEASURED /
NOT-A-GATE / PASS distinction directly, so this specific confusion cannot recur silently.

---

## PASSED — gates, each with its control demonstrated red

| ID | Check | Denominator | Positive control |
|---|---|---|---|
| GATE-NET-1 | No network capability — manifests | 3 manifests | Planted `INTERNET` permission |
| GATE-NET-1 | No network capability — sources | 65 Kotlin/Java files | Planted okhttp / `java.net` / WebView |
| GATE-NET-1 | No network capability — shipping deps | 113 resolved coordinates | Planted okhttp + Firebase coordinates |
| GATE-NET-2 | **Built debug APK** — permissions + DEX | 1 permission, 16 descriptors over 16 DEX files (28,652,352 bytes) | **A real assembled `netcontrol` APK** |
| GATE-NET-3 | **Built RELEASE APK** — permissions + DEX | 1 permission, **2 descriptors** over 1 DEX file (1,968,128 bytes) | The same real APK against the release baseline |
| GATE-API-1 | No IME API that compiles cleanly and fails at runtime (§1.1/1.3/1.4/1.6) | 104 files, 6 rules | Planted session-override, return-value branch, hardcoded backspace, blocking fetch |
| GATE-API-1 | `getInitial*` accessors only inside the privacy boundary (§1.2) | 104 files | Planted read outside the boundary |
| GATE-API-1 | Nothing typed reaches logcat or stdout | 104 files, production sources | Planted `Log.d` and `println` |
| GATE-CRYPTO-1 | No ECB, hardcoded IV/key, seeded `SecureRandom`, broken primitive | 104 files, 4 rules | Planted `AES/ECB`, fixed IV, hardcoded key, MD5, seeded random |
| GATE-LEX-1 | Shipped lexicon matches its manifest | 1 artifact, 355,587 forms | One byte flipped |
| GATE-LEX-2 | Upstream source integrity | 2 sources | One byte flipped before hashing |
| GATE-LEX-3 | The lexicon **inside the APK** hashes to the manifest's value | 1 asset, 4,607,433 bytes | One byte appended |
| GATE-ASSET-1 | The assets the app opens by name are the ones AGP packaged | 4 named assets, checked against the APK's own entry list | Expect a name AGP does not produce |
| GATE-MANIFEST-1 | IME service declares `exported`, `BIND_INPUT_METHOD`, the action, the meta-data | 4 requirements | `exported` flipped to false |
| GATE-R8-1 | R8 has not stripped what the system instantiates by name | 4 requirements on the minified build | Service declaration invalidated |
| GATE-LEARN-1 | The learned model persists counts over integer ids and nothing that can hold text | 2 learning source files | An encoder that accepts a `String` |
| GATE-LEARN-2 | Learning happens in exactly one place, guarded by `session.mayLearn` | 108 Kotlin source files | A second, unguarded call site |
| GATE-STORE-1 | The committed Play assets are what the app's own resources render to. They are generated from `res/`, not drawn beside it | 2 assets, byte-compared | One pixel of the icon background changed |
| GATE-LEARN-3 | No diagnostic store persists a string it did not choose from a fixed set. `DeviceEvidence` writes to disk from the keystroke path; that promise needs a gate, not a comment | 2 diagnostic stores, 3 string keys allowed by name | A diagnostic that writes down the word a timing was measured on |
| GATE-BIGRAM-1 | The bigram table **inside the APK** is byte-identical to the one every prediction number was measured on, and its header agrees with the manifest | 1 asset, 2,697,304 bytes, 51,900 groups | One byte appended |
| GATE-DOC-1 | The readiness verdict's device-blocked list matches this matrix, **and** the gate denominators published in this table match what the gates actually counted on this tree — **including this row's own id count**, which went stale the day the check for it was written | 22 ids + 5 denominators + this row | A device-blocked id dropped; a denominator off by one |
| GATE-DOC-3 | This matrix does not contradict **itself**: no id sits in the device-blocked table while another row marks it OBSERVED | the same 22 ids against every OBSERVED row | A row left in the blocked table after being marked OBSERVED |
| GATE-DOD-1 | Every criterion in the definition of done cites *something*, states one of the four allowed states, and every waived one names a waiver carrying a date, an owner and a reason | 27 criteria, 2 waivers | A criterion citing nothing, in a state nobody defined, beside a waiver decided in conversation |
| GATE-DOD-2 | Everything a done-criterion cites resolves — a gate the runner defines, a row in this matrix, or a file that exists | the same 27 criteria | A criterion citing a gate and a file that no longer exist |
| GATE-DOD-3 | No done-criterion reads MET while this matrix records its evidence as never run. `NOT RUN` is not `MET`, one level up from `NOT-MEASURED` is not `PASS` | the same 27 criteria against every not-run row | MET claimed over `M7-LAT` — "ready except for", written one row at a time |
| GATE-DOD-4 | The counts `DEFINITION_OF_DONE.md` publishes are the ones its own tables contain, per-tier verdict included | 18 published numbers | One published count off by one |
| GATE-SIZE-1 | The release artifact stays inside a budget written down **after** measuring it | 3 budget entries | Assets measured 50% larger |
| GATE-XML-1 | Every XML resource parses | 15 files | A comment containing `--` |
| GATE-TRACE-1 | The benchmark measures sections the app actually emits | 2 section names | Requested sections renamed |
| GATE-TRACE-2 | No traced region contains a call that suspends. `Trace` sections are per-thread, and a coroutine resuming on another worker closes a section it never opened | 2 traced regions, 6 suspending names | `readUserModel()` inside a `beginSection` region |
| GATE-DENOM-1 | A check that examined nothing never reports PASS | meta-gate | The network gate over an empty directory |
| GATE-META-1 | A neutered control is caught as `NOT-A-GATE` | 3 gates | A control file emptied by hand |

## PASSED — tests

| ID | Check | Denominator |
|---|---|---|
| TEST-CONST | §3 restricted-field constants match the platform | 16 assertions / 14 constants |
| M1-XCHECK | Kotlin shipped path agrees with the Python measurement | 75,000 tokens × 5 settings, **exact** |
| M1-SORT | Lexicon blob really is byte-sorted | 355,587 entries compared |
| M1-ROUNDTRIP | Every index round-trips | 355,587 entries |
| M2-CTXBUF | `InputContextBuffer` desync and recovery | 12 tests |
| M3-GRAPHEME | UAX #29 backspace widths | 30 assertions, 17 inputs |
| M3-GEOMETRY | Rows tile exactly; every key centre hit-tests to itself | 3 layouts, every key. **The mirroring assertion was deleted in M9** — it asserted the bug. See docs/milestones/M9.md. |
| M3-LAYOUT | Hebrew layout carries all 27 letters once, incl. 5 final forms | 27 letters |
| M4-PRIV-FETCH | **Initial text never fetched for a restricted field, tested with a field that DID contain text** | 9 input types × a real password string |
| M4-SWEEP | Exhaustive input-type sweep, unknown values fail closed | **4,096** variations + **16** classes |
| M5-TRIE | Trie agrees with reference Damerau-Levenshtein | exhaustive, 14 words × 22 queries |
| M5-CTRL | False auto-replace on known-correct words | 4,000 words — control: an indiscriminate replacer scores 100% |
| M6-IV | IV uniqueness across seals of identical plaintext | **2,000 seals**, all distinct |
| M6-TAMPER | Tampering with version, IV, body or tag each detected | 4 regions |
| M6-MODEL | The dictionary cannot hold anything but one Hebrew word | 9 rejection cases + reflective surface check |
| M7-A11Y-NAMES | Every key has a distinct, non-empty spoken name | 73 keys, 27 letters |
| M9-LAYOUT-STD | Hebrew key order matches SI-1452 read from the standard, not from the code | 3 rows, every key |
| M10-UNICODE | Every code point in U+0591..U+05C7 classified as `Character.getType` classifies it | **55 code points**, 51 marks + 4 punctuation |
| M10-BOUNDARY | The buffer's sentence-boundary set is pinned to `BOUNDARY_RE` in `build_bigrams.py`, read from the script | 7 boundary characters + `--` |
| M10-PRED-CTRL | A context-free predictor scores measurably worse through the same harness | 20,000 per cell — control: it cannot answer next-word at all, asserted at exactly 0 |
| M10-FALSEFLAG | Correct words are never offered as corrections | **20,000 in-lexicon words**, 0 flagged — control: an always-correcting engine scores 100% |
| M11-NOCTX | Every real-word flag comes from context, not from the confusion inventory | 69,494 sites with an **empty** bigram table, 0 flagged |
| M11-FLOOR | The bigram table's minimum stored log-count is the arithmetic floor of its pruning threshold | 532,168 entries; 21 = `round(log2(6)*8)` |
| M11-CTXBUF | Multi-word context and the span arithmetic a non-adjacent replacement depends on | 10 tests |
| M11-WIRING | A `TypingContext` in, a ranked strip out — the two halves are actually connected | 6 tests |
| M12-PERSONAL | The personal dictionary actually affects typing | 8 tests — control: without it the same word IS corrected |
| L1-POLICY | Which fields may be learned from | 8 tests, 6 field classes + a 4,096-variation sweep — **control: a normal text field DOES learn, end to end** |
| L1-ELIGIBLE | A once-seen pair is counted and never offered | control: a pair seen in 2 separate sessions IS offered |
| L1-NEUTRAL | An empty user model changes nothing | **135,960 contexts** — control: a populated model does change the output |
| L1-WIPE | The two encrypted stores are independently destroyable | 4 tests — control: a shared key WOULD have coupled the wipes |
| L1-SENTINEL | The OOV sentinel is never offered and never costs a slot | asserted at 3 suggestions returned |
| VERIF-SDK | §1 / §3 claims checked against `android.jar` + `api-versions.xml` | 31 claims: 28 confirmed, 0 contradicted, 3 not checkable |
| VERIF-LEX | Lexicon sources byte + sha256 exact | 2 of 2 |

## MEASURED — numbers, with their denominators

| ID | Measurement | Result | Denominator |
|---|---|---|---|
| M1-REPRO | Lexicon counts vs the build spec | **+0.000% on all four** (102,239 / 298,162 / 355,587 / 57,425) | 241,797 + 1,167,621 input rows |
| M1-COV | Token coverage on held-out Wikipedia, MIN_STEM=4 | **96.73%** (3.27% wrong-underline) | 75,000 tokens, corpus sha `02fe828c…` |
| M1-COV-NONE | Same, no prefix stripper | 94.61% | same corpus |
| M5-TOP1 | Correction top-1, shipped config | **52.60%** | 4,000 uniform typos, sha `f9f4ed80…` |
| M5-TOP3 | Correction top-3, shipped config | **66.23%** | same corpus |
| M5-FALSE-C1 | False auto-replace, known-correct words | **0.00%** | 4,000 words, sha `6e13ffd6…` |
| M5-FALSE-C2 | False auto-replace, raw held-out tokens | **0.68%** | 4,000 tokens, sha `c2e89437…` |
| M5-ADJ | Keyboard-adjacency discount effect | **−7.97 points** top-1; wrong auto-replacements ×8 | same corpus — **feature measured and NOT enabled** |
| M5-P95 | Suggestion latency p95 | 2.88 ms | 4,000 queries, **JVM on the build host — NOT a device number** |
| M5-STRUCT | Trie over the real lexicon | 567,767 nodes, 73.3% prefix sharing, 7.58 MiB, 148 ms build | 355,587 words |
| M10-PRED-1 | Completion top-3, 1-letter prefix | **5.73%** (2.15% without bigrams) | 20,000, slice sha `cedfb5be…` |
| M10-PRED-2 | Completion top-3, 2-letter prefix | **25.77%** (15.80%) | 20,000, same slice |
| M10-PRED-3 | Completion top-3, 3-letter prefix | **49.28%** (38.27%) | 20,000, same slice |
| M10-NEXT | Next-word top-3 | **9.80%**, offered in 88.36% of positions | 20,000, same slice |
| M10-MIX | Ordering policy: corrections-first vs completions-first | corrections-first **dominated** — worse on both corpora | 20,000 + 4,000 — baseline measured, then replaced |
| M11-RECALL | Real-word error recall, shipped config | **63.73%** | 45,867 injected errors, test slice sha `9fc528ae…` |
| A1-PRECISION | Real-word error precision against **human** judgement, blind, on held-out conversational text | batch 001 **[10.0%, 43.8%]** NOT DECIDABLE; batch 002 **[13.3%, 38.3%]** NOISE. Neither reaches the 60% ship band at any confidence | 80 + 240 firings judged, 20 + 60 controls, 18/20 and 57/60 passed |
| GATE-FONT-1 | The typeface **inside the APK** is the one the letter-pair measurement ranked, matched by CONTENT because R8 renames the resource | 1 typeface, 16,480 bytes | One byte appended to the packaged font |
| FONT-CHOICE | Which Hebrew face makes letter pairs hardest to confuse at the label's real pixel size | **Noto Sans Hebrew**: 10 at-risk pairs of 351 at 81px, against 12 / 14 / 22 for Assistant / Heebo / Rubik. The at-risk set — ה/ח, ח/ת, ט/ס, ג/נ, ב/כ, ד/ר — shares **no pair** with the phonetic confusion set the corrector uses | 27 letters, 351 pairs, 3 sizes |
| LEARN-BENEFIT | What adaptive learning DID, not what it stored: accepted completions that would not have been on screen without it | Counted on device, shown in settings. Causal, not "influenced" — a counterfactual re-rank with the model removed | 2 integers, no text |
| B1-ALLOCATION | Does reallocating the bigram table's bytes move prediction? Per-group cap + lower min-count, four variants at or under budget | **No.** Doubling groups (51,900→101,765) and tripling pairs buys **+0.61** next-word top-3 and costs 2.3–5.8 completion points. All four fail the rule fixed beforehand; nothing adopted. Offer rate rises +5.68 points, and `cap64/mc5` saves **609,592 APK bytes** at zero next-word cost | 20,000 per cell, eval slice |
| S1-WITHDRAWN | The distance-2 layer, shipped for one commit on the operator's decision, is out again | Earned **+0.11 recall points** over the prior alone and spoke **twice in 1.8M words**; cost 387,300 APK bytes. APK 5,457,116 → **5,069,695**; assets headroom 349,926 → **737,856** | measured on the release artifact |
| A1-FLAGSHIP | Precision on `אם`/`עם` — the pair the feature was built for and named after | **1 agreement in 9**; two-letter words overall **2 in 68**. Not a coverage failure: `אם` has 1,044 stored continuations and `עם` 4,473, among the best-covered entries in the table | 68 two-letter firings of 320 |
| A1-SPARSITY | How often is the detector's trigger condition true of CORRECT Hebrew? | **37.7%** of adjacent in-lexicon pairs are unseen in the shipped table; 21.3% of positions are blind on both sides. This is the mechanism behind the 12.5% precision floor and it is a property of corpus size, not of any threshold | 33,876 pairs, 27,734 positions, conversational test slice |
| A1-STABILITY | Is the single labeller reliable? 40 repeats re-shown blind, at least a day apart | **direction stability 96.2%** (25/26), four-way 75%. The one reversal favoured the text, so noise inflates the detector's score rather than deflating it | 26 pairs decided twice, 10/10 controls |
| S1P1-RECALL | The same, on conversational text | **78.45%** at 0.198% false alarms | 21,961 injected errors, 27,726 clean sites |
| M11-FALSE | Real-word false-alarm rate on untouched text | **0.25%** | 69,494 positions, same slice — control: a permissive detector scores 15.08% |
| M11-COST | One real-word check | 3.7 µs | 20,000 calls, **JVM on the build host — NOT a device number** |
| L1-ADAPT | Adaptive next-word, shipped config, vs static on the identical split | **+0.67 top-1 (+392), +0.48 top-3 (+279)**; offer rate 88.68% → 88.78% | 58,343 positions, 120 pseudo-users, slice `d8177a78…` |
| L1-COLD | Cold start | +0.00 at 0 sentences of history, +0.14 at 10, +0.57 at 40 | same slice |
| L1-PROTECT | What the once-seen protection costs | −0.32 top-1, −0.39 top-3 vs `minimumSessions=1` | same slice — **the cheaper setting is not shipped** |
| L1-WITHHELD | Share of learned pairs that are eligible to be suggested | **5.8%** (mean 52 of 888 per pseudo-user) | 120 pseudo-users |
| L1-OOV | Share of learned pairs touching the out-of-lexicon sentinel | 8.3% | 106,545 pairs |
| K1-SCORE | Per-keystroke cost of int8 scoring over 8 candidates, in Kotlin, at a shape that fits the asset budget today | **0.018 ms p95** (16,384 units, 548,992 bytes) against a **0.520 ms** whole-path baseline measured in the same run | 2,000 timed steps after 500 warm-up. **Arithmetic only — random weights, no accuracy claim.** Build host, not a phone: this can kill the branch and cannot bless it. Control: shape E full-softmax breached the bar at 9.016 ms |
| K1-SEPARATION | Whether scoring cost depends on vocabulary size | **It does not.** 355,587 units score in 0.026 ms; 8,192 units in 0.020 ms | Latency is set by `hidden` and `k`; bytes by `vocab × emb`. Vocabulary is bought with bytes and never with milliseconds |
| K1-MEMORY | Resident footprint against serialised bytes | **NOT-MEASURED** — the instrument returned −408,760 bytes for one shape | A negative resident size disqualifies the reading in both directions. The probe also holds a redundant same-size buffer, so the ratios measure the harness. Needs a heap dump or a deterministic retained-size calculation |
| H1-KTIV-DENSITY | Share of conversational tokens one `ו`/`י` deletion from another real word | **50.78%** conversational · 47.30% wiki | 40,434 / 85,840 Hebrew tokens. **Upper bound** — it counts real-word neighbours, which is broader than true ktiv variation. This is the structural reason no threshold rescues the real-word layer |
| H1-PREFIX | Share of conversational tokens carrying an agglutinated prefix chain | **42.81%**, of which 42.38% are **also** stored as their own surface form | Same denominators. **Upper bound** — a heuristic, not a morphological analysis; `מים` decomposes as `מ`+`ים` and `ים` is a word |
| H1-COVERAGE | Share of conversational tokens already in the lexicon as surface forms | **99.16%** conversational · 95.01% wiki | The lexicon is not the bottleneck; correction is not failing for lack of words |
| O1-OFFER | Withholding the next-word offer on weak evidence — best configuration reaching 20% precision among offered | dev **22.53%** / test **22.54%** precision, at **13.19%** retention against a 70% bar | 20,000 positions per slice, committed eval slice split even/odd. **RULE NOT MET, nothing adopted.** `s1` is flat; `margin` is the only signal that moves |
| O1-REGISTER | The same shipped engine on held-out transcribed dialogue instead of encyclopedic prose | prefix-1 completion top-3 **5.35% → 23.72%** (×4.43); next-word top-3 **9.09% → 12.09%** | 20,000 positions each. Held out by construction from the subtitle half of the training mix. **Not phone typing** — `M10-REGISTER` stays NOT MEASURED |
| M12-SIZE | Release artifact | APK 5,161,766 B; AAB 5,940,182 B | R8 cut DEX from 28,527,620 to 1,922,156 B. Assets are 3,023,216 B, of which the bigram table is 1,849,636 B |

## FAILED

**None.**

## RESOLVED

| ID | Was | Now |
|---|---|---|
| LINT-1 | `suppressesSpellChecker` is API 31 while minSdk was 30, so on API 30 the system spell checker stayed active over text this keyboard promised stays on-device | **Resolved by raising minSdk to 31** on operator instruction. Lint went from 1 warning to **0**, which is the direct confirmation that the attribute is now honoured. |
| M2-CONFIGCHANGE | `android:configChanges` was not read at all on API 30, so the declared mitigation did nothing there | **Resolved by the same change.** `InputMethodInfo.getConfigChanges()` is API 31, so the declaration is honoured on every supported device. State is still kept off the view, because the attribute enumerates specific changes rather than all of them. |

---

## NOT RUN

Nothing below has been exercised. None of it is described anywhere as working.

### OBSERVED ON A DEVICE — by the operator, on their own phone

**This section exists because the claim above it used to be false.** The operator installed the
app on their phone, enabled it, typed with it, and sent screenshots. That session is what found
the mirrored layout; M9 exists because of it. Until now this document went on saying nothing had
ever run on a device, which was written at M8 and never corrected.

Provenance: the operator's phone, 2026-08-20, from a build **before `913354a`** (the M9 fix) —
i.e. at or before `8352313`. Not a controlled test: no logs, no timings, no device model
recorded here. What follows is what a human watching the screen can attest to, and nothing more.

| ID | Check | Evidence | Now |
|---|---|---|---|
| M2-ENABLE | The IME can be enabled and selected from system settings | The operator did it; there is no other way to type with it | **OBSERVED** |
| M3-TOUCH | A touch has ever been dispatched to `KeyboardView` | The operator typed: *"זה עובד על הטלפון ואפשר להקליד"* | **OBSERVED** |
| M2-INSTALL | The APK installs and the keyboard renders on a real panel | Screenshots | **OBSERVED** |
| M3-LAYOUT-BUG | The layout was **wrong** on screen | *"כאילו זו מראה"* — the report that produced M9 | **OBSERVED, and fixed** |

**Second device session, 2026-08-21, from the build at `4a19a90`** — the operator's phone again,
typing into WhatsApp. This one exercised everything built after the first session:

| ID | Check | Evidence | Now |
|---|---|---|---|
| M9-LAYOUT | The **corrected** layout on a real panel | Top row reads `ק ר א ט ו ן ם פ` left-to-right, matching a physical SI-1452 keyboard | **OBSERVED** |
| M10-STRIP | The candidate strip renders and is populated | Three candidates with dividers, right-to-left, best on the right | **OBSERVED** |
| M10-NEXTWORD | Next-word prediction from the bigram table | After `גבינה`: **רכה**, **צרפתית** | **OBSERVED** |
| M11-REALWORD | **Context-dependent real-word error detection** | `אני אוהב עוגת שוקולד **אם** גבינה` → offered **`עם (אם)`**, in the correction colour, ranked first | **OBSERVED** |
| M11-GENERALISES | A **second, different** confusion caught on device | `אני אוהב **עת** ברצלונה` → offered **`את (עת)`** | **OBSERVED** |
| M11-COLOUR | Corrections are visually distinguished from ordinary suggestions | The real-word suggestion rendered amber, the two next-word suggestions white | **OBSERVED** |
| M11-EDIT | **Accepting** a real-word suggestion: deleting a span two words back and committing a rewrite, against a real `InputConnection` | The operator tapped it: *"ואני ניסיתי, וזה עובד ללחוץ על להחליף מילה"* | **OBSERVED** |
| M2-ROTATION | State survives rotation | The operator rotated mid-typing: *"הסיבוב עובר כמו שצריך"* | **OBSERVED** |
| M10-TAP | A suggestion has been tapped at all | Same | **OBSERVED** |
| M5-LOAD | The lexicon, trie and bigram table load from APK assets on a device | Suggestions could not appear otherwise | **OBSERVED** |

The `אם`/`עם` case is the one the operator named when commissioning this feature. It had never
run outside a JVM until this session.

The second catch matters more than the first for a reason worth stating: `אם`/`עם` and
`עת`/`את` are **not two entries in a list**. `HebrewConfusions` generates candidates from
letters realised identically in Modern Israeli Hebrew — `א↔ע`, `ח↔כ`, `כ↔ק`, `ת↔ט`, `ב↔ו`,
`ס↔ש` — so both are the same `א↔ע` substitution, produced rather than enumerated. One catch
could have been a lucky lookup; two from different words through the same rule is the mechanism
working.

**What this session did NOT establish:**

- **Why the previous build produced no suggestions at all is UNCONFIRMED.** Between that build
  and this one the warm-up's heap floor was halved (see `docs/LEARNING_MEASUREMENTS.md`
  and the L2 commit) and nothing else on the suggestion path changed, which makes memory
  pressure the leading explanation — but the in-app diagnostic that would settle it has not
  been read yet. Recorded as the leading hypothesis, not as a finding.
- Latency, TalkBack, Keystore and packet capture remain untested.
  *(This bullet said "Latency, **rotation**, TalkBack, ..." until 2026-08-21, while
  `M2-ROTATION` sat OBSERVED in the table directly above it. The row was added in a later
  edit and the prose beneath it was not. Same failure as the one this file's own correction
  section describes, three sections further down, pointing the same way: a claim kept in
  step by hand went stale in the pessimistic direction. `GATE-DOC-1` did not catch it
  because it compares this file against `RELEASE_READINESS.md`, not against itself.)*
- Adaptive learning is off by default and was not enabled in this session, so nothing about it
  has run on a device.

**What that session did NOT establish, and is not credited with:**

- It ran a build from **before M10, M11 and M12 existed.** Prediction, real-word error
  detection and the personal-dictionary wiring have never run on a device — they were written
  after those screenshots.
- The layout it exercised was the mirrored one. The **corrected** layout has never been seen on
  a phone by anyone.
- Nothing was measured. No latency, no memory, no rotation, no logs. "It typed" is an
  observation, not a measurement, and it is recorded here as an observation.
- The bottom row was not *reported* as clipped, which is not the same as M2-INSETS passing. An
  absent complaint is not a check.

**Third device session, 2026-08-21, WhatsApp again.** Provenance is weaker than the two above
and is recorded as weaker: the build is the debug APK sent after `1f805a1`, **as reported by the
operator**. A screenshot cannot identify a build, and nothing in this one does — the strip shows
post-M12 behaviour, so the build is at least M12, and everything past that rests on the report.
Sources: the operator's message *"יש רטט, יש הצעה ללמידה, אני על מכשיר, זה עובד"* and the
screenshot attached to it.

| ID | Check | Evidence | Now |
|---|---|---|---|
| M2-ENABLE-POST-M9 | The app still installs and the IME still enables on a build later than the one M9 fixed | The operator installed it, enabled it, and typed into WhatsApp with it | **OBSERVED** |
| MI-HAPTIC | Key taps produce haptic feedback on a real panel | *"יש רטט"*. `KeyboardView` calls `performHapticFeedback`; the platform side had never been confirmed | **OBSERVED** |

**`MI-HAPTIC` was not in this matrix until it passed.** That is worth saying out loud rather than
quietly adding a green row. Haptics shipped at MI and no line here ever recorded that the device
end needed a device. The hole was found by the observation, not by the matrix — which means the
matrix's coverage of the micro-interaction work is itself unmeasured, and `MI-PREVIEW`,
`MI-REPEAT`, `MI-LONGPRESS`, `MI-CONFIRM` may not be the whole list either.

**Key proportions: measured from the screenshot, and the report was wrong.** The operator
reported *"the top row keys are narrower than the middle row"*. Pixel measurement of their own
screenshot says otherwise, and says it precisely:

| Row | Keys | Measured fill width | Row span |
|---|---|---|---|
| Top `קראטוןםפ` | 8 | **67.6 px** (67–68) | 152 → 739 |
| Middle `שדגכעיחלךף` | 10 | **67.6 px** (67–68) | 78 → 814 |
| Bottom letters | 9 | **67.3 px** (67–68) | 115 → 776 |

The widths are equal to within a pixel of JPEG noise, and the key edges of the top row fall on
**exactly** the same x values as the middle row's — one shared grid, which is what
`KeyGeometry.layout`'s global unit is for. The predicted edges match the measured ones to the
pixel: unit = 891/12 = 74.25, top-row margin 2 units → first edge at 148.5 + GAP = **152.5**,
measured **152**; middle-row margin 1 unit → 74.25 + GAP = **78.25**, measured **78**.

What differs is the **row length**, not the key width: the top row has 8 keys and spans 587 px,
the middle has 10 and spans 736 px, so the top row is inset 148 px at each end against the
middle row's 74 px. That inset is what reads as "narrower". The fix at `ae2c5da` is doing
exactly what it was written to do; the perception it produces is a **design question**, not a
defect, and it is the operator's to decide. Recorded here so the next report of it does not
start over.

*(Side result: the same arithmetic dates the screenshot's scale. 74.25 − 67.6 = 6.65 px must be
`2 × GAP` = 8 device px, so the image is at 0.825 scale and the device is 1080 px wide. Every
device-pixel figure in `LabelFitTest` is derived from that.)*

**`MI-LABELFIT` — a real defect the same screenshot shows.** The multi-character labels are
drawn at the size set for one Hebrew letter and overflow the rounded rect they sit in: `en` by
about 5 device pixels on an 82-pixel key, `123` by about 3 on a 127-pixel one. Both stay inside
their own key's touch area — measured, and asserted in `LabelFitTest` so the claim cannot
silently widen — so nothing is mis-typed. What it costs is the border, and three same-coloured
function keys whose labels touch read as one block. `KeyGeometry.fitTextSize` shrinks a label
to fit and never grows one. **Not verified on a device**, so `MI-LABELFIT` is device-blocked.

**A third real-word catch, and why it counts for less than the two before it.** The screenshot
shows `תרעי` flagged and **`תראי (תרעי)`** offered in the correction colour — the same `א↔ע`
substitution as `אם`/`עם` and `עת`/`את`, on a third word pair. It is **not** credited as a new
row, because of the shape it fired in:

- The flagged word had **no left neighbour** in the visible field. It sat first, with only a
  right neighbour (`ה`), so the detector ran on **one** context word.
- One-context-word firings are **1.85% of the harvest** — 40 of 2,166 — and are essentially
  unmeasured. Of the 320 real firings among the 465 blind-labelled screens, **four** were
  one-context-word, **two** of those were decided, and **both decided ones were false alarms**
  (`b002-025` `העם`, `b002-292` `חי`). 0 of 2 is not a precision estimate; it is a statement
  that this shape has never been measured.
- The published precision interval — **12.5% to 39.7%**, 465 screens, `docs/LABELING_LOG.md` —
  is **not updated by this**, in either direction. One catch that looks right is an anecdote,
  and that interval was built to be immune to anecdotes.

So the mechanism produced a plausible correction in the least-measured configuration it has.
That is a thing to watch, not a thing to publish.

### The device can answer some of these itself

**Settings → Device self-check.** Most of the table below never said "a human must judge this".
It said "a device", and then waited months — because it needed a device *and* a person looking
at it at the right moment. A large share of it is not judgement at all: whether the bottom key
row clears the gesture inset is a subtraction; whether a Keystore key is hardware-backed is a
field on `KeyInfo`; whether a label overflows its key is the comparison the drawing code
already makes on every layout.

So the gate harness is ported to the phone. `DeviceSelfCheck` runs each check twice — once
against the device, once with a defect planted — and any check that stays green under
injection is reported **PROVES NOTHING** and counts as evidence for nothing. That is
`run_gates.py`'s rule, and it is there for the same reason: on a phone there is no build server
to notice a check that always says yes.

| Check | What the device measures | The defect its control plants |
|---|---|---|
| M2-INSETS | lowest key bottom vs the inset the window reported | keys drawn to the window's bottom edge |
| M8-NETPERM | the permissions the package manager sees | `INTERNET` present |
| M4-DEVICE | restricted fields seen / served / initial-text reads | one served restricted field |
| M6-KEYSTORE | `KeyInfo.securityLevel` for both aliases | the key reported as living in software |
| M7-CONTRAST | WCAG ratio of the colours **this device resolved** | the label drawn in the key's own colour |
| M7-LAT | p50/p95/max of the real keystroke path | a p95 one microsecond over budget |
| R2-FONT | whether `R.font.keyboard_label` resolved | the font failing to resolve |
| MI-LABELFIT | labels overflowing at the size actually drawn | one label over its key |
| M12-RELOAD | that the reload path ran | the reload never happening |
| MI-COUNTERS | preview / backspace-repeat / long-press executions | none of the three paths run |

**What this does NOT do, stated before anything is credited to it:**

- **It does not remove a single row below.** Every id above is still device-blocked and stays
  device-blocked until a real device runs it and the report comes back. What changed is that
  the answer is now a number the phone produces, not a person's recollection of a screen.
- **It cannot see the panel.** `M7-CONTRAST` is arithmetic on resolved colour values; a display
  with a night filter or low brightness can be illegible at a ratio that passes.
- **It cannot judge.** Whether the keyboard feels fast, whether a suggestion was useful,
  whether a shrunk label is still readable — none of that is arithmetic. `MI-PREVIEW`,
  `MI-REPEAT`, `MI-LONGPRESS`, `MI-CONFIRM`, `R1-FEEL`, `L2-PERSONAL`, `M6-UI`, `L1-SWITCH`
  and `M7-TALKBACK` stay human, and `MI-COUNTERS` answers only the half a counter can: whether
  the path has ever run.
- **`M8-NETPERM` is not `M8-NETCAPTURE`.** It reports the permission as the package manager
  sees it, which proves the OS would refuse a socket. It does not prove no attempt was made,
  and the packet capture stays open.
- **One device, one posture.** `M2-INSETS` measured on a gesture-navigation phone in portrait
  says nothing about three-button navigation or landscape.
- **`M7-LAT` measures the keyboard's own work** — plan, batch edit, learn, refresh — and not
  the frame the system draws afterwards. A person's perceived latency is this plus a vsync.
  It is also not the benchmark harness's `TraceSection` figure and must not be quoted as one.

**Two defects the self-check introduced, found by reviewing it rather than by running it.**

1. **It minted Keystore keys to measure them.** `M6-KEYSTORE` called `getOrCreate`, which
   creates the key when it is absent — so merely opening the self-check would have created a
   learning key for a user who never turned learning on, and a dictionary key for one who never
   added a word. The settings screen already refuses to load the learned model while the
   feature is off for exactly this reason. It now uses `exists` and reports **NOT-MEASURED**
   when a key has never been created, which is both the honest answer and the useful one.

2. **The instrument added latency it did not measure.** `recordKeystroke` flushed inline: a
   256-element sort and four preference writes on the keystroke path every sixteenth key —
   *after* the timestamp for that keystroke had already been taken. So the cost landed on the
   user and never appeared in `M7-LAT`. Both the flush and the micro-interaction counters
   (`recordPreviewShown` is called from `onDraw`) now run on a single low-priority daemon
   thread. The ring update stays on the caller's thread: four field writes under a lock held
   for nanoseconds, and handing that off would cost an allocation per key.

**On the privacy of measuring this at all.** `DeviceEvidence` writes to `SharedPreferences`
from the keystroke path — a file on disk, written while the user types, in a process that can
see everything they type. That is the exact shape of the thing this project promises never to
build, so the promise gets a gate rather than a comment: **`GATE-LEARN-3`** fails the build if
any diagnostic store persists a string it did not choose from a fixed set. Raw keystroke
timings never leave memory either; a sequence of per-key intervals is a rhythm, and rhythm
identifies people, so only percentiles and a count are written down.

### Requires a physical Android device — still not exercised

| ID | Check | What it needs |
|---|---|---|
| M2-INSETS | The bottom key row clears the gesture bar at targetSdk 36 | A device with gesture navigation |
| M2-SPELLCHECK | The system spell checker is actually suppressed on API 31+ | A device |
| M4-DEVICE | That the framework really does hand over password plaintext, and that `setInitialSurroundingText("")` releases it | A device; `android.jar` ships stubs only |
| M6-KEYSTORE | Keystore key generation, TEE/StrongBox backing, key destruction on wipe | A device |
| M6-UI | The dictionary management screen has ever been displayed | A device |
| M7-LAT | **p95 keystroke latency** via `TraceSectionMetric` | A device, **and the keyboard enabled and selected as the active IME** — which a benchmark cannot arrange for itself without `WRITE_SECURE_SETTINGS`. The harness is built, committed, and assembles in CI. |
| M7-TALKBACK | Whether the virtual view nodes are usable with TalkBack | A device with TalkBack |
| M7-CONTRAST | Perceptual legibility of key colours on a real panel | A device |
| M8-NETCAPTURE | A packet capture confirming no traffic | A device |
| M12-RELOAD | Whether the personal dictionary reload in `onStartInput` picks up a word added in Settings while the IME is running | A device with both components live |
| L1-KEYSTORE | That two Keystore aliases really are independent on hardware, and that deleting one leaves the other usable | A device. The JVM test proves the property the aliases exist for; the Keystore lookup itself has been NOT RUN since M6. |
| L1-SWITCH | Whether the learning switch, the status count and "forget what you learned" behave on screen | A device |
| MI-PREVIEW | The key-preview bubble, including the flip-below behaviour on the top row | A device |
| MI-REPEAT | Accelerating backspace: that the repeat rate is usable and that a tap never triggers it | A device |
| MI-LONGPRESS | The gershayim long press, and that it replaces rather than appends | A device |
| MI-CONFIRM | The away-from-cursor confirmation is actually noticed | A device, and a person to notice it |
| R1-FEEL | Whether the conversational corpus makes suggestions feel more useful in real messages | A device. The +12.73 points are measured on transcribed dialogue, not on written messages. |
| L2-PERSONAL | Whether personal word frequency is noticeable in use | A device, with learning enabled |
| L1-DEBOUNCE | Whether a 3-second debounced encrypted write actually stays off the input path | A device. The interval is a judgement, not a measurement. |
| R2-FONT | That the typeface `FONT-CHOICE` selected is the one actually drawing key labels on a panel | A device. `GATE-FONT-1` proves the right bytes are packaged; it cannot prove `ResourcesCompat.getFont` returned them at runtime rather than falling back |
| L2-BENEFIT | That the benefit counter ever shows a non-zero number in settings | A device, learning enabled, and enough accepted completions to move it. `LEARN-BENEFIT` is exercised in JVM tests only |
| MI-LABELFIT | That `123`, `en` and `he` sit inside their keys once shrunk, and stay legible at the reduced size | A device. The arithmetic is tested; how a shrunk label reads on a panel is not arithmetic |

### Requires operator action

| ID | Check | Blocker |
|---|---|---|
| M8-SIGN | Signed release AAB | **Signing secrets. Not invented here.** See `docs/OPERATOR_NOTICES.md` NOTICE 4. |
| M8-STORE | Store listing published, Data Safety submitted | A Play Console account; NOTICE 1's deadline |
| M8-TESTING | Internal testing track | Depends on M8-SIGN |
| M8-ASSETS | Store icon, feature graphic, screenshots, hosted privacy-policy URL | Screenshots need a device; the URL needs hosting |

### Not measured, and not fabricated

| ID | Check | Why it cannot be answered here |
|---|---|---|
| NATIVE-1 | What is inside the four native libraries the APK already ships | `libandroidx.graphics.path.so`, one per ABI, 37,392 bytes total, arrived transitively with a graphics dependency. `check_apk.py` and `check_no_network.py` both record in their own `NOT_COVERED` that native code is not scanned, so the no-network proof does not reach it. It was in neither table until 2026-08-25 — the same shape of gap as `MI-HAPTIC`. See `docs/CONFUSION_MEASUREMENTS.md`, *D1's constraint, re-examined*. |
| H1-ALPHABET | How often Hebrew typing mixes scripts, or uses geresh/gershayim, and what the keyboard does at those points | Every corpus here is Hebrew-letters-only **by construction** — `build_subtitle_corpus.py` keeps `[א-ת]+` runs and `build_eval_corpus.py` does the same. Latin, digits, gershayim and all punctuation are discarded before a token is written, so the measured 0.00% is a property of the regex and not of Hebrew. The app ships 861 abbreviation forms and is evaluated on text containing zero instances of the character they need. See `docs/FRICTION_INVENTORY.md`. |
| M5-REAL-TYPOS | Correction accuracy on **real** Hebrew typing errors | No such corpus exists here. The true error distribution lies between corpus A (uniform) and B (adjacency) and nothing in this project knows where. This is what would settle whether the adjacency discount should be enabled. |
| M4-OTP-ACC | OTP heuristic precision and recall | No labelled corpus of OTP fields. One was not fabricated. |
| M1-KTIV | Ktiv male/haser coverage over all reform-affected lemmas | No list of affected lemmas. The 10 pairs checked are the 10 the spec names, not a sample. |
| M5-NOSUGGEST | Splitting the 12.08% no-suggestion cases into stripper false-accepts vs no-candidate | Per-case human adjudication |
| M1-TYPO | Prefix-stripper typo-rejection rate (the spec's 88.4% at MIN_STEM 4) | A typo corpus and a correctly-constructed prefix-free non-word control |
| M10-REGISTER | Prediction accuracy on **phone typing** rather than Wikipedia prose | No corpus of Hebrew phone typing exists here. The register is wrong and held-out discipline does not fix that. |
| M11-BASERATE | How often real Hebrew typing produces an error **inside** the confusion inventory | Needs a corpus of genuine human errors. Without it, 63.73% recall answers only "given the error is one this detector can express", and no precision figure can be computed from a 0.253% false-alarm rate. |
| M11-KTIV-PAIR | Whether the `ו`/`י` pair is worth including | Available as `HebrewConfusions.KTIV_MALE` and **not measured**. It is the largest source of real-word pairs and mostly not a confusion at all. |
| M12-PERSONAL-RANK | Whether ranking personal words above lexicon words is right | No corpus of personal dictionaries. Recorded as a design decision with no measurement behind it. |
| L1-REALUSER | What adaptive learning is worth to **a person**, as opposed to a block of encyclopedia sentences | No corpus of one person's typing exists here. The pseudo-user protocol is a substitute and its central limitation — a Wikipedia article is not a person — is stated in `docs/LEARNING_MEASUREMENTS.md` rather than argued away. The **direction** of the bias is UNVERIFIED. |
| L1-NOTICEABLE | Whether +0.57 points of top-1 is a difference anyone would notice | Roughly one extra correct first suggestion per 175 words. Needs real users; not simulable. |

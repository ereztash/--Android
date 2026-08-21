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

Scale: 44 production Kotlin files, 32 test files, **214 JVM tests**, 9 gate scripts,
**18 gates**, 5 positive-control fixtures. **Lint: 0 issues.**

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
| GATE-NET-2 | **Built debug APK** — permissions + DEX | 1 permission, 16 descriptors over 13 DEX files (28,527,620 bytes) | **A real assembled `netcontrol` APK** |
| GATE-NET-3 | **Built RELEASE APK** — permissions + DEX | 1 permission, **2 descriptors** over 1 DEX file (1,922,156 bytes) | The same real APK against the release baseline |
| GATE-API-1 | No IME API that compiles cleanly and fails at runtime (§1.1/1.3/1.4/1.6) | 65 files, 6 rules | Planted session-override, return-value branch, hardcoded backspace, blocking fetch |
| GATE-API-1 | `getInitial*` accessors only inside the privacy boundary (§1.2) | 65 files | Planted read outside the boundary |
| GATE-API-1 | Nothing typed reaches logcat or stdout | 65 files, production sources | Planted `Log.d` and `println` |
| GATE-CRYPTO-1 | No ECB, hardcoded IV/key, seeded `SecureRandom`, broken primitive | 65 files, 4 rules | Planted `AES/ECB`, fixed IV, hardcoded key, MD5, seeded random |
| GATE-LEX-1 | Shipped lexicon matches its manifest | 1 artifact, 355,587 forms | One byte flipped |
| GATE-LEX-2 | Upstream source integrity | 2 sources | One byte flipped before hashing |
| GATE-LEX-3 | The lexicon **inside the APK** hashes to the manifest's value | 1 asset, 4,607,433 bytes | One byte appended |
| GATE-ASSET-1 | The assets the app opens by name are the ones AGP packaged | 3 named assets, checked against the APK's own entry list | Expect a name AGP does not produce |
| GATE-MANIFEST-1 | IME service declares `exported`, `BIND_INPUT_METHOD`, the action, the meta-data | 4 requirements | `exported` flipped to false |
| GATE-R8-1 | R8 has not stripped what the system instantiates by name | 4 requirements on the minified build | Service declaration invalidated |
| GATE-LEARN-1 | The learned model persists counts over integer ids and nothing that can hold text | 2 learning source files | An encoder that accepts a `String` |
| GATE-LEARN-2 | Learning happens in exactly one place, guarded by `session.mayLearn` | 79 Kotlin source files | A second, unguarded call site |
| GATE-BIGRAM-1 | The bigram table **inside the APK** is byte-identical to the one every prediction number was measured on, and its header agrees with the manifest | 1 asset, 2,985,642 bytes, 54,133 groups | One byte appended |
| GATE-SIZE-1 | The release artifact stays inside a budget written down **after** measuring it | 3 budget entries | Assets measured 50% larger |
| GATE-XML-1 | Every XML resource parses | 15 files | A comment containing `--` |
| GATE-TRACE-1 | The benchmark measures sections the app actually emits | 2 section names | Requested sections renamed |
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
| M11-RECALL | Real-word error recall, shipped config | **62.31%** | 45,867 injected errors, test slice sha `9fc528ae…` |
| M11-FALSE | Real-word false-alarm rate on untouched text | **0.25%** | 69,494 positions, same slice — control: a permissive detector scores 15.08% |
| M11-COST | One real-word check | 3.7 µs | 20,000 calls, **JVM on the build host — NOT a device number** |
| L1-ADAPT | Adaptive next-word, shipped config, vs static on the identical split | **+0.67 top-1 (+392), +0.48 top-3 (+279)**; offer rate 88.68% → 88.78% | 58,343 positions, 120 pseudo-users, slice `d8177a78…` |
| L1-COLD | Cold start | +0.00 at 0 sentences of history, +0.14 at 10, +0.57 at 40 | same slice |
| L1-PROTECT | What the once-seen protection costs | −0.32 top-1, −0.39 top-3 vs `minimumSessions=1` | same slice — **the cheaper setting is not shipped** |
| L1-WITHHELD | Share of learned pairs that are eligible to be suggested | **5.8%** (mean 52 of 888 per pseudo-user) | 120 pseudo-users |
| L1-OOV | Share of learned pairs touching the out-of-lexicon sentinel | 8.3% | 106,545 pairs |
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
- Latency, rotation, TalkBack, Keystore and packet capture remain untested.
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

### Requires a physical Android device — still not exercised

| ID | Check | What it needs |
|---|---|---|
| M2-ENABLE-POST-M9 | That the app still installs and the IME still enables **on the current build** | A device. The observation above is against a build four milestones old. |
| M2-INSETS | The bottom key row clears the gesture bar at targetSdk 36 | A device with gesture navigation |
| M2-ROTATION | State survives rotation and other configuration changes | A device. `configChanges` is now honoured, but the view is still recreated for changes outside the declared list, and that path has never been exercised. |
| M2-SPELLCHECK | The system spell checker is actually suppressed on API 31+ | A device |
| M3-TOUCH | A touch has ever been dispatched to `KeyboardView` | A device |
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
| L1-DEBOUNCE | Whether a 3-second debounced encrypted write actually stays off the input path | A device. The interval is a judgement, not a measurement. |

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
| M5-REAL-TYPOS | Correction accuracy on **real** Hebrew typing errors | No such corpus exists here. The true error distribution lies between corpus A (uniform) and B (adjacency) and nothing in this project knows where. This is what would settle whether the adjacency discount should be enabled. |
| M4-OTP-ACC | OTP heuristic precision and recall | No labelled corpus of OTP fields. One was not fabricated. |
| M1-KTIV | Ktiv male/haser coverage over all reform-affected lemmas | No list of affected lemmas. The 10 pairs checked are the 10 the spec names, not a sample. |
| M5-NOSUGGEST | Splitting the 12.08% no-suggestion cases into stripper false-accepts vs no-candidate | Per-case human adjudication |
| M1-TYPO | Prefix-stripper typo-rejection rate (the spec's 88.4% at MIN_STEM 4) | A typo corpus and a correctly-constructed prefix-free non-word control |
| M10-REGISTER | Prediction accuracy on **phone typing** rather than Wikipedia prose | No corpus of Hebrew phone typing exists here. The register is wrong and held-out discipline does not fix that. |
| M11-BASERATE | How often real Hebrew typing produces an error **inside** the confusion inventory | Needs a corpus of genuine human errors. Without it, 62.31% recall answers only "given the error is one this detector can express", and no precision figure can be computed from a 0.25% false-alarm rate. |
| M11-KTIV-PAIR | Whether the `ו`/`י` pair is worth including | Available as `HebrewConfusions.KTIV_MALE` and **not measured**. It is the largest source of real-word pairs and mostly not a confusion at all. |
| M12-PERSONAL-RANK | Whether ranking personal words above lexicon words is right | No corpus of personal dictionaries. Recorded as a design decision with no measurement behind it. |
| L1-REALUSER | What adaptive learning is worth to **a person**, as opposed to a block of encyclopedia sentences | No corpus of one person's typing exists here. The pseudo-user protocol is a substitute and its central limitation — a Wikipedia article is not a person — is stated in `docs/LEARNING_MEASUREMENTS.md` rather than argued away. The **direction** of the bias is UNVERIFIED. |
| L1-NOTICEABLE | Whether +0.57 points of top-1 is a difference anyone would notice | Roughly one extra correct first suggestion per 175 words. Needs real users; not simulable. |

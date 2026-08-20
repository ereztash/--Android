# QA matrix

Status of every gate and check in the project. Four states only:

- **PASSED** — ran, with a stated denominator, and its positive control was demonstrated red.
- **MEASURED** — a number, with its denominator and the corpus hash it came from.
- **FAILED** — ran and did not meet its bar.
- **NOT RUN** — has not been exercised. Spelled out in full, never omitted.

**There is no "ready except for". While any row below is NOT RUN, the app is not
release-ready — and it is not described as release-ready anywhere in this repository.**

**Last updated: M8, plus the operator's minSdk 31 decision. Verdict: see [RELEASE_READINESS.md](RELEASE_READINESS.md) — NOT READY.**

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

Scale: 33 production Kotlin files, 17 test files, **125 JVM tests**, 13 gate scripts,
**14 gates**, 5 positive-control fixtures. **Lint: 0 issues.**

---

## PASSED — gates, each with its control demonstrated red

| ID | Check | Denominator | Positive control |
|---|---|---|---|
| GATE-NET-1 | No network capability — manifests | 3 manifests | Planted `INTERNET` permission |
| GATE-NET-1 | No network capability — sources | 50 Kotlin/Java files | Planted okhttp / `java.net` / WebView |
| GATE-NET-1 | No network capability — shipping deps | 113 resolved coordinates | Planted okhttp + Firebase coordinates |
| GATE-NET-2 | **Built debug APK** — permissions + DEX | 1 permission, 16 descriptors over 8 DEX files (28,339,184 bytes) | **A real assembled `netcontrol` APK** |
| GATE-NET-3 | **Built RELEASE APK** — permissions + DEX | 1 permission, **2 descriptors** over 1 DEX file (1,927,388 bytes) | The same real APK against the release baseline |
| GATE-API-1 | No IME API that compiles cleanly and fails at runtime (§1.1/1.3/1.4/1.6) | 50 files, 6 rules | Planted session-override, return-value branch, hardcoded backspace, blocking fetch |
| GATE-API-1 | `getInitial*` accessors only inside the privacy boundary (§1.2) | 50 files | Planted read outside the boundary |
| GATE-API-1 | Nothing typed reaches logcat or stdout | 50 files, production sources | Planted `Log.d` and `println` |
| GATE-CRYPTO-1 | No ECB, hardcoded IV/key, seeded `SecureRandom`, broken primitive | 50 files, 4 rules | Planted `AES/ECB`, fixed IV, hardcoded key, MD5, seeded random |
| GATE-LEX-1 | Shipped lexicon matches its manifest | 1 artifact, 355,587 forms | One byte flipped |
| GATE-LEX-2 | Upstream source integrity | 2 sources | One byte flipped before hashing |
| GATE-LEX-3 | The lexicon **inside the APK** hashes to the manifest's value | 1 asset, 4,607,433 bytes | One byte appended |
| GATE-ASSET-1 | The assets the app opens by name are the ones AGP packaged | 3 | Expect a name AGP does not produce |
| GATE-MANIFEST-1 | IME service declares `exported`, `BIND_INPUT_METHOD`, the action, the meta-data | 4 requirements | `exported` flipped to false |
| GATE-R8-1 | R8 has not stripped what the system instantiates by name | 4 requirements on the minified build | Service declaration invalidated |
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
| M3-GEOMETRY | Rows tile exactly; every key centre hit-tests to itself; RTL mirrors | 3 layouts, every key |
| M3-LAYOUT | Hebrew layout carries all 27 letters once, incl. 5 final forms | 27 letters |
| M4-PRIV-FETCH | **Initial text never fetched for a restricted field, tested with a field that DID contain text** | 9 input types × a real password string |
| M4-SWEEP | Exhaustive input-type sweep, unknown values fail closed | **4,096** variations + **16** classes |
| M5-TRIE | Trie agrees with reference Damerau-Levenshtein | exhaustive, 14 words × 22 queries |
| M5-CTRL | False auto-replace on known-correct words | 4,000 words — control: an indiscriminate replacer scores 100% |
| M6-IV | IV uniqueness across seals of identical plaintext | **2,000 seals**, all distinct |
| M6-TAMPER | Tampering with version, IV, body or tag each detected | 4 regions |
| M6-MODEL | The dictionary cannot hold anything but one Hebrew word | 9 rejection cases + reflective surface check |
| M7-A11Y-NAMES | Every key has a distinct, non-empty spoken name | 73 keys, 27 letters |
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
| M8-SIZE | Release artifact | APK 3,311,943 B; AAB 4,076,022 B | R8 cut DEX from 28,339,184 to 1,927,388 B |

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

### Requires a physical Android device — nothing here has ever run on one

| ID | Check | What it needs |
|---|---|---|
| M2-ENABLE | The IME can be enabled and selected from system settings | A device |
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

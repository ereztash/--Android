# QA matrix

Status of every gate and check in the project. Three states only:

- **PASSED** — ran, with a stated denominator, and its positive control was demonstrated red.
- **FAILED** — ran and did not meet its bar.
- **NOT RUN** — has not been exercised. Spelled out, never omitted.

There is no "ready except for". While any row is NOT RUN, the app is not release-ready.

**Last updated: M2.**

---

## Environment these results came from

| | |
|---|---|
| Host | Linux 6.18.5, x86_64, 4 vCPU, 15 GiB RAM |
| JDK (build) | OpenJDK 17.0.19 (`jvmToolchain(17)`); host default is 21.0.10 |
| Gradle | 8.14.3 (wrapper) |
| AGP | 8.13.2 |
| Kotlin | 2.2.21 |
| Android SDK | Platform 36 (`platform-36_r02`), build-tools 36.0.0 |
| Device / emulator | **NONE. No Android device or emulator exists in this environment.** |
| Compose | BOM 2026.06.01 (Compose 1.11.4) — 2026.08.00 needs AGP 9.1+ and compileSdk 37 |

---

## PASSED

| ID | Check | Denominator | Positive control | Control shown red |
|---|---|---|---|---|
| GATE-NET-1 | No network capability — source detector | 1 Kotlin file, 8 lines | `tools/positive_controls/network/` | YES — `net.client`, `net.url`, `net.webview` all fired |
| GATE-NET-1 | No network capability — deps detector | 6 resolved coordinates | same | YES — okhttp + firebase both flagged |
| GATE-API-1 | No compile-clean/runtime-fatal IME API | 1 Kotlin file, 8 lines, 4 rules | `tools/positive_controls/forbidden_api/` | YES — all 4 rules fired |
| GATE-DENOM-1 | Zero denominator never reports PASS | n/a (meta-gate) | GATE-NET-1 over an empty dir under `--strict` | YES — exits 1, status `PASS-PARTIAL`, never `PASS` |
| GATE-META-1 | A neutered control is detected as `NOT-A-GATE` | 3 gates | control file emptied by hand | YES — `run_gates.py` exited 1 and printed `NOT-A-GATE` |
| TEST-CONST | §3 restricted-field constants match the platform | 16 assertions / 14 constants | — (assertion test, not a gate) | n/a |
| TEST-GATES | The gates' own behaviour | 9 tests | — | n/a |
| VERIF-SDK | §1 / §3 claims checked against `android.jar` + `api-versions.xml` | 31 claims: 28 confirmed, 0 contradicted, 3 not-checkable | — | n/a |
| VERIF-LEX | Lexicon sources byte + sha256 exact | 2 of 2 sources | — | n/a |
| GATE-LEX-1 | Shipped lexicon matches its manifest (gzip+raw sha256, form count, sort order) | 1 artifact, 355,587 forms | `--inject-defect artifact` (one byte flipped) | YES — both sha256 findings fired |
| GATE-LEX-2 | Upstream source integrity | 2 sources | `--inject-defect checksum` | YES — `CHECKSUM_MISMATCH`, exit 1 |
| M1-REPRO | Lexicon counts reproduce the build spec | 4 counts over 241,797 + 1,167,621 input rows | `--inject-defect filter` | YES — all 4 counts −21% to −25%, `COUNT_OUT_OF_TOLERANCE` |
| M1-DETERM | Artifact is byte-identical across runs | 2 runs | `--inject-defect nondeterminism` | YES — hashes differ across `PYTHONHASHSEED` |
| M1-XCHECK | Kotlin shipped path agrees with the Python measurement | 75,000 tokens × 5 settings | — (equality test) | n/a — exact agreement at all 5 |
| M1-SORT | Lexicon blob really is byte-sorted (binary search correctness) | 355,587 entries compared | — | n/a |
| M1-ROUNDTRIP | Every index round-trips through `indexOf`/`wordAt` | 355,587 entries | — | n/a |
| GATE-NET-2 | Built APK has no network capability — merged manifest | 1 permission entry examined | **A real assembled `netcontrol` APK** carrying INTERNET + ACCESS_NETWORK_STATE | YES — both permissions flagged |
| GATE-NET-2 | Built APK has no network capability — DEX | 16 descriptors across 8 DEX files, 28,339,184 bytes | same real APK, adding `java.net.HttpURLConnection` | YES — 2 novel descriptors flagged |
| GATE-MANIFEST-1 | IME service declares exported, BIND_INPUT_METHOD, the action and the meta-data | 4 requirements | `--inject-defect service` (exported → false) | YES |
| GATE-XML-1 | Every XML resource parses | 9 XML files | `tools/positive_controls/xml/malformed.xml` | YES — named file, line and column |
| M2-CTXBUF | `InputContextBuffer` desync/recovery semantics | 12 tests | — | n/a |
| M2-BUILD | `:app` assembles (debug + netcontrol) | 2 APKs | — | n/a |

## MEASURED, reported with their denominator (not gates — measurements)

| ID | Measurement | Result | Denominator |
|---|---|---|---|
| M1-COV | Lexicon token coverage on held-out Hebrew Wikipedia, MIN_STEM=4 | **96.73%** (3.27% wrong-underline) | 75,000 tokens / 22,804 types, corpus sha256 `02fe828c…` |
| M1-COV-NONE | Same, with no prefix stripper | 94.61% (5.39% wrong-underline) | same corpus |
| M1-COV-DELTA | Coverage cost of MIN_STEM 2→4 | −0.11 points (89 tokens) | same corpus |

These are ~1 point below the build spec's figures on its own (unspecified) corpus. Reported as
measured; see `docs/LEXICON_MEASUREMENTS.md` §2 for why the gap cannot be reconciled and was
not adjusted away.

## NOT MEASURED (reported as such — not counted as passing)

None at M2. GATE-NET-1's manifest detector moved from denominator 0 to 1 when `:app` landed,
so GATE-NET-1 is now a full PASS rather than PASS-PARTIAL. Its deps detector now examines
**117** resolved coordinates, up from 6.

## NOT RUN

Everything below has not been exercised. None of it is described as working.

| ID | Check | What it needs |
|---|---|---|
| GATE-NET-2 | DEX/bytecode network scan of the built artifact | An assembled APK/AAB (M8) |
| M2-ENABLE | IME can be enabled and selected from system settings | A real device |
| M2-INSETS | Bottom key row clears the gesture bar at targetSdk 36 | A real device with gesture navigation |
| M2-CONFIGCHANGE | State survives rotation on API 30, where `configChanges` is not honoured | A real API 30 device |
| M2-SPELLCHECK | System spell checker is actually suppressed | A real device |
| M3-INPUT | Basic input actions, grapheme-correct backspace on device | A real device |
| M4-PRIV | `EditorInfo` initial-text discard on a password field that *did* contain text | M4 |
| M1-TYPO | Prefix-stripper typo-rejection / recall / false-accept rates (the spec's 88.4% at MIN_STEM 4) | A typo corpus and a correctly-constructed, prefix-free non-word control — M5 |
| M1-DEVICE | Lexicon load time, memory and lookup latency on real hardware | A real device |
| M1-KTIV | Ktiv male/haser coverage rate over all reform-affected lemmas | A list of affected lemmas, which this project does not have |
| M5-ACC | top-1 / top-3 / false_auto_replace_rate against a hash-locked golden corpus | M5 |
| M5-CTRL | Control column: already-correct words must show ~0% correction rate | M5 |
| M7-A11Y | TalkBack navigation over virtual key nodes | A real device with TalkBack |
| M7-LAT | p95 keystroke latency via `TraceSectionMetric` | A real device + host app + macrobenchmark |
| M8-SIGN | Signed release AAB | Operator-provided signing secrets (NOTICE 4) |
| M8-STORE | Store listing + Data Safety declaration | A Play Console account (NOTICE 1, 4) |

## FAILED

None yet.

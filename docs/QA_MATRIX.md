# QA matrix

Status of every gate and check in the project. Three states only:

- **PASSED** — ran, with a stated denominator, and its positive control was demonstrated red.
- **FAILED** — ran and did not meet its bar.
- **NOT RUN** — has not been exercised. Spelled out, never omitted.

There is no "ready except for". While any row is NOT RUN, the app is not release-ready.

**Last updated: M0.**

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

## NOT MEASURED (reported as such — not counted as passing)

| ID | Check | Why |
|---|---|---|
| GATE-NET-1 manifest detector | denominator = 0 | No `AndroidManifest.xml` exists yet. Becomes a real measurement at M2. Overall gate status is therefore **PASS-PARTIAL**, not PASS. |

## NOT RUN

Everything below has not been exercised. None of it is described as working.

| ID | Check | What it needs |
|---|---|---|
| GATE-NET-2 | DEX/bytecode network scan of the built artifact | An assembled APK/AAB (M8) |
| M1-REPRO | Lexicon reproducibility gate (102,239 / 298,162 / 355,587 ±1%) | M1 |
| M2-ENABLE | IME can be enabled and selected | A real device |
| M3-INPUT | Basic input actions, grapheme-correct backspace on device | A real device |
| M4-PRIV | `EditorInfo` initial-text discard on a password field that *did* contain text | M4 |
| M5-ACC | top-1 / top-3 / false_auto_replace_rate against a hash-locked golden corpus | M5 |
| M5-CTRL | Control column: already-correct words must show ~0% correction rate | M5 |
| M7-A11Y | TalkBack navigation over virtual key nodes | A real device with TalkBack |
| M7-LAT | p95 keystroke latency via `TraceSectionMetric` | A real device + host app + macrobenchmark |
| M8-SIGN | Signed release AAB | Operator-provided signing secrets (NOTICE 4) |
| M8-STORE | Store listing + Data Safety declaration | A Play Console account (NOTICE 1, 4) |

## FAILED

None yet.

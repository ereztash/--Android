# Hebrew IME for Android

A privacy-first, **fully offline** Hebrew input method for Android.

No `android.permission.INTERNET`. No HTTP client, WebView, Firebase, analytics, ads, remote
crash reporting, account, sync, LLM or backend. This is enforced by a gate that runs on every
push, not by intention — see below.

| | |
|---|---|
| Language | Kotlin, Gradle Kotlin DSL, version catalog |
| JVM target | 17 |
| minSdk | 30 ([why](docs/OPERATOR_NOTICES.md#notice-3--minsdk-30-decision-and-what-it-costs)) |
| targetSdk / compileSdk | 36 |
| UI | Custom Android Views in the IME window; Jetpack Compose **only** in settings |

## The rule this repo is built around

> A gate that has never failed has not been shown to be a gate.

Every check that reports "clean" ships with three things, or it is recorded as **NOT-MEASURED**
and never as **PASSED**:

1. **A denominator** — how many files, cases or dependencies were actually examined.
2. **A positive control** — a deliberately planted defect, committed as a fixture, that makes
   the check go red. CI runs it on every push and **fails the build if the control does not
   fail**.
3. **An explicit list of what the gate does not cover**, printed by the gate itself.

`scripts/run_gates.py` enforces this structurally: it runs each control *before* the real
check, and reports the gate as `NOT-A-GATE` if the control comes back green — no matter what
the real check said.

```
$ python3 scripts/run_gates.py
gate          result        control                denominators
GATE-NET-1    PASS-PARTIAL  control red (FAIL)     manifest=0, source=1, deps=6
GATE-API-1    PASS          control red (FAIL)     forbidden_api=1
GATE-DENOM-1  PROVEN        control red (PASS-PARTIAL)
```

`PASS-PARTIAL` above is honest, not cosmetic: the manifest detector has examined zero files so
far, so it reports NOT-MEASURED rather than clean.

## Gates

| ID | Asserts | Control |
|---|---|---|
| `GATE-NET-1` | No network capability in any manifest, source file or resolved dependency coordinate | Planted INTERNET permission, okhttp/`java.net`/WebView usage, and okhttp + Firebase coordinates |
| `GATE-API-1` | None of the four Android IME APIs that compile cleanly and fail at runtime | Planted session-interface override, `InputConnection` return-value branch, hardcoded backspace width, blocking `getTextBeforeCursor` |
| `GATE-DENOM-1` | A check that examined nothing never reports PASS | The network gate run over an empty directory |

`GATE-API-1` is worth a word. All four of these are invisible to the Kotlin compiler and to
Android Lint:

- **§1.6** overriding `onCreateInputMethodSessionInterface()` throws `LinkageError` in
  `onCreate()` at targetSdk ≥ 34 — an instant crash on launch. The method is public and
  overridable in the API 36 SDK, so nothing warns you.
- **§1.3** `IRemoteInputConnection` is `oneway`. `commitText`, `setComposingText`,
  `deleteSurroundingText`, `beginBatchEdit` and `endBatchEdit` return `true` even when the
  editor dropped the command. Every `if (!ic.commitText(...))` branch is dead code.
- **§1.4** a hardcoded backspace width of 1 splits surrogate pairs, emoji ZWJ sequences and
  Hebrew base-letter + niqqud stacks.
- **§1.1** `getTextBeforeCursor()` is a blocking Binder round-trip that can stall for up to
  2000 ms — incompatible with any sub-50 ms budget.

## Layout

```
core/     pure JVM Kotlin -- lexicon, correction, sensitive-field logic.
          No Android dependency, so it is unit-testable in CI without a device.
scripts/  build_lexicon.py and the gates
tools/    positive controls (planted defects) and the gates' own tests
docs/     VERIFICATION.md, LICENSES.md, OPERATOR_NOTICES.md, QA_MATRIX.md, milestones/
```

## Documents worth reading first

- **[docs/QA_MATRIX.md](docs/QA_MATRIX.md)** — what has actually been exercised, and what has
  not. `NOT RUN` rows are spelled out rather than omitted.
- **[docs/VERIFICATION.md](docs/VERIFICATION.md)** — the platform claims this project depends
  on, each checked against `android.jar` and `api-versions.xml` rather than trusted.
- **[docs/LICENSES.md](docs/LICENSES.md)** — why almost every Hebrew wordlist in existence is
  unusable here, and which two are not.
- **[docs/OPERATOR_NOTICES.md](docs/OPERATOR_NOTICES.md)** — decisions and actions that need a
  human.

## Build

```bash
./gradlew build              # compile + unit tests
./gradlew dependencyAudit    # refresh the coordinate list GATE-NET-1 reads
python3 scripts/run_gates.py # all gates, each preceded by its positive control
python3 tools/gate_tests/test_gates.py
```

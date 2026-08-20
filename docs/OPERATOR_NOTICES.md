# Operator notices

Actions only the operator can take. Raised at M0 so none of them sits on the critical path
later. Nothing in this file is blocking the build — the clean path is being built regardless.

---

## NOTICE 1 — URGENT, 11 DAYS: Play targetSdk 36 deadline

**Deadline 2026-08-31. Today is 2026-08-20.**

Google Play requires targetSdk 36 for new apps *and* updates from 2026-08-31. An extension to
**2026-11-01** is available by filing under **Policy status in Play Console**.

This app already targets 36, so the deadline is not a code problem. It matters because the
first upload must clear review before the date, and a Play Console account plus an app entry
must exist first — neither of which exists yet (see NOTICE 4).

**Recommended action now:** file the extension request regardless of whether you think you
need it. It costs nothing, and it converts a hard 11-day deadline into a 73-day one. If the
first upload lands earlier, the extension is simply unused.

**Status: NOT DONE — requires operator with Play Console access.**

---

## NOTICE 2 — Two licensing emails worth sending today

Both cost nothing and could each remove months of morphology work. Neither is on the critical
path; the clean-path lexicon (`docs/LICENSES.md`) is being built in parallel and does not
depend on either reply.

**2a. Hspell — closed-source licensing request.**
Nadav Har'El and Dan Kenigsberg explicitly invite closed-source licensing requests on the
Hspell homepage. Hspell is, in practice, the entire Hebrew spellchecking ecosystem: its AGPLv3
covers the *word list*, not just the code (stated verbatim in `he_IL.aff` and repeated in the
man page), which is why every derivative in `docs/LICENSES.md` is blocked. A grant here would
unblock 469,509 entries plus 3,335 prefix rules.

**2b. DICTA — commercial terms for `dicta-il/wordlist`.**
4,056,791 forms with explicit ktiv haser/male columns — the best Hebrew resource in existence,
and the only one that would directly solve the ktiv male/haser problem in §2.5. Published
CC BY-NC 4.0, so blocked as-is. Ask about commercial terms.

**Status: NOT SENT — requires operator.**

---

## NOTICE 3 — minSdk decision: RESOLVED as 31

**RESOLVED: minSdk 31**, decided by the operator after the M7 evidence below. The history is
kept rather than rewritten, because the reasoning for the original choice is still the reasoning
for not going lower.

**What minSdk 31 costs:** Android 11 devices (API 30) can no longer install the app, on top of
Android 8.0–10 (API 26–29) which minSdk 30 had already excluded.

**What it buys:** both deficiencies in the amendment below disappear. `configChanges` is
honoured, and the system spell checker is genuinely suppressed rather than silently left on.

---

### Original M0 reasoning, kept for the record

**Chosen at M0: minSdk 30.** The tradeoff was stated explicitly so it could be reversed
knowingly. It was.

Verified locally against `platforms/android-36/data/api-versions.xml` (see
`docs/VERIFICATION.md`):

| API | Since |
|---|---|
| `EditorInfo.getInitialTextBeforeCursor` | 30 |
| `EditorInfo.setInitialSurroundingText` | 30 |
| `InputConnection.getSurroundingText` | 31 |
| `EditorInfo.getInitialSurroundingText` | **31** (not 30 — the `Initial*` family splits) |

**What minSdk 30 buys.** On API 26–29 there is no `getInitialTextBeforeCursor`; the only way
to read context is the blocking `getTextBeforeCursor`, a Binder round-trip that can stall for
up to `RemoteInputConnection.MAX_WAIT_TIME_MILLIS` (2000 ms). That is irreconcilable with any
sub-50 ms input budget. Supporting 26–29 therefore means **two** context-fetch
implementations, and the one place in the app where the latency target is impossible to meet.
minSdk 30 deletes that code path entirely. The static gate `ctx.blocking_fetch` (GATE-API-1)
enforces this — `getTextBeforeCursor` is banned outright in this codebase.

**What minSdk 30 costs.** Android 8.0–10 devices (API 26–29) cannot install the app.

**Reverse this only if** the operator explicitly requires Android 8–9 support. If so, say so
and the fallback path plus its own latency measurements will be built — but the latency claim
will then have to be stated separately for API 26–29, because it will not hold there.

### AMENDED AT M7 — the evidence now favours minSdk 31

Two further API-30 deficiencies were found after M0, both measured, neither anticipated when
minSdk 30 was chosen:

1. **`InputMethodInfo.getConfigChanges()` is API 31.** So `android:configChanges` in
   `method.xml` is **not read at all on API 30**, and `onCreateInputView()` is re-called on
   every rotation, theme change, locale change and density change. The app defends against this
   by holding no state in the view, but the declared mitigation simply does not apply there.
2. **`android:suppressesSpellChecker` is API 31**, which Lint flags. On API 30 the **system
   spell checker stays active** and paints its own red squiggles over the same text this
   keyboard promised stays on-device — visibly contradicting the app's central claim, on a
   surface the app does not control.

Neither breaks the app on API 30. Both make it measurably worse there, in ways a user would
notice and could reasonably blame on this keyboard.

**Recommendation: raise minSdk to 31.** The cost is Android 11 devices. The benefit is that
both of the above stop being caveats. This is a device-coverage decision and therefore the
operator's, not one to make silently in a build.

**Status: RESOLVED. minSdk raised to 31 on operator instruction.**

### What changed in the build when minSdk moved to 31

| | Before (minSdk 30) | After (minSdk 31) |
|---|---|---|
| `android:suppressesSpellChecker` | Ignored on API 30; system spell checker stayed on | Honoured on every supported device |
| `android:configChanges` on `<input-method>` | Not read on API 30 | Honoured on every supported device |
| Lint | 1 warning (`UnusedAttribute` on `suppressesSpellChecker`) | **0 warnings** |
| `InputConnection.getSurroundingText` (API 31) | Unreachable | Reachable — and now **explicitly banned** by `GATE-API-1`, since it is the same blocking-Binder hazard as `getTextBeforeCursor`. A version bump must not quietly open a gap the gate was closing. |

The last row is the one worth noticing: raising a minSdk makes new APIs reachable, and some of
them are ones the project had deliberately designed around. The ban was extended in the same
change rather than left for someone to discover.

---

## NOTICE 4 — Release signing secrets (needed at M8, not before)

A signed release build needs an upload keystore, its passwords and a key alias. These are
secrets and will not be invented, generated silently, or committed.

At M8 the build will stop and ask for:
- upload keystore (`.jks`) + store password + key alias + key password, or
- a decision to use **Play App Signing** with a fresh upload key.

`.gitignore` already excludes `*.jks`, `*.keystore`, `keystore.properties`, `signing.properties`.

A Play Console developer account is also needed, and the app entry must exist before NOTICE 1
can be acted on.

**Status: NOT PROVIDED — will block M8 only.**

---

## NOTICE 5 — Host toolchain deviation

The build spec requires JDK 17. This build host shipped only JDK 21. JDK 17.0.19 was installed
and is pinned through a Gradle toolchain (`jvmToolchain(17)`), so compilation targets 17
regardless of the launching JVM. CI pins `temurin` 17 directly. Recorded in
`docs/QA_MATRIX.md` so no row claims a JDK that did not run it.

**Status: RESOLVED, recorded.**

---

## NOTICE 6 — No device or emulator exists in this environment

Nothing in this repo has been run on an Android device or emulator. Everything asserted so far
is a static or JVM-level measurement. Every device-dependent claim — real input latency,
on-device accessibility behaviour, IME enable/select flow, the edge-to-edge inset fix — is
recorded in `docs/QA_MATRIX.md` as **NOT RUN**, with the named device and API level it would
need. None of them are described as passing.

**Status: OPEN — structural limit of this environment, not a task that can be finished here.**

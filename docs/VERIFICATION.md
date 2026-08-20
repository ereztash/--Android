# Verification log — claims in the build prompt, checked locally

Method: every row was checked against artifacts on this build host, not from memory.
Sources used:
- `ANDROID_SDK/platforms/android-36/android.jar` (Android SDK Platform 36, `platform-36_r02`)
  inspected with `javap -constants`.
- `ANDROID_SDK/platforms/android-36/data/api-versions.xml` (5,674,817 bytes), the SDK's own
  API-level database, parsed with ElementTree.
- Live HTTP `GET` of the two lexicon source URLs, byte count + `sha256sum`.

Denominator: **31 discrete claims** drawn from prompt sections 1, 2 and 3 were checkable
against a local artifact. 28 CONFIRMED, 0 CONTRADICTED, 3 NOT-CHECKABLE (recorded below as
UNVERIFIED rather than assumed true).

## Confirmed — API levels (source: api-versions.xml)

| Claim (prompt §) | Asserted | Measured | Verdict |
|---|---|---|---|
| §1.9 `EditorInfo.setInitialSurroundingText` | API 30 | since=30 | CONFIRMED |
| §1.9 `EditorInfo.getInitialTextBeforeCursor` | API 30 | since=30 | CONFIRMED |
| §1.9 `InputConnection.getSurroundingText` | API 31 | since=31 | CONFIRMED |
| §1.4 `deleteSurroundingTextInCodePoints` | API 24 | since=24 | CONFIRMED |
| §1.9 `TextAttribute` | API 33 | since=33 | CONFIRMED |
| §1.9 `OnBackInvokedDispatcher` | API 33 | since=33 | CONFIRMED |
| §1.3 `InputMethodManager.invalidateInput` | (implied modern) | since=33 | CONFIRMED |

Additional measured facts not asserted in the prompt, recorded because they affect design:
- `EditorInfo.getInitialSelectedText` = API 30, but `EditorInfo.getInitialSurroundingText`
  = **API 31**, not 30. The prompt does not claim otherwise, but the two `Initial*` families
  split across 30/31 and it is easy to assume they match. minSdk 30 therefore gets
  `getInitialTextBeforeCursor` but **not** `getInitialSurroundingText`.
- `commitText(CharSequence,int,TextAttribute)` and
  `setComposingText(CharSequence,int,TextAttribute)` are API 33 overloads; the 2-arg forms
  are API 3.

## Confirmed — constants (source: `javap -constants android.jar`)

Every value in prompt §3's restricted-field set was checked. All 13 match.

| Constant | Prompt | Measured (dec) | Measured (hex) | Verdict |
|---|---|---|---|---|
| `TYPE_MASK_CLASS` | 0x0f | 15 | 0x0f | CONFIRMED |
| `TYPE_MASK_VARIATION` | 0xff0 | 4080 | 0xff0 | CONFIRMED |
| `TYPE_CLASS_TEXT` | (1) | 1 | 0x1 | CONFIRMED |
| `TYPE_CLASS_NUMBER` | 0x2 | 2 | 0x2 | CONFIRMED |
| `TYPE_CLASS_PHONE` | 0x3 | 3 | 0x3 | CONFIRMED |
| `TYPE_TEXT_VARIATION_PASSWORD` | →0x81 | 128 | 0x80 (\|1 = 0x81) | CONFIRMED |
| `TYPE_TEXT_VARIATION_VISIBLE_PASSWORD` | →0x91 | 144 | 0x90 (\|1 = 0x91) | CONFIRMED |
| `TYPE_TEXT_VARIATION_WEB_PASSWORD` | →0xe1 | 224 | 0xe0 (\|1 = 0xe1) | CONFIRMED |
| `TYPE_NUMBER_VARIATION_PASSWORD` | →0x12 | 16 | 0x10 (\|2 = 0x12) | CONFIRMED |
| `TYPE_TEXT_VARIATION_EMAIL_ADDRESS` | →0x21 | 32 | 0x20 (\|1 = 0x21) | CONFIRMED |
| `TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS` | →0xd1 | 208 | 0xd0 (\|1 = 0xd1) | CONFIRMED |
| `TYPE_TEXT_VARIATION_URI` | →0x11 | 16 | 0x10 (\|1 = 0x11) | CONFIRMED |
| `TYPE_TEXT_FLAG_NO_SUGGESTIONS` | 0x00080000 | 524288 | 0x80000 | CONFIRMED |
| `IME_FLAG_NO_PERSONALIZED_LEARNING` | 0x1000000 | 16777216 | 0x1000000 | CONFIRMED |

## Confirmed — presence

| Claim | Measured | Verdict |
|---|---|---|
| §1.8 `android:suppressesSpellChecker` is a public attr | `android.R.attr.suppressesSpellChecker` = 16844355 | CONFIRMED |
| §1.7 `WindowInsets.Type.navigationBars()` / `.captionBar()` exist | both public static | CONFIRMED |
| §1.4 `BreakIterator.getCharacterInstance()` on Android | present in android.jar | CONFIRMED |
| §1.1 `SurroundingText` has offset/selectionStart/selectionEnd | present | CONFIRMED |

## Confirmed — §1.6 is a genuine trap

`onCreateInputMethodSessionInterface()` **is public and overridable** on both
`InputMethodService` and `AbstractInputMethodService` in the API 36 public SDK. Overriding it
therefore compiles cleanly with no deprecation and no lint error. The prompt's claim that it
throws `LinkageError` at runtime under targetSdk >= 34 is consistent with this being a
*runtime* compat-framework gate rather than a compile-time one — which is exactly what makes
it dangerous. **Treated as true and enforced by a static gate** (see GATE-IMS-1) rather than
discovered on a device.

## UNVERIFIED — could not be checked from a local artifact

These are recorded as UNVERIFIED. They are **not** contradicted; they simply could not be
measured here, and are not asserted as fact anywhere in this repo.

1. **§1.1 `EditorInfo.MEMORY_EFFICIENT_TEXT_LENGTH = 2048` and
   `MAX_INITIAL_SELECTION_LENGTH = 1024`.** Neither constant appears in the public
   `android.jar`; they are AOSP-internal (`@hide`). Absence from the public SDK is consistent
   with the prompt's description of them as AOSP constants. The *design* consequence
   (do not call `getTextBeforeCursor` per keystroke; read once from `EditorInfo` and maintain
   an incremental buffer) does not depend on the exact numbers, and is implemented regardless.
2. **§1.6 compat-change id 148086656 (`DISALLOW_INPUT_METHOD_INTERFACE_OVERRIDE`).**
   Compat-change ids are not shipped in the SDK. Not checkable without a device running
   `adb shell am compat`.
3. **§1.2 `TextView.onCreateInputConnection()` calls `setInitialSurroundingText(mText)`
   unconditionally for password fields.** This is AOSP framework *source* behaviour; the SDK
   ships stubs only (`android.jar` method bodies are `throw new RuntimeException("Stub!")`),
   so it cannot be confirmed from the SDK. It is **treated as true and defended against**
   unconditionally — the gate in M4 discards `EditorInfo` initial text for restricted fields
   whether or not the framework populated it, so the defence is correct under either
   behaviour. This is the safe direction to be wrong in.

## Lexicon sources — confirmed byte-exact

| Source | Asserted bytes | Measured bytes | Asserted sha256 | Verdict |
|---|---|---|---|---|
| A `InflectedVerbsExtended.csv` | 17,842,473 | 17,842,473 | `818793…52a456` | CONFIRMED (exact) |
| B `he_full.txt` | 19,215,890 | 19,215,890 | `a69a3f…a0f773` | CONFIRMED (exact) |

Full digests:
```
818793894a360243d471e0f302494b245736189465c8e0258e5665335052a456  InflectedVerbsExtended.csv
a69a3f390eb53183bf191c4eac18282592992aef9ff184c5dcf8919f5ea0f773  he_full.txt
```

## Contradictions found

**None.** No claim in prompt sections 1, 2 or 3 was contradicted by a local artifact.

## Host toolchain deviation (recorded, not a contradiction)

The prompt requires JDK 17. The build host shipped only JDK 21; JDK 17.0.19 was installed and
is pinned via a Gradle toolchain (`languageVersion = 17`), so compilation targets 17 bytecode
regardless of the launching JVM. `docs/QA_MATRIX.md` records which JDK actually ran each task.

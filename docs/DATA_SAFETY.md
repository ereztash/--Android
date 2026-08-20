# Play Data Safety declaration

Answers for the Play Console Data safety form, with the reasoning for each and the evidence
behind it.

## The governing rule

Play has **no IME-specific policy**. There are zero mentions of keyboard, IME or keystrokes in
User Data, Data safety, or the sensitive-API list. What governs is this:

> User data accessed by your app that is only processed locally on the user's device and not
> sent off device does not need to be disclosed.

A genuinely offline IME may therefore declare **zero collection**. That answer is only honest
while the app really has no way to send anything, **and it becomes false the instant any SDK
with telemetry enters the build.** The declaration below is not a form filled in once; it is a
claim that GATE-NET-1, GATE-NET-2 and GATE-NET-3 exist to keep true on every push.

---

## Answers

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **No** |
| Is all of the user data collected by your app encrypted in transit? | **N/A — no data is transmitted** |
| Do you provide a way for users to request that their data be deleted? | **Yes — in-app, immediate, and it destroys the key** |

### Data types: none declared

| Category | Collected | Shared | Why |
|---|---|---|---|
| Personal info | No | No | Never read except as text in the field being typed, never retained, never transmitted |
| Financial info | No | No | Payment and numeric fields are in the restricted set: no reading, no suggestions, no retention |
| Location | No | No | No location permission is declared |
| Contacts | No | No | No contacts permission is declared |
| Messages | No | No | Message text is processed in memory to offer a correction and is never stored or sent |
| Photos / videos / audio / files | No | No | Not accessed |
| App activity / diagnostics | No | No | No analytics, no crash reporting, no telemetry of any kind |

### The one thing the app stores

The **personal dictionary**: single words the user typed into the settings screen themselves.

- Never added automatically. Typing does not teach it anything.
- Stored **encrypted with AES-GCM** under a key held in the Android Keystore.
- Never leaves the device. `allowBackup="false"` and the data-extraction rules exclude every
  domain from both cloud backup and device transfer, so it does not leave by that route either.
- **Deletable in one action**, which destroys the Keystore key as well as the file, so the
  stored bytes become unopenable by anyone including this app.

Since M12 those words also affect typing: the keyboard stops calling them misspellings and
offers them as completions. That is the point of the feature, and it changes nothing about
where the data lives — the words are read from the same encrypted file into memory, and never
sent anywhere, because the app has no way to send anything.

Under the rule quoted above this is local processing and does **not** require disclosure. It is
described here, and in the app's own settings screen, because the user should know what is
stored regardless of what a form requires.

### What is held in memory, and never written down

Prediction and real-word error detection need surrounding words. The keyboard holds **up to
three completed words** plus the one being typed, in memory, in `InputContextBuffer`. That
buffer:

- is **never persisted**, in encrypted form or otherwise — there is no code path that writes it;
- is cleared on `onFinishInput`, and dropped entirely on any cursor movement this IME did not
  make;
- is never populated at all in a restricted field, because the initial text is not read there
  and suggestions are not computed;
- has a hard cap of 2,048 characters, the length `EditorInfo` itself documents.

The distinction that matters for this form is between *processing* and *storing*. This is
processing: bytes that exist while a field is focused and are gone when it is not.

---

## The evidence behind "no data is transmitted"

Not an assertion — each row is a check that runs on every push with a positive control proven
to make it fail.

| Evidence | Denominator | Control |
|---|---|---|
| No network permission in any manifest | 3 manifests | Planted `INTERNET`, gate goes red |
| No network API in any source file | 50 Kotlin/Java files | Planted okhttp / `java.net` / WebView usage |
| No network-capable dependency in the shipping graph | 113 resolved coordinates | Planted okhttp + Firebase coordinates |
| **The built release APK declares no network permission** | 1 permission entry, which is AGP's own local one | A real assembled APK carrying `INTERNET` |
| **The built release DEX references no network class** | 2 descriptors, both `android.net.Uri` string parsing | The same real APK, adding `java.net.HttpURLConnection` |
| Nothing typed reaches logcat or stdout | 50 files, production sources | Planted `Log.d` and `println` |

The release DEX figure is worth stating precisely: after R8, the shipping artifact contains
**two** network-adjacent class references, `android.net.Uri` and `android.net.Uri$Builder`,
both of which are string-parsing utilities that open no connection. The debug build carries 16
such references, pulled in transitively by androidx and Compose; minification removes all of
them. **The artifact that ships is the cleaner one.**

## Limits of this evidence, stated

- DEX scanning proves a class is *referenced*, not that it is called. With no `INTERNET`
  permission the kernel refuses the socket regardless.
- Native code (`.so`) is not scanned. This app ships none of its own, but the check does not
  cover it.
- Reflection and dynamically assembled class names are invisible to a static scan.
- Data leaving via IPC to another app that itself has network access is not detected. The app
  has no such IPC, but the gate does not prove that.
- **None of this has been observed on a device.** No network capture has been taken, because
  there is no device in the build environment. That is recorded as NOT RUN in
  `docs/QA_MATRIX.md`, and it is the check an operator should run before publishing.

## Account deletion

Play requires a deletion route for apps with accounts. **This app has no accounts, no sign-in
and no server**, so there is nothing to delete off-device. The in-app "Delete everything"
control removes the only stored data there is.

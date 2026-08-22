# Privacy Policy — Hebrew Keyboard (Offline)

**Last updated: 2026-08-22**

This is the text Google Play requires at a hosted URL. It is written to be read by a person,
and every statement in it is one this repository can substantiate. Where a claim would be
stronger than the evidence, it has been weakened rather than the evidence stretched.

---

## The short version

This keyboard has **no internet permission**. Not "we choose not to use the network" — the
permission is not in the app, so there is no code path that could use one, and no update can
add one without it appearing in the permission list on your phone before you install it.

Nothing you type is sent anywhere, because there is nowhere for it to be sent.

---

## What this app collects about you

**Nothing.**

There is no account, no sign-in, no identifier, no analytics, no crash reporting, no
advertising, and no third-party SDK of any kind in the build.

---

## What this app stores on your phone

Three things. All of them stay on your device, and all of them can be deleted from the app's
settings screen.

### 1. Your personal dictionary

Words you type into the settings screen yourself. The keyboard never adds a word here on its
own — typing does not teach it anything.

Stored encrypted with AES-GCM under a key held in the Android Keystore, which means the key
material is managed by the operating system's hardware-backed key store rather than by this app.

**"Delete everything" in settings destroys both the words and the key.**

### 2. What the keyboard has learned about how you write

**Off by default.** You turn it on, and you can turn it off.

When it is on, the keyboard counts how often one word follows another in what you type, so that
its next-word suggestions match your habits. What is stored is **counts over numbered
dictionary entries** — for example "entry 4,812 was followed by entry 91, eleven times".

It does not store sentences. It does not store what you wrote. It does not keep a log of your
keystrokes. The stored record is four whole numbers per pair and there is nowhere in the format
for text to go.

It never learns in a password, payment, email, phone or one-time-code field, and it never
learns in a name or address field either — those still get suggestions, but nothing from them is
remembered.

Stored encrypted, under a **separate** Keystore key from your personal dictionary, so that
erasing one cannot destroy the other.

**"Forget what you learned" in settings deletes it and destroys its key.**

### 3. Diagnostic numbers

Counters describing how the keyboard behaved on your phone: whether the dictionary loaded, how
many suggestions were requested, keystroke timing percentiles, whether key labels fitted their
keys, the contrast ratio of the colours your phone resolved.

These exist so that if something is wrong you can see why, and send a report if you choose to.

They are **numbers**. They contain no text you typed, no name of any app you typed into, and no
identity of any field. Individual keystroke timings are never written to storage at all — only
percentiles — because a sequence of keystroke intervals is a typing rhythm, and a typing rhythm
can identify a person.

**"Clear what was measured" in settings erases them.**

---

## Sensitive fields

In **password, payment, email, phone number and one-time-code** fields, the keyboard switches
itself off as a suggestion engine entirely. It does not read the surrounding text, does not
offer suggestions, does not autocorrect, and does not learn. It types the characters and nothing
else.

Android hands a keyboard the text already in a field when the field opens. In a restricted field
this app never reads it, and additionally overwrites the copy the system handed over, so it does
not stay in memory for as long as that field is open.

**Name and address fields** are treated in between: you still get suggestions there, but nothing
from them is ever remembered.

---

## What this app never does

- It never sends anything over a network. It cannot.
- It never shows advertising.
- It never shares anything with any third party, because it has nothing to share it with.
- It never replaces a word you typed on its own. Every change to your text is a tap you made.

---

## Children

This app collects no data from anyone, of any age.

---

## Permissions

The app requests **no runtime permissions at all**.

The permission list on the Play listing shows one entry,
`com.hebrewime.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. That is added automatically by the
Android build tools to every app; it is defined by this app for its own use, grants nothing, and
gives no access off your device.

---

## How to delete everything

Open the keyboard's settings — long-press the globe key, or find "Hebrew Keyboard" in your
phone's keyboard settings — and use:

- **Delete everything** — removes your personal dictionary and destroys its key.
- **Forget what you learned** — removes the adaptive model and destroys its key.
- **Clear what was measured** — removes the diagnostic counters.

Uninstalling the app removes all three, and the Android Keystore destroys the keys with the
package.

---

## Contact

*The operator must fill in a contact address here before publishing. Play requires one, and it
is not invented in this repository.*

---

## The evidence behind "it cannot send anything"

This is unusual to put in a privacy policy, and it is here because the claim is unusual: most
privacy policies describe an intention, and this one describes a property of the artifact.

- The app declares no `INTERNET` permission. Android refuses a socket to an app without it.
- Three automated checks run on every change: one over the manifest and every source file, one
  over the built debug APK, one over the built **release** APK. Each scans for the permission
  and for any reference to a networking class.
- Each of those checks ships with a deliberately broken build — a real APK carrying a real
  `INTERNET` permission and real networking code — and the check must be demonstrated to
  **fail** on it before its pass on the real app is accepted.

**The limit of that evidence, stated:** it is static analysis of source and of the built
artifact. No packet capture has been run on a phone. See `docs/QA_MATRIX.md`, row
`M8-NETCAPTURE`, which is open.

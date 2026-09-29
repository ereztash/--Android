# Public Release Readiness

Status: **PUBLIC SOURCE / OSS LICENSE NOT YET VERIFIED**

The repository is public. A root open-source license was not found during the 2026-09-29 portfolio audit. Because the project ships language assets and derived corpora, release readiness depends on both software licensing and data provenance.

## Public value

The strongest contribution is not merely a Hebrew keyboard. It is an engineering method for shipping language features under explicit falsification rules:

- every gate must demonstrate it can fail;
- a claim may not be broader than the measurement that produced it;
- thresholds are frozen before the result when possible;
- failed experiments remain part of the record;
- a shipped feature can be withdrawn after blind human labeling;
- NOT MEASURED and UNVERIFIED remain first-class states.

The keyboard itself is also useful as a local-only Hebrew IME reference: no internet permission, deterministic correction paths, Hebrew direction handling, and explicit privacy boundaries.

## Evidence boundary

Several headline measurements come from injected errors, encyclopedic prose, subtitles, or typed web comments rather than real phone-message text. The README already preserves these distinctions; public summaries must do the same.

## Before OSS spotlight

1. **Software license** — choose and add a root license.
2. **Asset provenance** — document the licenses and transformation path for lexicons, Wikipedia-derived material, subtitles, typed-comment corpora, and any packaged language asset.
3. **Clean build path** — document the minimum Android Studio / Gradle steps for a stranger.
4. **Contribution boundary** — distinguish product changes from experiments that require preregistered gates.

## Public one-liner

> A fully on-device Hebrew Android keyboard built under a rule that a gate does not count until it has been shown failing on a planted defect.

## Do not claim

- production readiness;
- phone-message accuracy from proxy corpora;
- that a green gate is sufficient evidence if its positive control has not failed;
- that removed experiments "almost worked."

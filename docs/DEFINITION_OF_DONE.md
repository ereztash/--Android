# Definition of done

`docs/RELEASE_READINESS.md` gives the verdict. `docs/PATH_TO_PRODUCTION.md` gives the list.
**This file gives the rule that decides when a box may be ticked**, so that "done" stops being
a judgement made freshly each time by whoever is looking.

It exists for the same reason `PATH_TO_PRODUCTION.md` does: *"production ready" was being
carried as a mood.* That file replaced the mood with a list. A list still needs someone to
decide when an item on it is finished, and that decision was the last thing here still being
made from memory.

**Nothing in this file changes any verdict.** It says what would.

---

## The verdict this file publishes

<!-- DOD-COUNTS-BEGIN: derived from the tier tables below, checked by GATE-DOD-4 -->

| Tier | Criteria | MET | NOT MET | NOT RUN | WAIVED | Verdict |
|---|---|---|---|---|---|---|
| C — a change may be committed | 11 | 9 | 0 | 0 | 2 | **MET** |
| R — a build may be handed to a human | 7 | 1 | 0 | 6 | 0 | **NOT MET** |
| P — the app may be published | 9 | 3 | 0 | 6 | 0 | **NOT MET** |

<!-- DOD-COUNTS-END -->

A tier is **MET** only when no criterion in it reads NOT MET or NOT RUN. Waivers are counted in
the open, never subtracted from the denominator, and never allowed to be silent.

---

## The seven rules that make this a definition and not a wish

These are the answer to *what is right to define as done here*. The criteria below are an
application of them; if the two ever disagree, the rules win and the criteria are wrong.

**1. Three tiers, because "ready" is three different questions.**
A change being finished, a build being fit to give a human, and an app being fit to publish
have different owners, different evidence and different failure modes. Collapsing them is what
produces "it works on my phone" standing in for "device coverage is shrinking" — two claims
that were both live in this project at once, and only one of which was true.

**2. Four states, the QA matrix's four: MET, NOT MET, NOT RUN, WAIVED.**
No percentages, no "mostly", no "ready except for". `NOT RUN` is not `MET`, exactly as
`NOT-MEASURED` is not `PASS`. This is the same rule `gatelib.py` enforces on every detector,
applied one level up.

**3. Every criterion names the artifact that settles it** — a gate id, a QA matrix row id, or a
file. A criterion with nothing to cite cannot be checked by anyone but its author, on the day
they wrote it. `GATE-DOD-1` fails the build on a criterion that cites nothing, and `GATE-DOD-2`
fails it on one that cites something no longer there.

**4. Every criterion must be able to fail.**
*A gate that has never failed has not been shown to be a gate* applies to done-criteria too. A
criterion no state of this repository could violate is deleted rather than kept for comfort. It
is why criteria here read "0 lint errors" and not "code quality is good".

**5. A waiver is a signature; silence is drift.**
Shipping without evidence is allowed. Shipping without *saying* that is not. A waiver carries a
date, an owner and a reason, and `GATE-DOD-1` fails the build on a WAIVED criterion whose
waiver does not exist. The blocked-check count in this project went 20 → 22 while features
shipped; a waiver is how that becomes a decision instead of a drift.

**6. The criteria list may grow, and growing is not failure.**
`MI-HAPTIC` was not in the QA matrix at all until a device session found it; `MI-LABELFIT`
arrived from measuring a screenshot. A done-list that can only shrink is describing the plan,
not the app. Additions are expected at every device session.

**7. Nothing goes in here that this project cannot settle.**
An unmeetable criterion is not rigour; it is how a definition of done becomes something people
route around. Real Hebrew typing errors, phone-register prediction, the confusion base rate and
what learning is worth to a person are **open questions with owners**, listed at the bottom and
deliberately not criteria. They gate no release, and no release claims them answered.

---

## Tier C — a change may be committed

Owner: whoever makes the change. Checked on every push, by CI.

<!-- DOD-TIER-BEGIN: C -->

| ID | Criterion | State | Evidence |
|---|---|---|---|
| C1 | The `:core` suite runs and passes, and the suite is proven non-empty — a task that runs zero tests is a zero denominator, not a green tick | MET | `scripts/assert_tests_ran.py`, `.github/workflows/ci.yml` |
| C2 | Debug, release and `netcontrol` APKs assemble, and the latency harness still compiles even though it cannot run | MET | `.github/workflows/ci.yml` |
| C3 | Android lint reports 0 errors | MET | `.github/workflows/ci.yml` |
| C4 | Every gate is PASS **with its own control demonstrated red in the same run**, or NOT-MEASURED against a named absent input | MET | `scripts/run_gates.py`, `GATE-STORE-1`, `GATE-META-1`, `GATE-DENOM-1` |
| C5 | The shipping artifact still carries no network capability — manifests, sources, resolved coordinates, and the DEX of the APK that ships | MET | `GATE-NET-1`, `GATE-NET-2`, `GATE-NET-3` |
| C6 | No IME API that compiles cleanly and fails at runtime; no ECB, fixed IV, hardcoded key or seeded `SecureRandom` | MET | `GATE-API-1`, `GATE-CRYPTO-1` |
| C7 | The release artifact stays inside a size budget written down **after** measuring it | MET | `GATE-SIZE-1` |
| C8 | The readiness documents do not contradict each other, themselves, or the denominators the gates actually counted | MET | `GATE-DOC-1`, `GATE-DOC-2`, `GATE-DOC-3` |
| C9 | This file's own criteria cite evidence that exists, and none of them claims MET over evidence the matrix says has not run | MET | `GATE-DOD-1`, `GATE-DOD-2`, `GATE-DOD-3`, `GATE-DOD-4` |
| C10 | No behaviour ships that is absent from **both** the gate suite and the QA matrix | WAIVED | `WAIVER-1`, `GATE-DOC-1` |
| C11 | Every number published in a document carries its denominator, and the hash of the corpus that produced it | WAIVED | `WAIVER-2`, `GATE-DOC-2` |

<!-- DOD-TIER-END: C -->

### C4 was NOT MET, and this file found it on its first run

`GATE-STORE-1` has been neither PASS nor NOT-MEASURED on any CI run since the commit that
introduced it. `scripts/build_store_assets.py` imports Pillow at module level and
`.github/workflows/ci.yml` never installs it, so on a runner the script raises
`ModuleNotFoundError` before it can compare a single pixel. With Pillow present it passes on
this tree in full, which is what makes this a workflow defect rather than an asset one.

Two things follow, and the second is worse than the first:

- **The gate job had been red on the base branch for four commits** and this was the reason.
  Nothing in this change caused it. It is now fixed here, on the operator's instruction: the
  gates job installs `pillow==12.3.0` before running anything. The version is pinned because
  `GATE-STORE-1`'s subject is a byte comparison against committed PNGs, which makes the
  renderer part of the measurement.
- **The crash was being read as a red control.** `run_gates.py` records `GATE-STORE-1` as
  `control red (exit=1)` — but the control exited 1 because the interpreter could not import
  a module, not because a planted defect was caught. A control that *cannot run* proves
  nothing, and this repository already knows that: it is why `NOT-MEASURED` exists and why
  `GATE-LEX-2` is reported distinctly from `NOT-A-GATE`. **That distinction is still not being
  made when a gate script dies on import, and installing Pillow does not make it** — it only
  removes the one case that exposed it. Open, and not fixed here.

C4 read **NOT MET** from the moment the failure was diagnosed until a gates run came back
green — not from the moment the fix was pushed. It is **MET** now on that run's evidence: CI
runs 66 and 67 on `f88fbc3`, both green, the first green gates run on this project since
`568c317`. `GATE-STORE-1` went from `exit=1` with a control that had crashed, to `exit=0` with
its control demonstrated red, and the "Test the gates themselves" step ran for the first time
in five commits — it had been skipped every time, because the step before it failed.

The gap between the fix and the state change is the point. A criterion that flips when someone
pushes what they believe is a fix is a criterion that records intentions; this one records
runs.


---

## Tier R — a build may be handed to a human tester

Owner: the operator, on their own phone; the builder records what comes back. This tier is
about **one artifact**, not about the repository: it is re-established for every candidate, and
a row here reading NOT RUN while the matrix records an older build as OBSERVED is correct, not
stale.

<!-- DOD-TIER-BEGIN: R -->

| ID | Criterion | State | Evidence |
|---|---|---|---|
| R1 | Tier C is met on the exact commit this artifact was built from, and that commit is recorded beside the artifact | NOT RUN | `.github/workflows/ci.yml`, `scripts/run_gates.py` |
| R2 | The artifact is signed with a key the operator supplied | NOT RUN | `M8-SIGN` |
| R3 | This build installs on a physical phone, enables as an IME, and types | NOT RUN | `M2-INSTALL`, `M2-ENABLE`, `M9-LAYOUT` |
| R4 | The device self-check has been run **on this build** and its report pasted into the matrix whole — including every check that stayed green under injection and therefore counts as evidence for nothing | NOT RUN | `M2-INSETS`, `M8-NETPERM`, `M4-DEVICE`, `M6-KEYSTORE`, `M7-CONTRAST` |
| R5 | p95 keystroke latency measured on that phone, with the device model written beside the number | NOT RUN | `M7-LAT` |
| R6 | Every check that needs a person looking has been attempted and its outcome recorded — including "looked, could not tell" | NOT RUN | `M6-UI`, `MI-PREVIEW`, `MI-REPEAT`, `MI-LONGPRESS`, `MI-CONFIRM`, `L1-SWITCH`, `R1-FEEL` |
| R7 | No row anywhere in the QA matrix reads FAILED | MET | `docs/QA_MATRIX.md` |

<!-- DOD-TIER-END: R -->

---

## Tier P — the app may be published

Owner: the operator. Every row here is theirs to tick, and none of them can be ticked from this
repository.

<!-- DOD-TIER-BEGIN: P -->

| ID | Criterion | State | Evidence |
|---|---|---|---|
| P1 | Tier R is met on this exact artifact | NOT RUN | `docs/DEFINITION_OF_DONE.md` |
| P2 | Every device-blocked row is OBSERVED, MEASURED, or WAIVED with a date, an owner and a reason | NOT RUN | `GATE-DOC-1`, `GATE-DOD-3` |
| P3 | The keyboard is usable with TalkBack on | NOT RUN | `M7-TALKBACK` |
| P4 | Either a packet capture confirms no traffic, or every published no-network claim says in those words that no capture was ever run | NOT RUN | `M8-NETCAPTURE`, `docs/PRIVACY_POLICY.md` |
| P5 | Listing, Data Safety, content rating, screenshots and a hosted privacy-policy URL are submitted | NOT RUN | `M8-STORE`, `M8-ASSETS`, `docs/STORE_LISTING.md`, `docs/DATA_SAFETY.md` |
| P6 | The targetSdk extension is filed before 2026-08-31 | NOT RUN | `docs/OPERATOR_NOTICES.md` |
| P7 | **No shipped feature sits below its own pre-registered stopping rule without a dated operator override** | MET | `GATE-WITHDRAWN-1`, `docs/LABELING_LOG.md` |
| P8 | No number in user-facing text is wider than the measurement behind it — the listing quotes the human-labelled precision bounds, not the injected-error recall | MET | `docs/STORE_LISTING.md`, `app/src/main/res/values/strings.xml`, `docs/LABELING_LOG.md` |
| P9 | The attribution the lexicon licences require is present in the shipped app | MET | `docs/LICENSES.md` |

<!-- DOD-TIER-END: P -->

### P8 is MET, and what that is and is not

The drafted listing and the app's first screen now say what the real-word check is worth before
a user meets it: the listing quotes **[12.5%, 39.7%]** — both bounds, from 320 real firings
judged blind — and the first screen says *wrong more often than it is right*, a phrasing chosen
because it holds at either end of that interval and does not go stale when the layer is
measured again. Neither quotes the 63.73% injected-error recall, which is the number that would
read as a quality claim and is not one.

**What P8 covers is the text in this repository.** What the operator eventually pastes into Play
Console is theirs, and this criterion cannot reach it. Writing the honest version here first is
the only thing that can be done about that from this side, and it is what MET means on this row.

### P7 was the one criterion failing. It was decided on 2026-08-25, and the decision was to withdraw.

**The adjacent real-word error layer no longer ships.**

Its precision, measured on 320 real firings labelled blind by a Hebrew speaker, is bounded at
**[12.5%, 39.7%]**. The rule registered *before* those labels existed calls a detector below
**40%** withdrawable, and **the ceiling does not reach the floor of the rule** — margin,
letter-pair and frequency restrictions were each swept and none rescues it. The evidence
advantage is flat across bands (16.0 / 12.5 / 16.7), so the detector's own confidence signal
does not separate its right answers from its wrong ones.

Three things decided it, and none of them is that the feature "failed":

1. **The rule was registered before the data.** Renegotiating it after the labels arrived is
   the exact thing pre-registration exists to prevent, and it would have been the first time in
   this repository that a pre-registered rule was renegotiated by the party it constrained.
2. **4.83 false alarms per true positive is not a neutral cost.** A strip that is wrong five
   times in six teaches a user to stop reading the strip — including when it is right about
   something else. The harm is not contained to the layer that causes it.
3. **`W1` measured it getting worse where it counts.** Its false-alarm rate on *correct* text is
   0.19% on transcribed dialogue and **0.33% on typed Hebrew** — the register a person actually
   types in. See [`docs/TYPED_REGISTER.md`](TYPED_REGISTER.md).

And one consistency argument that should have forced this earlier: **the distance-2 layer was
withdrawn on exactly this kind of evidence and the adjacent layer was not.** That inconsistency
is what this closes.

**What withdrawal means here, precisely.** `RealWordErrorDetector` is no longer constructed in
the shipped path; `PredictiveEngine.realWordErrors` stays at its default of `null` and the engine
returns early. The class, its tests and every measurement **stay** — `:core` still constructs it,
every sweep still reproduces, and nothing about the finding becomes unverifiable. What changed is
that it no longer runs for a user.

**A withdrawal that lives as an absence is the easiest thing in a codebase to undo by accident**,
so `GATE-WITHDRAWN-1` asserts the silence, with its control demonstrated red by wiring the
detector back in. P7 reads MET on that gate, not on this paragraph.

Waiving was available and was not taken. It remains available: re-enabling this layer is a
legitimate decision, and it belongs here with a date and an owner rather than in a diff.

---

## Waivers

A criterion may be waived. It may not be waived silently, and a waiver with no entry here fails
`GATE-DOD-1`.

<!-- DOD-WAIVERS-BEGIN -->

| ID | Waives | Date | Owner | Reason, and the compensating control |
|---|---|---|---|---|
| WAIVER-1 | C10 | 2026-08-25 | builder | Nothing can see a check that is in **neither** table — `GATE-DOC-1` compares two lists and an absent row cannot contradict anything. `MI-HAPTIC` shipped and was missing from the matrix entirely until a device session found it. Compensating control: the device session itself, which is what has found every such gap so far. Re-examined at each session; the waiver ends when a check can enumerate shipped behaviour independently of the two tables. |
| WAIVER-2 | C11 | 2026-08-25 | builder | `GATE-DOC-2` reads back five published denominators plus its own; every other number in every document is prose, and nothing here reads sentences. Compensating control: each measurement document records the corpus hash beside the number, by hand. The waiver ends when a check derives published numbers from the runs that produced them. |

<!-- DOD-WAIVERS-END -->

---

## Deliberately **not** criteria, and why

Every item below would improve the app and none of them can be settled with anything this
project has. They are named here so that "not in the definition of done" is a recorded decision
rather than an omission — and so that no release is described as having answered them.

| Open question | Matrix row | Why it is not a criterion |
|---|---|---|
| Correction accuracy on **real** Hebrew typing errors | `M5-REAL-TYPOS` | No such corpus exists here. This is what would settle the adjacency discount. |
| Prediction on **phone typing** rather than encyclopedic prose | `M10-REGISTER` | The register is wrong and held-out discipline does not fix it. |
| How often real typing produces an error the detector can even express | `M11-BASERATE` | Without it, recall answers a narrower question than a user asks. |
| What adaptive learning is worth to **a person** | `L1-REALUSER` | A Wikipedia article is not a person, and the direction of that bias is UNVERIFIED. |
| Whether +0.67 points of top-1 is noticeable | `L1-NOTICEABLE` | One extra correct suggestion per 149 words. Needs real users. |
| OTP-field heuristic precision and recall | `M4-OTP-ACC` | No labelled corpus. One was not fabricated. |
| Whether personal words belong above lexicon words | `M12-PERSONAL-RANK` | A design decision with no measurement behind it, recorded as one. |

Making any of these a criterion would freeze the release behind evidence nobody can produce,
and a definition of done that cannot be met is one that gets ignored — which costs more than
the honesty it appears to buy.

---

## What this file does NOT do

- It does not decide whether the app is good. Every criterion is about **evidence existing**,
  not about the app being pleasant to type on. `R1-FEEL` is the closest thing to the latter and
  it is one row of one tier.
- It cannot see a criterion that is missing. Rule 6 exists because the list grows on contact
  with a device, and no gate can flag an absence.
- It cannot see a criterion stale in the *pessimistic* direction — one reading NOT RUN whose
  evidence has since come back. That direction went undetected here for four milestones, and it
  is not caught by any check in this file: Tier R rows legitimately read NOT RUN for the current
  artifact while the matrix records an older build as OBSERVED, so the two cannot be compared
  automatically without producing false alarms.
- It proves nothing about the app. Like `GATE-DOC-1`, it compares documents to documents; it
  checks consistency, never truth.

# M1 — what this is worth, and to whom

**Question, put by the operator on 2026-08-25:** *what should be done to turn this into
something worth as much money as possible?*

Three research passes were run in parallel: the regulated/enterprise buyer, the resale value of
the linguistic assets, and the Hebrew consumer keyboard market. This file records what they
found, including — especially — the findings that contradict what this repository has been
built on. Nothing here is a plan. It is a register, with sources, of what the evidence says.

Every claim below is either sourced or explicitly marked as unverified. Where a source could
not be opened, that is stated rather than smoothed over.

---

## The finding that matters most, because it lands on our own thesis

**In 2,700 Hebrew-market reviews of Gboard and SwiftKey, ktiv male / ktiv haser
(כתיב מלא / כתיב חסר) is mentioned zero times.** Privacy is mentioned 4 times (0.15%).

Review corpus: 1,500 Gboard + 1,200 SwiftKey, pulled newest-first from Google Play's public
review endpoint with `hl=iw&gl=IL` on 2026-08-25.

This repository has spent its measurement effort on morphology and on the `ו`/`י` alternation.
`docs/FRICTION_INVENTORY.md` measures 50.78% of conversational tokens as one `ו`/`י` deletion
from another real word — a genuine property of the language. **It is not a complaint users
make.** A real linguistic property and a felt friction are not the same thing, and this
repository has been treating them as the same thing.

### What Hebrew users *do* report, verbatim and reproducible

| friction | evidence | affects |
|---|---|---|
| **bidi bracket inversion** — `()` come out reversed | 9 mentions. Gboard 4★: *"מקשי ה() (סוגריים) נכתבים הפוך בעברית. נא לתקן"*. SwiftKey 2★: *"בעקבות עדכונים אחרונים זה הפך להיות בלתי אפשרי לשימוש בעברית, מכיוון שהסוגריים תמיד יוצאים הפוכים"*. SwiftKey 1★ (EN): *"It has issues with RTL languages and () signs in some apps which placed in opposite direction inside the field."* | **both Gboard and SwiftKey** |
| **Hebrew + Latin + digits scrambles order** | SwiftKey 2★: *"הקלדה שמשלבת מילים בשפה העברית וגם באנגלית עם מספרים משבשת את סדר והרצף של מה שאני כותב."* | SwiftKey |
| **weak Hebrew autocorrect** | SwiftKey 1★: *"יכולת של הסוויפטקי מבחינת ai לתיקון שגיאות בעברית היא גרועה ביותר"*. Gboard 3★: *"הוא קצת לא מבין מה אני כותבת בעברית"* | both |
| **no nikud in SwiftKey** | 9 mentions, all SwiftKey-negative / Gboard-positive | SwiftKey |
| **Samsung Hebrew/Arabic lag** | thread title *"Major lags and slowness in Samsung keyboard - Hebrew and Arabic language"*, ≥4 pages — <https://eu.community.samsung.com/t5/samsung-lounge/major-lags-and-slowness-in-samsung-keyboard-hebrew-and-arabic/td-p/13401444> **(Cloudflare 403 — title and page count from search indexing only; the sentiment inside is unverified)** | Samsung |

Published, title verified, body JS-rendered and unread: Google's own Android Community carries
*"Auto-correction and Glide Typing not working for Hebrew in Gboard"* —
<https://support.google.com/android/thread/359841847/>.

**The convergence.** `docs/FRICTION_INVENTORY.md` records script-mixing and geresh/gershayim at
**0.00%** on both corpora, and states plainly that this is a hole in the evidence rather than a
finding: both corpora keep only `[א-ת]+` runs, so Latin characters, digits and punctuation were
removed before counting. The top verified user complaint in the market lives **exactly** in the
characters our corpora delete. The blindness was measured before the market pass ran, and the
market pass found the complaint independently. That is the strongest signal in this document.

**Calibration, against ourselves:** newest-first star distribution is Gboard 81% 5★ / 5% 1★,
SwiftKey 81% 5★ / 4% 1★. Israeli users are not in revolt. These are a real long tail, not a
market opening by themselves.

---

## Path A — a consumer Hebrew keyboard. No precedent, at any scale.

**Israel base:** Android 67.5% of mobile OS, vendor split Samsung 46.9% / Apple 32.5% / Xiaomi
12.1% (<https://gs.statcounter.com/os-market-share/mobile/israel>,
<https://gs.statcounter.com/vendor-market-share/mobile/israel>); 10.4M cellular connections,
8.61M internet users (<https://datareportal.com/reports/digital-2025-israel>). That is roughly
**5M Android users, about half on a Samsung default IME that cannot be replaced from Play**.
*(The 5M is arithmetic from the two sources, not a sourced figure.)*

**The Hebrew-specific category on Play is ad-farm reskins.** The largest, at 100K+ installs,
draws: *"Scam advertising app. Constant ads with no way to close or exit… there is no working
Hebrew keyboard"*. **On iOS the entire Hebrew-specific niche is 15–28 ratings per app** — Nikud
Keyboard (paid $1.99) has 21 ratings total. That is the observed size of the Hebrew paying
niche.

**What keyboards have earned, with numbers:**

| model | case | outcome |
|---|---|---|
| paid app | SwiftKey pre-2014 | **$17.5M** revenue in its last paid year, 2nd best-selling app on Play; went free June 2014, revenue **fell to £8.4M** |
| exit | SwiftKey → Microsoft 2016 | **$250M** on ~300M installs — global English scale |
| exit | Swype → Nuance 2011 | **$102.5M**; Nuance **discontinued Swype in 2018** |
| ads | CooTek/TouchPal | $37M (2017) → $442M (2020) → **$30M (2024)**, **NYSE-delisted 2022**. The keyboard was **~2% of ad revenue**; 81% came from unrelated fiction/game apps |
| ads (fraud) | Kika Tech | removed by Google for click-injection fraud, Nov 2018 |
| language-specific | **Bobble AI** (India, 120 Indic languages, Xiaomi + Krafton, **$51.77M raised**) | **₹27.4 Cr (~$3.3M) FY25, −32.6% YoY**, loss ₹60.88 Cr, latest round **~30% down** |
| assistant that ships a keyboard | Grammarly | $650M ARR — **not a keyboard business**; the keyboard is a distribution surface |

Sources: <https://techcrunch.com/2014/06/11/swiftkey-goes-freemium/> ·
<https://www.theregister.com/2016/02/03/microsoft_buys_swiftkey/> ·
<https://www.androidauthority.com/swype-keyboard-discontinued-838994/> ·
<https://www.macrotrends.net/stocks/charts/CTK/cootek-cayman/revenue> ·
<https://ir.cootek.com/2019-11-18-CooTek-Announces-Third-Quarter-2019-Unaudited-Results> ·
<https://www.buzzfeednews.com/article/craigsilverman/google-removes-cheetah-kika-apps> ·
<https://entrackr.com/exclusive/exclusive-bobble-ai-to-raise-fresh-funds-at-30-valuation-dip-9627501>

**Verdict: no third-party keyboard has ever earned meaningful standalone revenue from a single
small language market.** The best-funded language-specific keyboard on earth loses money in a
market ~100× Israel's.

**Israeli precedents, both dead:** *ai.type* (Tel Aviv, 40M+ downloads) leaked **31,293,959 user
records** from an unsecured MongoDB in Dec 2017
(<https://www.infosecurity-magazine.com/news/israeli-startup-leaks-data-on-31m/>). *SlideIT*
(Dasur) — 7M+ downloads, Google editor's choice, unpublished from Play 2024-04-29.

---

## Path B — sell the lexicon and the labelled sets. Priced in hundreds.

The LDC catalogue is the only public price list for this asset class:

| corpus | what it is | non-member |
|---|---|---|
| CELEX2 (LDC96L14) | the canonical morphological lexicon | **$300** |
| Mawukakan / Maninkakan lexicons | low-resource language lexicons | **$800** each |
| Penn Treebank-3 (LDC99T42) | the most-cited annotated corpus in NLP history | **$1,700** |

Annotation replacement cost runs $0.018–$0.04 per label
(<https://labelyourdata.com/pricing>). At a generous $2–5 per screen for expert Hebrew
judgement, **the 465-screen blind-labelled set replaces for roughly $1,000–$2,500** and prices
as a licensable artifact in the CELEX2 band.

**Two pricing levers are already gone.** CC BY-SA 4.0's ShareAlike means a buyer **cannot** take
a derived lexicon proprietary — no exclusivity. The pipeline is reproducible from public dumps —
no scarcity. Every large data-licensing deal (Reddit/Google **$60M/yr**, News Corp ~$50M/yr,
NYT/Amazon $20–25M/yr) prices exactly the thing we do not have: exclusivity over a corpus the
buyer cannot obtain elsewhere. **Provenance hygiene here is a legal shield, not a pricing
lever.**

**And the floor is state-funded at zero.** Israel adopted, via Resolution 212, **180M NIS over
3 years** to build Hebrew/Arabic corpora and models and open-source them
(<https://oecd.ai/en/dashboards/policy-initiatives/investment-in-localized-nlp-infrastructure-8644>).
Dicta-LM 1.7B — an on-device-capable Hebrew LLM — is free under CC BY-SA 4.0. IAHLT's NER
corpus and UD treebank are CC BY 4.0. Hspell/HebMorph is a free Hebrew morphological analyser.
Money is now flowing to **compute**, not lexicons: Nebius won a **$140M** national supercomputer
mandate with **$45M** from the Innovation Authority, explicitly to train Hebrew and Arabic
models.

**In no keyboard acquisition did anyone pay separately for a lexicon.** SwiftKey ($250M) bought
installs and team. Swype ($102.5M) bought product and team. **Fleksy is the cautionary case: the
team and the IP were sold *separately*** — Pinterest took the team in 2016 and explicitly did
**not** acquire the technology; the orphaned IP went to ThingThing, which spent six years
building an SDK licensing business and reached a **$1.6M Series A** on ~$3M total raised. That
is the realistic ceiling for keyboard IP sold as an asset.

---

## Path C — keyboard SDK licensing. A graveyard, including the Israeli one.

- **Fleksy / ThingThing** — SDK from $269/mo, usage-based from $0.0009/MAU. **Stopped SDK
  development ~2025 and shut down its website in June 2026**
  (<https://keyboardkit.com/blog/2026/06/08/fleksy-shuts-down-their-website>).
- **PayKey** — Israeli, Tel Aviv, the canonical "sell a keyboard SDK to banks" company. Raised
  **$16M**; named customers Westpac, UOB, Garanti, SpareBank 1, Davivienda, **Bank Leumi**.
  **paykey.com today sells earned-wage-access; the keyboard is gone from the homepage.**
- **Nuance/Swype** — the OEM model that actually worked at scale, killed by its owner in 2018.
- The one live public price point is **KeyboardKit Pro: $50–$500/mo per app** — solo-developer
  economics.

No published enterprise deal value exists for any keyboard SDK contract.

---

## Path D — the regulated buyer. Real regulation, no line item.

The rules exist and they are specific: **DISA STIG V-258403** and the **Intune Data Protection
Framework Level 3** both mandate restricting third-party keyboards. **The remedy they prescribe
is to ban all third-party keyboards, not to procure a vetted one.** There is no line item, no
tender, and no case study of anyone buying an approved keyboard to satisfy them. iOS enterprise
is structurally closed — there is no allowlist mechanism to be on.

Corrections to assumptions this repository was carrying:
- **Amendment 13 to Israel's Privacy Protection Law does not touch keyboards.** It took effect
  14 Aug 2025 with fines to NIS 3.2M or 5% of turnover, and it constrains cross-border transfer
  — it does not mandate data residency and creates no demand for a Hebrew lexicon.
- **HIPAA does not mandate keyboard controls.** *"FIPS keyboard"* is not a category.
- Money in trusted input flows to **app-shielding SDKs sold to app teams** (OneSpan), not to
  keyboards.

**The privacy demand is documented but unmonetized.** Trinity College Dublin (2022) measured
Gboard and SwiftKey transmitting installation IDs, per-word language, word length, typing
timestamps, the app being typed in, and advertising IDs, while AnySoftKeyboard sent nothing
(<https://www.scss.tcd.ie/Doug.Leith/pubs/gboard_kamil.pdf>). Yet: **FUTO Keyboard** ($11.99 on
Play, honorware, 100K+ installs) is the only paid offline keyboard, against free FOSS options at
500K–1M installs — and a reviewer summarizes the whole category: *"No internet, no trackers,
private. However it's basically unusable in 2026. No swipe text, no autocorrect."* The
unclaimed position is *offline **and** actually good*. Nobody has been paid for it.

**Open check, 20 minutes, decides whether a product gap exists at all: does FUTO Keyboard
support Hebrew, and how well.** Not yet run.

---

## Path E — sell the method. The only path with precedent.

| case | artifact | what was sold |
|---|---|---|
| **DORA** | free annual State of DevOps Report + *Accelerate* | research + assessment product → **acquired by Google Cloud, Dec 2018**, in their words *"a successful exit in under three years with no investors and no debt"* |
| **Ronny Kohavi** | *Trustworthy Online Controlled Experiments* — guardrail metrics, A/A validation, i.e. positive controls for every check | Maven cohorts at **$1,999–$2,999**, **30+ cohorts / 1,000+ practitioners since 2021**, plus private corporate classes |
| **Nielsen Norman Group** | free research articles | reports, certification, **$60,000 in-house training** |

**The pattern that pays: free, verifiable, public artifact → paid teaching, assessment,
consulting → optionally an acquisition. Never sell the artifact.**

**The honest caveat, which is the real risk in this path:** Forsgren, Humble, Kim, Kohavi and
Nielsen all carried prior institutional authority (Puppet, Google, Microsoft/Amazon/Airbnb,
Bell Labs/Sun). The artifact *converted* existing standing; it did not manufacture it from zero.

**What this repository already holds that fits the pattern.** The register comparison
(`O1-REGISTER`) moves **prefix-1 completion top-3 from 5.35% to 23.72% — ×4.43** — purely by
swapping encyclopaedic prose for held-out transcribed dialogue, 20,000 positions each. Stated as a claim: **Wikipedia-derived Hebrew benchmarks misstate conversational
performance by roughly 4×** — aimed at an ecosystem that just spent 180M NIS building
heavily Wikipedia-derived Hebrew resources and $140M on compute to train on them. **The caveat
travels with the claim or the claim is dishonest:** the conversational slice is transcribed
dialogue, not phone typing. `M10-REGISTER` is still **NOT MEASURED**, and the artifact must say
so on its face. Alongside it,
the 465-screen blind-labelled set establishes a **12.5% precision floor — 4.83 false alarms per
true positive** — for Hebrew real-word error detection against a human judge. No one in the free
tier (Dicta, IAHLT, NNLP-IL) has published a human-judged precision number of this kind for
Hebrew.

---

## What the evidence says not to do

1. **Do not build a paid consumer Hebrew keyboard.** The observed iOS Hebrew niche is 15–28
   ratings per app; the best-funded language-specific keyboard on earth loses money at 100× the
   market size.
2. **Do not build a keyboard SDK licensing business.** Fleksy shut down, PayKey pivoted off it,
   Nuance killed Swype.
3. **Do not sell the lexicon or the labelled sets as assets.** $300–$1,700 band, ShareAlike
   removes exclusivity, reproducibility removes scarcity, and the state is funding free
   substitutes.
4. **Do not lead with compliance.** The regulation is real and the prescribed remedy is banning
   this entire product category.
5. **Do not spend more on the parametric/morphological line before it is tied to a friction a
   user actually reports.** Five pre-registered experiments — O1, K1, G1, G2, G3 — all ended
   NOT ADOPTED, and the market pass found zero user demand articulated around the property they
   were optimising.

## What follows from it

Ordered by cost, not by ambition. Neither is a commitment; both are written so they can fail.

1. **Extend the corpora and the gates to the characters they currently delete, and build a bidi
   defect suite with a positive control.** ~~The control is the point: reproduce the bracket
   inversion first.~~ **RUN — see `docs/BIDI.md` (B1).** The bracket complaint does **not**
   reproduce as a property of the committed text: 0 of 8. What reproduces at **100%** is the
   *other* verified complaint, Hebrew mixed with Latin and digits. All three candidate
   mitigations failed a rule committed beforehand, and the popular one corrupts the text.
   Post hoc, the mechanism looks like an affordance problem in the key labels rather than a
   text problem in the output.
2. **Publish the register finding as a free, reproducible artifact.** ×4.43 with the harness that
   produced it. Path E's shape, and the only path on this page with precedent.

Everything else on this page is a reason to stop, and is recorded as one.

**Both were run. See [`docs/BIDI.md`](BIDI.md) for the first and
[`docs/PROGRAM.md`](PROGRAM.md) for what the two of them together turn into — including `A1`,
a measurement made for that plan which reordered it, and a correction to the claim on this page
that the bidi suite would demonstrate a defect Google and Microsoft ship. It did not.

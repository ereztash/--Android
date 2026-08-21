# Hebrew real-word error detector — the full labelled dataset

Every item put in front of a human, every answer, and every number derived from them.

Written by `scripts/export_label_dataset.py`; the summaries below are recomputed from
the rows at the bottom on every run, so a summary cannot drift from its data.


## What this is

A Hebrew keyboard flags **real-word errors**: words that are spelled correctly and still
wrong, like `אם` where `עם` was meant. Both are real words, so no spell check can see the
mistake — only the surrounding sentence carries the information.


The detector's recall has always been measured on **injected** errors drawn from its own
confusion inventory. That answers *given an error this detector can express, does context
find it?* — not the question a user asks, which is **when it says I am wrong, how often is
it right?** That needs a human. These are those humans' answers.


## How each item was shown

The sentence with the target word blanked, and **two words in random order** — the one
actually in the text and the one the detector proposed — with nothing indicating which was
which. Four responses: the two words, *both are fine*, or *neither / unclear*.


The labeller never saw which word the detector picked. The answer key was written to a
file the labelling screen does not load.


## Provenance

| | |
|---|---|
| corpus | `subtitle-corpus-heldout.txt.gz` |
| corpus sha256 | `1c2d9ce35ee763ed657df8fb90fc289b79ac18df4369b3ea9ea1381b9db61e3a` |
| corpus origin | OPUS OpenSubtitles v2018, Hebrew monolingual, cleaned |
| held out | by construction — every sentence is written to exactly one of train and held-out, so no sampled position trained the model that flagged it |
| sampling | uniform without replacement from the positions where the shipped detector fires, under a recorded seed |
| batch-001 seed | `20260822` |
| batch-001 protocol sha256 | `20b2bf0877012e95…` |
| batch-002 seed | `20260823` |
| batch-002 protocol sha256 | `74b1707eef3c5ebe…` |
| batch-003 seed | `20260824` |
| batch-003 protocol sha256 | `2be3c7540686f545…` |
| detector | shipped defaults; `checkWide` with `next2 = null`, the shape the app runs |
| labeller | one person, native Hebrew, the project's operator |

## Firing rate the sample was drawn from

| | |
|---|---|
| words scanned | 1,815,379 |
| eligible positions | 716,292 |
| detector firings | 2,166 — **1.19 per 1,000 words** |
| of which adjacent-window evidence | 2,156 (99.5%) |
| of which unigram-prior fallback | 8 (0.4%) |
| of which distance-2 evidence | 2 (0.1%) |

**Consequence for anyone analysing this data:** a uniform sample is a sample of the
adjacent path. These labels say nothing about the other two.


## Results by batch

| | batch-001 | batch-002 | batch-003 |
|---|---|---|---|
| screens | 100 | 315 | 50 |
| real firings judged | 80 | 240 | 0 — instrument check |
| controls | 18 / 20 passed | 57 / 60 passed | 10 / 10 passed |
| repeats from an earlier batch | 0 | 15 | 40 |
| **agreed with the detector** | **8** | **32** | — |
| preferred the word in the text | 45 | 148 | — |
| both fine | 4 | 18 | — |
| neither / unclear | 23 | 42 | — |
| abstention rate | 33.8% | 25.0% | — |
| **precision floor** | **10.0%** [5.2, 18.5] | **13.3%** [9.6, 18.2] | — |
| **precision ceiling** | **43.8%** [33.4, 54.7] | **38.3%** [32.4, 44.6] | — |
| median seconds per item | 4.5 | 4.0 | 4.4 |
| total minutes | 11 | 30 | 4 |

**floor** counts every abstention as a loss; **ceiling** counts every abstention as a
win. The true precision lies between them for any resolution of the ambiguous items,
since `agreed/n ≤ agreed/(agreed+overruled) ≤ (agreed+abstained)/n` always holds.


**Neither batch's ceiling reaches 60% at any confidence.**


The batches are reported separately and deliberately **not pooled**: each failed a
different pre-registered bar — batch 001 on abstentions, batch 002 on self-agreement —
and combining two runs that failed to reach a verdict in order to produce one is the
move the protocol exists to prevent. Pool them if your analysis wants to; the rows are
all here. Say that you did.


## Self-agreement, and why the two kinds of disagreement differ


55 items were re-shown in a later batch, re-shuffled, at least a day apart.


- identical answer: **42 / 55**

- **direction reversals** (agreed ↔ overruled): **1**

- moved across the abstain boundary: **12**


This distinction is the single most important thing to carry into any reanalysis.
The **floor** counts agreements over the full denominator, so it moves *only* on a
direction reversal. The **filtered precision** excludes exactly the items whose
classification is unstable. The labeller was stable about which word belongs and
unstable about whether the position is decidable at all — which is what unpointed
Hebrew does to a careful reader.


| item | | before → after | in text / suggested |
|---|---|---|---|
| b002-043 | same | both-fine → both-fine | ותוך / בתוך |
| b002-045 | same | in-text → in-text | עריקת / עריכת |
| b002-070 | same | in-text → in-text | שקי / סקי |
| b002-088 | same | unclear → unclear | לע / לא |
| b002-102 | same | in-text → in-text | הבא / הוא |
| b002-115 | same | both-fine → both-fine | הכשפים / הכספים |
| b002-148 | same | in-text → in-text | עליך / אליך |
| b002-156 | same | in-text → in-text | מאסר / מאשר |
| b002-158 | same | in-text → in-text | לקרוע / לקרוא |
| b002-171 | boundary | unclear → in-text | הביט / הבית |
| b002-179 | same | in-text → in-text | ותכנון / בתכנון |
| b002-235 | same | unclear → unclear | עלו / אלו |
| b002-253 | boundary | in-text → both-fine | יספרו / ישפרו |
| b002-256 | same | in-text → in-text | ואותו / באותו |
| b002-315 | boundary | in-text → unclear | מולי / מבלי |
| b003-001 | boundary | suggested → both-fine | חבר / כבר |
| b003-002 | boundary | unclear → in-text | הבא / הוא |
| b003-003 | same | in-text → in-text | אליי / עליי |
| b003-004 | boundary | in-text → unclear | בג / וג |
| b003-005 | boundary | unclear → in-text | לסוך / לסבך |
| b003-006 | same | unclear → unclear | בג / וג |
| b003-007 | same | in-text → in-text | באולם / בעולם |
| b003-008 | boundary | unclear → both-fine | לסחרר / לשחרר |
| b003-009 | same | both-fine → both-fine | הגור / הגבר |
| b003-011 | same | in-text → in-text | חי / כי |
| b003-012 | same | in-text → in-text | בלב / בלו |
| b003-014 | boundary | in-text → unclear | הגיסה / הגישה |
| b003-015 | same | in-text → in-text | אל / על |
| b003-016 | **REVERSAL** | suggested → in-text | לטבוע / לתבוע |
| b003-017 | same | in-text → in-text | אל / על |
| b003-018 | boundary | unclear → suggested | וובי / בובי |
| b003-019 | boundary | in-text → both-fine | שתף / שטף |
| b003-020 | same | in-text → in-text | אל / על |
| b003-021 | same | in-text → in-text | כה / קה |
| b003-022 | same | in-text → in-text | בכאב / וכאב |
| b003-023 | same | unclear → unclear | וקטע / בקטע |
| b003-024 | same | in-text → in-text | החול / הכול |
| b003-025 | same | in-text → in-text | ואנשים / באנשים |
| b003-026 | same | in-text → in-text | ושנית / בשנית |
| b003-027 | same | in-text → in-text | ועף / ואף |
| b003-028 | same | in-text → in-text | אבד / עבד |
| b003-029 | same | in-text → in-text | אל / על |
| b003-031 | same | suggested → suggested | יעבד / יאבד |
| b003-033 | same | in-text → in-text | השתן / השטן |
| b003-035 | same | suggested → suggested | תצתרך / תצטרך |
| b003-037 | same | in-text → in-text | עבד / עוד |
| b003-038 | same | in-text → in-text | אל / על |
| b003-039 | same | in-text → in-text | המסטר / המשטר |
| b003-040 | same | in-text → in-text | ולרדוף / בלרדוף |
| b003-041 | same | unclear → unclear | בט / בת |
| b003-042 | boundary | both-fine → unclear | שאלי / שעלי |
| b003-044 | same | in-text → in-text | וכוח / בכוח |
| b003-046 | same | in-text → in-text | ועוד / בעוד |
| b003-048 | same | in-text → in-text | חי / כי |
| b003-050 | same | unclear → unclear | טא / תא |

## By confusion pair

| letters | n | agreed | overruled | abstained | agreement of decided |
|---|---|---|---|---|---|
| א/ע | 53 | 4 | 34 | 15 | 10.5% |
| ב/ו | 51 | 5 | 38 | 8 | 11.6% |
| ו/ב | 50 | 4 | 28 | 18 | 12.5% |
| ע/א | 43 | 10 | 24 | 9 | 29.4% |
| ס/ש | 24 | 0 | 16 | 8 | 0.0% |
| ח/כ | 22 | 5 | 14 | 3 | 26.3% |
| ש/ס | 21 | 3 | 12 | 6 | 20.0% |
| ט/ת | 19 | 2 | 7 | 10 | 22.2% |
| כ/ק | 12 | 2 | 7 | 3 | 22.2% |
| ק/כ | 10 | 1 | 6 | 3 | 14.3% |
| כ/ח | 8 | 1 | 4 | 3 | 20.0% |
| ת/ט | 7 | 3 | 3 | 1 | 50.0% |

## By evidence strength

`adv` is the finding's evidence margin in the model's log-count units (`round(log2(count+1)*8)`), so 8 units is a doubling of the underlying corpus count. 21 is the table's pruning floor and the shipped threshold.


| advantage | n | agreed | overruled | abstained | agreement of decided |
|---|---|---|---|---|---|
| 21–27 | 125 | 17 | 70 | 38 | 19.5% |
| 28–39 | 106 | 12 | 69 | 25 | 14.8% |
| 40–63 | 72 | 10 | 44 | 18 | 18.5% |
| 64+ | 17 | 1 | 10 | 6 | 9.1% |

**No band separates agreement from disagreement.** Raising the threshold discards
correct catches at the same rate as wrong ones.


## Questions this dataset can answer, and questions it cannot


**Can:**

- What fraction of the detector's flags a native reader endorses, bounded.

- Whether any recorded feature — evidence margin, confusion pair, word length, sentence length, position, relative frequency — predicts endorsement.

- How often a flagged position is undecidable from the sentence alone.

- How stable one reader's judgments are, split by kind of disagreement.


**Cannot:**

- **Recall.** These are positions the detector *spoke* at. Errors it stayed silent on are not here, and finding them needs a different and much more expensive design.

- **The base rate of real-word errors in Hebrew typing.** Still unmeasured.

- **Anything about phone typing.** The frame is edited subtitle dialogue — closer to conversation than Wikipedia, and still not a person typing on a phone.

- **Anything about the distance-2 or prior-fallback layers**, which produced 10 of 2,166 firings and are essentially absent from a uniform sample.

- **Inter-annotator agreement.** One labeller. The repeats measure agreement with oneself, which is a weaker thing and is reported as such.


**Known contamination:** the corpus cleaner strips the geresh, so `ג'ואנה` appears as
`ג ואנה`, and merged subtitle lines produce run-on sentences. In batch 001, 17 of 80
sentences carried such an artefact; on the 63 clean ones all 8 agreements remained and
the abstention rate fell from 33.8% to 29%. Noise does not explain the result, but any
reanalysis should filter on it — the `words` column and a lexicon check will find them.


## Column reference

| column | meaning |
|---|---|
| `id` | item id, `b<batch>-<index>` in presentation order |
| `batch` | which batch |
| `stratum` | `real` = a genuine firing; `clean` = a control the detector did NOT fire on; `injected` = a control we corrupted; `repeat:…` = re-shown from an earlier batch |
| `path` | which evidence spoke: `adjacent`, `distance-2`, `prior`; `none`/`known` for controls |
| `adv` | evidence advantage in log-count units; blank for controls |
| `ctx` | neighbouring words available, 1 or 2 |
| `ms` | milliseconds the labeller spent on the item |
| `answer` | what was chosen: `suggested`, `in-text`, `both-fine`, `unclear` |
| `outcome` | `agreed` / `overruled` / `both-fine` / `unclear` for real items; `control-pass` / `control-miss` for controls |
| `pair` | the two letters that differ |
| `in_text` | the word standing in the sentence |
| `suggested` | the word offered against it — the detector's suggestion on a real item, the original on an injected control, a distractor the detector never proposed on a clean control |
| `freq_text / freq_sugg` | shipped unigram log-frequency of each, 0–255 |
| `position` | 0-based index of the target word |
| `words` | sentence length in tokens |
| `source_id` | position in the source corpus, `kind-sentence-position` |
| `sentence` | the full sentence; the target is marked ⟦…⟧ in the table below |

The same rows are in `LABEL_DATASET.tsv` beside this file, tab separated.


## All 465 items

The target word is wrapped in ⟦ ⟧. `answer` is what the labeller chose; the option
numbers they saw were randomised per item and are not meaningful here.


| id | stratum | path | adv | ms | answer | outcome | in text | suggested | sentence |
|---|---|---|---|---|---|---|---|---|---|
| b001-001 | real | adjacent | 23 | 8092 | in-text | overruled | יספרו | ישפרו | אתה יודע יום אחד ⟦יספרו⟧ את הסיפור |
| b001-002 | real | adjacent | 35 | 17718 | in-text | overruled | מאסר | מאשר | כך שתסתכלו על הכל ותהיו צמודים למכשירי הקשר אם תמצאו משהוא תודיעו אני רוצה ⟦מאסר⟧ לא קרב יריות |
| b001-003 | real | adjacent | 24 | 7574 | in-text | overruled | וריאה | בריאה | לב כבד ⟦וריאה⟧ של כבשה טחונים עם חלב כליות בצלים ושיבולת שועל מורתחים בקיבת חיה |
| b001-004 | real | adjacent | 34 | 12844 | unclear | unclear | הביט | הבית | שזה ⟦הביט⟧ עליהם כמחלה מדבקת בכמה גלקסיות |
| b001-005 | real | adjacent | 21 | 26903 | unclear | unclear | לכבל | לקבל | אם אתה רוצה שאקח אותך ⟦לכבל⟧ אני רוצה הסבר |
| b001-006 | real | adjacent | 25 | 9575 | in-text | overruled | בהרבה | והרבה | ברייס בגד ⟦בהרבה⟧ אנשים נכון |
| b001-007 | real | adjacent | 34 | 15589 | unclear | unclear | שועל | שואל | גרג אמר שזה לא היה ⟦שועל⟧ אמיתי |
| b001-008 | real | adjacent | 25 | 7857 | in-text | overruled | שקי | סקי | אבא ודאי לא שם לב שפרקתי ק ג של ⟦שקי⟧ קמח כי הוא עדיין לא יכול להרימם ולכן אין לאמא זמן לשבת ולקשקש |
| b001-009 | real | adjacent | 26 | 18143 | in-text | overruled | ישלחו | יסלחו | קח אותו לביה ח ואל תזכיר את הכתובת פה שלא ⟦ישלחו⟧ לי מפקחים של הממשלה |
| b001-010 | real | adjacent | 98 | 2222 | in-text | overruled | לשפר | לספר | מתערבת שאוכל ⟦לשפר⟧ לך את מצב הרוח |
| b001-011 | real | adjacent | 63 | 3595 | suggested | agreed | יעבד | יאבד | אנדרו עבד עבורי במשך שנה אף אחד לא ⟦יעבד⟧ את מקום פרנסתו בטח שאין חשש בלבצע עידכונים כאלה |
| b001-012 | real | adjacent | 25 | 4354 | suggested | agreed | ושל | בשל | את אומרת שהנאשם גאיוס בולטאר הורה על הוצאתך להורג ⟦ושל⟧ עוד אזרחים אחרים |
| b001-013 | real | adjacent | 70 | 4147 | in-text | overruled | אל | על | ומה בקשר ⟦אל⟧ הילדה הזאת |
| b001-014 | real | adjacent | 22 | 2426 | in-text | overruled | עולם | אולם | הוא יראה לי ⟦עולם⟧ שלא הכרתי |
| b001-015 | real | adjacent | 62 | 2868 | suggested | agreed | תביע | תביא | אולי היא ⟦תביע⟧ אותה אישית |
| b001-016 | injected | known |  | 7754 | suggested | control-pass | סל | של | אבי שלח את אונקס ואותי לביה ס ⟦סל⟧ הכומר וילוק בגיל כדי שנכיר את שני העולמות |
| b001-017 | real | adjacent | 28 | 6642 | unclear | unclear | לאכ | לאח | תאמ ןמ ור יפל ב וח ול ו ש ⟦לאכ⟧ י מ |
| b001-018 | real | adjacent | 21 | 10126 | unclear | unclear | וקטע | בקטע | אהבה ⟦וקטע⟧ עם כלב |
| b001-019 | real | adjacent | 25 | 5482 | in-text | overruled | ותכנון | בתכנון | וליתר דיוק מחלקת הנדסה ⟦ותכנון⟧ ערים |
| b001-020 | injected | known |  | 10811 | suggested | control-pass | עז | אז | או ⟦עז⟧ מה שג יי ראה שם אלה לא באמת שדיים |
| b001-021 | real | adjacent | 35 | 5547 | in-text | overruled | הקורים | הקברים | כשאין איפה לתלות את ⟦הקורים⟧ שלהם הם טווים את חוטי המשי על האדמה חול מסבך נותן תבואה יחדיו |
| b001-022 | injected | known |  | 5105 | suggested | control-pass | רועה | רואה | כאן אתה ⟦רועה⟧ את העמים או סמלי העמים השונים מקבצים את ארבעת יסודות החיים המים האש האדמה והאוויר סביב ליסוד החמישי |
| b001-023 | real | adjacent | 21 | 9155 | in-text | overruled | לכם | לחם | משלם את החשבונות קונה ת אוכל שם ⟦לכם⟧ גג מעל הראש |
| b001-024 | real | adjacent | 39 | 5247 | in-text | overruled | לגבר | לגור | ג יין ראויה ⟦לגבר⟧ בו היא חושקת לא לזה שישלם מחיר גבוה יותר |
| b001-025 | real | adjacent | 25 | 4982 | in-text | overruled | עפה | אפה | איך את ⟦עפה⟧ בלי כנפיים |
| b001-026 | injected | known |  | 1918 | suggested | control-pass | עיפה | איפה | אלווה אותך הביתה ⟦עיפה⟧ את גרה |
| b001-027 | real | adjacent | 45 | 10581 | unclear | unclear | הבא | הוא | כן היום זה יום ⟦הבא⟧ לעבודה קרוב משפחה רחוק |
| b001-028 | real | adjacent | 23 | 2893 | in-text | overruled | קורעת | קוראת | כי את יודעת שאני ⟦קורעת⟧ אותך |
| b001-029 | real | adjacent | 28 | 4224 | in-text | overruled | עליך | אליך | קנדל אני חושב שעובר ⟦עליך⟧ עוד יום רע |
| b001-030 | real | adjacent | 29 | 2630 | in-text | overruled | משיט | מסיט | תראי איך הוא ⟦משיט⟧ את האונייה שלו |
| b001-031 | real | adjacent | 104 | 4171 | in-text | overruled | אב | או | אני רוצה ⟦אב⟧ שהוא אותו האיש גם בבוקר וגם בלילה |
| b001-032 | injected | known |  | 1882 | suggested | control-pass | פאם | פעם | אף אחד אף ⟦פאם⟧ לא דאג לו |
| b001-033 | real | adjacent | 26 | 4983 | both-fine | both-fine | הסלט | השלט | בא במקום ⟦הסלט⟧ הזה |
| b001-034 | real | adjacent | 37 | 4543 | suggested | agreed | עני | אני | או שתהיה ⟦עני⟧ בחוץ בחלק מהם |
| b001-035 | clean | none |  | 3596 | in-text | control-pass | השאיר | השעיר | הוא ⟦השאיר⟧ את אמא שלי בצד |
| b001-036 | real | adjacent | 25 | 13525 | unclear | unclear | לעזור | לאזור | היי תשאל אותו ⟦לעזור⟧ לא אנחנו נכנסים לצרות |
| b001-037 | clean | none |  | 7408 | in-text | control-pass | שהוא | שהבא | החוק אומר שהוא צריך לדעת ⟦שהוא⟧ מוצא להורג הוא חייב לדעת למה הוא מוצא להורג |
| b001-038 | real | adjacent | 27 | 4272 | unclear | unclear | אין | עין | גבר ⟦אין⟧ ג נטלמן רוצה לדבר איתך |
| b001-039 | real | adjacent | 47 | 3967 | in-text | overruled | הבא | הוא | אם אני אתן למקסוול לצאת מזה אתה לא חושב שהבחור ⟦הבא⟧ ינסה לעשות את אותו הדבר |
| b001-040 | real | adjacent | 24 | 3052 | unclear | unclear | חל | כל | חל מ ו ם ⟦חל⟧ לב קל ה ש קבב ם יק ז וקה ינפמ |
| b001-041 | real | adjacent | 31 | 3944 | in-text | overruled | בלהביס | ולהביס | יש משהו מאוד רומנטי ⟦בלהביס⟧ את הלא מתים |
| b001-042 | real | adjacent | 28 | 4547 | suggested | agreed | עלון | אלון | היית צריך לראות כמה תשוקה הייתה בו כשהוא ראה את ⟦עלון⟧ הזין |
| b001-043 | real | adjacent | 48 | 9782 | in-text | overruled | עבד | עוד | עכשיו לאחר שהוא טעם בשר אדם הכלב יהיה ⟦עבד⟧ לתאוות הבשר שלו |
| b001-044 | real | adjacent | 35 | 4054 | in-text | overruled | לקרוע | לקרוא | כולנו צריכים ⟦לקרוע⟧ ת תחת |
| b001-045 | real | adjacent | 42 | 3171 | in-text | overruled | בג | וג | אתה בטח כל כך גאה ⟦בג⟧ ואי ובכל מה שקורה בקריירה שלו |
| b001-046 | injected | known |  | 2309 | in-text | control-miss | ואה | באה | ג ואנה ⟦ואה⟧ לכאן אל תדאג |
| b001-047 | real | adjacent | 26 | 2240 | unclear | unclear | האול | העול | אנו מחפשים את ⟦האול⟧ ספארק |
| b001-048 | injected | known |  | 3111 | suggested | control-pass | חל | כל | תשמור לך את ⟦חל⟧ הזכיות פחות חלקי החילוף שאני צריך |
| b001-049 | injected | known |  | 3816 | suggested | control-pass | מסנה | משנה | אני מתכוונת לצוד אותו לא משנה כמה זמן זה ייקח לא ⟦מסנה⟧ מה המחיר |
| b001-050 | real | adjacent | 22 | 7270 | in-text | overruled | כל | קל | רב ערך מדי ⟦כל⟧ השליטה הזו |
| b001-051 | real | adjacent | 37 | 4280 | in-text | overruled | ושנית | בשנית | קודם כן זה פשע של זעם לא משיכה מינית ⟦ושנית⟧ את לא דוחה את מאוד יפה |
| b001-052 | real | adjacent | 23 | 7476 | in-text | overruled | אלה | עלה | לאהבה בתנאים שלי ⟦אלה⟧ התנאים היחידים שכל אחד יודע התנאים שלו |
| b001-053 | real | adjacent | 21 | 3385 | suggested | agreed | חבר | כבר | תעשה סיבוב ⟦חבר⟧ תעשה סיבוב |
| b001-054 | real | adjacent | 21 | 3563 | suggested | agreed | שוכני | סוכני | זה אותו הסיפור עם ⟦שוכני⟧ מערות אחרים |
| b001-055 | real | adjacent | 26 | 2258 | in-text | overruled | הבא | הוא | ביום שבת ⟦הבא⟧ ייערך מצעד הגאווה |
| b001-056 | real | adjacent | 42 | 3292 | in-text | overruled | אשרת | עשרת | תמונה של ⟦אשרת⟧ הנסיעה |
| b001-057 | clean | none |  | 2431 | in-text | control-pass | זכה | זחה | שמעתי שפלנטיין ⟦זכה⟧ במועמדות |
| b001-058 | real | adjacent | 22 | 6038 | in-text | overruled | מולי | מבלי | החברה שלו ⟦מולי⟧ חולה |
| b001-059 | real | adjacent | 24 | 1588 | in-text | overruled | שביר | סביר | לא הוא ⟦שביר⟧ מדי |
| b001-060 | real | adjacent | 26 | 1334 | in-text | overruled | בזה | וזה | עכשיו אני נזכרת ⟦בזה⟧ וזה כמעט מצחיק |
| b001-061 | real | adjacent | 41 | 3155 | in-text | overruled | החסד | החשד | אבל הענק לי את ⟦החסד⟧ הקטן הזה |
| b001-062 | real | adjacent | 67 | 3705 | both-fine | both-fine | הגור | הגבר | של מי ⟦הגור⟧ הזה |
| b001-063 | real | adjacent | 50 | 1296 | unclear | unclear | לע | לא | םחל תצק קר םינוונחה תא הריכמ תא אבא ⟦לע⟧ ורבדי םה |
| b001-064 | real | adjacent | 23 | 8659 | in-text | overruled | אבודה | עבודה | התקווה שהייתה לך בחשאי ⟦אבודה⟧ עכשיו |
| b001-065 | real | adjacent | 43 | 16145 | both-fine | both-fine | הכשפים | הכספים | ראינו את ⟦הכשפים⟧ שלו |
| b001-066 | real | adjacent | 35 | 22507 | in-text | overruled | אל | על | לא לא כדי להגיע לביתו של נד כדאי שתפנה ימינה ותחזור ⟦אל⟧ הגבעות |
| b001-067 | real | adjacent | 31 | 5236 | in-text | overruled | בצ | וצ | רבותיי אני יכולה לעניין אתכם ⟦בצ⟧ יפס צ ילי וחצי המבורגר |
| b001-068 | real | adjacent | 41 | 10531 | unclear | unclear | עלו | אלו | אתה שוכח שלי הן ⟦עלו⟧ בשלי |
| b001-069 | clean | none |  | 2271 | in-text | control-pass | חושב | חושו | הוא ⟦חושב⟧ לעבור לגור ברומא |
| b001-070 | real | adjacent | 59 | 5397 | unclear | unclear | אל | על | המצאת דמות ⟦אל⟧ אחת עבורך למה לא להמציא אחת עבורה |
| b001-071 | real | adjacent | 25 | 5172 | unclear | unclear | אלי | עלי | כן ⟦אלי⟧ טרוג הכדור אצל מאוס |
| b001-072 | injected | known |  | 4653 | suggested | control-pass | הבא | הוא | מה ⟦הבא⟧ יכול לעשות עם חצי חור |
| b001-073 | real | adjacent | 28 | 5811 | in-text | overruled | ואותו | באותו | אל תששש אותי ⟦ואותו⟧ לא |
| b001-074 | real | adjacent | 22 | 6475 | in-text | overruled | אל | על | מסעדת ⟦אל⟧ חרם במזרח התיכון הציגה חוויה קולינרית שהייתה מאוד ייחודית |
| b001-075 | injected | known |  | 3492 | suggested | control-pass | מיש | מיס | יש לך סירה עבור ⟦מיש⟧ דניאלס |
| b001-076 | real | adjacent | 22 | 2823 | in-text | overruled | אל | על | ולנסות ולהיות נחמד ⟦אל⟧ מלקולם |
| b001-077 | real | adjacent | 25 | 4335 | suggested | agreed | עיני | איני | אינני מוכן לקבל זאת ⟦עיני⟧ חזיר חתיכת חרה |
| b001-078 | clean | none |  | 6530 | in-text | control-pass | אותו | עותו | אני תמיד שמח לקבל מעלה בן להחזיר ⟦אותו⟧ בחוץ מנשק הבנות מה שהופך אותם לבכות |
| b001-079 | clean | none |  | 21515 | unclear | control-miss | כי | חי | זה רעיון נפלא זה רעיון נהדר אני כבר מכיר אותו ⟦כי⟧ אנו צופים בו שנים וזה מחזיר אליי את הפור הייתה לו טרמפולינה |
| b001-080 | real | adjacent | 25 | 22352 | in-text | overruled | בלב | בלו | לפחות נימוסי הרופא שלך משאירים תקווה ⟦בלב⟧ ג ק |
| b001-081 | real | adjacent | 38 | 15677 | unclear | unclear | לסוך | לסבך | באופן רגיל פועלות צינוריות הדמעות כדי ⟦לסוך⟧ את העין ולהגן עליה כשמתרגשים הן מגיבות באופן מוגזם ויוצרות דמעות |
| b001-082 | clean | none |  | 5975 | in-text | control-pass | ון | בן | ג ⟦ון⟧ קלהון ביקר אותי |
| b001-083 | real | adjacent | 22 | 6486 | unclear | unclear | במצבר | במצור | האורגים שלו התרבו ⟦במצבר⟧ של נבנה מחדש בשטחיה של מורדור |
| b001-084 | real | adjacent | 59 | 7039 | in-text | overruled | אוטו | אותו | הכלב המסכן שלי בטח רץ החוצה ונפגע מאיזה ⟦אוטו⟧ או משאית |
| b001-085 | real | adjacent | 24 | 3533 | in-text | overruled | המוטבת | המוטות | לא התכוונתי לספר לך אבל את ⟦המוטבת⟧ שלי אם יקרה לי משהו |
| b001-086 | real | adjacent | 42 | 3210 | unclear | unclear | לב | לו | אני לא הורשו ⟦לב⟧ דבר כזה זמן |
| b001-087 | real | adjacent | 21 | 3532 | unclear | unclear | אולי | עולי | חשבתי על ⟦אולי⟧ עושה סיור ספר ברחבי הצי |
| b001-088 | real | adjacent | 52 | 2932 | in-text | overruled | באם | ואם | לא למעשה זה תלוי ⟦באם⟧ הם יצליחו לשרוד את הלילה |
| b001-089 | real | adjacent | 47 | 4773 | unclear | unclear | אל | על | רובין ⟦אל⟧ ז אק אני יודע שאהבת אותו אבל חייבים לעצור אותו |
| b001-090 | real | adjacent | 26 | 4041 | unclear | unclear | כדור | כדבר | אני יודעת איך נשמע ⟦כדור⟧ שיער |
| b001-091 | real | adjacent | 34 | 4170 | in-text | overruled | עריקת | עריכת | הוא עודד את ⟦עריקת⟧ המתיישבים |
| b001-092 | real | adjacent | 39 | 6693 | unclear | unclear | טא | תא | מס התותח הראשי הוא בחוץ ואני השקנו כל ⟦טא⟧ אה |
| b001-093 | real | adjacent | 59 | 1547 | unclear | unclear | מב | מו | עמ ש ⟦מב⟧ הנ שה םל י י ח ומל ענ הל מה ל ש תלב וק מה |
| b001-094 | clean | none |  | 3032 | in-text | control-pass | הונג | הבנג | אגדה בכל רחבי ⟦הונג⟧ קונג |
| b001-095 | real | adjacent | 34 | 2504 | in-text | overruled | חי | כי | ודאי שמעת שדיקי ⟦חי⟧ כעת באיטליה |
| b001-096 | real | adjacent | 29 | 7316 | in-text | overruled | וכל | בכל | הוא אוהב שמסתובבים אצלו אנשים וחוגגים מסיבות ⟦וכל⟧ העניינים |
| b001-097 | real | adjacent | 26 | 7325 | unclear | unclear | עשו | עשב | עם יתרון ל ⟦עשו⟧ חברי פיי לונג הסכם להדחת חברי ז אן הו |
| b001-098 | clean | none |  | 1483 | in-text | control-pass | על | אל | מי הורה ⟦על⟧ כך |
| b001-099 | real | adjacent | 33 | 6514 | both-fine | both-fine | ותוך | בתוך | הכרתי את באדי במכללה ⟦ותוך⟧ שלושה שבועות הוא בחר עבורי כוכב |
| b001-100 | clean | none |  | 3961 | in-text | control-pass | של | סל | אחרי עונת בייס קטבול הרגילה והמעייפת אנחנו חוזרים לחודש התשיעי ⟦של⟧ הגמר |
| b002-001 | clean | none |  | 40083 | in-text | control-pass | אותי | עותי | אתה מבין ⟦אותי⟧ לא אתה צודק אתה צודק בהחלט ממש ברגע זה אני מרגיש את המיצים חוזרים לביצים שלי |
| b002-002 | real | adjacent | 22 | 2857 | suggested | agreed | חבר | כבר | היא תפוסה ⟦חבר⟧ היא חייבת לו |
| b002-003 | real | adjacent | 22 | 6269 | in-text | overruled | ועוד | בעוד | שתברך אותם ותחזק אותם ⟦ועוד⟧ נבקש אל יקר שתברך גם את אלה שנשארו כאן בקבוצה זו |
| b002-004 | real | adjacent | 68 | 2605 | suggested | agreed | נקרע | נקרא | זה ⟦נקרע⟧ צמיד האהבה נכון |
| b002-005 | real | adjacent | 21 | 10026 | in-text | overruled | עליה | אליה | אם אגן ⟦עליה⟧ אז היא שלי |
| b002-006 | injected | known |  | 21861 | suggested | control-pass | סל | של | אני לא יכולה להכנס לבתים ⟦סל⟧ אנשים ולהרוג אותם |
| b002-007 | real | adjacent | 26 | 2431 | in-text | overruled | אליי | עליי | אל תתקרבי ⟦אליי⟧ מפני שאני לא רוצה לפגוע בך |
| b002-008 | real | adjacent | 30 | 3782 | in-text | overruled | מתכת | מתחת | המפקח טאו ארסן זה לא ⟦מתכת⟧ כבדה |
| b002-009 | clean | none |  | 2425 | in-text | control-pass | אתה | עתה | מה ⟦אתה⟧ עושה כאן בכלל |
| b002-010 | real | adjacent | 29 | 4735 | both-fine | both-fine | השידור | הסידור | ריד אני יודע מה טום חושב על ⟦השידור⟧ הזה |
| b002-011 | real | adjacent | 31 | 5816 | unclear | unclear | עשר | אשר | אל תתבייש אתה יודע הייתי רעב פעם ראיתי איש עשיר איש ⟦עשר⟧ שקינאתי |
| b002-012 | real | adjacent | 90 | 5292 | unclear | unclear | לסחרר | לשחרר | כדי ⟦לסחרר⟧ אותי כמוך |
| b002-013 | real | adjacent | 36 | 3747 | in-text | overruled | אם | עם | אולי תחשוב פעמיים ⟦אם⟧ להיכנס לתא עם הבחור הזה |
| b002-014 | clean | none |  | 2822 | in-text | control-pass | רוצה | רבצה | אני ⟦רוצה⟧ לדבר על זה |
| b002-015 | clean | none |  | 10357 | in-text | control-pass | יותר | יבתר | אני תמיד הגעתי ⟦יותר⟧ עמוק ממנו |
| b002-016 | real | adjacent | 90 | 5735 | in-text | overruled | סם | שם | תגיד לי היא אמרה באיזה ⟦סם⟧ היא השתמשה על מנת להצית את עצמה |
| b002-017 | real | adjacent | 60 | 7135 | unclear | unclear | הקבל | הקול | הם אמרו שבחורה בהריון שעושה סקי תהפוך את ⟦הקבל⟧ לעצבני |
| b002-018 | injected | known |  | 1966 | suggested | control-pass | תוב | טוב | אני לא מרגיש ⟦תוב⟧ אני ממש בלחץ |
| b002-019 | real | adjacent | 40 | 6782 | unclear | unclear | החוטם | החותם | כי הלהב בחזית מזכיר לי את ⟦החוטם⟧ הצופר הגדול שלך יקירה |
| b002-020 | clean | none |  | 3134 | in-text | control-pass | פאר | פער | זו דירת ⟦פאר⟧ בת שמונה חדרים |
| b002-021 | clean | none |  | 8154 | in-text | control-pass | הכוס | הקוס | כשאתה צעיר ⟦הכוס⟧ קטנה וקל למלא אותה |
| b002-022 | real | adjacent | 25 | 15181 | in-text | overruled | עם | אם | תגידי לו שחתימה על החוק ⟦עם⟧ הכרייה לא תחסום יחצ נות שתהלל תיקונים בבנקאות וגם תוקיע את תרמית חוגי הכרייה |
| b002-023 | real | adjacent | 26 | 7129 | suggested | agreed | וצ | בצ | בגלל שאני יכול לחשב כל חשבון קבלה ⟦וצ⟧ ק בצורה משעממת מס הכנסה תמיד מוזמן אלי הביתה |
| b002-024 | clean | none |  | 1491 | in-text | control-pass | טוב | תוב | הם לא מכירים זה את זה ⟦טוב⟧ כמונו |
| b002-025 | real | adjacent | 69 | 1921 | in-text | overruled | העם | האם | שמפשוטי ⟦העם⟧ אני או כי אני המתחרה |
| b002-026 | injected | known |  | 2060 | suggested | control-pass | מתר | מטר | יש לי גבר שהוא יותר משני ⟦מתר⟧ גובה |
| b002-027 | real | adjacent | 43 | 7201 | unclear | unclear | וחלק | בחלק | הם עברו את חי ר ⟦וחלק⟧ של |
| b002-028 | real | adjacent | 24 | 5352 | suggested | agreed | משחיט | משחית | הסרט ישבנים שרופים דורג כמספר אחד בשוברי הקופות אבל הסרט הזה ⟦משחיט⟧ את הנוער |
| b002-029 | clean | none |  | 6016 | in-text | control-pass | בזכות | וזכות | מבחינה מסוימת אתה גיבור מבחינה מסוימת אתה גיבור ⟦בזכות⟧ בריחתך מהג ונגל |
| b002-030 | real | adjacent | 30 | 4000 | in-text | overruled | החי | הכי | הגבר ⟦החי⟧ הראשון מזה שנה ואני הוצאתי אותו מכלל פעולה |
| b002-031 | injected | known |  | 25819 | suggested | control-pass | תוב | טוב | אתה תשמש בתפקיד עד שתמות או שאמצא מישהו יותר ⟦תוב⟧ תודה המפקד |
| b002-032 | real | adjacent | 49 | 2689 | in-text | overruled | לחור | לחבר | תיכנסי ⟦לחור⟧ שלך כלבה |
| b002-033 | real | adjacent | 44 | 5662 | suggested | agreed | ובית | בבית | יאנוס קורצ אק בית ספר ⟦ובית⟧ יתומים בגטו וורשה |
| b002-034 | real | adjacent | 37 | 3849 | suggested | agreed | מבותרים | מוותרים | כן ואם אתם לא תצאו משם לפני שמישהו יתפוס אותכם אנחנו נמצא את עצמנו ⟦מבותרים⟧ על כל רחבי העולם קדימה |
| b002-035 | clean | none |  | 6192 | in-text | control-pass | ספר | שפר | ירית בו או משהו לא עוד לא הוא הבריז מבית ⟦ספר⟧ כשהלכתי הביתה לבדוק מישהו היה שם |
| b002-036 | real | adjacent | 38 | 3088 | in-text | overruled | המסטר | המשטר | זה בלתי נמנע שהתלמידים יתעלו על ⟦המסטר⟧ זה הגורל בני אנוש לא יכולים להתנגד לו |
| b002-037 | clean | none |  | 1909 | in-text | control-pass | בפנקס | ופנקס | נאלצתי להשתמש ⟦בפנקס⟧ הצ קים שלי |
| b002-038 | real | adjacent | 54 | 3728 | in-text | overruled | ויום | ביום | מי יתן ⟦ויום⟧ זה יזכר כיומו של פלאש גורדון |
| b002-039 | real | adjacent | 27 | 9240 | unclear | unclear | ואת | באת | ידעתי שיהיה קשה לך שהוא פה אבל אני אוהב אותך כל כך ⟦ואת⟧ רומן |
| b002-040 | real | adjacent | 37 | 28719 | unclear | unclear | וואו | בואו | שף מפורסם ⟦וואו⟧ הנה נסי חלה ביתית אחת |
| b002-041 | real | adjacent | 40 | 4256 | in-text | overruled | מט | מת | ברינקלי הוא תפסן מעולה וכבר הציעו לו מבחן ב ⟦מט⟧ ס |
| b002-042 | real | adjacent | 26 | 3430 | in-text | overruled | אל | על | פקסס עותק של הצו ⟦אל⟧ הפקיד |
| b002-043 | repeat:batch-001 | adjacent | 33 | 3998 | both-fine | both-fine | ותוך | בתוך | הכרתי את באדי במכללה ⟦ותוך⟧ שלושה שבועות הוא בחר עבורי כוכב |
| b002-044 | clean | none |  | 4636 | in-text | control-pass | סרט | שרט | והוא מתכוון לזה בקפדנות של ⟦סרט⟧ ללא פושעים |
| b002-045 | repeat:batch-001 | adjacent | 34 | 2841 | in-text | overruled | עריקת | עריכת | הוא עודד את ⟦עריקת⟧ המתיישבים |
| b002-046 | real | adjacent | 21 | 1945 | unclear | unclear | שה | סה | שו דו שו דו שו דו שו דו ⟦שה⟧ לה לה לה לה לה לה הא |
| b002-047 | real | adjacent | 21 | 7033 | in-text | overruled | ואנשים | באנשים | אני מסכים ⟦ואנשים⟧ צעירים ותמימים צריכים להודות על מזלם להיות אסירי תודה |
| b002-048 | real | adjacent | 30 | 7683 | unclear | unclear | אל | על | מתוך אנשי ⟦אל⟧ למה יש לך לזיין את הבחורה הזאת |
| b002-049 | real | adjacent | 39 | 2644 | in-text | overruled | נכה | נקה | היי ווילסון אני הולך להוציא לאיזה ⟦נכה⟧ את העין |
| b002-050 | clean | none |  | 3561 | both-fine | control-pass | אנטוני | אנתוני | היי שמי ⟦אנטוני⟧ היידן |
| b002-051 | real | adjacent | 22 | 3503 | in-text | overruled | קי | כי | תחב ⟦קי⟧ אותי כאילו את באמת שמחה |
| b002-052 | real | adjacent | 29 | 2673 | in-text | overruled | השמים | הסמים | הארץ עם ⟦השמים⟧ הגדולים |
| b002-053 | real | adjacent | 22 | 3514 | suggested | agreed | באז | ואז | לא ⟦באז⟧ תחשוב על זה |
| b002-054 | real | adjacent | 22 | 3796 | in-text | overruled | והתנהגות | בהתנהגות | עכשיו יש לנו נשק אוטומטי מחסן מלא די וי די ⟦והתנהגות⟧ בלתי נאותה |
| b002-055 | real | adjacent | 34 | 2302 | in-text | overruled | שפרד | ספרד | את מדברת על ⟦שפרד⟧ וסלואן |
| b002-056 | real | adjacent | 27 | 6757 | unclear | unclear | חי | כי | אמא צ נדלר שונא את ⟦חי⟧ ההודיה והוא לא אוכל אף מאכל של החג |
| b002-057 | real | adjacent | 41 | 2986 | suggested | agreed | תפשתי | תפסתי | כמעת ⟦תפשתי⟧ אותו אבל זזו לי המשקפיים |
| b002-058 | injected | known |  | 5198 | suggested | control-pass | רכ | רק | הא הא אין לי בעיה עם זה ⟦רכ⟧ ש |
| b002-059 | real | adjacent | 52 | 10010 | in-text | overruled | עברך | עורך | לא קשור לטיסה אבל בהסתמך על ⟦עברך⟧ הצבאי לקרן שלי יש משרות פנויות במחלקת אבטחה |
| b002-060 | real | adjacent | 21 | 4339 | in-text | overruled | בק | בכ | שים לב הראות פוחתת ⟦בק⟧ מ האחרון |
| b002-061 | real | adjacent | 47 | 6039 | in-text | overruled | הבא | הוא | נעבור לתיק ⟦הבא⟧ ד |
| b002-062 | real | adjacent | 25 | 5768 | unclear | unclear | המסיכה | המשיכה | עשרים וחמישה על ⟦המסיכה⟧ סליחה |
| b002-063 | real | adjacent | 34 | 9013 | suggested | agreed | וביהמ | בביהמ | רידנשניידר חזר הביתה ⟦וביהמ⟧ ש מינה לי את לויד גארווי שהותיר אותי לחסדי בית המשפט |
| b002-064 | real | adjacent | 29 | 3227 | in-text | overruled | בלענות | ולענות | הקמפיין שלך ⟦בלענות⟧ על שאלות על |
| b002-065 | real | adjacent | 40 | 6740 | unclear | unclear | עלה | אלה | עצום את עיניך תינוקות בעריסה סופרות כבשים ⟦עלה⟧ את בית החלומות |
| b002-066 | real | adjacent | 34 | 7375 | in-text | overruled | נפטר | נפתר | למה אתה לא ⟦נפטר⟧ מהם |
| b002-067 | real | adjacent | 33 | 25975 | in-text | overruled | החומר | הכומר | ואני אומר אם זה היה ⟦החומר⟧ הפרטי שלה שאתו היא מתמסטלת |
| b002-068 | real | adjacent | 31 | 4480 | in-text | overruled | באולם | בעולם | האקוסטיקה שם דומה לאקוסטיקה ⟦באולם⟧ ו תראו מי הופיעה |
| b002-069 | real | adjacent | 30 | 9400 | unclear | unclear | כבר | חבר | אבנים יקרות כמו ⟦כבר⟧ מכרתי תכשיטים לחנויות יוקרה |
| b002-070 | repeat:batch-001 | adjacent | 25 | 2508 | in-text | overruled | שקי | סקי | אבא ודאי לא שם לב שפרקתי ק ג של ⟦שקי⟧ קמח כי הוא עדיין לא יכול להרימם ולכן אין לאמא זמן לשבת ולקשקש |
| b002-071 | real | adjacent | 21 | 2443 | in-text | overruled | אמט | אמת | פגשתי את ⟦אמט⟧ בשיקגו |
| b002-072 | real | adjacent | 58 | 2568 | in-text | overruled | וחיים | בחיים | הם פשוט אמיתיים יותר ⟦וחיים⟧ יותר |
| b002-073 | real | adjacent | 33 | 4615 | in-text | overruled | ההסוואה | ההשוואה | תבטל את ⟦ההסוואה⟧ של ספינת ההדמיה ותפעיל את קרן הגרירה |
| b002-074 | real | adjacent | 32 | 11807 | in-text | overruled | ועף | ואף | עוזב את הגלגל של ב ⟦ועף⟧ אל הגלגל של אופניים א קדימה ואחורה עד ששני זוגות האופניים מתנגשים |
| b002-075 | real | adjacent | 27 | 3341 | both-fine | both-fine | אנטוני | אנתוני | הרשה לי להציג את בת חסותי העלמה קתרין ברוק זהו סר ⟦אנטוני⟧ נייברט |
| b002-076 | real | adjacent | 32 | 3345 | in-text | overruled | אלה | עלה | אבל אם נסכים לקרב הזה ⟦אלה⟧ שישרדו בו יראו את צלקותיהם בגאווה ויאמרו |
| b002-077 | real | adjacent | 59 | 2497 | in-text | overruled | סולח | שולח | אבי לעולם לא היה ⟦סולח⟧ לי אם לא הייתי מציגה את עצמי |
| b002-078 | clean | none |  | 1632 | in-text | control-pass | יכול | יחול | אני חושב שאני ⟦יכול⟧ לחתום על זה |
| b002-079 | real | adjacent | 35 | 2745 | in-text | overruled | חובשים | כובשים | כשהם ⟦חובשים⟧ את כובע הטלה הפרסי רובם אפילו אינם יודעים שהם לובשים עור של צאצא כדי לחמם את עצמם |
| b002-080 | real | adjacent | 25 | 716 | both-fine | both-fine | להאריך | להעריך | שהיום אנחנו יכולים ⟦להאריך⟧ חיים |
| b002-081 | real | adjacent | 39 | 6469 | in-text | overruled | בג | וג | אנחנו לא יכולות לבטוח ⟦בג⟧ ונתן |
| b002-082 | real | adjacent | 24 | 8167 | in-text | overruled | קבר | כבר | לא יכולתי לצאת לראפטינג כי הוא בדיוק ⟦קבר⟧ ילדה שטבעה |
| b002-083 | real | adjacent | 31 | 4685 | in-text | overruled | אם | עם | אני רק אומר שאם החתול הזה באמת תקף אותך יש פחות סיכויים שהוא יחזור ⟦אם⟧ שתיכן תהיו יחד |
| b002-084 | real | adjacent | 39 | 5559 | suggested | agreed | במק | במכ | מה אפשר לעשות עם בחור כזה הוא טס כל כך מהר והכל אי אפשר אפילו לעקוב אחריו ⟦במק⟧ ם |
| b002-085 | real | adjacent | 27 | 3747 | in-text | overruled | לעור | לאור | כנראה שאתה לא יכול להכיר מישהו באמת עד ש נכנסת ⟦לעור⟧ שלו |
| b002-086 | real | adjacent | 45 | 4307 | in-text | overruled | השתן | השטן | אל תחשבי שהוא שלך רק מכיוון שסימנת אותו עם ⟦השתן⟧ שלך |
| b002-087 | real | adjacent | 22 | 6234 | in-text | overruled | ושל | בשל | עכשיו במאמץ לשפר את כל זה הכנסיה החליטה שהשנה זה הזמן להתחדשות של אמונה ⟦ושל⟧ סגנון |
| b002-088 | repeat:batch-001 | adjacent | 50 | 2322 | unclear | unclear | לע | לא | םחל תצק קר םינוונחה תא הריכמ תא אבא ⟦לע⟧ ורבדי םה |
| b002-089 | injected | known |  | 4342 | suggested | control-pass | היע | היא | אנשים ווילו עדיין שם בחוץ והיא כנראה לא יודעת מה ⟦היע⟧ עושה |
| b002-090 | real | adjacent | 35 | 4226 | in-text | overruled | בסירה | בשירה | הייתי ⟦בסירה⟧ של נג ריאן |
| b002-091 | clean | none |  | 6627 | in-text | control-pass | לא | לע | הוא לא אוהב ג וש ⟦לא⟧ אני לא |
| b002-092 | clean | none |  | 4049 | in-text | control-pass | שאני | סאני | אבל אני לא יכול שלא להרגיש כמו ⟦שאני⟧ מרגיש |
| b002-093 | real | adjacent | 29 | 3041 | in-text | overruled | ער | אר | ג נט קיוויתי שאת ⟦ער⟧ ה |
| b002-094 | injected | known |  | 433 | suggested | control-pass | לע | לא | שמע לי ⟦לע⟧ סוחבים קרון מאחורי מכסחת נוסעת |
| b002-095 | injected | known |  | 2820 | suggested | control-pass | אכד | אחד | ותזכרו אף ⟦אכד⟧ לא נוגע בפאזי |
| b002-096 | real | adjacent | 33 | 3698 | suggested | agreed | ארב | ערב | מה דעתכם על ⟦ארב⟧ אלפרט וטיחואנה בראס |
| b002-097 | real | adjacent | 22 | 2568 | in-text | overruled | בלראות | ולראות | אני לא מעוניין ⟦בלראות⟧ אותם מתים |
| b002-098 | real | adjacent | 57 | 3832 | suggested | agreed | מחסה | מכסה | הפיצוץ ⟦מחסה⟧ את הגניבה של הפלוטוניום |
| b002-099 | real | adjacent | 68 | 3824 | in-text | overruled | הבא | הוא | והפריט ⟦הבא⟧ מספר הוא מנורת סטיקלי והמכרז יחל ב דולר עכשיו עכשיו |
| b002-100 | real | adjacent | 53 | 5373 | in-text | overruled | לב | לו | אין לך שום דמיון שום ⟦לב⟧ שום ביצים אין לך כלום שם |
| b002-101 | real | adjacent | 26 | 10198 | in-text | overruled | בזה | וזה | ואז החזרת לו ⟦בזה⟧ שהתחתנת אתי והתחלת לשתות איזו נקמה |
| b002-102 | repeat:batch-001 | adjacent | 47 | 5600 | in-text | overruled | הבא | הוא | אם אני אתן למקסוול לצאת מזה אתה לא חושב שהבחור ⟦הבא⟧ ינסה לעשות את אותו הדבר |
| b002-103 | clean | none |  | 3062 | in-text | control-pass | נוסף | נושף | אני אלך ואדאג שתקבל עותק ⟦נוסף⟧ מהמזכר ההוא אוקי |
| b002-104 | clean | none |  | 3878 | in-text | control-pass | ואני | באני | רבנו כל הזמן מרי אן ⟦ואני⟧ ואביה החורג |
| b002-105 | injected | known |  | 3043 | suggested | control-pass | אוזרת | עוזרת | את בנה החורג ואת ⟦אוזרת⟧ הבית |
| b002-106 | real | adjacent | 30 | 6914 | unclear | unclear | מוטו | מותו | שבט ⟦מוטו⟧ זכה במחנה מפואר יחד עם מחסה נרחב שהיה עמוס באוכל אספקה וכלים לשם נוחיות |
| b002-107 | real | adjacent | 47 | 3653 | suggested | agreed | יחל | יכל | הוא ⟦יחל⟧ לספור אם לא תפתח |
| b002-108 | real | adjacent | 39 | 2470 | suggested | agreed | כריש | קריש | היה לי ⟦כריש⟧ דם במוח |
| b002-109 | real | adjacent | 29 | 4373 | in-text | overruled | אל | על | לכן רק שירו שיר לשלום ⟦אל⟧ תלחשו תפילה מוטב תשירו שיר לשלום בצעקה גדולה |
| b002-110 | real | adjacent | 22 | 2034 | in-text | overruled | שתף | שטף | בהחלט ⟦שתף⟧ את כולנו |
| b002-111 | real | adjacent | 56 | 6777 | unclear | unclear | לכו | לחו | הוא קפץ ל ⟦לכו⟧ ל זוז |
| b002-112 | real | adjacent | 25 | 4517 | in-text | overruled | אם | עם | אני חושש שאין הרבה כמרים באזור זה של החלל או בכל אזור ⟦אם⟧ להגיד את האמת |
| b002-113 | injected | known |  | 2658 | suggested | control-pass | יס | יש | האם ⟦יס⟧ משהו שאתה מבקש לומר לבית המשפט מר וולגרשריף |
| b002-114 | real | adjacent | 26 | 4230 | in-text | overruled | הברה | הורה | ובכן אולי לא אמרתי כל ⟦הברה⟧ קטנה וזעירה לא אבל באופן בסיסי אמרתי כן באופן בסיסי |
| b002-115 | repeat:batch-001 | adjacent | 43 | 3450 | both-fine | both-fine | הכשפים | הכספים | ראינו את ⟦הכשפים⟧ שלו |
| b002-116 | real | adjacent | 40 | 7080 | in-text | overruled | שיבת | סיבת | עם זאת הם לא חגגו את ⟦שיבת⟧ השמש לחיים עד לשוויון האביב או חג הפסחא |
| b002-117 | real | adjacent | 32 | 4200 | in-text | overruled | עין | אין | לך אני ישים ⟦עין⟧ עליו |
| b002-118 | real | adjacent | 49 | 4130 | in-text | overruled | הבא | הוא | מי יהיה המוביל ⟦הבא⟧ בקרב האיינשטיין הבא |
| b002-119 | real | adjacent | 30 | 2572 | in-text | overruled | הביטי | הביתי | לה לה לה ⟦הביטי⟧ על ראשי |
| b002-120 | real | adjacent | 25 | 2171 | in-text | overruled | תחבוש | תכבוש | אמרתי לך ⟦תחבוש⟧ את הכובע |
| b002-121 | real | adjacent | 27 | 7583 | in-text | overruled | ההאפלה | ההעפלה | זה גרם ב את ⟦ההאפלה⟧ בניו יורק |
| b002-122 | real | adjacent | 23 | 3910 | in-text | overruled | קרון | קרבן | תפתח את ⟦קרון⟧ המטען |
| b002-123 | real | adjacent | 22 | 2487 | in-text | overruled | חלום | כלום | האם אי פעם היה לכם ⟦חלום⟧ שנראה כל כך אמיתי עד שכשהתעוררתם לא ידעתם למה להאמין |
| b002-124 | real | adjacent | 24 | 4647 | suggested | agreed | משופר | מסופר | זה ⟦משופר⟧ טורבו גנרטור בתדירות נמוכה |
| b002-125 | real | adjacent | 23 | 10228 | suggested | agreed | דבר | דור | מה עוד יש לי לעזאזל ⟦דבר⟧ שיקר לי |
| b002-126 | real | adjacent | 37 | 4104 | in-text | overruled | לב | לו | אנחנו שורפים בפעימות ⟦לב⟧ דברים גדולים באים במחיר גדול מר פאצ ר |
| b002-127 | real | adjacent | 42 | 2439 | in-text | overruled | שעם | שאם | כן נראה לי ⟦שעם⟧ קצת יין אדום אפשר יהיה את נראית ממש נחמדה |
| b002-128 | real | adjacent | 21 | 6148 | in-text | overruled | שר | סר | תסתכלו יש לי ⟦שר⟧ חקלאות שבחיים לא אכל ירק |
| b002-129 | real | adjacent | 23 | 4057 | in-text | overruled | בכאב | וכאב | האם התנסת אי פעם ⟦בכאב⟧ ראש מיידי |
| b002-130 | real | adjacent | 22 | 2463 | suggested | agreed | הבריכה | הבריחה | בעיות ⟦הבריכה⟧ שלי נפתרו |
| b002-131 | real | adjacent | 54 | 1419 | both-fine | both-fine | כשפי | כספי | הן אמורות לשמור את ⟦כשפי⟧ הרוע בפנים נכון |
| b002-132 | real | adjacent | 24 | 2610 | unclear | unclear | בוש | בוס | זה ⟦בוש⟧ צ אס שוגרבוש |
| b002-133 | injected | known |  | 3463 | suggested | control-pass | לב | לו | אז תן ⟦לב⟧ לחתום |
| b002-134 | real | adjacent | 35 | 7783 | suggested | agreed | אין | עין | השנאה מתפוגגת האהבה אינה אלא תשוקה ⟦אין⟧ תחת |
| b002-135 | injected | known |  | 3484 | suggested | control-pass | אנסים | אנשים | היה מלון נחמד חוף נחמד פגשתי ⟦אנסים⟧ נחמדים |
| b002-136 | real | adjacent | 58 | 9365 | in-text | overruled | וכל | בכל | תודה על אתמול בערב ⟦וכל⟧ הקשור באימא |
| b002-137 | real | adjacent | 40 | 5947 | in-text | overruled | רעה | ראה | זאת הייתה הצהרה ⟦רעה⟧ מה שהצהרת |
| b002-138 | clean | none |  | 6979 | unclear | control-miss | מסחר | משחר | שמו סטיבן והוא מתווך ⟦מסחר⟧ ואנו גרים בדירה נהדרת במרכז העיר |
| b002-139 | real | adjacent | 32 | 4386 | both-fine | both-fine | ויתרון | ביתרון | אצטרך לוחמים ⟦ויתרון⟧ של דקות |
| b002-140 | real | adjacent | 98 | 4670 | both-fine | both-fine | שימן | סימן | הוא ⟦שימן⟧ את זה או משהו כזה |
| b002-141 | real | adjacent | 49 | 3431 | suggested | agreed | לעזעזל | לעזאזל | לך ⟦לעזעזל⟧ מני איך יכלת לעשות לי את זה |
| b002-142 | injected | known |  | 5919 | suggested | control-pass | מקן | מכן | ואני רואה לצערי שכמה ⟦מקן⟧ לא נועלת אותן אבל חלק מכן נועלות אותן |
| b002-143 | real | adjacent | 21 | 2800 | suggested | agreed | תכנית | טכנית | הוא אמר שזו ⟦תכנית⟧ על כלום |
| b002-144 | real | adjacent | 47 | 4025 | in-text | overruled | אם | עם | תקשיב טי ⟦אם⟧ הקול שלי שווה משהו |
| b002-145 | real | adjacent | 34 | 6531 | unclear | unclear | עלא | אלא | דהרמה אתה ב ⟦עלא⟧ לאחרונה עם אמא שלי |
| b002-146 | real | adjacent | 26 | 6071 | in-text | overruled | חלל | כלל | אנו עלולים ליפול מהעל ⟦חלל⟧ לפני הזמן ולהתקע בחלל הרחוק או להתאייד ברגע ש |
| b002-147 | real | adjacent | 21 | 9129 | unclear | unclear | סתום | שתום | אני יעצור אותך ברט ⟦סתום⟧ העין |
| b002-148 | repeat:batch-001 | adjacent | 28 | 2664 | in-text | overruled | עליך | אליך | קנדל אני חושב שעובר ⟦עליך⟧ עוד יום רע |
| b002-149 | real | adjacent | 51 | 3048 | in-text | overruled | נקרעה | נקראה | היא ⟦נקרעה⟧ לגזרים היא מתה מלב שבור הו טוב |
| b002-150 | real | adjacent | 31 | 6928 | both-fine | both-fine | לספל | לשפל | את זקוקה ⟦לספל⟧ של אומץ |
| b002-151 | injected | known |  | 3495 | suggested | control-pass | כן | חן | אולי הרעיון מוצא ⟦כן⟧ בעיניו |
| b002-152 | real | adjacent | 27 | 7292 | in-text | overruled | ישוט | ישות | הוא ⟦ישוט⟧ ליאדו בספינת העבדים כך שהוא יהיה שם כשתגיע |
| b002-153 | real | adjacent | 26 | 2965 | in-text | overruled | וקרע | וקרא | הוא התקיף את הילד ממש מול עיננו ⟦וקרע⟧ את החולצה שלו |
| b002-154 | clean | none |  | 1049 | in-text | control-pass | עוד | אוד | הוא לא יעבוד ⟦עוד⟧ לעולם |
| b002-155 | real | adjacent | 26 | 4929 | unclear | unclear | האול | העול | תן לי את ⟦האול⟧ ספארק ואולי תזכה לחיות כחיית המחמד שלי |
| b002-156 | repeat:batch-001 | adjacent | 35 | 4490 | in-text | overruled | מאסר | מאשר | כך שתסתכלו על הכל ותהיו צמודים למכשירי הקשר אם תמצאו משהוא תודיעו אני רוצה ⟦מאסר⟧ לא קרב יריות |
| b002-157 | injected | known |  | 11403 | suggested | control-pass | היב | היו | כל פעם שהתבצע שוד גדול ⟦היב⟧ חוקרים אותו ולא מוצאים שום דבר |
| b002-158 | repeat:batch-001 | adjacent | 35 | 2733 | in-text | overruled | לקרוע | לקרוא | כולנו צריכים ⟦לקרוע⟧ ת תחת |
| b002-159 | real | adjacent | 28 | 2891 | in-text | overruled | סולח | שולח | הוא גם אף פעם לא ⟦סולח⟧ לכל מי שעשה לו עוול |
| b002-160 | injected | known |  | 1400 | suggested | control-pass | עני | אני | זה למה ⟦עני⟧ פה |
| b002-161 | injected | known |  | 3836 | suggested | control-pass | בכוס | וכוס | אתה יודע ממש ממש מתאים לי עכשיו איזה מציצה טובה ⟦בכוס⟧ קפה חם |
| b002-162 | injected | known |  | 3547 | suggested | control-pass | לון | לבן | את מתארת צעיף משי ⟦לון⟧ בספר שלך |
| b002-163 | real | adjacent | 23 | 6158 | in-text | overruled | ומשהו | במשהו | אבל תראה יש ארבעה בנים לאקי בקוסטה מסה לבד ⟦ומשהו⟧ כמו במחוז אורנג |
| b002-164 | real | adjacent | 23 | 5820 | suggested | agreed | אוצר | עוצר | אני לא צריך להיות כאן אתה כאן ד ר וורן כי אתה ⟦אוצר⟧ מוזיאון ת ורן |
| b002-165 | real | adjacent | 22 | 10496 | in-text | overruled | ולרדוף | בלרדוף | אמא אני איש מאוד עסוק אין לי זמן לעזור לזה ⟦ולרדוף⟧ אחרי תביעות מגוכחות |
| b002-166 | injected | known |  | 3616 | suggested | control-pass | לע | לא | אתה רופא הומר אתה ⟦לע⟧ מסריח מאתר |
| b002-167 | real | adjacent | 43 | 1413 | in-text | overruled | אל | על | האם תדבר ⟦אל⟧ הוד מלכותו |
| b002-168 | injected | known |  | 2890 | suggested | control-pass | רבצה | רוצה | אני אישה עצמאית ששמה שפתון בגלל שאני ⟦רבצה⟧ לא בגלל שגברים חושבים שזה מושך יותר |
| b002-169 | real | adjacent | 33 | 3253 | in-text | overruled | וכל | בכל | גל הלם מחסל מין בודד ⟦וכל⟧ ההיסטוריה משתנה כתוצאה מכך |
| b002-170 | real | adjacent | 27 | 3761 | in-text | overruled | וחמש | בחמש | שלושה דולרים ⟦וחמש⟧ עשרה סנט |
| b002-171 | repeat:batch-001 | adjacent | 34 | 342 | in-text | overruled | הביט | הבית | שזה ⟦הביט⟧ עליהם כמחלה מדבקת בכמה גלקסיות |
| b002-172 | injected | known |  | 4740 | suggested | control-pass | אכשיו | עכשיו | לא אני חושש שאין לי אני רק ⟦אכשיו⟧ חזרתי לכאן אחרי הרבה שנים שלא הייתי פה |
| b002-173 | real | adjacent | 31 | 1510 | in-text | overruled | סולח | שולח | אל תחשבו שאני ⟦סולח⟧ לכם |
| b002-174 | real | adjacent | 32 | 2386 | in-text | overruled | לסיר | לשיר | אני מעדיפה לקפוץ ⟦לסיר⟧ עם מים רותחים |
| b002-175 | clean | none |  | 2587 | in-text | control-pass | חולנית | חבלנית | היא היתה ⟦חולנית⟧ מהרגע שפגשתי אותה |
| b002-176 | real | adjacent | 23 | 4035 | in-text | overruled | ומה | במה | אני חושב שהגיע הזמן שתבין מה זאת מוזיקה טובה ⟦ומה⟧ זאת מוזיקה גרועה |
| b002-177 | real | adjacent | 44 | 3026 | in-text | overruled | המכור | המקור | אלה לא העקבות של ⟦המכור⟧ ירא השמים המצוי |
| b002-178 | real | adjacent | 73 | 12690 | unclear | unclear | וי | בי | אתם באתם למקום הנכון אני הייתי סטודנט לתואר ראשון שנים אני יודע כל מה שצריך לדעת על ליגת הנשים של אי ⟦וי⟧ אי |
| b002-179 | repeat:batch-001 | adjacent | 25 | 3806 | in-text | overruled | ותכנון | בתכנון | וליתר דיוק מחלקת הנדסה ⟦ותכנון⟧ ערים |
| b002-180 | real | adjacent | 33 | 3662 | in-text | overruled | בצבא | בצבע | אבי נהג להיות ⟦בצבא⟧ אך כעת הוא סתם מומחה ממוצע לאיכות הסביבה |
| b002-181 | real | adjacent | 28 | 3792 | in-text | overruled | כמה | קמה | הוא שאל כמה שאלות על משחק הבייסבול ⟦כמה⟧ על הסמל |
| b002-182 | real | adjacent | 22 | 5563 | both-fine | both-fine | אבר | אור | הם תופסים כל ⟦אבר⟧ אפשרי |
| b002-183 | real | adjacent | 29 | 3696 | suggested | agreed | הכושי | הקושי | בגלל ⟦הכושי⟧ ההוא אני ארוויח מהכושי הזה |
| b002-184 | real | adjacent | 58 | 1574 | both-fine | both-fine | אשר | עשר | זה האחד ⟦אשר⟧ בחיים |
| b002-185 | real | adjacent | 32 | 21764 | in-text | overruled | החול | הכול | כמה הריסות אפשר לראות אבל ⟦החול⟧ החם והמים הכחולים זה בשבילי אני לא רוצה לזרז אתכם |
| b002-186 | real | adjacent | 29 | 2855 | in-text | overruled | משיט | מסיט | אתה ⟦משיט⟧ את הסחורה שלך בנהר |
| b002-187 | real | adjacent | 29 | 2489 | in-text | overruled | והרבה | בהרבה | שנה וחצי אחר כך ⟦והרבה⟧ דברים קרו |
| b002-188 | real | adjacent | 25 | 2931 | suggested | agreed | תביעת | טביעת | ג ון יש לך ⟦תביעת⟧ עין לכשרון |
| b002-189 | real | adjacent | 27 | 2785 | unclear | unclear | בטי | בתי | בו ⟦בטי⟧ הוא עסוק |
| b002-190 | real | adjacent | 57 | 8983 | in-text | overruled | אבד | עבד | לצערי הספר נאבד במאה ה לא ⟦אבד⟧ אלא נלקח |
| b002-191 | real | adjacent | 33 | 13385 | both-fine | both-fine | וארבעה | בארבעה | ספרטקוס זכה להצלחה ביקורתית ומסחרית ⟦וארבעה⟧ פרסי אוסקר |
| b002-192 | real | adjacent | 36 | 6494 | in-text | overruled | בחצי | וחצי | הוא זכה ⟦בחצי⟧ מיליון בהנד בול ועכשיו ברולטה |
| b002-193 | real | adjacent | 22 | 2489 | unclear | unclear | וכב | וכו | ל ⟦וכב⟧ ת ודיר מ ל ע ע מ שנ הנידמבתו מ וקמה םורדבםג ו ן ופצבםג |
| b002-194 | clean | none |  | 646 | in-text | control-pass | באתי | ואתי | איך זה היה נראה אילו ⟦באתי⟧ לבד |
| b002-195 | real | adjacent | 22 | 2924 | in-text | overruled | קרע | קרא | אמא שלי זה ⟦קרע⟧ אותה מבפנים מפני שהיא לא יכלה לעשות דבר בקשר לזה |
| b002-196 | injected | known |  | 38814 | suggested | control-pass | לאצור | לעצור | תקשיב תראה אנחנו יכולים ללכת להשיג צו לחזור עם הבולשת ולקחת כל מה שאנחנו רוצין ⟦לאצור⟧ כל מי שנרצה פשוט תן לבנאדם את הקלטת |
| b002-197 | real | adjacent | 59 | 5271 | in-text | overruled | וי | בי | אני עם ויולט כלומר הייתי עם ⟦וי⟧ לא מזמן היה לנו תינוק ביחד אזאנחנו אני לא יודע מה אנחנו |
| b002-198 | real | adjacent | 23 | 2971 | in-text | overruled | אל | על | ריי ⟦אל⟧ תידבק אליו אוקי |
| b002-199 | real | adjacent | 24 | 2402 | in-text | overruled | הבא | הוא | בבקשה ⟦הבא⟧ אותם פול והכל יוכל לחזור לאיך שהיה |
| b002-200 | real | adjacent | 29 | 2360 | unclear | unclear | תא | תע | תא של הל וכ י ⟦תא⟧ ש המ |
| b002-201 | real | adjacent | 25 | 1888 | unclear | unclear | וולי | בולי | אני מתה על ⟦וולי⟧ כדאי שאני אוהב אותו |
| b002-202 | real | adjacent | 33 | 1570 | unclear | unclear | וילי | בילי | אל תתפרק ⟦וילי⟧ אתה שומע אותי |
| b002-203 | real | adjacent | 28 | 5354 | in-text | overruled | אל | על | אבל מה כל זה אומר לנו בנוגע ⟦אל⟧ כוונותיו של הורדוס |
| b002-204 | clean | none |  | 10680 | in-text | control-pass | חזרה | כזרה | בדיוק כשחשבתי שהצלחתי לחמוק הם מושכים אותי ⟦חזרה⟧ פנימה |
| b002-205 | injected | known |  | 15295 | suggested | control-pass | ועצמך | בעצמך | אולי תעשי את זה ⟦ועצמך⟧ אורגון |
| b002-206 | real | adjacent | 72 | 67532 | in-text | overruled | אל | על | האם היא היתה מחברת מחדש את החלק ⟦אל⟧ גופה הלטאתי |
| b002-207 | real | adjacent | 29 | 6802 | unclear | unclear | הרעיה | הראיה | אני מקווה שתמצאי את ⟦הרעיה⟧ אחות העריקה שלך |
| b002-208 | real | adjacent | 47 | 3315 | in-text | overruled | חווית | כווית | השארו איתנו ל שעות של ⟦חווית⟧ פוטבול משובחת |
| b002-209 | real | adjacent | 48 | 2189 | in-text | overruled | הראיון | הרעיון | את ההצגה הרישמית שלך בשביל ⟦הראיון⟧ שלך עם המלך |
| b002-210 | real | adjacent | 65 | 3014 | unclear | unclear | וובי | בובי | ג וי לראות את ⟦וובי⟧ זה הרבה יותר כיף ממשהו שקרה ברכב של דוד שלי |
| b002-211 | real | adjacent | 22 | 5528 | in-text | overruled | וכל | בכל | אם אנחנו נחלץ אותו משם אנחנו יוצרים סערת חרא ⟦וכל⟧ הסכם השלום יתמוטט אדמירל ברונט אומר שחיילים סרבים רצחו את הטייס שלו |
| b002-212 | real | adjacent | 26 | 757 | both-fine | both-fine | שאלי | שעלי | טוב אתה יודע מה ⟦שאלי⟧ רוצה |
| b002-213 | real | adjacent | 62 | 2970 | in-text | overruled | חבשו | כבשו | אנא ⟦חבשו⟧ את המשקפיים כל הזמן |
| b002-214 | injected | known |  | 2363 | suggested | control-pass | בלא | ולא | הבטחת לקחת אותי לטהיטי ⟦בלא⟧ קיימת את זה |
| b002-215 | real | adjacent | 69 | 2016 | unclear | unclear | ון | בן | די ⟦ון⟧ דוד ג |
| b002-216 | real | adjacent | 43 | 2392 | in-text | overruled | אל | על | אחרת ⟦אל⟧ תאכלי את זה |
| b002-217 | real | adjacent | 24 | 12892 | both-fine | both-fine | נשיכה | נשיקה | זה אזור קשה שם בחוץ אתה עלול לקבל ⟦נשיכה⟧ באמצע הלילה |
| b002-218 | real | adjacent | 21 | 4005 | in-text | overruled | השאיר | השעיר | אולי הילד ⟦השאיר⟧ קצת נזלת מתחת לשלחן |
| b002-219 | real | adjacent | 22 | 5347 | in-text | overruled | מאז | מעז | בכל אופן אני ⟦מאז⟧ בכל פעם שאני מחזיקה עט אני חשה נרדפת על ידי המילים שהוא כתב |
| b002-220 | real | adjacent | 30 | 2566 | suggested | agreed | לעזעזל | לעזאזל | תעשה את זה עכשיו ⟦לעזעזל⟧ ותביא אותם אלי |
| b002-221 | real | adjacent | 45 | 3254 | in-text | overruled | הבא | הוא | האלף ⟦הבא⟧ מחכה לנו בפינה |
| b002-222 | real | adjacent | 30 | 3066 | both-fine | both-fine | קנת | קנט | מר ⟦קנת⟧ קבוצת טרוריסטים השתלטה על מגדל אייפל בפריס |
| b002-223 | real | adjacent | 24 | 2460 | suggested | agreed | לעזעזל | לעזאזל | מזל רע יד האלוהים ⟦לעזעזל⟧ אם אני יודע |
| b002-224 | real | adjacent | 22 | 7341 | in-text | overruled | רעייה | ראייה | אני רק מוזר לידוע מה העסק שלך בדלת של ⟦רעייה⟧ שלי |
| b002-225 | real | adjacent | 39 | 5490 | in-text | overruled | ספות | שפות | אני עברתי שתי ⟦ספות⟧ ב כריות ושמיכות |
| b002-226 | real | adjacent | 22 | 7868 | unclear | unclear | קי | כי | קילומטר משם ⟦קי⟧ הכין ארוחת ערב כשהוא הופרע בגסות |
| b002-227 | real | adjacent | 46 | 3591 | in-text | overruled | אל | על | נשים מתמסרות בקלות ⟦אל⟧ תירי בי |
| b002-228 | real | adjacent | 24 | 4019 | unclear | unclear | בג | וג | תתקשר לאימי ⟦בג⟧ ולייט |
| b002-229 | real | adjacent | 69 | 4185 | in-text | overruled | סאם | שאם | אמרתי לך ⟦סאם⟧ אין כזה דבר |
| b002-230 | real | adjacent | 28 | 5129 | in-text | overruled | אבד | עבד | ליבי ⟦אבד⟧ לא תכננתי להתאהב שוב אך פופ |
| b002-231 | real | adjacent | 22 | 1792 | in-text | overruled | בשלושה | ושלושה | שבעה קילו ⟦בשלושה⟧ שבועות |
| b002-232 | clean | none |  | 2077 | in-text | control-pass | רוצה | רבצה | לא ⟦רוצה⟧ לבייש את עצמי בפני עמיתיי הסוטים |
| b002-233 | injected | known |  | 1665 | suggested | control-pass | לאזאזל | לעזאזל | מה ⟦לאזאזל⟧ אתה רוצה |
| b002-234 | real | adjacent | 30 | 7087 | both-fine | both-fine | מפלטו | מפלתו | העוקצנות היא ⟦מפלטו⟧ של שכל רפה |
| b002-235 | repeat:batch-001 | adjacent | 41 | 4904 | unclear | unclear | עלו | אלו | אתה שוכח שלי הן ⟦עלו⟧ בשלי |
| b002-236 | real | adjacent | 29 | 751 | in-text | overruled | וכוח | בכוח | וסיבולת ⟦וכוח⟧ כדי לנצח במאבק ארוך |
| b002-237 | real | adjacent | 48 | 2721 | suggested | agreed | אם | עם | זה נחמד הם רוצים שאני יישאר ⟦אם⟧ הפרויקט פה |
| b002-238 | real | adjacent | 22 | 17062 | unclear | unclear | ערובה | ארובה | כפי שדיווחנו מוקדם יותר מרדף המשטרה פרוע הסתיים בטרגדיה הבוקר עם מותו של ⟦ערובה⟧ נקבה בן |
| b002-239 | real | adjacent | 22 | 6600 | in-text | overruled | ושל | בשל | עכשיו במאמץ לשפר את כל זה הכנסיה החליטה שהשנה זה הזמן להתחדשות של אמונה ⟦ושל⟧ סגנון |
| b002-240 | real | adjacent | 30 | 2729 | in-text | overruled | העור | האור | מעיל ⟦העור⟧ הזה לבד שווה ליש ט |
| b002-241 | real | adjacent | 50 | 7822 | in-text | overruled | סל | של | רואים את ברוס לי נלחם נגד שחקן הכדור ⟦סל⟧ כרים עבדול ג אבר צ אן לומד תכסיסים איך לנצח את יריבו |
| b002-242 | real | adjacent | 43 | 4230 | in-text | overruled | רוי | רבי | אבל הבלש איקס הזה האם הוא ⟦רוי⟧ וושבורן |
| b002-243 | real | adjacent | 32 | 3030 | in-text | overruled | חזה | כזה | וכשהוא הגיע לשם והמציאות לא הייתה כפי שהוא ⟦חזה⟧ אותה |
| b002-244 | real | adjacent | 65 | 1469 | in-text | overruled | קן | כן | לת ⟦קן⟧ את השמלה |
| b002-245 | real | adjacent | 36 | 3606 | unclear | unclear | פאם | פעם | בסדר ⟦פאם⟧ יש לי עוד פרוייקט בשבילך |
| b002-246 | real | adjacent | 43 | 4020 | suggested | agreed | שחל | שכל | האם אתה מודע לכך ⟦שחל⟧ איסור על שימוש בקסמים מחוץ לכותלי בית הספר כל עוד הינך מתחת לגיל |
| b002-247 | clean | none |  | 2017 | in-text | control-pass | את | עת | על מה ⟦את⟧ מדברת |
| b002-248 | real | adjacent | 30 | 7934 | suggested | agreed | הבא | הוא | עכשיו פנה שם שמאלה לפריג יה ליד הירח ⟦הבא⟧ שזורח |
| b002-249 | injected | known |  | 3990 | suggested | control-pass | מרעה | מראה | זה ⟦מרעה⟧ לנו את מקומנו בחברה |
| b002-250 | real | adjacent | 38 | 4162 | in-text | overruled | בג | וג | אני לא יתן לך לפגוע ⟦בג⟧ ני שוב טוב |
| b002-251 | real | adjacent | 47 | 6329 | unclear | unclear | בט | בת | לכי מפה ⟦בט⟧ אני לא רוצה לראות אותך |
| b002-252 | real | adjacent | 31 | 25192 | in-text | overruled | שוד | סוד | כל פעם שהתבצע ⟦שוד⟧ גדול היו חוקרים אותו ולא מוצאים שום דבר |
| b002-253 | repeat:batch-001 | adjacent | 23 | 5032 | both-fine | both-fine | יספרו | ישפרו | אתה יודע יום אחד ⟦יספרו⟧ את הסיפור |
| b002-254 | real | adjacent | 39 | 5460 | in-text | overruled | העורקים | העורכים | לחדר הימני ודרך ⟦העורקים⟧ של הריאות אל הריאות יש המרה בין דו תחמוצת הפחמן לחמצן |
| b002-255 | real | adjacent | 34 | 5451 | in-text | overruled | לשדר | לסדר | והוא מנסה ⟦לשדר⟧ בשפת הגוף שלו שחק על הכל |
| b002-256 | repeat:batch-001 | adjacent | 28 | 4582 | in-text | overruled | ואותו | באותו | אל תששש אותי ⟦ואותו⟧ לא |
| b002-257 | clean | none |  | 4109 | in-text | control-pass | שלו | סלו | מתוך מגירת שולחן הכתיבה ⟦שלו⟧ אתה יודע |
| b002-258 | injected | known |  | 1537 | unclear | control-miss | לע | לא | הנ ה את ה ⟦לע⟧ מרגיש יותר טוב |
| b002-259 | real | adjacent | 32 | 1529 | unclear | unclear | אל | על | מה טריניטרון בשבילך ⟦אל⟧ תיבת דואר ספאדוצ י |
| b002-260 | real | adjacent | 27 | 4694 | both-fine | both-fine | שלו | שלב | שהשיג את השיניים הטוחנות ⟦שלו⟧ מוקדם יותר בחייו להתחיל לאהוב זה מיסתורין נפלא |
| b002-261 | real | adjacent | 21 | 57488 | in-text | overruled | הגיסה | הגישה | זהו פולחן עבור זה משהו שכל אמא הגיסה שולחת לבת ⟦הגיסה⟧ שלה |
| b002-262 | real | adjacent | 58 | 2099 | in-text | overruled | אשר | עשר | זה האחד ⟦אשר⟧ בחיים |
| b002-263 | real | adjacent | 25 | 9368 | unclear | unclear | סטי | סתי | הבית של ⟦סטי⟧ עיר המתים |
| b002-264 | injected | known |  | 1386 | suggested | control-pass | עותי | אותי | אז תודה שהזמנת ⟦עותי⟧ לכאן |
| b002-265 | real | adjacent | 22 | 6412 | unclear | unclear | הכושי | הקושי | נינו שיבטה מאפיונר מזדיין משוחח עם ⟦הכושי⟧ מהאיסלאם |
| b002-266 | real | adjacent | 24 | 2661 | unclear | unclear | אסרת | אשרת | מדוע ⟦אסרת⟧ את אחת מהאחיות שלי |
| b002-267 | real | adjacent | 31 | 2197 | in-text | overruled | בזה | וזה | לא צפית ⟦בזה⟧ עדיין |
| b002-268 | injected | known |  | 3000 | both-fine | control-miss | היב | היו | הם ⟦היב⟧ גם חברים שלך |
| b002-269 | real | adjacent | 45 | 9173 | in-text | overruled | טפסו | תפסו | הם ⟦טפסו⟧ בתעלות האוורור |
| b002-270 | real | adjacent | 49 | 3172 | in-text | overruled | בלהציל | ולהציל | אשמים ⟦בלהציל⟧ את החיים שלך |
| b002-271 | real | adjacent | 24 | 8218 | unclear | unclear | הקס | הכס | מחזורים של ⟦הקס⟧ ווין במינון מלא |
| b002-272 | real | adjacent | 59 | 4432 | both-fine | both-fine | הסם | השם | כך תקבל את מנת ⟦הסם⟧ שלך |
| b002-273 | real | adjacent | 38 | 3884 | in-text | overruled | להציא | להציע | כדי ⟦להציא⟧ אותם החוצה היו צריכים לזהות |
| b002-274 | real | adjacent | 22 | 3479 | suggested | agreed | ועם | ואם | לפתע אתה כאן ⟦ועם⟧ המנגינה עליכם להקשיב אחד לשניה היטב כן |
| b002-275 | clean | none |  | 6712 | in-text | control-pass | יש | יס | אם לאויב שלך ⟦יש⟧ מספרים גדולים ממך אתה הפרד ומשול לצמצם את המספרים האלה |
| b002-276 | real | adjacent | 40 | 5212 | in-text | overruled | הכומר | החומר | בגלל זה ⟦הכומר⟧ שפך עלי מים קדושים |
| b002-277 | real | adjacent | 106 | 7942 | in-text | overruled | חי | כי | הוא נשאר עד ⟦חי⟧ למרות הברבריות של התליינים |
| b002-278 | clean | none |  | 31769 | in-text | control-pass | הארוכות | הארוחות | וצ נדלר אתה תצטרך להיזהר עם המקלחות ⟦הארוכות⟧ שלך בבוקר |
| b002-279 | real | adjacent | 24 | 546 | in-text | overruled | עז | אז | אה איש שנתן את ⟦עז⟧ מהמכלאה שלו |
| b002-280 | real | adjacent | 24 | 3255 | in-text | overruled | חלום | כלום | הם ⟦חלום⟧ הבלהות של בני הנעורים והם לא משרתים שום מטרה מועילה |
| b002-281 | real | adjacent | 22 | 11482 | in-text | overruled | הפרת | הפרט | אתם עצורים עבור ⟦הפרת⟧ קוד תחנה סעיף קטן בטא |
| b002-282 | real | adjacent | 24 | 2234 | in-text | overruled | בי | וי | היו עושים ⟦בי⟧ שם מעשי סדום |
| b002-283 | real | adjacent | 48 | 7020 | in-text | overruled | תכף | תקף | אוקיי אני כמעט רואה את החוד הוא ⟦תכף⟧ בחוץ |
| b002-284 | real | adjacent | 55 | 3359 | suggested | agreed | תצתרך | תצטרך | אתה ⟦תצתרך⟧ אנחנו נקח את זה מכאן |
| b002-285 | real | adjacent | 33 | 9606 | in-text | overruled | בלשלוח | ולשלוח | ל אפ טי אל אין מושג מה הם גילו אחרת הם לא היה מסתכנים ⟦בלשלוח⟧ את זה לאזרחים לניתוח |
| b002-286 | real | adjacent | 33 | 20782 | in-text | overruled | סלחתי | שלחתי | אני לא יכול לומר בכל הכנות שאי פעם ⟦סלחתי⟧ לו אדוני |
| b002-287 | real | adjacent | 31 | 3420 | in-text | overruled | עם | אם | בסדר אתם רוצים לדעת למה אני לא יכול לעזור לדי ג יי ⟦עם⟧ הקמפיין שלה |
| b002-288 | real | adjacent | 34 | 4926 | in-text | overruled | בלשים | ולשים | מה שאתה צריך לעשות זה להתרכז ⟦בלשים⟧ את כוח התחת בידיו של הישות הפשוטה שלך |
| b002-289 | clean | none |  | 5993 | in-text | control-pass | אם | עם | אשתי בפועל גם ⟦אם⟧ לא רשמית |
| b002-290 | real | adjacent | 45 | 4500 | in-text | overruled | בלמצוא | ולמצוא | מה לא בסדר ⟦בלמצוא⟧ את ישו הא |
| b002-291 | real | adjacent | 21 | 7649 | unclear | unclear | מפריס | מפריש | הוא ⟦מפריס⟧ במאי ממש מדהים |
| b002-292 | real | adjacent | 44 | 4582 | in-text | overruled | חי | כי | אורניזם ⟦חי⟧ אולי משהו בסגנון מחשב מבוסס די אנ איי |
| b002-293 | real | adjacent | 41 | 11701 | both-fine | both-fine | בחלל | בכלל | אתם צריכים להפעיל את הפצצה מרחוק לפני שהאסטרואיד יעבור מעל השטח הזה ⟦בחלל⟧ מחסום אפס |
| b002-294 | real | adjacent | 21 | 4918 | in-text | overruled | יקרע | יקרא | אם סטנלי שם אני ⟦יקרע⟧ לו את התחת |
| b002-295 | real | adjacent | 38 | 1769 | both-fine | both-fine | אם | עם | אני חייב לכדרר ⟦אם⟧ הכדור אצלך אתה סתם תנסה לקלוע |
| b002-296 | real | adjacent | 45 | 3210 | in-text | overruled | הבא | הוא | להיפך אני אומר ⟦הבא⟧ נעניק לו פרס |
| b002-297 | real | adjacent | 34 | 3539 | suggested | agreed | לטבוע | לתבוע | גבר יכול ⟦לטבוע⟧ בעיניים האלו |
| b002-298 | real | adjacent | 25 | 2227 | unclear | unclear | וק | וכ | ן ו מ עפ ל ⟦וק⟧ ה יה י טעמ ד ו ע |
| b002-299 | clean | none |  | 1739 | in-text | control-pass | את | עת | איני מבינה ⟦את⟧ המהומה |
| b002-300 | real | adjacent | 35 | 4927 | in-text | overruled | אל | על | לא לא כדי להגיע לביתו של נד כדאי שתפנה ימינה ותחזור ⟦אל⟧ הגבעות |
| b002-301 | real | adjacent | 25 | 6578 | in-text | overruled | ועוד | בעוד | אלף עכשיו ⟦ועוד⟧ אלף אחרי החתונה |
| b002-302 | real | adjacent | 37 | 1491 | in-text | overruled | אליך | עליך | אם לא ⟦אליך⟧ למי אפנה |
| b002-303 | real | adjacent | 30 | 3247 | in-text | overruled | עם | אם | היית צריך לדעת שזה מה שיקרה ⟦עם⟧ מגאזין כזה |
| b002-304 | real | adjacent | 24 | 3929 | suggested | agreed | עברי | עורי | הייתי חייב לחשוף את ⟦עברי⟧ קודם |
| b002-305 | real | adjacent | 38 | 884 | unclear | unclear | בט | בת | אז ⟦בט⟧ תשיר שני שירים |
| b002-306 | real | adjacent | 27 | 6142 | in-text | overruled | על | אל | איזה סכום כסף להוציא ⟦על⟧ הזר |
| b002-307 | real | adjacent | 52 | 4026 | in-text | overruled | עברך | עורך | אביה אינו יודע על ⟦עברך⟧ נכון |
| b002-308 | real | adjacent | 22 | 9968 | in-text | overruled | כבר | חבר | אתה יכול תמיד לחזות את המנצחים ⟦כבר⟧ בקו הזינוק |
| b002-309 | clean | none |  | 9569 | in-text | control-pass | קחי | כחי | תצאי מהבניין לימינך ⟦קחי⟧ את האוטובוס הראשון |
| b002-310 | real | adjacent | 34 | 7559 | in-text | overruled | אל | על | אני מעדיף לחשוב על זה כעל רווח הדדי שדוחף ⟦אל⟧ הכיוון הנכון |
| b002-311 | real | adjacent | 36 | 2264 | in-text | overruled | סוכר | סוקר | הוא אהב את זה זה כמו לפזר אבקת ⟦סוכר⟧ על סופגנייה |
| b002-312 | real | adjacent | 30 | 3773 | in-text | overruled | כה | קה | יש ברשותי תדפיס סודי ביותר של פרטי המכירות של חנות ספרים ⟦כה⟧ חסרת חשיבות אך כה מלאה בצדקנות עד שמיד חשתי אליה |
| b002-313 | real | adjacent | 22 | 4229 | unclear | unclear | ול | בל | לתלותמו הפי רעיש ⟦ול⟧ היה |
| b002-314 | injected | known |  | 15704 | suggested | control-pass | אד | עד | ושלא יגלו את הגופה ⟦אד⟧ היום הבא |
| b002-315 | repeat:batch-001 | adjacent | 22 | 7289 | unclear | unclear | מולי | מבלי | החברה שלו ⟦מולי⟧ חולה |
| b003-001 | repeat:batch-001 | adjacent | 21 | 15198 | both-fine | both-fine | חבר | כבר | תעשה סיבוב ⟦חבר⟧ תעשה סיבוב |
| b003-002 | repeat:batch-001 | adjacent | 45 | 10926 | in-text | overruled | הבא | הוא | כן היום זה יום ⟦הבא⟧ לעבודה קרוב משפחה רחוק |
| b003-003 | repeat:batch-002 | adjacent | 26 | 2453 | in-text | overruled | אליי | עליי | אל תתקרבי ⟦אליי⟧ מפני שאני לא רוצה לפגוע בך |
| b003-004 | repeat:batch-002 | adjacent | 39 | 5414 | unclear | unclear | בג | וג | אנחנו לא יכולות לבטוח ⟦בג⟧ ונתן |
| b003-005 | repeat:batch-001 | adjacent | 38 | 10313 | in-text | overruled | לסוך | לסבך | באופן רגיל פועלות צינוריות הדמעות כדי ⟦לסוך⟧ את העין ולהגן עליה כשמתרגשים הן מגיבות באופן מוגזם ויוצרות דמעות |
| b003-006 | repeat:batch-002 | adjacent | 24 | 4514 | unclear | unclear | בג | וג | תתקשר לאימי ⟦בג⟧ ולייט |
| b003-007 | repeat:batch-002 | adjacent | 31 | 5835 | in-text | overruled | באולם | בעולם | האקוסטיקה שם דומה לאקוסטיקה ⟦באולם⟧ ו תראו מי הופיעה |
| b003-008 | repeat:batch-002 | adjacent | 90 | 3644 | both-fine | both-fine | לסחרר | לשחרר | כדי ⟦לסחרר⟧ אותי כמוך |
| b003-009 | repeat:batch-001 | adjacent | 67 | 3742 | both-fine | both-fine | הגור | הגבר | של מי ⟦הגור⟧ הזה |
| b003-010 | clean | none |  | 10396 | in-text | control-pass | אני | עני | לא ⟦אני⟧ התכוונתי בלי הכסף |
| b003-011 | repeat:batch-002 | adjacent | 106 | 5781 | in-text | overruled | חי | כי | הוא נשאר עד ⟦חי⟧ למרות הברבריות של התליינים |
| b003-012 | repeat:batch-001 | adjacent | 25 | 1882 | in-text | overruled | בלב | בלו | לפחות נימוסי הרופא שלך משאירים תקווה ⟦בלב⟧ ג ק |
| b003-013 | clean | none |  | 1897 | in-text | control-pass | חשבתי | כשבתי | אני מניחה שאף פעם לא ⟦חשבתי⟧ על זה ככה |
| b003-014 | repeat:batch-002 | adjacent | 21 | 12134 | unclear | unclear | הגיסה | הגישה | זהו פולחן עבור זה משהו שכל אמא הגיסה שולחת לבת ⟦הגיסה⟧ שלה |
| b003-015 | repeat:batch-002 | adjacent | 72 | 8092 | in-text | overruled | אל | על | האם היא היתה מחברת מחדש את החלק ⟦אל⟧ גופה הלטאתי |
| b003-016 | repeat:batch-002 | adjacent | 34 | 2122 | in-text | overruled | לטבוע | לתבוע | גבר יכול ⟦לטבוע⟧ בעיניים האלו |
| b003-017 | repeat:batch-002 | adjacent | 29 | 2834 | in-text | overruled | אל | על | לכן רק שירו שיר לשלום ⟦אל⟧ תלחשו תפילה מוטב תשירו שיר לשלום בצעקה גדולה |
| b003-018 | repeat:batch-002 | adjacent | 65 | 6832 | suggested | agreed | וובי | בובי | ג וי לראות את ⟦וובי⟧ זה הרבה יותר כיף ממשהו שקרה ברכב של דוד שלי |
| b003-019 | repeat:batch-002 | adjacent | 22 | 3719 | both-fine | both-fine | שתף | שטף | בהחלט ⟦שתף⟧ את כולנו |
| b003-020 | repeat:batch-001 | adjacent | 22 | 2130 | in-text | overruled | אל | על | ולנסות ולהיות נחמד ⟦אל⟧ מלקולם |
| b003-021 | repeat:batch-002 | adjacent | 30 | 5887 | in-text | overruled | כה | קה | יש ברשותי תדפיס סודי ביותר של פרטי המכירות של חנות ספרים ⟦כה⟧ חסרת חשיבות אך כה מלאה בצדקנות עד שמיד חשתי אליה |
| b003-022 | repeat:batch-002 | adjacent | 23 | 2849 | in-text | overruled | בכאב | וכאב | האם התנסת אי פעם ⟦בכאב⟧ ראש מיידי |
| b003-023 | repeat:batch-001 | adjacent | 21 | 4492 | unclear | unclear | וקטע | בקטע | אהבה ⟦וקטע⟧ עם כלב |
| b003-024 | repeat:batch-002 | adjacent | 32 | 4935 | in-text | overruled | החול | הכול | כמה הריסות אפשר לראות אבל ⟦החול⟧ החם והמים הכחולים זה בשבילי אני לא רוצה לזרז אתכם |
| b003-025 | repeat:batch-002 | adjacent | 21 | 6690 | in-text | overruled | ואנשים | באנשים | אני מסכים ⟦ואנשים⟧ צעירים ותמימים צריכים להודות על מזלם להיות אסירי תודה |
| b003-026 | repeat:batch-001 | adjacent | 37 | 5580 | in-text | overruled | ושנית | בשנית | קודם כן זה פשע של זעם לא משיכה מינית ⟦ושנית⟧ את לא דוחה את מאוד יפה |
| b003-027 | repeat:batch-002 | adjacent | 32 | 8174 | in-text | overruled | ועף | ואף | עוזב את הגלגל של ב ⟦ועף⟧ אל הגלגל של אופניים א קדימה ואחורה עד ששני זוגות האופניים מתנגשים |
| b003-028 | repeat:batch-002 | adjacent | 57 | 22447 | in-text | overruled | אבד | עבד | לצערי הספר נאבד במאה ה לא ⟦אבד⟧ אלא נלקח |
| b003-029 | repeat:batch-002 | adjacent | 26 | 2625 | in-text | overruled | אל | על | פקסס עותק של הצו ⟦אל⟧ הפקיד |
| b003-030 | injected | known |  | 2865 | suggested | control-pass | אוהו | אוהב | אם אתה לא ⟦אוהו⟧ ספגטי וקציצת בשר צא החוצה |
| b003-031 | repeat:batch-001 | adjacent | 63 | 4432 | suggested | agreed | יעבד | יאבד | אנדרו עבד עבורי במשך שנה אף אחד לא ⟦יעבד⟧ את מקום פרנסתו בטח שאין חשש בלבצע עידכונים כאלה |
| b003-032 | injected | known |  | 1626 | suggested | control-pass | עת | את | יש לך ⟦עת⟧ המשקה שלי לא |
| b003-033 | repeat:batch-002 | adjacent | 45 | 3254 | in-text | overruled | השתן | השטן | אל תחשבי שהוא שלך רק מכיוון שסימנת אותו עם ⟦השתן⟧ שלך |
| b003-034 | clean | none |  | 4096 | in-text | control-pass | בני | וני | מה חשבת שהם יכולים לאלץ ⟦בני⟧ אדם להישאר כאן |
| b003-035 | repeat:batch-002 | adjacent | 55 | 2407 | suggested | agreed | תצתרך | תצטרך | אתה ⟦תצתרך⟧ אנחנו נקח את זה מכאן |
| b003-036 | injected | known |  | 4432 | suggested | control-pass | סלי | שלי | אם תשמור על הפה שלך אני אשמור על הסבלנות ⟦סלי⟧ ברור |
| b003-037 | repeat:batch-001 | adjacent | 48 | 3652 | in-text | overruled | עבד | עוד | עכשיו לאחר שהוא טעם בשר אדם הכלב יהיה ⟦עבד⟧ לתאוות הבשר שלו |
| b003-038 | repeat:batch-001 | adjacent | 35 | 3982 | in-text | overruled | אל | על | לא לא כדי להגיע לביתו של נד כדאי שתפנה ימינה ותחזור ⟦אל⟧ הגבעות |
| b003-039 | repeat:batch-002 | adjacent | 38 | 2631 | in-text | overruled | המסטר | המשטר | זה בלתי נמנע שהתלמידים יתעלו על ⟦המסטר⟧ זה הגורל בני אנוש לא יכולים להתנגד לו |
| b003-040 | repeat:batch-002 | adjacent | 22 | 5917 | in-text | overruled | ולרדוף | בלרדוף | אמא אני איש מאוד עסוק אין לי זמן לעזור לזה ⟦ולרדוף⟧ אחרי תביעות מגוכחות |
| b003-041 | repeat:batch-002 | adjacent | 38 | 5459 | unclear | unclear | בט | בת | אז ⟦בט⟧ תשיר שני שירים |
| b003-042 | repeat:batch-002 | adjacent | 26 | 5639 | unclear | unclear | שאלי | שעלי | טוב אתה יודע מה ⟦שאלי⟧ רוצה |
| b003-043 | injected | known |  | 8529 | suggested | control-pass | הבא | הוא | ועכשיו עם החיים שאני תמיד רציתי בשבילו ⟦הבא⟧ עוזב את הבית |
| b003-044 | repeat:batch-002 | adjacent | 29 | 4657 | in-text | overruled | וכוח | בכוח | וסיבולת ⟦וכוח⟧ כדי לנצח במאבק ארוך |
| b003-045 | clean | none |  | 3351 | in-text | control-pass | את | עת | בואו נשמור ⟦את⟧ הידיים והזרועות שלנו לעצמנו בסדר |
| b003-046 | repeat:batch-002 | adjacent | 25 | 3427 | in-text | overruled | ועוד | בעוד | אלף עכשיו ⟦ועוד⟧ אלף אחרי החתונה |
| b003-047 | clean | none |  | 2408 | in-text | control-pass | כמו | חמו | להשאיר אותך בוערת ⟦כמו⟧ כלב מגורה |
| b003-048 | repeat:batch-002 | adjacent | 44 | 2789 | in-text | overruled | חי | כי | אורניזם ⟦חי⟧ אולי משהו בסגנון מחשב מבוסס די אנ איי |
| b003-049 | injected | known |  | 1927 | suggested | control-pass | כשף | כסף | שפך ⟦כשף⟧ כמו מים אבל במשחק המודרני |
| b003-050 | repeat:batch-001 | adjacent | 39 | 3997 | unclear | unclear | טא | תא | מס התותח הראשי הוא בחוץ ואני השקנו כל ⟦טא⟧ אה |

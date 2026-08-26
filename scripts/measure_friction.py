"""H1 — the friction inventory. Descriptive counts. Nothing is chosen and nothing is adopted.

### Why this exists
Every measurement in this repository so far asks the same question: given a position in a
corpus, is the target word in the top three. That question was never derived from a purpose --
it was inherited from what keyboards do. The purpose, fixed by the operator on 2026-08-25, is
**typing Hebrew without fighting the language**, and nothing here has ever counted where the
language actually makes a person stumble.

So this counts the frictions themselves, on the two held-out slices everything else is measured
on, with denominators.

### Two numbers are UPPER BOUNDS and are labelled as such
- **Prefix chains** are found by a heuristic, not a morphological analysis: up to three leading
  characters drawn from the agglutinative set, leaving a stem of two or more that is in the
  lexicon. It over-counts. `מים` decomposes as `מ` + `ים`, and `ים` is a word. A real analyser
  would not make that mistake, and this project does not have one -- which is itself a finding.
- **Ktiv twins** are counted as "deleting one `ו` or `י` yields another lexicon word". That is
  broader than true ktiv male/haser variation: removing the `י` from `בית` gives `בת`, a
  different word rather than a spelling of the same one. What it measures is the *density of
  real-word neighbours one mater lectionis away*, which is the quantity that matters for a
  correction engine, and it is reported under that description rather than the narrower one.

### What it does NOT cover, and why the zeros below are not findings
Both corpora are Hebrew-letters-only **by construction**: `build_subtitle_corpus.py` keeps only
`[א-ת]+` runs and `build_eval_corpus.py` does the same. Latin characters, digits, geresh,
gershayim and all punctuation are discarded before a single token is written. The 0.00% rows
are therefore a property of the regex, not of Hebrew. Every friction that involves a character
which is not a Hebrew letter is invisible to every corpus in this repository.
"""
import gzip, sys, re, collections

LEX = set(w for w in gzip.decompress(open('lexicon/assets/he_lexicon.txt.gz','rb').read())
          .decode('utf-8').split('\n') if w)

HEB = 'אבגדהוזחטיכלמנסעפצקרשתךםןףץ'
FINALS = {'כ':'ך','מ':'ם','נ':'ן','פ':'ף','צ':'ץ'}
NONFINAL = {v:k for k,v in FINALS.items()}
PREFIXES = ['ו','ה','ב','כ','ל','מ','ש']
GERSH = '׳״"\''

def slices():
    for name in ('he_conversational_test.txt.gz','hewiki_eval_sample.txt.gz'):
        raw = gzip.decompress(open(f'lexicon/eval/{name}','rb').read()).decode('utf-8')
        yield name, [l.split(' ') for l in raw.split('\n') if l.strip()]

def strip_prefixes(tok):
    """Every prefix chain that leaves a stem of >=2 chars that is in the lexicon."""
    for n in (3, 2, 1):
        if len(tok) > n + 1 and all(c in PREFIXES for c in tok[:n]):
            stem = tok[n:]
            if stem in LEX:
                return tok[:n], stem
    return None

for name, sents in slices():
    c = collections.Counter()
    tokens = 0
    for s in sents:
        for i, tok in enumerate(s):
            tokens += 1
            heb = [ch for ch in tok if ch in HEB]
            has_latin = any('a' <= ch.lower() <= 'z' for ch in tok)
            has_digit = any(ch.isdigit() for ch in tok)
            if has_latin: c['latin'] += 1
            if has_digit: c['digit'] += 1
            if has_latin or has_digit: c['mixed_script'] += 1
            if any(ch in GERSH for ch in tok): c['gershayim'] += 1
            if not heb: continue
            c['hebrew_tokens'] += 1
            if tok in LEX:
                c['in_lexicon'] += 1
            else:
                c['oov_surface'] += 1
                sp = strip_prefixes(tok)
                if sp: c['oov_but_prefixed'] += 1
            sp = strip_prefixes(tok)
            if sp:
                c['prefixed'] += 1
                if tok in LEX: c['prefixed_and_in_lexicon'] += 1
            last = heb[-1]
            if last in NONFINAL: c['ends_final_form'] += 1
            if last in FINALS:
                c['ends_nonfinal_letter'] += 1
                if tok[:-1] + FINALS[last] in LEX: c['final_form_would_be_a_word'] += 1
            # ktiv: does adding/removing a mater lectionis yield another lexicon word?
            for j, ch in enumerate(tok):
                if ch in 'וי' and tok[:j] + tok[j+1:] in LEX and tok in LEX:
                    c['ktiv_pair'] += 1
                    break

    print(f"\n===== {name}  —  {tokens:,} tokens, {c['hebrew_tokens']:,} of them Hebrew")
    d = c['hebrew_tokens']
    def pct(k, den=None):
        den = den or d
        return f"{c[k]:>7,}  {100.0*c[k]/den:6.2f}%"
    print(f"  in lexicon as a surface form      {pct('in_lexicon')}")
    print(f"  NOT in lexicon (OOV surface)      {pct('oov_surface')}")
    print(f"    of those, a prefix chain fixes  {c['oov_but_prefixed']:>7,}  "
          f"{100.0*c['oov_but_prefixed']/max(c['oov_surface'],1):6.2f}% of OOV")
    print(f"  carries an agglutinated prefix    {pct('prefixed')}")
    print(f"    and is ALSO a surface form      {pct('prefixed_and_in_lexicon')}")
    print(f"  ends in a final form              {pct('ends_final_form')}")
    print(f"  ends in a non-final letter        {pct('ends_nonfinal_letter')}")
    print(f"    where the final form IS a word  {pct('final_form_would_be_a_word')}")
    print(f"  has a ktiv male/haser twin        {pct('ktiv_pair')}")
    print(f"  --- over ALL {tokens:,} tokens, not just Hebrew ones ---")
    print(f"  mixes script (latin or digit)     {pct('mixed_script', tokens)}")
    print(f"    latin                           {pct('latin', tokens)}")
    print(f"    digit                           {pct('digit', tokens)}")
    print(f"  contains geresh/gershayim         {pct('gershayim', tokens)}")

#!/usr/bin/env bash
# Regenerate every headline number in docs/FINDINGS.md from committed inputs.
#
# Nothing here is a summary of a result. Each block runs the harness that produced the number,
# and each harness prints its own positive control first -- if a control comes back green, the
# number below it is void and the harness says so.
#
# Two blocks need the network and say so. Nothing needs a device, and that is a limitation
# rather than a feature: every claim in FINDINGS.md is a claim about an algorithm or a corpus,
# and none of them is a claim about a phone.
set -u

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
: "${TYPED_DIR:=}"          # clone of omilab/Neural-Sentiment-Analyzer-for-Modern-Hebrew/data
: "${HEWIKI_TITLES:=}"      # hewiki-latest-all-titles-in-ns0.gz
FAILED=0

step() { printf '\n\033[1m=== %s\033[0m\n' "$1"; }
skip() { printf '    SKIPPED: %s\n' "$1"; }
run()  { echo "    \$ $*"; "$@" || { FAILED=1; echo "    ^ FAILED"; }; }

step "0. The gates, each with its own control demonstrated red in the same run"
run python3 scripts/run_gates.py

step "1. A1 - transcription and typing do not share an alphabet   [needs network]"
if [ -n "$TYPED_DIR" ]; then
  run python3 scripts/measure_alphabet.py --typed-dir "$TYPED_DIR" --subtitle-lines 3000000
else
  skip "set TYPED_DIR to the Amram et al. data directory (MIT)"
fi

step "2. A2 - what the out-of-lexicon gap is made of, and what would close it"
if [ -n "$HEWIKI_TITLES" ]; then
  run python3 scripts/measure_oov.py --titles "$HEWIKI_TITLES"
else
  run python3 scripts/measure_oov.py
  skip "set HEWIKI_TITLES for the Wikipedia-titles row; the rest is measured"
fi

step "3. W1 - prediction by register, on the first slice a person typed"
run ./gradlew --no-daemon :core:test --tests '*TypedRegisterTest*' -PrunTypedRegister=1

step "4. B2 + W6 - bidi arms, including the one AOSP actually ships for Hebrew"
run ./gradlew --no-daemon :core:test --tests '*BidiArmsTest*' -PrunBidiArms=1

step "5. W8 - spelling correction by source register"
run ./gradlew --no-daemon :core:test --tests '*CorrectionRegisterTest*' -PrunCorrectionRegister=1

step "6. H1 - the friction inventory, derived from the stated purpose"
run python3 scripts/measure_friction.py

printf '\n'
if [ "$FAILED" -eq 0 ]; then
  echo "All blocks completed. Read each harness's control line before its numbers."
else
  echo "At least one block FAILED. A failed block's numbers are not evidence."
fi
exit "$FAILED"

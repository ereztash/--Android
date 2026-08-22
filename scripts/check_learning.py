#!/usr/bin/env python3
"""GATE-LEARN-1 and GATE-LEARN-2 -- the two properties adaptive learning promises.

  learn_schema   The persisted form is counts over integer ids. No encoder in the codec
                 accepts a String or CharSequence, and no field of the stored record can hold
                 a character. "Never persist raw text" is enforced by there being nothing to
                 write text with, not by remembering the rule at each call site.

  learn_guard    Exactly one file calls into the learning API, and that call is guarded by
                 `session.mayLearn`. The flag has existed since M4; this checks it is read.

### Why a static gate rather than only a test

A test proves the code does the right thing on the inputs the test supplies. It cannot prove
that no OTHER call site exists, and a second unguarded `learn(...)` added later in a different
file is exactly the change that would pass every existing test while breaking the promise. That
is a question about the whole tree, which is what this file is for.

### What this gate does NOT cover

Listed in NOT_COVERED and printed with every run.
"""
from __future__ import annotations

import argparse
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gatelib import Detector, Finding, GateResult, report  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

CODEC = os.path.join("core", "src", "main", "kotlin", "com", "hebrewime", "core",
                     "learning", "UserNgramCodec.kt")
MODEL = os.path.join("core", "src", "main", "kotlin", "com", "hebrewime", "core",
                     "learning", "UserNgramModel.kt")

# A signature in the codec or the model that could carry text. `record(first: Int, second: Int)`
# is fine; `record(word: String)` is the whole failure this gate exists for.
TEXT_TYPES = re.compile(r":\s*(String|CharSequence|StringBuilder)\b")

# The guard that must sit above the single learn call site.
GUARD = re.compile(r"if\s*\(\s*!\s*session\.mayLearn\s*\)\s*return")
LEARN_CALL = re.compile(r"\bcorrection\.learn\s*\(")


def source_files(root: str) -> list[str]:
    out = []
    for base, dirs, files in os.walk(root):
        dirs[:] = [d for d in dirs if d not in ("build", ".git", ".gradle")]
        for f in files:
            if f.endswith(".kt"):
                out.append(os.path.join(base, f))
    return sorted(out)


def check_schema(root: str, inject: bool) -> Detector:
    det = Detector(name="learn_schema", unit="learning source files", denominator=0)
    targets = [os.path.join(root, CODEC), os.path.join(root, MODEL)]
    present = [t for t in targets if os.path.isfile(t)]
    if not present:
        det.notes.append("no learning sources found; NOT-MEASURED")
        return det
    det.denominator = len(present)

    for path in present:
        text = open(path, encoding="utf-8").read()
        if inject and path.endswith("UserNgramCodec.kt"):
            # PLANTED DEFECT: a field that can hold what the user typed. This is precisely the
            # change that would turn a count store into a keystroke log.
            text += "\n    fun encodeWord(word: String): ByteArray = word.toByteArray()\n"
        # Strip comments before matching, or the prose explaining the rule trips the rule.
        stripped = re.sub(r"/\*.*?\*/", "", text, flags=re.DOTALL)
        stripped = re.sub(r"//.*", "", stripped)
        for m in TEXT_TYPES.finditer(stripped):
            line = stripped[:m.start()].count("\n") + 1
            det.findings.append(Finding(
                "learn_schema", os.path.relpath(path, root), line,
                f"a {m.group(1)} appears in the learning store's API surface; the persisted "
                f"form must be counts over integer ids and nothing that can hold text",
                "learn.text_in_schema"))
    det.notes.append(f"checked {len(present)} files for text-bearing signatures")
    det.notes.append("the stored record is (first, second, count, sessions), four ints")
    return det


# The diagnostic stores. These persist NUMBERS about how the keyboard behaved. A putString
# here is the same failure as a String in the learning codec, wearing a different hat: a
# diagnostic that writes text to disk is a keystroke log with a helpful name.
#
# One string key is allowed by name and only by name: ImeDiagnostics records the CLASS NAME of
# a throwable so a load failure can be told from a restricted field. That is a fixed set of
# Java identifiers chosen by the exception, never anything derived from input. Everything else
# that writes a string is a finding.
DIAGNOSTIC_STORES = [
    "app/src/main/kotlin/com/hebrewime/diagnostics/DeviceEvidence.kt",
    "app/src/main/kotlin/com/hebrewime/diagnostics/ImeDiagnostics.kt",
]
# Matches the KEY argument whatever form it takes. An earlier version matched only a
# bare identifier, so `putString("last_word", w)` -- a literal key, the most likely
# way anyone would actually add one -- walked straight through. The control caught
# it: the planted defect used a literal and the gate stayed green, which is the whole
# reason a control runs before the gate it belongs to.
PUT_STRING = re.compile(r"\bputString\s*\(\s*([^,()]+?)\s*,")
ALLOWED_STRING_KEYS = {
    "KEY_STATE",    # an EngineState enum name
    "KEY_DETAIL",   # a throwable's class name, never its message
    "KEY_DEGRADED", # a comma-joined list of ASSET names, fixed at build time
}


def check_diagnostics(root: str, inject: bool) -> Detector:
    """No diagnostic store may persist a string it did not choose itself.

    ### Why this gate exists
    `DeviceEvidence` was added so the phone could answer the checks in `docs/QA_MATRIX.md`
    that are arithmetic rather than judgement. It writes to `SharedPreferences` from the
    keystroke path. That is a file on disk, written on every key, in a process that can see
    everything typed -- which is the exact shape of the thing this project promises not to
    build. The promise needs a gate, not a comment.
    """
    det = Detector(name="diagnostic_store", unit="diagnostic stores", denominator=0)
    present = [p for p in DIAGNOSTIC_STORES if os.path.isfile(os.path.join(root, p))]
    if not present:
        det.notes.append("no diagnostic stores found; NOT-MEASURED")
        return det
    det.denominator = len(present)

    for rel in present:
        path = os.path.join(root, rel)
        text = open(path, encoding="utf-8").read()
        if inject and rel.endswith("DeviceEvidence.kt"):
            # PLANTED DEFECT: the single line that would turn this into a keystroke log --
            # persisting the word the timing was measured on alongside the timing.
            text += '\n    fun recordWord(c: Context, w: String) = ' \
                    'edit(c) { putString("last_word", w) }\n'
        stripped = re.sub(r"/\*.*?\*/", "", text, flags=re.DOTALL)
        stripped = re.sub(r"//.*", "", stripped)
        for m in PUT_STRING.finditer(stripped):
            key = m.group(1)
            if key in ALLOWED_STRING_KEYS:
                continue
            line = stripped[:m.start()].count("\n") + 1
            det.findings.append(Finding(
                "diagnostic_store", rel, line,
                f"putString({key}) persists a string this store did not choose from a fixed "
                f"set. A diagnostic writes numbers; a diagnostic that writes text is a "
                f"keystroke log.",
                "diag.text_persisted"))
    det.notes.append(
        f"checked {len(present)} stores; {len(ALLOWED_STRING_KEYS)} string keys allowed by "
        f"name (enum name, throwable class name, asset list)")
    return det


def check_guard(root: str, inject: bool) -> Detector:
    det = Detector(name="learn_guard", unit="Kotlin source files", denominator=0)
    files = source_files(root)
    det.denominator = len(files)
    if not files:
        det.notes.append("no Kotlin sources found; NOT-MEASURED")
        det.denominator = 0
        return det

    call_sites = []
    for path in files:
        if os.sep + "test" + os.sep in path:
            continue
        text = open(path, encoding="utf-8").read()
        if inject and path.endswith("HebrewImeService.kt"):
            # PLANTED DEFECT: a second, unguarded call. It compiles, it looks reasonable, and
            # it learns from password fields.
            text += "\n    private fun leak() { correction.learn(\"a\", \"b\") }\n"
        stripped = re.sub(r"/\*.*?\*/", "", text, flags=re.DOTALL)
        stripped = re.sub(r"//.*", "", stripped)
        for m in LEARN_CALL.finditer(stripped):
            line = stripped[:m.start()].count("\n") + 1
            guarded = GUARD.search(stripped, 0, m.start()) is not None
            call_sites.append((os.path.relpath(path, root), line, guarded, stripped, m.start()))

    if not call_sites:
        det.notes.append("no learning call site found at all; NOT-MEASURED rather than PASS")
        det.denominator = 0
        return det

    # The guard must be in the SAME function, not merely somewhere earlier in the file.
    for path, line, _, stripped, at in call_sites:
        body_start = stripped.rfind("private fun", 0, at)
        if body_start < 0:
            body_start = stripped.rfind("fun ", 0, at)
        guarded_here = GUARD.search(stripped, body_start, at) is not None
        if not guarded_here:
            det.findings.append(Finding(
                "learn_guard", path, line,
                "a learning call site is not guarded by `if (!session.mayLearn) return` in "
                "its own function; this is how a password field gets learned from",
                "learn.unguarded_call_site"))

    files_with_calls = {c[0] for c in call_sites}
    if len(files_with_calls) > 1:
        det.findings.append(Finding(
            "learn_guard", ", ".join(sorted(files_with_calls)), 0,
            f"learning is called from {len(files_with_calls)} files; it must be one, so the "
            f"guard is reviewable in a single place",
            "learn.multiple_call_sites"))

    det.notes.append(f"{len(call_sites)} learning call site(s) in "
                     f"{len(files_with_calls)} file(s): {sorted(files_with_calls)}")
    return det


NOT_COVERED = [
    "Matches source text. A learning call assembled by reflection, or reached through an "
    "interface this gate does not know the name of, would not be seen.",
    "Proves the guard is PRESENT, not that SensitiveFieldPolicy classifies fields correctly. "
    "That is M4's exhaustive input-type sweep, which is a separate check.",
    "Says nothing about whether the encrypted file on a device really contains what the codec "
    "wrote. Nothing here has ever run on a device.",
    "Does not check the second condition -- the user's opt-in -- which lives in "
    "CorrectionController and is covered by tests rather than by this gate.",
    "A String in a learning source file is refused even where it would have been harmless. "
    "That is deliberate: a narrow rule with exceptions is a rule someone will add an exception "
    "to.",
]


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", default=ROOT)
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--strict", action="store_true")
    ap.add_argument("--inject-defect", choices=["schema", "guard", "diagnostics"],
                    help="PLANT A DEFECT. Positive control; every value must go red.")
    args = ap.parse_args()

    result = GateResult(
        gate="GATE-LEARN-1 / GATE-LEARN-2",
        description="the learned model stores counts over ids, and learning happens in exactly "
                    "one guarded place",
        detectors=[
            check_schema(args.root, args.inject_defect == "schema"),
            check_guard(args.root, args.inject_defect == "guard"),
            check_diagnostics(args.root, args.inject_defect == "diagnostics"),
        ],
        not_covered=NOT_COVERED,
    )
    return report(result, args.json, args.strict)


if __name__ == "__main__":
    raise SystemExit(main())

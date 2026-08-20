#!/usr/bin/env python3
"""GATE-API-1 -- ban the Android IME traps that compile cleanly and fail at runtime.

Each rule encodes a specific verified fact from the build spec. These are all things the
Kotlin compiler and Android Lint will happily accept:

  ims.session_override   §1.6  Overriding onCreateInputMethodSessionInterface() throws
                               LinkageError in onCreate() at targetSdk >= 34
                               (DISALLOW_INPUT_METHOD_INTERFACE_OVERRIDE). Compiles fine --
                               the method is public in the API 36 SDK (see docs/VERIFICATION.md).
  ic.return_branch       §1.3  IRemoteInputConnection is `oneway`; commitText/setComposingText/
                               deleteSurroundingText/beginBatchEdit/endBatchEdit return true
                               unless Binder throws. Branching on them is dead code.
  bksp.hardcoded         §1.4  A hardcoded delete width of 1 splits surrogate pairs, emoji ZWJ
                               sequences and Hebrew base+niqqud stacks. Width must come from
                               BreakIterator.
  ctx.blocking_fetch     §1.1  getTextBeforeCursor() is a blocking Binder round-trip
                               (RemoteInputConnection.MAX_WAIT_TIME_MILLIS = 2000). At minSdk 30
                               getInitialTextBeforeCursor() exists, so there is no reason to
                               call it at all.

Suppression: `API-GATE-ALLOW: <reason>` on the line. The reason is mandatory and every
suppression is printed.
"""
from __future__ import annotations

import argparse
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gatelib import Detector, Finding, GateResult, report  # noqa: E402

# The InputConnection methods whose return value is meaningless (§1.3).
ONEWAY_METHODS = (
    "commitText", "setComposingText", "setComposingRegion", "finishComposingText",
    "deleteSurroundingText", "beginBatchEdit", "endBatchEdit", "performEditorAction",
    "commitCompletion", "commitCorrection", "setSelection", "sendKeyEvent",
)
_ONEWAY_ALT = "|".join(ONEWAY_METHODS)

RULES: list[dict] = [
    {
        "id": "ims.session_override",
        "pattern": re.compile(r"\boverride\s+fun\s+onCreateInputMethodSessionInterface\b"
                              r"|\bpublic\s+AbstractInputMethodSessionImpl\s+onCreateInputMethodSessionInterface\b"),
        "message": "§1.6 overriding onCreateInputMethodSessionInterface throws LinkageError "
                   "at targetSdk>=34 -- instant crash on launch",
        "allow_paths": [],
    },
    {
        "id": "ic.return_branch",
        # if (!ic.commitText(...))   /   if (ic.commitText(...))   /   && ic.endBatchEdit()
        "pattern": re.compile(r"(?:if\s*\(\s*!?|&&\s*|\|\|\s*|return\s+)"
                              rf"[A-Za-z_][A-Za-z0-9_]*\s*(?:\?\.|\.)\s*(?:{_ONEWAY_ALT})\s*\("),
        "message": "§1.3 InputConnection return values are meaningless (oneway Binder); "
                   "branching on them is dead code. Reconcile via onUpdateSelection instead.",
        "allow_paths": [],
    },
    {
        "id": "bksp.hardcoded",
        "pattern": re.compile(r"\bdeleteSurroundingText(?:InCodePoints)?\s*\(\s*1\s*,"),
        "message": "§1.4 hardcoded backspace width of 1 splits surrogate pairs, emoji ZWJ "
                   "sequences and Hebrew base+niqqud stacks. Use BreakIterator.",
        "allow_paths": [],
    },
    {
        "id": "ctx.blocking_fetch",
        "pattern": re.compile(r"\.getTextBeforeCursor\s*\(|\.getTextAfterCursor\s*\("),
        "message": "§1.1 blocking Binder round-trip, up to MAX_WAIT_TIME_MILLIS=2000. "
                   "At minSdk 30 use EditorInfo.getInitialTextBeforeCursor + an incremental "
                   "buffer resynced from onUpdateSelection.",
        "allow_paths": [],
    },
]

SUPPRESS_RE = re.compile(r"API-GATE-ALLOW:\s*(?P<reason>\S.*?)\s*$")
DEFAULT_EXCLUDE_DIRS = {
    ".git", ".gradle", ".idea", ".kotlin", "build", "out",
    os.path.join("tools", "positive_controls"),
}
SOURCE_EXTS = (".kt", ".java")

NOT_COVERED = [
    "Static text matching only. A violation assembled at runtime (reflection, a lambda stored "
    "in a variable, an interface indirection) is not detected.",
    "The ic.return_branch rule matches a receiver-dot-method shape. A call through a helper "
    "that itself returns the Boolean, then branched on elsewhere, is not detected.",
    "Does not verify the positive claim -- that onUpdateSelection reconciliation exists and is "
    "correct. It only bans the wrong pattern. Correctness is covered by unit tests, not here.",
    "deleteSurroundingTextInCodePoints's return value IS meaningful on API 26-32 (§1.3 "
    "exception). This gate does not check that the exception is handled where it applies.",
]


def _walk(root: str, use_default_excludes: bool):
    excludes = set(DEFAULT_EXCLUDE_DIRS) if use_default_excludes else set()
    for dirpath, dirnames, filenames in os.walk(root):
        rel_dir = os.path.relpath(dirpath, root)
        rel_dir = "" if rel_dir == "." else rel_dir
        dirnames[:] = [
            d for d in dirnames
            if d not in excludes
            and os.path.normpath(os.path.join(rel_dir, d)) not in excludes
        ]
        for fn in filenames:
            yield dirpath, fn


def scan(root: str, use_default_excludes: bool) -> Detector:
    det = Detector(name="forbidden_api", unit="Kotlin/Java source files", denominator=0)
    lines_scanned = 0
    for dirpath, fn in _walk(root, use_default_excludes):
        if not fn.endswith(SOURCE_EXTS):
            continue
        path = os.path.join(dirpath, fn)
        rel = os.path.relpath(path, root)
        det.denominator += 1
        with open(path, "r", encoding="utf-8", errors="replace") as fh:
            for lineno, line in enumerate(fh, start=1):
                lines_scanned += 1
                stripped = line.strip()
                if stripped.startswith("//") or stripped.startswith("*"):
                    continue
                sup = SUPPRESS_RE.search(line)
                for rule in RULES:
                    if any(a in rel for a in rule["allow_paths"]):
                        continue
                    if not rule["pattern"].search(line):
                        continue
                    if sup:
                        det.suppressions.append({
                            "path": rel, "line": lineno,
                            "rules": [rule["id"]], "reason": sup.group("reason"),
                        })
                        continue
                    det.findings.append(Finding(
                        "forbidden_api", rel, lineno, stripped[:160], rule["id"]))
    det.notes.append(f"{lines_scanned} source lines read against {len(RULES)} rules")
    if det.denominator == 0:
        det.notes.append("No Kotlin/Java sources found. Reported NOT-MEASURED, not PASS.")
    return det


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", default=".")
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--strict", action="store_true")
    ap.add_argument("--no-default-excludes", action="store_true")
    args = ap.parse_args()

    root = os.path.abspath(args.root)
    if not os.path.isdir(root):
        print(f"root does not exist: {root}", file=sys.stderr)
        return 2

    result = GateResult(
        gate="GATE-API-1",
        description="no Android IME API that compiles cleanly and fails at runtime",
        detectors=[scan(root, not args.no_default_excludes)],
        not_covered=NOT_COVERED,
    )
    return report(result, args.json, args.strict)


if __name__ == "__main__":
    raise SystemExit(main())

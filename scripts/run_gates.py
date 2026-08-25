#!/usr/bin/env python3
"""Run every repo gate, and refuse to record any of them as PASSED unless its positive
control was demonstrated RED in this same run.

This is the mechanism behind the project's overriding rule:

    A gate that has never failed has not been shown to be a gate.

For each gate we run the control FIRST. If the control does not go red, the gate is reported
as NOT-A-GATE and the whole run fails -- regardless of what the gate said about the real tree.
A green result from an unproven gate is worth nothing and is not allowed to look like a pass.
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PY = sys.executable


# Gates whose inputs are legitimately absent in some environments, with the reason.
#
# Everything NOT in this set that comes back NOT-MEASURED is a **failure**, strict or not. The
# previous rule -- NOT-MEASURED is only fatal under --strict -- let three release gates go dark
# and still printed `overall: OK`, on the one build configuration that ever ships. An expected
# absence is a short, named list; an unexpected one is a gate that stopped working.
MAY_BE_ABSENT = {
    "GATE-LEX-1": "the 37 MB of upstream lexicon sources are gitignored and absent on a fresh "
                  "clone; with nothing to rebuild the reproducibility control cannot go red",
    "GATE-LEX-2": "same upstream sources",
}


RELEASE_DIR = os.path.join(ROOT, "app", "build", "outputs", "apk", "release")


def resolve_release_apk() -> str:
    """The release APK, whatever the signing configuration named it.

    ### Why this is a function and not a constant
    It was a constant, spelling `app-release-unsigned.apk`. AGP writes that name only while the
    build is UNSIGNED. The moment `keystore.properties` exists -- that is, at release time, on
    the only build that ever ships -- AGP writes `app-release.apk` instead, the constant points
    at a file that no longer exists, and GATE-NET-3, GATE-R8-1 and GATE-SIZE-1 all report
    NOT-MEASURED while the suite still prints `overall: OK`.

    So the network check on the shipping artifact, the R8 check on the shipping artifact and
    the size budget on the shipping artifact would all have stopped running at exactly the
    moment they became load-bearing, and nothing would have said so louder than a line most
    readers skim.

    Found by generating a throwaway key and building a signed release, which is the only way
    this could have surfaced before the operator hit it.

    An APK present in the directory under an unexpected name is a hard error rather than a
    fallback: a gate that quietly measures a different artifact than it names is worse than one
    that stops.
    """
    signed = os.path.join(RELEASE_DIR, "app-release.apk")
    unsigned = os.path.join(RELEASE_DIR, "app-release-unsigned.apk")
    if os.path.isfile(signed):
        return signed
    if os.path.isfile(unsigned):
        return unsigned
    if os.path.isdir(RELEASE_DIR):
        strays = sorted(f for f in os.listdir(RELEASE_DIR) if f.endswith(".apk"))
        if strays:
            raise SystemExit(
                f"release APKs present under unrecognised names: {strays}. Refusing to guess "
                f"which one ships. Update resolve_release_apk() rather than letting the "
                f"release gates measure an artifact they did not name."
            )
    # Nothing built yet. The "requires" mechanism reports this honestly as NOT-MEASURED.
    return unsigned


def _gates(strict: bool) -> list[dict]:
    s = ["--strict"] if strict else []
    net = os.path.join(ROOT, "scripts", "check_no_network.py")
    api = os.path.join(ROOT, "scripts", "check_forbidden_api.py")
    lex = os.path.join(ROOT, "scripts", "check_lexicon.py")
    xml = os.path.join(ROOT, "scripts", "check_xml.py")
    trace = os.path.join(ROOT, "scripts", "check_trace_sections.py")
    size = os.path.join(ROOT, "scripts", "check_size.py")
    learn = os.path.join(ROOT, "scripts", "check_learning.py")
    docs = os.path.join(ROOT, "scripts", "check_docs.py")
    dod = os.path.join(ROOT, "scripts", "check_dod.py")
    withdrawn = os.path.join(ROOT, "scripts", "check_withdrawn.py")
    apk = os.path.join(ROOT, "scripts", "check_apk.py")
    store = os.path.join(ROOT, "scripts", "build_store_assets.py")
    debug_apk = os.path.join(ROOT, "app", "build", "outputs", "apk", "debug", "app-debug.apk")
    netc_apk = os.path.join(ROOT, "app", "build", "outputs", "apk", "netcontrol",
                            "app-netcontrol.apk")
    release_apk = resolve_release_apk()
    release_baseline = os.path.join(ROOT, "tools", "apk_dex_baseline_release.json")
    # The 37 MB of upstream lexicon sources. Gitignored, so absent on a fresh clone and on
    # every CI runner unless a step fetches them first. Both the reproducibility detector and
    # its positive control need them: with no sources there is nothing to rebuild and nothing
    # to corrupt, so the control cannot go red -- which is NOT the same as a control that ran
    # and failed to fire, and must not be reported as though it were.
    lexicon_sources = [
        os.path.join(ROOT, "lexicon", "cache", "InflectedVerbsExtended.csv"),
        os.path.join(ROOT, "lexicon", "cache", "he_full.txt"),
    ]
    return [
        {
            "id": "GATE-NET-1",
            "what": "no network capability (manifests, sources, dependency coordinates)",
            "real": [PY, net, "--root", ROOT, "--json"] + s,
            "control": [PY, net, "--root",
                        os.path.join(ROOT, "tools", "positive_controls", "network"),
                        "--no-default-excludes", "--json"],
            "control_desc": "planted INTERNET permission + okhttp/java.net/WebView source + "
                            "okhttp & firebase coordinates",
        },
        {
            "id": "GATE-API-1",
            "what": "no IME API that compiles cleanly and fails at runtime (§1.1/1.3/1.4/1.6)",
            "real": [PY, api, "--root", ROOT, "--json"] + s,
            "control": [PY, api, "--root",
                        os.path.join(ROOT, "tools", "positive_controls", "forbidden_api"),
                        "--no-default-excludes", "--json"],
            "control_desc": "planted session-interface override, return-value branch, "
                            "hardcoded backspace width, blocking getTextBeforeCursor",
        },
        {
            "id": "GATE-CRYPTO-1",
            "what": "no ECB, hardcoded IV or key, seeded SecureRandom, or broken primitive",
            "real": [PY, api, "--root", ROOT, "--json"] + s,
            "control": [PY, api, "--root",
                        os.path.join(ROOT, "tools", "positive_controls", "crypto"),
                        "--no-default-excludes", "--json"],
            "control_desc": "planted AES/ECB, a fixed IV, a hardcoded key, MD5, and a seeded "
                            "SecureRandom",
        },
        {
            # The artifact detector needs nothing but the repository, so this gate is proven
            # everywhere. Its reproducibility detector needs the sources and reports
            # NOT-MEASURED without them, which is why the gate can come back PASS-PARTIAL on a
            # runner that has no cache. PASS-PARTIAL is not PASS and is printed as such.
            "id": "GATE-LEX-1",
            "what": "the shipped lexicon matches its manifest and the recipe reproduces it",
            "real": [PY, lex, "--json"] + s,
            "control": [PY, lex, "--inject-defect", "artifact", "--json"],
            "control_desc": "one byte of the committed lexicon artifact flipped",
        },
        {
            "id": "GATE-LEX-2",
            "what": "upstream source integrity: a changed source must not build silently",
            "real": [PY, lex, "--json"] + s,
            "control": [PY, lex, "--inject-defect", "checksum", "--json"],
            "control_desc": "one byte of upstream source A flipped before hashing",
            "requires": lexicon_sources,
        },
        {
            "id": "GATE-XML-1",
            "what": "every XML resource parses",
            "real": [PY, xml, "--json"] + s,
            "control": [PY, xml, "--root", os.path.join(ROOT, "tools", "positive_controls",
                                                        "xml"),
                        "--include-controls", "--json"],
            "control_desc": "a manifest comment containing the double hyphen XML forbids",
        },
        {
            "id": "GATE-NET-2",
            "what": "the BUILT apk has no network capability (merged manifest + DEX)",
            "real": [PY, apk, "--apk", debug_apk, "--json"] + s,
            "control": [PY, apk, "--apk", netc_apk, "--json"],
            "control_desc": "a REAL assembled apk carrying INTERNET and java.net usage",
            "requires": [debug_apk, netc_apk],
        },
        {
            "id": "GATE-MANIFEST-1",
            "what": "the IME service declaration is valid (§1.8)",
            "real": [PY, apk, "--apk", debug_apk, "--json"] + s,
            "control": [PY, apk, "--apk", debug_apk, "--inject-defect", "service", "--json"],
            "control_desc": "android:exported flipped to false in the merged manifest",
            "requires": [debug_apk],
        },
        {
            "id": "GATE-LEX-3",
            "what": "the lexicon INSIDE the apk is the one the manifest describes",
            "real": [PY, apk, "--apk", debug_apk, "--json"] + s,
            "control": [PY, apk, "--apk", debug_apk, "--inject-defect", "lexicon", "--json"],
            "control_desc": "one byte appended to the packaged lexicon asset",
            "requires": [debug_apk],
        },
        {
            "id": "GATE-ASSET-1",
            "what": "the assets the app opens by name are the ones AGP actually packaged",
            "real": [PY, apk, "--apk", debug_apk, "--json"] + s,
            "control": [PY, apk, "--apk", debug_apk, "--inject-defect", "asset_name",
                        "--json"],
            "control_desc": "expect an asset name AGP does not produce (a .gz that AGP strips)",
            "requires": [debug_apk],
        },
        {
            "id": "GATE-BIGRAM-1",
            "what": "the bigram table INSIDE the apk is the one prediction was measured on",
            "real": [PY, apk, "--apk", debug_apk, "--json"] + s,
            "control": [PY, apk, "--apk", debug_apk, "--inject-defect", "bigram_content",
                        "--json"],
            "control_desc": "one byte appended to the packaged bigram table",
            "requires": [debug_apk],
        },
        {
            "id": "GATE-FONT-1",
            "what": "the typeface INSIDE the apk is the one the letter-pair measurement ranked",
            "real": [PY, apk, "--apk", debug_apk, "--json"] + s,
            "control": [PY, apk, "--apk", debug_apk, "--inject-defect", "font",
                        "--json"],
            "control_desc": "one byte appended to the packaged typeface",
            "requires": [debug_apk],
        },
        {
            "id": "GATE-NET-3",
            "what": "the RELEASE artifact -- the one that ships -- has no network capability",
            "real": [PY, apk, "--apk", release_apk, "--baseline", release_baseline,
                     "--json"] + s,
            "control": [PY, apk, "--apk", netc_apk, "--baseline", release_baseline, "--json"],
            "control_desc": "the netcontrol apk measured against the release baseline",
            "requires": [release_apk, netc_apk],
        },
        {
            "id": "GATE-R8-1",
            "what": "R8 has not stripped the classes the system instantiates by name",
            "real": [PY, apk, "--apk", release_apk, "--baseline", release_baseline,
                     "--json"] + s,
            "control": [PY, apk, "--apk", release_apk, "--baseline", release_baseline,
                        "--inject-defect", "service", "--json"],
            "control_desc": "the IME service declaration invalidated in the release manifest",
            "requires": [release_apk],
        },
        {
            "id": "GATE-LEARN-1",
            "what": "the learned model persists counts over integer ids and nothing that can "
                    "hold text",
            "real": [PY, learn, "--root", ROOT, "--json"] + s,
            "control": [PY, learn, "--root", ROOT, "--inject-defect", "schema", "--json"],
            "control_desc": "a planted encoder that accepts a String -- the change that turns "
                            "a count store into a keystroke log",
        },
        {
            "id": "GATE-LEARN-2",
            "what": "learning happens in exactly one place, guarded by session.mayLearn",
            "real": [PY, learn, "--root", ROOT, "--json"] + s,
            "control": [PY, learn, "--root", ROOT, "--inject-defect", "guard", "--json"],
            "control_desc": "a planted second call site with no guard: it compiles, it looks "
                            "reasonable, and it learns from password fields",
        },
        {
            "id": "GATE-LEARN-3",
            "what": "no diagnostic store persists a string it did not choose from a fixed set",
            "real": [PY, learn, "--root", ROOT, "--json"] + s,
            "control": [PY, learn, "--root", ROOT, "--inject-defect", "diagnostics", "--json"],
            "control_desc": "a diagnostic that writes down the word a timing was measured on "
                            "-- the one line that turns a counter store into a keystroke log",
        },
        {
            # The store assets are RENDERED from app/src/main/res. A committed PNG that no
            # longer matches what those resources produce is a third source of truth, and
            # nobody would notice: a store asset is looked at once. Which is exactly how a
            # Latin capital N shipped for months as this app's Hebrew keyboard icon.
            "id": "GATE-STORE-1",
            "what": "the committed Play assets are what the app's own resources render to",
            "real": [PY, store, "--check"],
            "control": [PY, store, "--check", "--inject-defect"],
            "control_desc": "one pixel of the icon's background changed -- what a store asset "
                            "left behind by a palette change looks like",
        },
        {
            "id": "GATE-DOC-1",
            "what": "the readiness verdict's device-blocked list matches the QA matrix",
            "real": [PY, docs, "--root", ROOT, "--json"] + s,
            "control": [PY, docs, "--root", ROOT, "--inject-defect", "stale", "--json"],
            "control_desc": "a readiness list still naming a check the matrix records as done "
                            "-- the exact drift that made the same sentence stale twice",
        },
        {
            # A FOURTH control on the same script. check_docs.py now carries three detectors,
            # and the TOTAL gate count is the one that had no reader at all: it went stale in
            # two documents the moment GATE-WITHDRAWN-1 was added, and --fix-denominators
            # rewrote none of them because no rule covered the number. The gate against stale
            # counts had a stale count just outside its own reach.
            "id": "GATE-DOC-4",
            "what": "the total number of gates published in QA_MATRIX.md and README.md is the "
                    "number run_gates.py actually defines",
            "real": [PY, docs, "--root", ROOT, "--json"] + s,
            "control": [PY, docs, "--root", ROOT, "--inject-defect", "gatecount", "--json"],
            "control_desc": "both documents publishing one gate more than the runner defines "
                            "-- what every hand-copied total looks like the day after a gate "
                            "is added",
        },
        {
            # A third control on the same detector, because "stale" only exercises the
            # cross-document comparison. This one exercises the matrix against itself, which
            # went unchecked until a bullet in QA_MATRIX.md was found contradicting a table
            # six lines above it.
            "id": "GATE-DOC-3",
            "what": "QA_MATRIX.md does not contradict itself: no row is both device-blocked "
                    "and OBSERVED in the same file",
            "real": [PY, docs, "--root", ROOT, "--json"] + s,
            "control": [PY, docs, "--root", ROOT, "--inject-defect", "selfcontradiction",
                        "--json"],
            "control_desc": "a row left in the device-blocked table after being marked "
                            "OBSERVED -- what hand-editing produces when a check is copied "
                            "into the observed table and not deleted from the blocked one",
        },
        {
            # A second control on the same script, because check_docs.py now carries two
            # detectors and a control shown red on one says nothing about the other.
            "id": "GATE-DOC-2",
            "what": "the denominators QA_MATRIX.md publishes are the ones the gates counted",
            "real": [PY, docs, "--root", ROOT, "--json"] + s,
            "control": [PY, docs, "--root", ROOT, "--inject-defect", "denominator", "--json"],
            "control_desc": "one published denominator off by one -- what a hand-copied "
                            "count looks like the day after a source file is added",
        },
        {
            # Two layers were withdrawn against a pre-registered rule, and both withdrawals
            # live as an ABSENCE -- a constructor no longer called, a default left at null.
            # An absence is the easiest thing in a codebase to undo by accident, and nothing
            # asserted the silence until this gate existed.
            "id": "GATE-WITHDRAWN-1",
            "what": "no layer withdrawn against a pre-registered stopping rule is constructed "
                    "in the shipped path",
            "real": [PY, withdrawn, "--json"] + s,
            "control": [PY, withdrawn, "--inject-defect", "reenable", "--json"],
            "control_desc": "the real-word detector wired back into the shipped controller -- "
                            "what a merge resolution or a 'restored' missing wire-up looks "
                            "like the day nobody remembers why the slot was empty",
        },
        {
            # The definition of done is the third document whose central claims must be kept
            # in step with the other two by hand -- the mechanism that produced a stale device
            # claim twice and a stale denominator inside the gate written against stale
            # denominators. It arrives with a gate rather than after one.
            "id": "GATE-DOD-1",
            "what": "every done-criterion cites something, states one of the four allowed "
                    "states, and every waived one names a waiver that exists",
            "real": [PY, dod, "--root", ROOT, "--json"] + s,
            "control": [PY, dod, "--root", ROOT, "--inject-defect", "unfalsifiable",
                        "--json"],
            "control_desc": "a criterion citing nothing, in a state nobody defined, beside a "
                            "waiver decided in conversation and never written down -- what a "
                            "criterion written to feel finished looks like",
        },
        {
            "id": "GATE-DOD-2",
            "what": "everything a done-criterion cites resolves: a gate the runner defines, a "
                    "row the QA matrix carries, or a file that exists",
            "real": [PY, dod, "--root", ROOT, "--json"] + s,
            "control": [PY, dod, "--root", ROOT, "--inject-defect", "phantom", "--json"],
            "control_desc": "a criterion citing a gate and a file that no longer exist -- "
                            "evidence renamed out from under a criterion that still reads as "
                            "satisfied",
        },
        {
            "id": "GATE-DOD-3",
            "what": "no criterion claims MET while the QA matrix records its evidence as "
                    "never run",
            "real": [PY, dod, "--root", ROOT, "--json"] + s,
            "control": [PY, dod, "--root", ROOT, "--inject-defect", "metoverunrun", "--json"],
            "control_desc": "MET claimed over M7-LAT, which has never run -- 'ready except "
                            "for', written one row at a time",
        },
        {
            "id": "GATE-DOD-4",
            "what": "the counts the definition of done publishes are the ones its own tables "
                    "contain, verdict included",
            "real": [PY, dod, "--root", ROOT, "--json"] + s,
            "control": [PY, dod, "--root", ROOT, "--inject-defect", "count", "--json"],
            "control_desc": "one published count off by one -- what a hand-copied summary "
                            "looks like the day after a criterion changes state",
        },
        {
            "id": "GATE-SIZE-1",
            "what": "the release artifact stays inside the size budget written down for it",
            "real": [PY, size, "--apk", release_apk, "--json"] + s,
            "control": [PY, size, "--apk", release_apk, "--inject-defect", "assets", "--json"],
            "control_desc": "assets measured 50% larger, which is what regenerating the "
                            "bigram table at a lower prune threshold would look like",
            "requires": [release_apk],
        },
        {
            "id": "GATE-TRACE-1",
            "what": "the benchmark measures trace sections the app actually emits",
            "real": [PY, trace, "--json"] + s,
            "control": [PY, trace, "--inject-defect", "names", "--json"],
            "control_desc": "rename the benchmark's requested sections, which is what a "
                            "one-sided rename looks like and would otherwise report zero "
                            "measurements as success",
        },
        {
            # A second control on the same script: GATE-TRACE-1's control renames sections and
            # says nothing about whether they are balanced, which is how a suspending call sat
            # inside a traced region for four milestones.
            "id": "GATE-TRACE-2",
            "what": "no traced region contains a call that suspends",
            "real": [PY, trace, "--json"] + s,
            "control": [PY, trace, "--inject-defect", "balance", "--json"],
            "control_desc": "readUserModel() inside a Trace.beginSection region -- the exact "
                            "shape Android lint found in CorrectionController.warmUp",
        },
        {
            "id": "GATE-DENOM-1",
            "what": "a check that examined nothing must not report PASS",
            "real": None,  # meta-gate: it has no real-tree run, only a control
            "control": [PY, net, "--root", "@EMPTYDIR@", "--strict", "--json"],
            "control_desc": "run GATE-NET-1 over an empty directory under --strict; every "
                            "detector has denominator 0, so the only correct answer is a "
                            "failure, not PASS",
        },
    ]


def _run(cmd: list[str], empty_dir: str) -> tuple[int, dict | None, str]:
    cmd = [c.replace("@EMPTYDIR@", empty_dir) for c in cmd]
    p = subprocess.run(cmd, capture_output=True, text=True)
    data = None
    try:
        data = json.loads(p.stdout)
    except json.JSONDecodeError:
        pass
    return p.returncode, data, (p.stdout + p.stderr)


def _denoms(data: dict | None) -> str:
    if not data:
        return "n/a"
    return ", ".join(f"{d['name']}={d['denominator']}" for d in data.get("detectors", []))


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--strict", action="store_true",
                    help="NOT-MEASURED detectors count as failures (release readiness)")
    ap.add_argument("--verbose", action="store_true")
    args = ap.parse_args()

    rows = []
    ok = True

    with tempfile.TemporaryDirectory() as empty_dir:
        for g in _gates(args.strict):
            # 0. Some gates check a build artifact. An artifact that was never built is not a
            #    clean artifact, and a control that cannot run has not proven anything -- so
            #    this is reported as NOT-MEASURED, distinctly from a control that ran and
            #    failed to go red.
            absent = [p for p in g.get("requires", []) if not os.path.isfile(p)]
            if absent:
                expected = g["id"] in MAY_BE_ABSENT
                rows.append((g["id"], "NOT-MEASURED",
                             f"input absent: {os.path.basename(absent[0])}", "n/a"))
                why = (f"Expected in this environment: {MAY_BE_ABSENT[g['id']]}."
                       if expected else
                       "This gate is NOT on the list of gates allowed to go dark, so a missing "
                       "input is a gate that stopped working, not an environment.")
                print(f"\n!!! {g['id']}: NOT-MEASURED. Its control needs "
                      f"{', '.join(os.path.relpath(p, ROOT) for p in absent)}, which is not "
                      f"present. A control that cannot run has proven nothing.\n    {why}",
                      file=sys.stderr)
                if args.strict or not expected:
                    ok = False
                continue

            # 1. Control first. It must go red.
            c_code, c_data, c_out = _run(g["control"], empty_dir)
            control_red = c_code != 0
            c_status = (c_data or {}).get("status", f"exit={c_code}")

            if not control_red:
                ok = False
                rows.append((g["id"], "NOT-A-GATE", "control did NOT go red", "n/a"))
                print(f"\n!!! {g['id']}: positive control did not fail. "
                      f"The gate is unproven and cannot be trusted.\n{c_out}",
                      file=sys.stderr)
                continue

            if args.verbose:
                print(f"--- {g['id']} control output ---\n{c_out}")

            # 2. Real tree.
            if g["real"] is None:
                rows.append((g["id"], "PROVEN", f"control red ({c_status})", "n/a"))
                continue

            r_code, r_data, r_out = _run(g["real"], empty_dir)
            r_status = (r_data or {}).get("status", f"exit={r_code}")
            if r_code != 0:
                ok = False
                print(f"\n!!! {g['id']} FAILED on the real tree:\n{r_out}", file=sys.stderr)
            rows.append((g["id"], r_status, f"control red ({c_status})", _denoms(r_data)))

    width = max(len(r[0]) for r in rows)
    print("\n" + "=" * 100)
    print("GATE RESULTS  (a gate is only PASSED if its control was demonstrated RED here)")
    print("=" * 100)
    print(f"{'gate'.ljust(width)}  {'result':<13} {'control':<22} denominators")
    print("-" * 100)
    for gid, status, ctrl, den in rows:
        print(f"{gid.ljust(width)}  {status:<13} {ctrl:<22} {den}")
    print("=" * 100)
    print("PASS-PARTIAL means at least one detector had denominator 0 and was reported "
          "NOT-MEASURED.\nIt is not a pass. Run with --strict to make it a failure.")
    print("NOT-MEASURED fails the run unless the gate is listed in MAY_BE_ABSENT with a "
          "reason.\nA gate going dark unannounced is how three release gates stopped "
          "measuring the\nshipping artifact the moment a signing key existed.")
    print(f"\noverall: {'OK' if ok else 'FAILED'}")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())

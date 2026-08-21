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
    apk = os.path.join(ROOT, "scripts", "check_apk.py")
    debug_apk = os.path.join(ROOT, "app", "build", "outputs", "apk", "debug", "app-debug.apk")
    netc_apk = os.path.join(ROOT, "app", "build", "outputs", "apk", "netcontrol",
                            "app-netcontrol.apk")
    release_apk = os.path.join(ROOT, "app", "build", "outputs", "apk", "release",
                               "app-release-unsigned.apk")
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
            "id": "GATE-DOC-1",
            "what": "the readiness verdict's device-blocked list matches the QA matrix",
            "real": [PY, docs, "--root", ROOT, "--json"] + s,
            "control": [PY, docs, "--root", ROOT, "--inject-defect", "stale", "--json"],
            "control_desc": "a readiness list still naming a check the matrix records as done "
                            "-- the exact drift that made the same sentence stale twice",
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
            "control": [PY, trace, "--inject-defect", "--json"],
            "control_desc": "rename the benchmark's requested sections, which is what a "
                            "one-sided rename looks like and would otherwise report zero "
                            "measurements as success",
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
                rows.append((g["id"], "NOT-MEASURED",
                             f"input absent: {os.path.basename(absent[0])}", "n/a"))
                print(f"\n!!! {g['id']}: NOT-MEASURED. Its control needs "
                      f"{', '.join(os.path.relpath(p, ROOT) for p in absent)}, which is not "
                      f"present. A control that cannot run has proven nothing -- this gate is "
                      f"unproven in THIS environment, and is reported as such rather than as "
                      f"a pass or as a broken gate.", file=sys.stderr)
                if args.strict:
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
    print(f"\noverall: {'OK' if ok else 'FAILED'}")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""GATE-LEX-1 -- the shipped lexicon is the one the manifest describes, and it is reproducible.

Two detectors, deliberately separated because they can be measured independently:

  artifact       The committed lexicon/assets/he_lexicon.txt.gz matches MANIFEST.json byte for byte
                 (gzip sha256, uncompressed sha256, form count, sort order). Always runnable:
                 the artifact and its manifest are both in the repository.

  reproducibility  Re-running scripts/build_lexicon.py from the upstream sources reproduces
                 the same artifact. Requires the 37 MB of upstream sources. If they are not
                 cached and cannot be fetched, this detector reports NOT-MEASURED -- never
                 PASS. A reproducibility claim with no rebuild behind it is worth nothing.
"""
from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import os
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gatelib import Detector, Finding, GateResult, report  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ARTIFACT = os.path.join(ROOT, "lexicon", "assets", "he_lexicon.txt.gz")
MANIFEST = os.path.join(ROOT, "lexicon", "MANIFEST.json")

NOT_COVERED = [
    "Does not check that the lexicon is linguistically CORRECT, only that it is the artifact "
    "the manifest describes and that the recipe reproduces it.",
    "Does not re-verify the upstream sources' licences. That is a human judgement recorded in "
    "docs/LICENSES.md, and it is not re-derivable from the bytes.",
    "Coverage and accuracy are not checked here. Coverage is docs/LEXICON_MEASUREMENTS.md; "
    "correction accuracy needs the golden corpus and is M5.",
    "When the upstream sources are unavailable the reproducibility detector reports "
    "NOT-MEASURED. That is not the same as reproducibility having been demonstrated.",
]


def check_artifact(inject: str | None) -> Detector:
    det = Detector(name="artifact", unit="lexicon artifacts", denominator=0)
    if not os.path.isfile(ARTIFACT) or not os.path.isfile(MANIFEST):
        det.notes.append(f"artifact or manifest missing ({ARTIFACT}); NOT-MEASURED")
        return det

    det.denominator = 1
    manifest = json.load(open(MANIFEST, encoding="utf-8"))
    packed = open(ARTIFACT, "rb").read()
    if inject == "artifact":
        # PLANTED DEFECT: corrupt the artifact in memory so the digest no longer matches.
        packed = packed[:-1] + bytes([packed[-1] ^ 0x01])

    exp = manifest["output"]
    got_gzip = hashlib.sha256(packed).hexdigest()
    if got_gzip != exp["gzip_sha256"]:
        det.findings.append(Finding(
            "artifact", "lexicon/assets/he_lexicon.txt.gz", 0,
            f"gzip sha256 {got_gzip} != manifest {exp['gzip_sha256']}",
            "artifact.gzip_sha256"))

    try:
        blob = gzip.decompress(packed)
    except Exception as exc:  # noqa: BLE001
        det.findings.append(Finding("artifact", "lexicon/assets/he_lexicon.txt.gz", 0,
                                    f"gunzip failed: {exc}", "artifact.corrupt"))
        return det

    got_raw = hashlib.sha256(blob).hexdigest()
    if got_raw != exp["uncompressed_sha256"]:
        det.findings.append(Finding(
            "artifact", "lexicon/assets/he_lexicon.txt.gz", 0,
            f"uncompressed sha256 {got_raw} != manifest {exp['uncompressed_sha256']}",
            "artifact.raw_sha256"))

    words = blob.decode("utf-8").split("\n")
    if words and words[-1] == "":
        words.pop()
    if len(words) != manifest["counts"]["union"]:
        det.findings.append(Finding(
            "artifact", "lexicon/assets/he_lexicon.txt.gz", 0,
            f"{len(words)} forms != manifest {manifest['counts']['union']}",
            "artifact.form_count"))

    unsorted_at = next((i for i in range(1, len(words)) if words[i] <= words[i - 1]), None)
    if unsorted_at is not None:
        det.findings.append(Finding(
            "artifact", "lexicon/assets/he_lexicon.txt.gz", unsorted_at,
            f"not sorted at index {unsorted_at}: {words[unsorted_at - 1]!r} "
            f">= {words[unsorted_at]!r} -- byte-wise binary search would be incorrect",
            "artifact.sort_order"))

    det.notes.append(f"{len(words)} forms, {len(blob)} raw bytes, {len(packed)} gzip bytes")
    det.notes.append(f"gzip sha256 {got_gzip}")
    return det


def check_reproducibility(inject: str | None, allow_fetch: bool) -> Detector:
    det = Detector(name="reproducibility", unit="rebuilds", denominator=0)
    cache = os.path.join(ROOT, "lexicon", "cache")
    needed = ["InflectedVerbsExtended.csv", "he_full.txt"]
    have = all(os.path.isfile(os.path.join(cache, n)) for n in needed)
    if not have and not allow_fetch:
        det.notes.append(
            "upstream sources are not cached and --allow-fetch was not passed; "
            "reproducibility NOT-MEASURED (a rebuild did not happen)")
        return det

    cmd = [sys.executable, os.path.join(ROOT, "scripts", "build_lexicon.py"), "--dry-run"]
    if inject:
        cmd += ["--inject-defect", inject]
        if inject == "filter":
            cmd += ["--allow-unverified-source"]
    proc = subprocess.run(cmd, capture_output=True, text=True)
    det.denominator = 1

    if proc.returncode != 0:
        tail = (proc.stderr.strip().splitlines() or ["(no stderr)"])[-1]
        det.findings.append(Finding("reproducibility", "scripts/build_lexicon.py", 0,
                                    tail, "reproducibility.build_failed"))
        return det

    try:
        rebuilt = json.loads(proc.stdout)
    except json.JSONDecodeError:
        det.findings.append(Finding("reproducibility", "scripts/build_lexicon.py", 0,
                                    "build produced no parseable summary",
                                    "reproducibility.no_summary"))
        return det

    manifest = json.load(open(MANIFEST, encoding="utf-8"))
    if rebuilt["gzip_sha256"] != manifest["output"]["gzip_sha256"]:
        det.findings.append(Finding(
            "reproducibility", "lexicon/assets/he_lexicon.txt.gz", 0,
            f"rebuild produced {rebuilt['gzip_sha256']}, committed artifact is "
            f"{manifest['output']['gzip_sha256']}",
            "reproducibility.hash_drift"))
    for k, v in rebuilt["counts"].items():
        if v != manifest["counts"][k]:
            det.findings.append(Finding(
                "reproducibility", "lexicon/MANIFEST.json", 0,
                f"count {k}: rebuild {v} != manifest {manifest['counts'][k]}",
                "reproducibility.count_drift"))
    det.notes.append(f"rebuild reproduced counts {rebuilt['counts']}")
    return det


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--strict", action="store_true")
    ap.add_argument("--allow-fetch", action="store_true",
                    help="permit downloading the 37 MB of upstream sources if not cached")
    ap.add_argument("--inject-defect", choices=["artifact", "checksum", "filter",
                                                "nondeterminism"],
                    help="PLANT A DEFECT. Positive control -- every value must go red.")
    args = ap.parse_args()

    artifact_inject = args.inject_defect if args.inject_defect == "artifact" else None
    build_inject = args.inject_defect if args.inject_defect in (
        "checksum", "filter", "nondeterminism") else None

    result = GateResult(
        gate="GATE-LEX-1",
        description="the shipped lexicon matches its manifest and the recipe reproduces it",
        detectors=[
            check_artifact(artifact_inject),
            check_reproducibility(build_inject, args.allow_fetch),
        ],
        not_covered=NOT_COVERED,
    )
    return report(result, args.json, args.strict)


if __name__ == "__main__":
    raise SystemExit(main())

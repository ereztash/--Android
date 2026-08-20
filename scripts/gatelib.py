"""Shared reporting for repo gates.

Design rule this file exists to enforce (see docs/milestones/M0.md and the project's
overriding rule): a check that examined nothing reports NOT-MEASURED, never PASS. Every
detector must therefore carry a denominator -- the number of things it actually looked at --
and that denominator travels with the result all the way into the printed report.
"""
from __future__ import annotations

import dataclasses
import json
import sys
from typing import Any

PASS = "PASS"
FAIL = "FAIL"
NOT_MEASURED = "NOT-MEASURED"

EXIT_PASS = 0
EXIT_FAIL = 1
EXIT_ERROR = 2


@dataclasses.dataclass
class Finding:
    detector: str
    path: str
    line: int
    evidence: str
    rule: str

    def to_dict(self) -> dict[str, Any]:
        return dataclasses.asdict(self)


@dataclasses.dataclass
class Detector:
    name: str
    unit: str
    denominator: int
    findings: list[Finding] = dataclasses.field(default_factory=list)
    suppressions: list[dict[str, Any]] = dataclasses.field(default_factory=list)
    notes: list[str] = dataclasses.field(default_factory=list)

    @property
    def status(self) -> str:
        if self.findings:
            return FAIL
        if self.denominator == 0:
            # The whole point. Zero examined is not clean, it is unmeasured.
            return NOT_MEASURED
        return PASS

    def to_dict(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "unit": self.unit,
            "denominator": self.denominator,
            "status": self.status,
            "findings": [f.to_dict() for f in self.findings],
            "suppressions": self.suppressions,
            "notes": self.notes,
        }


@dataclasses.dataclass
class GateResult:
    gate: str
    description: str
    detectors: list[Detector]
    not_covered: list[str]

    @property
    def status(self) -> str:
        if any(d.status == FAIL for d in self.detectors):
            return FAIL
        if any(d.status == NOT_MEASURED for d in self.detectors):
            return "PASS-PARTIAL"
        return PASS

    def to_dict(self) -> dict[str, Any]:
        return {
            "gate": self.gate,
            "description": self.description,
            "status": self.status,
            "detectors": [d.to_dict() for d in self.detectors],
            "not_covered": self.not_covered,
        }


def report(result: GateResult, as_json: bool, strict: bool) -> int:
    """Print the result and return the process exit code.

    strict=True turns PASS-PARTIAL into a failure. Release readiness runs strict; earlier
    milestones do not, because a detector can legitimately have nothing to look at yet --
    but it is still reported as NOT-MEASURED and never as PASS.
    """
    if as_json:
        print(json.dumps(result.to_dict(), indent=2, ensure_ascii=False))
    else:
        print(f"=== {result.gate}: {result.description}")
        for d in result.detectors:
            print(f"  [{d.status:<12}] {d.name:<22} denominator = {d.denominator} {d.unit}")
            for note in d.notes:
                print(f"      note: {note}")
            for s in d.suppressions:
                print(f"      SUPPRESSED: {s['path']}:{s['line']}  reason={s['reason']!r}")
            for f in d.findings:
                print(f"      FINDING [{f.rule}] {f.path}:{f.line}: {f.evidence}")
        print(f"  overall: {result.status}")
        if result.not_covered:
            print("  this gate does NOT cover:")
            for nc in result.not_covered:
                print(f"    - {nc}")

    if result.status == FAIL:
        return EXIT_FAIL
    if result.status == "PASS-PARTIAL" and strict:
        print(f"{result.gate}: strict mode -- PASS-PARTIAL is not PASS", file=sys.stderr)
        return EXIT_FAIL
    return EXIT_PASS

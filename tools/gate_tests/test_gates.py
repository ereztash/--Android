#!/usr/bin/env python3
"""Tests for the gates themselves.

These assert the three properties the project requires of every gate:
  (a) it reports a denominator, and refuses to say PASS when that denominator is zero;
  (b) its positive control makes it go red, naming the specific planted rules;
  (c) it stays green on the real tree.

Run: python3 -m unittest discover -s tools/gate_tests -v
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
PY = sys.executable
NET = os.path.join(ROOT, "scripts", "check_no_network.py")
API = os.path.join(ROOT, "scripts", "check_forbidden_api.py")


def run(cmd):
    p = subprocess.run(cmd, capture_output=True, text=True, cwd=ROOT)
    try:
        return p.returncode, json.loads(p.stdout)
    except json.JSONDecodeError:
        return p.returncode, None


def rules_of(data, detector=None):
    out = set()
    for d in data["detectors"]:
        if detector and d["name"] != detector:
            continue
        for f in d["findings"]:
            out.add(f["rule"])
    return out


def denom(data, name):
    return next(d["denominator"] for d in data["detectors"] if d["name"] == name)


def status(data, name):
    return next(d["status"] for d in data["detectors"] if d["name"] == name)


class TestNetworkGate(unittest.TestCase):
    CONTROL = os.path.join(ROOT, "tools", "positive_controls", "network")

    def test_positive_control_goes_red_on_all_three_detectors(self):
        code, data = run([PY, NET, "--root", self.CONTROL, "--no-default-excludes", "--json"])
        self.assertEqual(code, 1, "control must make the gate exit non-zero")
        self.assertEqual(data["status"], "FAIL")
        for det in ("manifest", "source", "deps"):
            self.assertEqual(status(data, det), "FAIL", f"{det} detector must go red")

    def test_positive_control_names_the_planted_rules(self):
        _, data = run([PY, NET, "--root", self.CONTROL, "--no-default-excludes", "--json"])
        self.assertIn("manifest.network_permission", rules_of(data, "manifest"))
        src = rules_of(data, "source")
        for expected in ("net.client", "net.url", "net.webview"):
            self.assertIn(expected, src)
        self.assertTrue(
            any(r.startswith("deps.blocklist:com.squareup.okhttp3")
                for r in rules_of(data, "deps")))

    def test_suppression_is_reported_not_silent(self):
        _, data = run([PY, NET, "--root", self.CONTROL, "--no-default-excludes", "--json"])
        sup = next(d["suppressions"] for d in data["detectors"] if d["name"] == "source")
        self.assertEqual(len(sup), 1)
        self.assertTrue(sup[0]["reason"], "a suppression must carry a reason")

    def test_empty_tree_is_not_measured_never_pass(self):
        with tempfile.TemporaryDirectory() as empty:
            code, data = run([PY, NET, "--root", empty, "--json"])
            self.assertNotEqual(data["status"], "PASS")
            self.assertEqual(data["status"], "PASS-PARTIAL")
            for det in ("manifest", "source", "deps"):
                self.assertEqual(status(data, det), "NOT-MEASURED")
                self.assertEqual(denom(data, det), 0)
            self.assertEqual(code, 0, "non-strict: PASS-PARTIAL is not an error")

            code, _ = run([PY, NET, "--root", empty, "--strict", "--json"])
            self.assertEqual(code, 1, "strict: zero denominator must fail the build")

    def test_real_tree_is_clean_with_a_real_denominator(self):
        code, data = run([PY, NET, "--root", ROOT, "--json"])
        self.assertEqual(code, 0)
        self.assertNotEqual(data["status"], "FAIL")
        self.assertGreater(denom(data, "source"), 0, "must have examined real sources")

    def test_gate_declares_what_it_does_not_cover(self):
        _, data = run([PY, NET, "--root", ROOT, "--json"])
        self.assertGreaterEqual(len(data["not_covered"]), 3)


class TestForbiddenApiGate(unittest.TestCase):
    CONTROL = os.path.join(ROOT, "tools", "positive_controls", "forbidden_api")

    def test_all_four_planted_traps_fire(self):
        code, data = run([PY, API, "--root", self.CONTROL, "--no-default-excludes", "--json"])
        self.assertEqual(code, 1)
        found = rules_of(data)
        for rule in ("ims.session_override", "ic.return_branch",
                     "bksp.hardcoded", "ctx.blocking_fetch"):
            self.assertIn(rule, found, f"planted trap {rule} was not detected")

    def test_real_tree_clean(self):
        code, data = run([PY, API, "--root", ROOT, "--json"])
        self.assertEqual(code, 0)
        self.assertEqual(data["status"], "PASS")
        self.assertGreater(denom(data, "forbidden_api"), 0)


class TestOrchestrator(unittest.TestCase):
    def test_run_gates_passes_and_proves_each_control(self):
        p = subprocess.run([PY, os.path.join(ROOT, "scripts", "run_gates.py")],
                           capture_output=True, text=True, cwd=ROOT)
        self.assertEqual(p.returncode, 0, p.stdout + p.stderr)
        self.assertNotIn("NOT-A-GATE", p.stdout)
        for gate in ("GATE-NET-1", "GATE-API-1", "GATE-DENOM-1"):
            self.assertIn(gate, p.stdout)


if __name__ == "__main__":
    unittest.main(verbosity=2)

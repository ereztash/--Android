#!/usr/bin/env python3
"""GATE-NET-1 -- prove the shipped app has no network capability.

Three independent detectors, each with its own denominator:

  manifest  every AndroidManifest.xml -> any network-capable <uses-permission>
  source    every .kt/.java file      -> any network API, client library or transport
  deps      every resolved coordinate -> any known network-capable artifact

A detector that examined zero things reports NOT-MEASURED, never PASS.

Suppression: a source line may carry `NET-GATE-ALLOW: <reason>`. The reason is mandatory and
every suppression is printed in the report, so an exemption is visible rather than silent.
"""
from __future__ import annotations

import argparse
import os
import re
import sys
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gatelib import Detector, Finding, GateResult, report  # noqa: E402

ANDROID_NS = "{http://schemas.android.com/apk/res/android}"

# --- manifest detector -------------------------------------------------------------------
# Permissions that grant the app the ability to move bytes off the device, or to observe /
# manipulate the transports that do.
NETWORK_PERMISSIONS = {
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.ACCESS_WIFI_STATE",
    "android.permission.CHANGE_NETWORK_STATE",
    "android.permission.CHANGE_WIFI_STATE",
    "android.permission.CHANGE_WIFI_MULTICAST_STATE",
    "android.permission.NEARBY_WIFI_DEVICES",
    "android.permission.BLUETOOTH",
    "android.permission.BLUETOOTH_ADMIN",
    "android.permission.BLUETOOTH_CONNECT",
    "android.permission.BLUETOOTH_SCAN",
    "android.permission.BLUETOOTH_ADVERTISE",
    "android.permission.NFC",
    "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.ACCESS_COARSE_LOCATION",
    "android.permission.READ_PHONE_STATE",
    "android.permission.SEND_SMS",
    "android.permission.RECEIVE_SMS",
    "android.permission.READ_SMS",
}

# --- source detector ---------------------------------------------------------------------
SOURCE_RULES: list[tuple[str, str]] = [
    ("net.socket", r"\bjava\.net\.(?:Socket|ServerSocket|DatagramSocket|MulticastSocket)\b"),
    ("net.socket", r"\b(?:Server)?Socket\s*\("),
    ("net.socket", r"\bDatagramSocket\s*\("),
    ("net.url", r"\bjava\.net\.(?:URL|URLConnection|HttpURLConnection|URI)\b"),
    ("net.url", r"\bHttpsURLConnection\b"),
    ("net.url", r"\bHttpURLConnection\b"),
    ("net.url", r"\.openConnection\s*\("),
    ("net.url", r"\.openStream\s*\("),
    ("net.address", r"\bjava\.net\.(?:InetAddress|InetSocketAddress|Inet4Address|Inet6Address)\b"),
    ("net.address", r"\bInetAddress\s*\."),
    ("net.nio", r"\bjava\.nio\.channels\.(?:SocketChannel|ServerSocketChannel|DatagramChannel|AsynchronousSocketChannel)\b"),
    ("net.ssl", r"\bjavax\.net\.ssl\."),
    ("net.client", r"\bokhttp3\b|\bcom\.squareup\.okhttp\b"),
    ("net.client", r"\bretrofit2\b"),
    ("net.client", r"\bio\.ktor\.client\b"),
    ("net.client", r"\bcom\.android\.volley\b"),
    ("net.client", r"\borg\.apache\.http\b"),
    ("net.client", r"\bio\.grpc\b"),
    ("net.client", r"\bio\.netty\b"),
    ("net.webview", r"\bandroid\.webkit\.WebView\b|\bWebView\s*\(|\bWebViewClient\b|\bWebChromeClient\b"),
    ("net.android", r"\bandroid\.net\.ConnectivityManager\b|\bConnectivityManager\b"),
    ("net.android", r"\bandroid\.net\.wifi\b"),
    ("net.android", r"\bandroid\.net\.Uri\.parse\s*\(\s*[\"']https?://"),
    ("net.android", r"\bDownloadManager\b"),
    ("net.sdk", r"\bcom\.google\.firebase\b"),
    ("net.sdk", r"\bcom\.google\.android\.gms\b"),
    ("net.sdk", r"\bio\.sentry\b|\bcom\.bugsnag\b|\bcom\.crashlytics\b"),
    ("net.sdk", r"\bcom\.amplitude\b|\bcom\.mixpanel\b|\bcom\.segment\b"),
    ("net.sdk", r"\bcom\.amazonaws\b|\bsoftware\.amazon\.awssdk\b"),
    ("net.mail", r"\bjavax\.mail\b|\bjakarta\.mail\b"),
]
SOURCE_RULES_C = [(name, re.compile(pat)) for name, pat in SOURCE_RULES]
SUPPRESS_RE = re.compile(r"NET-GATE-ALLOW:\s*(?P<reason>\S.*?)\s*$")

# --- dependency detector -----------------------------------------------------------------
DEP_BLOCKLIST = [
    "com.squareup.okhttp3", "com.squareup.retrofit2", "com.squareup.wire",
    "io.ktor:ktor-client", "com.android.volley", "org.apache.httpcomponents",
    "io.grpc", "io.netty", "org.eclipse.jetty",
    "com.google.firebase", "com.google.android.gms", "com.google.android.play",
    "com.google.api-client", "com.google.http-client",
    "io.sentry", "com.bugsnag", "com.crashlytics",
    "com.amplitude", "com.mixpanel", "com.segment", "com.appsflyer", "com.adjust",
    "com.amazonaws", "software.amazon.awssdk",
    "com.facebook.android", "com.onesignal", "com.urbanairship",
    "androidx.webkit", "org.chromium",
]

DEFAULT_EXCLUDE_DIRS = {
    ".git", ".gradle", ".idea", ".kotlin", "build", "out",
    os.path.join("tools", "positive_controls"),
}

SOURCE_EXTS = (".kt", ".java")


def _walk(root: str, use_default_excludes: bool, extra_excludes: list[str]):
    excludes = set(extra_excludes)
    if use_default_excludes:
        excludes |= DEFAULT_EXCLUDE_DIRS
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


def scan_manifests(root, use_default_excludes, extra_excludes) -> Detector:
    det = Detector(name="manifest", unit="AndroidManifest.xml files", denominator=0)
    for dirpath, fn in _walk(root, use_default_excludes, extra_excludes):
        if fn != "AndroidManifest.xml":
            continue
        path = os.path.join(dirpath, fn)
        det.denominator += 1
        try:
            tree = ET.parse(path)
        except ET.ParseError as exc:
            det.findings.append(Finding("manifest", os.path.relpath(path, root), 0,
                                        f"unparseable manifest: {exc}", "manifest.parse"))
            continue
        raw = open(path, "r", encoding="utf-8", errors="replace").read().splitlines()
        for tag in ("uses-permission", "uses-permission-sdk-23"):
            for el in tree.getroot().iter(tag):
                name = el.get(ANDROID_NS + "name", "")
                if name in NETWORK_PERMISSIONS:
                    line = next((i + 1 for i, t in enumerate(raw) if name in t), 0)
                    det.findings.append(Finding(
                        "manifest", os.path.relpath(path, root), line,
                        f"<{tag}> {name}", "manifest.network_permission"))
    if det.denominator == 0:
        det.notes.append(
            "No AndroidManifest.xml exists in the scanned tree yet. Reported NOT-MEASURED, "
            "not PASS. This flips to a real measurement when an Android module lands.")
    return det


def scan_sources(root, use_default_excludes, extra_excludes) -> Detector:
    det = Detector(name="source", unit="Kotlin/Java source files", denominator=0)
    lines_scanned = 0
    for dirpath, fn in _walk(root, use_default_excludes, extra_excludes):
        if not fn.endswith(SOURCE_EXTS):
            continue
        path = os.path.join(dirpath, fn)
        det.denominator += 1
        rel = os.path.relpath(path, root)
        with open(path, "r", encoding="utf-8", errors="replace") as fh:
            for lineno, line in enumerate(fh, start=1):
                lines_scanned += 1
                sup = SUPPRESS_RE.search(line)
                hits = [n for n, rx in SOURCE_RULES_C if rx.search(line)]
                if not hits:
                    continue
                if sup:
                    det.suppressions.append({
                        "path": rel, "line": lineno,
                        "rules": sorted(set(hits)), "reason": sup.group("reason"),
                    })
                    continue
                for rule in sorted(set(hits)):
                    det.findings.append(Finding(
                        "source", rel, lineno, line.strip()[:160], rule))
    det.notes.append(f"{lines_scanned} source lines read")
    if det.denominator == 0:
        det.notes.append("No Kotlin/Java sources found. Reported NOT-MEASURED, not PASS.")
    return det


def scan_deps(root, coord_files: list[str]) -> Detector:
    det = Detector(name="deps", unit="resolved module coordinates", denominator=0)
    seen = set()
    files_read = 0
    for cf in coord_files:
        if not os.path.isfile(cf):
            continue
        files_read += 1
        with open(cf, "r", encoding="utf-8") as fh:
            for lineno, line in enumerate(fh, start=1):
                coord = line.strip()
                if not coord or coord.startswith("#"):
                    continue
                if coord in seen:
                    continue
                seen.add(coord)
                det.denominator += 1
                for bad in DEP_BLOCKLIST:
                    if coord.startswith(bad) or bad in coord:
                        det.findings.append(Finding(
                            "deps", os.path.relpath(cf, root), lineno, coord,
                            f"deps.blocklist:{bad}"))
                        break
    det.notes.append(f"{files_read} coordinate file(s) read")
    if det.denominator == 0:
        det.notes.append(
            "No resolved coordinates available. Run `./gradlew dependencyAudit` first. "
            "Reported NOT-MEASURED, not PASS.")
    return det


NOT_COVERED = [
    "Compiled bytecode and DEX are NOT scanned here. A dependency could contain network code "
    "whose coordinate is not on the blocklist. DEX scanning is GATE-NET-2 (separate).",
    "This gate does not prove a third-party artifact is network-free; it proves the artifact "
    "is not on a known-network blocklist. The blocklist is necessarily incomplete.",
    "Network access reached by reflection, a dynamically assembled class name, or native/JNI "
    "code is not detected by the source detector.",
    "Data leaving the device via IPC to another app that itself has INTERNET is not detected.",
    "Gradle build scripts are not scanned. Build-time downloads are out of scope; what must "
    "be offline is the shipped application.",
]


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", default=".")
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--strict", action="store_true",
                    help="treat PASS-PARTIAL (any NOT-MEASURED detector) as failure")
    ap.add_argument("--no-default-excludes", action="store_true",
                    help="scan build/ and tools/positive_controls too (used by the control run)")
    ap.add_argument("--exclude", action="append", default=[])
    ap.add_argument("--deps", action="append", default=[],
                    help="coordinate file(s); defaults to the dependencyAudit report")
    args = ap.parse_args()

    root = os.path.abspath(args.root)
    if not os.path.isdir(root):
        print(f"root does not exist: {root}", file=sys.stderr)
        return 2
    use_def = not args.no_default_excludes

    coord_files = list(args.deps)
    if not coord_files:
        coord_files = [os.path.join(root, "build", "reports", "dependency-audit",
                                    "coordinates.txt")]
        for dirpath, fn in _walk(root, use_def, args.exclude):
            if fn.endswith(".coordinates.txt"):
                coord_files.append(os.path.join(dirpath, fn))

    result = GateResult(
        gate="GATE-NET-1",
        description="no network capability in manifests, sources or declared dependencies",
        detectors=[
            scan_manifests(root, use_def, args.exclude),
            scan_sources(root, use_def, args.exclude),
            scan_deps(root, coord_files),
        ],
        not_covered=NOT_COVERED,
    )
    return report(result, args.json, args.strict)


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""GATE-NET-2 and GATE-MANIFEST-1 -- checks against the BUILT APK, not the source.

Source gates prove intent. This proves what actually shipped, after manifest merging, after
dependency resolution, after dexing.

Three detectors:

  apk_permissions   The merged manifest declares no network permission.
  apk_dex           No network class descriptor appears in the DEX beyond a committed
                    baseline.
  apk_ime_service   The IME service declaration is exactly what an IME needs (§1.8).

### Why the DEX detector uses a baseline instead of an absolute blocklist

Measured, not assumed: a *clean* debug build of this app already contains 12 network-ish
descriptors -- `java/net/Socket`, `java/net/InetAddress`, `android/webkit/WebView`,
`android/net/ConnectivityManager` and others -- pulled in transitively by androidx, Compose
and the Kotlin stdlib. They are references in the constant pool, not calls, and with no
INTERNET permission none of them can do anything. A gate that failed on their mere presence
would be red on a perfectly clean app, and the natural response to that -- deleting the rule --
would leave no gate at all.

So the gate reports the **delta** from a committed baseline. A descriptor that was not in the
clean build is a finding. This is characterising the environment, not tuning to pass, and the
evidence is that the positive control still goes red: the `netcontrol` APK adds
`java/net/HttpURLConnection` and `java/net/URLConnection`, neither of which is in the baseline.

Re-baseline deliberately, never to silence a finding, and only after establishing where a new
descriptor came from.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gatelib import Detector, Finding, GateResult, report  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BASELINE = os.path.join(ROOT, "tools", "apk_dex_baseline.json")

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
    "android.permission.READ_CONTACTS",
    "android.permission.GET_ACCOUNTS",
}

DEX_PREFIXES = [
    b"Ljava/net/", b"Ljavax/net/", b"Lokhttp3/", b"Lretrofit2/", b"Lokio/",
    b"Landroid/webkit/", b"Landroid/net/", b"Lcom/google/firebase/",
    b"Lcom/google/android/gms/", b"Lio/ktor/", b"Lio/grpc/", b"Lio/netty/",
    b"Lcom/android/volley/", b"Lorg/apache/http/", b"Lio/sentry/",
    b"Ljava/nio/channels/SocketChannel", b"Ljava/nio/channels/ServerSocketChannel",
    b"Ljava/nio/channels/DatagramChannel", b"Landroid/app/DownloadManager",
]
DEX_RE = re.compile(b"|".join(re.escape(p) + rb"[A-Za-z0-9_/$]*;" for p in DEX_PREFIXES))


def find_aapt2() -> str | None:
    home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not home:
        return None
    bt = os.path.join(home, "build-tools")
    if not os.path.isdir(bt):
        return None
    for version in sorted(os.listdir(bt), reverse=True):
        candidate = os.path.join(bt, version, "aapt2")
        if os.path.isfile(candidate):
            return candidate
    return None


def aapt2_dump(aapt2: str, what: list[str], apk: str) -> str:
    return subprocess.run([aapt2, "dump", *what, apk],
                          capture_output=True, text=True).stdout


def check_permissions(aapt2: str | None, apk: str, inject: bool) -> Detector:
    det = Detector(name="apk_permissions", unit="uses-permission entries", denominator=0)
    if aapt2 is None:
        det.notes.append("aapt2 not found (set ANDROID_HOME); NOT-MEASURED")
        return det
    out = aapt2_dump(aapt2, ["permissions"], apk)
    perms = re.findall(r"uses-permission: name='([^']+)'", out)
    if inject:
        perms.append("android.permission.INTERNET")
    det.denominator = len(perms)
    for p in perms:
        if p in NETWORK_PERMISSIONS:
            det.findings.append(Finding("apk_permissions", os.path.basename(apk), 0,
                                        f"uses-permission {p}", "apk.network_permission"))
    non_network = [p for p in perms if p not in NETWORK_PERMISSIONS]
    det.notes.append(f"declared permissions: {perms if perms else 'none'}")
    if non_network:
        det.notes.append(
            "note: AGP injects <app-id>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION into every "
            "build. It is a locally-defined signature permission, not a capability this app "
            "requested, and it grants no access off device.")
    if det.denominator == 0:
        det.notes.append("no permissions at all; denominator 0, so NOT-MEASURED not PASS")
    return det


def check_dex(apk: str, baseline: set[str], inject: bool) -> Detector:
    det = Detector(name="apk_dex", unit="DEX class descriptors", denominator=0)
    found: dict[str, int] = {}
    dex_files = 0
    dex_bytes = 0
    with zipfile.ZipFile(apk) as z:
        for name in z.namelist():
            if not name.endswith(".dex"):
                continue
            dex_files += 1
            data = z.read(name)
            dex_bytes += len(data)
            for m in DEX_RE.finditer(data):
                s = m.group(0).decode("ascii", "replace")
                found[s] = found.get(s, 0) + 1
    if inject:
        found["Lokhttp3/OkHttpClient;"] = 1

    det.denominator = len(found)
    novel = sorted(set(found) - baseline)
    for d in novel:
        det.findings.append(Finding(
            "apk_dex", os.path.basename(apk), 0,
            f"{d} appears in the DEX but is not in the committed baseline",
            "apk.dex_novel_network_class"))

    missing = sorted(baseline - set(found))
    det.notes.append(f"{dex_files} dex files, {dex_bytes} bytes scanned")
    det.notes.append(f"{len(found)} network-ish descriptors found, "
                     f"{len(baseline)} in baseline, {len(novel)} novel")
    if missing:
        det.notes.append(f"{len(missing)} baseline descriptors no longer present "
                         f"(drift, not a failure): {missing[:5]}")
    if dex_files == 0:
        det.notes.append("no DEX in the APK; denominator 0, so NOT-MEASURED not PASS")
        det.denominator = 0
    return det


IME_REQUIREMENTS = [
    ("exported", r'android:exported\(0x01010010\)=true', "android:exported must be true, "
     "or the app will not install on Android 12+"),
    ("permission", r'android:permission\(0x01010006\)="android\.permission\.BIND_INPUT_METHOD"',
     "the service must be guarded by the signature-level BIND_INPUT_METHOD permission"),
    ("action", r'android:name\(0x01010003\)="android\.view\.InputMethod"',
     "the intent-filter must declare the android.view.InputMethod action"),
    ("metadata", r'android:name\(0x01010003\)="android\.view\.im"',
     "the service must carry android.view.im meta-data pointing at the input-method XML"),
]


def check_ime_service(aapt2: str | None, apk: str, inject: bool) -> Detector:
    det = Detector(name="apk_ime_service", unit="IME service requirements", denominator=0)
    if aapt2 is None:
        det.notes.append("aapt2 not found (set ANDROID_HOME); NOT-MEASURED")
        return det
    tree = aapt2_dump(aapt2, ["xmltree", "--file", "AndroidManifest.xml"], apk)
    block = re.search(r"E: service \(line=\d+\)(.*?)(?=\n\s{10}E: (?:service|activity|receiver|provider) )",
                      tree, re.DOTALL)
    scope = block.group(1) if block else tree
    if inject:
        scope = scope.replace("=true", "=false")

    det.denominator = len(IME_REQUIREMENTS)
    for name, pattern, why in IME_REQUIREMENTS:
        if not re.search(pattern, scope):
            det.findings.append(Finding("apk_ime_service", os.path.basename(apk), 0,
                                        f"{name}: {why}", f"ime.missing_{name}"))
    det.notes.append(f"checked {len(IME_REQUIREMENTS)} requirements against the merged manifest")
    return det


NOT_COVERED = [
    "A DEX descriptor is a constant-pool reference, not a call. This gate proves a class is "
    "referenced, not that it is reachable or invoked.",
    "The baseline is specific to this dependency set and build type. A release build with "
    "minification produces a different set and must be re-baselined deliberately.",
    "Native code (.so) is not scanned. A JNI library could open a socket without any DEX "
    "reference -- though with no INTERNET permission it would still be refused by the kernel.",
    "Reflection and dynamically constructed class names are invisible here.",
    "This gate does not prove the app behaves correctly, only that it lacks the capability to "
    "reach the network and declares the IME service an IME needs.",
]


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--apk", default=os.path.join(
        ROOT, "app", "build", "outputs", "apk", "debug", "app-debug.apk"))
    ap.add_argument("--baseline", default=BASELINE)
    ap.add_argument("--write-baseline", action="store_true",
                    help="regenerate the DEX baseline from this APK. Deliberate act only.")
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--strict", action="store_true")
    ap.add_argument("--inject-defect", choices=["permission", "dex", "service"],
                    help="PLANT A DEFECT. Positive control; every value must go red.")
    args = ap.parse_args()

    if not os.path.isfile(args.apk):
        # An absent artifact is not a clean artifact. Report every detector as NOT-MEASURED
        # rather than erroring or, worse, passing.
        missing = GateResult(
            gate="GATE-NET-2 / GATE-MANIFEST-1",
            description=f"APK not built: {os.path.relpath(args.apk, ROOT)}",
            detectors=[
                Detector(name=n, unit=u, denominator=0,
                         notes=[f"APK absent ({args.apk}); run ./gradlew :app:assembleDebug"])
                for n, u in (("apk_permissions", "uses-permission entries"),
                             ("apk_dex", "DEX class descriptors"),
                             ("apk_ime_service", "IME service requirements"))
            ],
            not_covered=NOT_COVERED,
        )
        return report(missing, args.json, args.strict)

    aapt2 = find_aapt2()

    if args.write_baseline:
        found = check_dex(args.apk, set(), False)
        descriptors = sorted({f.evidence.split(" ")[0] for f in found.findings})
        with open(args.baseline, "w", encoding="utf-8") as fh:
            json.dump({
                "generated_from": os.path.relpath(args.apk, ROOT),
                "why": "A clean build legitimately references network classes transitively "
                       "via androidx/Compose/Kotlin stdlib. The gate reports the delta from "
                       "this set, so a genuinely new network path is a finding while the "
                       "unavoidable background is not. Re-baseline deliberately, after "
                       "establishing where a new descriptor came from -- never to silence a "
                       "finding.",
                "descriptors": descriptors,
            }, fh, indent=2)
            fh.write("\n")
        print(f"wrote {len(descriptors)} descriptors to {args.baseline}")
        return 0

    baseline: set[str] = set()
    if os.path.isfile(args.baseline):
        baseline = set(json.load(open(args.baseline, encoding="utf-8"))["descriptors"])

    result = GateResult(
        gate="GATE-NET-2 / GATE-MANIFEST-1",
        description=f"built APK has no network capability and declares a valid IME service "
                    f"({os.path.relpath(args.apk, ROOT)})",
        detectors=[
            check_permissions(aapt2, args.apk, args.inject_defect == "permission"),
            check_dex(args.apk, baseline, args.inject_defect == "dex"),
            check_ime_service(aapt2, args.apk, args.inject_defect == "service"),
        ],
        not_covered=NOT_COVERED,
    )
    return report(result, args.json, args.strict)


if __name__ == "__main__":
    raise SystemExit(main())

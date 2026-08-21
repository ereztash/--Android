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
import gzip
import hashlib
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
LEXICON_MANIFEST = os.path.join(ROOT, "lexicon", "MANIFEST.json")
BIGRAM_MANIFEST = os.path.join(ROOT, "lexicon", "BIGRAM_MANIFEST.json")

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


def check_lexicon_asset(apk: str, inject: str | None) -> Detector:
    """The lexicon inside the APK must be the lexicon the manifest describes.

    This is not paranoia about zip integrity. AGP **transparently gunzips** `.gz` assets while
    packaging: the repository holds `lexicon/assets/he_lexicon.txt.gz`, and the APK contains
    `assets/he_lexicon.txt`, plain UTF-8. The framing changes between source and artifact, so
    "the file is present" says nothing. What matters is that the *content* still hashes to what
    `scripts/build_lexicon.py` produced -- otherwise the keyboard could ship a silently
    different dictionary from the one every measurement in docs/LEXICON_MEASUREMENTS.md was
    taken against.
    """
    det = Detector(name="apk_lexicon", unit="packaged lexicon assets", denominator=0)
    if not os.path.isfile(LEXICON_MANIFEST):
        det.notes.append("lexicon/MANIFEST.json missing; NOT-MEASURED")
        return det
    expected = json.load(open(LEXICON_MANIFEST, encoding="utf-8"))["output"]

    # The Kotlin side opens these by EXACT name. AGP strips the .gz while packaging, so the
    # names in the APK are not the names in the repository, and a rename here would be a
    # runtime crash on first suggestion rather than a build error.
    expected_names = {
        "assets/he_lexicon.txt", "assets/he_freq.bin", "assets/he_bigrams.bin",
        "assets/he_abbreviations.txt",
    }
    if inject == "asset_name":
        # PLANTED DEFECT: pretend the code expects a name AGP does not produce, which is what
        # a rename or a change in AGP's .gz handling would look like.
        expected_names = {"assets/he_lexicon.txt.gz"}

    with zipfile.ZipFile(apk) as z:
        present = set(z.namelist())
        for required in sorted(expected_names):
            if required not in present:
                det.findings.append(Finding(
                    "apk_lexicon", os.path.basename(apk), 0,
                    f"{required} is not in the APK; CorrectionController opens it by this "
                    f"exact name and would crash on first use",
                    "apk.missing_named_asset"))

        names = [n for n in z.namelist()
                 if n.startswith("assets/") and "he_lexicon" in n]
        if not names:
            det.notes.append("no lexicon asset found in the APK; NOT-MEASURED")
            return det
        det.denominator = len(names) + len(expected_names)
        for name in names:
            data = z.read(name)
            if data[:2] == b"\x1f\x8b":
                data = gzip.decompress(data)
            if inject == "content":
                data = data + b"\n"
            digest = hashlib.sha256(data).hexdigest()
            det.notes.append(f"{name}: {len(data)} bytes uncompressed, sha256 {digest}")
            if digest != expected["uncompressed_sha256"]:
                det.findings.append(Finding(
                    "apk_lexicon", os.path.basename(apk), 0,
                    f"{name} hashes to {digest}, but lexicon/MANIFEST.json says "
                    f"{expected['uncompressed_sha256']}",
                    "apk.lexicon_mismatch"))
            words = data.count(b"\n")
            if words != expected.get("word_count", words):
                det.findings.append(Finding(
                    "apk_lexicon", os.path.basename(apk), 0,
                    f"{name} holds {words} lines, manifest says "
                    f"{expected.get('word_count')}", "apk.lexicon_count"))
    return det


# The two count tables, each with the manifest that describes the exact bytes every number in
# the docs was measured on. Both are loaded through the same reader and both degrade to
# BigramModel.EMPTY when absent, so both fail the same silent way.
COUNT_TABLES = [
    ("he_bigrams", BIGRAM_MANIFEST, "lexicon/BIGRAM_MANIFEST.json", "bigram_content",
     "CorrectionController opens assets/he_bigrams.bin by that exact name and degrades to "
     "BigramModel.EMPTY when it is absent, so the app would run and quietly score prefix-1 "
     "top-3 2.15% instead of 5.73% with nothing at runtime reporting a problem"),
]


def check_bigram_asset(apk: str, inject: str | None) -> Detector:
    """The count tables inside the APK must be the ones every number was measured on.

    Same argument as `check_lexicon_asset`, one step further along. The accuracy figures in
    `docs/PREDICTION_MEASUREMENTS.md` and `docs/CONFUSION_MEASUREMENTS.md` -- and the floors
    the accuracy tests enforce -- are properties of specific tables built from a specific dump.
    Ship a different one and every number becomes a claim about a file that is not in the app.

    The failure this actually guards against is silent: `BigramModel.EMPTY` exists so that a
    missing table degrades instead of crashing. That is the right runtime behaviour and the
    wrong thing to discover in production, so the absence is caught here, on the artifact,
    rather than by a user noticing the keyboard got worse.
    """
    det = Detector(name="apk_bigrams", unit="packaged count tables", denominator=0)
    with zipfile.ZipFile(apk) as z:
        namelist = z.namelist()
        for stem, manifest_path, manifest_label, inject_key, absence in COUNT_TABLES:
            if not os.path.isfile(manifest_path):
                det.notes.append(f"{manifest_label} missing; {stem} NOT-MEASURED")
                continue
            manifest = json.load(open(manifest_path, encoding="utf-8"))["model"]
            names = [n for n in namelist
                     if n.startswith("assets/") and stem in n]
            if not names:
                det.findings.append(Finding(
                    "apk_bigrams", os.path.basename(apk), 0,
                    f"no {stem} table in the APK; {absence}",
                    "apk.missing_bigram_asset"))
                det.denominator += 1
                continue

            det.denominator += len(names)
            for name in names:
                data = z.read(name)
                if data[:2] == b"\x1f\x8b":
                    data = gzip.decompress(data)
                if inject == inject_key:
                    data = data + b"\x00"
                digest = hashlib.sha256(data).hexdigest()
                det.notes.append(
                    f"{name}: {len(data)} bytes uncompressed, sha256 {digest}")
                if digest != manifest["raw_sha256"]:
                    det.findings.append(Finding(
                        "apk_bigrams", os.path.basename(apk), 0,
                        f"{name} hashes to {digest}, but {manifest_label} says "
                        f"{manifest['raw_sha256']}", "apk.bigram_mismatch"))
                if len(data) != manifest["raw_bytes"]:
                    det.findings.append(Finding(
                        "apk_bigrams", os.path.basename(apk), 0,
                        f"{name} is {len(data)} bytes, manifest says "
                        f"{manifest['raw_bytes']}", "apk.bigram_size"))
                # Parse the header the way BigramModel.load does, so a table that is present
                # and correctly hashed but structurally wrong is still caught.
                if len(data) >= 4:
                    groups = int.from_bytes(data[0:4], "little")
                    det.notes.append(f"{name}: header declares {groups} groups, "
                                     f"manifest says {manifest['groups']}")
                    if groups != manifest["groups"]:
                        det.findings.append(Finding(
                            "apk_bigrams", os.path.basename(apk), 0,
                            f"{name} header declares {groups} groups, manifest says "
                            f"{manifest['groups']}", "apk.bigram_groups"))
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
    "reach the network, declares the IME service an IME needs, and ships the lexicon its "
    "manifest describes.",
    "The lexicon detector checks the artifact's bytes, not its linguistic quality. Coverage "
    "and accuracy live in docs/LEXICON_MEASUREMENTS.md and M5.",
    "The bigram detector checks that the shipped table is byte-identical to the measured one "
    "and that its header agrees with the manifest. It does NOT check that the model is any "
    "good -- prediction accuracy is measured in PredictionAccuracyTest against a held-out "
    "corpus, and neither check substitutes for the other.",
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
    ap.add_argument("--inject-defect",
                    choices=["permission", "dex", "service", "lexicon", "asset_name",
                             "bigram_content"],
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
                             ("apk_ime_service", "IME service requirements"),
                             ("apk_lexicon", "packaged lexicon assets"),
                             ("apk_bigrams", "packaged count tables"))
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
            check_lexicon_asset(
                args.apk,
                {"lexicon": "content", "asset_name": "asset_name"}.get(args.inject_defect),
            ),
            check_bigram_asset(args.apk, args.inject_defect),
        ],
        not_covered=NOT_COVERED,
    )
    return report(result, args.json, args.strict)


if __name__ == "__main__":
    raise SystemExit(main())

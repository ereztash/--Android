# Positive control for GATE-NET-1

This directory contains **deliberately planted defects**. It is excluded from every real scan
(`scripts/check_no_network.py` hard-excludes `tools/positive_controls`) and is compiled by
nothing. It exists for one reason: to prove the gate can go red.

A gate that has never failed has not been shown to be a gate. CI runs the gate against this
directory on every push and **fails the build if the gate reports PASS here**.

Planted defects, one per detector:

| Detector | Planted defect | Expected rule |
|---|---|---|
| manifest | `android.permission.INTERNET` in `AndroidManifest.xml` | `manifest.network_permission` |
| source | `import okhttp3.OkHttpClient` | `net.client` |
| source | `java.net.URL(...)` + `.openConnection()` | `net.url` |
| source | `android.webkit.WebView` | `net.webview` |
| deps | `com.squareup.okhttp3:okhttp:4.12.0` | `deps.blocklist:com.squareup.okhttp3` |

The file also carries one **suppressed** hit, to prove that suppression is reported rather
than silent.

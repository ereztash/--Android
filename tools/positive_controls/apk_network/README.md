# Positive control for GATE-NET-2 (built-APK network scan)

This is the source set for the `netcontrol` build type. Assembling it produces a **real APK**
carrying a **real** `INTERNET` permission in its merged manifest and **real** `java.net`
usage compiled into its DEX.

Why go to the trouble of building a second APK rather than pointing the scanner at a fixture
file: a fixture that merely *looks* like a merged manifest and a text file that merely *looks*
like DEX strings would prove nothing about the scanner's ability to read an actual APK. The
gate's job is to reject a defective build artifact, so the control has to be a defective build
artifact.

`java.net.HttpURLConnection` is used rather than OkHttp on purpose: it needs no dependency, so
this control cannot itself introduce a network-capable coordinate into the build.

These sources live under `tools/positive_controls/`, which every source-scanning gate already
excludes, so wiring them into a build type does not weaken GATE-NET-1 or GATE-API-1.

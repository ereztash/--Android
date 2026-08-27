# E4 — the emulator claim, tested

`docs/OPERATOR_NOTICES.md` NOTICE 6 has read, since M0:

> **NOTICE 6 — No device or emulator exists in this environment.**
> **Status: OPEN — structural limit of this environment, not a task that can be finished here.**

Twenty-two rows in `QA_MATRIX.md` are blocked behind it. It had never been tested. This
document tests it, because "structural limit" is a strong claim and the repository's own rule is
that an assertion is not evidence.

## What was done

| step | result |
|---|---|
| `sdkmanager --list` for `emulator` and `system-images;android-36` | **both fetchable** through the proxy |
| install `emulator` 37.1.11 + `system-images;android-36;google_apis;x86_64` | **succeeded**, 4.3 GB |
| `avdmanager create avd -d pixel_6` | **created**, 1080×2400 at 420 dpi |
| boot headless, `-accel off -gpu swiftshader_indirect -no-window` | **`sys.boot_completed=1`** after ~15 minutes |
| `adb install app-debug.apk` | **Success** |
| `ime enable` + `ime set` | **`default_input_method = com.hebrewime/.ime.HebrewImeService`** |

**The first half of NOTICE 6 is false.** An emulator runs here, Android 36 boots, and the
keyboard installs and is selected as the system IME.

That last row is worth noting on its own. `M7-LAT` records that the benchmark needs the IME
"enabled and selected as the active IME — which a benchmark cannot arrange for itself without
`WRITE_SECURE_SETTINGS`." **`adb shell ime set` arranges it in one line.** Whatever else blocks
`M7-LAT`, that particular obstacle is not real.

## Where it stopped

```
ANR in com.hebrewime
Reason: Process ... failed to complete startup
Load: 39.11 / 35.08 / 21.92
/proc/pressure/cpu: some avg10=89.98 avg60=91.06 avg300=88.01
```

A load average of 39 and 90% CPU stall on a CPU being emulated instruction-by-instruction.
**This is the environment, not a defect in the app, and it is not recorded as one.** The
warm-up path loads a 355,587-word lexicon and builds a 567,767-node trie; under TCG that does
not finish inside Android's startup timeout.

Total evidence accumulated in `DeviceEvidence`:

```xml
<int name="dictionary_reloads" value="1" />
```

`onStartInput` fired once. The keyboard never laid out. **No insets, no label-fit, no
restricted-field evidence. Zero device-blocked rows closed.**

## Two further attempts, because one configuration is not a conclusion

The first run used the `google_apis` image on default cores while the host was still doing
dexopt. Both are fixable, so they were fixed.

| # | image | cores | host | boot | outcome |
|---|---|---|---|---|---|
| 1 | `google_apis` | default | busy | ~15 min | ANR **`com.hebrewime`** — failed to complete startup |
| 2 | `google_apis` | 4 | idle | **~5 min** | guest load *rose* 25→36 and never settled |
| 3 | **`default` (AOSP, no GMS)** | 4 | idle | ~8 min | load *fell* 30→21, then ANR **`com.android.launcher3`** |

Four vCPUs cut boot from 15 minutes to 5, so the core count was a real constraint and is no
longer one. Dropping Google Play services changed the load's *direction* — with `google_apis`
it climbed and stayed climbing; with AOSP it fell steadily. Both were genuine improvements and
**neither was sufficient.**

The third attempt is the one that settles it. On the lightest available image, on all four
cores, on an idle host, with the guest's own load trending down, **the launcher itself** could
not stay responsive. At that point the thing failing is not the app, not the image and not the
configuration.

## What this establishes, and what it does not

**NOTICE 6 was half wrong, and the right half is now measured rather than asserted.** The limit
is not that an emulator cannot exist here. It is that **no hardware-accelerated emulator is
available** — `/dev/kvm` is absent and `/proc/cpuinfo` carries neither `vmx` nor `svm` — and an
unaccelerated one is too slow for this particular app to finish starting.

**The distinction that matters for anyone who tries again:**

- **Geometry is emulator-safe.** Insets and label fit are recorded on layout and do not depend
  on how fast the CPU is. The AVD's 1080×2400 at 420 dpi is *exactly* the geometry
  `LabelFitTest` derived from the operator's screenshot. With KVM, `M2-INSETS` and
  `MI-LABELFIT` look reachable. **Plausible, not demonstrated** — the app never got far enough
  to lay out, so this is an inference from what the instrumentation records, not a result.
- **Timing is not emulator-safe.** `M7-LAT` cannot be answered on any emulator; a p95 measured
  on a software-emulated CPU is a number about the host.
- **Hardware backing is not emulator-safe.** `M6-KEYSTORE` and `L1-KEYSTORE` ask about TEE and
  StrongBox. An emulator has no TEE, so a green result there would be meaningless.
- **Perception is not emulator-safe.** `M7-CONTRAST`, `MI-CONFIRM`, `R1-FEEL`, `L2-PERSONAL`
  need a person, not a screen buffer.

So the 22 do not all have the same blocker, and NOTICE 6 has been treating them as if they did.

## Not measured

- **Whether an accelerated emulator would in fact close any row.** Nothing here demonstrates it.
  The only honest claim is that the geometry rows are not blocked by the *reason* the rest are.
- **Whether the ANR would also occur on a slow real device.** The warm-up is heavy, and this
  says nothing about a low-end phone one way or the other. It was not measured and is not
  claimed.
- **Three configurations were tried, not one.** That is enough to attribute the failure to the
  absent hypervisor rather than to a choice, but it is still one host. A different unaccelerated
  host is not covered.
- `system-images;android-36;default;x86_64` (AOSP) remains installed so a future session with
  KVM need not re-download it. The `google_apis` image was deleted: it is measurably the worse
  choice here and was costing 4.3 GB.

package com.hebrewime.diagnostics

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import androidx.core.content.res.ResourcesCompat
import com.hebrewime.R
import com.hebrewime.core.selfcheck.CheckArithmetic
import com.hebrewime.core.selfcheck.SelfCheck
import com.hebrewime.core.selfcheck.SelfCheck.Status
import com.hebrewime.core.selfcheck.SelfCheckReport
import com.hebrewime.dictionary.KeystoreKeyProvider
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory

/**
 * Runs the checks a phone can answer about itself, and proves each one can fail.
 *
 * ### The shape, and why it is this shape
 * `scripts/run_gates.py` runs every gate's positive control **before** the gate, and reports
 * `NOT-A-GATE` if the control comes back green. That rule is the reason this repository's gate
 * results are worth reading. It applies here for exactly the same reason: a self-check that
 * always says PASS is a green light with nothing behind it, and on a phone there is no build
 * server to notice.
 *
 * So [run] evaluates every check twice -- once against the device, once against a planted
 * defect -- and any check that stayed green under injection is downgraded to
 * [Status.NOT_A_GATE] and counts as evidence for nothing.
 *
 * ### What this does NOT replace
 * A person. Nothing here can tell whether the keyboard feels fast, whether a suggestion was
 * useful, or whether a shrunk label is still readable. Those rows stay device-blocked and stay
 * human. What this removes is the rows that were only ever arithmetic and were waiting on
 * someone to be holding the phone at the right moment.
 */
object DeviceSelfCheck {

    /**
     * The p95 keystroke budget, in microseconds.
     *
     * 50 ms is the target the build spec set and `docs/RELEASE_READINESS.md` reports as
     * unverified. It is a budget, not a measurement, and this check is the first thing that
     * can turn it into one.
     */
    const val LATENCY_BUDGET_MICROS = 50_000L

    fun run(context: Context): SelfCheckReport {
        val evidence = DeviceEvidence.read(context)
        val real = checks(context, evidence, inject = false)
        val injected = checks(context, evidence, inject = true).associateBy { it.id }

        val proven = real.map { check ->
            val control = injected[check.id]
            when {
                // A control that could not be evaluated cannot prove anything either way; the
                // check keeps its own status, which for an unmeasured check is already honest.
                control == null -> check
                check.status != Status.PASS -> check
                control.status == Status.FAIL -> check
                else -> check.copy(
                    status = Status.NOT_A_GATE,
                    measured = "${check.measured} - BUT its control stayed " +
                        "${control.status.name}, so this PASS proves nothing",
                )
            }
        }
        return SelfCheckReport(proven, deviceContext(context))
    }

    private fun deviceContext(context: Context) = listOf(
        "android" to "API ${Build.VERSION.SDK_INT}",
        "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
        // Read from the package manager rather than a compile-time constant, so the report
        // names the build that is INSTALLED. A screenshot cannot identify a build; this line
        // is what makes a self-check report able to.
        "build" to installedVersion(context),
        "screen" to "${context.resources.displayMetrics.widthPixels}x" +
            "${context.resources.displayMetrics.heightPixels} @ " +
            "${context.resources.displayMetrics.density}x",
        "night mode" to if (isNight(context)) "on" else "off",
    )

    private fun installedVersion(context: Context): String = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName} (code ${info.longVersionCode})"
    } catch (missing: PackageManager.NameNotFoundException) {
        "unknown (${missing.javaClass.simpleName})"
    }

    private fun isNight(context: Context): Boolean =
        (context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    private fun checks(
        context: Context,
        e: DeviceEvidence.Snapshot,
        inject: Boolean,
    ): List<SelfCheck> = listOf(
        insets(e, inject),
        network(context, inject),
        restrictedFields(e, inject),
        keystore(inject),
        contrast(context, inject),
        latency(e, inject),
        typeface(context, e, inject),
        labelFit(e, inject),
        reload(e, inject),
        microInteractions(e, inject),
    )

    // ------------------------------------------------------------------ M2-INSETS

    private fun insets(e: DeviceEvidence.Snapshot, inject: Boolean): SelfCheck {
        val id = "M2-INSETS"
        val q = "does the bottom key row clear the system gesture inset at targetSdk 36?"
        val nc = "one device, one orientation, one navigation mode. A three-button device and " +
            "a gesture device do not have the same inset, and neither does landscape."
        if (!e.insetSeen) {
            return SelfCheck(id, q, Status.NOT_MEASURED,
                             "the keyboard has not been laid out since evidence was reset", nc)
        }
        // PLANTED DEFECT: the keys drawn to the window's bottom edge, which is what the
        // keyboard would look like if the inset padding were removed.
        val lowest = if (inject) e.viewHeight.toFloat() else e.lowestKeyBottom
        val clearance = CheckArithmetic.gestureClearance(e.viewHeight, lowest, e.bottomInset)
        return SelfCheck(
            id, q,
            if (clearance >= 0f) Status.PASS else Status.FAIL,
            "view ${e.viewHeight}px, lowest key ends ${lowest}px, bottom inset " +
                "${e.bottomInset}px -> clearance ${clearance}px",
            nc,
        )
    }

    // ------------------------------------------------------------------ M8-NETCAPTURE

    private fun network(context: Context, inject: Boolean): SelfCheck {
        val id = "M8-NETPERM"
        val q = "does the installed package hold any network permission on this device?"
        val nc = "This is the PERMISSION as the package manager sees it, not a packet capture. " +
            "It proves the OS would refuse a socket; it does not prove no attempt was made, " +
            "and M8-NETCAPTURE stays open."
        val pm = context.packageManager
        val declared = try {
            pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions.orEmpty().toList()
        } catch (missing: PackageManager.NameNotFoundException) {
            return SelfCheck(id, q, Status.NOT_MEASURED, "package not found: $missing", nc)
        }
        val networkish = declared.filter {
            it == android.Manifest.permission.INTERNET ||
                it == android.Manifest.permission.ACCESS_NETWORK_STATE ||
                it == android.Manifest.permission.ACCESS_WIFI_STATE
        }
        // PLANTED DEFECT: pretend INTERNET is declared.
        val found = if (inject) networkish + android.Manifest.permission.INTERNET else networkish
        return SelfCheck(
            id, q,
            if (found.isEmpty()) Status.PASS else Status.FAIL,
            if (found.isEmpty()) "${declared.size} permissions declared, none network"
            else "network permissions present: $found",
            nc,
        )
    }

    // ------------------------------------------------------------------ M4-DEVICE

    private fun restrictedFields(e: DeviceEvidence.Snapshot, inject: Boolean): SelfCheck {
        val id = "M4-DEVICE"
        val q = "on fields the policy restricts, was anything ever suggested or any initial " +
            "text ever read?"
        val nc = "Counts opportunities the keyboard SAW. It cannot prove the framework did not " +
            "hand plaintext to some other component, and it says nothing about field types " +
            "this device's apps never presented."
        // PLANTED DEFECT: one served restricted field.
        val served = if (inject) e.restrictedServed + 1 else e.restrictedServed
        val verdict = CheckArithmetic.restrictedFieldVerdict(e.restrictedSeen, served)
            ?: return SelfCheck(
                id, q, Status.NOT_MEASURED,
                "no restricted field has been seen yet - type one character into a password " +
                    "field to make this measurable",
                nc,
            )
        val taken = if (inject) e.initialTextTaken + 1 else e.initialTextTaken
        val clean = verdict && taken == 0
        return SelfCheck(
            id, q,
            if (clean) Status.PASS else Status.FAIL,
            "${e.restrictedSeen} restricted fields seen, $served served, " +
                "$taken initial-text reads",
            nc,
        )
    }

    // ------------------------------------------------------------------ M6/L1-KEYSTORE

    private fun keystore(inject: Boolean): SelfCheck {
        val id = "M6-KEYSTORE"
        val q = "are the two Keystore aliases real, independent, and hardware-backed?"
        val nc = "Reports what KeyInfo says. A device that lies about its security level lies " +
            "to this too. It also does not test destruction on wipe, which needs a wipe."
        return try {
            val aliases = listOf(
                KeystoreKeyProvider.KEY_ALIAS,
                KeystoreKeyProvider.LEARNING_KEY_ALIAS,
            )
            // `exists`, never `getOrCreate`. The first version of this check called
            // getOrCreate, which MINTS the key when it is absent -- so merely opening the
            // self-check would have created a learning key for a user who never turned
            // learning on, and a dictionary key for one who never added a word. Settings
            // already refuses to load the learned model while the feature is off, for exactly
            // this reason: touching the Keystore for data the user has not asked the app to
            // use is not a diagnostic, it is a side effect.
            //
            // A key that does not exist yet is NOT-MEASURED. That is the honest answer and it
            // is also the useful one: it says "turn the feature on, then ask again".
            val existing = aliases.filter { KeystoreKeyProvider.exists(it) }
            if (existing.isEmpty()) {
                return SelfCheck(
                    id, q, Status.NOT_MEASURED,
                    "neither key exists yet - add a word to your dictionary, or turn learning " +
                        "on, and run this again. Creating one here to measure it would be a " +
                        "side effect, not a measurement",
                    nc,
                )
            }
            val levels = existing.map { alias ->
                alias to securityLevelOf(KeystoreKeyProvider.getOrCreate(alias))
            }
            val distinct = levels.map { it.first }.distinct().size == levels.size &&
                levels.size == aliases.size
            // PLANTED DEFECT: report the key as living in software.
            val described = if (inject) levels.map { it.first to "SOFTWARE (injected)" }
            else levels
            val hardware = described.all { !it.second.startsWith("SOFTWARE") }
            SelfCheck(
                id, q,
                if (hardware && distinct) Status.PASS else Status.FAIL,
                described.joinToString("; ") { "${it.first}: ${it.second}" } +
                    if (existing.size < aliases.size)
                        "  (${aliases.size - existing.size} alias not created yet, so " +
                            "independence is not yet demonstrable)"
                    else "",
                nc,
            )
        } catch (unavailable: Throwable) {
            SelfCheck(id, q, Status.NOT_MEASURED,
                      "Keystore unavailable: ${unavailable.javaClass.simpleName}", nc)
        }
    }

    /**
     * Where the key material lives, as the platform reports it.
     *
     * `KeyInfo.getSecurityLevel()` is API 31 and this app's minSdk is 31, so the deprecated
     * `isInsideSecureHardware()` path is not needed.
     */
    private fun securityLevelOf(key: SecretKey): String = try {
        val factory = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore")
        val info = factory.getKeySpec(key, KeyInfo::class.java) as KeyInfo
        // The platform's own constants, not local copies of their current values. The first
        // version hardcoded 0/1/2 "so the when() reads as a table"; lint's SwitchIntDef caught
        // it. A security level is exactly the wrong thing to compare against a number this
        // file believes the platform uses -- the failure mode is a report that calmly says
        // TEE about a key living in software.
        when (info.securityLevel) {
            KeyProperties.SECURITY_LEVEL_STRONGBOX -> "STRONGBOX"
            KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "TEE"
            KeyProperties.SECURITY_LEVEL_SOFTWARE -> "SOFTWARE"
            KeyProperties.SECURITY_LEVEL_UNKNOWN -> "UNKNOWN"
            KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE -> "UNKNOWN-BUT-SECURE"
            else -> "UNRECOGNISED(${info.securityLevel})"
        }
    } catch (unknown: Throwable) {
        "UNREADABLE(${unknown.javaClass.simpleName})"
    }

    // ------------------------------------------------------------------ M7-CONTRAST

    private fun contrast(context: Context, inject: Boolean): SelfCheck {
        val id = "M7-CONTRAST"
        val q = "do the key colours THIS DEVICE resolved clear WCAG AA for large text?"
        val nc = "Arithmetic on the resolved colour values. It cannot see the panel: a display " +
            "with an aggressive colour profile, a night filter or a low brightness can be " +
            "illegible at a ratio that passes here."
        val pairs = listOf(
            "label on key" to (R.color.key_label to R.color.key_background),
            "label on function key" to (R.color.key_label to R.color.key_background_function),
            "pressed label" to (R.color.key_label_pressed to R.color.key_background_pressed),
            "correction on strip" to
                (R.color.candidate_correction to R.color.keyboard_background),
        )
        val results = pairs.map { (name, res) ->
            val fg = context.getColor(res.first)
            // PLANTED DEFECT: draw the label in the key's own colour.
            val bg = if (inject) fg else context.getColor(res.second)
            name to CheckArithmetic.contrastRatio(fg, bg)
        }
        if (results.any { it.second == null }) {
            return SelfCheck(id, q, Status.NOT_MEASURED,
                             "a colour resolved with alpha; no ratio is derivable", nc)
        }
        val worst = results.minBy { it.second!! }
        val pass = worst.second!! >= CheckArithmetic.AA_LARGE_TEXT
        return SelfCheck(
            id, q,
            if (pass) Status.PASS else Status.FAIL,
            "worst pair '${worst.first}' = ${CheckArithmetic.ratio(worst.second!!)}:1 " +
                "(AA large text needs ${CheckArithmetic.AA_LARGE_TEXT})",
            nc,
        )
    }

    // ------------------------------------------------------------------ M7-LAT

    private fun latency(e: DeviceEvidence.Snapshot, inject: Boolean): SelfCheck {
        val id = "M7-LAT"
        val q = "is p95 keystroke latency inside the 50 ms budget on this phone?"
        val nc = "Measures the keyboard's own work: plan, batch edit, learn, refresh. It does " +
            "NOT include the frame the system draws afterwards, so a person's perceived " +
            "latency is this plus a vsync, and it is not the benchmark harness's TraceSection " +
            "figure either."
        if (e.latencyN < CheckArithmetic.MIN_SAMPLES_FOR_P95) {
            return SelfCheck(
                id, q, Status.NOT_MEASURED,
                "${e.latencyN} keystrokes recorded; a p95 needs at least " +
                    "${CheckArithmetic.MIN_SAMPLES_FOR_P95} or it is just the maximum",
                nc,
            )
        }
        // PLANTED DEFECT: a p95 one microsecond over budget.
        val p95 = if (inject) LATENCY_BUDGET_MICROS + 1 else e.latencyP95Micros
        return SelfCheck(
            id, q,
            if (p95 <= LATENCY_BUDGET_MICROS) Status.PASS else Status.FAIL,
            "n=${e.latencyN}  p50=${micros(e.latencyP50Micros)}  p95=${micros(p95)}  " +
                "max=${micros(e.latencyMaxMicros)}  budget=${micros(LATENCY_BUDGET_MICROS)}",
            nc,
        )
    }

    private fun micros(v: Long): String = "${v / 1000}.${(v % 1000) / 100}ms"

    // ------------------------------------------------------------------ R2-FONT

    private fun typeface(context: Context, e: DeviceEvidence.Snapshot, inject: Boolean): SelfCheck {
        val id = "R2-FONT"
        val q = "did the measured typeface actually load, rather than falling back?"
        val nc = "Proves the resource resolved to a Typeface. It does not prove the glyphs " +
            "drawn are the ones the letter-pair measurement ranked - only GATE-FONT-1's " +
            "content hash does that, and only for the bytes in the APK."
        val loaded = try {
            ResourcesCompat.getFont(context, R.font.keyboard_label) != null
        } catch (missing: Throwable) {
            false
        }
        // PLANTED DEFECT: the font failing to resolve.
        val effective = if (inject) false else loaded
        return SelfCheck(
            id, q,
            if (effective) Status.PASS else Status.FAIL,
            if (effective) "R.font.keyboard_label resolved" +
                (if (e.typefaceLoaded) " and the keyboard used it" else "; the keyboard has " +
                    "not drawn since evidence was reset")
            else "the font did not resolve; the keyboard is drawing in the platform default",
            nc,
        )
    }

    // ------------------------------------------------------------------ MI-LABELFIT

    private fun labelFit(e: DeviceEvidence.Snapshot, inject: Boolean): SelfCheck {
        val id = "MI-LABELFIT"
        val q = "does every key label fit inside its key at the size this device drew it?"
        val nc = "Measures advance against available width. Whether a SHRUNK label is still " +
            "readable is not arithmetic and stays a human check."
        if (e.labelsTotal == 0) {
            return SelfCheck(id, q, Status.NOT_MEASURED,
                             "the keyboard has not drawn since evidence was reset", nc)
        }
        // PLANTED DEFECT: one label over its key.
        val over = if (inject) e.labelsOverflowing + 1 else e.labelsOverflowing
        return SelfCheck(
            id, q,
            if (over == 0) Status.PASS else Status.FAIL,
            "$over of ${e.labelsTotal} labels overflow; widest label fills " +
                "${CheckArithmetic.ratio(e.labelWorstRatio.toDouble() * 100)}% of its key",
            nc,
        )
    }

    // ------------------------------------------------------------------ M12-RELOAD

    private fun reload(e: DeviceEvidence.Snapshot, inject: Boolean): SelfCheck {
        val id = "M12-RELOAD"
        val q = "does the personal dictionary reload while the IME is running?"
        val nc = "Counts that the reload path RAN. Whether the word then stops being " +
            "underlined is what the user sees, and that stays a human check."
        // PLANTED DEFECT: the reload never happening.
        val reloads = if (inject) 0 else e.dictionaryReloads
        return if (reloads == 0) {
            SelfCheck(id, q, if (inject) Status.FAIL else Status.NOT_MEASURED,
                      "the reload path has not run since evidence was reset - add a word in " +
                          "Settings, then type in any app", nc)
        } else {
            SelfCheck(id, q, Status.PASS, "$reloads reloads performed", nc)
        }
    }

    // ------------------------------------------------------------------ MI-*

    private fun microInteractions(e: DeviceEvidence.Snapshot, inject: Boolean): SelfCheck {
        val id = "MI-COUNTERS"
        val q = "have the micro-interaction paths executed at all on this device?"
        val nc = "Counts EXECUTIONS, not quality. MI-PREVIEW, MI-REPEAT and MI-LONGPRESS ask " +
            "whether they feel right, and a counter cannot answer that. A zero here does mean " +
            "the path has never run, which is the half a counter can settle."
        val previews = if (inject) 0 else e.previewsShown
        val repeats = if (inject) 0 else e.backspaceRepeats
        val longs = if (inject) 0 else e.longPresses
        val ran = listOf(previews, repeats, longs).count { it > 0 }
        return SelfCheck(
            id, q,
            if (ran == 3) Status.PASS else if (inject) Status.FAIL else Status.NOT_MEASURED,
            "previews=$previews  backspace-repeats=$repeats  long-presses=$longs " +
                "($ran of 3 paths exercised)",
            nc,
        )
    }
}

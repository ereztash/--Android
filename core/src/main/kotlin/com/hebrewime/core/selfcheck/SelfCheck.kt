package com.hebrewime.core.selfcheck

/**
 * The result of one on-device check, in the same shape the Python gates use.
 *
 * ### Why this exists at all
 * `docs/QA_MATRIX.md` carries a table of checks that "require a physical Android device". For
 * most of them that was true only in the weakest sense: they required a device *and a human to
 * look at it*, so they stayed NOT RUN for months because nobody was holding the phone at the
 * moment the question was asked.
 *
 * A large share of that table is not a matter of judgement. Whether the bottom key row clears
 * the gesture inset is a subtraction. Whether the Keystore key is hardware-backed is a field
 * on `KeyInfo`. Whether a label overflows its key is the comparison this module already does
 * for drawing. Those are **measurements**, and the device can take them itself and report a
 * number.
 *
 * So this is the gate harness, ported to the phone. It does not make a device unnecessary --
 * it makes the device's own report the evidence, instead of a person's recollection of it.
 *
 * ### The rule this inherits
 * *A gate that has never failed has not been shown to be a gate.* Every check here must be
 * runnable in [injected] mode, where a defect is planted and the check must come back
 * [Status.FAIL]. A check whose control comes back green is reported [Status.NOT_A_GATE] and is
 * not counted as evidence for anything -- exactly as `scripts/run_gates.py` does.
 */
data class SelfCheck(
    /** Matches the id in `docs/QA_MATRIX.md`, so a report can be pasted against the table. */
    val id: String,
    val question: String,
    val status: Status,
    /** The measurement, in units, e.g. "gesture inset 48px, clearance 12px". */
    val measured: String,
    /** What a green result here does NOT establish. Never empty. */
    val notCovered: String,
) {
    enum class Status {
        /** Measured, and the measurement satisfies the check. */
        PASS,

        /** Measured, and it does not. */
        FAIL,

        /**
         * The measurement could not be taken -- an API returned nothing, the surface has not
         * been laid out yet, the user has not done the thing being counted. **Never PASS.**
         * A check that examined nothing is the failure this project exists to refuse.
         */
        NOT_MEASURED,

        /**
         * The check ran with a defect planted and came back green anyway, so it cannot
         * distinguish a working device from a broken one. Its PASS is worthless.
         */
        NOT_A_GATE,
    }

    val line: String
        get() = "[${status.name.padEnd(12)}] ${id.padEnd(18)} $measured"
}

/**
 * A whole self-check run: the checks, and whether every one of them proved it can fail.
 */
data class SelfCheckReport(
    val checks: List<SelfCheck>,
    /** Free-form device facts that are context, not checks: API level, density, night mode. */
    val context: List<Pair<String, String>>,
) {
    val passed: Int get() = checks.count { it.status == SelfCheck.Status.PASS }
    val failed: Int get() = checks.count { it.status == SelfCheck.Status.FAIL }
    val notMeasured: Int get() = checks.count { it.status == SelfCheck.Status.NOT_MEASURED }
    val notGates: Int get() = checks.count { it.status == SelfCheck.Status.NOT_A_GATE }

    /** True only if every check was measured AND every control went red. */
    val clean: Boolean get() = failed == 0 && notMeasured == 0 && notGates == 0

    /**
     * The report as text the operator can paste back verbatim.
     *
     * Plain text rather than JSON because its destination is a chat message, and because
     * anything a human has to reformat is a thing a human will summarise instead.
     */
    fun render(): String = buildString {
        appendLine("HEBREW IME - DEVICE SELF-CHECK")
        appendLine("=".repeat(58))
        for ((k, v) in context) appendLine("  $k: $v")
        appendLine("-".repeat(58))
        for (c in checks) {
            appendLine(c.line)
            appendLine("      ? ${c.question}")
            appendLine("      x does not cover: ${c.notCovered}")
        }
        appendLine("-".repeat(58))
        appendLine("PASS $passed   FAIL $failed   NOT-MEASURED $notMeasured   NOT-A-GATE $notGates")
        if (!clean) {
            appendLine("NOT CLEAN. A NOT-MEASURED check examined nothing; a NOT-A-GATE check")
            appendLine("passed with a defect planted and proves nothing.")
        }
    }
}

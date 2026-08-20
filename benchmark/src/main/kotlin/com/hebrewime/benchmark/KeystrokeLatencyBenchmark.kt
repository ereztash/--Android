package com.hebrewime.benchmark

import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Measures the IME's own trace sections while UiAutomator types into a host app.
 *
 * ### Why it is built this way
 * An IME runs in its own process. Macrobenchmark's built-in metrics are scoped to
 * `targetPackageName`, so they measure the host app and are structurally blind to the keyboard.
 * `TraceSectionMetric` with `targetPackageOnly = false` is the only mechanism that crosses that
 * boundary, and it is `@ExperimentalMetricApi`.
 *
 * ### The failure mode this design has to avoid
 * If [SUGGEST_SECTION] does not match a section the IME actually emits, `TraceSectionMetric`
 * reports **zero measurements rather than an error**. A latency result of "no data" looks
 * indistinguishable from a passing run in a summary. `TraceSectionNamesTest` in `:core` pins
 * these constants against the ones `CorrectionController` emits so a rename fails the build
 * instead of silently producing an empty measurement.
 *
 * ### Prerequisites, which are on the operator
 * This measures nothing unless the keyboard under test is **enabled and selected as the active
 * IME on the device**. There is no way for a benchmark to do that itself without
 * `WRITE_SECURE_SETTINGS`. See `docs/QA_MATRIX.md`.
 */
@RunWith(AndroidJUnit4::class)
class KeystrokeLatencyBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @OptIn(ExperimentalMetricApi::class)
    @Test
    fun suggestionLatency() = benchmarkRule.measureRepeated(
        packageName = HOST_PACKAGE,
        metrics = listOf(
            // targetPackageOnly = false is the whole point: these sections are emitted by the
            // IME process, not by the host app being driven.
            TraceSectionMetric(
                sectionName = SUGGEST_SECTION,
                mode = TraceSectionMetric.Mode.Average,
                targetPackageOnly = false,
            ),
            TraceSectionMetric(
                sectionName = LOAD_SECTION,
                mode = TraceSectionMetric.Mode.Sum,
                targetPackageOnly = false,
            ),
        ),
        iterations = ITERATIONS,
        startupMode = StartupMode.WARM,
        setupBlock = {
            pressHome()
            startActivityAndWait()
        },
    ) {
        val field = device.wait(Until.findObject(By.res(HOST_PACKAGE, TARGET_FIELD_ID)), TIMEOUT)
            ?: error(
                "host app field '$TARGET_FIELD_ID' not found. Is :hostapp installed?"
            )
        field.click()
        // Give the IME time to show. A fixed wait is crude, but the alternative -- waiting on
        // the IME window -- needs an accessibility connection the benchmark does not have.
        device.waitForIdle(IME_SETTLE_MS)

        // Type a word that is NOT in the lexicon, so the engine actually searches rather than
        // short-circuiting on the "already a word" check. Measuring the short-circuit would
        // report a latency the user never experiences.
        for (character in TYPO.toCharArray()) {
            field.text = field.text.orEmpty() + character
            device.waitForIdle(PER_KEY_SETTLE_MS)
        }
        device.waitForIdle(TIMEOUT)
    }

    private companion object {
        const val HOST_PACKAGE = "com.hebrewime.hostapp"
        const val TARGET_FIELD_ID = "benchmark_target"

        /** Must match `CorrectionController.TRACE_SUGGEST`. Pinned by a test. */
        const val SUGGEST_SECTION = "HebrewIme.suggest"

        /** Must match `CorrectionController.TRACE_LOAD`. Pinned by a test. */
        const val LOAD_SECTION = "HebrewIme.loadLexicon"

        /** A deliberate misspelling, so the correction path is exercised. */
        const val TYPO = "מקלדתת"

        const val ITERATIONS = 10
        const val TIMEOUT = 5_000L
        const val IME_SETTLE_MS = 1_500L
        const val PER_KEY_SETTLE_MS = 200L
    }
}

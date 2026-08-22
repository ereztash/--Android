package com.hebrewime.core.selfcheck

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.pow

/**
 * The arithmetic behind the on-device checks, with no Android types anywhere in it.
 *
 * Same reason `KeyGeometry` is in `:core`: these are subtractions and ratios with easy
 * off-by-ones, and an assertion that runs on a build server is worth more than one that runs
 * only when someone is holding a phone. The `:app` side reads real values off the device and
 * hands them here; everything that can be wrong about the *decision* is wrong in this file,
 * where a JVM test can find it.
 */
object CheckArithmetic {

    // ---------------------------------------------------------------- contrast

    /**
     * The sRGB relative luminance of a packed ARGB colour, per WCAG 2.1.
     *
     * Alpha is ignored: the keyboard draws opaque fills, and a translucent one would need a
     * composite against whatever is behind it, which is not knowable here. If a colour ever
     * ships with alpha < 255 this returns a luminance for the wrong colour, so [contrastRatio]
     * refuses those instead of guessing.
     */
    fun relativeLuminance(argb: Int): Double {
        fun channel(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        val r = channel((argb shr 16) and 0xFF)
        val g = channel((argb shr 8) and 0xFF)
        val b = channel(argb and 0xFF)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /**
     * WCAG contrast ratio between two opaque colours, 1.0 to 21.0.
     *
     * Returns null when either colour is not fully opaque, because the honest answer then is
     * "not measurable from these two values alone" and a number would be a guess.
     */
    fun contrastRatio(foreground: Int, background: Int): Double? {
        if ((foreground ushr 24) != 0xFF || (background ushr 24) != 0xFF) return null
        val a = relativeLuminance(foreground)
        val b = relativeLuminance(background)
        val lighter = maxOf(a, b)
        val darker = minOf(a, b)
        return (lighter + 0.05) / (darker + 0.05)
    }

    /**
     * WCAG 2.1 AA for non-text UI and large text is 3.0:1; normal text is 4.5:1.
     *
     * Key labels are drawn at 42% of a row height -- on a 1080px phone that is about 81px,
     * far above the 18.66px "large text" boundary, so 3.0 is the applicable bar and 4.5 is
     * reported alongside it rather than enforced.
     */
    const val AA_LARGE_TEXT = 3.0
    const val AA_NORMAL_TEXT = 4.5

    // ---------------------------------------------------------------- insets

    /**
     * Pixels between the bottom of the lowest key and the top of the system gesture inset.
     *
     * Negative means the key row is underneath the gesture area, which is the M2-INSETS
     * failure: at targetSdk 36 the window is edge-to-edge and nothing exempts an IME, so a
     * bottom row drawn to the window's edge sits under the navigation gesture strip and its
     * taps go to the system.
     *
     * @param viewHeight the keyboard view's height in pixels
     * @param lowestKeyBottom the bottom edge of the lowest key row, in the same pixels
     * @param bottomInset the system gesture / navigation-bar inset at the bottom
     */
    fun gestureClearance(viewHeight: Int, lowestKeyBottom: Float, bottomInset: Int): Float =
        (viewHeight - bottomInset) - lowestKeyBottom

    // ---------------------------------------------------------------- latency

    /**
     * Percentiles of a latency sample, in the units the samples are in.
     *
     * Nearest-rank on the sorted sample -- no interpolation. With a few hundred keystrokes the
     * interpolated and nearest-rank p95 differ by less than the measurement noise, and
     * nearest-rank has the property that every reported number is a keystroke that actually
     * happened.
     *
     * Returns null for an empty sample. Never zero: a zero p95 would read as "instant" when it
     * means "no data", which is the confusion `GATE-TRACE-1` exists to prevent on the other
     * side of the same measurement.
     */
    fun percentile(samples: LongArray, p: Double): Long? {
        if (samples.isEmpty()) return null
        require(p > 0.0 && p <= 100.0) { "percentile must be in (0, 100]" }
        val sorted = samples.sortedArray()
        val rank = ceil(p / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    /**
     * The number of samples needed before a p95 means anything.
     *
     * With n samples the p95 is the ceil(0.95n)-th value, so below 20 samples it is simply the
     * maximum and reporting it as a percentile overstates what was measured.
     */
    const val MIN_SAMPLES_FOR_P95 = 20

    // ---------------------------------------------------------------- labels

    /**
     * True when a label drawn at [advance] pixels does not fit [available] pixels.
     *
     * The counterpart of `KeyGeometry.fitTextSize`: that decides the size, this decides whether
     * the fix was needed and whether it worked. Both must agree, and
     * `SelfCheckArithmeticTest` asserts they do.
     */
    fun overflows(advance: Float, available: Float): Boolean =
        available > 0f && advance > available + TOLERANCE_PX

    /**
     * Half a pixel. Text advance comes back as a float from a rasteriser; a label that is
     * 0.2px over its key is not a defect anyone can see, and flagging it would make this check
     * fire on rounding.
     */
    const val TOLERANCE_PX = 0.5f

    // ---------------------------------------------------------------- counters

    /**
     * A privacy counter's verdict: [allowed] requests out of [requests] were served.
     *
     * `M4-DEVICE` asks whether the framework really hands over password plaintext and whether
     * the policy catches it. The device cannot answer that by showing the text -- writing it
     * down would be the leak. It can answer with counts: if restricted fields were seen and
     * none was ever served, the policy held for every one of them.
     *
     * Returns null when no restricted field has been seen at all, because "0 leaks out of 0
     * opportunities" is not evidence of anything and must not be rendered as a pass.
     */
    fun restrictedFieldVerdict(restrictedSeen: Int, restrictedServed: Int): Boolean? =
        if (restrictedSeen <= 0) null else restrictedServed == 0

    // ---------------------------------------------------------------- helpers

    /** Formats a ratio for a report: two decimals, no locale surprises. */
    fun ratio(value: Double): String {
        val scaled = (value * 100.0).toLong()
        return "${scaled / 100}.${(abs(scaled) % 100).toString().padStart(2, '0')}"
    }
}

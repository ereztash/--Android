package com.hebrewime.core.text

/**
 * The `android.text.InputType` and `EditorInfo` values this project depends on, redeclared as
 * pure Kotlin constants.
 *
 * WHY REDECLARE THEM: `:core` is a plain JVM module with no Android dependency, which is what
 * makes the sensitive-field logic unit-testable in CI without a device. The cost of that is
 * that these values are copied rather than referenced, and a copy can drift from the platform.
 *
 * HOW THE COPY IS KEPT HONEST: every value below was read out of
 * `platforms/android-36/android.jar` with `javap -constants` on 2026-08-20 and recorded in
 * `docs/VERIFICATION.md`. [AndroidInputTypesTest] re-derives the composite field types from
 * these primitives and asserts they equal the literals the build spec requires, so a typo in
 * any single constant fails the build rather than silently widening what counts as a
 * non-sensitive field.
 *
 * There is deliberately no `isPassword()` helper in the Android framework; the mask logic is
 * written by hand in [RestrictedInputTypes] (M4).
 */
object AndroidInputTypes {

    // --- masks -------------------------------------------------------------------------
    /** `InputType.TYPE_MASK_CLASS` = 15 (0x0000000f). */
    const val TYPE_MASK_CLASS: Int = 0x0000000f

    /** `InputType.TYPE_MASK_VARIATION` = 4080 (0x00000ff0). */
    const val TYPE_MASK_VARIATION: Int = 0x00000ff0

    // --- classes -----------------------------------------------------------------------
    const val TYPE_CLASS_TEXT: Int = 0x00000001
    const val TYPE_CLASS_NUMBER: Int = 0x00000002
    const val TYPE_CLASS_PHONE: Int = 0x00000003
    const val TYPE_CLASS_DATETIME: Int = 0x00000004

    // --- text variations ---------------------------------------------------------------
    const val TYPE_TEXT_VARIATION_URI: Int = 0x00000010
    const val TYPE_TEXT_VARIATION_EMAIL_ADDRESS: Int = 0x00000020
    const val TYPE_TEXT_VARIATION_PASSWORD: Int = 0x00000080
    const val TYPE_TEXT_VARIATION_VISIBLE_PASSWORD: Int = 0x00000090
    const val TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS: Int = 0x000000d0
    const val TYPE_TEXT_VARIATION_WEB_PASSWORD: Int = 0x000000e0

    // --- number variations -------------------------------------------------------------
    const val TYPE_NUMBER_VARIATION_PASSWORD: Int = 0x00000010

    // --- flags -------------------------------------------------------------------------
    const val TYPE_TEXT_FLAG_NO_SUGGESTIONS: Int = 0x00080000

    // --- EditorInfo.imeOptions ---------------------------------------------------------
    /** `EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING` = 16777216 (0x01000000). */
    const val IME_FLAG_NO_PERSONALIZED_LEARNING: Int = 0x01000000
}

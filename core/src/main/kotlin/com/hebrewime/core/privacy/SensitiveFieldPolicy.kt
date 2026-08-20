package com.hebrewime.core.privacy

import com.hebrewime.core.text.AndroidInputTypes as T

/** What the keyboard is allowed to do in this field. */
enum class InputMode {
    /** Suggestions, correction, learning and context are all permitted. */
    NORMAL,

    /**
     * Nothing is read, retained, suggested, corrected, learned or persisted. The keyboard
     * types characters and does nothing else.
     */
    RESTRICTED,
}

/** Why a field was restricted. Recorded so the decision is explainable, never a bare boolean. */
enum class RestrictionReason {
    PASSWORD,
    VISIBLE_PASSWORD,
    WEB_PASSWORD,
    NUMERIC_PASSWORD,
    EMAIL,
    WEB_EMAIL,
    URI,
    PHONE,
    NUMBER,
    NO_SUGGESTIONS_FLAG,
    NO_PERSONALIZED_LEARNING_FLAG,
    OTP_HEURISTIC,

    /** An input-type class or variation this build does not recognise. Fails closed. */
    UNRECOGNISED_INPUT_TYPE,
}

/**
 * Why a field may be suggested into but must not be *learned* from.
 *
 * This distinction is an addition beyond the build spec's restricted set, and it is here
 * because "restricted" and "safe to memorise" are not the same question. A person's name or a
 * postal address is ordinary text that deserves normal suggestions — turning them off would be
 * a visible regression for no privacy gain, since the keyboard is not reading anything it was
 * not shown. But writing them into a persistent personal dictionary is different: it turns a
 * transient field into a stored record of who the user knows and where they live.
 *
 * So these fields suggest normally and never learn.
 */
enum class NoLearnReason {
    PERSON_NAME,
    POSTAL_ADDRESS,
}

/** Everything the policy is allowed to look at. Deliberately small. */
data class FieldDescriptor(
    val inputType: Int,
    val imeOptions: Int = 0,
    val hintText: CharSequence? = null,
    val fieldName: CharSequence? = null,
    val packageName: String? = null,
    val maxLength: Int? = null,
)

/**
 * The outcome of starting an input session.
 *
 * [initialTextBeforeCursor] is `null` whenever [mode] is [InputMode.RESTRICTED] — and, more
 * importantly, the text was never *fetched* in that case. See [SensitiveFieldPolicy.beginSession].
 */
data class SessionStart(
    val mode: InputMode,
    val reasons: Set<RestrictionReason>,
    val noLearnReasons: Set<NoLearnReason>,
    val initialTextBeforeCursor: CharSequence?,
    val cursorPosition: Int,
) {
    val isRestricted: Boolean get() = mode == InputMode.RESTRICTED
    val mayReadContext: Boolean get() = !isRestricted
    val maySuggest: Boolean get() = !isRestricted
    val mayAutocorrect: Boolean get() = !isRestricted
    val mayUseComposingText: Boolean get() = !isRestricted

    /** Stricter than [maySuggest]: see [NoLearnReason]. */
    val mayLearn: Boolean get() = !isRestricted && noLearnReasons.isEmpty()

    val mayPersist: Boolean get() = mayLearn
}

/**
 * Decides whether a field is sensitive, and gates the initial text on that decision.
 *
 * ### The problem this exists to solve
 * `TextView.onCreateInputConnection()` calls `outAttrs.setInitialSurroundingText(mText)`
 * **unconditionally**, with no `inputType` check — password fields included. An IME is handed
 * up to 2048 characters of password plaintext without asking, and cannot refuse delivery.
 *
 * There is no `isPassword()` helper in the public Android API. The mask logic below is written
 * by hand because the platform does not provide it.
 *
 * ### Why [beginSession] takes a provider rather than a string
 * "Read the text, then throw it away if the field is sensitive" is not good enough: by then
 * the plaintext has been pulled into this process, copied into a `CharSequence`, and left for
 * the GC to deal with whenever it feels like it. Any crash, heap dump or stray log in between
 * exposes it.
 *
 * Passing a lazy provider makes the discard **structural**. When the field is restricted the
 * provider is simply never invoked, so the text is never pulled across at all. The unit tests
 * assert exactly that — that the provider was *not called* — because asserting only that the
 * returned value is null would still pass if the text had been read and leaked first.
 */
object SensitiveFieldPolicy {

    /**
     * Classify a field.
     *
     * Fails closed: an input-type class this build does not recognise is Restricted, not
     * Normal. A future Android release adding a "payment card" class should be treated as
     * sensitive by default, not opened up by silence.
     */
    fun classify(field: FieldDescriptor): Set<RestrictionReason> {
        val reasons = linkedSetOf<RestrictionReason>()

        val klass = field.inputType and T.TYPE_MASK_CLASS
        val variation = field.inputType and T.TYPE_MASK_VARIATION

        when (klass) {
            T.TYPE_CLASS_TEXT -> when (variation) {
                T.TYPE_TEXT_VARIATION_PASSWORD -> reasons += RestrictionReason.PASSWORD
                T.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ->
                    reasons += RestrictionReason.VISIBLE_PASSWORD
                T.TYPE_TEXT_VARIATION_WEB_PASSWORD -> reasons += RestrictionReason.WEB_PASSWORD
                T.TYPE_TEXT_VARIATION_EMAIL_ADDRESS -> reasons += RestrictionReason.EMAIL
                T.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> reasons += RestrictionReason.WEB_EMAIL
                T.TYPE_TEXT_VARIATION_URI -> reasons += RestrictionReason.URI
                // Anything not on the known-safe list fails CLOSED. There are 256 possible
                // variation values and Android currently defines 15; if a future release adds
                // a sensitive one, silence must not be what opens it up.
                in KNOWN_SAFE_TEXT_VARIATIONS -> Unit
                else -> reasons += RestrictionReason.UNRECOGNISED_INPUT_TYPE
            }

            T.TYPE_CLASS_NUMBER -> {
                reasons += RestrictionReason.NUMBER
                if (variation == T.TYPE_NUMBER_VARIATION_PASSWORD) {
                    reasons += RestrictionReason.NUMERIC_PASSWORD
                }
            }

            T.TYPE_CLASS_PHONE -> reasons += RestrictionReason.PHONE

            T.TYPE_CLASS_DATETIME -> Unit // a date is not free text; nothing to protect or learn

            0 -> Unit // TYPE_NULL: no soft keyboard input expected

            else -> reasons += RestrictionReason.UNRECOGNISED_INPUT_TYPE
        }

        if (field.inputType and T.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0) {
            reasons += RestrictionReason.NO_SUGGESTIONS_FLAG
        }
        if (field.imeOptions and T.IME_FLAG_NO_PERSONALIZED_LEARNING != 0) {
            reasons += RestrictionReason.NO_PERSONALIZED_LEARNING_FLAG
        }
        if (looksLikeOneTimeCode(field)) {
            reasons += RestrictionReason.OTP_HEURISTIC
        }
        return reasons
    }

    /** Fields that may be suggested into but never learned from. See [NoLearnReason]. */
    fun classifyLearning(field: FieldDescriptor): Set<NoLearnReason> {
        if (field.inputType and T.TYPE_MASK_CLASS != T.TYPE_CLASS_TEXT) return emptySet()
        return when (field.inputType and T.TYPE_MASK_VARIATION) {
            TYPE_TEXT_VARIATION_PERSON_NAME -> setOf(NoLearnReason.PERSON_NAME)
            TYPE_TEXT_VARIATION_POSTAL_ADDRESS -> setOf(NoLearnReason.POSTAL_ADDRESS)
            else -> emptySet()
        }
    }

    /**
     * Begin a session, reading the initial text **only** if the field is not restricted.
     *
     * @param initialTextProvider invoked at most once, and never at all when the field is
     *   restricted. It must be the only route by which `EditorInfo`'s initial text enters this
     *   process.
     */
    fun beginSession(
        field: FieldDescriptor,
        cursorPosition: Int,
        initialTextProvider: () -> CharSequence?,
    ): SessionStart {
        val reasons = classify(field)
        val restricted = reasons.isNotEmpty()
        return SessionStart(
            mode = if (restricted) InputMode.RESTRICTED else InputMode.NORMAL,
            reasons = reasons,
            noLearnReasons = if (restricted) emptySet() else classifyLearning(field),
            // The provider is not called on the restricted branch. This is the whole point.
            initialTextBeforeCursor = if (restricted) null else initialTextProvider(),
            cursorPosition = cursorPosition.coerceAtLeast(0),
        )
    }

    /**
     * Best-effort one-time-code detection.
     *
     * An IME **cannot** see autofill hints: `EditorInfo` has no autofill-hints field and
     * `TextView.onCreateInputConnection` never writes them into `outAttrs` or its extras.
     * `AUTOFILL_HINT_SMS_OTP` exists but is for autofill services only. So this is heuristic,
     * it **fails closed**, and its accuracy is deliberately not claimed anywhere: there is no
     * labelled corpus of OTP fields to measure it against.
     *
     * A false positive costs a suggestion. A false negative puts a passcode in a database.
     */
    fun looksLikeOneTimeCode(field: FieldDescriptor): Boolean {
        val haystack = buildString {
            field.hintText?.let { append(it).append(' ') }
            field.fieldName?.let { append(it) }
        }.lowercase()

        if (haystack.isNotEmpty() && OTP_KEYWORDS.any { it in haystack }) return true

        // A very short numeric field with suggestions disabled is almost always a code.
        val klass = field.inputType and T.TYPE_MASK_CLASS
        val numeric = klass == T.TYPE_CLASS_NUMBER
        val short = field.maxLength != null && field.maxLength in 4..8
        if (numeric && short) return true

        return false
    }

    // Android's defined TYPE_CLASS_TEXT variations that carry no special sensitivity.
    private const val TYPE_TEXT_VARIATION_NORMAL = 0x00000000
    private const val TYPE_TEXT_VARIATION_EMAIL_SUBJECT = 0x00000030
    private const val TYPE_TEXT_VARIATION_SHORT_MESSAGE = 0x00000040
    private const val TYPE_TEXT_VARIATION_LONG_MESSAGE = 0x00000050
    private const val TYPE_TEXT_VARIATION_PERSON_NAME = 0x00000060
    private const val TYPE_TEXT_VARIATION_POSTAL_ADDRESS = 0x00000070
    private const val TYPE_TEXT_VARIATION_WEB_EDIT_TEXT = 0x000000a0
    private const val TYPE_TEXT_VARIATION_FILTER = 0x000000b0
    private const val TYPE_TEXT_VARIATION_PHONETIC = 0x000000c0

    private val KNOWN_SAFE_TEXT_VARIATIONS = setOf(
        TYPE_TEXT_VARIATION_NORMAL,
        TYPE_TEXT_VARIATION_EMAIL_SUBJECT,
        TYPE_TEXT_VARIATION_SHORT_MESSAGE,
        TYPE_TEXT_VARIATION_LONG_MESSAGE,
        TYPE_TEXT_VARIATION_PERSON_NAME,
        TYPE_TEXT_VARIATION_POSTAL_ADDRESS,
        TYPE_TEXT_VARIATION_WEB_EDIT_TEXT,
        TYPE_TEXT_VARIATION_FILTER,
        TYPE_TEXT_VARIATION_PHONETIC,
    )

    private val OTP_KEYWORDS = listOf(
        "otp", "one-time", "one time", "onetime", "passcode", "pin",
        "verification code", "verify code", "security code", "auth code",
        "2fa", "mfa", "sms code", "confirmation code",
        // Hebrew
        "קוד אימות", "קוד חד", "סיסמה חד", "קוד אבטחה", "קוד זיהוי",
    )
}

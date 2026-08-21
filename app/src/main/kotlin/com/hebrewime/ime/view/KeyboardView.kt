package com.hebrewime.ime.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.SoundEffectConstants
import android.view.View
import android.view.accessibility.AccessibilityEvent
import androidx.core.view.ViewCompat
import com.hebrewime.core.keyboard.Key
import com.hebrewime.core.keyboard.KeyAction
import com.hebrewime.core.keyboard.KeyGeometry
import com.hebrewime.core.keyboard.KeyRect
import com.hebrewime.core.keyboard.KeyDescriptions
import com.hebrewime.core.keyboard.KeyboardLayout
import com.hebrewime.R

/**
 * Draws a [KeyboardLayout] and turns touches into key presses.
 *
 * Everything decidable -- where each key sits, which key a touch resolves to, what a press
 * means -- lives in `:core` ([KeyGeometry], `KeyPressPlanner`) and is unit-tested there. This
 * class is deliberately thin: it owns pixels and events, and nothing else.
 *
 * It holds **no input state**. The current layout and the shift flag live on the service,
 * because this view is destroyed and recreated on any configuration change outside the
 * `configChanges` list. See [KeyboardHostView].
 */
class KeyboardView(context: Context) : View(context) {

    /** Called with the pressed key. The service decides what it means. */
    var onKeyPressed: ((Key) -> Unit)? = null

    /**
     * Called when a long press resolves to an alternate character, with the key that was already
     * committed on DOWN and the text that should replace it.
     *
     * Two arguments rather than one because the replacement is a two-step edit — remove what the
     * press already committed, then commit the alternate — and the view has no `InputConnection`
     * to do either.
     */
    var onLongPressAlternate: ((Key, String) -> Unit)? = null

    /**
     * Exposes each canvas-drawn key as a virtual view node. Without it the whole keyboard is a
     * single blank rectangle to TalkBack -- see [KeyboardAccessibilityHelper].
     */
    private val accessibilityHelper = KeyboardAccessibilityHelper(this).also {
        ViewCompat.setAccessibilityDelegate(this, it)
    }

    private var layoutModel: KeyboardLayout? = null
    private var rects: List<KeyRect> = emptyList()
    private var pressedKey: Key? = null
    private var pressedRect: KeyRect? = null

    /** Set while a long press is holding an alternate character, so the preview shows it. */
    private var longPressAlternate: String? = null

    private val repeatHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var repeatRunnable: Runnable? = null
    private var repeatDelay = 0L

    private val previewFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.key_background_function)
    }
    /**
     * The typeface for every glyph this keyboard draws.
     *
     * Nothing here set one before: key labels, the preview bubble and the candidate strip all
     * rendered in whatever the platform resolved for Hebrew. That is the smallest and most
     * glanced text in the product, and it was the one visual decision nobody had made.
     *
     * Chosen by measurement — `scripts/build_keyboard_font.py` renders all 351 pairs of the 27
     * Hebrew letters at the label's real pixel size and ranks candidates by how many pairs
     * overlap enough to be confusable. `lexicon/FONT_MANIFEST.json` carries the result.
     *
     * Null if the resource cannot be loaded, which leaves the platform default: a keyboard that
     * will not draw is worse than one drawn in the wrong face.
     */
    private val labelTypeface: android.graphics.Typeface? =
        try {
            androidx.core.content.res.ResourcesCompat.getFont(context, R.font.keyboard_label)
        } catch (missing: Throwable) {
            null
        }

    private val previewLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.key_label)
        textAlign = Paint.Align.CENTER
        typeface = labelTypeface ?: typeface
    }

    private val keyFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.key_background)
    }
    private val keyFillPressed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.key_background_pressed)
    }
    private val functionFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.key_background_function)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.key_label)
        textAlign = Paint.Align.CENTER
        typeface = labelTypeface ?: typeface
    }
    private val labelPaintPressed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.key_label_pressed)
        textAlign = Paint.Align.CENTER
        typeface = labelTypeface ?: typeface
    }

    private val scratch = RectF()

    /**
     * The label size every key shares, and the per-key size after fitting.
     *
     * Two arrays rather than a lookup, because this is read once per key per frame on the
     * draw path. `labelSizes[i]` belongs to `rects[i]` and is rebuilt with it.
     */
    private var fullLabelSize = 0f
    private var labelSizes = FloatArray(0)

    fun setLayout(model: KeyboardLayout) {
        val changed = layoutModel?.id != model.id
        layoutModel = model
        rects = emptyList()
        accessibilityHelper.setKeys(emptyList())
        requestLayout()
        invalidate()
        if (changed) {
            // A layout switch is otherwise completely silent, which leaves a screen-reader user
            // on a keyboard whose keys have all changed with no indication that anything
            // happened.
            accessibilityHelper.announceLayoutChange(
                KeyDescriptions.describe(
                    Key(model.id, model.id, KeyAction.SWITCH_LAYOUT),
                )
            )
        }
    }

    /** Required so ExploreByTouchHelper can receive hover events. */
    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        accessibilityHelper.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        // A fixed fraction of screen height rather than a fixed dp value: on a tall phone a
        // dp-sized keyboard looks stranded, and on a short one it swallows the app.
        val height = (resources.displayMetrics.heightPixels * KEYBOARD_HEIGHT_FRACTION).toInt()
            .coerceAtLeast(MIN_HEIGHT_PX)
        setMeasuredDimension(width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recomputeGeometry(w, h)
    }

    private fun recomputeGeometry(w: Int, h: Int) {
        val model = layoutModel ?: return
        if (w <= 0 || h <= 0) return
        rects = KeyGeometry.layout(model, w.toFloat(), h.toFloat())
        // Rebuilt, never cached across layouts: stale nodes would tell a screen reader a key
        // is somewhere it has moved away from.
        accessibilityHelper.setKeys(rects)
        val rowHeight = h.toFloat() / model.rows.size
        fullLabelSize = rowHeight * LABEL_HEIGHT_FRACTION
        labelPaint.textSize = fullLabelSize
        labelPaintPressed.textSize = fullLabelSize
        // Multi-character labels -- `123`, `en`, `he` -- do not fit a key sized for one Hebrew
        // letter. Measured once here, not per frame; text advance is linear in size, so one
        // measurement at the shared size gives the exact ratio for every key.
        labelSizes = FloatArray(rects.size) { i ->
            val r = rects[i]
            KeyGeometry.fitTextSize(
                fullSize = fullLabelSize,
                measuredAtFull = labelPaint.measureText(r.key.label),
                available = r.width - 2f * GAP - LABEL_BREATHING_ROOM,
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (rects.isEmpty()) recomputeGeometry(width, height)
        for (i in rects.indices) {
            val r = rects[i]
            scratch.set(r.left + GAP, r.top + GAP, r.right - GAP, r.bottom - GAP)
            val paint = when {
                r.key == pressedKey -> keyFillPressed
                r.key.action != KeyAction.CHARACTER -> functionFill
                else -> keyFill
            }
            canvas.drawRoundRect(scratch, CORNER, CORNER, paint)
            val text = if (r.key == pressedKey) labelPaintPressed else labelPaint
            text.textSize = labelSizes.getOrElse(i) { fullLabelSize }
            // Re-read per key: the baseline follows the size, and a label that was shrunk
            // would otherwise sit off-centre by the difference.
            val metrics = text.fontMetrics
            canvas.drawText(r.key.label, r.centerX,
                            r.centerY - (metrics.descent + metrics.ascent) / 2f, text)
        }
        // The preview bubble only ever shows a character key, which is one grapheme and always
        // fits, so it keeps the shared size.
        labelPaint.textSize = fullLabelSize
        labelPaintPressed.textSize = fullLabelSize
        val previewMetrics = labelPaint.fontMetrics
        drawPreview(canvas, (previewMetrics.descent + previewMetrics.ascent) / 2f)
    }

    /**
     * The bubble showing what is under the finger.
     *
     * ### Why a keyboard needs this at all
     * The finger covers the key it is pressing. Without a preview the only confirmation that the
     * right key was hit is the character appearing in a text field somewhere else on screen,
     * which is a slower loop than typing. Every commercial keyboard draws one.
     *
     * ### Drawn inside the view, never in a window
     * A `PopupWindow` would let the bubble float above the keyboard's top edge, and would also
     * bring window lifecycle, z-order and dismissal bugs into a view that is recreated on every
     * configuration change. Instead the bubble is drawn in this canvas and **flips below the key
     * when it would leave the top edge**, so the top row gets a preview too rather than a
     * clipped one. Slightly unusual, always visible, no window to leak.
     *
     * Function keys get no preview: they carry an icon that is already legible with a finger on
     * it, and a bubble showing ⌫ tells nobody anything.
     */
    private fun drawPreview(canvas: Canvas, baselineOffset: Float) {
        val rect = pressedRect ?: return
        val key = pressedKey ?: return
        if (key.action != KeyAction.CHARACTER && key.action != KeyAction.SPACE) return
        val shown = longPressAlternate ?: key.label
        if (shown.isBlank()) return

        val w = rect.width * PREVIEW_SCALE
        val h = rect.height * PREVIEW_SCALE
        val cx = rect.centerX.coerceIn(w / 2f, width - w / 2f)
        var top = rect.top - h - PREVIEW_GAP
        if (top < 0f) top = rect.bottom + PREVIEW_GAP
        val bottom = top + h
        if (bottom > height) return

        scratch.set(cx - w / 2f, top, cx + w / 2f, bottom)
        canvas.drawRoundRect(scratch, CORNER, CORNER, previewFill)
        previewLabel.textSize = labelPaint.textSize * PREVIEW_SCALE
        val metrics = previewLabel.fontMetrics
        canvas.drawText(
            shown, cx, (top + bottom) / 2f - (metrics.descent + metrics.ascent) / 2f,
            previewLabel,
        )
    }

    /**
     * Hold backspace and it accelerates.
     *
     * Deleting a sentence one tap at a time is the kind of thing people notice every single
     * time. The repeat starts after [REPEAT_START_MS] so an ordinary tap never triggers it, and
     * the interval shortens toward [REPEAT_MIN_MS] so a long hold clears text at a usable rate
     * without the first few deletions being uncontrollable.
     *
     * It calls the same [onKeyPressed] path as a tap, so the grapheme-correct deletion from M3
     * applies to every repeat — a repeat that deleted UTF-16 units would tear apart exactly the
     * sequences that deletion was written to protect.
     */
    private fun startRepeating(key: Key) {
        stopRepeating()
        repeatDelay = REPEAT_START_MS
        val runnable = object : Runnable {
            override fun run() {
                onKeyPressed?.invoke(key)
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                repeatDelay = (repeatDelay * REPEAT_DECAY).toLong().coerceAtLeast(REPEAT_MIN_MS)
                repeatHandler.postDelayed(this, repeatDelay)
            }
        }
        repeatRunnable = runnable
        repeatHandler.postDelayed(runnable, REPEAT_START_MS)
    }

    private fun stopRepeating() {
        repeatRunnable?.let { repeatHandler.removeCallbacks(it) }
        repeatRunnable = null
    }

    /** Arm the long-press alternate, if this key has one. */
    private fun armLongPress(key: Key) {
        val alternate = key.longPressOutput ?: return
        val runnable = Runnable {
            longPressAlternate = alternate
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            invalidate()
        }
        repeatRunnable = runnable
        repeatHandler.postDelayed(runnable, LONG_PRESS_MS)
    }

    /**
     * ### Keys fire on DOWN, and this was changed after a user said it felt slow
     *
     * The original comment here read: *"Fire on UP, not DOWN: a touch that slid off the intended
     * key should follow the finger, and firing on DOWN would commit the wrong character first."*
     * That reasoning is coherent and it is **wrong for a keyboard**.
     *
     * Firing on UP delays every single character by the whole duration of the press — typically
     * 50–120 ms of finger-down time, on every keystroke, forever. The slide-off it protects
     * against is rare, and users do not slide off to *cancel*; they slide to reach a popup, which
     * this keyboard does not have. Every production IME commits on DOWN, and the operator's
     * report — "it feels a bit slower than normal typing" — is exactly what the difference feels
     * like.
     *
     * The cost is real and accepted: a genuine mis-tap now commits, and is fixed with backspace
     * rather than by lifting somewhere else.
     *
     * ### Feedback happens before anything else in the handler
     * Haptic and sound come first, ahead of the edit itself, because the perception of speed is
     * set by the first confirmation the finger gets and not by when the glyph appears. Both go
     * through the platform helpers, which honour the user's system-wide haptic and touch-sound
     * settings — a keyboard that buzzes when the phone is set to silent is a keyboard people
     * uninstall.
     */
    /**
     * The repeat and long-press timers post to a Handler that outlives this view.
     *
     * `onCreateInputView` is called again on every configuration change not covered by the
     * IME's `configChanges` declaration, so a keyboard view is discarded and rebuilt routinely.
     * A pending Runnable holding a reference to a discarded view is a leak that also fires
     * `onKeyPressed` on a keyboard nobody is looking at.
     */
    override fun onDetachedFromWindow() {
        stopRepeating()
        longPressAlternate = null
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (rects.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val key = KeyGeometry.hitTest(rects, event.x, event.y)
                pressedKey = key
                pressedRect = rects.firstOrNull { it.key == key }
                longPressAlternate = null
                invalidate()
                if (key != null) {
                    // Backspace repeats; every other key may carry a long-press alternate.
                    // Never both, so one timer serves both without them racing.
                    if (key.action == KeyAction.BACKSPACE) startRepeating(key)
                    else armLongPress(key)
                    // Confirmation first. See the class docs above.
                    // Single-argument form ON PURPOSE. The two-argument overload takes FLAGS,
                    // and the only flags available are ones that IGNORE the user's settings.
                    // This call respects them, which is the whole point.
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    playSoundEffect(SoundEffectConstants.CLICK)
                    performClick()
                    sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED)
                    onKeyPressed?.invoke(key)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                // Track the highlight so the key under the finger looks live, but do NOT fire
                // again: the character was committed on DOWN and a slide is not a second press.
                val key = KeyGeometry.hitTest(rects, event.x, event.y)
                if (key != pressedKey) {
                    // Sliding off cancels a pending repeat or long press. Holding backspace and
                    // drifting a few pixels should not keep deleting a field the finger has
                    // left.
                    stopRepeating()
                    longPressAlternate = null
                    pressedKey = key
                    pressedRect = rects.firstOrNull { it.key == key }
                    invalidate()
                }
            }

            MotionEvent.ACTION_UP -> {
                // A long press REPLACES the character committed on DOWN: one backspace, then
                // the alternate. Committing the alternate without removing the original would
                // leave both, and the alternate is chosen precisely because it is not the one
                // already there.
                val alternate = longPressAlternate
                val key = pressedKey
                stopRepeating()
                longPressAlternate = null
                pressedKey = null
                pressedRect = null
                invalidate()
                if (alternate != null && key != null) {
                    onLongPressAlternate?.invoke(key, alternate)
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                stopRepeating()
                longPressAlternate = null
                pressedKey = null
                pressedRect = null
                invalidate()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private companion object {
        /** How much larger the preview bubble is than the key it describes. */
        const val PREVIEW_SCALE = 1.35f
        const val PREVIEW_GAP = 6f

        /** A tap must never trigger a repeat, so this is comfortably longer than one. */
        const val REPEAT_START_MS = 400L
        const val REPEAT_MIN_MS = 45L
        const val REPEAT_DECAY = 0.82

        /** Long enough not to fire on a deliberate tap, short enough not to feel stuck. */
        const val LONG_PRESS_MS = 350L

        const val KEYBOARD_HEIGHT_FRACTION = 0.32f
        const val MIN_HEIGHT_PX = 480
        const val LABEL_HEIGHT_FRACTION = 0.42f
        const val GAP = 4f

        /**
         * Space left inside a key beyond the drawn rounded rect, so a shrunk label does not
         * touch its own border. Total, not per side.
         */
        const val LABEL_BREATHING_ROOM = 8f
        const val CORNER = 12f
    }
}

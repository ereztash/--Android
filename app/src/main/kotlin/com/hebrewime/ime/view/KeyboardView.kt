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
     * Exposes each canvas-drawn key as a virtual view node. Without it the whole keyboard is a
     * single blank rectangle to TalkBack -- see [KeyboardAccessibilityHelper].
     */
    private val accessibilityHelper = KeyboardAccessibilityHelper(this).also {
        ViewCompat.setAccessibilityDelegate(this, it)
    }

    private var layoutModel: KeyboardLayout? = null
    private var rects: List<KeyRect> = emptyList()
    private var pressedKey: Key? = null

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
    }
    private val labelPaintPressed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.key_label_pressed)
        textAlign = Paint.Align.CENTER
    }

    private val scratch = RectF()

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
        labelPaint.textSize = rowHeight * LABEL_HEIGHT_FRACTION
        labelPaintPressed.textSize = labelPaint.textSize
    }

    override fun onDraw(canvas: Canvas) {
        if (rects.isEmpty()) recomputeGeometry(width, height)
        val metrics = labelPaint.fontMetrics
        val baselineOffset = (metrics.descent + metrics.ascent) / 2f
        for (r in rects) {
            scratch.set(r.left + GAP, r.top + GAP, r.right - GAP, r.bottom - GAP)
            val paint = when {
                r.key == pressedKey -> keyFillPressed
                r.key.action != KeyAction.CHARACTER -> functionFill
                else -> keyFill
            }
            canvas.drawRoundRect(scratch, CORNER, CORNER, paint)
            val text = if (r.key == pressedKey) labelPaintPressed else labelPaint
            canvas.drawText(r.key.label, r.centerX, r.centerY - baselineOffset, text)
        }
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
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (rects.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val key = KeyGeometry.hitTest(rects, event.x, event.y)
                pressedKey = key
                invalidate()
                if (key != null) {
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
                    pressedKey = key
                    invalidate()
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pressedKey = null
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
        const val KEYBOARD_HEIGHT_FRACTION = 0.32f
        const val MIN_HEIGHT_PX = 480
        const val LABEL_HEIGHT_FRACTION = 0.42f
        const val GAP = 4f
        const val CORNER = 12f
    }
}

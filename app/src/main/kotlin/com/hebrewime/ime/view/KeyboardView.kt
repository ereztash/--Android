package com.hebrewime.ime.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.hebrewime.core.keyboard.Key
import com.hebrewime.core.keyboard.KeyAction
import com.hebrewime.core.keyboard.KeyGeometry
import com.hebrewime.core.keyboard.KeyRect
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
 * because this view is destroyed and recreated on every configuration change (and on API 30
 * the `configChanges` declaration is not even read). See [KeyboardHostView].
 */
class KeyboardView(context: Context) : View(context) {

    /** Called with the pressed key. The service decides what it means. */
    var onKeyPressed: ((Key) -> Unit)? = null

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
        layoutModel = model
        rects = emptyList()
        requestLayout()
        invalidate()
    }

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

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (rects.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val key = KeyGeometry.hitTest(rects, event.x, event.y)
                if (key != pressedKey) {
                    pressedKey = key
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                // Fire on UP, not DOWN: a touch that slid off the intended key should follow
                // the finger, and firing on DOWN would commit the wrong character first.
                val key = KeyGeometry.hitTest(rects, event.x, event.y)
                pressedKey = null
                invalidate()
                if (key != null) {
                    performClick()
                    onKeyPressed?.invoke(key)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
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

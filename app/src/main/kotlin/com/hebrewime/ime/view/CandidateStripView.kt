package com.hebrewime.ime.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import com.hebrewime.R
import com.hebrewime.core.correction.Suggestion

/**
 * The strip of suggestions above the keyboard.
 *
 * Laid out right-to-left, because the candidates are Hebrew words and the best one belongs
 * where the eye starts. This follows the *script*, not the device locale — a Hebrew suggestion
 * strip reads right-to-left on an English phone too.
 *
 * Holds no state that matters: the suggestions are pushed in by the service and the view is
 * destroyed and recreated on every configuration change.
 */
class CandidateStripView(context: Context) : View(context) {

    var onCandidateChosen: ((Suggestion) -> Unit)? = null

    private var candidates: List<Suggestion> = emptyList()
    private var pressedIndex = -1
    private var bounds: List<ClosedFloatingPointRange<Float>> = emptyList()

    private val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.key_background_function)
    }
    private val pressed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.key_background_pressed)
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.key_label)
        textAlign = Paint.Align.CENTER
    }
    private val divider = Paint().apply {
        color = context.getColor(R.color.keyboard_background)
        strokeWidth = 2f
    }

    fun setCandidates(list: List<Suggestion>) {
        candidates = list
        pressedIndex = -1
        recomputeBounds()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = (resources.displayMetrics.density * HEIGHT_DP).toInt()
        setMeasuredDimension(width, height)
        label.textSize = height * LABEL_FRACTION
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        label.textSize = h * LABEL_FRACTION
        recomputeBounds()
    }

    private fun recomputeBounds() {
        if (candidates.isEmpty() || width == 0) {
            bounds = emptyList()
            return
        }
        val slot = width.toFloat() / candidates.size
        // Right-to-left: candidate 0, the best one, occupies the rightmost slot.
        bounds = candidates.indices.map { i ->
            val right = width - i * slot
            (right - slot)..right
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), background)
        if (candidates.isEmpty()) return
        val metrics = label.fontMetrics
        val baseline = height / 2f - (metrics.descent + metrics.ascent) / 2f
        candidates.forEachIndexed { i, candidate ->
            val range = bounds[i]
            if (i == pressedIndex) {
                canvas.drawRect(range.start, 0f, range.endInclusive, height.toFloat(), pressed)
            }
            canvas.drawText(
                candidate.word,
                (range.start + range.endInclusive) / 2f,
                baseline,
                label,
            )
            if (i < candidates.size - 1) {
                canvas.drawLine(range.start, height * 0.2f, range.start, height * 0.8f, divider)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (candidates.isEmpty()) return false
        val index = bounds.indexOfFirst { event.x in it }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                if (index != pressedIndex) {
                    pressedIndex = index
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                pressedIndex = -1
                invalidate()
                if (index >= 0) {
                    performClick()
                    onCandidateChosen?.invoke(candidates[index])
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedIndex = -1
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
        const val HEIGHT_DP = 44f
        const val LABEL_FRACTION = 0.42f
    }
}

package com.hebrewime.ime.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import androidx.core.view.ViewCompat
import com.hebrewime.R
import com.hebrewime.core.prediction.Prediction
import com.hebrewime.core.prediction.SuggestionKind

/**
 * The strip of suggestions above the keyboard.
 *
 * Laid out right-to-left, because the candidates are Hebrew words and the best one belongs
 * where the eye starts. This follows the *script*, not the device locale — a Hebrew suggestion
 * strip reads right-to-left on an English phone too.
 *
 * ### Corrections are marked, completions are not
 * A [SuggestionKind.CORRECTION] is an assertion that the word as typed is wrong. A
 * [SuggestionKind.COMPLETION] asserts only that it is unfinished, and a
 * [SuggestionKind.NEXT_WORD] asserts nothing about what was typed at all. Presenting all three
 * identically would mean the keyboard can tell a user their spelling is wrong and have no way
 * to say so. Corrections are therefore drawn in a distinct colour **and** described as
 * corrections to accessibility services — colour alone would carry the distinction only to a
 * sighted user with normal colour vision.
 *
 * Holds no state that matters: the suggestions are pushed in by the service and the view is
 * destroyed and recreated on every configuration change.
 */
class CandidateStripView(context: Context) : View(context) {

    /** One candidate and the horizontal band it occupies. Shared with the a11y helper. */
    data class Slot(val prediction: Prediction, val left: Float, val right: Float)

    var onCandidateChosen: ((Prediction) -> Unit)? = null

    /**
     * Exposes each drawn candidate as a virtual view node. Without it the entire strip is one
     * blank rectangle to TalkBack and no suggestion is reachable at all.
     */
    private val accessibilityHelper = CandidateAccessibilityHelper(this).also {
        ViewCompat.setAccessibilityDelegate(this, it)
    }

    private var candidates: List<Prediction> = emptyList()
    private var pressedIndex = -1
    private var slots: List<Slot> = emptyList()

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
    private val correctionLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.candidate_correction)
        textAlign = Paint.Align.CENTER
    }
    private val divider = Paint().apply {
        color = context.getColor(R.color.keyboard_background)
        strokeWidth = 2f
    }

    fun setCandidates(list: List<Prediction>) {
        candidates = list
        pressedIndex = -1
        recomputeSlots()
        invalidate()
    }

    /** Required so ExploreByTouchHelper can receive hover events. */
    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        accessibilityHelper.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = (resources.displayMetrics.density * HEIGHT_DP).toInt()
        setMeasuredDimension(width, height)
        setTextSize(height.toFloat())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        setTextSize(h.toFloat())
        recomputeSlots()
    }

    private fun setTextSize(height: Float) {
        label.textSize = height * LABEL_FRACTION
        correctionLabel.textSize = height * LABEL_FRACTION
    }

    private fun recomputeSlots() {
        if (candidates.isEmpty() || width == 0) {
            slots = emptyList()
            accessibilityHelper.setSlots(slots)
            return
        }
        val slotWidth = width.toFloat() / candidates.size
        // Right-to-left: candidate 0, the best one, occupies the rightmost slot.
        slots = candidates.mapIndexed { i, prediction ->
            val right = width - i * slotWidth
            Slot(prediction, right - slotWidth, right)
        }
        accessibilityHelper.setSlots(slots)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), background)
        if (slots.isEmpty()) return
        val metrics = label.fontMetrics
        val baseline = height / 2f - (metrics.descent + metrics.ascent) / 2f
        slots.forEachIndexed { i, slot ->
            if (i == pressedIndex) {
                canvas.drawRect(slot.left, 0f, slot.right, height.toFloat(), pressed)
            }
            val paint =
                if (slot.prediction.kind == SuggestionKind.CORRECTION) correctionLabel else label
            canvas.drawText(
                slot.prediction.word,
                (slot.left + slot.right) / 2f,
                baseline,
                paint,
            )
            if (i < slots.size - 1) {
                canvas.drawLine(slot.left, height * 0.2f, slot.left, height * 0.8f, divider)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (slots.isEmpty()) return false
        val index = slots.indexOfFirst { event.x >= it.left && event.x < it.right }
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
                    onCandidateChosen?.invoke(slots[index].prediction)
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

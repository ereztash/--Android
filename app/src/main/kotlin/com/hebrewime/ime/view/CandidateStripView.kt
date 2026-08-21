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
 * ### A real-word error names the word it would replace
 * [SuggestionKind.REAL_WORD_ERROR] is the one kind that rewrites text **away from the cursor**
 * — `אם` two words back, in a sentence the user has moved on from. Showing it as a bare word
 * would ask someone to accept a change to something they cannot see, so its label carries the
 * replaced word alongside the replacement: `עם (אם)`. The parenthesised original is what makes
 * the tap predictable.
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

    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.key_label)
        textAlign = Paint.Align.CENTER
        typeface = labelTypeface ?: typeface
    }
    private val correctionLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.candidate_correction)
        textAlign = Paint.Align.CENTER
        typeface = labelTypeface ?: typeface
    }
    private val divider = Paint().apply {
        color = context.getColor(R.color.keyboard_background)
        strokeWidth = 2f
    }

    /**
     * Briefly show what was just applied, then go quiet.
     *
     * ### Why only this needs confirming
     * Applying a completion or a correction changes the word under the cursor, where the user is
     * already looking. A [SuggestionKind.REAL_WORD_ERROR] changes a word **two positions back**
     * — text the eye has left — and on a phone that edit can happen entirely outside the
     * reader's attention. A change the user does not notice is a change they cannot check.
     *
     * So the strip holds the applied word for [CONFIRM_MS] with a mark, instead of clearing
     * instantly as every other application does.
     */
    fun confirmApplied(prediction: Prediction) {
        if (prediction.wordsBack <= 0) {
            setCandidates(emptyList())
            return
        }
        confirmation = "${prediction.word} ✓"
        slots = emptyList()
        pressedIndex = -1
        invalidate()
        removeCallbacks(clearConfirmation)
        postDelayed(clearConfirmation, CONFIRM_MS)
    }

    private var confirmation: String? = null
    private val clearConfirmation = Runnable {
        confirmation = null
        invalidate()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(clearConfirmation)
        super.onDetachedFromWindow()
    }

    fun setCandidates(list: List<Prediction>) {
        confirmation = null
        removeCallbacks(clearConfirmation)
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
        confirmation?.let {
            val m = correctionLabel.fontMetrics
            canvas.drawText(
                it, width / 2f, height / 2f - (m.descent + m.ascent) / 2f, correctionLabel,
            )
            return
        }
        if (slots.isEmpty()) return
        val metrics = label.fontMetrics
        val baseline = height / 2f - (metrics.descent + metrics.ascent) / 2f
        slots.forEachIndexed { i, slot ->
            if (i == pressedIndex) {
                canvas.drawRect(slot.left, 0f, slot.right, height.toFloat(), pressed)
            }
            val kind = slot.prediction.kind
            val paint = if (
                kind == SuggestionKind.CORRECTION || kind == SuggestionKind.REAL_WORD_ERROR
            ) correctionLabel else label
            canvas.drawText(
                labelOf(slot.prediction),
                (slot.left + slot.right) / 2f,
                baseline,
                paint,
            )
            if (i < slots.size - 1) {
                canvas.drawLine(slot.left, height * 0.2f, slot.left, height * 0.8f, divider)
            }
        }
    }

    /** What is drawn for a candidate. See the class docs for why one kind is not just a word. */
    private fun labelOf(prediction: Prediction): String =
        if (prediction.kind == SuggestionKind.REAL_WORD_ERROR && prediction.replaces != null) {
            "${prediction.word} (${prediction.replaces})"
        } else {
            prediction.word
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
        /** Long enough to be seen while glancing back, short enough not to block the next word. */
        const val CONFIRM_MS = 1_200L

        const val HEIGHT_DP = 44f
        const val LABEL_FRACTION = 0.42f
    }
}

package com.hebrewime.ime.view

import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityEvent
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import com.hebrewime.core.prediction.PredictionDescriptions

/**
 * Makes the canvas-drawn suggestions visible to accessibility services.
 *
 * The strip has exactly the problem [KeyboardAccessibilityHelper] solves for the keys: the
 * candidates are `Canvas.drawText` calls inside one `View`, so to TalkBack the strip is a
 * single blank rectangle. Before this class the suggestions were unreachable — a blind user
 * could type but could not use a single suggestion the keyboard offered.
 *
 * It also carries the distinction the strip draws in colour. A correction claims the typed
 * word is *wrong*; a completion claims it is *unfinished*. That difference is spoken, via
 * [PredictionDescriptions], rather than left to a hue.
 */
class CandidateAccessibilityHelper(
    private val host: CandidateStripView,
) : ExploreByTouchHelper(host) {

    private var slots: List<CandidateStripView.Slot> = emptyList()

    /** Called by [CandidateStripView] whenever the candidates or the geometry change. */
    fun setSlots(newSlots: List<CandidateStripView.Slot>) {
        slots = newSlots
        invalidateRoot()
    }

    override fun getVirtualViewAt(x: Float, y: Float): Int {
        val index = slots.indexOfFirst { x >= it.left && x < it.right }
        return if (index >= 0) index else HOST_ID
    }

    override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
        for (i in slots.indices) virtualViewIds.add(i)
    }

    override fun onPopulateNodeForVirtualView(
        virtualViewId: Int,
        node: AccessibilityNodeInfoCompat,
    ) {
        val slot = slots.getOrNull(virtualViewId)
        if (slot == null) {
            // ExploreByTouchHelper throws on an unbounded or undescribed node, even for an id
            // it no longer recognises.
            node.contentDescription = ""
            node.setBoundsInParent(Rect(0, 0, 1, 1))
            return
        }
        node.contentDescription = PredictionDescriptions.describe(slot.prediction)
        node.className = CANDIDATE_CLASS_NAME
        node.isFocusable = true
        node.isClickable = true
        node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
        node.setBoundsInParent(
            Rect(slot.left.toInt(), 0, slot.right.toInt(), host.height.coerceAtLeast(1)),
        )
    }

    override fun onPerformActionForVirtualView(
        virtualViewId: Int,
        action: Int,
        arguments: Bundle?,
    ): Boolean {
        if (action != AccessibilityNodeInfoCompat.ACTION_CLICK) return false
        val slot = slots.getOrNull(virtualViewId) ?: return false
        host.onCandidateChosen?.invoke(slot.prediction)
        sendEventForVirtualView(virtualViewId, AccessibilityEvent.TYPE_VIEW_CLICKED)
        return true
    }

    private companion object {
        /** Reported as Button: assistive technologies key their spoken role off known names. */
        const val CANDIDATE_CLASS_NAME = "android.widget.Button"
        const val HOST_ID = View.NO_ID
    }
}

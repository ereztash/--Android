package com.hebrewime.ime.view

import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityEvent
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import com.hebrewime.core.keyboard.Key
import com.hebrewime.core.keyboard.KeyDescriptions
import com.hebrewime.core.keyboard.KeyRect

/**
 * Makes the canvas-drawn keys visible to accessibility services.
 *
 * ### Why this is not optional
 * The keys are drawn with `Canvas.drawRoundRect`. To the accessibility framework the whole
 * keyboard is **one** `View` with no children, so TalkBack sees a single blank rectangle and
 * a blind user has no keyboard at all. There is no IME-specific accessibility API and no
 * official IME accessibility guidance — `InputMethodService.java` contains zero occurrences of
 * "accessibility" — so the ordinary `View` mechanism is the whole answer: one virtual view node
 * per key, exposed through [ExploreByTouchHelper].
 *
 * ### Nodes are rebuilt, never cached across layouts
 * The key rectangles change on every layout switch and every configuration change. A stale node
 * list would hand the screen reader coordinates for keys that have moved, which is worse than
 * no accessibility: the user is told a key is somewhere it is not.
 */
class KeyboardAccessibilityHelper(
    private val host: KeyboardView,
) : ExploreByTouchHelper(host) {

    private var rects: List<KeyRect> = emptyList()

    /** Called by [KeyboardView] whenever the geometry changes. */
    fun setKeys(newRects: List<KeyRect>) {
        rects = newRects
        invalidateRoot()
    }

    override fun getVirtualViewAt(x: Float, y: Float): Int {
        val index = rects.indexOfFirst { it.contains(x, y) }
        return if (index >= 0) index else HOST_ID
    }

    override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
        for (i in rects.indices) virtualViewIds.add(i)
    }

    override fun onPopulateNodeForVirtualView(
        virtualViewId: Int,
        node: AccessibilityNodeInfoCompat,
    ) {
        val rect = rects.getOrNull(virtualViewId)
        if (rect == null) {
            // ExploreByTouchHelper requires a bounded, described node even for an id it no
            // longer recognises; returning an empty one throws.
            node.contentDescription = ""
            node.setBoundsInParent(Rect(0, 0, 1, 1))
            return
        }
        node.contentDescription = KeyDescriptions.describe(rect.key)
        node.className = KEY_CLASS_NAME
        node.isFocusable = true
        node.isClickable = true
        node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
        node.setBoundsInParent(
            Rect(
                rect.left.toInt(),
                rect.top.toInt(),
                rect.right.toInt(),
                rect.bottom.toInt(),
            )
        )
    }

    override fun onPerformActionForVirtualView(
        virtualViewId: Int,
        action: Int,
        arguments: Bundle?,
    ): Boolean {
        if (action != AccessibilityNodeInfoCompat.ACTION_CLICK) return false
        val key: Key = rects.getOrNull(virtualViewId)?.key ?: return false
        host.onKeyPressed?.invoke(key)
        sendEventForVirtualView(virtualViewId, AccessibilityEvent.TYPE_VIEW_CLICKED)
        return true
    }

    /** Announce a layout change, which is otherwise silent and leaves the user lost. */
    fun announceLayoutChange(description: String) {
        host.announceForAccessibility(description)
    }

    private companion object {
        /**
         * Reported as Button rather than the concrete class: assistive technologies key their
         * behaviour and their spoken role off known framework class names.
         */
        const val KEY_CLASS_NAME = "android.widget.Button"
        const val HOST_ID = View.NO_ID
    }
}

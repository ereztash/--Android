package com.hebrewime.ime.view

import android.content.Context
import android.view.WindowInsets
import android.widget.FrameLayout
import com.hebrewime.R

/**
 * Root container for the keyboard.
 *
 * ### Insets
 * At targetSdk >= 35 `PhoneWindow.generateLayout()` sets `mDecorFitsSystemWindows = false` for
 * every window in the process, with **no IME exemption**, and at 36 the
 * `windowOptOutEdgeToEdgeEnforcement` escape hatch is gone. So this window is edge-to-edge
 * whether or not that was asked for, and without the padding applied below the bottom key row
 * sits underneath the gesture bar.
 *
 * `captionBar()` is included alongside `navigationBars()` because it carries the system IME
 * switcher; when that is hidden the caption inset is where the space goes.
 *
 * ### No state here
 * This view holds no input state, deliberately. `onCreateInputView()` is not called once --
 * its javadoc is wrong. `initViews()` nulls the input view and re-runs from
 * `resetStateForNewConfiguration()` on every configuration change not declared in
 * `<input-method android:configChanges>`, and `InputMethodInfo.getConfigChanges()` is API 31
 * while this app's minSdk is 30, so on API 30 that declaration is not even read. Anything
 * cached in this class would be lost on rotation, theme change, locale change or a density
 * change. State lives on the service.
 */
class KeyboardHostView(context: Context) : FrameLayout(context) {

    init {
        setBackgroundColor(context.getColor(R.color.keyboard_background))
        fitsSystemWindows = false
        setOnApplyWindowInsetsListener { view, insets ->
            val systemInsets = insets.getInsets(
                WindowInsets.Type.navigationBars() or WindowInsets.Type.captionBar()
            )
            view.setPadding(
                systemInsets.left,
                view.paddingTop,
                systemInsets.right,
                systemInsets.bottom,
            )
            insets
        }
    }
}

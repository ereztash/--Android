// POSITIVE CONTROL for GATE-API-1. Every line below is a deliberate defect.
// Compiled by nothing. Excluded from every real scan.
package tools.positive_controls.forbidden_api

class PlantedTraps : android.inputmethodservice.InputMethodService() {

    // §1.6 -- compiles fine, LinkageError in onCreate() at targetSdk >= 34.
    override fun onCreateInputMethodSessionInterface(): AbstractInputMethodSessionImpl {
        return super.onCreateInputMethodSessionInterface()
    }

    fun trapReturnBranch(ic: android.view.inputmethod.InputConnection) {
        // §1.3 -- dead code. commitText returns true even when the editor dropped it.
        if (!ic.commitText("x", 1)) {
            error("this branch can never run")
        }
    }

    fun trapHardcodedBackspace(ic: android.view.inputmethod.InputConnection) {
        // §1.4 -- splits surrogate pairs and Hebrew niqqud stacks.
        ic.deleteSurroundingText(1, 0)
    }

    fun trapBlockingFetch(ic: android.view.inputmethod.InputConnection): CharSequence? {
        // §1.1 -- blocking Binder round-trip, up to 2000 ms.
        return ic.getTextBeforeCursor(2048, 0)
    }

    fun suppressedExample(ic: android.view.inputmethod.InputConnection) {
        ic.deleteSurroundingText(1, 0) // API-GATE-ALLOW: control fixture, proves suppression is visible
    }
}

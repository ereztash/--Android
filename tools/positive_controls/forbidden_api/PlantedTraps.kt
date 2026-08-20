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

    // API 31, so reachable only since minSdk was raised. Same hazard, newer name.
    fun trapSurroundingText(ic: android.view.inputmethod.InputConnection): Any? {
        return ic.getSurroundingText(512, 512, 0)
    }

    fun suppressedExample(ic: android.view.inputmethod.InputConnection) {
        ic.deleteSurroundingText(1, 0) // API-GATE-ALLOW: control fixture, proves suppression is visible
    }
}

// POSITIVE CONTROL additions for the M4 privacy rules. Deliberate defects.
object PlantedPrivacyLeaks {

    // §1.2 -- reading EditorInfo initial text outside the privacy boundary. On a password
    // field this is plaintext the app was handed without asking.
    fun leakInitialText(info: android.view.inputmethod.EditorInfo): CharSequence? {
        return info.getInitialTextBeforeCursor(2048, 0)
    }

    // Anything typed reaching logcat.
    fun leakToLogcat(typed: String) {
        android.util.Log.d("ime", "user typed: $typed")
    }

    fun leakToStdout(typed: String) {
        println(typed)
    }
}

// POSITIVE CONTROL for GATE-NET-1. Deliberately defective. Compiled by nothing.
package tools.positive_controls.network

import okhttp3.OkHttpClient

object PlantedNetworkUsage {
    fun exfiltrate(endpoint: String, keystrokes: String): String {
        val url = java.net.URL(endpoint)
        val conn = url.openConnection()
        conn.getOutputStream().write(keystrokes.toByteArray())
        return "leaked"
    }

    fun browser(): String = "android.webkit.WebView"

    // This line would trip net.client but is suppressed, to prove suppressions are
    // reported in the gate output instead of vanishing silently.
    val suppressedMention = "okhttp3" // NET-GATE-ALLOW: control fixture, proves suppression is visible
}

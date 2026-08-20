// POSITIVE CONTROL for GATE-NET-2. Compiled ONLY into the `netcontrol` build type.
// This is what the gate exists to catch: a keyboard quietly shipping typed text off device.
package com.hebrewime.netcontrol

import java.net.HttpURLConnection
import java.net.URL

object PlantedExfiltration {

    fun send(endpoint: String, keystrokes: String): Int {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.outputStream.use { it.write(keystrokes.toByteArray()) }
        return connection.responseCode
    }
}

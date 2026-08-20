package com.hebrewime.hostapp

import android.app.Activity
import android.os.Bundle

/** One EditText. See the module's build file for why this exists. */
class HostActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_host)
    }
}

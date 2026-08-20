package com.hebrewime.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hebrewime.R

/**
 * Settings, reachable from the system keyboard list via
 * `<input-method android:settingsActivity>`.
 *
 * The attribution block is a licence obligation, not decoration: the lexicon is built from
 * CC BY 4.0 and CC BY-SA 4.0 sources, and attribution is the condition on which this app is
 * allowed to ship them. See docs/LICENSES.md.
 */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Scaffold { inner ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(inner)
                            .verticalScroll(rememberScrollState())
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            stringResource(R.string.settings_title),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text(
                            stringResource(R.string.attribution_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(ATTRIBUTION, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

private val ATTRIBUTION = """
    Hebrew inflected verb list — © Eran Tomer, via NNLP-IL/Hebrew-Resources.
    Licensed under Creative Commons Attribution 4.0 International (CC BY 4.0).

    Word frequency list — derived from the OpenSubtitles corpus via OPUS, published as
    hermitdave/FrequencyWords. Licensed under Creative Commons Attribution-ShareAlike 4.0
    International (CC BY-SA 4.0).

    The combined dictionary shipped with this app is a derivative of the above and is itself
    published under CC BY-SA 4.0. The build script that produces it is part of this project's
    source repository.
""".trimIndent()

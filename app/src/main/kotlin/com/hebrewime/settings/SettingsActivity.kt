package com.hebrewime.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hebrewime.R
import com.hebrewime.core.dictionary.PersonalDictionary
import com.hebrewime.dictionary.PersonalDictionaryRepository
import kotlinx.coroutines.launch

/**
 * Settings, reachable from the system keyboard list via
 * `<input-method android:settingsActivity>`.
 *
 * Two things here are obligations rather than features. The attribution block is a licence
 * condition: the lexicon is built from CC BY 4.0 and CC BY-SA 4.0 sources and attribution is
 * what permits shipping them. And the dictionary management screen exists so that "everything
 * stays on your device" is something the user can act on -- they can see exactly what is
 * stored and destroy it.
 */
class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = PersonalDictionaryRepository(applicationContext)
        setContent {
            MaterialTheme {
                SettingsScreen(repository)
            }
        }
    }
}

@Composable
private fun SettingsScreen(repository: PersonalDictionaryRepository) {
    val scope = rememberCoroutineScope()
    var dictionary by remember { mutableStateOf(PersonalDictionary()) }
    var entries by remember { mutableStateOf(emptyList<String>()) }
    var draft by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmingWipe by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        dictionary = repository.load()
        entries = dictionary.all()
    }

    Scaffold { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineSmall,
            )

            Card {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        stringResource(R.string.dictionary_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(R.string.dictionary_explainer),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it; error = null },
                label = { Text(stringResource(R.string.dictionary_add_label)) },
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    val word = draft.trim()
                    if (!PersonalDictionary.isStorable(word)) {
                        // The type refuses this too; the message is so the user knows why.
                        error = "Enter one Hebrew word, up to " +
                            "${PersonalDictionary.MAX_WORD_LENGTH} letters."
                        return@Button
                    }
                    dictionary.add(word)
                    entries = dictionary.all()
                    draft = ""
                    scope.launch { repository.save(dictionary) }
                },
                enabled = draft.isNotBlank(),
            ) {
                Text(stringResource(R.string.dictionary_add_button))
            }

            Text(
                stringResource(R.string.dictionary_count, entries.size),
                style = MaterialTheme.typography.titleSmall,
            )

            for (word in entries) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(word, style = MaterialTheme.typography.bodyLarge)
                    TextButton(onClick = {
                        dictionary.remove(word)
                        entries = dictionary.all()
                        scope.launch { repository.save(dictionary) }
                    }) {
                        Text(stringResource(R.string.dictionary_delete))
                    }
                }
                HorizontalDivider()
            }

            Button(
                onClick = { confirmingWipe = true },
                enabled = entries.isNotEmpty(),
            ) {
                Text(stringResource(R.string.dictionary_wipe_button))
            }

            HorizontalDivider()
            Text(
                stringResource(R.string.attribution_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(ATTRIBUTION, style = MaterialTheme.typography.bodySmall)
        }
    }

    if (confirmingWipe) {
        AlertDialog(
            onDismissRequest = { confirmingWipe = false },
            title = { Text(stringResource(R.string.dictionary_wipe_button)) },
            text = { Text(stringResource(R.string.dictionary_wipe_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingWipe = false
                    scope.launch {
                        repository.wipe()
                        dictionary = PersonalDictionary()
                        entries = emptyList()
                    }
                }) { Text(stringResource(R.string.dictionary_wipe_button)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingWipe = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
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

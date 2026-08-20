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
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hebrewime.R
import com.hebrewime.core.dictionary.PersonalDictionary
import com.hebrewime.diagnostics.ImeDiagnostics
import com.hebrewime.dictionary.PersonalDictionaryRepository
import androidx.lifecycle.lifecycleScope
import com.hebrewime.learning.LearningPreferences
import com.hebrewime.learning.UserModelRepository
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
        val learning = UserModelRepository(applicationContext, lifecycleScope)
        setContent {
            MaterialTheme {
                SettingsScreen(repository, learning)
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    repository: PersonalDictionaryRepository,
    learning: UserModelRepository,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var dictionary by remember { mutableStateOf(PersonalDictionary()) }
    var entries by remember { mutableStateOf(emptyList<String>()) }
    var draft by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmingWipe by remember { mutableStateOf(false) }
    var learningEnabled by remember { mutableStateOf(LearningPreferences.isEnabled(context)) }
    var learnedPairs by remember { mutableStateOf(0) }
    var confirmingForget by remember { mutableStateOf(false) }
    var diagnostics by remember { mutableStateOf(ImeDiagnostics.read(context)) }

    LaunchedEffect(Unit) {
        dictionary = repository.load()
        entries = dictionary.all()
        // Only read the learned model when learning is on. Loading it while the feature is off
        // would mean touching the Keystore for data the user has not asked the app to use.
        if (learningEnabled) learnedPairs = learning.load().pairCount
        diagnostics = ImeDiagnostics.read(context)
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

            // Keyboard status. This exists because a user reported the keyboard typing fine and
            // never suggesting, and every possible cause was silent -- see ImeDiagnostics.
            Text(
                stringResource(R.string.diagnostics_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.diagnostics_hint),
                style = MaterialTheme.typography.bodySmall,
            )
            Card {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(diagnostics.verdict, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        buildString {
                            append("state: ${diagnostics.state}")
                            if (diagnostics.lexiconWords > 0) {
                                append("\nwords: ${diagnostics.lexiconWords}")
                                append(" · trie nodes: ${diagnostics.trieNodes}")
                                append(" · bigrams: ${diagnostics.bigramPairs}")
                            }
                            if (diagnostics.degradedAssets.isNotEmpty()) {
                                append("\nunreadable: ${diagnostics.degradedAssets}")
                            }
                            append("\nrequests: ${diagnostics.requests}")
                            append(" · permitted: ${diagnostics.allowed}")
                            append(" · produced something: ${diagnostics.nonEmpty}")
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            TextButton(onClick = {
                ImeDiagnostics.reset(context)
                diagnostics = ImeDiagnostics.read(context)
            }) { Text(stringResource(R.string.diagnostics_reset)) }

            HorizontalDivider()

            // Adaptive learning. Default OFF, and off is a real state rather than a disabled
            // toggle: with it off the engine is handed an empty model, which is arithmetically
            // identical to having no model at all.
            Text(
                stringResource(R.string.learning_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.learning_body),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.learning_switch))
                Switch(
                    checked = learningEnabled,
                    onCheckedChange = { on ->
                        learningEnabled = on
                        LearningPreferences.setEnabled(context, on)
                        // Turning it off does not delete what is already stored -- that is what
                        // the forget button is for, and conflating the two would mean someone
                        // pausing the feature silently lost everything.
                        if (on) scope.launch { learnedPairs = learning.load().pairCount }
                    },
                )
            }
            Card {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        stringResource(R.string.learning_never_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        stringResource(R.string.learning_never_body),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Text(
                when {
                    !learningEnabled -> stringResource(R.string.learning_status_off)
                    learnedPairs == 0 -> stringResource(R.string.learning_status_empty)
                    else -> stringResource(R.string.learning_status, learnedPairs)
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = { confirmingForget = true }) {
                Text(stringResource(R.string.learning_forget_button))
            }

            HorizontalDivider()
            Text(
                stringResource(R.string.attribution_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(ATTRIBUTION, style = MaterialTheme.typography.bodySmall)
        }
    }

    if (confirmingForget) {
        AlertDialog(
            onDismissRequest = { confirmingForget = false },
            title = { Text(stringResource(R.string.learning_forget_button)) },
            text = { Text(stringResource(R.string.learning_forget_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingForget = false
                    scope.launch {
                        // Deletes the ciphertext AND the learning key -- a different alias from
                        // the personal dictionary's, so this leaves the dictionary intact.
                        learning.wipe()
                        learnedPairs = 0
                    }
                }) { Text(stringResource(R.string.learning_forget_button)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingForget = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
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

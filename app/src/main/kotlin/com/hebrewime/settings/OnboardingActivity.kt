package com.hebrewime.settings

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hebrewime.R

/**
 * Onboarding: enable the keyboard, then switch to it.
 *
 * Compose is used here and nowhere near the IME window. That is deliberate and is not to be
 * "modernised": there is no official Compose-in-IME support, no sample and no documentation,
 * `ComposeView` throws `IllegalStateException` inside an `InputMethodService` until
 * `LifecycleOwner`, `SavedStateRegistryOwner` and `ViewModelStoreOwner` are hand-rolled, and it
 * would add a recomposition layer to the most latency-sensitive path in the app.
 */
class OnboardingActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                OnboardingScreen(
                    isEnabled = ::isImeEnabled,
                    isSelected = ::isImeSelected,
                    onOpenSettings = {
                        startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                    },
                    onPickKeyboard = {
                        getSystemService(InputMethodManager::class.java)
                            ?.showInputMethodPicker()
                    },
                )
            }
        }
    }

    private fun isImeEnabled(): Boolean =
        getSystemService(InputMethodManager::class.java)
            ?.enabledInputMethodList
            ?.any { it.packageName == packageName } == true

    private fun isImeSelected(): Boolean =
        Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.startsWith("$packageName/") == true
}

@Composable
private fun OnboardingScreen(
    isEnabled: () -> Boolean,
    isSelected: () -> Boolean,
    onOpenSettings: () -> Unit,
    onPickKeyboard: () -> Unit,
) {
    // Re-read on every resume: the user leaves this screen to change the setting and comes
    // back, so a value captured once would always read "not enabled".
    val lifecycleOwner = LocalLifecycleOwner.current
    var enabled by remember { mutableStateOf(isEnabled()) }
    var selected by remember { mutableStateOf(isSelected()) }
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                enabled = isEnabled()
                selected = isSelected()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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
                stringResource(R.string.onboarding_title),
                style = MaterialTheme.typography.headlineSmall,
            )

            InfoCard(
                stringResource(R.string.onboarding_offline_title),
                stringResource(R.string.onboarding_offline_body),
            )
            InfoCard(
                stringResource(R.string.onboarding_sensitive_title),
                stringResource(R.string.onboarding_sensitive_body),
            )

            Text(
                stringResource(R.string.onboarding_step_enable),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(
                    if (enabled) R.string.onboarding_enabled else R.string.onboarding_not_enabled
                )
            )
            Button(onClick = onOpenSettings) {
                Text(stringResource(R.string.onboarding_enable_button))
            }

            Text(
                stringResource(R.string.onboarding_step_select),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(
                    if (selected) R.string.onboarding_selected
                    else R.string.onboarding_not_selected
                )
            )
            Button(onClick = onPickKeyboard, enabled = enabled) {
                Text(stringResource(R.string.onboarding_select_button))
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

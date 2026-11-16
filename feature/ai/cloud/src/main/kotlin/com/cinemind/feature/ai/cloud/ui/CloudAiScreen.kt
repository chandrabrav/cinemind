package com.cinemind.feature.ai.cloud.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.hilt.navigation.compose.hiltViewModel
import com.cinemind.core.ui.components.GlassCard
import com.cinemind.core.ui.components.GradientButton
import com.cinemind.core.ui.components.ScreenHeader
import com.cinemind.core.ui.components.SectionHeader
import com.cinemind.core.ui.components.SettingRow
import com.cinemind.core.ui.components.StatusPill
import com.cinemind.core.ui.components.Tone
import com.cinemind.core.ui.theme.CineMind
import com.cinemind.core.ui.theme.Spacing
import com.cinemind.feature.ai.cloud.CloudKeyStore
import com.cinemind.feature.ai.cloud.CloudModel
import com.cinemind.feature.ai.cloud.CloudSettings
import com.cinemind.feature.ai.cloud.EditAssistant
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CloudAiViewModel @Inject constructor(
    private val keys: CloudKeyStore,
    private val assistant: EditAssistant,
) : ViewModel() {
    val settings: StateFlow<CloudSettings> = keys.settings

    private val _test = MutableStateFlow<String?>(null)
    /** Result of the last key test (null = none yet). */
    val test: StateFlow<String?> = _test.asStateFlow()
    private val _testing = MutableStateFlow(false)
    val testing: StateFlow<Boolean> = _testing.asStateFlow()

    fun save(key: String) {
        runCatching { keys.saveKey(key) }.onFailure { _test.value = it.message }.onSuccess { _test.value = null }
    }

    fun remove() {
        keys.removeKey()
        _test.value = null
    }

    fun setModel(m: CloudModel) = keys.setModel(m)
    fun setConsent(on: Boolean) = keys.setConsent(on)

    fun runTest() {
        if (_testing.value) return
        _testing.value = true
        viewModelScope.launch {
            _test.value = runCatching { "✓ Works: “" + assistant.test() + "”" }.getOrElse { "✗ " + (it.message ?: "Failed") }
            _testing.value = false
        }
    }
}

/** Settings → Cloud AI: bring your own Claude API key. */
@Composable
fun CloudAiScreen(viewModel: CloudAiViewModel = hiltViewModel()) {
    val s by viewModel.settings.collectAsStateWithLifecycle()
    val test by viewModel.test.collectAsStateWithLifecycle()
    val testing by viewModel.testing.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var draft by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = Spacing.xl),
    ) {
        ScreenHeader(title = "Cloud AI", subtitle = "Optional · use your own Claude API key")

        GlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter)) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Claude (Anthropic)", style = MaterialTheme.typography.titleMedium, color = CineMind.colors.textPrimary, modifier = Modifier.weight(1f))
                    StatusPill(if (s.hasKey) "Key saved" else "No key", if (s.hasKey) Tone.Success else Tone.Neutral)
                }
                Text(
                    "Powers the editing assistant with bigger ideas than the on-device model. You pay Anthropic directly for what you use. Without a key, the assistant runs on this phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = CineMind.colors.textTertiary,
                )
                if (s.hasKey) {
                    Text("Key ${s.keyHint}", style = MaterialTheme.typography.bodyMedium, color = CineMind.colors.textSecondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                        OutlinedButton(onClick = viewModel::runTest, enabled = !testing) { Text(if (testing) "Testing…" else "Test key") }
                        TextButton(onClick = viewModel::remove) { Text("Remove key") }
                    }
                } else {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        label = { Text("API key") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    GradientButton(
                        text = "Save key",
                        onClick = { viewModel.save(draft); draft = "" },
                        enabled = draft.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.anthropic.com/settings/keys"))) }
                    }) { Text("Get a key at console.anthropic.com") }
                }
                test?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = CineMind.colors.textSecondary) }
                Text(
                    "Your key is encrypted with this phone's secure keystore and is only sent to Anthropic. It's never in logs, backups or feedback reports.",
                    style = MaterialTheme.typography.labelSmall,
                    color = CineMind.colors.textTertiary,
                )
            }
        }

        SectionHeader("What is sent", modifier = Modifier.padding(top = Spacing.l))
        GlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter)) {
            SettingRow(
                title = "Allow sending text to Claude",
                subtitle = "Your request plus a short text summary of the project: clip lengths, titles and the start of captions. Never your photos, video or sound.",
            ) {
                Switch(checked = s.consented, onCheckedChange = viewModel::setConsent)
            }
        }

        SectionHeader("Model", modifier = Modifier.padding(top = Spacing.l))
        GlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter)) {
            Column {
                CloudModel.entries.forEach { m ->
                    SettingRow(title = m.label, subtitle = m.note) {
                        RadioButton(selected = s.model == m, onClick = { viewModel.setModel(m) })
                    }
                }
            }
        }
        Spacer(Modifier.height(Spacing.l))
    }
}

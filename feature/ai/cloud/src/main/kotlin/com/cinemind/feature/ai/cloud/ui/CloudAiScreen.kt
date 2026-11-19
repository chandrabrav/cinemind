package com.cinemind.feature.ai.cloud.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cinemind.core.domain.aigen.AiMode
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
import com.cinemind.feature.ai.cloud.CloudProvider
import com.cinemind.feature.ai.cloud.CloudSettings
import com.cinemind.feature.ai.cloud.EditAssistant
import com.cinemind.feature.ai.cloud.OpenAiCompatClient
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
    /** Result of the last key test or model list (null = none yet). */
    val test: StateFlow<String?> = _test.asStateFlow()
    private val _testing = MutableStateFlow(false)
    val testing: StateFlow<Boolean> = _testing.asStateFlow()
    private val _models = MutableStateFlow<Map<CloudProvider, List<String>>>(emptyMap())
    /** Models each provider offers this key (loaded on request). */
    val models: StateFlow<Map<CloudProvider, List<String>>> = _models.asStateFlow()

    fun setProvider(p: CloudProvider) {
        keys.setProvider(p)
        _test.value = null
    }

    fun save(p: CloudProvider, key: String) {
        runCatching { keys.saveKey(key, p) }
            .onFailure { _test.value = it.message }
            .onSuccess {
                _test.value = null
                if (p != CloudProvider.ANTHROPIC) loadModels(p)
            }
    }

    fun remove(p: CloudProvider) {
        keys.removeKey(p)
        _models.value = _models.value - p
        _test.value = null
    }

    fun setModel(m: CloudModel) = keys.setModel(m)
    fun setProviderModel(p: CloudProvider, id: String) = keys.setProviderModel(p, id)
    fun setConsent(on: Boolean) = keys.setConsent(on)
    fun setMode(mode: AiMode) = keys.setMode(mode)

    fun loadModels(p: CloudProvider) {
        val key = runCatching { keys.apiKey(p) }.getOrNull() ?: return
        viewModelScope.launch {
            _testing.value = true
            runCatching { OpenAiCompatClient(p, key, "list").listModels() }
                .onSuccess { list ->
                    _models.value = _models.value + (p to list)
                    // First time: pick a sensible default so the service works straight away.
                    if (keys.settings.value.providers[p]?.modelId.isNullOrBlank()) {
                        (list.firstOrNull { defaultModel(p, it) } ?: list.firstOrNull())?.let { keys.setProviderModel(p, it) }
                    }
                    _test.value = if (list.isEmpty()) "✗ No chat models found for this key" else null
                }
                .onFailure { _test.value = "✗ " + (it.message ?: "Couldn't load models") }
            _testing.value = false
        }
    }

    fun runTest() {
        if (_testing.value) return
        _testing.value = true
        viewModelScope.launch {
            _test.value = runCatching { "✓ Works: “" + assistant.test() + "”" }.getOrElse { "✗ " + (it.message ?: "Failed") }
            _testing.value = false
        }
    }

    private fun defaultModel(p: CloudProvider, id: String): Boolean = when (p) {
        CloudProvider.OPENAI -> id.startsWith("gpt-") && !id.contains("mini") && !id.contains("nano")
        CloudProvider.GEMINI -> id.contains("pro")
        CloudProvider.OPENROUTER -> id.startsWith("anthropic/") || id.startsWith("openai/")
        CloudProvider.ANTHROPIC -> true
    }
}

/** AI models → Cloud: connect Claude, ChatGPT, Gemini or OpenRouter with your own key. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CloudAiScreen(viewModel: CloudAiViewModel = hiltViewModel()) {
    val s by viewModel.settings.collectAsStateWithLifecycle()
    val test by viewModel.test.collectAsStateWithLifecycle()
    val testing by viewModel.testing.collectAsStateWithLifecycle()
    val models by viewModel.models.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val c = CineMind.colors
    val p = s.provider
    val state = s.providers[p]
    var draft by remember(p) { mutableStateOf("") }
    var filter by remember(p) { mutableStateOf("") }

    LaunchedEffect(p, state?.hasKey) {
        if (p != CloudProvider.ANTHROPIC && state?.hasKey == true && models[p] == null) viewModel.loadModels(p)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = Spacing.xl),
    ) {
        ScreenHeader(title = "Cloud AI", subtitle = "Optional · your own key · you pay the AI company directly")

        SectionHeader("AI mode")
        GlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter)) {
            Column {
                AiMode.entries.forEach { m ->
                    SettingRow(title = m.label, subtitle = m.note) {
                        RadioButton(selected = s.mode == m, onClick = { viewModel.setMode(m) })
                    }
                }
            }
        }

        SectionHeader("Service", modifier = Modifier.padding(top = Spacing.l))
        GlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter)) {
            Column {
                CloudProvider.entries.forEach { prov ->
                    val ps = s.providers[prov]
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { viewModel.setProvider(prov) }.padding(vertical = Spacing.xs),
                    ) {
                        RadioButton(selected = p == prov, onClick = { viewModel.setProvider(prov) })
                        Column(Modifier.weight(1f)) {
                            Text(prov.label, style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                            Text(prov.company, style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
                        }
                        StatusPill(if (ps?.hasKey == true) "Key saved" else "No key", if (ps?.hasKey == true) Tone.Success else Tone.Neutral)
                    }
                }
            }
        }

        SectionHeader(p.label, modifier = Modifier.padding(top = Spacing.l))
        GlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter)) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Text(
                    "Gives the editing assistant and Smart Edit bigger ideas than the on-device model. ${p.company} bills you for what you use. Without a key, everything runs on this phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textTertiary,
                )
                if (state?.hasKey == true) {
                    Text("Key ${state.keyHint}", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                        OutlinedButton(onClick = viewModel::runTest, enabled = !testing && s.hasKey) { Text(if (testing) "Working…" else "Test") }
                        TextButton(onClick = { viewModel.remove(p) }) { Text("Remove key") }
                    }
                } else {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        label = { Text("${p.label} API key") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    GradientButton(
                        text = "Save key",
                        onClick = { viewModel.save(p, draft); draft = "" },
                        enabled = draft.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.keyUrl))) }
                    }) { Text("Get a key at " + Uri.parse(p.keyUrl).host) }
                }
                test?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.textSecondary) }
                Text(
                    "Your key is encrypted with this phone's secure keystore and only sent to ${p.company}. It's never in logs, backups or feedback reports.",
                    style = MaterialTheme.typography.labelSmall,
                    color = c.textTertiary,
                )
            }
        }

        SectionHeader("Model", modifier = Modifier.padding(top = Spacing.l))
        GlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter)) {
            if (p == CloudProvider.ANTHROPIC) {
                Column {
                    CloudModel.entries.forEach { m ->
                        SettingRow(title = m.label, subtitle = m.note) {
                            RadioButton(selected = s.model == m, onClick = { viewModel.setModel(m) })
                        }
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    val current = state?.modelId.orEmpty()
                    Text(
                        if (current.isBlank()) "No model chosen yet" else "Using $current",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (current.isBlank()) c.textTertiary else c.textPrimary,
                    )
                    val list = models[p].orEmpty()
                    if (state?.hasKey != true) {
                        Text("Save a key to see the models it can use.", style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
                    } else {
                        OutlinedTextField(
                            value = filter,
                            onValueChange = { filter = it },
                            label = { Text("Search models, or type a model id") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            list.filter { filter.isBlank() || it.contains(filter.trim(), ignoreCase = true) }.take(30).forEach { id ->
                                FilterChip(selected = id == current, onClick = { viewModel.setProviderModel(p, id) }, label = { Text(id) })
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                            if (filter.isNotBlank() && filter.trim() !in list) {
                                OutlinedButton(onClick = { viewModel.setProviderModel(p, filter) }) { Text("Use “${filter.trim()}”") }
                            }
                            TextButton(onClick = { viewModel.loadModels(p) }, enabled = !testing) { Text("Refresh list") }
                        }
                    }
                }
            }
        }

        SectionHeader("What is sent", modifier = Modifier.padding(top = Spacing.l))
        GlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter)) {
            SettingRow(
                title = "Allow sending text to ${p.label}",
                subtitle = "Your request plus a short text summary of the project: clip lengths, titles and the start of captions. Never your photos, video or sound.",
            ) {
                Switch(checked = s.consented, onCheckedChange = viewModel::setConsent)
            }
        }
        Spacer(Modifier.height(Spacing.l))
    }
}

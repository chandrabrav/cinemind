package com.cinemind.feature.ai.llm.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Icon
import com.cinemind.core.ui.components.GlassCard
import com.cinemind.core.ui.components.GradientButton
import com.cinemind.core.ui.components.ScreenHeader
import com.cinemind.core.ui.components.SectionHeader
import com.cinemind.core.ui.components.SettingRow
import com.cinemind.core.ui.components.StatusPill
import com.cinemind.core.ui.components.Tone
import com.cinemind.core.ui.theme.CineMind
import com.cinemind.core.ui.theme.Spacing
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cinemind.feature.ai.llm.ondevice.AiPreferences
import com.cinemind.feature.ai.llm.ondevice.BenchmarkResult
import com.cinemind.feature.ai.llm.ondevice.DownloadMath
import com.cinemind.feature.ai.llm.ondevice.ModelBenchmark
import com.cinemind.feature.ai.llm.ondevice.ModelCatalog
import com.cinemind.feature.ai.llm.ondevice.ModelDownloadManager
import com.cinemind.feature.ai.llm.ondevice.ModelSpec
import com.cinemind.feature.ai.llm.ondevice.ModelState
import com.cinemind.feature.ai.llm.ondevice.ModelStore
import com.cinemind.feature.ai.llm.ondevice.OnDeviceLlm
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AiSettingsUi(
    val useAi: Boolean,
    val wifiOnly: Boolean,
    val preferGpu: Boolean,
    val selectedId: String,
    val freeBytes: Long,
    /** Planning model id: "" automatic, "off", or a model id. */
    val plannerId: String = "",
    /** The planning model actually in use (after "automatic" is resolved). */
    val plannerInUse: String? = null,
    /** The speech model in use (Whisper), or null = Gemma's audio. */
    val speechInUse: String? = null,
    val speechId: String = "",
)

sealed interface BenchmarkUi {
    data object Idle : BenchmarkUi
    data object Running : BenchmarkUi
    data class Done(val results: List<BenchmarkResult>) : BenchmarkUi {
        val report: String get() = results.joinToString(separator = "\n") { it.report() }
    }
    data class Failed(val message: String) : BenchmarkUi
}

@HiltViewModel
class AiModelsViewModel @Inject constructor(
    private val downloads: ModelDownloadManager,
    private val store: ModelStore,
    private val prefs: AiPreferences,
    private val llm: OnDeviceLlm,
    private val benchmark: ModelBenchmark,
) : ViewModel() {

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AiSettingsUi> = _settings.asStateFlow()

    private val _bench = MutableStateFlow<BenchmarkUi>(BenchmarkUi.Idle)
    val bench: StateFlow<BenchmarkUi> = _bench.asStateFlow()

    fun state(spec: ModelSpec): StateFlow<ModelState> = downloads.state(spec)

    fun record(spec: ModelSpec) = store.record(spec)

    private fun read() = AiSettingsUi(prefs.useAiInSmartEdit, prefs.wifiOnly, prefs.preferGpu, prefs.selectedModelId, store.freeBytes(), prefs.plannerModelId, llm.reasoningModel()?.id, llm.speechModel()?.id, prefs.speechModelId)

    fun setUseAi(v: Boolean) { prefs.useAiInSmartEdit = v; _settings.value = read() }
    fun setWifiOnly(v: Boolean) { prefs.wifiOnly = v; _settings.value = read() }
    fun setPreferGpu(v: Boolean) {
        prefs.preferGpu = v
        _settings.value = read()
        viewModelScope.launch { llm.unload() } // reload on the new backend next time
    }
    fun select(spec: ModelSpec) {
        if (spec.speech) {
            prefs.speechModelId = spec.id
        } else if (spec.reasoning) {
            prefs.plannerModelId = spec.id
            prefs.crashedModels = prefs.crashedModels - spec.id // the user wants to try it again
        } else prefs.selectedModelId = spec.id
        _settings.value = read()
        viewModelScope.launch { llm.unload() }
    }

    /** Don't use any planning model (text planning falls back to the picture model or the rules). */
    /** Transcribe with Gemma's audio instead of Whisper. */
    fun speechOff() {
        prefs.speechModelId = AiPreferences.PLANNER_OFF
        _settings.value = read()
    }

    fun plannerOff() {
        prefs.plannerModelId = AiPreferences.PLANNER_OFF
        _settings.value = read()
    }

    fun download(spec: ModelSpec) { downloads.start(spec); _settings.value = read() }
    fun pause() = downloads.pause()
    fun delete(spec: ModelSpec) {
        viewModelScope.launch {
            llm.unload()
            downloads.delete(spec)
            _settings.value = read()
        }
    }

    fun runBenchmark() {
        if (_bench.value is BenchmarkUi.Running) return
        _bench.value = BenchmarkUi.Running
        viewModelScope.launch {
            _bench.value = runCatching { benchmark.runAll() }
                .fold({ BenchmarkUi.Done(it) }, { BenchmarkUi.Failed(it.message ?: "Benchmark failed") })
        }
    }
}

/** On-device AI: download/manage models, choose options, and benchmark this phone. */
@Composable
fun AiModelsScreen(viewModel: AiModelsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val bench by viewModel.bench.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val c = CineMind.colors

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = Spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        ScreenHeader(
            title = "On-device AI",
            subtitle = "Runs entirely on this phone · ${DownloadMath.formatBytes(settings.freeBytes)} free",
        )
        Text(
            "Models are downloaded once from their publisher (Hugging Face) and checked for corruption before use. " +
                "Your videos never leave the phone.",
            style = MaterialTheme.typography.bodySmall,
            color = c.textTertiary,
            modifier = Modifier.padding(horizontal = Spacing.gutter),
        )

        SectionHeader("Pictures & speech", hint = "Sees your shots, transcribes speech (Gemma)")
        ModelCatalog.VISUAL.forEach { spec ->
            val state by viewModel.state(spec).collectAsStateWithLifecycle()
            ModelCard(
                spec = spec,
                state = state,
                selected = settings.selectedId == spec.id,
                onSelect = { viewModel.select(spec) },
                onDownload = { viewModel.download(spec) },
                onPause = viewModel::pause,
                onDelete = { viewModel.delete(spec) },
                record = if (state == ModelState.Installed) viewModel.record(spec) else null,
            )
        }

        SectionHeader("Planning (optional)", hint = "Thinks edit plans through on the phone (Spark). Without one, the picture model or the built-in rules plan.")
        ModelCatalog.PLANNERS.forEach { spec ->
            val state by viewModel.state(spec).collectAsStateWithLifecycle()
            ModelCard(
                spec = spec,
                state = state,
                selected = settings.plannerInUse == spec.id,
                onSelect = { viewModel.select(spec) },
                onDownload = { viewModel.download(spec) },
                onPause = viewModel::pause,
                onDelete = { viewModel.delete(spec) },
                record = if (state == ModelState.Installed) viewModel.record(spec) else null,
                inUseText = "In use for planning edits",
            )
        }
        if (settings.plannerId != AiPreferences.PLANNER_OFF) {
            TextButton(onClick = viewModel::plannerOff, modifier = Modifier.padding(horizontal = Spacing.gutter)) { Text("Don't use a planning model", color = c.textSecondary) }
        }

        SectionHeader("Speech (optional)", hint = "Turns speech into captions with exact timings, 99 languages, and can translate to English (OpenAI Whisper). Without one, Gemma listens.")
        ModelCatalog.SPEECH.forEach { spec ->
            val state by viewModel.state(spec).collectAsStateWithLifecycle()
            ModelCard(
                spec = spec,
                state = state,
                selected = settings.speechInUse == spec.id,
                onSelect = { viewModel.select(spec) },
                onDownload = { viewModel.download(spec) },
                onPause = viewModel::pause,
                onDelete = { viewModel.delete(spec) },
                record = if (state == ModelState.Installed) viewModel.record(spec) else null,
                inUseText = "In use for captions",
            )
        }
        if (settings.speechId != AiPreferences.PLANNER_OFF) {
            TextButton(onClick = viewModel::speechOff, modifier = Modifier.padding(horizontal = Spacing.gutter)) { Text("Use Gemma for speech instead", color = c.textSecondary) }
        }

        SectionHeader("Options")
        GlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter)) {
            SettingRow("Use AI in Smart edit", subtitle = "Reads your brief and describes your shots") {
                Switch(checked = settings.useAi, onCheckedChange = viewModel::setUseAi)
            }
            SettingRow("Download on Wi-Fi only", subtitle = "Models are 1.7–3.7 GB") {
                Switch(checked = settings.wifiOnly, onCheckedChange = viewModel::setWifiOnly)
            }
            SettingRow("Use the GPU", subtitle = "Faster; turn off if the app crashes while analysing") {
                Switch(checked = settings.preferGpu, onCheckedChange = viewModel::setPreferGpu)
            }
        }

        SectionHeader("Benchmark this phone", hint = "Every installed model on the same CineMind tasks: load, a text answer, a picture (Gemma) and a real edit plan checked by the app. Send the report back.")
        GlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter)) {
            when (val b = bench) {
                BenchmarkUi.Running -> {
                    Text("Running… the first load can take up to a minute", style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
                    Spacer(Modifier.height(Spacing.s))
                    LinearProgressIndicator(color = c.accent, trackColor = c.surfaceSunken, modifier = Modifier.fillMaxWidth())
                }
                is BenchmarkUi.Done -> {
                    Text(b.report, style = MaterialTheme.typography.bodySmall, color = c.textPrimary)
                    Row {
                        TextButton(onClick = { copy(context, b.report) }) { Text("Copy report", color = c.accent) }
                        TextButton(onClick = viewModel::runBenchmark) { Text("Run again", color = c.textSecondary) }
                    }
                }
                is BenchmarkUi.Failed -> {
                    Text(b.message, color = c.danger, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(Spacing.s))
                    GradientButton("Try again", onClick = viewModel::runBenchmark, icon = Icons.Outlined.Speed)
                }
                BenchmarkUi.Idle -> GradientButton("Run benchmark", onClick = viewModel::runBenchmark, icon = Icons.Outlined.Speed)
            }
        }
    }
}

@Composable
private fun ModelCard(
    spec: ModelSpec,
    state: ModelState,
    selected: Boolean,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onDelete: () -> Unit,
    record: com.cinemind.feature.ai.llm.ondevice.ModelRecord? = null,
    inUseText: String = "In use for Smart edit and captions",
) {
    val c = CineMind.colors
    GlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onSelect)
            Column(modifier = Modifier.weight(1f)) {
                Text(spec.name, style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
                Text("${DownloadMath.formatBytes(spec.sizeBytes)} · ${spec.license}", style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
            }
            when (state) {
                ModelState.Installed -> StatusPill("Installed", Tone.Success)
                is ModelState.Downloading -> StatusPill("Downloading", Tone.Accent)
                ModelState.Verifying -> StatusPill("Checking", Tone.Accent)
                is ModelState.Paused -> StatusPill("Paused", Tone.Warning)
                is ModelState.Failed -> StatusPill("Problem", Tone.Danger)
                ModelState.NotInstalled -> Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = c.textTertiary)
            }
        }
        Text(spec.summary, style = MaterialTheme.typography.bodySmall, color = c.textSecondary, modifier = Modifier.padding(top = Spacing.xs))
        Spacer(Modifier.height(Spacing.m))
        when (state) {
            ModelState.NotInstalled -> GradientButton("Download", onClick = onDownload, icon = Icons.Default.CloudDownload)
            is ModelState.Downloading -> {
                Text(
                    "${DownloadMath.formatBytes(state.bytes)} of ${DownloadMath.formatBytes(state.total)} · ${(state.fraction * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = c.textSecondary,
                )
                Spacer(Modifier.height(Spacing.xs))
                LinearProgressIndicator(progress = { state.fraction }, color = c.accent, trackColor = c.surfaceSunken, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = onPause, modifier = Modifier.padding(top = Spacing.s)) { Text("Pause", color = c.textSecondary) }
            }
            is ModelState.Paused -> Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${(DownloadMath.fraction(state.bytes, state.total) * 100).toInt()}% downloaded",
                    style = MaterialTheme.typography.labelMedium,
                    color = c.textSecondary,
                    modifier = Modifier.weight(1f),
                )
                GradientButton("Resume", onClick = onDownload)
                TextButton(onClick = onDelete) { Text("Discard", color = c.danger) }
            }
            ModelState.Verifying -> {
                Text("Checking the file…", style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
                Spacer(Modifier.height(Spacing.xs))
                LinearProgressIndicator(color = c.accent, trackColor = c.surfaceSunken, modifier = Modifier.fillMaxWidth())
            }
            ModelState.Installed -> Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (selected) inUseText else "Ready",
                        style = MaterialTheme.typography.labelMedium,
                        color = c.textSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDelete) { Text("Delete", color = c.danger) }
                }
                if (record != null) {
                    val date = android.text.format.DateFormat.getMediumDateFormat(LocalContext.current).format(java.util.Date(record.verifiedAtMs))
                    Text(
                        "${DownloadMath.formatBytes(record.sizeBytes)} on this phone · checksum verified $date · format ${record.format}. Kept when the app updates.",
                        style = MaterialTheme.typography.labelSmall,
                        color = c.textTertiary,
                    )
                }
            }
            is ModelState.Failed -> Column {
                Text(state.message, color = c.danger, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(Spacing.s))
                GradientButton(if (state.bytes > 0) "Resume" else "Try again", onClick = onDownload)
            }
        }
    }
}

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("CineMind benchmark", text))
    Toast.makeText(context, "Report copied", Toast.LENGTH_SHORT).show()
}

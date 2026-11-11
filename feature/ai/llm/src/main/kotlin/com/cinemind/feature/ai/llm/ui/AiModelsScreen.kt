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
)

sealed interface BenchmarkUi {
    data object Idle : BenchmarkUi
    data object Running : BenchmarkUi
    data class Done(val result: BenchmarkResult) : BenchmarkUi
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

    private fun read() = AiSettingsUi(prefs.useAiInSmartEdit, prefs.wifiOnly, prefs.preferGpu, prefs.selectedModelId, store.freeBytes())

    fun setUseAi(v: Boolean) { prefs.useAiInSmartEdit = v; _settings.value = read() }
    fun setWifiOnly(v: Boolean) { prefs.wifiOnly = v; _settings.value = read() }
    fun setPreferGpu(v: Boolean) {
        prefs.preferGpu = v
        _settings.value = read()
        viewModelScope.launch { llm.unload() } // reload on the new backend next time
    }
    fun select(spec: ModelSpec) {
        prefs.selectedModelId = spec.id
        _settings.value = read()
        viewModelScope.launch { llm.unload() }
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
            _bench.value = runCatching { benchmark.run() }
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("On-device AI", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Models run entirely on this phone; your videos never leave it. A model is downloaded once " +
                "from its publisher (Hugging Face) and checked for corruption before use. " +
                "Free storage: ${DownloadMath.formatBytes(settings.freeBytes)}.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ModelCatalog.ALL.forEach { spec ->
            val state by viewModel.state(spec).collectAsStateWithLifecycle()
            ModelCard(
                spec = spec,
                state = state,
                selected = settings.selectedId == spec.id,
                onSelect = { viewModel.select(spec) },
                onDownload = { viewModel.download(spec) },
                onPause = viewModel::pause,
                onDelete = { viewModel.delete(spec) },
            )
        }

        SwitchRow("Use AI in Smart edit", "Describes your shots and reads your brief with the model.", settings.useAi, viewModel::setUseAi)
        SwitchRow("Download on Wi-Fi only", "Models are 2.6–3.7 GB.", settings.wifiOnly, viewModel::setWifiOnly)
        SwitchRow("Use the GPU", "Faster; turn off if the app crashes while analysing.", settings.preferGpu, viewModel::setPreferGpu)

        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Benchmark this phone", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Loads the selected model and times a text answer and one image analysis. Send the report back so the model choice is based on your phone's real numbers.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                when (val b = bench) {
                    BenchmarkUi.Running -> {
                        Text("Running… (the first load can take up to a minute)")
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    is BenchmarkUi.Done -> {
                        Text(b.result.report(), style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(onClick = { copy(context, b.result.report()) }) { Text("Copy report") }
                            TextButton(onClick = viewModel::runBenchmark) { Text("Run again") }
                        }
                    }
                    is BenchmarkUi.Failed -> {
                        Text(b.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        Button(onClick = viewModel::runBenchmark) { Text("Try again") }
                    }
                    BenchmarkUi.Idle -> Button(onClick = viewModel::runBenchmark) { Text("Run benchmark") }
                }
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
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected, onClick = onSelect)
                Column(modifier = Modifier.weight(1f)) {
                    Text(spec.name, style = MaterialTheme.typography.titleSmall)
                    Text("${DownloadMath.formatBytes(spec.sizeBytes)} · ${spec.license}", style = MaterialTheme.typography.labelSmall)
                }
            }
            Text(spec.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            when (state) {
                ModelState.NotInstalled -> Button(onClick = onDownload) { Text("Download") }
                is ModelState.Downloading -> {
                    Text("Downloading ${DownloadMath.formatBytes(state.bytes)} of ${DownloadMath.formatBytes(state.total)}")
                    LinearProgressIndicator(progress = { state.fraction }, modifier = Modifier.fillMaxWidth())
                    OutlinedButton(onClick = onPause) { Text("Pause") }
                }
                is ModelState.Paused -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Paused at ${(DownloadMath.fraction(state.bytes, state.total) * 100).toInt()}%", modifier = Modifier.weight(1f))
                    Button(onClick = onDownload) { Text("Resume") }
                    TextButton(onClick = onDelete) { Text("Discard") }
                }
                ModelState.Verifying -> {
                    Text("Checking the file…")
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                ModelState.Installed -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Installed", color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDelete) { Text("Delete") }
                }
                is ModelState.Failed -> Column {
                    Text(state.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = onDownload) { Text(if (state.bytes > 0) "Resume" else "Try again") }
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("CineMind benchmark", text))
    Toast.makeText(context, "Report copied", Toast.LENGTH_SHORT).show()
}

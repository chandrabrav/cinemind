package com.cinemind.feature.teleprompter

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cinemind.core.common.CineMindLogger
import com.cinemind.core.domain.model.MediaAsset
import com.cinemind.core.domain.model.ProjectFactory
import com.cinemind.core.domain.repository.MediaAssetRepository
import com.cinemind.core.domain.repository.ProjectRepository
import com.cinemind.core.domain.teleprompter.AutoScroll
import com.cinemind.core.domain.teleprompter.Script
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/** A recorded take of one script segment. */
data class Take(val file: File, val durationUs: Long)

data class TeleprompterUi(
    val script: String = "",
    val wpm: Int = 140,
    /** Text size in sp. */
    val textSize: Int = 30,
    val mirror: Boolean = false,
    val frontCamera: Boolean = true,
    val segment: Int = 0,
    val phase: Phase = Phase.EDIT,
    val countdown: Int = 0,
    /** Next word to read (index into the script's words). */
    val position: Int = 0,
    val elapsedMs: Long = 0,
    val takes: Map<Int, Take> = emptyMap(),
    val saving: Boolean = false,
    val error: String? = null,
) {
    enum class Phase { EDIT, READY, COUNTDOWN, RECORDING }
}

/**
 * Teleprompter Studio: write (or paste) a script, read it to the camera with the text scrolling
 * near the lens, record it in segments (one take per paragraph; any segment can be retaken), and
 * turn the takes into an editable project. The script scrolls at the chosen reading speed and can
 * be nudged by dragging. Takes are kept in the app's own storage; nothing is uploaded.
 */
@HiltViewModel
class TeleprompterViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val projectRepository: ProjectRepository,
    private val assetRepository: MediaAssetRepository,
) : ViewModel() {

    private val prefs = context.getSharedPreferences("teleprompter", Context.MODE_PRIVATE)
    private val _ui = MutableStateFlow(
        TeleprompterUi(
            script = prefs.getString("draft", "").orEmpty(),
            wpm = prefs.getInt("wpm", 140),
            textSize = prefs.getInt("textSize", 30),
            mirror = prefs.getBoolean("mirror", false),
        ),
    )
    val ui: StateFlow<TeleprompterUi> = _ui.asStateFlow()
    private val created = Channel<String>(Channel.BUFFERED)
    /** Emits the new project's id after [finish]. */
    val createdFlow = created.receiveAsFlow()

    private var parsed = Script(_ui.value.script)
    val script: Script get() = parsed
    private var ticker: Job? = null
    private var scrollStartWord = 0
    private var scrollStartMs = 0L

    fun setScript(text: String) {
        _ui.value = _ui.value.copy(script = text, takes = emptyMap(), segment = 0, position = 0)
        parsed = Script(text)
        prefs.edit().putString("draft", text).apply()
    }

    fun setWpm(wpm: Int) {
        val w = wpm.coerceIn(60, 260)
        // Keep the current word when the speed changes mid-take.
        scrollStartWord = _ui.value.position
        scrollStartMs = System.currentTimeMillis()
        _ui.value = _ui.value.copy(wpm = w)
        prefs.edit().putInt("wpm", w).apply()
    }

    fun setTextSize(sp: Int) {
        _ui.value = _ui.value.copy(textSize = sp.coerceIn(18, 56))
        prefs.edit().putInt("textSize", _ui.value.textSize).apply()
    }

    fun setMirror(on: Boolean) {
        _ui.value = _ui.value.copy(mirror = on)
        prefs.edit().putBoolean("mirror", on).apply()
    }

    fun flipCamera() {
        if (_ui.value.phase == TeleprompterUi.Phase.RECORDING) return
        _ui.value = _ui.value.copy(frontCamera = !_ui.value.frontCamera)
    }

    /** Leaves the editor for the camera, on the first segment without a take. */
    fun startSession() {
        if (parsed.segments.isEmpty()) {
            _ui.value = _ui.value.copy(error = "Write a script first (blank lines split it into segments)")
            return
        }
        val first = parsed.segments.firstOrNull { it.index !in _ui.value.takes }?.index ?: 0
        selectSegment(first)
        _ui.value = _ui.value.copy(phase = TeleprompterUi.Phase.READY, error = null)
    }

    fun backToEditor() {
        stopTicker()
        _ui.value = _ui.value.copy(phase = TeleprompterUi.Phase.EDIT)
    }

    fun selectSegment(index: Int) {
        if (_ui.value.phase == TeleprompterUi.Phase.RECORDING) return
        val seg = parsed.segments.getOrNull(index) ?: return
        _ui.value = _ui.value.copy(segment = index, position = seg.firstWord, elapsedMs = 0)
    }

    /** 3-2-1, then [onGo] (the screen starts the camera recording). */
    fun countdown(onGo: () -> Unit) {
        if (_ui.value.phase != TeleprompterUi.Phase.READY) return
        viewModelScope.launch {
            for (n in 3 downTo 1) {
                _ui.value = _ui.value.copy(phase = TeleprompterUi.Phase.COUNTDOWN, countdown = n)
                delay(1_000)
                if (_ui.value.phase != TeleprompterUi.Phase.COUNTDOWN) return@launch
            }
            _ui.value = _ui.value.copy(phase = TeleprompterUi.Phase.RECORDING, countdown = 0, elapsedMs = 0)
            onGo()
            startTicker()
        }
    }

    fun cancelCountdown() {
        if (_ui.value.phase == TeleprompterUi.Phase.COUNTDOWN) _ui.value = _ui.value.copy(phase = TeleprompterUi.Phase.READY, countdown = 0)
    }

    private fun startTicker() {
        stopTicker()
        scrollStartWord = _ui.value.position
        scrollStartMs = System.currentTimeMillis()
        val startedAt = scrollStartMs
        ticker = viewModelScope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                val pos = AutoScroll.positionAt(scrollStartWord, now - scrollStartMs, _ui.value.wpm, parsed.words.size)
                _ui.value = _ui.value.copy(position = pos, elapsedMs = now - startedAt)
                delay(50)
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    /** Drag on the script while recording: move the reading point by [words] (keeps scrolling from there). */
    fun nudge(words: Int) {
        val seg = parsed.segments.getOrNull(_ui.value.segment) ?: return
        val pos = (_ui.value.position + words).coerceIn(seg.firstWord, parsed.words.size)
        scrollStartWord = pos
        scrollStartMs = System.currentTimeMillis()
        _ui.value = _ui.value.copy(position = pos)
    }

    /** Recording stopped: [file] is the take for the current segment (replaces an earlier one). */
    fun onTakeRecorded(file: File?, error: String?) {
        stopTicker()
        val seg = _ui.value.segment
        if (file == null || !file.exists() || file.length() == 0L) {
            _ui.value = _ui.value.copy(phase = TeleprompterUi.Phase.READY, error = error ?: "The take couldn't be saved")
            return
        }
        val duration = durationUs(file)
        _ui.value.takes[seg]?.file?.takeIf { it != file }?.delete()
        val takes = _ui.value.takes + (seg to Take(file, duration))
        val next = parsed.segments.firstOrNull { it.index > seg && it.index !in takes }?.index
            ?: parsed.segments.firstOrNull { it.index !in takes }?.index
            ?: seg
        _ui.value = _ui.value.copy(takes = takes, phase = TeleprompterUi.Phase.READY, error = error)
        selectSegment(next)
    }

    fun deleteTake(index: Int) {
        _ui.value.takes[index]?.file?.delete()
        _ui.value = _ui.value.copy(takes = _ui.value.takes - index)
    }

    /** Where the next take goes (app storage; removed when the take is replaced or deleted). */
    fun newTakeFile(): File {
        val dir = File(context.filesDir, "teleprompter").apply { mkdirs() }
        return File(dir, "take_" + System.currentTimeMillis() + ".mp4")
    }

    /** Makes a project from the takes, in script order. */
    fun finish() {
        val takes = _ui.value.takes.toSortedMap()
        if (takes.isEmpty() || _ui.value.saving) return
        _ui.value = _ui.value.copy(saving = true)
        viewModelScope.launch {
            runCatching {
                val assets = withContext(Dispatchers.IO) {
                    takes.values.mapNotNull { t -> assetFor(t.file)?.also { assetRepository.createAsset(it) } }
                }
                require(assets.isNotEmpty()) { "No readable takes" }
                val firstWords = parsed.words.take(4).joinToString(" ") { it.text }.take(32)
                val name = firstWords.ifBlank { "Teleprompter " + SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date()) }
                projectRepository.createProjectWithTimeline(ProjectFactory.fromAssets(name, assets))
            }.onSuccess { id ->
                _ui.value = _ui.value.copy(saving = false, takes = emptyMap(), phase = TeleprompterUi.Phase.EDIT)
                created.send(id)
            }.onFailure {
                CineMindLogger.e(it, "Teleprompter project failed")
                _ui.value = _ui.value.copy(saving = false, error = "Couldn't make the project: ${it.message}")
            }
        }
    }

    private fun durationUs(file: File): Long = runCatching {
        MediaMetadataRetriever().use { r ->
            r.setDataSource(file.absolutePath)
            (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1_000
        }
    }.getOrDefault(0L)

    /** A media asset for a recorded take (display-oriented size, real frame rate). */
    private fun assetFor(file: File): MediaAsset? = runCatching {
        MediaMetadataRetriever().use { r ->
            r.setDataSource(file.absolutePath)
            fun key(k: Int) = r.extractMetadata(k)
            val w = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val (ow, oh) = if (rot == 90 || rot == 270) h to w else w to h
            val durationUs = (key(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1_000
            val fps = key(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()?.takeIf { it > 1f } ?: 30f
            if (durationUs <= 0) return@use null
            MediaAsset(
                uri = Uri.fromFile(file).toString(),
                mimeType = "video/mp4",
                width = ow,
                height = oh,
                durationUs = durationUs,
                frameRate = fps,
                hasAudio = key(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes",
                dateTaken = file.lastModified(),
                displayName = file.name,
                sizeBytes = file.length(),
            )
        }
    }.getOrNull()

    override fun onCleared() {
        stopTicker()
        super.onCleared()
    }
}

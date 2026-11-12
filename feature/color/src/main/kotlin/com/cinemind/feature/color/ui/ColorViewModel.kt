package com.cinemind.feature.color.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cinemind.core.domain.color.LutAdvisor
import com.cinemind.core.domain.color.LutFormatException
import com.cinemind.core.domain.color.LutSuggestion
import com.cinemind.core.domain.color.SceneProfile
import com.cinemind.feature.color.library.FootageStats
import com.cinemind.feature.color.library.FrameSample
import com.cinemind.feature.color.library.LutEntry
import com.cinemind.feature.color.library.LutLibrary
import com.cinemind.feature.color.library.LutPackInfo
import com.cinemind.feature.color.library.LutSource
import com.cinemind.feature.color.library.SceneProbe
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class LookFilter(val label: String) { SUGGESTED("For this video"), ALL("All"), CINEMIND("CineMind"), PACK("Film pack"), IMPORTED("Imported") }

data class SuggestedLook(val entry: LutEntry, val reason: String)

data class ColorUi(
    val query: String = "",
    val filter: LookFilter = LookFilter.SUGGESTED,
    val entries: List<LutEntry> = emptyList(),
    val suggestions: List<SuggestedLook> = emptyList(),
    val stats: FootageStats? = null,
    /** Words the suggestions are based on (shown to the user). */
    val basis: List<String> = emptyList(),
    val aiAvailable: Boolean = false,
    val aiBusy: Boolean = false,
    val aiUsed: Boolean = false,
    val downloading: Set<String> = emptySet(),
    val message: String? = null,
    val packNoticeAccepted: Boolean = false,
    val packInfo: LutPackInfo? = null,
    /** Bumped when thumbnails change. */
    val thumbsVersion: Int = 0,
)

/**
 * The colour tool's library side: search, filters, AI look suggestions for the footage and the
 * project's theme, live look thumbnails on the real frame, pack downloads and .cube import.
 * The grade itself is applied by the editor (one undo step per change).
 */
@HiltViewModel
class ColorViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val library: LutLibrary,
    private val probe: SceneProbe,
) : ViewModel() {

    private val prefs = context.getSharedPreferences("color", Context.MODE_PRIVATE)
    private val _ui = MutableStateFlow(ColorUi(packNoticeAccepted = prefs.getBoolean("pack_notice", false), packInfo = library.packInfo, aiAvailable = probe.aiAvailable))
    val ui: StateFlow<ColorUi> = _ui.asStateFlow()

    private var frame: FrameSample? = null
    private var themeWords: Set<String> = emptySet()
    private var aiWords: Set<String> = emptySet()
    private val thumbs = java.util.concurrent.ConcurrentHashMap<String, Bitmap>()
    private var thumbJob: Job? = null

    /** Called when the sheet opens: the frame to judge/preview on and the project's own words. */
    fun start(frameUri: String?, isVideo: Boolean, timeUs: Long, themeText: String) {
        themeWords = LutAdvisor.keywords(themeText)
        _ui.value = _ui.value.copy(aiAvailable = probe.aiAvailable)
        viewModelScope.launch {
            frame = frameUri?.let { probe.frame(it, isVideo, timeUs) }
            thumbs.clear()
            refresh()
        }
    }

    fun setQuery(q: String) {
        _ui.value = _ui.value.copy(query = q, filter = if (q.isNotBlank() && _ui.value.filter == LookFilter.SUGGESTED) LookFilter.ALL else _ui.value.filter)
        refresh()
    }

    fun setFilter(f: LookFilter) {
        _ui.value = _ui.value.copy(filter = f)
        refresh()
    }

    fun thumbnail(id: String): Bitmap? = thumbs[id]

    /** On-device AI names the scene; suggestions are recomputed with those words. */
    fun askAi() {
        val f = frame ?: return
        if (_ui.value.aiBusy) return
        _ui.value = _ui.value.copy(aiBusy = true, message = null)
        viewModelScope.launch {
            runCatching { probe.aiSceneWords(f) }
                .onSuccess { aiWords = it; _ui.value = _ui.value.copy(aiUsed = true) }
                .onFailure { _ui.value = _ui.value.copy(message = "The on-device AI couldn't describe the scene: ${it.message}") }
            _ui.value = _ui.value.copy(aiBusy = false)
            refresh()
        }
    }

    fun acceptPackNotice() {
        prefs.edit().putBoolean("pack_notice", true).apply()
        _ui.value = _ui.value.copy(packNoticeAccepted = true)
    }

    /** Downloads a film-pack LUT; [then] runs with the entry once it's ready. */
    fun download(entry: LutEntry, then: (LutEntry) -> Unit) {
        if (entry.id in _ui.value.downloading) return
        _ui.value = _ui.value.copy(downloading = _ui.value.downloading + entry.id, message = null)
        viewModelScope.launch {
            runCatching { library.download(entry.id) }
                .onSuccess { then(entry.copy(available = true)) }
                .onFailure { _ui.value = _ui.value.copy(message = "Couldn't download “${entry.name}”: ${it.message}") }
            _ui.value = _ui.value.copy(downloading = _ui.value.downloading - entry.id)
            refresh()
        }
    }

    fun importCube(uri: Uri, displayName: String?, then: (LutEntry) -> Unit) {
        viewModelScope.launch {
            runCatching { library.importCube(uri, displayName) }
                .onSuccess {
                    _ui.value = _ui.value.copy(filter = LookFilter.IMPORTED, message = "Imported “${it.name}”")
                    refresh()
                    then(it)
                }
                .onFailure { e ->
                    val why = if (e is LutFormatException) e.message else "The file couldn't be read (${e.message})"
                    _ui.value = _ui.value.copy(message = "Not imported: $why")
                }
        }
    }

    fun deleteImported(entry: LutEntry) {
        library.deleteImported(entry.id)
        refresh()
    }

    fun dismissMessage() { _ui.value = _ui.value.copy(message = null) }

    private fun refresh() {
        val s = _ui.value
        val stats = frame?.let { probe.measure(listOf(it)) }
        val all = library.entries()
        val words = themeWords + aiWords
        val profile = SceneProfile(words = words, brightness = stats?.brightness, saturation = stats?.saturation, warmth = stats?.warmth)
        // Suggest from looks usable right now (CineMind, imported, downloaded pack) plus the pack's best few.
        val suggestions = LutAdvisor.suggest(profile, all.map { it.candidate() }, limit = 8)
            .mapNotNull { sug: LutSuggestion -> all.firstOrNull { it.id == sug.id }?.let { SuggestedLook(it, sug.reason) } }
        val entries = when (s.filter) {
            LookFilter.SUGGESTED -> suggestions.map { it.entry }
            LookFilter.ALL -> library.search(s.query)
            LookFilter.CINEMIND -> library.search(s.query, LutSource.BUILTIN)
            LookFilter.PACK -> library.search(s.query, LutSource.PACK)
            LookFilter.IMPORTED -> library.search(s.query, LutSource.IMPORTED)
        }
        val basis = (aiWords.take(6) + themeWords.filter { w -> all.any { w in it.tags } }.take(6)).distinct()
        _ui.value = s.copy(entries = entries, suggestions = suggestions, stats = stats, basis = basis)
        renderThumbs(entries.filter { it.available }.take(60))
    }

    private fun renderThumbs(list: List<LutEntry>) {
        val f = frame ?: return
        thumbJob?.cancel()
        thumbJob = viewModelScope.launch {
            withContext(Dispatchers.Default) {
                list.filter { !thumbs.containsKey(it.id) }.forEach { e ->
                    val lut = library.load(e.id) ?: return@forEach
                    thumbs[e.id] = probe.preview(f, lut)
                }
            }
            _ui.value = _ui.value.copy(thumbsVersion = _ui.value.thumbsVersion + 1)
        }
    }

    /** The frame without a look (for the "None" tile and before/after). */
    fun originalFrame(): Bitmap? = frame?.bitmap
}

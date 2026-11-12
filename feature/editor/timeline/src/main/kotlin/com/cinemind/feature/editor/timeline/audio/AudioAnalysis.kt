package com.cinemind.feature.editor.timeline.audio

import android.content.Context
import android.net.Uri
import com.cinemind.core.common.CineMindLogger
import com.cinemind.core.domain.audio.BeatDetector
import com.cinemind.core.domain.audio.BeatInfo
import com.cinemind.core.domain.audio.Waveform
import com.cinemind.core.domain.model.MediaAsset
import com.cinemind.feature.captions.speech.AudioExtractor
import com.cinemind.feature.captions.speech.NoAudioException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Waveforms and beats for audio on the timeline. Each sound is decoded once (16 kHz mono, the
 * phone's own decoders), then its peaks (and, for music, beats) are kept in memory and in
 * `files/audio-analysis/` so reopening a project is instant. Originals are only read.
 */
class AudioAnalysis(private val context: Context) {

    private val extractor = AudioExtractor(context)
    private val dir = File(context.filesDir, "audio-analysis").apply { mkdirs() }
    private val peaksCache = ConcurrentHashMap<String, FloatArray>()
    private val beatsCache = ConcurrentHashMap<String, BeatInfo>()
    private val lock = Mutex()

    private fun key(asset: MediaAsset) = (asset.id + "_" + asset.sizeBytes + "_" + asset.durationUs).replace(Regex("[^A-Za-z0-9_-]"), "")

    fun cachedPeaks(asset: MediaAsset): FloatArray? = peaksCache[key(asset)]
    fun cachedBeats(asset: MediaAsset): BeatInfo? = beatsCache[key(asset)]

    /** Peaks ([Waveform.BUCKET_US] each) for the whole sound, or null if it has no audio. */
    suspend fun peaks(asset: MediaAsset): FloatArray? = lock.withLock { analyse(asset, wantBeats = false)?.first }

    /** Beats of a song (null when it has no clear pulse or no audio). */
    suspend fun beats(asset: MediaAsset): BeatInfo? = lock.withLock { analyse(asset, wantBeats = true)?.second }

    private suspend fun analyse(asset: MediaAsset, wantBeats: Boolean): Pair<FloatArray, BeatInfo?>? = withContext(Dispatchers.Default) {
        val k = key(asset)
        val peaksFile = File(dir, "$k.peaks")
        val beatsFile = File(dir, "$k.beats")
        var peaks = peaksCache[k] ?: readPeaks(peaksFile)?.also { peaksCache[k] = it }
        var beats = beatsCache[k] ?: runCatching { BeatInfo.decode(beatsFile.readText()) }.getOrNull()?.also { beatsCache[k] = it }
        if (peaks != null && (!wantBeats || beats != null || beatsFile.exists())) return@withContext peaks to beats
        val samples = try {
            extractor.extract(Uri.parse(asset.uri), 0, if (asset.durationUs > 0) asset.durationUs else Long.MAX_VALUE / 4)
        } catch (e: NoAudioException) {
            return@withContext null
        } catch (e: Exception) {
            CineMindLogger.w("Audio analysis failed for ${asset.displayName}: ${e.message}")
            return@withContext null
        }
        if (samples.isEmpty()) return@withContext null
        peaks = Waveform.peaks(samples, (BeatDetector.SAMPLE_RATE * Waveform.BUCKET_US / 1_000_000L).toInt())
        peaksCache[k] = peaks
        writePeaks(peaksFile, peaks)
        if (wantBeats) {
            beats = BeatDetector.detect(samples)
            beats?.let { beatsCache[k] = it }
            runCatching { beatsFile.writeText(beats?.encode() ?: "") }
        }
        peaks to beats
    }

    private fun readPeaks(f: File): FloatArray? = runCatching {
        if (!f.exists()) return null
        DataInputStream(f.inputStream().buffered()).use { input ->
            val n = input.readInt()
            if (n !in 0..10_000_000) return null
            FloatArray(n) { input.readFloat() }
        }
    }.getOrNull()

    private fun writePeaks(f: File, p: FloatArray) {
        runCatching {
            DataOutputStream(f.outputStream().buffered()).use { out ->
                out.writeInt(p.size)
                p.forEach { out.writeFloat(it) }
            }
        }
    }
}

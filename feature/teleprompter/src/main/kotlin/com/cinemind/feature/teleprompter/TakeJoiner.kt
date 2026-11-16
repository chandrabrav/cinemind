package com.cinemind.feature.teleprompter

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Joins a take's camera video (no sound) with the sound CineMind captured for voice-follow. */
@OptIn(UnstableApi::class)
object TakeJoiner {

    suspend fun join(context: Context, video: File, wav: File, out: File): File = withContext(Dispatchers.Main) {
        val videoItem = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(video))).setRemoveAudio(true).build()
        val audioItem = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(wav))).build()
        val composition = Composition.Builder(
            EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO)).addItem(videoItem).build(),
            EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO)).addItem(audioItem).build(),
        ).build()
        suspendCancellableCoroutine { cont ->
            val transformer = Transformer.Builder(context)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (cont.isActive) cont.resume(out)
                    }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        if (cont.isActive) cont.resumeWithException(exportException)
                    }
                })
                .build()
            transformer.start(composition, out.absolutePath)
            cont.invokeOnCancellation { transformer.cancel() }
        }
    }
}

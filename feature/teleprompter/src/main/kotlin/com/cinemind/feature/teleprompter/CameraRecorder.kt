package com.cinemind.feature.teleprompter

import android.annotation.SuppressLint
import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cinemind.core.common.CineMindLogger
import java.io.File

/**
 * Camera preview + video recording (CameraX), with sound. 1080p when the camera supports it,
 * falling back to the best lower quality. One recording at a time.
 */
class CameraRecorder(private val context: Context) {

    private val recorder = Recorder.Builder()
        .setQualitySelector(QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
        .build()
    val videoCapture: VideoCapture<Recorder> = VideoCapture.withOutput(recorder)
    private var recording: Recording? = null

    val isRecording: Boolean get() = recording != null

    /** Starts recording into [file]; [onDone] gets the file (or null) and an error message, once stopped. */
    @SuppressLint("MissingPermission") // the screen asks for camera + microphone before showing the camera
    fun start(file: File, withAudio: Boolean, onDone: (File?, String?) -> Unit) {
        if (recording != null) return
        val pending = videoCapture.output.prepareRecording(context, FileOutputOptions.Builder(file).build())
            .let { if (withAudio) it.withAudioEnabled() else it }
        recording = pending.start(ContextCompat.getMainExecutor(context)) { event ->
            if (event is VideoRecordEvent.Finalize) {
                recording = null
                if (event.hasError() && event.error != VideoRecordEvent.Finalize.ERROR_NONE) {
                    CineMindLogger.w("Take finished with error ${event.error}: ${event.cause?.message}")
                    // Some errors still leave a usable file (e.g. stopped because storage ran low).
                    onDone(file.takeIf { it.exists() && it.length() > 0 }, "Recording problem (code ${event.error})")
                } else {
                    onDone(file, null)
                }
            }
        }
    }

    fun stop() {
        recording?.stop()
    }
}

/** The live camera image, bound to the screen's lifecycle; [front] picks the selfie camera. */
@Composable
fun CameraPreview(recorder: CameraRecorder, front: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val view = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    DisposableEffect(front, owner) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            runCatching {
                val p = future.get()
                provider = p
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                p.unbindAll()
                p.bindToLifecycle(owner, selector, preview, recorder.videoCapture)
            }.onFailure { CineMindLogger.e(it, "Camera couldn't start") }
        }, ContextCompat.getMainExecutor(context))
        onDispose { runCatching { provider?.unbindAll() } }
    }
    AndroidView(factory = { view }, modifier = modifier)
}

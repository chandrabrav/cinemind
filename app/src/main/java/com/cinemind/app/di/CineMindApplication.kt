package com.cinemind.app.di

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import com.cinemind.app.BuildConfig
import com.cinemind.core.common.CineMindLogger
import com.cinemind.core.common.TimberTree
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

@HiltAndroidApp
class CineMindApplication : Application(), ImageLoaderFactory {

    @javax.inject.Inject lateinit var onDeviceLlm: com.cinemind.feature.ai.llm.ondevice.OnDeviceLlm

    override fun onCreate() {
        super.onCreate()
        // First: record any crash (incl. out-of-memory) so it can be diagnosed on the phone.
        com.cinemind.core.common.Diagnostics.install(this)
        initializeTimber()
        CineMindLogger.i("CineMind Application created")
    }

    /**
     * The app left the screen or Android needs memory: give back the AI model (several GB) unless
     * a job is using it. Android killed CineMind in the background at 5.1 GB on the owner's phone
     * while the model stayed loaded after speech-to-text.
     */
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN || level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            com.cinemind.core.common.Diagnostics.memoryNote(this, "system asked to free memory (level $level)")
            onDeviceLlm.releaseIfIdle()
        }
    }

    /** Single app-wide image loader; VideoFrameDecoder lets Coil render video thumbnails. */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components { add(VideoFrameDecoder.Factory()) }
            .crossfade(true)
            .build()

    private fun initializeTimber() {
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        } else {
            Timber.plant(TimberTree())
        }
    }
}

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

    override fun onCreate() {
        super.onCreate()
        initializeTimber()
        CineMindLogger.i("CineMind Application created")
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

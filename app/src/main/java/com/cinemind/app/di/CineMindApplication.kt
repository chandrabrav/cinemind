package com.cinemind.app.di

import android.app.Application
import com.cinemind.core.common.CineMindLogger
import com.cinemind.core.common.TimberTree
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

@HiltAndroidApp
class CineMindApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        initializeTimber()
        CineMindLogger.i("CineMind Application created")
    }

    private fun initializeTimber() {
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        } else {
            Timber.plant(TimberTree())
        }
    }
}
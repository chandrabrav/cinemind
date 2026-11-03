package com.cinemind.core.common

import android.util.Log
import timber.log.Timber

class TimberTree : Timber.Tree() {
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        // Suppress verbose logs in all builds
        if (priority == Log.VERBOSE) return
        val tagStr = tag ?: "CineMind"
        val msg = if (t != null) "$message\n${Log.getStackTraceString(t)}" else message
        Log.println(priority, tagStr, msg)
    }
}
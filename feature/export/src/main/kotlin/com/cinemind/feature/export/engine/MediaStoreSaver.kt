package com.cinemind.feature.export.engine

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException

/** Publishes a finished MP4 to the shared Movies/CineMind folder (visible in the gallery). No permission needed. */
internal object MediaStoreSaver {

    const val RELATIVE_DIR = "Movies/CineMind"

    fun save(context: Context, file: File, displayName: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/CineMind")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw IOException("Could not create the video in Movies")
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("Could not write the video")
            out.use { stream -> file.inputStream().use { it.copyTo(stream) } }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            // Never leave a half-written, invisible entry behind.
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        return uri
    }
}

package com.cinemind.feature.media.importer

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.cinemind.core.common.CineMindLogger
import com.cinemind.core.domain.model.MediaAsset
import com.cinemind.core.domain.repository.MediaAssetRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class UnsupportedMediaException(message: String) : Exception(message)

/**
 * Turns content URIs from the system Photo Picker into [MediaAsset]s.
 *
 * Originals are never copied or modified: the asset stores the URI, and we request a persistable
 * read grant so the project can still open the file after the app restarts.
 */
class MediaImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: MediaAssetRepository,
) {
    private val resolver: ContentResolver get() = context.contentResolver

    /** Imports [uri], or returns the existing asset if this URI was imported before. */
    suspend fun import(uri: Uri): MediaAsset = withContext(Dispatchers.IO) {
        repository.getAssetByUri(uri.toString())?.let { return@withContext it }

        persistReadAccess(uri)
        val asset = readAsset(uri)
        repository.createAsset(asset)
        CineMindLogger.i("Imported ${asset.mimeType} ${asset.width}x${asset.height} ${asset.durationUs}us")
        asset
    }

    private fun persistReadAccess(uri: Uri) {
        try {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            // Some providers do not offer persistable grants; access then lasts only for this session.
            CineMindLogger.w("No persistable permission for picked media: ${e.message}")
        }
    }

    private fun readAsset(uri: Uri): MediaAsset {
        val mime = resolver.getType(uri) ?: throw UnsupportedMediaException("Unknown file type")
        val (name, size) = queryNameAndSize(uri)
        return when {
            mime.startsWith("video/") -> readVideo(uri, mime, name, size)
            mime.startsWith("image/") -> readImage(uri, mime, name, size)
            else -> throw UnsupportedMediaException("Unsupported type: $mime")
        }
    }

    private fun queryNameAndSize(uri: Uri): Pair<String, Long> {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx).orEmpty() else ""
                    val size = if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx) else 0L
                    return name to size
                }
            }
        return "" to 0L
    }

    private fun readVideo(uri: Uri, mime: String, name: String, size: Long): MediaAsset {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            fun key(k: Int): String? = retriever.extractMetadata(k)

            val durationUs = MediaMetadataParsing.durationMsToUs(key(MediaMetadataRetriever.METADATA_KEY_DURATION))
            if (durationUs <= 0) throw UnsupportedMediaException("Video has no readable duration")

            val oriented = MediaMetadataParsing.orientedSize(
                width = MediaMetadataParsing.parseIntOr(key(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)),
                height = MediaMetadataParsing.parseIntOr(key(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)),
                rotationDegrees = MediaMetadataParsing.parseIntOr(key(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)),
            )
            if (oriented.width <= 0 || oriented.height <= 0) {
                throw UnsupportedMediaException("Video has no readable picture size")
            }

            val frameCount = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull()
            val fps = readContainerFrameRate(uri)
                ?: MediaMetadataParsing.frameRateFrom(frameCount, durationUs)
                    ?.takeIf { MediaMetadataParsing.isPlausibleFrameRate(it) }

            return MediaAsset(
                uri = uri.toString(),
                mimeType = mime,
                width = oriented.width,
                height = oriented.height,
                durationUs = durationUs,
                frameRate = fps ?: DEFAULT_FPS,
                hasAudio = key(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes",
                displayName = name,
                sizeBytes = size,
            )
        } catch (e: UnsupportedMediaException) {
            throw e
        } catch (e: Exception) {
            throw UnsupportedMediaException("Could not read video: ${e.message}")
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun readContainerFrameRate(uri: Uri): Float? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            (0 until extractor.trackCount)
                .map { extractor.getTrackFormat(it) }
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                ?.takeIf { it.containsKey(MediaFormat.KEY_FRAME_RATE) }
                ?.let { format ->
                    // KEY_FRAME_RATE may be stored as an int or a float depending on the muxer.
                    runCatching { format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }
                        .getOrElse { format.getFloat(MediaFormat.KEY_FRAME_RATE) }
                }
                ?.takeIf { MediaMetadataParsing.isPlausibleFrameRate(it) }
        } catch (e: Exception) {
            null
        } finally {
            extractor.release()
        }
    }

    // Limitation: EXIF orientation of photos is not applied yet (portrait photos may report
    // landscape dimensions). Thumbnails still display correctly because Coil honours EXIF.
    private fun readImage(uri: Uri, mime: String, name: String, size: Long): MediaAsset {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // With inJustDecodeBounds, decodeStream returns null by design; only the stream may be missing.
        val stream = resolver.openInputStream(uri) ?: throw UnsupportedMediaException("Could not open image")
        stream.use { BitmapFactory.decodeStream(it, null, options) }
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            throw UnsupportedMediaException("Could not read image size")
        }
        return MediaAsset(
            uri = uri.toString(),
            mimeType = mime,
            width = options.outWidth,
            height = options.outHeight,
            durationUs = 0,
            frameRate = 0f,
            hasAudio = false,
            displayName = name,
            sizeBytes = size,
        )
    }

    private companion object {
        const val DEFAULT_FPS = 30f
    }
}

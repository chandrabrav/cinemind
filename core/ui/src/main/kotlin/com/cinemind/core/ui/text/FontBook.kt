package com.cinemind.core.ui.text

import android.content.res.AssetManager
import android.graphics.Typeface
import com.cinemind.core.domain.captions.CaptionFont
import java.util.concurrent.ConcurrentHashMap

/**
 * One place that turns a [CaptionFont] + weight into a Typeface, for the editor preview, captions
 * and export alike, so text looks identical everywhere. Bundled fonts load from assets/fonts once;
 * [install] is called from Application.onCreate. Without it (or if a file is missing) the font's
 * system family is used, so text always renders.
 */
object FontBook {

    @Volatile private var assets: AssetManager? = null
    private val cache = ConcurrentHashMap<String, Typeface>()

    fun install(assets: AssetManager) {
        this.assets = assets
    }

    fun typeface(font: CaptionFont, weight: Int): Typeface {
        val w = weight.coerceIn(100, 1000)
        val key = if (font.display) font.name else "${font.name}:$w"
        return cache.getOrPut(key) { load(font, w) }
    }

    private fun load(font: CaptionFont, weight: Int): Typeface {
        val bundled = font.asset?.let { file ->
            assets?.let { a -> runCatching { Typeface.createFromAsset(a, "fonts/$file") }.getOrNull() }
        }
        if (bundled != null) return if (font.display) bundled else Typeface.create(bundled, weight, false)
        return Typeface.create(Typeface.create(font.family, Typeface.NORMAL), weight, false)
    }
}

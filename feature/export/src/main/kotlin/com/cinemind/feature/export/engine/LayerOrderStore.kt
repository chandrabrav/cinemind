package com.cinemind.feature.export.engine

import android.content.Context
import com.cinemind.core.domain.text.LayerOrder

/** Per-project layer order (captions above/below the picture-in-picture and the titles). */
object LayerOrderStore {
    private fun prefs(c: Context) = c.getSharedPreferences("layer_order", Context.MODE_PRIVATE)
    fun get(c: Context, projectId: String): LayerOrder = LayerOrder.decode(prefs(c).getString(projectId, null))
    fun set(c: Context, projectId: String, order: LayerOrder) {
        prefs(c).edit().putString(projectId, order.encode()).apply()
    }
}

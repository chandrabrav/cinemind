package com.cinemind.feature.ai.generation.smartedit

import android.content.Context
import com.cinemind.core.domain.aiedit.StyleReference
import com.cinemind.core.domain.aiedit.StyleReferences

/**
 * The sample videos and reference images attached to a project's brief, as their measured style
 * (text only: no copies of the media, no paths). Kept on the phone per project.
 */
object ReferenceStore {
    private fun prefs(c: Context) = c.getSharedPreferences("brief-references", Context.MODE_PRIVATE)

    fun load(c: Context, projectId: String): List<StyleReference> =
        prefs(c).getString(projectId, null).orEmpty().lines().mapNotNull { if (it.isBlank()) null else StyleReference.decode(it) }

    fun save(c: Context, projectId: String, refs: List<StyleReference>) {
        val e = prefs(c).edit()
        if (refs.isEmpty()) e.remove(projectId) else e.putString(projectId, refs.take(StyleReferences.MAX).joinToString("\n") { it.encode() })
        e.apply()
    }
}

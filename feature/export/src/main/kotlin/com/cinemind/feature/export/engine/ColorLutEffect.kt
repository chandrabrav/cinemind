package com.cinemind.feature.export.engine

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.SingleColorLut
import com.cinemind.core.domain.color.CubeLut

/** Turns a baked grade into Media3's GPU LUT effect (same in preview and export). */
@OptIn(UnstableApi::class)
object ColorLutEffect {

    fun create(lut: CubeLut): SingleColorLut = SingleColorLut.createFromCube(toArgbCube(lut))

    /** Media3 wants cube[R][G][B] as ARGB ints (8 bits per channel). */
    fun toArgbCube(lut: CubeLut): Array<Array<IntArray>> {
        val n = lut.size
        return Array(n) { r ->
            Array(n) { g ->
                IntArray(n) { b ->
                    val rr = (lut.at(r, g, b, 0) * 255f + 0.5f).toInt().coerceIn(0, 255)
                    val gg = (lut.at(r, g, b, 1) * 255f + 0.5f).toInt().coerceIn(0, 255)
                    val bb = (lut.at(r, g, b, 2) * 255f + 0.5f).toInt().coerceIn(0, 255)
                    (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
                }
            }
        }
    }
}

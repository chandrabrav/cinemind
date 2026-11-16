package com.cinemind.feature.export.engine

import android.content.Context
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.cinemind.core.domain.editing.ChromaKey
import com.cinemind.core.domain.editing.ClipFraming
import com.cinemind.core.domain.editing.MaskShape

/**
 * Green screen and shape masks on the GPU (Media3 effect; preview and export use the same one).
 * Keyed and masked-out pixels become transparent, so overlays show what is underneath. The key
 * compares colour only (Cb/Cr), so shadows on the green screen are removed too; the formula
 * matches [ChromaKey.keep].
 */
@OptIn(UnstableApi::class)
internal class KeyMaskEffect(private val framing: ClipFraming) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = Program(framing, useHdr)

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = !framing.hasKeyOrMask

    private class Program(private val f: ClipFraming, useHdr: Boolean) : BaseGlShaderProgram(useHdr, 1) {

        private val program: GlProgram = try {
            GlProgram(VERTEX, FRAGMENT)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
        private var aspect = 1f

        init {
            program.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
        }

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            aspect = inputWidth.toFloat() / inputHeight.coerceAtLeast(1)
            return Size(inputWidth, inputHeight)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                program.use()
                program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                val key = f.chroma
                if (key != null) {
                    val (u, v) = ChromaKey.chroma(key.color)
                    val lo = key.strength * 0.3f
                    program.setFloatsUniform("uKeyUv", floatArrayOf(u, v))
                    program.setFloatUniform("uLo", lo)
                    program.setFloatUniform("uHi", lo + key.softness * 0.2f + 1e-4f)
                    program.setFloatUniform("uKeyOn", 1f)
                } else {
                    program.setFloatsUniform("uKeyUv", floatArrayOf(0f, 0f))
                    program.setFloatUniform("uLo", 0f)
                    program.setFloatUniform("uHi", 1f)
                    program.setFloatUniform("uKeyOn", 0f)
                }
                program.setFloatUniform("uShape", f.mask.ordinal.toFloat())
                program.setFloatUniform("uFeather", (f.maskFeather * 2f).coerceAtLeast(0.002f))
                program.setFloatUniform("uAspect", aspect)
                program.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e, presentationTimeUs)
            }
        }

        override fun release() {
            super.release()
            try {
                program.delete()
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e)
            }
        }
    }

    private companion object {
        const val VERTEX = """
attribute vec4 aFramePosition;
varying vec2 vTexSamplingCoord;
void main() {
  gl_Position = aFramePosition;
  vTexSamplingCoord = vec2(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5);
}
"""

        // Shapes: 0 none, 1 circle, 2 rounded, 3 heart, 4 diamond (MaskShape order).
        const val FRAGMENT = """
precision mediump float;
uniform sampler2D uTexSampler;
uniform vec2 uKeyUv;
uniform float uLo;
uniform float uHi;
uniform float uKeyOn;
uniform float uShape;
uniform float uFeather;
uniform float uAspect;
varying vec2 vTexSamplingCoord;

vec2 uv(vec3 c) {
  return vec2(-0.169 * c.r - 0.331 * c.g + 0.5 * c.b + 0.5, 0.5 * c.r - 0.419 * c.g - 0.081 * c.b + 0.5);
}
float dot2(vec2 v) { return dot(v, v); }
float heart(vec2 p) {
  p.x = abs(p.x);
  if (p.y + p.x > 1.0) return sqrt(dot2(p - vec2(0.25, 0.75))) - sqrt(2.0) / 4.0;
  return sqrt(min(dot2(p - vec2(0.0, 1.0)), dot2(p - 0.5 * max(p.x + p.y, 0.0)))) * sign(p.x - p.y);
}
float shapeDist(vec2 p) {
  if (uShape < 0.5) return -1.0;
  if (uShape < 1.5) {
    vec2 s = vec2(max(uAspect, 1.0), max(1.0 / uAspect, 1.0));
    return length(p * s) - 1.0;
  }
  if (uShape < 2.5) {
    float r = 0.3;
    vec2 q = abs(p) - vec2(1.0 - r);
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
  }
  if (uShape < 3.5) {
    vec2 h = vec2(p.x, p.y + 1.0) * 0.55;
    return heart(h) / 0.55;
  }
  return (abs(p.x) + abs(p.y) - 1.0) * 0.7071;
}
void main() {
  vec4 c = texture2D(uTexSampler, vTexSamplingCoord);
  float a = c.a;
  if (uKeyOn > 0.5) {
    float d = distance(uv(c.rgb), uKeyUv);
    float keep = smoothstep(uLo, uHi, d);
    // Spill: pull leftover key colour on the edges toward grey.
    float luma = dot(c.rgb, vec3(0.2126, 0.7152, 0.0722));
    c.rgb = mix(vec3(luma), c.rgb, clamp(keep * 1.5, 0.0, 1.0));
    a *= keep;
  }
  vec2 p = vTexSamplingCoord * 2.0 - 1.0;
  float dist = shapeDist(p);
  a *= 1.0 - smoothstep(-uFeather, 0.0, dist);
  gl_FragColor = vec4(c.rgb, a);
}
"""
    }
}

package com.cinemind.feature.export.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.opengl.GLES20
import android.opengl.GLUtils
import androidx.annotation.OptIn
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.cinemind.core.common.CineMindLogger
import com.cinemind.core.domain.editing.SpeedMath
import com.cinemind.core.domain.editing.SubjectLook
import com.cinemind.core.domain.editing.SubjectMapping
import com.cinemind.core.domain.model.Clip
import com.cinemind.core.domain.text.TextFrame
import com.cinemind.core.domain.text.TextLayer
import com.cinemind.core.domain.text.TextLayerEditing
import com.cinemind.core.ui.text.TextLayerPainter
import java.io.File
import java.nio.ByteBuffer

/**
 * Uses a clip's AI subject cutout ([MatteReader]) on the GPU: a background look ([SubjectLook]) and
 * text drawn *behind* the subject. Runs after the clip is fitted to the frame, so [mapping] takes
 * each frame pixel back to the source picture the cutout was made on. Preview and export share it.
 * If the cutout can't be read the picture passes through unchanged and behind-text is drawn on top.
 */
@OptIn(UnstableApi::class)
internal class SubjectEffect(
    private val clip: Clip,
    private val matte: File,
    private val mapping: SubjectMapping,
    private val look: SubjectLook,
    private val behindTexts: List<TextLayer>,
    /** Captions set to sit behind the person (drawn with the same painter as everywhere else). */
    private val behindCaptions: RenderCaptions? = null,
) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = Program(useHdr)

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = look == SubjectLook.NONE && behindTexts.isEmpty() && behindCaptions == null

    /** Source time of the clip shown at composition time [timelineUs]. */
    private fun sourceUs(timelineUs: Long): Long =
        clip.sourceInUs + SpeedMath.timelineToSource(clip, (timelineUs - clip.timelineInUs).coerceAtLeast(0))

    private inner class Program(useHdr: Boolean) : BaseGlShaderProgram(useHdr, 1) {

        private val program: GlProgram = try {
            GlProgram(VERTEX, FRAGMENT)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
        private val reader: MatteReader? = runCatching { MatteReader(matte) }
            .onFailure { CineMindLogger.w("Cutout unreadable for clip ${clip.id}: ${it.message}") }
            .getOrNull()
        private val maskTex = IntArray(2)
        private val maskIndex = intArrayOf(-1, -1)
        private var textTex = 0
        private var textBitmap: Bitmap? = null
        private var textCanvas: Canvas? = null
        private val painter = TextLayerPainter()
        private var lastFrames: List<TextFrame>? = null
        private val captionPainter = com.cinemind.core.ui.captions.CaptionPainter()
        private var lastCaption: com.cinemind.core.domain.captions.CaptionFrame? = null
        private var width = 1
        private var height = 1

        init {
            program.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
            GLES20.glGenTextures(2, maskTex, 0)
            maskTex.forEach { setupTexture(it) }
            val t = IntArray(1)
            GLES20.glGenTextures(1, t, 0)
            textTex = t[0]
            setupTexture(textTex)
        }

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            width = inputWidth.coerceAtLeast(1)
            height = inputHeight.coerceAtLeast(1)
            return Size(inputWidth, inputHeight)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                val sample = reader?.maskAt(sourceUs(presentationTimeUs))
                if (sample != null) {
                    upload(0, sample.indexA, sample.a)
                    upload(1, sample.indexB, sample.b)
                }
                val hasText = updateText(presentationTimeUs)

                program.use()
                program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                program.setSamplerTexIdUniform("uMaskA", maskTex[0], 1)
                program.setSamplerTexIdUniform("uMaskB", maskTex[1], 2)
                program.setSamplerTexIdUniform("uText", textTex, 3)
                program.setFloatUniform("uMix", sample?.mix ?: 0f)
                program.setFloatUniform("uHasMask", if (sample != null) 1f else 0f)
                program.setFloatUniform("uHasText", if (hasText) 1f else 0f)
                program.setFloatUniform("uLook", look.ordinal.toFloat())
                program.setFloatsUniform("uK", floatArrayOf(mapping.kx, mapping.ky))
                program.setFloatUniform("uTurns", mapping.quarterTurns.toFloat())
                program.setFloatsUniform("uFlip", floatArrayOf(if (mapping.flipX) -1f else 1f, if (mapping.flipY) -1f else 1f))
                program.setFloatsUniform("uCrop", floatArrayOf(mapping.cropLeft, mapping.cropTop, mapping.cropWidth, mapping.cropHeight))
                program.setFloatsUniform("uPx", floatArrayOf(1f / width, 1f / height))
                program.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e, presentationTimeUs)
            }
        }

        private fun upload(slot: Int, index: Int, mask: ByteArray) {
            val r = reader ?: return
            if (maskIndex[slot] == index) return
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTex[slot])
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, r.width, r.height, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, ByteBuffer.wrap(mask))
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 4)
            maskIndex[slot] = index
        }

        /** Draws the behind-subject texts for this instant into a texture; false when none show. */
        private fun updateText(timeUs: Long): Boolean {
            val frames = if (behindTexts.isEmpty()) emptyList() else TextLayerEditing.framesAt(behindTexts, timeUs)
            val caption = behindCaptions?.let { com.cinemind.core.domain.captions.CaptionLayout.frameAt(it.captions, it.style, timeUs) }
            if (frames.isEmpty() && caption == null) return false
            if (frames == lastFrames && caption == lastCaption) return true
            val bmp = textBitmap?.takeIf { it.width == width && it.height == height }
                ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                    textBitmap?.recycle()
                    textBitmap = it
                    textCanvas = Canvas(it)
                }
            bmp.eraseColor(Color.TRANSPARENT)
            painter.drawAll(textCanvas!!, width, height, frames)
            if (caption != null) captionPainter.draw(textCanvas!!, width, height, caption, behindCaptions!!.style)
            lastCaption = caption
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textTex)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
            lastFrames = frames
            return true
        }

        private fun setupTexture(id: Int) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            // A 1×1 placeholder so sampling is defined before the first upload.
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, 1, 1, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, ByteBuffer.wrap(byteArrayOf(0)))
        }

        override fun release() {
            super.release()
            runCatching { reader?.close() }
            textBitmap?.recycle()
            textBitmap = null
            try {
                GLES20.glDeleteTextures(2, maskTex, 0)
                GLES20.glDeleteTextures(1, intArrayOf(textTex), 0)
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

        // Looks: 0 none, 1 cutout, 2 blur, 3 colour pop, 4 spotlight, 5 outline (SubjectLook order).
        // Mapping matches SubjectMapping.toSource (frame and mask coordinates are top-down).
        const val FRAGMENT = """
precision mediump float;
uniform sampler2D uTexSampler;
uniform sampler2D uMaskA;
uniform sampler2D uMaskB;
uniform sampler2D uText;
uniform float uMix;
uniform float uHasMask;
uniform float uHasText;
uniform float uLook;
uniform vec2 uK;
uniform float uTurns;
uniform vec2 uFlip;
uniform vec4 uCrop;
uniform vec2 uPx;
varying vec2 vTexSamplingCoord;

float subjectAt(vec2 o) {
  vec2 p = (o - 0.5) * uK;
  if (abs(p.x) > 0.5 || abs(p.y) > 0.5) return 0.0;
  if (uTurns > 0.5) p = vec2(p.y, -p.x);
  if (uTurns > 1.5) p = vec2(p.y, -p.x);
  if (uTurns > 2.5) p = vec2(p.y, -p.x);
  p *= uFlip;
  vec2 s = uCrop.xy + (p + 0.5) * uCrop.zw;
  return mix(texture2D(uMaskA, s).r, texture2D(uMaskB, s).r, uMix);
}

vec3 blurred(vec2 uv) {
  vec3 sum = vec3(0.0);
  vec2 r = uPx * 14.0;
  sum += texture2D(uTexSampler, uv).rgb;
  sum += texture2D(uTexSampler, uv + vec2( r.x, 0.0)).rgb;
  sum += texture2D(uTexSampler, uv + vec2(-r.x, 0.0)).rgb;
  sum += texture2D(uTexSampler, uv + vec2(0.0,  r.y)).rgb;
  sum += texture2D(uTexSampler, uv + vec2(0.0, -r.y)).rgb;
  sum += texture2D(uTexSampler, uv + r * 0.7).rgb;
  sum += texture2D(uTexSampler, uv - r * 0.7).rgb;
  sum += texture2D(uTexSampler, uv + vec2(r.x, -r.y) * 0.7).rgb;
  sum += texture2D(uTexSampler, uv + vec2(-r.x, r.y) * 0.7).rgb;
  sum += texture2D(uTexSampler, uv + vec2( r.x * 2.0, 0.0)).rgb;
  sum += texture2D(uTexSampler, uv + vec2(-r.x * 2.0, 0.0)).rgb;
  sum += texture2D(uTexSampler, uv + vec2(0.0,  r.y * 2.0)).rgb;
  sum += texture2D(uTexSampler, uv + vec2(0.0, -r.y * 2.0)).rgb;
  return sum / 13.0;
}

void main() {
  vec2 uv = vTexSamplingCoord;
  vec2 o = vec2(uv.x, 1.0 - uv.y);
  vec4 c = texture2D(uTexSampler, uv);
  float m = 0.0;
  if (uHasMask > 0.5) {
    m = smoothstep(0.3, 0.7, subjectAt(o));
    if (uLook > 0.5 && uLook < 1.5) {
      c.a *= m;
    } else if (uLook > 1.5 && uLook < 2.5) {
      c.rgb = mix(blurred(uv), c.rgb, m);
    } else if (uLook > 2.5 && uLook < 3.5) {
      float g = dot(c.rgb, vec3(0.2126, 0.7152, 0.0722));
      c.rgb = mix(vec3(g), c.rgb, m);
    } else if (uLook > 3.5 && uLook < 4.5) {
      c.rgb = mix(c.rgb * 0.28, c.rgb, m);
    } else if (uLook > 4.5) {
      float d = 0.0;
      vec2 r = uPx * 7.0;
      d = max(d, subjectAt(o + vec2( r.x, 0.0)));
      d = max(d, subjectAt(o + vec2(-r.x, 0.0)));
      d = max(d, subjectAt(o + vec2(0.0,  r.y)));
      d = max(d, subjectAt(o + vec2(0.0, -r.y)));
      d = max(d, subjectAt(o + r * 0.7));
      d = max(d, subjectAt(o - r * 0.7));
      d = max(d, subjectAt(o + vec2(r.x, -r.y) * 0.7));
      d = max(d, subjectAt(o + vec2(-r.x, r.y) * 0.7));
      float edge = clamp(smoothstep(0.3, 0.7, d) - m, 0.0, 1.0);
      c.rgb = mix(c.rgb, vec3(1.0), edge);
      c.a = max(c.a, edge);
    }
  }
  if (uHasText > 0.5) {
    vec4 t = texture2D(uText, o);
    float a = t.a * (1.0 - m);
    vec3 rgb = t.a > 0.001 ? t.rgb / t.a : vec3(0.0);
    c.rgb = mix(c.rgb, rgb, a);
    c.a = max(c.a, a);
  }
  gl_FragColor = c;
}
"""
    }
}

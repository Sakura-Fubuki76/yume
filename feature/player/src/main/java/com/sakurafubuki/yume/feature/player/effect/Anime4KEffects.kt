package com.sakurafubuki.yume.feature.player.effect

import android.content.Context
import android.opengl.GLES30
import androidx.annotation.OptIn
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.sakurafubuki.yume.core.common.Logger
import com.sakurafubuki.yume.core.model.Anime4KAutoDownscalePreMode
import com.sakurafubuki.yume.core.model.Anime4KRestoreMode
import com.sakurafubuki.yume.core.model.Anime4KUpscaleMode

@UnstableApi
class Anime4KRestoreEffect(
    private val mode: Anime4KRestoreMode,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        val assetPath = when (mode) {
            Anime4KRestoreMode.M -> "Anime4K_Restore_CNN_M.glsl"
            Anime4KRestoreMode.L -> "Anime4K_Restore_CNN_L.glsl"
            Anime4KRestoreMode.OFF -> error("toGlShaderProgram called for OFF mode")
        }
        return Anime4KCNNShaderProgram(useHdr, context, assetPath)
    }

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = mode == Anime4KRestoreMode.OFF
}

@UnstableApi
class Anime4KClampHighlightsEffect : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = Anime4KClampHighlightsShaderProgram(useHdr)
}

@UnstableApi
private class Anime4KClampHighlightsShaderProgram(
    useHdr: Boolean,
) : BaseGlShaderProgram(useHdr, 1) {
    private var program: GlProgram? = null
    private var passThroughProgram: GlProgram? = null
    private var failed = false

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        passThroughProgram = runCatching { GlProgram(VERTEX_SHADER, PASS_THROUGH_FRAG).also(::setupVertexBuffers) }
            .onFailure { Logger.w(TAG, "Anime4K clamp pass-through compilation failed", it) }
            .getOrNull()
        program = runCatching { GlProgram(VERTEX_SHADER, CLAMP_HIGHLIGHTS_FRAG).also(::setupVertexBuffers) }
            .onFailure {
                Logger.w(TAG, "Anime4K clamp shader compilation failed", it)
                failed = true
            }
            .getOrNull()
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        val outputFbo = currentFramebuffer()
        if (failed) {
            drawPassThrough(inputTexId, outputFbo, passThroughProgram)
            return
        }
        try {
            val viewport = currentViewport()
            val width = viewport[2]
            val height = viewport[3]
            if (width <= 0 || height <= 0) {
                drawPassThrough(inputTexId, outputFbo, passThroughProgram)
                return
            }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, outputFbo)
            GLES30.glViewport(0, 0, width, height)
            program!!.use()
            program!!.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            texelSizeScratch[0] = 1f / width
            texelSizeScratch[1] = 1f / height
            program!!.setFloatsUniform("uTexelSize", texelSizeScratch)
            program!!.bindAttributesAndUniforms()
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            checkGlError("Anime4K clamp")
        } catch (e: Exception) {
            Logger.w(TAG, "Anime4K clamp drawFrame failed", e)
            failed = true
            drawPassThrough(inputTexId, outputFbo, passThroughProgram)
        }
    }

    override fun release() {
        super.release()
        program?.delete()
        passThroughProgram?.delete()
        program = null
        passThroughProgram = null
    }
}

@OptIn(UnstableApi::class)
private fun setupVertexBuffers(program: GlProgram) {
    program.setBufferAttribute("aFramePosition", FRAME_POSITION_DATA, 2)
    program.setBufferAttribute("aTexSamplingCoord", TEX_COORD_DATA, 2)
}

private fun currentFramebuffer(): Int {
    GLES30.glGetIntegerv(GLES30.GL_FRAMEBUFFER_BINDING, framebufferBindingScratch, 0)
    return framebufferBindingScratch[0]
}

private fun currentViewport(): IntArray {
    GLES30.glGetIntegerv(GLES30.GL_VIEWPORT, viewportScratch, 0)
    return viewportScratch
}

@OptIn(UnstableApi::class)
private fun drawPassThrough(inputTexId: Int, outputFbo: Int, program: GlProgram? = null) {
    val passThrough = program ?: return
    val viewport = currentViewport()
    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, outputFbo)
    GLES30.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
    passThrough.use()
    passThrough.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
    passThrough.bindAttributesAndUniforms()
    GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
}

private fun checkGlError(tag: String) {
    val err = GLES30.glGetError()
    if (err != GLES30.GL_NO_ERROR) {
        Logger.w(TAG, "$tag GL error: 0x${Integer.toHexString(err)}")
    }
}

private const val TAG = "Anime4K"
private val framebufferBindingScratch = IntArray(1)
private val viewportScratch = IntArray(4)
private val texelSizeScratch = FloatArray(2)
private val FRAME_POSITION_DATA = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
private val TEX_COORD_DATA = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

private val VERTEX_SHADER = """
    #version 300 es
    in vec4 aFramePosition;
    in vec4 aTexSamplingCoord;
    out vec2 vTexCoord;
    void main() {
        gl_Position = aFramePosition;
        vTexCoord = aTexSamplingCoord.xy;
    }
""".trimIndent()

private val PASS_THROUGH_FRAG = """
    #version 300 es
    precision highp float;
    uniform sampler2D uTexSampler;
    in vec2 vTexCoord;
    out vec4 fragColor;
    void main() {
        fragColor = texture(uTexSampler, vTexCoord);
    }
""".trimIndent()

private val CLAMP_HIGHLIGHTS_FRAG = """
    #version 300 es
    precision highp float;
    uniform sampler2D uTexSampler;
    uniform vec2 uTexelSize;
    in vec2 vTexCoord;
    out vec4 fragColor;

    float luma(vec3 c) {
        return dot(c, vec3(0.299, 0.587, 0.114));
    }

    void main() {
        vec4 c = texture(uTexSampler, vTexCoord);
        float current = luma(c.rgb);
        float neighborMax = 0.0;
        for (int x = -2; x <= 2; x++) {
            for (int y = -2; y <= 2; y++) {
                if (x != 0 || y != 0) {
                    neighborMax = max(neighborMax, luma(texture(uTexSampler, vTexCoord + vec2(float(x), float(y)) * uTexelSize).rgb));
                }
            }
        }
        float target = min(current, neighborMax + 0.015);
        fragColor = vec4(clamp(c.rgb - (current - target), 0.0, 1.0), c.a);
    }
""".trimIndent()

@UnstableApi
class Anime4KUpscaleEffect(
    private val upscaleMode: Anime4KUpscaleMode,
    private val downscalePreMode: Anime4KAutoDownscalePreMode = Anime4KAutoDownscalePreMode.OFF,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        val assetPath = when (upscaleMode) {
            Anime4KUpscaleMode.CNN_X2_M -> "Anime4K_Upscale_CNN_x2_M.glsl"
            Anime4KUpscaleMode.CNN_X2_L -> "Anime4K_Upscale_CNN_x2_L.glsl"
            Anime4KUpscaleMode.GAN_X2_M -> "Anime4K_Upscale_GAN_x2_M.glsl"
            Anime4KUpscaleMode.OFF -> error("toGlShaderProgram called for OFF mode")
        }
        val dsFactor = when (downscalePreMode) {
            Anime4KAutoDownscalePreMode.X4 -> 0.5f
            Anime4KAutoDownscalePreMode.X2 -> 1.0f
            Anime4KAutoDownscalePreMode.OFF -> 1.0f
        }
        return Anime4KCNNShaderProgram(useHdr, context, assetPath, dsFactor)
    }

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = upscaleMode == Anime4KUpscaleMode.OFF
}

@UnstableApi
class Anime4KAutoDownscalePreEffect(
    private val mode: Anime4KAutoDownscalePreMode,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = Anime4KAutoDownscalePreShaderProgram(useHdr, mode)

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = mode == Anime4KAutoDownscalePreMode.OFF
}

@UnstableApi
private class Anime4KAutoDownscalePreShaderProgram(
    useHdr: Boolean,
    private val mode: Anime4KAutoDownscalePreMode,
) : BaseGlShaderProgram(useHdr, 1) {
    private var program: GlProgram? = null
    private var passThroughProgram: GlProgram? = null
    private var failed = false
    private var downscaleW = 0
    private var downscaleH = 0

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        passThroughProgram = runCatching { GlProgram(VERTEX_SHADER, PASS_THROUGH_FRAG).also(::setupVertexBuffers) }
            .onFailure { Logger.w(TAG, "AutoDownscalePre pass-through compilation failed", it) }
            .getOrNull()
        program = runCatching { GlProgram(VERTEX_SHADER, PASS_THROUGH_FRAG).also(::setupVertexBuffers) }
            .onFailure {
                Logger.w(TAG, "AutoDownscalePre shader compilation failed", it)
                failed = true
            }
            .getOrNull()
        downscaleW = when (mode) {
            Anime4KAutoDownscalePreMode.X4 -> inputWidth / 2
            else -> inputWidth
        }
        downscaleH = when (mode) {
            Anime4KAutoDownscalePreMode.X4 -> inputHeight / 2
            else -> inputHeight
        }
        Logger.i(TAG, "AutoDownscalePre mode=$mode input=${inputWidth}x$inputHeight → output=${downscaleW}x$downscaleH")
        return Size(downscaleW, downscaleH)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        val outputFbo = currentFramebuffer()
        if (failed) {
            drawPassThrough(inputTexId, outputFbo, passThroughProgram)
            return
        }
        try {
            val viewport = currentViewport()
            val width = viewport[2]
            val height = viewport[3]
            if (width <= 0 || height <= 0) {
                drawPassThrough(inputTexId, outputFbo, passThroughProgram)
                return
            }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, outputFbo)
            GLES30.glViewport(0, 0, width, height)
            program!!.use()
            program!!.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            program!!.bindAttributesAndUniforms()
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        } catch (e: Exception) {
            Logger.w(TAG, "AutoDownscalePre drawFrame failed", e)
            failed = true
            drawPassThrough(inputTexId, outputFbo, passThroughProgram)
        }
    }

    override fun release() {
        super.release()
        program?.delete()
        passThroughProgram?.delete()
        program = null
        passThroughProgram = null
    }
}

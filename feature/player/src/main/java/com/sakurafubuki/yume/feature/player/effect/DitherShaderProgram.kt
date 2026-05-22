package com.sakurafubuki.yume.feature.player.effect

import android.opengl.GLES30
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import com.sakurafubuki.yume.core.common.Logger

@UnstableApi
class DitherShaderProgram(
    useHdr: Boolean,
    private val ditherBitDepth: Int = 8,
) : BaseGlShaderProgram(useHdr, 1) {

    private var program: GlProgram? = null
    private var passThroughProgram: GlProgram? = null
    private var shaderInitFailed = false
    private val framebufferBinding = IntArray(1)
    private val viewport = IntArray(4)
    private val resolution = FloatArray(2)

    private val ditherStrength = 1.0f / ((1 shl ditherBitDepth) - 1).toFloat()

    private val isPassthrough: Boolean = useHdr

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        passThroughProgram = runCatching {
            GlProgram(VERTEX_SHADER, PASS_THROUGH_FRAG).also(::setupVertexBuffers)
        }.onFailure {
            Logger.w(TAG, "Dither pass-through shader compilation failed", it)
        }.getOrNull()

        program = runCatching {
            GlProgram(VERTEX_SHADER, FRAG_SHADER).also(::setupVertexBuffers)
        }.onFailure {
            Logger.w(TAG, "Dither shader compilation failed, falling back to pass-through", it)
            shaderInitFailed = true
        }.getOrNull()
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        val outputFbo = currentFramebuffer()
        if (shaderInitFailed || isPassthrough) {
            drawPassThrough(inputTexId, outputFbo)
            return
        }

        try {
            val viewport = currentViewport()
            val width = viewport[2]
            val height = viewport[3]
            if (width <= 0 || height <= 0) {
                drawPassThrough(inputTexId, outputFbo)
                return
            }

            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, outputFbo)
            GLES30.glViewport(0, 0, width, height)
            program!!.use()
            program!!.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            program!!.setFloatUniform("uDitherStrength", ditherStrength)
            resolution[0] = width.toFloat()
            resolution[1] = height.toFloat()
            program!!.setFloatsUniform("uResolution", resolution)
            program!!.bindAttributesAndUniforms()
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            checkGlError("Dither")
        } catch (e: Exception) {
            Logger.w(TAG, "Dither drawFrame failed, disabling effect", e)
            shaderInitFailed = true
            drawPassThrough(inputTexId, outputFbo)
        }
    }

    override fun release() {
        super.release()
        program?.delete()
        passThroughProgram?.delete()
        program = null
        passThroughProgram = null
    }

    private fun setupVertexBuffers(glProgram: GlProgram) {
        glProgram.setBufferAttribute("aFramePosition", FRAME_POSITION_DATA, 2)
        glProgram.setBufferAttribute("aTexSamplingCoord", TEX_COORD_DATA, 2)
    }

    private fun drawPassThrough(inputTexId: Int, outputFbo: Int) {
        val passThrough = passThroughProgram ?: return
        val viewport = currentViewport()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, outputFbo)
        GLES30.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
        passThrough.use()
        passThrough.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
        passThrough.bindAttributesAndUniforms()
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun currentFramebuffer(): Int {
        GLES30.glGetIntegerv(GLES30.GL_FRAMEBUFFER_BINDING, framebufferBinding, 0)
        return framebufferBinding[0]
    }

    private fun currentViewport(): IntArray {
        GLES30.glGetIntegerv(GLES30.GL_VIEWPORT, viewport, 0)
        return viewport
    }

    private fun checkGlError(tag: String) {
        val err = GLES30.glGetError()
        if (err != GLES30.GL_NO_ERROR) {
            Logger.w(TAG, "$tag GL error: 0x${Integer.toHexString(err)}")
        }
    }

    private companion object {
        private const val TAG = "VideoDither"
        val FRAME_POSITION_DATA = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val TEX_COORD_DATA = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

        val VERTEX_SHADER = """
            #version 300 es
            in vec4 aFramePosition;
            in vec4 aTexSamplingCoord;
            out vec2 vTexCoord;
            void main() {
                gl_Position = aFramePosition;
                vTexCoord = aTexSamplingCoord.xy;
            }
        """.trimIndent()

        val PASS_THROUGH_FRAG = """
            #version 300 es
            precision mediump float;
            uniform sampler2D uTexSampler;
            in vec2 vTexCoord;
            out vec4 fragColor;
            void main() {
                fragColor = texture(uTexSampler, vTexCoord);
            }
        """.trimIndent()

        val FRAG_SHADER = """
            #version 300 es
            precision highp float;
            uniform sampler2D uTexSampler;
            uniform float uDitherStrength;
            uniform vec2 uResolution;
            in vec2 vTexCoord;
            out vec4 fragColor;

            float bayer(vec2 coord) {
                int x = int(mod(coord.x, 4.0));
                int y = int(mod(coord.y, 4.0));

                if (y == 0) {
                    if (x == 0) return 0.0 / 16.0 - 0.5;
                    if (x == 1) return 8.0 / 16.0 - 0.5;
                    if (x == 2) return 2.0 / 16.0 - 0.5;
                    return 10.0 / 16.0 - 0.5;
                } else if (y == 1) {
                    if (x == 0) return 12.0 / 16.0 - 0.5;
                    if (x == 1) return 4.0 / 16.0 - 0.5;
                    if (x == 2) return 14.0 / 16.0 - 0.5;
                    return 6.0 / 16.0 - 0.5;
                } else if (y == 2) {
                    if (x == 0) return 3.0 / 16.0 - 0.5;
                    if (x == 1) return 11.0 / 16.0 - 0.5;
                    if (x == 2) return 1.0 / 16.0 - 0.5;
                    return 9.0 / 16.0 - 0.5;
                } else {
                    if (x == 0) return 15.0 / 16.0 - 0.5;
                    if (x == 1) return 7.0 / 16.0 - 0.5;
                    if (x == 2) return 13.0 / 16.0 - 0.5;
                    return 5.0 / 16.0 - 0.5;
                }
            }

            void main() {
                vec4 color = texture(uTexSampler, vTexCoord);
                float dither = bayer(vTexCoord * uResolution) * uDitherStrength;
                fragColor = vec4(clamp(color.rgb + dither, 0.0, 1.0), color.a);
            }
        """.trimIndent()
    }
}

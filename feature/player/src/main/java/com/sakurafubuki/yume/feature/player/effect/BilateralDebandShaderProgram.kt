package com.sakurafubuki.yume.feature.player.effect

import android.opengl.GLES30
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import com.sakurafubuki.yume.core.common.Logger

@UnstableApi
class BilateralDebandShaderProgram(
    useHdr: Boolean,
    private val threshold: Float = 0.008f,
    private val strength: Float = 0.004f,
    private val radius: Int = 4,
) : BaseGlShaderProgram(useHdr, 2) {

    private var blurProgram: GlProgram? = null
    private var debandProgram: GlProgram? = null
    private var passThroughProgram: GlProgram? = null
    private var intermediateFboId = 0
    private var intermediateTextureId = 0
    private var textureWidth = 0
    private var textureHeight = 0
    private var shaderInitFailed = false
    private val framebufferBinding = IntArray(1)
    private val viewport = IntArray(4)
    private val texelSize = FloatArray(2)
    private val textureScratch = IntArray(1)
    private val framebufferScratch = IntArray(1)

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        passThroughProgram = runCatching {
            GlProgram(VERTEX_SHADER, PASS_THROUGH_FRAG).also(::setupVertexBuffers)
        }.onFailure {
            Logger.w(TAG, "Deband pass-through shader compilation failed", it)
        }.getOrNull()

        try {
            blurProgram = GlProgram(VERTEX_SHADER, buildBilateralFragShader()).also(::setupVertexBuffers)
            debandProgram = GlProgram(VERTEX_SHADER, DEBAND_FRAG_SHADER).also(::setupVertexBuffers)
        } catch (e: Exception) {
            Logger.w(TAG, "Deband shader compilation failed, falling back to pass-through", e)
            shaderInitFailed = true
        }
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        val outputFbo = currentFramebuffer()
        if (shaderInitFailed) {
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

            ensureIntermediateTexture(width, height)
            if (shaderInitFailed) {
                drawPassThrough(inputTexId, outputFbo)
                return
            }

            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, intermediateFboId)
            GLES30.glViewport(0, 0, width, height)
            blurProgram!!.use()
            blurProgram!!.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            texelSize[0] = 1f / width
            texelSize[1] = 1f / height
            blurProgram!!.setFloatsUniform("uTexelSize", texelSize)
            blurProgram!!.setFloatUniform("uSigmaSpace", radius / 3f)
            blurProgram!!.setFloatUniform("uSigmaColor", threshold * 2f)
            blurProgram!!.bindAttributesAndUniforms()
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            checkGlError("Deband pass 1")

            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, outputFbo)
            GLES30.glViewport(0, 0, width, height)
            debandProgram!!.use()
            debandProgram!!.setSamplerTexIdUniform("uOrigSampler", inputTexId, 0)
            debandProgram!!.setSamplerTexIdUniform("uBlurSampler", intermediateTextureId, 1)
            debandProgram!!.setFloatUniform("uThreshold", threshold)
            debandProgram!!.setFloatUniform("uStrength", strength)
            debandProgram!!.bindAttributesAndUniforms()
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            checkGlError("Deband pass 2")
        } catch (e: Exception) {
            Logger.w(TAG, "Deband drawFrame failed, disabling effect", e)
            shaderInitFailed = true
            drawPassThrough(inputTexId, outputFbo)
        }
    }

    override fun release() {
        super.release()
        blurProgram?.delete()
        debandProgram?.delete()
        passThroughProgram?.delete()
        blurProgram = null
        debandProgram = null
        passThroughProgram = null
        if (intermediateFboId != 0) {
            framebufferScratch[0] = intermediateFboId
            textureScratch[0] = intermediateTextureId
            GLES30.glDeleteFramebuffers(1, framebufferScratch, 0)
            GLES30.glDeleteTextures(1, textureScratch, 0)
            intermediateFboId = 0
            intermediateTextureId = 0
        }
    }

    private fun setupVertexBuffers(program: GlProgram) {
        program.setBufferAttribute("aFramePosition", FRAME_POSITION_DATA, 2)
        program.setBufferAttribute("aTexSamplingCoord", TEX_COORD_DATA, 2)
    }

    private fun drawPassThrough(inputTexId: Int, outputFbo: Int) {
        val program = passThroughProgram ?: return
        val viewport = currentViewport()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, outputFbo)
        GLES30.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
        program.use()
        program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
        program.bindAttributesAndUniforms()
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun ensureIntermediateTexture(width: Int, height: Int) {
        if (textureWidth == width && textureHeight == height) return
        if (intermediateFboId != 0) {
            framebufferScratch[0] = intermediateFboId
            textureScratch[0] = intermediateTextureId
            GLES30.glDeleteFramebuffers(1, framebufferScratch, 0)
            GLES30.glDeleteTextures(1, textureScratch, 0)
        }

        val texIds = IntArray(1)
        GLES30.glGenTextures(1, texIds, 0)
        intermediateTextureId = texIds[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, intermediateTextureId)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, width, height, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null,
        )
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        val fboIds = IntArray(1)
        GLES30.glGenFramebuffers(1, fboIds, 0)
        intermediateFboId = fboIds[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, intermediateFboId)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER,
            GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D,
            intermediateTextureId,
            0,
        )
        val fboStatus = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        if (fboStatus != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            Logger.w(TAG, "Intermediate FBO incomplete: 0x${Integer.toHexString(fboStatus)}")
            framebufferScratch[0] = intermediateFboId
            textureScratch[0] = intermediateTextureId
            GLES30.glDeleteFramebuffers(1, framebufferScratch, 0)
            GLES30.glDeleteTextures(1, textureScratch, 0)
            intermediateFboId = 0
            intermediateTextureId = 0
            shaderInitFailed = true
            return
        }
        textureWidth = width
        textureHeight = height
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

    private fun buildBilateralFragShader(): String = """
        #version 300 es
        precision highp float;
        uniform sampler2D uTexSampler;
        uniform vec2 uTexelSize;
        uniform float uSigmaSpace;
        uniform float uSigmaColor;
        in vec2 vTexCoord;
        out vec4 fragColor;

        float gaussSpace(float dist) {
            return exp(-dist * dist / (2.0 * uSigmaSpace * uSigmaSpace));
        }
        float gaussColor(float diff) {
            return exp(-diff * diff / (2.0 * uSigmaColor * uSigmaColor));
        }

        void main() {
            vec4 center = texture(uTexSampler, vTexCoord);
            vec3 weightedSum = vec3(0.0);
            float totalWeight = 0.0;
            for (int x = -$radius; x <= $radius; x++) {
                for (int y = -$radius; y <= $radius; y++) {
                    vec2 offset = vec2(float(x), float(y)) * uTexelSize;
                    vec4 sampleColor = texture(uTexSampler, vTexCoord + offset);
                    float spaceDist = length(vec2(float(x), float(y)));
                    float colorDiff = length(sampleColor.rgb - center.rgb);
                    float weight = gaussSpace(spaceDist) * gaussColor(colorDiff);
                    weightedSum += sampleColor.rgb * weight;
                    totalWeight += weight;
                }
            }
            fragColor = vec4(weightedSum / max(totalWeight, 0.00001), center.a);
        }
    """.trimIndent()

    private companion object {
        private const val TAG = "BilateralDeband"
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

        val DEBAND_FRAG_SHADER = """
            #version 300 es
            precision highp float;
            uniform sampler2D uOrigSampler;
            uniform sampler2D uBlurSampler;
            uniform float uThreshold;
            uniform float uStrength;
            in vec2 vTexCoord;
            out vec4 fragColor;

            void main() {
                vec4 original = texture(uOrigSampler, vTexCoord);
                vec4 blurred = texture(uBlurSampler, vTexCoord);
                float diff = length(original.rgb - blurred.rgb);
                float smooth = 1.0 - smoothstep(uThreshold * 0.5, uThreshold, diff);
                vec3 debanded = mix(original.rgb, blurred.rgb, smooth * clamp(uStrength * 256.0, 0.0, 1.0));
                fragColor = vec4(debanded, original.a);
            }
        """.trimIndent()
    }
}

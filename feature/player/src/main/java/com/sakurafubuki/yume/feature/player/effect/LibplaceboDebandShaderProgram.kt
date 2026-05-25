package com.sakurafubuki.yume.feature.player.effect

import android.opengl.GLES30
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import com.sakurafubuki.yume.core.common.Logger

@UnstableApi
class LibplaceboDebandShaderProgram(
    private val useHdr: Boolean,
    private val maxLuma: Float? = null,
    private val iterations: Int = 1,
    private val threshold: Float = 3.0f,
    private val radius: Float = 16.0f,
    private val grain: Float = 4.0f,
) : BaseGlShaderProgram(useHdr, 1) {

    private var program: GlProgram? = null
    private var passThroughProgram: GlProgram? = null
    private var shaderInitFailed = false
    private val framebufferBinding = IntArray(1)
    private val viewport = IntArray(4)
    private val texelSize = FloatArray(2)
    private val grainNeutral = floatArrayOf(0f, 0f, 0f)

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        passThroughProgram = runCatching {
            GlProgram(VERTEX_SHADER, PASS_THROUGH_FRAG).also(::setupVertexBuffers)
        }.onFailure {
            Logger.w(TAG, "Deband pass-through shader compilation failed", it)
        }.getOrNull()

        program = runCatching {
            GlProgram(VERTEX_SHADER, buildDebandFragmentShader()).also(::setupVertexBuffers)
        }.onFailure {
            Logger.w(TAG, "Deband shader compilation failed, falling back to pass-through", it)
            shaderInitFailed = true
        }.getOrNull()
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

            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, outputFbo)
            GLES30.glViewport(0, 0, width, height)
            program!!.use()
            program!!.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            texelSize[0] = 1f / width
            texelSize[1] = 1f / height
            program!!.setFloatsUniform("uTexelSize", texelSize)
            program!!.setFloatUniform("uThreshold", threshold / 1000f)
            program!!.setFloatUniform("uRadius", radius)
            program!!.setFloatUniform("uGrain", effectiveGrain() / 1000f)
            program!!.setFloatsUniform("uGrainNeutral", grainNeutral)
            program!!.setFloatUniform("uFrameSeed", frameSeed(presentationTimeUs))
            program!!.bindAttributesAndUniforms()
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            checkGlError("Deband")
        } catch (e: Exception) {
            Logger.w(TAG, "Deband drawFrame failed, disabling effect", e)
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

    private fun effectiveGrain(): Float {
        if (!useHdr) return grain
        if (maxLuma == null || maxLuma <= 0f) return grain * FALLBACK_HDR_GRAIN_SCALE
        return grain * (SDR_WHITE / maxLuma)
    }

    private fun frameSeed(presentationTimeUs: Long): Float {
        if (presentationTimeUs <= 0L) return 0f
        return ((presentationTimeUs / APPROX_FRAME_DURATION_US) % FRAME_SEED_PERIOD).toFloat()
    }

    private fun setupVertexBuffers(program: GlProgram) {
        program.setBufferAttribute("aFramePosition", FRAME_POSITION_DATA, 2)
        program.setBufferAttribute("aTexSamplingCoord", TEX_COORD_DATA, 2)
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

    private fun buildDebandFragmentShader(): String = DEBAND_FRAG_SHADER
        .replace("%ITERATIONS%", iterations.coerceIn(0, MAX_ITERATIONS).toString())

    private companion object {
        private const val TAG = "LibplaceboDeband"
        private const val MAX_ITERATIONS = 16
        private const val SDR_WHITE = 100f
        private const val FALLBACK_HDR_GRAIN_SCALE = 0.25f
        private const val APPROX_FRAME_DURATION_US = 16_666L
        private const val FRAME_SEED_PERIOD = 65_536L
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
            precision highp float;
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
            uniform sampler2D uTexSampler;
            uniform vec2 uTexelSize;
            uniform float uThreshold;
            uniform float uRadius;
            uniform float uGrain;
            uniform vec3 uGrainNeutral;
            uniform float uFrameSeed;
            in vec2 vTexCoord;
            out vec4 fragColor;

            vec3 rand3(inout vec3 state) {
                state = fract(state * vec3(443.8975, 397.2973, 491.1871));
                state += dot(state, state.yzx + 19.19);
                return fract((state.xxy + state.yzz) * state.zyx);
            }

            void main() {
                vec4 color = textureLod(uTexSampler, vTexCoord, 0.0);
                vec3 res = color.rgb;
                vec3 state = vec3(gl_FragCoord.xy, uFrameSeed);

                for (int i = 1; i <= %ITERATIONS%; i++) {
                    vec3 random = rand3(state);
                    vec2 d = random.xy * vec2(float(i) * uRadius, 6.28318530718);
                    d = d.x * vec2(cos(d.y), sin(d.y));

                    vec3 avg = vec3(0.0);
                    avg += textureLod(uTexSampler, vTexCoord + uTexelSize * vec2(+d.x, +d.y), 0.0).rgb;
                    avg += textureLod(uTexSampler, vTexCoord + uTexelSize * vec2(-d.x, +d.y), 0.0).rgb;
                    avg += textureLod(uTexSampler, vTexCoord + uTexelSize * vec2(-d.x, -d.y), 0.0).rgb;
                    avg += textureLod(uTexSampler, vTexCoord + uTexelSize * vec2(+d.x, -d.y), 0.0).rgb;
                    avg *= 0.25;

                    vec3 diff = abs(res - avg);
                    vec3 bound = vec3(uThreshold / float(i));
                    res = mix(avg, res, step(bound, diff));
                }

                if (uGrain > 0.0) {
                    vec3 strength = min(abs(res - uGrainNeutral), vec3(uGrain));
                    res += strength * (rand3(state) - vec3(0.5));
                }

                fragColor = vec4(res, color.a);
            }
        """.trimIndent()
    }
}

package com.sakurafubuki.yume.feature.player.effect

import android.content.Context
import android.opengl.GLES30
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import com.sakurafubuki.yume.core.common.Logger
import java.io.BufferedReader
import java.io.InputStreamReader

@UnstableApi
class Anime4KCNNShaderProgram(
    useHdr: Boolean,
    private val context: Context,
    private val assetPath: String,
) : BaseGlShaderProgram(useHdr, 1) {

    private var layers: List<CompiledLayer> = emptyList()
    private var passThroughProgram: GlProgram? = null
    private var shaderInitFailed = false
    private var width = 0
    private var height = 0

    private data class FboTex(var fbo: Int = 0, var tex: Int = 0)

    private val intermediates = mutableMapOf<String, FboTex>()

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        width = inputWidth
        height = inputHeight

        passThroughProgram = runCatching {
            GlProgram(VERTEX_SHADER, PASS_THROUGH_FRAG).also(::setupVertexBuffers)
        }.onFailure {
            Logger.w(TAG, "CNN pass-through shader compilation failed", it)
        }.getOrNull()

        try {
            val source = readAsset(assetPath)
            val parsed = parseCnnSource(source)
            layers = parsed.map { compileLayer(it) }
            Logger.i(TAG, "CNN compiled ${layers.size} layers from $assetPath")
        } catch (e: Exception) {
            Logger.w(TAG, "CNN init failed for $assetPath, using pass-through", e)
            shaderInitFailed = true
            layers = emptyList()
        }
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        val outputFbo = currentFramebuffer()
        if (shaderInitFailed || layers.isEmpty()) {
            drawPassThrough(inputTexId, outputFbo)
            return
        }
        try {
            val viewport = currentViewport()
            val w = viewport[2]
            val h = viewport[3]
            if (w <= 0 || h <= 0) {
                drawPassThrough(inputTexId, outputFbo)
                return
            }

            if (w != width || h != height) {
                releaseIntermediates()
                width = w
                height = h
            }

            val textureRegistry = mutableMapOf("MAIN" to inputTexId)

            for ((index, layer) in layers.withIndex()) {
                val isLast = index == layers.lastIndex
                val targetFbo = if (isLast) outputFbo else ensureIntermediate(layer.outputName, w, h)

                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, targetFbo)
                GLES30.glViewport(0, 0, w, h)
                layer.program.use()

                for ((unit, texName) in layer.inputNames.withIndex()) {
                    val texId = textureRegistry[texName]
                        ?: intermediates[texName]?.tex
                        ?: inputTexId
                    layer.program.setSamplerTexIdUniform("uInput$unit", texId, unit)
                }
                if (layer.needsTexelSize) {
                    layer.program.setFloatsUniform("uTexelSize", floatArrayOf(1f / w, 1f / h))
                }
                layer.program.bindAttributesAndUniforms()
                GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
                checkGlError("CNN layer ${index + 1}")

                if (!isLast) {
                    val fboTex = intermediates[layer.outputName]!!
                    textureRegistry[layer.outputName] = fboTex.tex
                }

                if (!isLast) {
                    val neededLater = layers.drop(index + 1).flatMap { it.inputNames }.toSet()
                    val toRemove = intermediates.keys.filter { it !in neededLater && it != layer.outputName }
                    for (name in toRemove) {
                        intermediates[name]?.let { ft ->
                            GLES30.glDeleteFramebuffers(1, intArrayOf(ft.fbo), 0)
                            GLES30.glDeleteTextures(1, intArrayOf(ft.tex), 0)
                        }
                        intermediates.remove(name)
                    }
                }
            }
        } catch (e: Exception) {
            Logger.w(TAG, "CNN drawFrame failed, disabling", e)
            shaderInitFailed = true
            drawPassThrough(inputTexId, outputFbo)
        }
    }

    override fun release() {
        super.release()
        layers.forEach { it.program.delete() }
        layers = emptyList()
        passThroughProgram?.delete()
        passThroughProgram = null
        releaseIntermediates()
    }

    private fun releaseIntermediates() {
        for ((_, ft) in intermediates) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(ft.fbo), 0)
            GLES30.glDeleteTextures(1, intArrayOf(ft.tex), 0)
        }
        intermediates.clear()
    }

    private fun ensureIntermediate(name: String, w: Int, h: Int): Int {
        intermediates[name]?.let { return it.fbo }

        val result = tryCreateFboTex(w, h, useHalfFloat = true)
        if (result != null) {
            intermediates[name] = result
            return result.fbo
        }
        Logger.w(TAG, "RGBA16F FBO failed, falling back to RGBA8 for $name")

        val fallbackResult = tryCreateFboTex(w, h, useHalfFloat = false)
        if (fallbackResult != null) {
            intermediates[name] = fallbackResult
            return fallbackResult.fbo
        }
        Logger.e(TAG, "Failed to create intermediate FBO for $name")
        shaderInitFailed = true
        return 0
    }

    private fun tryCreateFboTex(w: Int, h: Int, useHalfFloat: Boolean): FboTex? {
        val texIds = IntArray(1)
        GLES30.glGenTextures(1, texIds, 0)
        val tex = texIds[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)

        if (useHalfFloat) {
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, 0x881A, w, h, 0,
                GLES30.GL_RGBA, 0x140B, null,
            )
        } else {
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, w, h, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null,
            )
        }
        if (GLES30.glGetError() != GLES30.GL_NO_ERROR) {
            GLES30.glDeleteTextures(1, intArrayOf(tex), 0)
            return null
        }

        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        val fboIds = IntArray(1)
        GLES30.glGenFramebuffers(1, fboIds, 0)
        val fbo = fboIds[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER,
            GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D,
            tex,
            0,
        )
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
            GLES30.glDeleteTextures(1, intArrayOf(tex), 0)
            return null
        }
        return FboTex(fbo, tex)
    }

    private data class ParsedLayer(
        val outputName: String,
        val inputNames: List<String>,
        val macros: List<String>,
        val body: String,
        val returnExpr: String,
        val isFinal: Boolean,
    )

    private fun parseCnnSource(source: String): List<ParsedLayer> {
        val sections = source.split(Regex("""//!DESC\s""")).drop(1)
        return sections.map { section ->
            val lines = section.lines()

            val bindNames = lines
                .filter { it.trimStart().startsWith("//!BIND ") }
                .map { it.trimStart().removePrefix("//!BIND ").trim() }

            val saveName = lines
                .firstOrNull { it.trimStart().startsWith("//!SAVE ") }
                ?.trimStart()?.removePrefix("//!SAVE ")?.trim() ?: "MAIN"

            val isFinal = saveName == "MAIN"

            val macroLines = lines
                .filter { it.trimStart().startsWith("#define ") }
                .map { it.trim() }

            val hookStart = lines.indexOfFirst { it.contains("vec4 hook()") }
            val bodyLines = mutableListOf<String>()
            var braceDepth = 0
            var inBody = false
            for (i in hookStart until lines.size) {
                val line = lines[i]
                if (line.contains("{")) {
                    braceDepth += line.count { it == '{' }
                    inBody = true
                    if (line.contains("vec4 hook()")) continue
                }
                if (!inBody) continue
                if (line.contains("}")) {
                    braceDepth -= line.count { it == '}' }
                    if (braceDepth <= 0) break
                }
                bodyLines.add(line)
            }
            val body = bodyLines.joinToString("\n").trim()

            val returnLine = bodyLines.lastOrNull { it.trimStart().startsWith("return ") }
            val returnExpr = returnLine
                ?.trimStart()
                ?.removePrefix("return ")
                ?.removeSuffix(";")
                ?.trim()
                ?: "result"

            ParsedLayer(
                outputName = saveName,
                inputNames = bindNames,
                macros = macroLines,
                body = body.removeSuffix(returnLine ?: "").trimEnd(),
                returnExpr = returnExpr,
                isFinal = isFinal,
            )
        }
    }

    private data class CompiledLayer(
        val program: GlProgram,
        val outputName: String,
        val inputNames: List<String>,
        val needsTexelSize: Boolean,
    )

    private fun compileLayer(parsed: ParsedLayer): CompiledLayer {
        val needsTexelSize = parsed.macros.any { it.contains("x_off") }
        val fragShader = buildFragmentShader(parsed)
        val program = GlProgram(VERTEX_SHADER, fragShader)
        setupVertexBuffers(program)
        return CompiledLayer(program, parsed.outputName, parsed.inputNames, needsTexelSize)
    }

    private fun buildFragmentShader(layer: ParsedLayer): String {
        val sb = StringBuilder()
        sb.appendLine("#version 300 es")
        sb.appendLine("precision highp float;")

        for ((i, name) in layer.inputNames.withIndex()) {
            sb.appendLine("uniform sampler2D uInput$i;")
        }
        if (layer.macros.any { it.contains("x_off") }) {
            sb.appendLine("uniform vec2 uTexelSize;")
        }
        sb.appendLine("in vec2 vTexCoord;")
        sb.appendLine("out vec4 fragColor;")

        for (macro in layer.macros) {
            val transformed = transformMacro(macro, layer.inputNames)
            sb.appendLine(transformed)
        }

        sb.appendLine("void main() {")
        sb.appendLine(layer.body)

        var returnExpr = layer.returnExpr

        returnExpr = returnExpr.replace(
            Regex("""MAIN_tex\s*\(\s*MAIN_pos\s*\)"""),
            "texture(uInput0, vTexCoord)",
        )
        sb.appendLine("    fragColor = $returnExpr;")
        sb.appendLine("}")

        return sb.toString()
    }

    private fun transformMacro(macro: String, inputNames: List<String>): String {
        var result = macro

        result = result.replace(Regex("""(\w+)_texOff\s*\(\s*vec2\s*\(\s*x_off\s*,\s*y_off\s*\)\s*\)""")) { match ->
            val texName = match.groupValues[1]
            val idx = inputNames.indexOf(texName)
            val sampler = if (idx >= 0) "uInput$idx" else "uInput0"
            "texture($sampler, vTexCoord + vec2(x_off, y_off) * uTexelSize)"
        }

        result = result.replace(Regex("""(\w+)_tex\s*\(\s*\w+_pos\s*\)""")) { match ->
            val texName = match.groupValues[1]
            val idx = inputNames.indexOf(texName)
            val sampler = if (idx >= 0) "uInput$idx" else "uInput0"
            "texture($sampler, vTexCoord)"
        }

        return result
    }

    private fun readAsset(path: String): String {
        val inputStream = context.assets.open(path)
        return BufferedReader(InputStreamReader(inputStream)).use { it.readText() }
    }

    private fun setupVertexBuffers(program: GlProgram) {
        program.setBufferAttribute("aFramePosition", FRAME_POSITION_DATA, 2)
        program.setBufferAttribute("aTexSamplingCoord", TEX_COORD_DATA, 2)
    }

    private fun currentFramebuffer(): Int = IntArray(1).also {
        GLES30.glGetIntegerv(GLES30.GL_FRAMEBUFFER_BINDING, it, 0)
    }[0]

    private fun currentViewport(): IntArray = IntArray(4).also {
        GLES30.glGetIntegerv(GLES30.GL_VIEWPORT, it, 0)
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

    private fun checkGlError(tag: String) {
        val err = GLES30.glGetError()
        if (err != GLES30.GL_NO_ERROR) {
            Logger.w(TAG, "$tag GL error: 0x${Integer.toHexString(err)}")
        }
    }

    private companion object {
        private const val TAG = "Anime4KCNN"

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
    }
}

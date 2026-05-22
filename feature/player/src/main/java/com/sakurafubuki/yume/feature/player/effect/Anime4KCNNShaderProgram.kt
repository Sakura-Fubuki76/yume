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
    private val downscaleFactor: Float = 1.0f,
) : BaseGlShaderProgram(useHdr, 1) {

    private var layers: List<CompiledLayer> = emptyList()
    private var passThroughProgram: GlProgram? = null
    private var shaderInitFailed = false
    private var inputWidth = 0
    private var inputHeight = 0
    private var workWidth = 0
    private var workHeight = 0
    private var scaleFactor = 1

    private data class FboTex(
        var fbo: Int = 0,
        var tex: Int = 0,
        var width: Int = 0,
        var height: Int = 0,
        var useHalfFloat: Boolean = true,
    )

    private val intermediates = mutableListOf<FboTex>()
    private val framebufferBinding = IntArray(1)
    private val viewport = IntArray(4)
    private val deleteScratch = IntArray(1)
    private val inputTexelSize = FloatArray(2)

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        releaseIntermediates()
        this.inputWidth = inputWidth
        this.inputHeight = inputHeight
        workWidth = (inputWidth * downscaleFactor).toInt().coerceAtLeast(1)
        workHeight = (inputHeight * downscaleFactor).toInt().coerceAtLeast(1)

        passThroughProgram = runCatching {
            GlProgram(VERTEX_SHADER, PASS_THROUGH_FRAG).also(::setupVertexBuffers)
        }.onFailure {
            Logger.w(TAG, "CNN pass-through shader compilation failed", it)
        }.getOrNull()

        try {
            val source = readAsset(assetPath)
            scaleFactor = parseScaleFactor(source)
            val parsed = parseCnnSource(source)
            val layerPlans = buildLayerPlans(parsed)
            layers = layerPlans.map { compileLayer(it) }
            Logger.i(
                TAG,
                "CNN input=${inputWidth}x$inputHeight work=${workWidth}x$workHeight scale=${scaleFactor}x " +
                    "compiled ${layers.size} layers using ${intermediates.size} intermediate slots from $assetPath",
            )
        } catch (e: Exception) {
            Logger.w(TAG, "CNN init failed for $assetPath, using pass-through", e)
            shaderInitFailed = true
            layers = emptyList()
        }
        return Size(workWidth * scaleFactor, workHeight * scaleFactor)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        val outputFbo = currentFramebuffer()
        if (shaderInitFailed || layers.isEmpty()) {
            drawPassThrough(inputTexId, outputFbo)
            return
        }
        try {
            val viewport = currentViewport()
            val vw = viewport[2]
            val vh = viewport[3]
            if (vw <= 0 || vh <= 0) {
                drawPassThrough(inputTexId, outputFbo)
                return
            }

            inputTexelSize[0] = 1f / inputWidth
            inputTexelSize[1] = 1f / inputHeight

            for ((index, layer) in layers.withIndex()) {
                val isLast = index == layers.lastIndex
                val targetFbo = if (isLast) outputFbo else ensureIntermediate(layer.outputSlot, workWidth, workHeight)

                val layerW = if (isLast) vw else workWidth
                val layerH = if (isLast) vh else workHeight
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, targetFbo)
                GLES30.glViewport(0, 0, layerW, layerH)
                layer.program.use()

                for ((unit, slot) in layer.inputSlots.withIndex()) {
                    val texId = if (slot == MAIN_TEXTURE_SLOT) {
                        inputTexId
                    } else {
                        intermediates.getOrNull(slot)?.tex?.takeIf { it != 0 } ?: inputTexId
                    }
                    layer.program.setSamplerTexIdUniform("uInput$unit", texId, unit)
                }
                if (layer.needsTexelSize) {
                    layer.program.setFloatsUniform("uTexelSize", inputTexelSize)
                }
                layer.program.bindAttributesAndUniforms()
                GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
                checkGlError("CNN layer ${index + 1}")
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
        for (ft in intermediates) {
            deleteFboTex(ft)
        }
        intermediates.clear()
    }

    private fun ensureIntermediate(slot: Int, w: Int, h: Int): Int {
        val existing = intermediates[slot]
        if (existing.fbo != 0 && existing.width == w && existing.height == h) return existing.fbo
        deleteFboTex(existing)

        val result = tryCreateFboTex(w, h, useHalfFloat = true)
        if (result != null) {
            intermediates[slot] = result
            return result.fbo
        }
        Logger.w(TAG, "RGBA16F FBO failed, falling back to RGBA8 for slot $slot")

        val fallbackResult = tryCreateFboTex(w, h, useHalfFloat = false)
        if (fallbackResult != null) {
            intermediates[slot] = fallbackResult
            return fallbackResult.fbo
        }
        Logger.e(TAG, "Failed to create intermediate FBO for slot $slot")
        shaderInitFailed = true
        return 0
    }

    private fun deleteFboTex(ft: FboTex) {
        if (ft.fbo != 0) {
            deleteScratch[0] = ft.fbo
            GLES30.glDeleteFramebuffers(1, deleteScratch, 0)
            ft.fbo = 0
        }
        if (ft.tex != 0) {
            deleteScratch[0] = ft.tex
            GLES30.glDeleteTextures(1, deleteScratch, 0)
            ft.tex = 0
        }
        ft.width = 0
        ft.height = 0
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
            deleteScratch[0] = tex
            GLES30.glDeleteTextures(1, deleteScratch, 0)
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
            deleteScratch[0] = fbo
            GLES30.glDeleteFramebuffers(1, deleteScratch, 0)
            deleteScratch[0] = tex
            GLES30.glDeleteTextures(1, deleteScratch, 0)
            return null
        }
        return FboTex(fbo, tex, w, h, useHalfFloat)
    }

    private data class ParsedLayer(
        val outputName: String,
        val inputNames: List<String>,
        val macros: List<String>,
        val body: String,
        val returnExpr: String,
        val isFinal: Boolean,
    )

    private fun parseScaleFactor(source: String): Int {
        val pattern = Regex("""//!WIDTH\s+\w+\.\w\s+(\d+)\s+\*""")
        var maxScale = 1
        for (line in source.lines()) {
            pattern.find(line)?.let { match ->
                val s = match.groupValues[1].toInt()
                if (s > maxScale) maxScale = s
            }
        }
        return maxScale
    }

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
        val outputSlot: Int,
        val inputSlots: IntArray,
        val needsTexelSize: Boolean,
    )

    private data class LayerPlan(
        val parsed: ParsedLayer,
        val outputSlot: Int,
        val inputSlots: IntArray,
    )

    private fun buildLayerPlans(parsedLayers: List<ParsedLayer>): List<LayerPlan> {
        val lastUseByName = mutableMapOf<String, Int>()
        parsedLayers.forEachIndexed { index, layer ->
            for (name in layer.inputNames) {
                if (name != MAIN_TEXTURE_NAME) lastUseByName[name] = index
            }
        }

        val activeSlotsByName = mutableMapOf<String, Int>()
        val freeSlots = ArrayDeque<Int>()
        val plans = ArrayList<LayerPlan>(parsedLayers.size)

        parsedLayers.forEachIndexed { index, layer ->
            val inputSlots = IntArray(layer.inputNames.size) { inputIndex ->
                activeSlotsByName[layer.inputNames[inputIndex]] ?: MAIN_TEXTURE_SLOT
            }
            val outputSlot = if (layer.isFinal) MAIN_TEXTURE_SLOT else allocateIntermediateSlot(freeSlots)
            plans += LayerPlan(layer, outputSlot, inputSlots)

            if (!layer.isFinal) {
                activeSlotsByName[layer.outputName] = outputSlot
            }

            val reusableNames = activeSlotsByName
                .filter { (name, _) -> lastUseByName[name]?.let { it <= index } ?: true }
                .keys
                .toList()
            for (name in reusableNames) {
                val slot = activeSlotsByName.remove(name) ?: continue
                freeSlots.addLast(slot)
            }
        }

        return plans
    }

    private fun allocateIntermediateSlot(freeSlots: ArrayDeque<Int>): Int {
        if (freeSlots.isNotEmpty()) return freeSlots.removeFirst()
        val slot = intermediates.size
        intermediates += FboTex()
        return slot
    }

    private fun compileLayer(plan: LayerPlan): CompiledLayer {
        val needsTexelSize = plan.parsed.macros.any { it.contains("x_off") }
        val fragShader = buildFragmentShader(plan.parsed)
        val program = GlProgram(VERTEX_SHADER, fragShader)
        setupVertexBuffers(program)
        return CompiledLayer(program, plan.outputSlot, plan.inputSlots, needsTexelSize)
    }

    private fun buildFragmentShader(layer: ParsedLayer): String {
        val sb = StringBuilder()
        sb.appendLine("#version 300 es")
        sb.appendLine("precision highp float;")

        for ((i, name) in layer.inputNames.withIndex()) {
            sb.appendLine("uniform sampler2D uInput$i;")
        }
        if (layer.macros.any { it.contains("x_off") } || layer.body.contains("_pt") || layer.body.contains("_size")) {
            sb.appendLine("uniform vec2 uTexelSize;")
        }
        sb.appendLine("in vec2 vTexCoord;")
        sb.appendLine("out vec4 fragColor;")

        for (macro in layer.macros) {
            val transformed = transformMacro(macro, layer.inputNames)
            sb.appendLine(transformed)
        }

        sb.appendLine("void main() {")
        val body = transformBodyRefs(layer.body, layer.inputNames)
        sb.appendLine(body)

        var returnExpr = transformBodyRefs(layer.returnExpr, layer.inputNames)
        returnExpr = returnExpr.replace(
            Regex("""MAIN_tex\s*\(\s*MAIN_pos\s*\)"""),
            "texture(uInput0, vTexCoord)",
        )
        sb.appendLine("    fragColor = $returnExpr;")
        sb.appendLine("}")

        return sb.toString()
    }

    private fun transformBodyRefs(code: String, inputNames: List<String>): String {
        var result = code

        // Replace NAME_pos, NAME_size, NAME_pt
        for ((index, name) in inputNames.withIndex()) {
            result = result.replace(Regex("""\b${Regex.escape(name)}_pos\b"""), "vTexCoord")
            result = result.replace(Regex("""\b${Regex.escape(name)}_size\b"""), "vec2(textureSize(uInput$index, 0))")
            result = result.replace(Regex("""\b${Regex.escape(name)}_pt\b"""), "1.0 / vec2(textureSize(uInput$index, 0))")
        }

        // Replace NAME_tex(expr) where expr may contain nested parens
        result = replaceTexCall(result, inputNames)

        return result
    }

    private fun replaceTexCall(code: String, inputNames: List<String>): String {
        val pattern = Regex("""(\w+)_tex\s*\(""")
        val sb = StringBuilder()
        var i = 0
        while (i < code.length) {
            val match = pattern.find(code, i)
            if (match == null) {
                sb.append(code.substring(i))
                break
            }
            sb.append(code.substring(i, match.range.first))
            val name = match.groupValues[1]
            val idx = inputNames.indexOf(name)
            if (idx < 0) {
                sb.append(match.value)
                i = match.range.last + 1
                continue
            }
            // Scan forward to find matching closing paren
            var depth = 1
            var j = match.range.last + 1
            while (j < code.length && depth > 0) {
                when (code[j]) {
                    '(' -> depth++
                    ')' -> depth--
                }
                j++
            }
            val arg = code.substring(match.range.last + 1, j - 1)
            sb.append("texture(uInput$idx, $arg)")
            i = j
        }
        return sb.toString()
    }

    private fun transformMacro(macro: String, inputNames: List<String>): String {
        var result = macro

        result = result.replace(Regex("""(\w+)_texOff\s*\(\s*vec2\s*\(\s*x_off\s*,\s*y_off\s*\)\s*(\*\s*[\d.]+)?\s*\)""")) { match ->
            val texName = match.groupValues[1]
            val extraScale = match.groupValues[2]
            val idx = inputNames.indexOf(texName)
            val sampler = if (idx >= 0) "uInput$idx" else "uInput0"
            "texture($sampler, vTexCoord + vec2(x_off, y_off)$extraScale * uTexelSize)"
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

    private fun currentFramebuffer(): Int {
        GLES30.glGetIntegerv(GLES30.GL_FRAMEBUFFER_BINDING, framebufferBinding, 0)
        return framebufferBinding[0]
    }

    private fun currentViewport(): IntArray {
        GLES30.glGetIntegerv(GLES30.GL_VIEWPORT, viewport, 0)
        return viewport
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
        private const val MAIN_TEXTURE_NAME = "MAIN"
        private const val MAIN_TEXTURE_SLOT = -1

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

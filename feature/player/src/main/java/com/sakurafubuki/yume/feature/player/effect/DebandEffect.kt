package com.sakurafubuki.yume.feature.player.effect

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

@UnstableApi
class DebandEffect(
    private val maxLuma: Float? = null,
    private val iterations: Int = 1,
    private val threshold: Float = 3.0f,
    private val radius: Float = 16.0f,
    private val grain: Float = 4.0f,
) : GlEffect {

    @UnstableApi
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = LibplaceboDebandShaderProgram(useHdr, maxLuma, iterations, threshold, radius, grain)

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = false
}

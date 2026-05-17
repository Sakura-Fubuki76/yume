package com.sakurafubuki.yume.feature.player

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Window
import com.sakurafubuki.yume.core.common.Logger
import kotlin.math.abs

object RefreshRateMatcher {

    private const val TAG = "RefreshRateMatcher"

    private var originalModeId = -1
    private var matchedModeId = -1
    private var matchedFrameRate = 0f

    fun onVideoFrameRateDetected(fps: Float, window: Window) {
        if (fps <= 0f || abs(fps - matchedFrameRate) < 0.1f) return
        matchedFrameRate = fps

        val context = window.context
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return
        val modes = display.supportedModes ?: return

        val currentMode = display.mode
        if (originalModeId < 0) {
            originalModeId = currentMode.modeId
        }

        val best = findBestMode(modes, fps, currentMode)
        if (best != null && best.modeId != currentMode.modeId) {
            Logger.i(TAG, "Video ${fps}fps → display ${best.refreshRate}Hz (was ${currentMode.refreshRate}Hz)")
            val lp = window.attributes
            lp.preferredDisplayModeId = best.modeId
            window.attributes = lp
            matchedModeId = best.modeId
        }
    }

    fun onPlaybackStopped(window: Window) {
        if (originalModeId < 0 || matchedModeId < 0) return
        Logger.i(TAG, "Restoring original display mode $originalModeId")
        val lp = window.attributes
        lp.preferredDisplayModeId = originalModeId
        window.attributes = lp
        originalModeId = -1
        matchedModeId = -1
        matchedFrameRate = 0f
    }

    private fun findBestMode(modes: Array<Display.Mode>, fps: Float, current: Display.Mode) = modes.minByOrNull { mode ->
        when (val ratio = mode.refreshRate / fps) {
            in 0.99f..1.01f -> 0f
            in 1.99f..2.01f -> 0.1f
            in 0.49f..0.51f -> 0.1f
            else -> abs(ratio - 1f)
        }
    }
}

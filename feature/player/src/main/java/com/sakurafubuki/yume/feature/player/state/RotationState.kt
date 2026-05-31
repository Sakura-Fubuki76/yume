package com.sakurafubuki.yume.feature.player.state

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.DisposableEffectResult
import androidx.compose.runtime.DisposableEffectScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.util.Consumer
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.listen
import androidx.media3.common.util.UnstableApi
import com.sakurafubuki.yume.core.model.ScreenOrientation

@UnstableApi
@Composable
fun rememberRotationState(
    player: Player,
    screenOrientation: ScreenOrientation,
): RotationState {
    val activity = LocalActivity.current as ComponentActivity
    val rotationState = remember(activity, player, screenOrientation) {
        RotationState(
            activity = activity,
            player = player,
            screenOrientation = screenOrientation,
        )
    }
    DisposableEffect(activity, rotationState) {
        rotationState.handleListeners(this)
    }
    LaunchedEffect(rotationState) { rotationState.observe() }
    return rotationState
}

private data class DisplayVideoSize(
    val width: Float,
    val height: Float,
)

@Stable
class RotationState(
    private val activity: ComponentActivity,
    private val player: Player,
    private val screenOrientation: ScreenOrientation,
) {
    var currentRequestedOrientation: Int by mutableIntStateOf(activity.requestedOrientation)
        private set

    fun rotate() {
        val orientation = when (activity.resources.configuration.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        requestOrientation(orientation)
    }

    fun handleListeners(disposableEffectScope: DisposableEffectScope): DisposableEffectResult = with(disposableEffectScope) {
        val configurationChangedListener: Consumer<Configuration> = Consumer {
            currentRequestedOrientation = activity.requestedOrientation
        }

        activity.addOnConfigurationChangedListener(configurationChangedListener)

        onDispose {
            activity.removeOnConfigurationChangedListener(configurationChangedListener)
        }
    }

    suspend fun observe() {
        setOrientation()
        player.listen { events ->
            if (events.contains(Player.EVENT_TRACKS_CHANGED) ||
                events.contains(Player.EVENT_VIDEO_SIZE_CHANGED)
            ) {
                applyVideoOrientation()
            }
        }
    }

    private fun applyVideoOrientation() {
        if (screenOrientation != ScreenOrientation.VIDEO_ORIENTATION) return
        val orient = getVideoBasedOrientation()
        requestOrientation(orient)
    }

    private fun setOrientation() {
        val orientation = when (screenOrientation) {
            ScreenOrientation.AUTOMATIC -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
            ScreenOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            ScreenOrientation.LANDSCAPE_REVERSE -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            ScreenOrientation.LANDSCAPE_AUTO -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            ScreenOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            ScreenOrientation.VIDEO_ORIENTATION -> getVideoBasedOrientation()
        }
        requestOrientation(orientation)
    }

    private fun getVideoBasedOrientation(): Int {
        val (w, h) = getDisplayVideoSize()
        return when {
            w <= 0f || h <= 0f -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            h > w -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    private fun requestOrientation(orientation: Int) {
        activity.requestedOrientation = orientation
        currentRequestedOrientation = orientation
    }

    private fun getDisplayVideoSize(): DisplayVideoSize {
        val playerVideoSize = player.videoSize
        if (playerVideoSize.width > 0 && playerVideoSize.height > 0) {
            return DisplayVideoSize(
                width = playerVideoSize.width * playerVideoSize.pixelWidthHeightRatio,
                height = playerVideoSize.height.toFloat(),
            )
        }
        return getTrackVideoSize()
    }

    private fun getTrackVideoSize(): DisplayVideoSize {
        val videoTrack = player.currentTracks.groups
            .firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }
            ?: player.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO }
            ?: return DisplayVideoSize(0f, 0f)
        if (videoTrack.mediaTrackGroup.length == 0) return DisplayVideoSize(0f, 0f)
        val format = videoTrack.mediaTrackGroup.getFormat(0)
        return format.toDisplayVideoSize()
    }

    private fun Format.toDisplayVideoSize(): DisplayVideoSize {
        if (width <= 0 || height <= 0) return DisplayVideoSize(0f, 0f)
        val rotated = rotationDegrees == 90 || rotationDegrees == 270
        val pixelRatio = pixelWidthHeightRatio.takeIf { it > 0f } ?: 1f
        return if (rotated) {
            DisplayVideoSize(
                width = height.toFloat(),
                height = width * pixelRatio,
            )
        } else {
            DisplayVideoSize(
                width = width * pixelRatio,
                height = height.toFloat(),
            )
        }
    }
}

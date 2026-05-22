package com.sakurafubuki.yume.feature.player.extensions

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import com.sakurafubuki.yume.core.common.Logger
import com.sakurafubuki.yume.feature.player.service.setMediaControllerIsScrubbingModeEnabled
import io.github.sakurafubuki.yume.nativelib.renderer.OffsetRenderer
import kotlin.math.roundToLong

fun Player.switchTrack(trackType: @C.TrackType Int, trackIndex: Int) {
    val trackTypeText = when (trackType) {
        C.TRACK_TYPE_AUDIO -> "audio"
        C.TRACK_TYPE_TEXT -> "subtitle"
        else -> throw IllegalArgumentException("Invalid track type: $trackType")
    }

    if (trackIndex < 0) {
        Logger.d("Player", "Disabling $trackTypeText")
        trackSelectionParameters = trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(trackType, true)
            .build()
    } else {
        val tracks = currentTracks.groups.filter { it.type == trackType }

        if (tracks.isEmpty() || trackIndex >= tracks.size) {
            Logger.e("Player", "Operation failed: Invalid track index: $trackIndex")
            return
        }

        Logger.d("Player", "Setting $trackTypeText track: $trackIndex")
        val trackSelectionOverride = TrackSelectionOverride(tracks[trackIndex].mediaTrackGroup, 0)

        trackSelectionParameters = trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(trackType, false)
            .setOverrideForType(trackSelectionOverride)
            .build()
    }
}

@UnstableApi
fun Player.getManuallySelectedTrackIndex(trackType: @C.TrackType Int): Int? {
    val isDisabled = trackSelectionParameters.disabledTrackTypes.contains(trackType)
    if (isDisabled) return -1

    val trackOverrides = trackSelectionParameters.overrides.values.map { it.mediaTrackGroup }
    val trackOverride = trackOverrides.firstOrNull { it.type == trackType } ?: return null
    val tracks = currentTracks.groups.filter { it.type == trackType }

    return tracks.indexOfFirst { it.mediaTrackGroup == trackOverride }.takeIf { it != -1 }
}

fun Player.addAdditionalSubtitleConfiguration(subtitle: MediaItem.SubtitleConfiguration) {
    val currentMediaItemLocal = currentMediaItem ?: return
    val existingSubConfigurations = currentMediaItemLocal.localConfiguration?.subtitleConfigurations ?: emptyList()

    if (existingSubConfigurations.any { it.id == subtitle.id }) {
        return
    }

    val updateMediaItem = currentMediaItemLocal
        .buildUpon()
        .setSubtitleConfigurations(existingSubConfigurations + listOf(subtitle))
        .build()

    val index = currentMediaItemIndex
    addMediaItem(index + 1, updateMediaItem)
    seekTo(index + 1, currentPosition)
    removeMediaItem(index)
}

@OptIn(UnstableApi::class)
fun Player.setIsScrubbingModeEnabled(enabled: Boolean) {
    when (this) {
        is MediaController -> this.setMediaControllerIsScrubbingModeEnabled(enabled)
        is ExoPlayer -> this.isScrubbingModeEnabled = enabled
    }
}

private val subtitleDelayStore = java.util.Collections.synchronizedMap(java.util.WeakHashMap<Player, Long>())
private val subtitleSpeedStore = java.util.Collections.synchronizedMap(java.util.WeakHashMap<Player, Float>())

@OptIn(UnstableApi::class)
var Player.subtitleDelayMilliseconds: Long
    get() = subtitleDelayStore[this] ?: 0L
    set(value) {
        subtitleDelayStore[this] = value
        applySubtitleTimingToRenderers()
    }

@OptIn(UnstableApi::class)
var Player.subtitleSpeed: Float
    get() = subtitleSpeedStore[this] ?: 1f
    set(value) {
        subtitleSpeedStore[this] = value.coerceIn(0.1f, 10f)
        applySubtitleTimingToRenderers()
    }

@OptIn(UnstableApi::class)
fun Player.applySubtitleTimingToRenderers() {
    if (this !is ExoPlayer) return
    for (index in 0 until rendererCount) {
        val renderer = getRenderer(index) as? OffsetRenderer ?: continue
        renderer.syncOffsetMilliseconds = subtitleDelayMilliseconds
        renderer.syncSpeedMultiplier = subtitleSpeed
    }
}

fun Player.getSubtitleAdjustedPositionMs(): Long = (currentPosition * subtitleSpeed).roundToLong() - subtitleDelayMilliseconds

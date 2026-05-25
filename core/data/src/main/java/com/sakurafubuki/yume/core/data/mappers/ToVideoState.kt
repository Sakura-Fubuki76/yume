package com.sakurafubuki.yume.core.data.mappers

import android.net.Uri
import com.sakurafubuki.yume.core.data.models.VideoState
import com.sakurafubuki.yume.core.database.converter.UriListConverter
import com.sakurafubuki.yume.core.database.entities.MediumStateEntity

fun MediumStateEntity.toVideoState(): VideoState = VideoState(
    path = uriString,
    position = playbackPosition.takeIf { it != 0L },
    audioTrackIndex = audioTrackIndex,
    subtitleTrackIndex = subtitleTrackIndex,
    selectedSubtitleUri = selectedSubtitleUri?.let(Uri::parse),
    playbackSpeed = playbackSpeed,
    externalSubs = UriListConverter.fromStringToList(externalSubs),
    videoScale = videoScale,
    subtitleDelayMilliseconds = subtitleDelayMilliseconds,
    subtitleSpeed = subtitleSpeed,
)

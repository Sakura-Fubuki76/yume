package com.sakurafubuki.yume.feature.player.ui.controls

import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.sakurafubuki.yume.core.common.Logger
import com.sakurafubuki.yume.core.model.ChapterEntry
import com.sakurafubuki.yume.core.model.VideoContentScale
import com.sakurafubuki.yume.core.ui.R
import com.sakurafubuki.yume.core.ui.designsystem.NextIcons
import com.sakurafubuki.yume.core.ui.extensions.copy
import com.sakurafubuki.yume.feature.player.LocalUseMaterialYouControls
import com.sakurafubuki.yume.feature.player.buttons.LoopButton
import com.sakurafubuki.yume.feature.player.buttons.PlayerButton
import com.sakurafubuki.yume.feature.player.buttons.ShuffleButton
import com.sakurafubuki.yume.feature.player.extensions.drawableRes
import com.sakurafubuki.yume.feature.player.extensions.noRippleClickable
import com.sakurafubuki.yume.feature.player.state.MediaPresentationState
import com.sakurafubuki.yume.feature.player.state.SpriteSheetState
import com.sakurafubuki.yume.feature.player.state.durationFormatted
import com.sakurafubuki.yume.feature.player.state.pendingPositionFormatted
import com.sakurafubuki.yume.feature.player.state.positionFormatted
import com.sakurafubuki.yume.feature.player.ui.ThumbnailPreview

@OptIn(UnstableApi::class)
@Composable
fun ControlsBottomView(
    modifier: Modifier = Modifier,
    player: Player,
    mediaPresentationState: MediaPresentationState,
    controlsAlignment: Alignment.Horizontal,
    videoContentScale: VideoContentScale,
    anime4KEnabled: Boolean,
    isPipSupported: Boolean,
    spriteSheetState: SpriteSheetState? = null,
    isSeeking: Boolean = false,
    seekPosition: Long = 0L,
    chapters: List<ChapterEntry> = emptyList(),
    onVideoContentScaleClick: () -> Unit,
    onVideoContentScaleLongClick: () -> Unit,
    onLockControlsClick: () -> Unit,
    onPictureInPictureClick: () -> Unit,
    onRotateClick: () -> Unit,
    onPlayInBackgroundClick: () -> Unit,
    onAnime4KClick: () -> Unit = {},
    onSeek: (Long) -> Unit,
    onSeekEnd: () -> Unit,
) {
    val systemBarsPadding = WindowInsets.systemBars.union(WindowInsets.displayCutout).asPaddingValues()
    Column(
        modifier = modifier
            .padding(systemBarsPadding.copy(top = 0.dp))
            .padding(horizontal = 8.dp)
            .padding(top = 16.dp)
            .padding(bottom = 16.dp.takeIf { systemBarsPadding.calculateBottomPadding() == 0.dp } ?: 0.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            var showPendingPosition by rememberSaveable { mutableStateOf(false) }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.noRippleClickable {
                    showPendingPosition = !showPendingPosition
                },
            ) {
                Text(
                    text = when (showPendingPosition) {
                        true -> "-${mediaPresentationState.pendingPositionFormatted}"
                        false -> mediaPresentationState.positionFormatted
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                )
                Text(
                    text = stringResource(R.string.time_separator),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                )
                Text(
                    text = mediaPresentationState.durationFormatted,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                )
            }

            Spacer(modifier = Modifier.weight(1f))
            PlayerButton(
                modifier = modifier.size(30.dp),
                onClick = onRotateClick,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_screen_rotation),
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                )
            }
        }

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val parentWidthPx = constraints.maxWidth.toFloat()

            if (isSeeking && spriteSheetState != null) {
                val sheet = spriteSheetState.cachedSheet
                if (sheet != null) {
                    val frameIndex = spriteSheetState.getFrameIndex(seekPosition)
                    val fraction = if (mediaPresentationState.duration > 0) {
                        seekPosition.toFloat() / mediaPresentationState.duration
                    } else {
                        0f
                    }
                    val chapterTitle = chapters
                        .firstOrNull { seekPosition in it.startTimeMs..<it.endTimeMs }
                        ?.title
                        ?.takeIf { it.isNotBlank() }
                    ThumbnailPreview(
                        spriteSheet = sheet.bitmap,
                        metadata = sheet.metadata,
                        frameIndex = frameIndex,
                        seekFraction = fraction,
                        parentWidth = parentWidthPx,
                        chapterTitle = chapterTitle,
                    )
                }
            }

            PlayerSeekbar(
                position = mediaPresentationState.position.toFloat(),
                bufferedPosition = mediaPresentationState.bufferedPosition.toFloat(),
                duration = mediaPresentationState.duration.toFloat(),
                showBufferedProgress = player.currentMediaItem?.mediaId?.isHttpUrl() == true,
                chapters = chapters,
                onSeek = { onSeek(it.toLong()) },
                onSeekFinished = { onSeekEnd() },
            )
        }
        Box(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, alignment = controlsAlignment),
            ) {
                PlayerButton(onClick = onLockControlsClick) {
                    Icon(
                        painter = painterResource(R.drawable.ic_lock_open),
                        contentDescription = null,
                    )
                }
                PlayerButton(
                    onClick = onVideoContentScaleClick,
                    onLongClick = onVideoContentScaleLongClick,
                ) {
                    Icon(
                        painter = painterResource(videoContentScale.drawableRes()),
                        contentDescription = null,
                    )
                }
                if (isPipSupported) {
                    PlayerButton(onClick = onPictureInPictureClick) {
                        Icon(
                            painter = painterResource(R.drawable.ic_pip),
                            contentDescription = null,
                        )
                    }
                }
                PlayerButton(onClick = onPlayInBackgroundClick) {
                    Icon(
                        painter = painterResource(R.drawable.ic_headset),
                        contentDescription = null,
                    )
                }
                LoopButton(player = player)
                ShuffleButton(player = player)
            }

            PlayerButton(
                modifier = Modifier.align(Alignment.CenterEnd),
                onClick = onAnime4KClick,
            ) {
                Icon(
                    imageVector = NextIcons.AutoFix,
                    contentDescription = stringResource(R.string.anime4k_upscale_title),
                    tint = if (anime4KEnabled) Color.White else Color.White.copy(alpha = 0.45f),
                )
            }
        }
    }
}

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerSeekbar(
    modifier: Modifier = Modifier,
    position: Float,
    bufferedPosition: Float,
    duration: Float,
    showBufferedProgress: Boolean,
    chapters: List<ChapterEntry> = emptyList(),
    onSeek: (Float) -> Unit,
    onSeekFinished: () -> Unit,
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        if (LocalUseMaterialYouControls.current) {
            MaterialYouSlider(
                modifier = modifier.fillMaxWidth(),
                value = position,
                bufferedValue = bufferedPosition,
                valueRange = 0f..duration,
                showBufferedProgress = showBufferedProgress,
                chapters = chapters,
                onValueChange = onSeek,
                onValueChangeFinished = onSeekFinished,
            )
        } else {
            SimpleSlider(
                modifier = modifier.fillMaxWidth(),
                value = position,
                bufferedValue = bufferedPosition,
                valueRange = 0f..duration,
                showBufferedProgress = showBufferedProgress,
                chapters = chapters,
                onValueChange = onSeek,
                onValueChangeFinished = onSeekFinished,
            )
        }
    }
}

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MaterialYouSlider(
    modifier: Modifier = Modifier,
    value: Float,
    bufferedValue: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    showBufferedProgress: Boolean,
    chapters: List<ChapterEntry> = emptyList(),
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val bufferedColor = MaterialTheme.colorScheme.primaryContainer
    val interactionSource = remember { MutableInteractionSource() }
    val trackHeight = 8.dp
    val thumbWidth = 4.dp
    val trackThumbGapWidth = 12.dp

    Slider(
        value = value,
        valueRange = valueRange,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        interactionSource = interactionSource,
        modifier = modifier.size(24.dp),
        track = { sliderState ->
            val disabledAlpha = 0.4f

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(trackHeight),
            ) {
                val min = sliderState.valueRange.start
                val max = sliderState.valueRange.endInclusive
                val range = (max - min).takeIf { it > 0f } ?: 1f
                val playedFraction = ((sliderState.value - min) / range).coerceIn(0f, 1f)
                val bufferedFraction = ((bufferedValue - min) / range).coerceIn(playedFraction, 1f)
                val playedPixels = size.width * playedFraction
                val bufferedPixels = size.width * bufferedFraction

                val endCornerRadius = size.height / 2f
                val insideCornerRadius = 2.dp.toPx()
                val gapHalf = trackThumbGapWidth.toPx() / 2f
                val leftEnd = (playedPixels - gapHalf).coerceIn(0f, size.width)
                val rightStart = (playedPixels + gapHalf).coerceIn(0f, size.width)
                val bufferedEnd = (bufferedPixels - gapHalf).coerceIn(0f, size.width)

                if (leftEnd > 0f) {
                    drawRoundedRect(
                        offset = Offset(0f, 0f),
                        size = Size(leftEnd, size.height),
                        color = primaryColor.copy(alpha = disabledAlpha),
                        startCornerRadius = endCornerRadius,
                        endCornerRadius = insideCornerRadius,
                    )
                }

                if (rightStart < size.width) {
                    drawRoundedRect(
                        offset = Offset(rightStart, 0f),
                        size = Size(size.width - rightStart, size.height),
                        color = primaryColor.copy(alpha = disabledAlpha),
                        startCornerRadius = insideCornerRadius,
                        endCornerRadius = endCornerRadius,
                    )
                }

                if (showBufferedProgress && bufferedEnd > leftEnd) {
                    drawRoundedRect(
                        offset = Offset(leftEnd, 0f),
                        size = Size(bufferedEnd - leftEnd, size.height),
                        color = bufferedColor.copy(alpha = 0.7f),
                        startCornerRadius = insideCornerRadius,
                        endCornerRadius = if (bufferedEnd >= size.width) endCornerRadius else insideCornerRadius,
                    )
                }

                if (leftEnd > 0f) {
                    drawRoundedRect(
                        offset = Offset(0f, 0f),
                        size = Size(leftEnd, size.height),
                        color = primaryColor,
                        startCornerRadius = endCornerRadius,
                        endCornerRadius = insideCornerRadius,
                    )
                }

                if (chapters.isNotEmpty()) {
                    val tickColor = Color.White.copy(alpha = 0.5f)
                    val tickWidth = 2.dp.toPx()
                    var drawn = 0
                    for (chapter in chapters) {
                        val fraction = ((chapter.startTimeMs.toFloat() - min) / range).coerceIn(0f, 1f)
                        if (fraction > 0f && fraction < 1f) {
                            val tickX = size.width * fraction
                            drawLine(
                                color = tickColor,
                                start = Offset(tickX, 0f),
                                end = Offset(tickX, size.height),
                                strokeWidth = tickWidth,
                            )
                            drawn++
                        }
                    }
                    Logger.d("BUG4_Chapters", "MaterialYouSlider: drew $drawn/${chapters.size} chapter ticks")
                }
            }
        },
        thumb = {
            Box(
                modifier = Modifier
                    .width(thumbWidth)
                    .height(20.dp)
                    .background(primaryColor, CircleShape),
            )
        },
    )
}

private fun DrawScope.drawRoundedRect(
    offset: Offset,
    size: Size,
    color: Color,
    startCornerRadius: Float,
    endCornerRadius: Float,
) {
    val startCorner = CornerRadius(startCornerRadius, startCornerRadius)
    val endCorner = CornerRadius(endCornerRadius, endCornerRadius)
    val track = RoundRect(
        rect = Rect(Offset(offset.x, 0f), size = Size(size.width, size.height)),
        topLeft = startCorner,
        topRight = endCorner,
        bottomRight = endCorner,
        bottomLeft = startCorner,
    )
    drawPath(
        path = Path().apply {
            addRoundRect(track)
        },
        color = color,
    )
}

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimpleSlider(
    modifier: Modifier = Modifier,
    value: Float,
    bufferedValue: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    showBufferedProgress: Boolean,
    chapters: List<ChapterEntry> = emptyList(),
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    val rangeEnd = valueRange.endInclusive.takeIf { it > 0f } ?: 1f
    val playedFraction = (value / rangeEnd).coerceIn(0f, 1f)
    val bufferedFraction = (bufferedValue / rangeEnd).coerceIn(playedFraction, 1f)
    Slider(
        value = value,
        valueRange = valueRange,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        modifier = modifier.height(20.dp),
        thumb = {
            Box(
                modifier = Modifier.size(16.dp)
                    .shadow(4.dp, CircleShape)
                    .background(Color.White),
            )
        },
        track = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(Color.White.copy(0.5f))
                    .then(
                        if (chapters.isNotEmpty()) {
                            Modifier.drawWithContent {
                                drawContent()
                                val range = valueRange.endInclusive - valueRange.start
                                if (range > 0f) {
                                    val tickColor = Color.White.copy(alpha = 0.5f)
                                    val tickWidth = 2.dp.toPx()
                                    for (chapter in chapters) {
                                        val fraction = ((chapter.startTimeMs.toFloat() - valueRange.start) / range).coerceIn(0f, 1f)
                                        if (fraction > 0f && fraction < 1f) {
                                            val tickX = size.width * fraction
                                            drawLine(
                                                color = tickColor,
                                                start = Offset(tickX, 0f),
                                                end = Offset(tickX, size.height),
                                                strokeWidth = tickWidth,
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            Modifier
                        },
                    ),
            ) {
                if (showBufferedProgress) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(bufferedFraction)
                            .height(4.dp)
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f)),
                    )
                }
                if (valueRange.endInclusive > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(playedFraction)
                            .height(4.dp)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
        },
    )
}

private fun String.isHttpUrl(): Boolean = startsWith("http://", ignoreCase = true) || startsWith("https://", ignoreCase = true)

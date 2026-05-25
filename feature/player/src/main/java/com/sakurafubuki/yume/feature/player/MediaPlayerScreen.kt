package com.sakurafubuki.yume.feature.player

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.sakurafubuki.yume.core.data.repository.MoovIndexCache
import com.sakurafubuki.yume.core.model.Anime4KAutoDownscalePreMode
import com.sakurafubuki.yume.core.model.Anime4KRestoreMode
import com.sakurafubuki.yume.core.model.Anime4KUpscaleMode
import com.sakurafubuki.yume.core.model.ControlButtonsPosition
import com.sakurafubuki.yume.core.model.PlayerPreferences
import com.sakurafubuki.yume.core.model.WebDavServer
import com.sakurafubuki.yume.core.ui.R as coreUiR
import com.sakurafubuki.yume.core.ui.extensions.copy
import com.sakurafubuki.yume.feature.player.ass.AssSubtitleState
import com.sakurafubuki.yume.feature.player.buttons.NextButton
import com.sakurafubuki.yume.feature.player.buttons.PlayPauseButton
import com.sakurafubuki.yume.feature.player.buttons.PlayerButton
import com.sakurafubuki.yume.feature.player.buttons.PreviousButton
import com.sakurafubuki.yume.feature.player.extensions.copy as copyMediaItem
import com.sakurafubuki.yume.feature.player.extensions.nameRes
import com.sakurafubuki.yume.feature.player.extensions.selectedSubtitleUri
import com.sakurafubuki.yume.feature.player.state.ControlsVisibilityState
import com.sakurafubuki.yume.feature.player.state.VerticalGesture
import com.sakurafubuki.yume.feature.player.state.rememberBrightnessState
import com.sakurafubuki.yume.feature.player.state.rememberControlsVisibilityState
import com.sakurafubuki.yume.feature.player.state.rememberErrorState
import com.sakurafubuki.yume.feature.player.state.rememberMediaPresentationState
import com.sakurafubuki.yume.feature.player.state.rememberMetadataState
import com.sakurafubuki.yume.feature.player.state.rememberNetworkSeekTrafficInfo
import com.sakurafubuki.yume.feature.player.state.rememberPictureInPictureState
import com.sakurafubuki.yume.feature.player.state.rememberRotationState
import com.sakurafubuki.yume.feature.player.state.rememberSeekGestureState
import com.sakurafubuki.yume.feature.player.state.rememberSpriteSheetState
import com.sakurafubuki.yume.feature.player.state.rememberTapGestureState
import com.sakurafubuki.yume.feature.player.state.rememberVideoZoomAndContentScaleState
import com.sakurafubuki.yume.feature.player.state.rememberVolumeAndBrightnessGestureState
import com.sakurafubuki.yume.feature.player.state.rememberVolumeState
import com.sakurafubuki.yume.feature.player.state.seekAmountFormatted
import com.sakurafubuki.yume.feature.player.state.seekToPositionFormated
import com.sakurafubuki.yume.feature.player.ui.DoubleTapIndicator
import com.sakurafubuki.yume.feature.player.ui.OverlayShowView
import com.sakurafubuki.yume.feature.player.ui.OverlayView
import com.sakurafubuki.yume.feature.player.ui.SubtitleConfiguration
import com.sakurafubuki.yume.feature.player.ui.VerticalProgressView
import com.sakurafubuki.yume.feature.player.ui.controls.ControlsBottomView
import com.sakurafubuki.yume.feature.player.ui.controls.ControlsTopView
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

val LocalControlsVisibilityState = compositionLocalOf<ControlsVisibilityState?> { null }

@OptIn(UnstableApi::class)
@Composable
fun MediaPlayerScreen(
    player: Player?,
    viewModel: PlayerViewModel,
    playerPreferences: PlayerPreferences,
    modifier: Modifier = Modifier,
    webDavServersById: () -> Map<Int, WebDavServer>,
    onSelectSubtitleClick: () -> Unit,
    onBackClick: () -> Unit,
    onPlayInBackgroundClick: () -> Unit,
) {
    val volumeState = rememberVolumeState(
        player = player,
        showVolumePanelIfHeadsetIsOn = playerPreferences.showSystemVolumePanel,
    )
    val context = LocalContext.current
    val assState = viewModel.assState
    var selectedAssUri by remember { mutableStateOf<Uri?>(null) }
    val customFontsDirectory = playerPreferences.customFontsDirectory
    LaunchedEffect(customFontsDirectory) {
        if (!assState.fontsReady) {
            if (customFontsDirectory.isNotBlank()) {
                withContext(Dispatchers.IO) {
                    assState.loadFontsFromSafTree(context, customFontsDirectory)
                }
            } else {
                assState.setFontsReady()
            }
        }
    }
    player ?: return
    val metadataState = rememberMetadataState(player)
    var currentMediaId by remember(player) { mutableStateOf(player.currentMediaItem?.mediaId) }
    DisposableEffect(player) {
        fun updateCurrentMediaId() {
            currentMediaId = player.currentMediaItem?.mediaId
        }
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                currentMediaId = mediaItem?.mediaId
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                updateCurrentMediaId()
            }
        }
        player.addListener(listener)
        updateCurrentMediaId()
        onDispose {
            player.removeListener(listener)
        }
    }
    LaunchedEffect(currentMediaId) {
        val mediaId = currentMediaId
        selectedAssUri = null
        if (mediaId.isNullOrBlank()) return@LaunchedEffect
        player.currentMediaItem
            ?.mediaMetadata
            ?.selectedSubtitleUri
            ?.toUri()
            ?.takeIf { it.isAssSubtitleUri() }
            ?.let { persistedAssUri ->
                selectedAssUri = persistedAssUri
                return@LaunchedEffect
            }
        repeat(20) {
            val autoUri = AssSubtitleState.autoSelectAssByMediaId.remove(mediaId)
            if (autoUri != null) {
                selectedAssUri = autoUri
                return@LaunchedEffect
            }
            delay(100L)
        }
    }
    val mediaPresentationState = rememberMediaPresentationState(player)
    val controlsVisibilityState = rememberControlsVisibilityState(
        player = player,
        hideAfter = playerPreferences.controllerAutoHideTimeout.seconds,
    )
    val tapGestureState = rememberTapGestureState(
        player = player,
        doubleTapGesture = playerPreferences.doubleTapGesture,
        seekIncrementMillis = playerPreferences.seekIncrement.seconds.inWholeMilliseconds,
        useLongPressGesture = playerPreferences.useLongPressControls,
        longPressSpeed = playerPreferences.longPressControlsSpeed,
    )
    val seekGestureState = rememberSeekGestureState(
        player = player,
        sensitivity = playerPreferences.seekSensitivity,
        enableSeekGesture = playerPreferences.useSeekControls,
    )
    val seekTrafficInfo = rememberNetworkSeekTrafficInfo(
        player = player,
        active = seekGestureState.seekAmount != null,
    )
    val pictureInPictureState = rememberPictureInPictureState(
        player = player,
        autoEnter = playerPreferences.autoPip,
    )
    val videoZoomAndContentScaleState = rememberVideoZoomAndContentScaleState(
        player = player,
        initialContentScale = playerPreferences.playerVideoZoom,
        enableZoomGesture = playerPreferences.useZoomControls,
        enablePanGesture = playerPreferences.enablePanGesture,
        onEvent = viewModel::onVideoZoomEvent,
    )
    val brightnessState = rememberBrightnessState()
    val volumeAndBrightnessGestureState = rememberVolumeAndBrightnessGestureState(
        volumeState = volumeState,
        brightnessState = brightnessState,
        enableVolumeGesture = playerPreferences.enableVolumeSwipeGesture,
        enableBrightnessGesture = playerPreferences.enableBrightnessSwipeGesture,
        volumeGestureSensitivity = playerPreferences.volumeGestureSensitivity,
        brightnessGestureSensitivity = playerPreferences.brightnessGestureSensitivity,
    )
    val rotationState = rememberRotationState(
        player = player,
        screenOrientation = playerPreferences.playerScreenOrientation,
    )
    val errorState = rememberErrorState(player = player)

    val coroutineScope = rememberCoroutineScope()
    val spriteSheetState = rememberSpriteSheetState(
        context = LocalContext.current,
        scope = coroutineScope,
        webDavServersProvider = webDavServersById,
    )

    val mediaId = player.currentMediaItem?.mediaId ?: ""
    val chapters = MoovIndexCache.getChapters(mediaId)

    DisposableEffect(player, webDavServersById().size) {
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                spriteSheetState.onMediaReady(player)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    spriteSheetState.onMediaReady(player)
                }
            }
        }
        player.addListener(listener)
        spriteSheetState.onMediaReady(player)
        onDispose {
            player.removeListener(listener)
        }
    }

    LaunchedEffect(pictureInPictureState.isInPictureInPictureMode) {
        if (pictureInPictureState.isInPictureInPictureMode) {
            controlsVisibilityState.hideControls()
        }
    }

    LaunchedEffect(tapGestureState.isLongPressGestureInAction) {
        if (tapGestureState.isLongPressGestureInAction) {
            controlsVisibilityState.hideControls()
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        if (playerPreferences.rememberPlayerBrightness) {
            brightnessState.setBrightness(playerPreferences.playerBrightness)
        }
    }

    LaunchedEffect(brightnessState.currentBrightness) {
        if (playerPreferences.rememberPlayerBrightness) {
            viewModel.updatePlayerBrightness(brightnessState.currentBrightness)
        }
    }

    var overlayView by remember { mutableStateOf<OverlayView?>(null) }
    val anime4KEnabled = playerPreferences.isAnime4KEnabled()
    var anime4KStatusMessage by remember { mutableStateOf<String?>(null) }
    val anime4KEnabledMessage = stringResource(coreUiR.string.anime4k_enabled_status)
    val anime4KDisabledMessage = stringResource(coreUiR.string.anime4k_disabled_status)
    LaunchedEffect(anime4KStatusMessage) {
        if (anime4KStatusMessage != null) {
            delay(1400L)
            anime4KStatusMessage = null
        }
    }

    CompositionLocalProvider(LocalControlsVisibilityState provides controlsVisibilityState) {
        Box {
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .background(Color.Black),
            ) {
                PlayerContentFrame(
                    player = player,
                    assState = assState,
                    pictureInPictureState = pictureInPictureState,
                    controlsVisibilityState = controlsVisibilityState,
                    tapGestureState = tapGestureState,
                    seekGestureState = seekGestureState,
                    videoZoomAndContentScaleState = videoZoomAndContentScaleState,
                    volumeAndBrightnessGestureState = volumeAndBrightnessGestureState,
                    selectedAssUri = selectedAssUri,
                    subtitleConfiguration = SubtitleConfiguration(
                        useSystemCaptionStyle = playerPreferences.useSystemCaptionStyle,
                        showBackground = playerPreferences.subtitleBackground,
                        textSize = playerPreferences.subtitleTextSize,
                        applyEmbeddedStyles = playerPreferences.applyEmbeddedStyles,
                        textColor = playerPreferences.assSubtitleTextColor,
                        customFontsDirectory = playerPreferences.customFontsDirectory,
                    ),
                )

                AnimatedVisibility(
                    visible = controlsVisibilityState.controlsVisible && !controlsVisibilityState.controlsLocked,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    Box(
                        modifier = modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.3f)),
                    )
                }

                if (mediaPresentationState.isBuffering) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(72.dp),
                    )
                }

                DoubleTapIndicator(tapGestureState = tapGestureState)

                AnimatedVisibility(
                    modifier = Modifier
                        .padding(top = 24.dp)
                        .align(Alignment.TopCenter),
                    visible = tapGestureState.isLongPressGestureInAction,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    Surface(shape = CircleShape) {
                        Row(
                            modifier = Modifier.padding(
                                horizontal = 16.dp,
                                vertical = 8.dp,
                            ),
                        ) {
                            Text(
                                text = stringResource(coreUiR.string.fast_playback_speed, tapGestureState.longPressSpeed),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                }

                if (controlsVisibilityState.controlsVisible && controlsVisibilityState.controlsLocked) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .safeDrawingPadding()
                            .padding(top = 24.dp),
                    ) {
                        PlayerButton(
                            containerColor = Color.Black.copy(0.5f),
                            onClick = { controlsVisibilityState.unlockControls() },
                        ) {
                            Icon(
                                painter = painterResource(coreUiR.drawable.ic_lock),
                                contentDescription = stringResource(coreUiR.string.controls_unlock),
                            )
                        }
                    }
                } else {
                    PlayerControlsView(
                        topView = {
                            AnimatedVisibility(
                                visible = controlsVisibilityState.controlsVisible,
                                enter = fadeIn(),
                                exit = fadeOut(),
                            ) {
                                ControlsTopView(
                                    title = metadataState.title ?: "",
                                    onAudioClick = {
                                        controlsVisibilityState.hideControls()
                                        overlayView = OverlayView.AUDIO_SELECTOR
                                    },
                                    onSubtitleClick = {
                                        controlsVisibilityState.hideControls()
                                        overlayView = OverlayView.SUBTITLE_SELECTOR
                                    },
                                    onPlaybackSpeedClick = {
                                        controlsVisibilityState.hideControls()
                                        overlayView = OverlayView.PLAYBACK_SPEED
                                    },
                                    onPlaylistClick = {
                                        controlsVisibilityState.hideControls()
                                        overlayView = OverlayView.PLAYLIST
                                    },
                                    onBackClick = onBackClick,
                                )
                            }
                        },
                        middleView = {
                            when {
                                anime4KStatusMessage != null -> InfoView(info = anime4KStatusMessage.orEmpty())
                                seekGestureState.seekAmount != null -> InfoView(
                                    info = "${seekGestureState.seekAmountFormatted}\n[${seekGestureState.seekToPositionFormated}]",
                                    supportingInfo = seekTrafficInfo,
                                )
                                videoZoomAndContentScaleState.isZooming -> InfoView(info = "${(videoZoomAndContentScaleState.zoom * 100).toInt()}%")
                                videoZoomAndContentScaleState.showContentScaleIndicator -> InfoView(info = stringResource(videoZoomAndContentScaleState.videoContentScale.nameRes()))
                                controlsVisibilityState.controlsVisible -> ControlsMiddleView(player = player)
                                else -> Unit
                            }
                        },
                        bottomView = {
                            AnimatedVisibility(
                                visible = controlsVisibilityState.controlsVisible && !controlsVisibilityState.controlsLocked,
                                enter = fadeIn(),
                                exit = fadeOut(),
                            ) {
                                val context = LocalContext.current
                                ControlsBottomView(
                                    player = player,
                                    mediaPresentationState = mediaPresentationState,
                                    controlsAlignment = when (playerPreferences.controlButtonsPosition) {
                                        ControlButtonsPosition.LEFT -> Alignment.Start
                                        ControlButtonsPosition.RIGHT -> Alignment.End
                                    },
                                    videoContentScale = videoZoomAndContentScaleState.videoContentScale,
                                    anime4KEnabled = anime4KEnabled,
                                    isPipSupported = pictureInPictureState.isPipSupported,
                                    spriteSheetState = spriteSheetState,
                                    isSeeking = seekGestureState.isSeeking,
                                    seekPosition = seekGestureState.currentSeekPosition,
                                    chapters = chapters,
                                    onSeek = seekGestureState::onSeek,
                                    onSeekEnd = seekGestureState::onSeekEnd,
                                    onRotateClick = rotationState::rotate,
                                    onPlayInBackgroundClick = onPlayInBackgroundClick,
                                    onLockControlsClick = {
                                        controlsVisibilityState.showControls()
                                        controlsVisibilityState.lockControls()
                                    },
                                    onVideoContentScaleClick = {
                                        controlsVisibilityState.showControls()
                                        videoZoomAndContentScaleState.switchToNextVideoContentScale()
                                    },
                                    onVideoContentScaleLongClick = {
                                        controlsVisibilityState.hideControls()
                                        overlayView = OverlayView.VIDEO_CONTENT_SCALE
                                    },
                                    onPictureInPictureClick = {
                                        if (!pictureInPictureState.hasPipPermission) {
                                            Toast.makeText(context, coreUiR.string.enable_pip_from_settings, Toast.LENGTH_SHORT).show()
                                            pictureInPictureState.openPictureInPictureSettings()
                                        } else {
                                            pictureInPictureState.enterPictureInPictureMode()
                                        }
                                    },
                                    onAnime4KClick = {
                                        anime4KStatusMessage = if (anime4KEnabled) anime4KDisabledMessage else anime4KEnabledMessage
                                        viewModel.toggleAnime4KEffects()
                                        controlsVisibilityState.showControls()
                                    },
                                )
                            }
                        },
                    )
                }

                val systemBarsPadding = WindowInsets.systemBars.asPaddingValues()
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .displayCutoutPadding()
                        .padding(systemBarsPadding.copy(top = 0.dp, bottom = 0.dp))
                        .padding(24.dp),
                ) {
                    AnimatedVisibility(
                        modifier = Modifier.align(Alignment.CenterStart),
                        visible = volumeAndBrightnessGestureState.activeGesture == VerticalGesture.VOLUME,
                        enter = fadeIn(),
                        exit = fadeOut(),
                    ) {
                        VerticalProgressView(
                            value = volumeState.volumePercentage,
                            maxValue = volumeState.maxVolumePercentage,
                            icon = painterResource(coreUiR.drawable.ic_volume),
                        )
                    }

                    AnimatedVisibility(
                        modifier = Modifier.align(Alignment.CenterEnd),
                        visible = volumeAndBrightnessGestureState.activeGesture == VerticalGesture.BRIGHTNESS,
                        enter = fadeIn(),
                        exit = fadeOut(),
                    ) {
                        VerticalProgressView(
                            value = brightnessState.brightnessPercentage,
                            icon = painterResource(coreUiR.drawable.ic_brightness),
                        )
                    }
                }
            }

            OverlayShowView(
                player = player,
                overlayView = overlayView,
                videoContentScale = videoZoomAndContentScaleState.videoContentScale,
                onDismiss = { overlayView = null },
                onSelectSubtitleClick = onSelectSubtitleClick,
                onAssTrackSelected = { uriStr ->
                    val uri = if (uriStr.isBlank()) null else uriStr.toUri()
                    selectedAssUri = uri
                    if (uri != null) {
                        player.currentMediaItem?.let { currentMediaItem ->
                            player.replaceMediaItem(
                                player.currentMediaItemIndex,
                                currentMediaItem.copyMediaItem(
                                    subtitleTrackIndex = -1,
                                    selectedSubtitleUri = uri.toString(),
                                ),
                            )
                            viewModel.updateSelectedSubtitle(
                                uri = currentMediaItem.mediaId,
                                subtitleTrackIndex = -1,
                                selectedSubtitleUri = uri,
                            )
                        }
                    }
                },
                selectedAssUri = selectedAssUri,
                onSubtitleOptionEvent = viewModel::onSubtitleOptionEvent,
                onVideoContentScaleChanged = { videoZoomAndContentScaleState.onVideoContentScaleChanged(it) },
            )
        }
    }

    errorState.error?.let { error ->
        AlertDialog(
            onDismissRequest = { },
            title = {
                Text(text = stringResource(coreUiR.string.error_playing_video))
            },
            text = {
                Text(text = error.message ?: stringResource(coreUiR.string.unknown_error))
            },
            confirmButton = {
                if (player.hasNextMediaItem()) {
                    TextButton(
                        onClick = {
                            errorState.dismiss()
                            player.seekToNext()
                            player.play()
                        },
                    ) {
                        Text(text = stringResource(coreUiR.string.play_next_video))
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        errorState.dismiss()
                        onBackClick()
                    },
                ) {
                    Text(text = stringResource(coreUiR.string.exit))
                }
            },
        )
    }

    BackHandler {
        if (overlayView != null) {
            overlayView = null
        } else {
            onBackClick()
        }
    }
}

private fun PlayerPreferences.isAnime4KEnabled(): Boolean = anime4KRestoreMode != Anime4KRestoreMode.OFF ||
    anime4KAutoDownscalePreMode != Anime4KAutoDownscalePreMode.OFF ||
    anime4KUpscaleMode != Anime4KUpscaleMode.OFF ||
    enableAnime4KClampHighlights

@Composable
fun InfoView(
    modifier: Modifier = Modifier,
    info: String,
    supportingInfo: String? = null,
    textStyle: TextStyle = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = info,
            style = textStyle,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        if (!supportingInfo.isNullOrBlank()) {
            Text(
                text = supportingInfo,
                style = MaterialTheme.typography.labelLarge,
                color = Color.White.copy(alpha = 0.85f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
fun ControlsMiddleView(modifier: Modifier = Modifier, player: Player) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(40.dp, alignment = Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PreviousButton(player = player)
        PlayPauseButton(player = player)
        NextButton(player = player)
    }
}

@Composable
fun PlayerControlsView(
    modifier: Modifier = Modifier,
    topView: @Composable () -> Unit,
    middleView: @Composable BoxScope.() -> Unit,
    bottomView: @Composable () -> Unit,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column {
            topView()
            Spacer(modifier = Modifier.weight(1f))
            bottomView()
        }

        middleView()
    }
}

private fun Uri.isAssSubtitleUri(): Boolean {
    val path = lastPathSegment ?: path ?: toString()
    return path.endsWith(".ass", ignoreCase = true) || path.endsWith(".ssa", ignoreCase = true)
}

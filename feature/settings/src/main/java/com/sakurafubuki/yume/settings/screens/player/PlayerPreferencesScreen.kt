package com.sakurafubuki.yume.settings.screens.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sakurafubuki.yume.core.common.extensions.isPipFeatureSupported
import com.sakurafubuki.yume.core.model.Anime4KAutoDownscalePreMode
import com.sakurafubuki.yume.core.model.Anime4KRestoreMode
import com.sakurafubuki.yume.core.model.Anime4KUpscaleMode
import com.sakurafubuki.yume.core.model.ControlButtonsPosition
import com.sakurafubuki.yume.core.model.PlayerPreferences
import com.sakurafubuki.yume.core.model.Resume
import com.sakurafubuki.yume.core.model.ScreenOrientation
import com.sakurafubuki.yume.core.model.VideoEffectType
import com.sakurafubuki.yume.core.ui.FloatingNavigationClearance
import com.sakurafubuki.yume.core.ui.R
import com.sakurafubuki.yume.core.ui.components.ClickablePreferenceItem
import com.sakurafubuki.yume.core.ui.components.ListSectionTitle
import com.sakurafubuki.yume.core.ui.components.NextTopAppBar
import com.sakurafubuki.yume.core.ui.components.PreferenceSlider
import com.sakurafubuki.yume.core.ui.components.PreferenceSwitch
import com.sakurafubuki.yume.core.ui.components.RadioTextButton
import com.sakurafubuki.yume.core.ui.designsystem.NextIcons
import com.sakurafubuki.yume.core.ui.preview.DayNightPreview
import com.sakurafubuki.yume.core.ui.theme.YumeTheme
import com.sakurafubuki.yume.settings.composables.OptionsDialog
import com.sakurafubuki.yume.settings.extensions.name

@Composable
fun PlayerPreferencesScreen(
    onNavigateUp: () -> Unit,
    viewModel: PlayerPreferencesViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    PlayerPreferencesContent(
        uiState = uiState,
        onEvent = viewModel::onEvent,
        onNavigateUp = onNavigateUp,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PlayerPreferencesContent(
    uiState: PlayerPreferencesUiState,
    onEvent: (PlayerPreferencesUiEvent) -> Unit,
    onNavigateUp: () -> Unit = {},
) {
    Scaffold(
        topBar = {
            NextTopAppBar(
                title = stringResource(id = R.string.player_name),
                navigationIcon = {
                    FilledTonalIconButton(onClick = onNavigateUp) {
                        Icon(
                            imageVector = NextIcons.ArrowBack,
                            contentDescription = stringResource(id = R.string.navigate_up),
                        )
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(state = rememberScrollState())
                .padding(innerPadding)
                .padding(start = 16.dp, end = 16.dp, bottom = FloatingNavigationClearance),
        ) {
            ListSectionTitle(text = stringResource(id = R.string.interface_name))
            Column(
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                PreferenceSwitch(
                    title = stringResource(id = R.string.material_you_controls),
                    description = stringResource(id = R.string.material_you_controls_description),
                    icon = NextIcons.Appearance,
                    isChecked = uiState.preferences.useMaterialYouControls,
                    onClick = { onEvent(PlayerPreferencesUiEvent.ToggleUseMaterialYouControls) },
                    isFirstItem = true,
                )
                PreferenceSlider(
                    title = stringResource(R.string.controller_timeout),
                    description = stringResource(R.string.seconds, uiState.preferences.controllerAutoHideTimeout),
                    icon = NextIcons.Timer,
                    value = uiState.preferences.controllerAutoHideTimeout.toFloat(),
                    valueRange = 1.0f..60.0f,
                    onValueChange = { onEvent(PlayerPreferencesUiEvent.UpdateControlAutoHideTimeout(it.toInt())) },
                    isLastItem = true,
                    trailingContent = {
                        FilledIconButton(onClick = { onEvent(PlayerPreferencesUiEvent.UpdateControlAutoHideTimeout(PlayerPreferences.DEFAULT_CONTROLLER_AUTO_HIDE_TIMEOUT)) }) {
                            Icon(
                                imageVector = NextIcons.History,
                                contentDescription = stringResource(id = R.string.reset_controller_timeout),
                            )
                        }
                    },
                )
            }

            ListSectionTitle(text = stringResource(id = R.string.effects_name))
            Column(
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                EffectItems(
                    prefs = uiState.preferences,
                    onEvent = onEvent,
                )
            }

            ListSectionTitle(text = stringResource(id = R.string.playback))
            Column(
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                ClickablePreferenceItem(
                    title = stringResource(id = R.string.resume),
                    description = stringResource(id = R.string.resume_description),
                    icon = NextIcons.Resume,
                    onClick = { onEvent(PlayerPreferencesUiEvent.ShowDialog(PlayerPreferenceDialog.ResumeDialog)) },
                    isFirstItem = true,
                )
                PreferenceSlider(
                    title = stringResource(id = R.string.default_playback_speed),
                    description = uiState.preferences.defaultPlaybackSpeed.toString(),
                    icon = NextIcons.Speed,
                    value = uiState.preferences.defaultPlaybackSpeed,
                    valueRange = 0.2f..4.0f,
                    onValueChange = { onEvent(PlayerPreferencesUiEvent.UpdateDefaultPlaybackSpeed(it)) },
                    trailingContent = {
                        FilledIconButton(onClick = { onEvent(PlayerPreferencesUiEvent.UpdateDefaultPlaybackSpeed(1f)) }) {
                            Icon(
                                imageVector = NextIcons.History,
                                contentDescription = stringResource(id = R.string.reset_default_playback_speed),
                            )
                        }
                    },
                )
                PreferenceSwitch(
                    title = stringResource(id = R.string.autoplay_settings),
                    description = stringResource(
                        id = R.string.autoplay_settings_description,
                    ),
                    icon = NextIcons.Player,
                    isChecked = uiState.preferences.autoplay,
                    onClick = { onEvent(PlayerPreferencesUiEvent.ToggleAutoplay) },
                )
                if (LocalContext.current.isPipFeatureSupported) {
                    PreferenceSwitch(
                        title = stringResource(id = R.string.pip_settings),
                        description = stringResource(
                            id = R.string.pip_settings_description,
                        ),
                        icon = NextIcons.Pip,
                        isChecked = uiState.preferences.autoPip,
                        onClick = { onEvent(PlayerPreferencesUiEvent.ToggleAutoPip) },
                    )
                }
                PreferenceSwitch(
                    title = stringResource(id = R.string.background_play),
                    description = stringResource(
                        id = R.string.background_play_description,
                    ),
                    icon = NextIcons.Headset,
                    isChecked = uiState.preferences.autoBackgroundPlay,
                    onClick = { onEvent(PlayerPreferencesUiEvent.ToggleAutoBackgroundPlay) },
                )
                PreferenceSwitch(
                    title = stringResource(id = R.string.remember_brightness_level),
                    description = stringResource(
                        id = R.string.remember_brightness_level_description,
                    ),
                    icon = NextIcons.Brightness,
                    isChecked = uiState.preferences.rememberPlayerBrightness,
                    onClick = { onEvent(PlayerPreferencesUiEvent.ToggleRememberBrightnessLevel) },
                )
                PreferenceSwitch(
                    title = stringResource(id = R.string.remember_selections),
                    description = stringResource(id = R.string.remember_selections_description),
                    icon = NextIcons.Selection,
                    isChecked = uiState.preferences.rememberSelections,
                    onClick = { onEvent(PlayerPreferencesUiEvent.ToggleRememberSelections) },
                )
                ClickablePreferenceItem(
                    title = stringResource(id = R.string.player_screen_orientation),
                    description = uiState.preferences.playerScreenOrientation.name(),
                    icon = NextIcons.Rotation,
                    onClick = {
                        onEvent(PlayerPreferencesUiEvent.ShowDialog(PlayerPreferenceDialog.PlayerScreenOrientationDialog))
                    },
                    isLastItem = true,
                )
            }
        }

        uiState.showDialog?.let { showDialog ->
            when (showDialog) {
                PlayerPreferenceDialog.ResumeDialog -> {
                    OptionsDialog(
                        text = stringResource(id = R.string.resume),
                        onDismissClick = { onEvent(PlayerPreferencesUiEvent.ShowDialog(null)) },
                    ) {
                        items(Resume.entries.toTypedArray()) {
                            RadioTextButton(
                                text = it.name(),
                                selected = (it == uiState.preferences.resume),
                                onClick = {
                                    onEvent(PlayerPreferencesUiEvent.UpdatePlaybackResume(it))
                                    onEvent(PlayerPreferencesUiEvent.ShowDialog(null))
                                },
                            )
                        }
                    }
                }

                PlayerPreferenceDialog.PlayerScreenOrientationDialog -> {
                    OptionsDialog(
                        text = stringResource(id = R.string.player_screen_orientation),
                        onDismissClick = { onEvent(PlayerPreferencesUiEvent.ShowDialog(null)) },
                    ) {
                        items(ScreenOrientation.entries.toTypedArray()) {
                            RadioTextButton(
                                text = it.name(),
                                selected = it == uiState.preferences.playerScreenOrientation,
                                onClick = {
                                    onEvent(PlayerPreferencesUiEvent.UpdatePreferredPlayerOrientation(it))
                                    onEvent(PlayerPreferencesUiEvent.ShowDialog(null))
                                },
                            )
                        }
                    }
                }

                PlayerPreferenceDialog.ControlButtonsDialog -> {
                    OptionsDialog(
                        text = stringResource(id = R.string.control_buttons_alignment),
                        onDismissClick = { onEvent(PlayerPreferencesUiEvent.ShowDialog(null)) },
                    ) {
                        items(ControlButtonsPosition.entries.toTypedArray()) {
                            RadioTextButton(
                                text = it.name(),
                                selected = it == uiState.preferences.controlButtonsPosition,
                                onClick = {
                                    onEvent(PlayerPreferencesUiEvent.UpdatePreferredControlButtonsPosition(it))
                                    onEvent(PlayerPreferencesUiEvent.ShowDialog(null))
                                },
                            )
                        }
                    }
                }

                PlayerPreferenceDialog.Anime4KRestoreDialog -> {
                    OptionsDialog(
                        text = stringResource(id = R.string.anime4k_restore_title),
                        onDismissClick = { onEvent(PlayerPreferencesUiEvent.ShowDialog(null)) },
                    ) {
                        items(Anime4KRestoreMode.entries.toTypedArray()) {
                            RadioTextButton(
                                text = it.name(),
                                selected = it == uiState.preferences.anime4KRestoreMode,
                                onClick = {
                                    onEvent(PlayerPreferencesUiEvent.UpdateAnime4KRestoreMode(it))
                                    onEvent(PlayerPreferencesUiEvent.ShowDialog(null))
                                },
                            )
                        }
                    }
                }

                PlayerPreferenceDialog.Anime4KUpscaleDialog -> {
                    OptionsDialog(
                        text = stringResource(id = R.string.anime4k_upscale_title),
                        onDismissClick = { onEvent(PlayerPreferencesUiEvent.ShowDialog(null)) },
                    ) {
                        items(Anime4KUpscaleMode.entries.toTypedArray()) {
                            RadioTextButton(
                                text = it.name(),
                                selected = it == uiState.preferences.anime4KUpscaleMode,
                                onClick = {
                                    onEvent(PlayerPreferencesUiEvent.UpdateAnime4KUpscaleMode(it))
                                    onEvent(PlayerPreferencesUiEvent.ShowDialog(null))
                                },
                            )
                        }
                    }
                }

                PlayerPreferenceDialog.Anime4KAutoDownscalePreDialog -> {
                    OptionsDialog(
                        text = stringResource(id = R.string.anime4k_autodownscalepre_title),
                        onDismissClick = { onEvent(PlayerPreferencesUiEvent.ShowDialog(null)) },
                    ) {
                        items(Anime4KAutoDownscalePreMode.entries.toTypedArray()) {
                            RadioTextButton(
                                text = it.name(),
                                selected = it == uiState.preferences.anime4KAutoDownscalePreMode,
                                onClick = {
                                    onEvent(PlayerPreferencesUiEvent.UpdateAnime4KAutoDownscalePreMode(it))
                                    onEvent(PlayerPreferencesUiEvent.ShowDialog(null))
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EffectItems(
    prefs: PlayerPreferences,
    onEvent: (PlayerPreferencesUiEvent) -> Unit,
) {
    val types = prefs.videoEffectsOrder
    types.forEachIndexed { index, type ->
        val isFirst = index == 0
        val isLast = index == types.lastIndex
        when (type) {
            VideoEffectType.AUTODOWNSCALEPRE -> ClickablePreferenceItem(
                title = stringResource(id = R.string.anime4k_autodownscalepre_title),
                description = prefs.anime4KAutoDownscalePreMode.name(),
                icon = NextIcons.AspectRatio,
                onClick = { onEvent(PlayerPreferencesUiEvent.ShowDialog(PlayerPreferenceDialog.Anime4KAutoDownscalePreDialog)) },
                isFirstItem = isFirst,
                isLastItem = isLast,
            )
            VideoEffectType.UPSCALE -> ClickablePreferenceItem(
                title = stringResource(id = R.string.anime4k_upscale_title),
                description = prefs.anime4KUpscaleMode.name(),
                icon = NextIcons.HighQuality,
                onClick = { onEvent(PlayerPreferencesUiEvent.ShowDialog(PlayerPreferenceDialog.Anime4KUpscaleDialog)) },
                isFirstItem = isFirst,
                isLastItem = isLast,
            )
            VideoEffectType.RESTORE -> ClickablePreferenceItem(
                title = stringResource(id = R.string.anime4k_restore_title),
                description = prefs.anime4KRestoreMode.name(),
                icon = NextIcons.AutoFix,
                onClick = { onEvent(PlayerPreferencesUiEvent.ShowDialog(PlayerPreferenceDialog.Anime4KRestoreDialog)) },
                isFirstItem = isFirst,
                isLastItem = isLast,
            )
            VideoEffectType.DEBAND -> PreferenceSwitch(
                title = stringResource(id = R.string.deband_title),
                description = stringResource(id = R.string.deband_description),
                icon = NextIcons.Gradient,
                isChecked = prefs.enableDeband,
                onClick = { onEvent(PlayerPreferencesUiEvent.ToggleDeband) },
                isFirstItem = isFirst,
                isLastItem = isLast,
            )
            VideoEffectType.CLAMP_HIGHLIGHTS -> PreferenceSwitch(
                title = stringResource(id = R.string.anime4k_clamp_highlights_title),
                description = stringResource(id = R.string.anime4k_clamp_highlights_description),
                icon = NextIcons.Contrast,
                isChecked = prefs.enableAnime4KClampHighlights,
                onClick = { onEvent(PlayerPreferencesUiEvent.ToggleAnime4KClampHighlights) },
                isFirstItem = isFirst,
                isLastItem = isLast,
            )
            VideoEffectType.DITHER -> PreferenceSwitch(
                title = stringResource(id = R.string.dither_title),
                description = stringResource(id = R.string.dither_description),
                icon = NextIcons.PhotoFilter,
                isChecked = prefs.enableDither,
                onClick = { onEvent(PlayerPreferencesUiEvent.ToggleDither) },
                isFirstItem = isFirst,
                isLastItem = isLast,
            )
        }
    }
    PreferenceSwitch(
        title = stringResource(id = R.string.refresh_rate_match_title),
        description = stringResource(id = R.string.refresh_rate_match_description),
        icon = NextIcons.Tv,
        isChecked = prefs.enableRefreshRateMatch,
        onClick = { onEvent(PlayerPreferencesUiEvent.ToggleRefreshRateMatch) },
        isLastItem = true,
    )
}

@DayNightPreview
@Composable
private fun PlayerPreferencesScreenPreview() {
    YumeTheme {
        PlayerPreferencesContent(
            uiState = PlayerPreferencesUiState(),
            onEvent = {},
        )
    }
}

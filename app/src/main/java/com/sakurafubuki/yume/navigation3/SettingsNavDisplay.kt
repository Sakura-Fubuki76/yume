package com.sakurafubuki.yume.navigation3

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.sakurafubuki.yume.settings.Setting
import com.sakurafubuki.yume.settings.SettingsScreen
import com.sakurafubuki.yume.settings.screens.about.AboutPreferencesScreen
import com.sakurafubuki.yume.settings.screens.about.LibrariesScreen
import com.sakurafubuki.yume.settings.screens.appearance.AppearancePreferencesScreen
import com.sakurafubuki.yume.settings.screens.audio.AudioPreferencesScreen
import com.sakurafubuki.yume.settings.screens.decoder.DecoderPreferencesScreen
import com.sakurafubuki.yume.settings.screens.general.GeneralPreferencesScreen
import com.sakurafubuki.yume.settings.screens.gesture.GesturePreferencesScreen
import com.sakurafubuki.yume.settings.screens.medialibrary.FolderPreferencesScreen
import com.sakurafubuki.yume.settings.screens.medialibrary.MediaLibraryPreferencesScreen
import com.sakurafubuki.yume.settings.screens.performance.PerformancePreferencesScreen
import com.sakurafubuki.yume.settings.screens.player.PlayerPreferencesScreen
import com.sakurafubuki.yume.settings.screens.subtitle.SubtitlePreferencesScreen
import com.sakurafubuki.yume.settings.screens.thumbnail.ThumbnailPreferencesScreen

@Composable
fun SettingsNavDisplay(
    backStack: MutableList<NavKey>,
    modifier: Modifier = Modifier,
) {
    val navigateUp: () -> Unit = { backStack.popOrFalse() }
    val transitionSpecs = yumeNavTransitionSpecs()

    NavDisplay(
        backStack = backStack,
        modifier = modifier,
        onBack = navigateUp,
        entryDecorators = rememberYumeNavEntryDecorators(),
        transitionSpec = transitionSpecs.transitionSpec,
        popTransitionSpec = transitionSpecs.popTransitionSpec,
        predictivePopTransitionSpec = transitionSpecs.predictivePopTransitionSpec,
        entryProvider = entryProvider {
            entry<SettingsHomeKey> {
                SettingsScreen(
                    onNavigateUp = null,
                    onItemClick = { setting -> backStack.pushSingleTop(setting.toSettingsNavKey()) },
                )
            }
            entry<AppearancePreferencesKey> {
                AppearancePreferencesScreen(onNavigateUp = navigateUp)
            }
            entry<MediaLibraryPreferencesKey> {
                MediaLibraryPreferencesScreen(
                    onNavigateUp = navigateUp,
                    onFolderSettingClick = { backStack.pushSingleTop(FolderPreferencesKey) },
                    onThumbnailSettingClick = { backStack.pushSingleTop(ThumbnailPreferencesKey) },
                )
            }
            entry<ThumbnailPreferencesKey> {
                ThumbnailPreferencesScreen(onNavigateUp = navigateUp)
            }
            entry<FolderPreferencesKey> {
                FolderPreferencesScreen(onNavigateUp = navigateUp)
            }
            entry<PlayerPreferencesKey> {
                PlayerPreferencesScreen(onNavigateUp = navigateUp)
            }
            entry<GesturePreferencesKey> {
                GesturePreferencesScreen(onNavigateUp = navigateUp)
            }
            entry<DecoderPreferencesKey> {
                DecoderPreferencesScreen(onNavigateUp = navigateUp)
            }
            entry<AudioPreferencesKey> {
                AudioPreferencesScreen(onNavigateUp = navigateUp)
            }
            entry<SubtitlePreferencesKey> {
                SubtitlePreferencesScreen(onNavigateUp = navigateUp)
            }
            entry<PerformancePreferencesKey> {
                PerformancePreferencesScreen(onNavigateUp = navigateUp)
            }
            entry<GeneralPreferencesKey> {
                GeneralPreferencesScreen(onNavigateUp = navigateUp)
            }
            entry<AboutPreferencesKey> {
                AboutPreferencesScreen(
                    onNavigateUp = navigateUp,
                    onLibrariesClick = { backStack.pushSingleTop(LibrariesKey) },
                )
            }
            entry<LibrariesKey> {
                LibrariesScreen(onNavigateUp = navigateUp)
            }
        },
    )
}

private fun Setting.toSettingsNavKey(): SettingsNavKey = when (this) {
    Setting.APPEARANCE -> AppearancePreferencesKey
    Setting.MEDIA_LIBRARY -> MediaLibraryPreferencesKey
    Setting.PLAYER -> PlayerPreferencesKey
    Setting.GESTURES -> GesturePreferencesKey
    Setting.DECODER -> DecoderPreferencesKey
    Setting.AUDIO -> AudioPreferencesKey
    Setting.SUBTITLE -> SubtitlePreferencesKey
    Setting.PERFORMANCE -> PerformancePreferencesKey
    Setting.GENERAL -> GeneralPreferencesKey
    Setting.ABOUT -> AboutPreferencesKey
}

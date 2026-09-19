package com.sakurafubuki.yume.navigation

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.navigation3.runtime.NavKey
import com.sakurafubuki.yume.navigation3.ImageNavDisplay
import com.sakurafubuki.yume.navigation3.MediaNavDisplay
import com.sakurafubuki.yume.navigation3.SettingsNavDisplay
import com.sakurafubuki.yume.navigation3.rememberYumeNavEntryDecorators

@Composable
fun AppNavHost(
    context: Context,
    pagerState: PagerState,
    mediaBackStack: MutableList<NavKey>,
    imageBackStack: MutableList<NavKey>,
    settingsBackStack: MutableList<NavKey>,
    onNavigateToSettingsTab: () -> Unit,
    userScrollEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val tabState = rememberSaveableStateHolder()
    // Keep decorators outside the page content so tab switches retain entry state/VMs.
    val mediaDecorators = rememberYumeNavEntryDecorators()
    val imageDecorators = rememberYumeNavEntryDecorators()
    val settingsDecorators = rememberYumeNavEntryDecorators()
    HorizontalPager(
        state = pagerState,
        userScrollEnabled = userScrollEnabled,
        modifier = modifier.clipToBounds(),
    ) { page ->
        tabState.SaveableStateProvider(key = pageToScreen(page).route) {
            when (pageToScreen(page)) {
                Screen.Video -> MediaNavDisplay(
                    context = context,
                    backStack = mediaBackStack,
                    onNavigateToSettings = onNavigateToSettingsTab,
                    modifier = Modifier.fillMaxSize(),
                    entryDecorators = mediaDecorators,
                )
                Screen.Image -> ImageNavDisplay(
                    backStack = imageBackStack,
                    onNavigateToSettings = onNavigateToSettingsTab,
                    modifier = Modifier.fillMaxSize(),
                    entryDecorators = imageDecorators,
                )
                Screen.Settings -> SettingsNavDisplay(
                    backStack = settingsBackStack,
                    modifier = Modifier.fillMaxSize(),
                    entryDecorators = settingsDecorators,
                )
            }
        }
    }
}

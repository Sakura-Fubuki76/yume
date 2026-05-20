package com.sakurafubuki.yume.navigation

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.navigation3.runtime.NavKey
import com.sakurafubuki.yume.navigation3.ImageNavDisplay
import com.sakurafubuki.yume.navigation3.MediaNavDisplay
import com.sakurafubuki.yume.navigation3.SettingsNavDisplay

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
    HorizontalPager(
        state = pagerState,
        userScrollEnabled = userScrollEnabled,
        modifier = modifier,
    ) { page ->
        when (page) {
            0 -> {
                MediaNavDisplay(
                    context = context,
                    backStack = mediaBackStack,
                    onNavigateToSettings = onNavigateToSettingsTab,
                    modifier = Modifier
                        .fillMaxSize()
                        .clipToBounds(),
                )
            }

            1 -> {
                ImageNavDisplay(
                    backStack = imageBackStack,
                    onNavigateToSettings = onNavigateToSettingsTab,
                    modifier = Modifier
                        .fillMaxSize()
                        .clipToBounds(),
                )
            }

            else -> {
                SettingsNavDisplay(
                    backStack = settingsBackStack,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

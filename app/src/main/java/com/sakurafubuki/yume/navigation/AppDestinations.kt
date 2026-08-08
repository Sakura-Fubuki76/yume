package com.sakurafubuki.yume.navigation

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import com.sakurafubuki.yume.R
import com.sakurafubuki.yume.core.ui.designsystem.NextIcons

data class AppDestination(
    val screen: Screen,
    val icon: ImageVector,
    @param:StringRes val labelRes: Int,
)

val appDestinations: List<AppDestination> = listOf(
    AppDestination(
        screen = Screen.Video,
        icon = NextIcons.Video,
        labelRes = R.string.bottom_nav_video,
    ),
    AppDestination(
        screen = Screen.Image,
        icon = NextIcons.Image,
        labelRes = R.string.bottom_nav_images,
    ),
    AppDestination(
        screen = Screen.Settings,
        icon = NextIcons.Settings,
        labelRes = R.string.bottom_nav_settings,
    ),
)

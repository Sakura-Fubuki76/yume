package com.sakurafubuki.yume.navigation

sealed class Screen(val route: String) {
    data object Video : Screen("video")
    data object Image : Screen("image")
    data object Settings : Screen("settings")
}

fun pageToScreen(page: Int): Screen = when (page) {
    0 -> Screen.Video
    1 -> Screen.Image
    else -> Screen.Settings
}

fun screenToPage(screen: Screen): Int = when (screen) {
    Screen.Video -> 0
    Screen.Image -> 1
    Screen.Settings -> 2
}

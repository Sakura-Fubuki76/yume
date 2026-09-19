package com.sakurafubuki.yume.navigation

sealed class Screen(val route: String) {
    data object Video : Screen("video")
    data object Image : Screen("image")
    data object Recent : Screen("recent")
    data object Settings : Screen("settings")
}

fun pageToScreen(page: Int): Screen = when (page) {
    0 -> Screen.Video
    1 -> Screen.Recent
    2 -> Screen.Image
    else -> Screen.Settings
}

fun screenToPage(screen: Screen): Int = when (screen) {
    Screen.Video -> 0
    Screen.Recent -> 1
    Screen.Image -> 2
    Screen.Settings -> 3
}

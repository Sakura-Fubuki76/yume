package com.sakurafubuki.yume.navigation

sealed class Screen(val route: String) {
    data object Video : Screen("video")
    data object Image : Screen("image")
    data object Settings : Screen("settings")
}

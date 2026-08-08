package com.sakurafubuki.yume.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FlexibleBottomAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.window.core.layout.WindowSizeClass

private enum class NavigationMode {
    COMPACT,
    MEDIUM,
    EXPANDED,
}

private fun WindowSizeClass.toNavigationMode(): NavigationMode = when {
    isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND) -> NavigationMode.EXPANDED
    isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND) -> NavigationMode.MEDIUM
    else -> NavigationMode.COMPACT
}

/**
 * 只渲染当前窗口宽度类对应的导航条。
 * [alpha] 用于 ImageViewer 全屏覆盖时的渐隐；三种形态共用同一份 [appDestinations]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppAdaptiveNavBar(
    selectedScreen: Screen,
    onNavigate: (Screen) -> Unit,
    windowSizeClass: WindowSizeClass,
    alpha: Float = 1f,
    modifier: Modifier = Modifier,
) {
    when (windowSizeClass.toNavigationMode()) {
        NavigationMode.COMPACT -> {
            Box(modifier = modifier.graphicsLayer { this.alpha = alpha }) {
                FlexibleBottomAppBar {
                    appDestinations.forEach { item ->
                        val label = stringResource(item.labelRes)
                        NavigationBarItem(
                            selected = selectedScreen.route == item.screen.route,
                            onClick = { onNavigate(item.screen) },
                            icon = { Icon(imageVector = item.icon, contentDescription = null) },
                            label = { Text(text = label) },
                            alwaysShowLabel = true,
                            colors = NavigationBarItemDefaults.colors(),
                        )
                    }
                }
            }
        }

        NavigationMode.MEDIUM -> {
            NavigationRail(
                modifier = modifier.graphicsLayer { this.alpha = alpha },
            ) {
                appDestinations.forEach { item ->
                    NavigationRailItem(
                        selected = selectedScreen.route == item.screen.route,
                        onClick = { onNavigate(item.screen) },
                        icon = { Icon(imageVector = item.icon, contentDescription = null) },
                        label = { Text(text = stringResource(item.labelRes)) },
                        alwaysShowLabel = true,
                    )
                }
            }
        }

        NavigationMode.EXPANDED -> {
            PermanentDrawerSheet(
                modifier = modifier.graphicsLayer { this.alpha = alpha },
            ) {
                appDestinations.forEach { item ->
                    NavigationDrawerItem(
                        label = { Text(text = stringResource(item.labelRes)) },
                        selected = selectedScreen.route == item.screen.route,
                        onClick = { onNavigate(item.screen) },
                        icon = { Icon(imageVector = item.icon, contentDescription = null) },
                    )
                }
            }
        }
    }
}

/**
 * 顶层布局容器：按窗口宽度类提供不同的导航宿主结构。
 * - COMPACT：FlexibleBottomAppBar 作为 bottomBar，内容区为 Scaffold 内容。
 * - MEDIUM：NavigationRail 左侧，内容区占剩余宽度。
 * - EXPANDED：PermanentNavigationDrawer 左侧常驻抽屉。
 *
 * [barAlpha] 用于 ImageViewer 全屏覆盖时渐隐（保持挂载以避免布局抖动）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppAdaptiveNavigationContainer(
    selectedScreen: Screen,
    onNavigate: (Screen) -> Unit,
    windowSizeClass: WindowSizeClass,
    modifier: Modifier = Modifier,
    barAlpha: Float = 1f,
    content: @Composable BoxScope.() -> Unit,
) {
    when (windowSizeClass.toNavigationMode()) {
        NavigationMode.COMPACT -> {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                modifier = modifier,
                bottomBar = {
                    AppAdaptiveNavBar(
                        selectedScreen = selectedScreen,
                        onNavigate = onNavigate,
                        windowSizeClass = windowSizeClass,
                        alpha = barAlpha,
                    )
                },
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .padding(innerPadding)
                        .fillMaxSize(),
                ) {
                    content()
                }
            }
        }

        NavigationMode.MEDIUM -> {
            Row(modifier = modifier.fillMaxSize()) {
                AppAdaptiveNavBar(
                    selectedScreen = selectedScreen,
                    onNavigate = onNavigate,
                    windowSizeClass = windowSizeClass,
                    alpha = barAlpha,
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                ) {
                    content()
                }
            }
        }

        NavigationMode.EXPANDED -> {
            PermanentNavigationDrawer(
                modifier = modifier,
                drawerContent = {
                    AppAdaptiveNavBar(
                        selectedScreen = selectedScreen,
                        onNavigate = onNavigate,
                        windowSizeClass = windowSizeClass,
                        alpha = barAlpha,
                    )
                },
                content = {
                    Box(modifier = Modifier.fillMaxSize()) {
                        content()
                    }
                },
            )
        }
    }
}

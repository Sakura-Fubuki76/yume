package com.sakurafubuki.yume.navigation3

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.sakurafubuki.yume.feature.imagebrowser.ui.ImageBrowserRoute
import com.sakurafubuki.yume.feature.imagebrowser.ui.ImageViewerStore

@Composable
fun ImageNavDisplay(
    backStack: MutableList<NavKey>,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        val transitionSpecs = yumeNavTransitionSpecs()

        NavDisplay(
            backStack = backStack,
            modifier = Modifier.fillMaxSize(),
            onBack = { backStack.popOrFalse() },
            entryDecorators = rememberYumeNavEntryDecorators(),
            transitionSpec = transitionSpecs.transitionSpec,
            popTransitionSpec = transitionSpecs.popTransitionSpec,
            predictivePopTransitionSpec = transitionSpecs.predictivePopTransitionSpec,
            entryProvider = entryProvider {
                entry<ImageBrowserKey> { key ->
                    ImageBrowserRoute(
                        routePath = key.path,
                        routeCloudServerId = key.cloudServerId,
                        onNavigateToSettings = onNavigateToSettings,
                        onNavigateToPath = { path, cloudServerId ->
                            backStack.pushSingleTop(imageBrowserKey(path = path, cloudServerId = cloudServerId))
                        },
                        onNavigateBackFromPath = { fallbackPath, cloudServerId ->
                            val popped = backStack.popOrFalse()
                            if (!popped) {
                                backStack.pushSingleTop(
                                    imageBrowserKey(
                                        path = fallbackPath,
                                        cloudServerId = cloudServerId,
                                    ),
                                )
                            }
                        },
                        onImageClick = ImageViewerStore::showViewer,
                    )
                }
            },
        )
    }
}

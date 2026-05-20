package com.sakurafubuki.yume.navigation3

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.sakurafubuki.yume.feature.player.PlayerActivity
import com.sakurafubuki.yume.feature.player.utils.PlayerApi
import com.sakurafubuki.yume.feature.videopicker.screens.mediapicker.MediaPickerRoute
import com.sakurafubuki.yume.feature.videopicker.screens.search.SearchRoute

@Composable
fun MediaNavDisplay(
    context: Context,
    backStack: MutableList<NavKey>,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val transitionSpecs = yumeNavTransitionSpecs()

    NavDisplay(
        backStack = backStack,
        modifier = modifier.fillMaxSize(),
        onBack = { backStack.popOrFalse() },
        entryDecorators = rememberYumeNavEntryDecorators(),
        transitionSpec = transitionSpecs.transitionSpec,
        popTransitionSpec = transitionSpecs.popTransitionSpec,
        predictivePopTransitionSpec = transitionSpecs.predictivePopTransitionSpec,
        entryProvider = entryProvider {
            entry<MediaPickerKey> { key ->
                MediaPickerRoute(
                    routeFolderId = key.folderId,
                    routeCloudPath = key.cloudPath,
                    routeCloudServerId = key.cloudServerId,
                    onPlayVideo = context::playVideo,
                    onPlayVideos = context::playVideos,
                    onNavigateUp = { backStack.popOrFalse() },
                    onFolderClick = { folderPath ->
                        backStack.pushSingleTop(mediaPickerKey(folderId = folderPath))
                    },
                    onCloudFolderClick = { cloudPath, cloudServerId ->
                        backStack.pushSingleTop(
                            mediaPickerKey(
                                cloudPath = cloudPath,
                                cloudServerId = cloudServerId,
                            ),
                        )
                    },
                    onCloudBackFromPath = { fallbackPath, cloudServerId ->
                        val popped = backStack.popOrFalse()
                        if (!popped) {
                            backStack.pushSingleTop(
                                mediaPickerKey(
                                    cloudPath = fallbackPath,
                                    cloudServerId = cloudServerId,
                                ),
                            )
                        }
                    },
                    onSettingsClick = onNavigateToSettings,
                    onSearchClick = { backStack.pushSingleTop(SearchKey) },
                )
            }
            entry<SearchKey> {
                SearchRoute(
                    onPlayVideo = context::playVideo,
                    onNavigateUp = { backStack.popOrFalse() },
                    onFolderClick = { folderPath ->
                        backStack.pushSingleTop(mediaPickerKey(folderId = folderPath))
                    },
                )
            }
        },
    )
}

private fun Context.playVideo(uri: Uri) {
    val intent = Intent(this, PlayerActivity::class.java).apply {
        action = Intent.ACTION_VIEW
        data = uri
    }
    startActivity(intent)
}

private fun Context.playVideos(uris: List<Uri>) {
    if (uris.isEmpty()) return
    val intent = Intent(this, PlayerActivity::class.java).apply {
        action = Intent.ACTION_VIEW
        data = uris.first()
        putParcelableArrayListExtra(PlayerApi.API_PLAYLIST, ArrayList(uris))
    }
    startActivity(intent)
}

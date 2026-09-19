package com.sakurafubuki.yume.navigation3

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.sakurafubuki.yume.feature.player.PlayerActivity
import com.sakurafubuki.yume.feature.videopicker.screens.recent.RecentRoute

@Composable
fun RecentNavDisplay(
    context: Context,
    backStack: MutableList<NavKey>,
    modifier: Modifier = Modifier,
    entryDecorators: List<NavEntryDecorator<NavKey>> = rememberYumeNavEntryDecorators(),
) {
    val transitionSpecs = yumeNavTransitionSpecs()

    NavDisplay(
        backStack = backStack,
        modifier = modifier.fillMaxSize(),
        onBack = { backStack.popOrFalse() },
        entryDecorators = entryDecorators,
        transitionSpec = transitionSpecs.transitionSpec,
        popTransitionSpec = transitionSpecs.popTransitionSpec,
        predictivePopTransitionSpec = transitionSpecs.predictivePopTransitionSpec,
        entryProvider = entryProvider {
            entry<RecentHomeKey> {
                RecentRoute(
                    onPlayVideo = context::playVideo,
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

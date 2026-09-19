package com.sakurafubuki.yume.feature.videopicker.screens.recent

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sakurafubuki.yume.core.model.Video
import com.sakurafubuki.yume.core.ui.FloatingNavigationClearance
import com.sakurafubuki.yume.core.ui.R
import com.sakurafubuki.yume.core.ui.components.CancelButton
import com.sakurafubuki.yume.core.ui.components.NextDialog
import com.sakurafubuki.yume.core.ui.components.NextTopAppBar
import com.sakurafubuki.yume.core.ui.designsystem.NextIcons
import com.sakurafubuki.yume.feature.videopicker.composables.CenterCircularProgressBar
import com.sakurafubuki.yume.feature.videopicker.composables.VideoItem

@Composable
fun RecentRoute(
    viewModel: RecentViewModel = hiltViewModel(),
    onPlayVideo: (Uri) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    RecentScreen(
        uiState = uiState,
        onPlayVideo = onPlayVideo,
        onClearRecentlyPlayed = viewModel::onClearRecentlyPlayed,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun RecentScreen(
    uiState: RecentUiState,
    onPlayVideo: (Uri) -> Unit,
    onClearRecentlyPlayed: (String) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    var pendingDelete by remember { mutableStateOf<Video?>(null) }

    Scaffold(
        topBar = {
            NextTopAppBar(
                title = stringResource(R.string.recent_playback_title),
            )
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) { scaffoldPadding ->
        val contentPadding = PaddingValues(
            top = scaffoldPadding.calculateTopPadding(),
            start = 8.dp,
            end = 8.dp,
            // Extra scroll range so the last row clears the floating bar.
            bottom = FloatingNavigationClearance,
        )

        when {
            !uiState.loaded -> CenterCircularProgressBar()

            uiState.videos.isEmpty() -> RecentEmptyView(contentPadding = contentPadding)

            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(1),
                modifier = Modifier.fillMaxSize(),
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(
                    items = uiState.videos,
                    key = { _, video -> video.uriString },
                ) { index, video ->
                    VideoItem(
                        video = video,
                        isRecentlyPlayedVideo = false,
                        preferences = uiState.preferences,
                        isFirstItem = index == 0,
                        isLastItem = index == uiState.videos.lastIndex,
                        onClick = { onPlayVideo(video.uriString.toUri()) },
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            pendingDelete = video
                        },
                    )
                }
            }
        }
    }

    pendingDelete?.let { video ->
        NextDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.remove_recent_playback)) },
            content = {
                Text(
                    text = stringResource(R.string.remove_recent_playback_confirmation, video.displayName),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onClearRecentlyPlayed(video.uriString)
                    pendingDelete = null
                }) {
                    Text(stringResource(R.string.remove))
                }
            },
            dismissButton = { CancelButton(onClick = { pendingDelete = null }) },
        )
    }
}

@Composable
private fun RecentEmptyView(contentPadding: PaddingValues) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(
                horizontal = 24.dp,
                vertical = 40.dp,
            ),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = NextIcons.History,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(40.dp),
            )
        }
        Spacer(modifier = Modifier.size(16.dp))
        Text(
            text = stringResource(R.string.recent_playback_empty),
            style = MaterialTheme.typography.titleLargeEmphasized,
        )
    }
}

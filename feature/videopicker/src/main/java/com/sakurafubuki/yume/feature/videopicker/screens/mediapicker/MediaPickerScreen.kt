package com.sakurafubuki.yume.feature.videopicker.screens.mediapicker

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.ToggleFloatingActionButtonDefaults.animateIcon
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.accompanist.permissions.shouldShowRationale
import com.sakurafubuki.yume.core.common.Logger
import com.sakurafubuki.yume.core.common.VideoThumbnailStore
import com.sakurafubuki.yume.core.common.storagePermissions
import com.sakurafubuki.yume.core.media.services.MediaService
import com.sakurafubuki.yume.core.model.ApplicationPreferences
import com.sakurafubuki.yume.core.model.Folder
import com.sakurafubuki.yume.core.model.MediaLayoutMode
import com.sakurafubuki.yume.core.model.MediaViewMode
import com.sakurafubuki.yume.core.model.Video
import com.sakurafubuki.yume.core.ui.R
import com.sakurafubuki.yume.core.ui.base.DataState
import com.sakurafubuki.yume.core.ui.components.CancelButton
import com.sakurafubuki.yume.core.ui.components.DoneButton
import com.sakurafubuki.yume.core.ui.components.NextDialog
import com.sakurafubuki.yume.core.ui.components.NextTopAppBar
import com.sakurafubuki.yume.core.ui.composables.PermissionMissingView
import com.sakurafubuki.yume.core.ui.designsystem.NextIcons
import com.sakurafubuki.yume.core.ui.extensions.copy
import com.sakurafubuki.yume.core.ui.preview.DayNightPreview
import com.sakurafubuki.yume.core.ui.preview.VideoPickerPreviewParameterProvider
import com.sakurafubuki.yume.core.ui.theme.YumeTheme
import com.sakurafubuki.yume.feature.videopicker.composables.CenterCircularProgressBar
import com.sakurafubuki.yume.feature.videopicker.composables.MediaView
import com.sakurafubuki.yume.feature.videopicker.composables.NoVideosFound
import com.sakurafubuki.yume.feature.videopicker.composables.QuickSettingsDialog
import com.sakurafubuki.yume.feature.videopicker.composables.RenameDialog
import com.sakurafubuki.yume.feature.videopicker.composables.TextIconToggleButton
import com.sakurafubuki.yume.feature.videopicker.composables.VideoInfoDialog
import com.sakurafubuki.yume.feature.videopicker.state.SelectedFolder
import com.sakurafubuki.yume.feature.videopicker.state.SelectedVideo
import com.sakurafubuki.yume.feature.videopicker.state.rememberSelectionManager

@Composable
fun MediaPickerRoute(
    viewModel: MediaPickerViewModel = hiltViewModel(),
    onPlayVideo: (uri: Uri) -> Unit,
    onPlayVideos: (uris: List<Uri>) -> Unit,
    onFolderClick: (folderPath: String) -> Unit,
    onCloudFolderClick: (cloudPath: String, cloudServerId: Int?) -> Unit,
    onCloudBackFromPath: (fallbackPath: String, cloudServerId: Int?) -> Unit,
    onSettingsClick: () -> Unit,
    onSearchClick: () -> Unit,
    onNavigateUp: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    MediaPickerScreen(
        uiState = uiState,
        onPlayVideo = onPlayVideo,
        onPlayVideos = onPlayVideos,
        onNavigateUp = onNavigateUp,
        onFolderClick = onFolderClick,
        onCloudFolderClick = onCloudFolderClick,
        onCloudBackFromPath = onCloudBackFromPath,
        onSettingsClick = onSettingsClick,
        onSearchClick = onSearchClick,
        onEvent = viewModel::onEvent,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalPermissionsApi::class)
@Composable
internal fun MediaPickerScreen(
    uiState: MediaPickerUiState,
    onNavigateUp: () -> Unit = {},
    onPlayVideo: (Uri) -> Unit = {},
    onPlayVideos: (List<Uri>) -> Unit = {},
    onFolderClick: (String) -> Unit = {},
    onCloudFolderClick: (String, Int?) -> Unit = { _, _ -> },
    onCloudBackFromPath: (String, Int?) -> Unit = { _, _ -> },
    onSettingsClick: () -> Unit = {},
    onSearchClick: () -> Unit = {},
    onEvent: (MediaPickerUiEvent) -> Unit = {},
) {
    val selectionManager = rememberSelectionManager()
    val permissionState = rememberMultiplePermissionsState(permissions = storagePermissions)
    val permissionGranted = permissionState.permissions.any { it.status.isGranted }
    val showPermissionRationale = permissionState.permissions.any { it.status.shouldShowRationale }
    val lazyGridState = rememberLazyGridState()
    val selectVideoFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { it?.let { onPlayVideo(it) } },
    )

    var isFabExpanded by rememberSaveable { mutableStateOf(false) }
    var showQuickSettingsDialog by rememberSaveable { mutableStateOf(false) }
    var showCloudServerSelectorDialog by rememberSaveable { mutableStateOf(false) }
    var showModeSwitchDialog by rememberSaveable { mutableStateOf(false) }
    var showUrlDialog by rememberSaveable { mutableStateOf(false) }
    val selectedCloudServer = uiState.webDavServers.firstOrNull { it.id == uiState.selectedCloudServerId }
        ?: uiState.webDavServers.firstOrNull()
    val currentCloudFolder = (uiState.cloudDataState as? DataState.Success)?.value
    val canNavigateCloudUp = uiState.mode == com.sakurafubuki.yume.core.model.MediaMode.CLOUD &&
        selectedCloudServer != null &&
        (uiState.selectedCloudServerId != null || normalizePath(uiState.cloudPath) != "/")
    val isAtHome = when (uiState.mode) {
        com.sakurafubuki.yume.core.model.MediaMode.LOCAL -> uiState.folderName == null
        com.sakurafubuki.yume.core.model.MediaMode.CLOUD -> !canNavigateCloudUp
    }

    var showRenameActionFor: Video? by rememberSaveable { mutableStateOf(null) }
    var showInfoActionFor: Video? by rememberSaveable { mutableStateOf(null) }
    var showDeleteVideosConfirmation by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(uiState.mode) {
        if (uiState.mode == com.sakurafubuki.yume.core.model.MediaMode.CLOUD) {
            selectionManager.clearSelection()
        }
    }

    val selectedItemsSize = selectionManager.selectedFolders.size + selectionManager.selectedVideos.size
    val totalItemsSize = (uiState.mediaDataState as? DataState.Success)?.value?.run { folderList.size + mediaList.size } ?: 0

    Scaffold(
        topBar = {
            NextTopAppBar(
                title = (
                    if (uiState.mode == com.sakurafubuki.yume.core.model.MediaMode.CLOUD) {
                        if (uiState.selectedCloudServerId == null && normalizePath(uiState.cloudPath) == "/") {
                            stringResource(R.string.app_name)
                        } else {
                            currentCloudFolder?.name ?: selectedCloudServer?.name ?: stringResource(R.string.app_name)
                        }
                    } else {
                        uiState.folderName ?: stringResource(R.string.app_name)
                    }
                    ).takeIf { !selectionManager.isInSelectionMode } ?: "",
                fontWeight = FontWeight.Bold,
                navigationIcon = {
                    if (selectionManager.isInSelectionMode) {
                        Row(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.secondaryContainer)
                                .clickable { selectionManager.exitSelectionMode() }
                                .padding(8.dp)
                                .padding(end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = NextIcons.Close,
                                contentDescription = stringResource(id = R.string.navigate_up),
                            )
                            Text(
                                text = stringResource(R.string.m_n_selected, selectedItemsSize, totalItemsSize),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    } else if (uiState.mode == com.sakurafubuki.yume.core.model.MediaMode.CLOUD && canNavigateCloudUp) {
                        FilledTonalIconButton(
                            onClick = {
                                onCloudBackFromPath(parentPath(uiState.cloudPath), selectedCloudServer.id)
                            },
                        ) {
                            Icon(
                                imageVector = NextIcons.ArrowBack,
                                contentDescription = stringResource(id = R.string.navigate_up),
                            )
                        }
                    } else if (uiState.folderName != null) {
                        FilledTonalIconButton(onClick = onNavigateUp) {
                            Icon(
                                imageVector = NextIcons.ArrowBack,
                                contentDescription = stringResource(id = R.string.navigate_up),
                            )
                        }
                    }
                },
                actions = {
                    if (selectionManager.isInSelectionMode) {
                        FilledTonalIconButton(
                            onClick = {
                                if (selectedItemsSize != totalItemsSize) {
                                    (uiState.mediaDataState as? DataState.Success)?.value?.let { folder ->
                                        folder.folderList.forEach { selectionManager.selectFolder(it) }
                                        folder.mediaList.forEach { selectionManager.selectVideo(it) }
                                    }
                                } else {
                                    selectionManager.clearSelection()
                                }
                            },
                        ) {
                            Icon(
                                imageVector = if (selectedItemsSize != totalItemsSize) {
                                    NextIcons.SelectAll
                                } else {
                                    NextIcons.DeselectAll
                                },
                                contentDescription = if (selectedItemsSize != totalItemsSize) {
                                    stringResource(R.string.select_all)
                                } else {
                                    stringResource(R.string.deselect_all)
                                },
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                                .clip(CircleShape)
                                .combinedClickable(
                                    onClick = { showModeSwitchDialog = true },
                                    onLongClick = {
                                        if (uiState.mode == com.sakurafubuki.yume.core.model.MediaMode.CLOUD) {
                                            showCloudServerSelectorDialog = true
                                        }
                                    },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = NextIcons.Sync,
                                contentDescription = if (uiState.mode == com.sakurafubuki.yume.core.model.MediaMode.CLOUD) {
                                    stringResource(R.string.switch_to_local_mode)
                                } else {
                                    stringResource(R.string.switch_to_cloud_mode)
                                },
                            )
                        }
                        IconButton(onClick = onSearchClick) {
                            Icon(
                                imageVector = NextIcons.Search,
                                contentDescription = stringResource(id = R.string.search),
                            )
                        }
                        IconButton(onClick = { showQuickSettingsDialog = true }) {
                            Icon(
                                imageVector = NextIcons.DashBoard,
                                contentDescription = stringResource(id = R.string.menu),
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            SelectionActionsSheet(
                show = uiState.mode == com.sakurafubuki.yume.core.model.MediaMode.LOCAL &&
                    selectionManager.isInSelectionMode &&
                    selectionManager.allSelectedVideos.isNotEmpty(),
                showRenameAction = selectionManager.isSingleVideoSelected,
                showInfoAction = selectionManager.isSingleVideoSelected,
                onPlayAction = {
                    val videoUris = selectionManager.allSelectedVideos.map { it.uriString.toUri() }
                    onPlayVideos(videoUris)
                    selectionManager.clearSelection()
                },
                onRenameAction = {
                    val selectedVideo = selectionManager.selectedVideos.firstOrNull() ?: return@SelectionActionsSheet
                    val video = (uiState.mediaDataState as? DataState.Success)?.value?.mediaList
                        ?.find { it.uriString == selectedVideo.uriString } ?: return@SelectionActionsSheet
                    showRenameActionFor = video
                },
                onInfoAction = {
                    val selectedVideo = selectionManager.selectedVideos.firstOrNull() ?: return@SelectionActionsSheet
                    val video = (uiState.mediaDataState as? DataState.Success)?.value?.mediaList
                        ?.find { it.uriString == selectedVideo.uriString } ?: return@SelectionActionsSheet
                    showInfoActionFor = video
                    selectionManager.clearSelection()
                },
                onShareAction = {
                    onEvent(MediaPickerUiEvent.ShareVideos(selectionManager.allSelectedVideos.map { it.uriString }))
                },
                onDeleteAction = {
                    if (MediaService.willSystemAsksForDeleteConfirmation()) {
                        onEvent(MediaPickerUiEvent.DeleteVideos(selectionManager.allSelectedVideos.map { it.uriString }))
                        selectionManager.clearSelection()
                    } else {
                        showDeleteVideosConfirmation = true
                    }
                },
            )
        },
        floatingActionButton = {
            if (selectionManager.isInSelectionMode || !isAtHome) return@Scaffold

            FloatingActionButtonMenu(
                expanded = isFabExpanded,
                button = {
                    ToggleFloatingActionButton(
                        checked = isFabExpanded,
                        onCheckedChange = { isFabExpanded = !isFabExpanded },
                    ) {
                        val icon by remember {
                            derivedStateOf {
                                if (checkedProgress > 0.5f) NextIcons.Close else NextIcons.Play
                            }
                        }
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.animateIcon(checkedProgress = { checkedProgress }),
                        )
                    }
                },
            ) {
                FloatingActionButtonMenuItem(
                    onClick = {
                        isFabExpanded = false
                        showUrlDialog = true
                    },
                    icon = {
                        Icon(
                            imageVector = NextIcons.Link,
                            contentDescription = null,
                        )
                    },
                    text = {
                        Text(text = stringResource(id = R.string.open_network_stream))
                    },
                )
                FloatingActionButtonMenuItem(
                    onClick = {
                        isFabExpanded = false
                        selectVideoFileLauncher.launch("video/*")
                    },
                    icon = {
                        Icon(
                            imageVector = NextIcons.FileOpen,
                            contentDescription = null,
                        )
                    },
                    text = {
                        Text(text = stringResource(id = R.string.open_local_video))
                    },
                )
                FloatingActionButtonMenuItem(
                    onClick = {
                        isFabExpanded = false
                        val folder = (uiState.mediaDataState as? DataState.Success)?.value ?: return@FloatingActionButtonMenuItem
                        val videoToPlay = folder.recentlyPlayedVideo ?: folder.firstVideo ?: return@FloatingActionButtonMenuItem
                        onPlayVideo(videoToPlay.uriString.toUri())
                    },
                    icon = {
                        Icon(
                            imageVector = NextIcons.History,
                            contentDescription = null,
                        )
                    },
                    text = {
                        Text(text = stringResource(id = R.string.recently_played))
                    },
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) { scaffoldPadding ->
        val contentScaffoldPadding = scaffoldPadding.copy(bottom = 0.dp)
        AnimatedContent(
            targetState = uiState.mode,
            transitionSpec = {
                if (targetState == com.sakurafubuki.yume.core.model.MediaMode.CLOUD) {
                    slideInHorizontally(
                        animationSpec = tween(durationMillis = 280),
                        initialOffsetX = { fullWidth -> fullWidth },
                    ) togetherWith slideOutHorizontally(
                        animationSpec = tween(durationMillis = 280),
                        targetOffsetX = { fullWidth -> (-fullWidth * 0.3f).toInt() },
                    )
                } else {
                    slideInHorizontally(
                        animationSpec = tween(durationMillis = 280),
                        initialOffsetX = { fullWidth -> (-fullWidth * 0.3f).toInt() },
                    ) togetherWith slideOutHorizontally(
                        animationSpec = tween(durationMillis = 280),
                        targetOffsetX = { fullWidth -> fullWidth },
                    )
                }
            },
            label = "video_mode_switch",
        ) { mode ->
            if (mode == com.sakurafubuki.yume.core.model.MediaMode.CLOUD) {
                CloudVideoPane(
                    preferences = uiState.preferences,
                    servers = uiState.webDavServers,
                    serversLoaded = uiState.cloudServersLoaded,
                    mediaDataState = uiState.cloudDataState,
                    refreshing = uiState.cloudRefreshing,
                    onAddServer = onSettingsClick,
                    onRefresh = { onEvent(MediaPickerUiEvent.RefreshCloud) },
                    onFolderClick = {
                        val target = decodeCloudFolderPath(it)
                        val targetPath = target?.second ?: normalizePath(it)
                        if (target == null || target.first == selectedCloudServer?.id) {
                            onEvent(MediaPickerUiEvent.PreloadCloudPath(targetPath))
                        }
                        onCloudFolderClick(targetPath, target?.first ?: selectedCloudServer?.id)
                    },
                    onVideoClick = { clickedUri, playlist ->
                        val orderedPlaylist = orderPlaylistFromClickedItem(
                            clickedUri = clickedUri,
                            playlist = playlist,
                        )
                        if (orderedPlaylist.size > 1) {
                            onPlayVideos(orderedPlaylist)
                        } else {
                            onPlayVideo(clickedUri)
                        }
                    },
                    modifier = Modifier.padding(contentScaffoldPadding),
                )
            } else {
                when (uiState.mediaDataState) {
                    is DataState.Error -> {
                    }

                    is DataState.Loading -> {
                        CenterCircularProgressBar(modifier = Modifier.padding(contentScaffoldPadding))
                    }

                    is DataState.Success -> {
                        PullToRefreshBox(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(contentScaffoldPadding)
                                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                                .background(MaterialTheme.colorScheme.background),
                            isRefreshing = uiState.refreshing,
                            onRefresh = { onEvent(MediaPickerUiEvent.Refresh) },
                        ) {
                            val updatedScaffoldPadding = contentScaffoldPadding.copy(top = 0.dp, bottom = 0.dp, start = 0.dp)
                            PermissionMissingView(
                                isGranted = permissionGranted,
                                showRationale = showPermissionRationale,
                                permissions = storagePermissions,
                                launchPermissionRequest = { permissionState.launchMultiplePermissionRequest() },
                            ) {
                                val rootFolder = uiState.mediaDataState.value
                                if (rootFolder == null || rootFolder.folderList.isEmpty() && rootFolder.mediaList.isEmpty()) {
                                    NoVideosFound(contentPadding = updatedScaffoldPadding)
                                    return@PermissionMissingView
                                }

                                val effectivePreferences = if (uiState.folderName != null) {
                                    uiState.preferences.copy(mediaViewMode = MediaViewMode.FOLDER_TREE)
                                } else {
                                    uiState.preferences
                                }

                                MediaView(
                                    rootFolder = rootFolder,
                                    preferences = effectivePreferences,
                                    onFolderClick = onFolderClick,
                                    onVideoClick = { onPlayVideo(it) },
                                    selectionManager = selectionManager,
                                    lazyGridState = lazyGridState,
                                    contentPadding = updatedScaffoldPadding,
                                    onVideoLoaded = { onEvent(MediaPickerUiEvent.AddToSync(it)) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    BackHandler(enabled = isFabExpanded) {
        isFabExpanded = false
    }

    BackHandler(enabled = selectionManager.isInSelectionMode) {
        selectionManager.exitSelectionMode()
    }

    LaunchedEffect(lazyGridState.isScrollInProgress) {
        if (isFabExpanded && lazyGridState.isScrollInProgress) {
            isFabExpanded = false
        }
    }

    LaunchedEffect(selectionManager.isInSelectionMode) {
        if (selectionManager.isInSelectionMode) {
            isFabExpanded = false
        }
    }

    if (showQuickSettingsDialog) {
        QuickSettingsDialog(
            applicationPreferences = uiState.preferences,
            onDismiss = { showQuickSettingsDialog = false },
            updatePreferences = { onEvent(MediaPickerUiEvent.UpdateMenu(it)) },
        )
    }

    if (showCloudServerSelectorDialog && uiState.mode == com.sakurafubuki.yume.core.model.MediaMode.CLOUD) {
        CloudServerSelectorDialog(
            servers = uiState.webDavServers,
            selectedServerIds = uiState.selectedCloudServerIds,
            onDismiss = { showCloudServerSelectorDialog = false },
            onToggleServer = { onEvent(MediaPickerUiEvent.ToggleCloudServerSelection(it)) },
        )
    }

    if (showModeSwitchDialog) {
        NextDialog(
            onDismissRequest = { showModeSwitchDialog = false },
            title = { Text(stringResource(R.string.switch_mode)) },
            content = {
                Text(
                    text = stringResource(
                        R.string.switch_mode_message,
                        stringResource(if (uiState.mode == com.sakurafubuki.yume.core.model.MediaMode.CLOUD) R.string.local_mode else R.string.cloud_mode),
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onEvent(MediaPickerUiEvent.ToggleMode)
                    showModeSwitchDialog = false
                }) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = { CancelButton(onClick = { showModeSwitchDialog = false }) },
        )
    }

    if (showUrlDialog) {
        NetworkUrlDialog(
            onDismiss = { showUrlDialog = false },
            onDone = { onPlayVideo(it.toUri()) },
        )
    }

    showRenameActionFor?.let { video ->
        RenameDialog(
            name = video.displayName,
            onDismiss = { showRenameActionFor = null },
            onDone = {
                onEvent(MediaPickerUiEvent.RenameVideo(video.uriString.toUri(), it))
                showRenameActionFor = null
                selectionManager.clearSelection()
            },
        )
    }

    showInfoActionFor?.let { video ->
        VideoInfoDialog(
            video = video,
            onDismiss = { showInfoActionFor = null },
        )
    }

    if (showDeleteVideosConfirmation) {
        DeleteConfirmationDialog(
            selectedVideos = selectionManager.selectedVideos,
            selectedFolders = selectionManager.selectedFolders,
            onConfirm = {
                onEvent(MediaPickerUiEvent.DeleteVideos(selectionManager.allSelectedVideos.map { it.uriString }))
                selectionManager.clearSelection()
                showDeleteVideosConfirmation = false
            },
            onCancel = { showDeleteVideosConfirmation = false },
        )
    }
}

@Composable
private fun CloudVideoPane(
    preferences: ApplicationPreferences,
    servers: List<com.sakurafubuki.yume.core.model.WebDavServer>,
    serversLoaded: Boolean,
    mediaDataState: DataState<Folder?>,
    refreshing: Boolean,
    onAddServer: () -> Unit,
    onRefresh: () -> Unit,
    onFolderClick: (String) -> Unit,
    onVideoClick: (clickedUri: Uri, playlist: List<Uri>) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!serversLoaded) {
        CloudLoadingSkeleton()
        return
    }

    if (servers.isEmpty()) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .clickable { onAddServer() },
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = NextIcons.CloudOff,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(text = stringResource(R.string.no_cloud_media_library))
            Spacer(Modifier.height(4.dp))
            Text(text = stringResource(R.string.tap_to_add_webdav_storage_in_settings))
        }
        return
    }

    PullToRefreshBox(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .background(MaterialTheme.colorScheme.background),
        isRefreshing = refreshing,
        onRefresh = onRefresh,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
        ) {
            when (mediaDataState) {
                is DataState.Loading -> CloudLoadingSkeleton()
                is DataState.Error -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = mediaDataState.value.message ?: stringResource(R.string.load_failed),
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    TextButton(onClick = onRefresh) { Text(stringResource(R.string.retry)) }
                }
                is DataState.Success -> {
                    val rawFolder = mediaDataState.value
                    if (rawFolder == null || (rawFolder.folderList.isEmpty() && rawFolder.mediaList.isEmpty())) {
                        NoVideosFound(contentPadding = PaddingValues(top = 56.dp))
                        return@PullToRefreshBox
                    }

                    val (rootFolder, displayPrefs) = when (preferences.mediaViewMode) {
                        MediaViewMode.VIDEOS -> {
                            val allVideos = rawFolder.allMediaList
                            rawFolder.copy(
                                folderList = emptyList(),
                                mediaList = allVideos,
                            ) to preferences
                        }
                        MediaViewMode.FOLDERS -> {
                            rawFolder.copy(
                                folderList = flattenToLeafVideoFolders(rawFolder.folderList),
                            ) to preferences.copy(mediaViewMode = MediaViewMode.FOLDER_TREE)
                        }
                        else -> rawFolder to preferences
                    }

                    val cloudPlaylist = rootFolder.mediaList.map { it.uriString.toUri() }
                    rootFolder.mediaList.forEach { video ->
                        video.thumbnailUriString?.let { thumb ->
                            VideoThumbnailStore.thumbnailUriMap[video.uriString] = thumb
                        }
                        if (video.duration > 0) {
                            VideoThumbnailStore.durationMsMap[video.uriString] = video.duration
                        }

                        Logger.d(
                            "BUG2_MediaPicker",
                            "video uri=${video.uriString.take(80)} " +
                                "duration=${video.duration}ms " +
                                "thumb=${video.thumbnailUriString?.take(80) ?: "NULL"} " +
                                "name=${video.nameWithExtension}",
                        )
                    }
                    Logger.d("BUG2_MediaPicker", "STORE: thumbnails=${VideoThumbnailStore.thumbnailUriMap.size} durations=${VideoThumbnailStore.durationMsMap.size}")
                    MediaView(
                        rootFolder = rootFolder,
                        preferences = displayPrefs,
                        allowSelection = false,
                        showHeaders = displayPrefs.mediaViewMode == MediaViewMode.FOLDER_TREE,
                        onFolderClick = onFolderClick,
                        onVideoClick = { clickedUri -> onVideoClick(clickedUri, cloudPlaylist) },
                        onVideoLoaded = {},
                        contentPadding = PaddingValues(bottom = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CloudServerSelectorDialog(
    servers: List<com.sakurafubuki.yume.core.model.WebDavServer>,
    selectedServerIds: Set<Int>,
    onDismiss: () -> Unit,
    onToggleServer: (Int) -> Unit,
) {
    NextDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.select_webdav_storage)) },
        content = {
            if (servers.isEmpty()) {
                Text(stringResource(R.string.no_available_webdav_storage))
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.selected_storage_count, selectedServerIds.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        servers.forEach { server ->
                            FilterChip(
                                selected = selectedServerIds.contains(server.id),
                                onClick = { onToggleServer(server.id) },
                                label = { Text(server.name) },
                                leadingIcon = {
                                    if (selectedServerIds.contains(server.id)) {
                                        Icon(
                                            imageVector = NextIcons.Check,
                                            contentDescription = null,
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) }
        },
    )
}

private fun orderPlaylistFromClickedItem(
    clickedUri: Uri,
    playlist: List<Uri>,
): List<Uri> {
    if (playlist.isEmpty()) return listOf(clickedUri)

    val clickedIndex = playlist.indexOf(clickedUri)
    if (clickedIndex < 0) {
        return listOf(clickedUri) + playlist.filterNot { it == clickedUri }
    }

    return playlist.drop(clickedIndex) + playlist.take(clickedIndex)
}

@Composable
private fun CloudLoadingSkeleton(
    modifier: Modifier = Modifier,
) {
    val placeholderRatios = listOf(1.25f, 0.9f, 1.05f, 1.35f, 0.85f, 1.1f)

    Column(
        modifier = modifier.fillMaxSize(),
    ) {
        Text(
            text = stringResource(R.string.loading_cloud_media),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(placeholderRatios) { ratio ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .height((132f * ratio).dp),
                )
            }
        }
    }
}

private fun normalizePath(path: String): String {
    val trimmed = path.trim()
    if (trimmed.isEmpty()) return "/"
    val withLeadingSlash = if (trimmed.startsWith('/')) trimmed else "/$trimmed"
    return withLeadingSlash.removeSuffix("/").ifBlank { "/" }
}

private fun parentPath(path: String): String {
    val normalized = normalizePath(path)
    if (normalized == "/") return "/"
    return normalized.substringBeforeLast('/', "/").ifBlank { "/" }
}

private fun decodeCloudFolderPath(path: String): Pair<Int, String>? {
    if (!path.startsWith(CLOUD_SERVER_PATH_PREFIX)) return null
    val payload = path.removePrefix(CLOUD_SERVER_PATH_PREFIX)
    val separator = payload.indexOf(':')
    if (separator <= 0) return null
    val serverId = payload.substring(0, separator).toIntOrNull() ?: return null
    val serverPath = payload.substring(separator + 1).ifBlank { "/" }
    return serverId to normalizePath(serverPath)
}

@Composable
private fun DeleteConfirmationDialog(
    modifier: Modifier = Modifier,
    selectedVideos: Set<SelectedVideo>,
    selectedFolders: Set<SelectedFolder>,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    NextDialog(
        onDismissRequest = onCancel,
        title = {
            Text(
                text = when {
                    selectedVideos.isEmpty() -> when (selectedFolders.size) {
                        1 -> stringResource(R.string.delete_one_folder)
                        else -> stringResource(R.string.delete_folders, selectedFolders.size)
                    }

                    selectedFolders.isEmpty() -> when (selectedVideos.size) {
                        1 -> stringResource(R.string.delete_one_video)
                        else -> stringResource(R.string.delete_videos, selectedVideos.size)
                    }

                    else -> stringResource(R.string.delete_items, selectedFolders.size + selectedVideos.size)
                },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = modifier,
            ) {
                Text(text = stringResource(R.string.delete))
            }
        },
        dismissButton = { CancelButton(onClick = onCancel) },
        modifier = modifier,
        content = {
            Text(
                text = if ((selectedFolders.size + selectedVideos.size) == 1) {
                    stringResource(R.string.delete_item_info)
                } else {
                    stringResource(R.string.delete_items_info)
                },
                style = MaterialTheme.typography.titleSmall,
            )
        },
    )
}

@Composable
private fun NetworkUrlDialog(
    onDismiss: () -> Unit,
    onDone: (String) -> Unit,
) {
    var url by rememberSaveable { mutableStateOf("") }
    NextDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.network_stream)) },
        content = {
            Text(text = stringResource(R.string.enter_a_network_url))
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(text = stringResource(R.string.example_url)) },
            )
        },
        confirmButton = {
            DoneButton(
                enabled = url.isNotBlank(),
                onClick = { onDone(url) },
            )
        },
        dismissButton = { CancelButton(onClick = onDismiss) },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SelectionActionsSheet(
    modifier: Modifier = Modifier,
    show: Boolean,
    showRenameAction: Boolean,
    showInfoAction: Boolean,
    onPlayAction: () -> Unit,
    onRenameAction: () -> Unit,
    onShareAction: () -> Unit,
    onInfoAction: () -> Unit,
    onDeleteAction: () -> Unit,
) {
    AnimatedVisibility(
        modifier = modifier.padding(
            start = WindowInsets.displayCutout.asPaddingValues()
                .calculateStartPadding(LocalLayoutDirection.current),
        ),
        visible = show,
        enter = slideInVertically { it },
        exit = slideOutVertically { it },
    ) {
        val shape = MaterialTheme.shapes.largeIncreased.copy(
            bottomStart = ZeroCornerSize,
            bottomEnd = ZeroCornerSize,
        )
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .fillMaxWidth()
                    .background(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = shape,
                    )
                    .clip(shape)
                    .horizontalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(
                        horizontal = 8.dp,
                        vertical = 12.dp,
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                SelectionAction(
                    imageVector = NextIcons.Play,
                    title = stringResource(R.string.play),
                    onClick = onPlayAction,
                )
                if (showRenameAction) {
                    SelectionAction(
                        imageVector = NextIcons.Edit,
                        title = stringResource(R.string.rename),
                        onClick = onRenameAction,
                    )
                }
                SelectionAction(
                    imageVector = NextIcons.Share,
                    title = stringResource(R.string.share),
                    onClick = onShareAction,
                )
                if (showInfoAction) {
                    SelectionAction(
                        imageVector = NextIcons.Info,
                        title = stringResource(id = R.string.info),
                        onClick = onInfoAction,
                    )
                }
                SelectionAction(
                    imageVector = NextIcons.Delete,
                    title = stringResource(id = R.string.delete),
                    onClick = onDeleteAction,
                )
            }
        }
    }
}

@Composable
private fun SelectionAction(
    imageVector: ImageVector,
    title: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .defaultMinSize(
                minWidth = 75.dp,
                minHeight = 64.dp,
            )
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(
                horizontal = 16.dp,
                vertical = 8.dp,
            ),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = title,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.size(4.dp))
        Text(
            text = title,
            modifier = Modifier,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

private fun flattenToLeafVideoFolders(folders: List<Folder>): List<Folder> = folders.flatMap { folder ->
    if (folder.mediaList.isNotEmpty()) {
        listOf(folder)
    } else if (folder.folderList.isNotEmpty()) {
        flattenToLeafVideoFolders(folder.folderList)
    } else {
        emptyList()
    }
}

private const val CLOUD_SERVER_PATH_PREFIX = "__cloud_server__"

@PreviewScreenSizes
@PreviewLightDark
@Composable
private fun MediaPickerScreenPreview(
    @PreviewParameter(VideoPickerPreviewParameterProvider::class)
    videos: List<Video>,
) {
    YumeTheme {
        MediaPickerScreen(
            uiState = MediaPickerUiState(
                folderName = null,
                mediaDataState = DataState.Success(
                    value = Folder(
                        name = "Root Folder",
                        path = "/root",
                        dateModified = System.currentTimeMillis(),
                        folderList = listOf(
                            Folder(name = "Folder 1", path = "/root/folder1", dateModified = System.currentTimeMillis()),
                            Folder(name = "Folder 2", path = "/root/folder2", dateModified = System.currentTimeMillis()),
                        ),
                        mediaList = videos,
                    ),
                ),
                preferences = ApplicationPreferences().copy(
                    mediaViewMode = MediaViewMode.FOLDER_TREE,
                    mediaLayoutMode = MediaLayoutMode.GRID,
                ),
            ),
        )
    }
}

@Preview
@Composable
private fun ButtonPreview() {
    Surface {
        TextIconToggleButton(
            text = stringResource(R.string.title),
            icon = NextIcons.Title,
            onClick = {},
        )
    }
}

@DayNightPreview
@Composable
private fun MediaPickerNoVideosFoundPreview() {
    YumeTheme {
        Surface {
            MediaPickerScreen(
                uiState = MediaPickerUiState(
                    folderName = null,
                    mediaDataState = DataState.Success(null),
                    preferences = ApplicationPreferences(),
                ),
            )
        }
    }
}

@DayNightPreview
@Composable
private fun MediaPickerLoadingPreview() {
    YumeTheme {
        Surface {
            MediaPickerScreen(
                uiState = MediaPickerUiState(
                    folderName = null,
                    mediaDataState = DataState.Loading,
                    preferences = ApplicationPreferences(),
                ),
            )
        }
    }
}

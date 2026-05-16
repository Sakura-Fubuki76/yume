package com.sakurafubuki.yume.feature.imagebrowser.ui

import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.accompanist.permissions.shouldShowRationale
import com.sakurafubuki.yume.core.common.Logger
import com.sakurafubuki.yume.core.common.imagePermissions
import com.sakurafubuki.yume.core.model.ApplicationPreferences
import com.sakurafubuki.yume.core.model.Folder
import com.sakurafubuki.yume.core.model.ImageQuality
import com.sakurafubuki.yume.core.model.MediaLayoutMode
import com.sakurafubuki.yume.core.model.MediaMode
import com.sakurafubuki.yume.core.model.MediaViewMode
import com.sakurafubuki.yume.core.model.Video
import com.sakurafubuki.yume.core.ui.R
import com.sakurafubuki.yume.core.ui.components.CancelButton
import com.sakurafubuki.yume.core.ui.components.NextDialog
import com.sakurafubuki.yume.core.ui.components.NextTopAppBar
import com.sakurafubuki.yume.core.ui.composables.PermissionMissingView
import com.sakurafubuki.yume.core.ui.designsystem.NextIcons
import com.sakurafubuki.yume.core.ui.extensions.copy
import com.sakurafubuki.yume.core.ui.motion.Direction
import com.sakurafubuki.yume.core.ui.motion.LocalSharedElementRegistry
import com.sakurafubuki.yume.core.ui.motion.LocalTransitionEngine
import com.sakurafubuki.yume.core.ui.motion.SharedElementRegistry
import com.sakurafubuki.yume.core.ui.motion.TransitionType
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val LocalGridLockState = compositionLocalOf { mutableStateOf(false) }

object ImageViewerStore {
    var images: List<Video> = emptyList()
    var previewQuality: ImageQuality = DEFAULT_IMAGE_QUALITY
    var imageBrowserMemoryCachePercent: Int = ApplicationPreferences.DEFAULT_IMAGE_BROWSER_MEMORY_CACHE_PERCENT
    var imageBrowserThumbnailSizePx: Int = ApplicationPreferences.DEFAULT_IMAGE_BROWSER_THUMBNAIL_SIZE_PX
    var imageBrowserPreloadRange: Int = ApplicationPreferences.DEFAULT_IMAGE_BROWSER_PRELOAD_RANGE
    var launchUri: String? = null
    var launchOriginBounds: Rect? = null
    var lockGridScroll: Boolean = false
    var ensureTargetBounds: (suspend (String) -> Rect?)? = null
    var isViewerShowing by mutableStateOf(false)
    var viewerIndex: Int = -1
    var bottomBarAlpha by mutableFloatStateOf(1f)
    var heroTransitionImageUri: String? by mutableStateOf(null)
    var imageHostingBaseUrls: Set<String> = emptySet()

    fun showViewer(index: Int) {
        viewerIndex = index
        isViewerShowing = true
    }

    fun hideViewer() {
        isViewerShowing = false
        viewerIndex = -1
        heroTransitionImageUri = null
    }

    fun prepareLaunch(uri: String, registry: SharedElementRegistry) {
        lockGridScroll = true
        launchUri = uri
        launchOriginBounds = registry.getBounds(uri)
    }

    fun displayUriFor(uri: String): String = images.firstOrNull { it.uriString == uri }?.displayUriString() ?: uri
}

private const val TAG = "ImageViewer"

private const val SHARED_ELEMENT_BOUNDS_TIMEOUT_MS = 220L
private const val IMAGE_VIEWER_ARC_MIN_PX = 56f
private const val IMAGE_VIEWER_ARC_MAX_PX = 220f
private const val CLOUD_SERVER_PATH_PREFIX = "__cloud_server__"
private val DEFAULT_IMAGE_QUALITY = ImageQuality.HIGH
private val VIEWER_IMAGE_QUALITY = ImageQuality.ORIGINAL
private val IMAGE_VIEWER_OPEN_ANIMATION = spring<Float>(
    dampingRatio = 0.92f,
    stiffness = 450f,
)
private val IMAGE_VIEWER_CLOSE_ANIMATION = spring<Float>(
    dampingRatio = 0.95f,
    stiffness = 600f,
)

private const val BG_ALPHA_CURVE_POWER = 0.4f

private enum class CloseTrigger {
    BackPress,
    PredictiveBack,
}

private object ImageCellLoadState {
    const val LOADING = 0
    const val SUCCESS = 1
    const val ERROR = 2
}

private object GridImageLoadMemory {
    private const val MAX_ENTRIES = 2000
    private val loadedUris = object : LinkedHashMap<String, Boolean>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > MAX_ENTRIES
    }

    @Synchronized
    fun contains(uri: String): Boolean = loadedUris.containsKey(uri)

    @Synchronized
    fun add(uri: String) {
        loadedUris[uri] = true
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun ImageBrowserRoute(
    routePath: String = "/",
    routeCloudServerId: Int? = null,
    onNavigateToSettings: () -> Unit,
    onNavigateToPath: (String, Int?) -> Unit,
    onNavigateBackFromPath: (String, Int?) -> Unit,
    onImageClick: (Int) -> Unit,
    viewModel: ImageBrowserViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val permissionState = rememberMultiplePermissionsState(imagePermissions)
    val permissionGranted = permissionState.permissions.any { it.status.isGranted }
    val showRationale = permissionState.permissions.any { it.status.shouldShowRationale }
    val normalizedRoutePath = remember(routePath) { normalizePath(routePath) }

    val gridLockState = remember { mutableStateOf(false) }

    LaunchedEffect(normalizedRoutePath, routeCloudServerId, uiState.mode) {
        if (
            routeCloudServerId == null &&
            uiState.mode == MediaMode.LOCAL &&
            normalizePath(uiState.localPath) != normalizedRoutePath
        ) {
            viewModel.onEvent(ImageBrowserUiEvent.OpenLocalFolder(normalizedRoutePath))
        }
    }

    LaunchedEffect(ImageViewerStore.isViewerShowing) {
        if (!ImageViewerStore.isViewerShowing) {
            gridLockState.value = false
        }
    }

    CompositionLocalProvider(LocalGridLockState provides gridLockState) {
        ImageBrowserScreen(
            uiState = uiState,
            localImageLoader = viewModel.localImageLoader,
            permissionGranted = permissionGranted,
            showRationale = showRationale,
            onRequestPermission = { permissionState.launchMultiplePermissionRequest() },
            onNavigateToSettings = onNavigateToSettings,
            onNavigateToPath = onNavigateToPath,
            onNavigateBackFromPath = onNavigateBackFromPath,
            onEvent = viewModel::onEvent,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ImageBrowserScreen(
    uiState: ImageBrowserUiState,
    localImageLoader: ImageLoader,
    permissionGranted: Boolean,
    showRationale: Boolean,
    onRequestPermission: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToPath: (String, Int?) -> Unit,
    onNavigateBackFromPath: (String, Int?) -> Unit,
    onEvent: (ImageBrowserUiEvent) -> Unit,
) {
    var showQuickSettingsDialog by rememberSaveable { mutableStateOf(false) }
    var showCloudServerSelectorDialog by rememberSaveable { mutableStateOf(false) }
    var showModeSwitchDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(permissionGranted) {
        if (permissionGranted) {
            onEvent(ImageBrowserUiEvent.RefreshLocal)
        }
    }

    val selectedCloudServer = uiState.webDavServers.firstOrNull { it.id == uiState.selectedCloudServerId }
        ?: uiState.webDavServers.firstOrNull()
    val canNavigateCloudUp = uiState.mode == MediaMode.CLOUD &&
        selectedCloudServer != null &&
        normalizePath(uiState.cloudPath) != normalizePath(selectedCloudServer.basePath)
    val canNavigateLocalUp = uiState.mode == MediaMode.LOCAL && normalizePath(uiState.localPath) != "/"

    val currentGalleryState = when (uiState.mode) {
        MediaMode.LOCAL -> uiState.localGalleryState
        MediaMode.CLOUD -> uiState.cloudGalleryState
    }
    val currentFolder = when (currentGalleryState) {
        is ImageGalleryUiState.Content -> currentGalleryState.folder
        else -> null
    }

    LaunchedEffect(
        currentFolder,
        uiState.preferences.imageBrowserMemoryCachePercent,
        uiState.preferences.imageBrowserThumbnailSizePx,
        uiState.preferences.imageBrowserPreloadRange,
        uiState.preferences.imageQuality,
    ) {
        ImageViewerStore.images = currentFolder?.mediaList ?: emptyList()
        ImageViewerStore.previewQuality = uiState.preferences.imageQuality
        ImageViewerStore.imageBrowserMemoryCachePercent = uiState.preferences.imageBrowserMemoryCachePercent
        ImageViewerStore.imageBrowserThumbnailSizePx = uiState.preferences.imageBrowserThumbnailSizePx
        ImageViewerStore.imageBrowserPreloadRange = uiState.preferences.imageBrowserPreloadRange
    }

    Scaffold(
        topBar = {
            NextTopAppBar(
                title = when (uiState.mode) {
                    MediaMode.LOCAL -> currentFolder?.name ?: stringResource(R.string.app_name)
                    MediaMode.CLOUD -> currentFolder?.name ?: selectedCloudServer?.name ?: stringResource(R.string.app_name)
                },
                navigationIcon = {
                    when {
                        canNavigateCloudUp -> {
                            FilledTonalIconButton(
                                onClick = {
                                    val parent = parentPath(uiState.cloudPath)
                                    onNavigateBackFromPath(parent, selectedCloudServer.id)
                                },
                            ) {
                                Icon(imageVector = NextIcons.ArrowBack, contentDescription = stringResource(R.string.navigate_up))
                            }
                        }

                        canNavigateLocalUp -> {
                            FilledTonalIconButton(
                                onClick = {
                                    val parent = localNavigateUpTarget(uiState.localPath, uiState.preferences.imageViewMode)
                                    onEvent(ImageBrowserUiEvent.OpenLocalFolder(parent))
                                    onNavigateBackFromPath(parent, null)
                                },
                            ) {
                                Icon(imageVector = NextIcons.ArrowBack, contentDescription = stringResource(R.string.navigate_up))
                            }
                        }
                    }
                },
                actions = {
                    Box(
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .clip(CircleShape)
                            .combinedClickable(
                                onClick = { showModeSwitchDialog = true },
                                onLongClick = {
                                    if (uiState.mode == MediaMode.CLOUD) {
                                        showCloudServerSelectorDialog = true
                                    }
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (uiState.mode == MediaMode.CLOUD) NextIcons.Cloud else NextIcons.Folder,
                            contentDescription = if (uiState.mode == MediaMode.CLOUD) {
                                stringResource(R.string.switch_to_local_mode)
                            } else {
                                stringResource(R.string.switch_to_cloud_mode)
                            },
                        )
                    }
                    IconButton(onClick = { showQuickSettingsDialog = true }) {
                        Icon(imageVector = NextIcons.DashBoard, contentDescription = stringResource(R.string.menu))
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) { scaffoldPadding ->
        val contentScaffoldPadding = scaffoldPadding.copy(bottom = 0.dp)
        AnimatedContent(
            targetState = uiState.mode,
            transitionSpec = {
                if (targetState == MediaMode.CLOUD) {
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
            label = "image_mode_switch",
        ) { mode ->
            when (mode) {
                MediaMode.LOCAL -> PermissionMissingView(
                    isGranted = permissionGranted,
                    showRationale = showRationale,
                    permissions = listOf(stringResource(R.string.media_library)),
                    launchPermissionRequest = onRequestPermission,
                ) {
                    FolderPane(
                        galleryState = uiState.localGalleryState,
                        refreshing = false,
                        isCloudMode = false,
                        currentPath = uiState.localPath,
                        showCloudEmptyState = false,
                        preferences = uiState.preferences,
                        imageQuality = DEFAULT_IMAGE_QUALITY,
                        localImageLoader = localImageLoader,
                        onRefresh = { onEvent(ImageBrowserUiEvent.RefreshLocal) },
                        onFolderClick = { path ->
                            val normalizedPath = normalizePath(path)
                            onEvent(ImageBrowserUiEvent.OpenLocalFolder(normalizedPath))
                            onNavigateToPath(normalizedPath, null)
                        },
                        onMediaClick = { uri ->
                            val index = currentFolder?.mediaList?.indexOfFirst { it.uriString == uri.toString() } ?: -1
                            if (index >= 0) ImageViewerStore.showViewer(index)
                        },
                        onAddServer = onNavigateToSettings,
                        servers = emptyList(),
                        selectedServerId = null,
                        modifier = Modifier.padding(contentScaffoldPadding),
                    )
                }
                MediaMode.CLOUD -> FolderPane(
                    galleryState = uiState.cloudGalleryState,
                    refreshing = uiState.cloudRefreshing,
                    isCloudMode = true,
                    currentPath = uiState.cloudPath,
                    showCloudEmptyState = true,
                    preferences = uiState.preferences,
                    imageQuality = DEFAULT_IMAGE_QUALITY,
                    localImageLoader = localImageLoader,
                    onRefresh = { onEvent(ImageBrowserUiEvent.RefreshCloud) },
                    onFolderClick = { path ->
                        val target = decodeCloudFolderPath(path)
                        val targetPath = target?.second ?: normalizePath(path)
                        val targetServerId = target?.first ?: selectedCloudServer?.id
                        if (target == null || target.first == selectedCloudServer?.id) {
                            onEvent(ImageBrowserUiEvent.PreloadCloudPath(targetPath))
                        }
                        onNavigateToPath(targetPath, targetServerId)
                    },
                    onMediaClick = { uri ->
                        val index = currentFolder?.mediaList?.indexOfFirst { it.uriString == uri.toString() } ?: -1
                        if (index >= 0) ImageViewerStore.showViewer(index)
                    },
                    onAddServer = onNavigateToSettings,
                    servers = uiState.webDavServers,
                    selectedServerId = selectedCloudServer?.id,
                    onProbeCloudDimensions = { uri, width, height ->
                        onEvent(ImageBrowserUiEvent.ReportCloudImageDimensions(uri, width, height))
                    },
                    cloudHasMore = uiState.cloudHasMore,
                    cloudLoadingMore = uiState.cloudLoadingMore,
                    cloudError = uiState.cloudError,
                    onLoadMore = { onEvent(ImageBrowserUiEvent.LoadNextCloudPage) },
                    onRetryLoadMore = { onEvent(ImageBrowserUiEvent.LoadNextCloudPage) },
                    modifier = Modifier.padding(contentScaffoldPadding),
                )
            }
        }
    }

    if (showQuickSettingsDialog) {
        ImageQuickSettingsDialog(
            applicationPreferences = uiState.preferences,
            onDismiss = {
                if (showQuickSettingsDialog) showQuickSettingsDialog = false
            },
            updatePreferences = { onEvent(ImageBrowserUiEvent.UpdateMenu(it)) },
        )
    }

    if (showCloudServerSelectorDialog && uiState.mode == MediaMode.CLOUD) {
        CloudServerSelectorDialog(
            servers = uiState.webDavServers,
            selectedServerIds = uiState.selectedCloudServerIds,
            onDismiss = { showCloudServerSelectorDialog = false },
            onSelectServer = { onEvent(ImageBrowserUiEvent.ToggleCloudServerSelection(it)) },
        )
    }

    if (showModeSwitchDialog) {
        NextDialog(
            onDismissRequest = { showModeSwitchDialog = false },
            title = { Text(stringResource(R.string.switch_mode)) },
            content = {
                Text(
                    stringResource(
                        R.string.switch_mode_message,
                        stringResource(if (uiState.mode == MediaMode.CLOUD) R.string.local_mode else R.string.cloud_mode),
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onEvent(ImageBrowserUiEvent.ToggleMode)
                        showModeSwitchDialog = false
                    },
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = { CancelButton(onClick = { showModeSwitchDialog = false }) },
        )
    }
}

@Composable
private fun FolderPane(
    galleryState: ImageGalleryUiState,
    refreshing: Boolean,
    isCloudMode: Boolean,
    currentPath: String,
    showCloudEmptyState: Boolean,
    preferences: ApplicationPreferences,
    imageQuality: ImageQuality,
    localImageLoader: ImageLoader,
    onRefresh: () -> Unit,
    onFolderClick: (String) -> Unit,
    onMediaClick: (Uri) -> Unit,
    onAddServer: () -> Unit,
    servers: List<com.sakurafubuki.yume.core.model.WebDavServer>,
    selectedServerId: Int?,
    modifier: Modifier = Modifier,
    onProbeCloudDimensions: (String, Int, Int) -> Unit = { _, _, _ -> },
    cloudHasMore: Boolean = false,
    cloudLoadingMore: Boolean = false,
    cloudError: String? = null,
    onLoadMore: () -> Unit = {},
    onRetryLoadMore: () -> Unit = {},
) {
    if (servers.isNotEmpty() || selectedServerId != null) {
        val selectedServer = servers.firstOrNull { it.id == selectedServerId } ?: servers.firstOrNull()
        if (selectedServer != null) {
            PaneContent(
                galleryState = galleryState,
                refreshing = refreshing,
                isCloudMode = isCloudMode,
                preferences = preferences,
                imageQuality = imageQuality,
                localImageLoader = localImageLoader,
                onRefresh = onRefresh,
                onFolderClick = onFolderClick,
                onMediaClick = onMediaClick,
                onProbeCloudDimensions = onProbeCloudDimensions,
                modifier = modifier,
                cloudHasMore = cloudHasMore,
                cloudLoadingMore = cloudLoadingMore,
                cloudError = cloudError,
                onLoadMore = onLoadMore,
                onRetryLoadMore = onRetryLoadMore,
            )
            return
        }
    }

    if (servers.isEmpty() && selectedServerId != null) {
        return
    }

    if (showCloudEmptyState && servers.isEmpty() && currentPath == "/" && galleryState is ImageGalleryUiState.Loading) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .clickable { onAddServer() },
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(imageVector = NextIcons.CloudOff, contentDescription = null, modifier = Modifier.size(64.dp))
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = stringResource(R.string.no_cloud_media_library))
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = stringResource(R.string.tap_to_add_webdav_storage_in_settings))
        }
        return
    }

    PaneContent(
        galleryState = galleryState,
        refreshing = refreshing,
        isCloudMode = isCloudMode,
        preferences = preferences,
        imageQuality = imageQuality,
        localImageLoader = localImageLoader,
        onRefresh = onRefresh,
        onFolderClick = onFolderClick,
        onMediaClick = onMediaClick,
        onProbeCloudDimensions = onProbeCloudDimensions,
        modifier = modifier,
        cloudHasMore = cloudHasMore,
        cloudLoadingMore = cloudLoadingMore,
        cloudError = cloudError,
        onLoadMore = onLoadMore,
        onRetryLoadMore = onRetryLoadMore,
    )
}

@Composable
private fun CloudServerSelectorDialog(
    servers: List<com.sakurafubuki.yume.core.model.WebDavServer>,
    selectedServerIds: Set<Int>,
    onDismiss: () -> Unit,
    onSelectServer: (Int) -> Unit,
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
                                onClick = { onSelectServer(server.id) },
                                label = { Text(server.name) },
                                leadingIcon = {
                                    if (selectedServerIds.contains(server.id)) {
                                        Icon(imageVector = NextIcons.Check, contentDescription = null)
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

@Composable
private fun PaneContent(
    galleryState: ImageGalleryUiState,
    refreshing: Boolean,
    isCloudMode: Boolean,
    preferences: ApplicationPreferences,
    imageQuality: ImageQuality,
    localImageLoader: ImageLoader,
    onRefresh: () -> Unit,
    onFolderClick: (String) -> Unit,
    onMediaClick: (Uri) -> Unit,
    onProbeCloudDimensions: (String, Int, Int) -> Unit,
    modifier: Modifier = Modifier,
    cloudHasMore: Boolean = false,
    cloudLoadingMore: Boolean = false,
    cloudError: String? = null,
    onLoadMore: () -> Unit = {},
    onRetryLoadMore: () -> Unit = {},
) {
    PullToRefreshBox(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .background(MaterialTheme.colorScheme.background),
        isRefreshing = refreshing,
        onRefresh = onRefresh,
    ) {
        when (galleryState) {
            ImageGalleryUiState.Loading -> LoadingGalleryState(
                isRefreshing = refreshing,
                onRefresh = onRefresh,
            )

            is ImageGalleryUiState.Error -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = NextIcons.CloudOff,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(text = galleryState.message)
                Spacer(modifier = Modifier.height(12.dp))
                TextButton(onClick = onRefresh) { Text(stringResource(R.string.retry)) }
            }

            ImageGalleryUiState.Empty -> {
                EmptyGalleryState(onRefresh = onRefresh)
            }

            is ImageGalleryUiState.Content -> {
                val folder = galleryState.folder
                ImageMediaView(
                    rootFolder = folder,
                    isCloudMode = isCloudMode,
                    preferences = preferences,
                    imageQuality = imageQuality,
                    localImageLoader = localImageLoader,
                    onFolderClick = onFolderClick,
                    onImageClick = onMediaClick,
                    onProbeCloudDimensions = onProbeCloudDimensions,
                    contentPadding = PaddingValues(bottom = 8.dp),
                    cloudHasMore = cloudHasMore,
                    cloudLoadingMore = cloudLoadingMore,
                    cloudError = cloudError,
                    onLoadMore = onLoadMore,
                    onRetryLoadMore = onRetryLoadMore,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImageMediaView(
    rootFolder: Folder,
    isCloudMode: Boolean,
    preferences: ApplicationPreferences,
    imageQuality: ImageQuality,
    localImageLoader: ImageLoader,
    onFolderClick: (String) -> Unit,
    onImageClick: (Uri) -> Unit,
    onProbeCloudDimensions: (String, Int, Int) -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
    cloudHasMore: Boolean = false,
    cloudLoadingMore: Boolean = false,
    cloudError: String? = null,
    onLoadMore: () -> Unit = {},
    onRetryLoadMore: () -> Unit = {},
) {
    val gridState = rememberLazyStaggeredGridState()
    val sharedElementRegistry = LocalSharedElementRegistry.current

    val cellCoordinatesMap = remember { mutableMapOf<String, LayoutCoordinates>() }
    val contentHorizontalPadding = 8.dp
    val itemSpacing = if (preferences.imageLayoutMode == MediaLayoutMode.LIST) 8.dp else 4.dp
    val columns = if (preferences.imageLayoutMode == MediaLayoutMode.LIST) 1 else 2

    LaunchedEffect(gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index, cloudHasMore, cloudLoadingMore) {
        val lastVisibleIndex = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        val totalItems = rootFolder.folderList.size + rootFolder.mediaList.size
        if (totalItems > 0 && lastVisibleIndex >= totalItems - 5 && cloudHasMore && !cloudLoadingMore) {
            onLoadMore()
        }
    }

    DisposableEffect(rootFolder.path, rootFolder.mediaList, rootFolder.folderList) {
        ImageViewerStore.ensureTargetBounds = boundsResolver@{ uri ->
            val lastKnownBounds = sharedElementRegistry.getLastKnownBounds(uri)

            val coordDirect = cellCoordinatesMap[uri]?.let { coords ->
                runCatching { coords.boundsInWindow() }.getOrNull()
            }
            if (coordDirect != null) {
                return@boundsResolver coordDirect
            }

            val directBounds = awaitSharedElementBounds(
                registry = sharedElementRegistry,
                key = uri,
                timeoutMs = SHARED_ELEMENT_BOUNDS_TIMEOUT_MS / 3,
            )
            if (directBounds != null) return@boundsResolver directBounds

            val mediaIndex = rootFolder.mediaList.indexOfFirst { it.uriString == uri }
            if (mediaIndex < 0) {
                return@boundsResolver lastKnownBounds
            }

            val targetIndex = imageGridItemIndex(rootFolder, mediaIndex)
            try {
                val viewportHeight = gridState.layoutInfo.viewportSize.height
                val centerOffset = if (viewportHeight > 0) -(viewportHeight / 3) else -300
                gridState.scrollToItem(index = targetIndex, scrollOffset = centerOffset)
            } catch (_: Exception) {
                return@boundsResolver lastKnownBounds
            }

            withFrameNanos { }
            withFrameNanos { }
            val freshCoord = cellCoordinatesMap[uri]?.let { coords ->
                runCatching { coords.boundsInWindow() }.getOrNull()
            }
            if (freshCoord != null) {
                return@boundsResolver freshCoord
            }
            val finalBounds = sharedElementRegistry.getBounds(uri) ?: lastKnownBounds
            finalBounds
        }

        onDispose { }
    }

    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(columns),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        userScrollEnabled = !LocalGridLockState.current.value,
        contentPadding = contentPadding + PaddingValues(horizontal = contentHorizontalPadding, vertical = 8.dp),
        verticalItemSpacing = itemSpacing,
        horizontalArrangement = Arrangement.spacedBy(itemSpacing),
    ) {
        itemsIndexed(
            items = rootFolder.folderList,
            key = { _, folder -> folder.path },
        ) { _, folder ->
            ImageFolderCell(
                folder = folder,
                isCloudMode = isCloudMode,
                imageQuality = imageQuality,
                thumbnailMaxEdgePx = preferences.imageBrowserThumbnailSizePx,
                localImageLoader = localImageLoader,
                onClick = { onFolderClick(folder.path) },
                onProbeCloudDimensions = onProbeCloudDimensions,
            )
        }

        itemsIndexed(
            items = rootFolder.mediaList,
            key = { _, image -> image.uriString },
        ) { _, image ->
            ImageGridCell(
                image = image,
                isCloudMode = isCloudMode,
                imageQuality = imageQuality,
                thumbnailMaxEdgePx = preferences.imageBrowserThumbnailSizePx,
                localImageLoader = localImageLoader,
                onClick = { onImageClick(image.uriString.toUri()) },
                onProbeCloudDimensions = onProbeCloudDimensions,
                onCoordinatesChanged = { coords -> cellCoordinatesMap[image.uriString] = coords },
            )
        }

        if (cloudLoadingMore) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(
                            text = stringResource(R.string.loading_more),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (!cloudLoadingMore && cloudError != null) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = cloudError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 2,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = onRetryLoadMore) { Text(stringResource(R.string.retry)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImageFolderCell(
    folder: Folder,
    isCloudMode: Boolean,
    imageQuality: ImageQuality,
    thumbnailMaxEdgePx: Int,
    localImageLoader: ImageLoader,
    onClick: () -> Unit,
    onProbeCloudDimensions: (String, Int, Int) -> Unit,
) {
    val coverMedia = folder.coverMedia
    val childCoverMedia = remember(folder.folderList) {
        folder.folderList
            .mapNotNull { it.coverMedia }
            .filter { it.uriString.isNotBlank() || !it.thumbnailUriString.isNullOrBlank() }
            .take(4)
    }
    val imageCount = folder.mediaCount.takeIf { it > 0 } ?: folder.mediaList.size
    val coverWidth = coverMedia?.width ?: 0
    val coverHeight = coverMedia?.height ?: 0
    val cardAspectRatio = remember(coverMedia?.uriString ?: folder.path, coverWidth, coverHeight) {
        if (coverMedia != null && coverWidth > 0 && coverHeight > 0) {
            (coverWidth.toFloat() / coverHeight.toFloat()).coerceIn(0.2f, 5.0f)
        } else {
            fallbackAspectRatioFor(coverMedia?.uriString ?: folder.path)
        }
    }
    Card(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .aspectRatio(cardAspectRatio),
        shape = RoundedCornerShape(18.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainer),
        ) {
            if (childCoverMedia.size >= 2) {
                FolderCoverCollage(
                    coverMedia = childCoverMedia,
                    isCloudMode = isCloudMode,
                    imageQuality = imageQuality,
                    thumbnailMaxEdgePx = thumbnailMaxEdgePx,
                    localImageLoader = localImageLoader,
                    onProbeCloudDimensions = onProbeCloudDimensions,
                )
            } else if (coverMedia != null) {
                FolderCoverImage(
                    coverMedia = coverMedia,
                    isCloudMode = isCloudMode,
                    imageQuality = imageQuality,
                    thumbnailMaxEdgePx = thumbnailMaxEdgePx,
                    localImageLoader = localImageLoader,
                    contentDescription = folder.name,
                    modifier = Modifier.fillMaxSize(),
                    onProbeCloudDimensions = onProbeCloudDimensions,
                )
            } else {
                ImageThumbnailSkeleton()
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Text(
                    text = folder.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    maxLines = 1,
                )
                Text(
                    text = "$imageCount ${stringResource(R.string.images)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.9f),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun FolderCoverCollage(
    coverMedia: List<Video>,
    isCloudMode: Boolean,
    imageQuality: ImageQuality,
    thumbnailMaxEdgePx: Int,
    localImageLoader: ImageLoader,
    onProbeCloudDimensions: (String, Int, Int) -> Unit,
) {
    val slots = remember(coverMedia) {
        val distinct = coverMedia.distinctBy { it.displayUriString() }.take(4)
        if (distinct.size == 3) distinct + distinct.first() else distinct
    }
    if (slots.size == 2) {
        Row(modifier = Modifier.fillMaxSize()) {
            slots.forEach { cover ->
                FolderCoverImage(
                    coverMedia = cover,
                    isCloudMode = isCloudMode,
                    imageQuality = imageQuality,
                    thumbnailMaxEdgePx = thumbnailMaxEdgePx,
                    localImageLoader = localImageLoader,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    onProbeCloudDimensions = onProbeCloudDimensions,
                )
            }
        }
        return
    }
    Column(modifier = Modifier.fillMaxSize()) {
        for (row in 0 until 2) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                for (column in 0 until 2) {
                    val cover = slots.getOrNull(row * 2 + column)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f),
                    ) {
                        if (cover != null) {
                            FolderCoverImage(
                                coverMedia = cover,
                                isCloudMode = isCloudMode,
                                imageQuality = imageQuality,
                                thumbnailMaxEdgePx = thumbnailMaxEdgePx,
                                localImageLoader = localImageLoader,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                onProbeCloudDimensions = onProbeCloudDimensions,
                            )
                        } else {
                            ImageThumbnailSkeleton()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderCoverImage(
    coverMedia: Video,
    isCloudMode: Boolean,
    imageQuality: ImageQuality,
    thumbnailMaxEdgePx: Int,
    localImageLoader: ImageLoader,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    onProbeCloudDimensions: (String, Int, Int) -> Unit,
) {
    val context = LocalContext.current
    val coverDisplayUri = coverMedia.displayUriString()
    val isLocalCover = coverDisplayUri.startsWith("file://")
    val coverData: Any = if (isLocalCover) {
        File(coverDisplayUri.toUri().path ?: coverDisplayUri)
    } else {
        coverDisplayUri
    }
    var coverLoadState by remember(coverDisplayUri) {
        mutableIntStateOf(ImageCellLoadState.LOADING)
    }
    val coverFadeInAlpha by animateFloatAsState(
        targetValue = if (coverLoadState == ImageCellLoadState.SUCCESS) 1f else 0f,
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "folderCoverFadeIn",
    )
    AsyncImage(
        model = buildImageRequest(
            context = context,
            data = coverData,
            quality = imageQuality,
            profile = ImageRequestProfile.THUMBNAIL,
            thumbnailMaxEdgePx = thumbnailMaxEdgePx,
        ),
        imageLoader = if (isLocalCover) localImageLoader else SingletonImageLoader.get(context),
        contentDescription = contentDescription,
        contentScale = ContentScale.Crop,
        modifier = modifier.graphicsLayer { alpha = coverFadeInAlpha },
        onLoading = {
            coverLoadState = ImageCellLoadState.LOADING
        },
        onSuccess = { state ->
            coverLoadState = ImageCellLoadState.SUCCESS
            if (isCloudMode && (coverMedia.width <= 0 || coverMedia.height <= 0)) {
                val width = state.result.image.width
                val height = state.result.image.height
                if (width > 0 && height > 0) {
                    onProbeCloudDimensions(coverMedia.uriString, width, height)
                }
            }
        },
        onError = { error ->
            coverLoadState = ImageCellLoadState.ERROR
            Logger.w(TAG, "ImageFolderCell onError: uri=${coverMedia.uriString.take(100)} error=${error.result.throwable.message}")
        },
    )
}

@Composable
private fun ImageGridCell(
    image: Video,
    isCloudMode: Boolean,
    imageQuality: ImageQuality,
    thumbnailMaxEdgePx: Int,
    localImageLoader: ImageLoader,
    onClick: () -> Unit,
    onProbeCloudDimensions: (String, Int, Int) -> Unit,
    onCoordinatesChanged: ((LayoutCoordinates) -> Unit)? = null,
) {
    val context = LocalContext.current
    val sharedElementRegistry = LocalSharedElementRegistry.current
    val gridLockState = LocalGridLockState.current
    val displayUri = image.displayUriString()
    val lastBoundsUpdateMs = remember { LongArray(1) }
    var loadState by remember(displayUri) {
        mutableIntStateOf(
            if (GridImageLoadMemory.contains(displayUri)) {
                ImageCellLoadState.SUCCESS
            } else {
                ImageCellLoadState.LOADING
            },
        )
    }
    val aspectRatio = remember(image.uriString, image.width, image.height) {
        if (image.width > 0 && image.height > 0) {
            (image.width.toFloat() / image.height.toFloat()).coerceIn(0.2f, 5.0f)
        } else {
            fallbackAspectRatioFor(image.uriString)
        }
    }
    val fadeInAlpha by animateFloatAsState(
        targetValue = if (loadState == ImageCellLoadState.SUCCESS) 1f else 0f,
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "gridImageFadeIn",
    )
    Card(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .onGloballyPositioned { coordinates ->
                onCoordinatesChanged?.invoke(coordinates)
                val now = SystemClock.elapsedRealtime()
                if (now - lastBoundsUpdateMs[0] >= 32L) {
                    val bounds = coordinates.boundsInWindow()
                    sharedElementRegistry.updateBounds(image.uriString, bounds)
                    lastBoundsUpdateMs[0] = now
                }
            }
            .clickable {
                ImageViewerStore.prepareLaunch(image.uriString, sharedElementRegistry)
                gridLockState.value = true
                onClick()
            }
            .aspectRatio(aspectRatio),
        shape = RoundedCornerShape(18.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = buildImageRequest(
                    context = context,
                    data = displayUri,
                    quality = imageQuality,
                    profile = ImageRequestProfile.THUMBNAIL,
                    thumbnailMaxEdgePx = thumbnailMaxEdgePx,
                ),
                imageLoader = resolveImageLoader(context, displayUri, localImageLoader),
                contentDescription = image.nameWithExtension,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = if (ImageViewerStore.heroTransitionImageUri == image.uriString) 0f else fadeInAlpha
                    },
                onLoading = {
                    loadState = ImageCellLoadState.LOADING
                },
                onError = {
                    loadState = ImageCellLoadState.ERROR
                },
                onSuccess = { state ->
                    loadState = ImageCellLoadState.SUCCESS
                    GridImageLoadMemory.add(displayUri)
                    val loadedW = state.result.image.width
                    val loadedH = state.result.image.height
                    if (isCloudMode && (image.width <= 0 || image.height <= 0)) {
                        if (loadedW > 0 && loadedH > 0) {
                            onProbeCloudDimensions(image.uriString, loadedW, loadedH)
                        }
                    }
                },
            )
            when (loadState) {
                ImageCellLoadState.LOADING -> ImageThumbnailSkeleton()
                ImageCellLoadState.ERROR -> ImageThumbnailSkeleton(isError = true)
                ImageCellLoadState.SUCCESS -> Unit
            }
        }
    }
}

private fun fallbackAspectRatioFor(seed: String): Float {
    val hash = seed.hashCode().toLong() and 0xFFFFFFFFL
    val normalized = hash.toFloat() / 0xFFFFFFFFL.toFloat()
    return 0.6f + normalized * 0.9f
}

@Composable
private fun ImageThumbnailSkeleton(isError: Boolean = false) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                if (isError) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainer,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (isError) {
            Icon(
                imageVector = NextIcons.Image,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier.size(34.dp),
            )
        }
    }
}

@Composable
private fun LoadingGalleryState(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(text = stringResource(if (isRefreshing) R.string.refreshing_gallery else R.string.loading_gallery))
                Text(
                    text = stringResource(R.string.preparing_thumbnails),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        }

        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Fixed(2),
            state = rememberLazyStaggeredGridState(),
            modifier = Modifier.fillMaxSize(),
            verticalItemSpacing = 8.dp,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(8) { index ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .aspectRatio(if (index % 2 == 0) 0.82f else 1.18f),
                ) {
                    ImageThumbnailSkeleton()
                }
            }
        }

        TextButton(onClick = onRefresh, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(stringResource(R.string.retry)) }
    }
}

@Composable
private fun EmptyGalleryState(
    onRefresh: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = NextIcons.Image,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(text = stringResource(R.string.no_images_found))
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = stringResource(R.string.try_another_folder_or_pull_to_refresh))
        Spacer(modifier = Modifier.height(12.dp))
        TextButton(onClick = onRefresh) { Text(stringResource(R.string.refresh)) }
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

private fun Video.displayUriString(): String = thumbnailUriString?.takeIf { it.isNotBlank() } ?: uriString

private fun localNavigateUpTarget(path: String, imageViewMode: MediaViewMode): String = when (imageViewMode) {
    MediaViewMode.FOLDERS -> "/"
    else -> parentPath(path)
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

private fun imageGridItemIndex(rootFolder: Folder, mediaIndex: Int): Int = rootFolder.folderList.size + mediaIndex

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ImageViewerRoute(
    initialIndex: Int,
    onBack: () -> Unit,
) {
    val transitionEngine = LocalTransitionEngine.current
    val sharedElementRegistry = LocalSharedElementRegistry.current
    val viewModel = hiltViewModel<ImageBrowserViewModel>()
    var hasConsumedBackNavigation by remember { mutableStateOf(false) }

    fun requestNavigateBackOnce() {
        if (hasConsumedBackNavigation) return
        hasConsumedBackNavigation = true
        onBack()
    }

    val images = ImageViewerStore.images
    if (images.isEmpty()) {
        LaunchedEffect(Unit) {
            requestNavigateBackOnce()
        }
        return
    }
    val pageScales = remember { mutableStateMapOf<Int, Float>() }
    var isPinchActive by remember { mutableStateOf(false) }
    val pagerState = rememberPagerState(initialPage = initialIndex, pageCount = { images.size })
    val currentPageScale = pageScales[pagerState.currentPage] ?: 1f
    val scope = rememberCoroutineScope()
    val localImageLoader = viewModel.localImageLoader
    val launchUri = remember { ImageViewerStore.launchUri }
    val launchOriginBounds = remember { ImageViewerStore.launchOriginBounds }
    val launchIndex = images.indexOfFirst { it.uriString == launchUri }.takeIf { it >= 0 } ?: initialIndex

    var swipeDismissProgress by remember { mutableFloatStateOf(0f) }
    var transitionStartBounds by remember { mutableStateOf<Rect?>(null) }
    var transitionEndBounds by remember { mutableStateOf<Rect?>(null) }
    var transitionImageUri by remember { mutableStateOf(images.getOrNull(launchIndex)?.uriString ?: images.first().uriString) }
    var transitionStartCorner by remember { mutableStateOf(18.dp) }
    var transitionEndCorner by remember { mutableStateOf(0.dp) }
    var isTransitionRunning by remember { mutableStateOf(false) }
    var isAwaitingRoutePop by remember { mutableStateOf(false) }
    var useFallbackCloseAnimation by remember { mutableStateOf(false) }
    val shouldPlayLaunchAnimation = launchUri != null
    var showHeroOverlay by remember { mutableStateOf(shouldPlayLaunchAnimation) }
    var animationReady by remember { mutableStateOf(false) }
    var hasPlayedLaunchTransition by remember { mutableStateOf(false) }
    var isCloseOverlay by remember { mutableStateOf(false) }
    var overlayViewportRect by remember { mutableStateOf(Rect(0f, 0f, 1f, 1f)) }
    val swipeDismissThreshold = 0.15f
    val swipeDismissVelocityThresholdPxPerSec = 800f

    fun currentImageUri(): String = images.getOrNull(pagerState.currentPage)?.uriString ?: images[launchIndex].uriString

    fun imageDisplayBounds(uri: String): Rect {
        val image = images.firstOrNull { it.uriString == uri }
        return fittedImageRect(
            viewport = overlayViewportRect,
            imageWidth = image?.width ?: 0,
            imageHeight = image?.height ?: 0,
            fallbackSeed = uri,
        )
    }

    fun clampToViewport(bounds: Rect): Rect {
        val clamped = Rect(
            left = bounds.left.coerceAtLeast(0f),
            top = bounds.top.coerceAtLeast(0f),
            right = bounds.right.coerceAtMost(overlayViewportRect.width),
            bottom = bounds.bottom.coerceAtMost(overlayViewportRect.height),
        )
        val valid = (clamped.right - clamped.left) > 16f && (clamped.bottom - clamped.top) > 16f
        return if (valid) clamped else bounds
    }

    suspend fun resolveDestinationBounds(uri: String): Rect? {
        val direct = sharedElementRegistry.getBounds(uri)
        if (direct != null) return clampToViewport(direct)
        val ensured = ImageViewerStore.ensureTargetBounds?.invoke(uri)
        if (ensured != null) return clampToViewport(ensured)
        val lastKnown = sharedElementRegistry.getLastKnownBounds(uri)
        return if (lastKnown != null) clampToViewport(lastKnown) else null
    }

    fun resetTransition() {
        swipeDismissProgress = 0f
        isAwaitingRoutePop = false
        isTransitionRunning = false
        useFallbackCloseAnimation = false
        showHeroOverlay = false
        animationReady = false
        isCloseOverlay = false
        transitionStartBounds = null
        transitionEndBounds = null
        transitionStartCorner = 18.dp
        transitionEndCorner = 0.dp
        ImageViewerStore.heroTransitionImageUri = null
        transitionEngine.finish()
    }

    fun beginCloseTransition(currentUri: String, destinationBounds: Rect?, startRectOverride: Rect? = null) {
        val start = startRectOverride ?: imageDisplayBounds(currentUri)
        isAwaitingRoutePop = false
        isCloseOverlay = true
        transitionImageUri = currentUri
        transitionStartBounds = start
        transitionEndBounds = destinationBounds
        transitionStartCorner = 0.dp
        transitionEndCorner = 18.dp
        useFallbackCloseAnimation = destinationBounds == null
        animationReady = true
        ImageViewerStore.heroTransitionImageUri = currentUri
        isTransitionRunning = true
    }

    fun awaitRoutePop() {
        isTransitionRunning = false
        isAwaitingRoutePop = true
    }

    suspend fun startCloseTransition(
        trigger: CloseTrigger,
        initialProgress: Float,
        isGestureDriven: Boolean,
        startRectOverride: Rect? = null,
    ): Boolean {
        if (hasConsumedBackNavigation || isAwaitingRoutePop) return false
        val currentUri = currentImageUri()
        val destinationBounds = resolveDestinationBounds(currentUri)
        beginCloseTransition(currentUri, destinationBounds, startRectOverride = startRectOverride)
        showHeroOverlay = true
        transitionEngine.start(
            type = if (trigger == CloseTrigger.PredictiveBack) TransitionType.PredictiveBack else TransitionType.SharedElement,
            direction = Direction.Backward,
            initialProgress = initialProgress.coerceIn(0f, 1f),
            isGestureDriven = isGestureDriven,
        )
        return true
    }

    suspend fun closeWithSpring(trigger: CloseTrigger, initialProgress: Float, startRectOverride: Rect? = null) {
        val started = startCloseTransition(
            trigger = trigger,
            initialProgress = initialProgress,
            isGestureDriven = false,
            startRectOverride = startRectOverride,
        )
        if (!started) return
        animate(
            initialValue = initialProgress.coerceIn(0f, 1f),
            targetValue = 1f,
            animationSpec = IMAGE_VIEWER_CLOSE_ANIMATION,
        ) { value, _ ->
            transitionEngine.updateProgress(value)
        }
        ImageViewerStore.heroTransitionImageUri = null
        awaitRoutePop()
        requestNavigateBackOnce()
    }

    DisposableEffect(Unit) {
        transitionEngine.dimEnabled = false
        onDispose {
            ImageViewerStore.lockGridScroll = false
            ImageViewerStore.heroTransitionImageUri = null
            showHeroOverlay = false
            transitionEngine.finish()
            transitionEngine.dimEnabled = true
        }
    }

    val shouldShowHeroOverlay = showHeroOverlay &&
        animationReady &&
        !useFallbackCloseAnimation &&
        overlayViewportRect.width > 1f &&
        overlayViewportRect.height > 1f
    val overlayProgress = transitionEngine.progress.coerceIn(0f, 1f)
    val overlayStartRect = transitionStartBounds ?: overlayViewportRect
    val overlayEndRect = transitionEndBounds ?: overlayViewportRect
    val overlayHeroRect = interpolateRectWithArc(overlayStartRect, overlayEndRect, overlayProgress)
    val overlayHeroCorner = lerp(transitionStartCorner.value, transitionEndCorner.value, overlayProgress).dp

    LaunchedEffect(Unit) {
        if (hasPlayedLaunchTransition) return@LaunchedEffect
        while (overlayViewportRect.width <= 1f || overlayViewportRect.height <= 1f) {
            withFrameNanos { }
        }
        val startBounds = launchOriginBounds
            ?: sharedElementRegistry.getBounds(launchUri.orEmpty())
            ?: return@LaunchedEffect

        val clampedStart = Rect(
            left = startBounds.left.coerceAtLeast(0f),
            top = startBounds.top.coerceAtLeast(0f),
            right = startBounds.right.coerceAtMost(overlayViewportRect.width),
            bottom = startBounds.bottom.coerceAtMost(overlayViewportRect.height),
        )
        val clampedValid = (clampedStart.right - clampedStart.left) > 16f &&
            (clampedStart.bottom - clampedStart.top) > 16f
        val launchImageUri = launchUri
            ?.takeIf { images.any { img -> img.uriString == it } }
            ?: images[launchIndex].uriString
        hasPlayedLaunchTransition = true
        transitionStartBounds = if (clampedValid) clampedStart else startBounds
        transitionEndBounds = imageDisplayBounds(launchImageUri)
        transitionStartCorner = 18.dp
        transitionEndCorner = 0.dp
        transitionImageUri = launchImageUri
        ImageViewerStore.heroTransitionImageUri = launchImageUri
        useFallbackCloseAnimation = false
        isCloseOverlay = false
        isTransitionRunning = true
        showHeroOverlay = true
        animationReady = true
        transitionEngine.start(type = TransitionType.SharedElement, direction = Direction.Forward, initialProgress = 0f)
        animate(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = IMAGE_VIEWER_OPEN_ANIMATION,
        ) { value, _ ->
            transitionEngine.updateProgress(value)
        }

        withFrameNanos { }

        resetTransition()
        ImageViewerStore.launchUri = null
        ImageViewerStore.launchOriginBounds = null
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        val predictiveBackEnabled = true
        PredictiveBackHandler(enabled = predictiveBackEnabled) { progress ->

            if (hasConsumedBackNavigation || isAwaitingRoutePop) {
                return@PredictiveBackHandler
            }

            if (!hasPlayedLaunchTransition || isTransitionRunning) {
                transitionEngine.start(
                    type = TransitionType.PredictiveBack,
                    direction = Direction.Backward,
                    initialProgress = 0f,
                    isGestureDriven = true,
                )
                try {
                    progress.collect { backEvent ->
                        transitionEngine.updateProgress(backEvent.progress)
                    }
                } catch (_: CancellationException) {
                }

                animate(
                    initialValue = transitionEngine.progress.coerceIn(0f, 1f),
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = 200, easing = LinearEasing),
                ) { value, _ ->
                    transitionEngine.updateProgress(value)
                }
                transitionEngine.finish()
                return@PredictiveBackHandler
            }

            isTransitionRunning = true
            transitionEngine.start(
                type = TransitionType.PredictiveBack,
                direction = Direction.Backward,
                initialProgress = 0f,
                isGestureDriven = true,
            )

            try {
                progress.collect { backEvent ->
                    transitionEngine.updateProgress(backEvent.progress)
                }

                val currentUri = currentImageUri()

                val syncBounds = sharedElementRegistry.getBounds(currentUri)
                    ?: sharedElementRegistry.getLastKnownBounds(currentUri)

                val releaseProgress = transitionEngine.progress.coerceIn(0f, 1f)

                val pbTranslationX = overlayViewportRect.width * releaseProgress
                val pbScale = 1f - (0.05f * releaseProgress)
                val baseImageBounds = imageDisplayBounds(currentUri)

                val scaledWidth = baseImageBounds.width * pbScale
                val scaledHeight = baseImageBounds.height * pbScale
                val scaledCenterX = baseImageBounds.center.x + pbTranslationX
                val scaledCenterY = baseImageBounds.center.y

                val currentRectOverride = Rect(
                    left = scaledCenterX - scaledWidth / 2f,
                    top = scaledCenterY - scaledHeight / 2f,
                    right = scaledCenterX + scaledWidth / 2f,
                    bottom = scaledCenterY + scaledHeight / 2f,
                )

                beginCloseTransition(
                    currentUri = currentUri,
                    destinationBounds = syncBounds,
                    startRectOverride = currentRectOverride,
                )
                showHeroOverlay = true
                transitionEngine.start(
                    type = TransitionType.SharedElement,
                    direction = Direction.Backward,
                    initialProgress = releaseProgress,
                    isGestureDriven = false,
                )

                if (syncBounds == null) {
                    scope.launch {
                        val resolvedBounds = resolveDestinationBounds(currentUri)
                        if (resolvedBounds != null) {
                            transitionEndBounds = resolvedBounds
                            useFallbackCloseAnimation = false
                        }
                    }
                }

                animate(
                    initialValue = releaseProgress,
                    targetValue = 1f,
                    animationSpec = IMAGE_VIEWER_CLOSE_ANIMATION,
                ) { value, _ ->
                    transitionEngine.updateProgress(value)
                }

                ImageViewerStore.heroTransitionImageUri = null
                awaitRoutePop()
                requestNavigateBackOnce()
            } catch (_: CancellationException) {
                animate(
                    initialValue = transitionEngine.progress.coerceIn(0f, 1f),
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = 180, easing = LinearEasing),
                ) { value, _ ->
                    transitionEngine.updateProgress(value)
                }
                resetTransition()
            }
        }
    }

    BackHandler(enabled = !isTransitionRunning && !isAwaitingRoutePop && hasPlayedLaunchTransition) {
        scope.launch {
            closeWithSpring(trigger = CloseTrigger.BackPress, initialProgress = 0f)
        }
    }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
    ) {
        val fullRect = Rect(0f, 0f, constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        val safeProgress = transitionEngine.progress.coerceIn(0f, 1f)
        SideEffect {
            if (overlayViewportRect != fullRect) {
                overlayViewportRect = fullRect
                if (isTransitionRunning && !isCloseOverlay && showHeroOverlay) {
                    transitionEndBounds = imageDisplayBounds(transitionImageUri)
                }
            }
            ImageViewerStore.bottomBarAlpha = when {
                isTransitionRunning && transitionEngine.direction == Direction.Backward -> safeProgress.coerceIn(0f, 1f)
                else -> 0f
            }
            if (!isTransitionRunning) isCloseOverlay = false
        }

        val fallbackScale = 1f - (0.1f * safeProgress)
        val fallbackAlpha = 1f - safeProgress
        val heroOverlayActive = showHeroOverlay && !useFallbackCloseAnimation
        val isGestureDragInProgress = transitionEngine.isGestureDriven && isTransitionRunning
        val suppressPager = heroOverlayActive && !isGestureDragInProgress
        val pagerAlpha = when {
            useFallbackCloseAnimation -> fallbackAlpha
            suppressPager -> 0f
            else -> 1f
        }
        val predictiveBackProgress = if (!isCloseOverlay && transitionEngine.type == TransitionType.PredictiveBack) safeProgress else 0f
        val predictiveBackTranslationX = fullRect.width * predictiveBackProgress
        val predictiveBackScale = 1f - (0.05f * predictiveBackProgress)

        val whiteBgAlpha = when {
            isTransitionRunning && transitionEngine.direction == Direction.Backward ->
                (1f - safeProgress).pow(BG_ALPHA_CURVE_POWER).coerceIn(0f, 1f)
            swipeDismissProgress > 0f ->
                (1f - swipeDismissProgress.coerceIn(0f, 1f)).pow(BG_ALPHA_CURVE_POWER).coerceIn(0f, 1f)
            isTransitionRunning && transitionEngine.direction == Direction.Forward ->
                safeProgress.pow(BG_ALPHA_CURVE_POWER).coerceIn(0f, 1f)
            shouldPlayLaunchAnimation && !hasPlayedLaunchTransition -> 0f
            else -> 1f
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = whiteBgAlpha.coerceIn(0f, 1f) }
                .background(Color.Black),
        )

        Box(
            modifier = Modifier.fillMaxSize(),
        ) {
            HorizontalPager(
                state = pagerState,
                beyondViewportPageCount = 1,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = predictiveBackTranslationX
                        alpha = pagerAlpha
                        val closeScale = if (useFallbackCloseAnimation) fallbackScale else 1f
                        val swipeScale = 1f - (0.25f * swipeDismissProgress)
                        scaleX = closeScale * predictiveBackScale * swipeScale
                        scaleY = closeScale * predictiveBackScale * swipeScale
                    },
                userScrollEnabled = !isPinchActive && currentPageScale <= 1f && !isTransitionRunning && !isAwaitingRoutePop,
            ) { page ->
                ImageViewerPage(
                    imageUri = images[page].uriString,
                    imageQuality = VIEWER_IMAGE_QUALITY,
                    localImageLoader = localImageLoader,
                    onScaleChanged = { scale -> pageScales[page] = scale },
                    onMultiTouchChanged = { active -> isPinchActive = active },
                    enableSwipeToDismiss = page == pagerState.currentPage &&
                        hasPlayedLaunchTransition &&
                        !isAwaitingRoutePop &&
                        (!isTransitionRunning || isGestureDragInProgress) &&
                        !heroOverlayActive &&
                        !isPinchActive &&
                        currentPageScale <= 1.01f,
                    swipeDismissHeightPx = fullRect.height,
                    swipeDismissWidthPx = fullRect.width,
                    onSwipeDismissProgress = { dismissProgress ->
                        swipeDismissProgress = dismissProgress.coerceIn(0f, 1f)
                    },
                    onSwipeDismissRelease = { dismissProgress, releaseVelocityY, currentRect ->
                        val shouldDismiss = dismissProgress >= swipeDismissThreshold || releaseVelocityY >= swipeDismissVelocityThresholdPxPerSec

                        if (shouldDismiss) {
                            val currentUri = currentImageUri()
                            ImageViewerStore.heroTransitionImageUri = currentUri
                            val capturedProgress = dismissProgress.coerceIn(0f, 1f)
                            val immediateBounds = sharedElementRegistry.getBounds(currentUri)
                                ?: sharedElementRegistry.getLastKnownBounds(currentUri)

                            scope.launch {
                                val resolvedBounds = immediateBounds ?: resolveDestinationBounds(currentUri)
                                transitionEndBounds = resolvedBounds
                                useFallbackCloseAnimation = resolvedBounds == null

                                transitionEngine.start(
                                    type = TransitionType.SharedElement,
                                    direction = Direction.Backward,
                                    initialProgress = capturedProgress,
                                    isGestureDriven = false,
                                )
                                isCloseOverlay = true
                                showHeroOverlay = true
                                animationReady = true
                                isTransitionRunning = true
                                transitionStartBounds = currentRect
                                transitionImageUri = currentUri
                                transitionStartCorner = 0.dp
                                transitionEndCorner = 18.dp

                                animate(
                                    initialValue = capturedProgress,
                                    targetValue = 1f,
                                    animationSpec = IMAGE_VIEWER_CLOSE_ANIMATION,
                                ) { value, _ ->
                                    transitionEngine.updateProgress(value)
                                }
                                ImageViewerStore.heroTransitionImageUri = null
                                awaitRoutePop()
                                requestNavigateBackOnce()
                            }
                        } else {
                            swipeDismissProgress = 0f
                            if (transitionEngine.isGestureDriven || transitionEngine.isRunning) {
                                resetTransition()
                            }
                        }
                    },
                )
            }

            NeighborImagePrefetch(
                images = images,
                currentPage = pagerState.currentPage,
                imageQuality = ImageViewerStore.previewQuality,
                radius = if (pagerState.isScrollInProgress) {
                    minOf(1, ImageViewerStore.imageBrowserPreloadRange.coerceIn(1, 6))
                } else {
                    ImageViewerStore.imageBrowserPreloadRange.coerceIn(1, 6)
                },
                localImageLoader = localImageLoader,
            )

            if (shouldShowHeroOverlay) {
                SharedElementHeroOverlayContent(
                    heroRect = overlayHeroRect,
                    heroCorner = overlayHeroCorner,
                    transitionImageUri = transitionImageUri,
                    imageQuality = DEFAULT_IMAGE_QUALITY,
                    localImageLoader = localImageLoader,
                    contentAlpha = 1f,
                )
            }
        }
    }
}

@Composable
private fun SharedElementHeroOverlayContent(
    heroRect: Rect,
    heroCorner: androidx.compose.ui.unit.Dp,
    transitionImageUri: String,
    imageQuality: ImageQuality,
    localImageLoader: ImageLoader,
    contentAlpha: Float,
    overlayProfile: ImageRequestProfile = ImageRequestProfile.THUMBNAIL,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val displayUri = ImageViewerStore.displayUriFor(transitionImageUri)
    val heroWidth = with(density) { heroRect.width.coerceAtLeast(1f).toDp() }
    val heroHeight = with(density) { heroRect.height.coerceAtLeast(1f).toDp() }
    Box(
        modifier = Modifier
            .fillMaxSize(),
    ) {
        Box(
            modifier = Modifier
                .width(heroWidth)
                .height(heroHeight)
                .graphicsLayer {
                    translationX = heroRect.left
                    translationY = heroRect.top
                    shape = RoundedCornerShape(heroCorner)
                    clip = true
                    alpha = contentAlpha
                }
                .background(Color.Black),
        ) {
            val placeholderKey = thumbnailMemoryCacheKey(
                data = displayUri,
                quality = imageQuality,
                thumbnailMaxEdgePx = ImageViewerStore.imageBrowserThumbnailSizePx,
            )
            AsyncImage(
                model = buildImageRequest(
                    context = context,
                    data = displayUri,
                    quality = imageQuality,
                    profile = overlayProfile,
                    thumbnailMaxEdgePx = ImageViewerStore.imageBrowserThumbnailSizePx,
                )
                    .newBuilder()
                    .placeholderMemoryCacheKey(placeholderKey)
                    .build(),
                imageLoader = resolveImageLoader(context, displayUri, localImageLoader),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private fun interpolateRectWithArc(start: Rect, end: Rect, fraction: Float): Rect {
    val safeFraction = fraction.coerceIn(0f, 1f)
    val width = lerp(start.width, end.width, safeFraction)
    val height = lerp(start.height, end.height, safeFraction)
    val centerX = lerp(start.center.x, end.center.x, safeFraction)
    val centerY = smoothArcCenterY(start, end, safeFraction)
    return Rect(
        left = centerX - width / 2f,
        top = centerY - height / 2f,
        right = centerX + width / 2f,
        bottom = centerY + height / 2f,
    )
}

private fun smoothArcCenterY(start: Rect, end: Rect, fraction: Float): Float {
    val linearY = lerp(start.center.y, end.center.y, fraction)
    val distance = abs(end.center.y - start.center.y)
    val sizeDelta = abs(end.height - start.height)
    val arcHeight = max(
        IMAGE_VIEWER_ARC_MIN_PX,
        min(IMAGE_VIEWER_ARC_MAX_PX, distance * 0.28f + sizeDelta * 0.08f),
    )
    val t = fraction.coerceIn(0f, 1f)
    val bump = 16f * t * t * (1f - t) * (1f - t)
    return linearY - arcHeight * bump
}

private fun fittedImageRect(
    viewport: Rect,
    imageWidth: Int,
    imageHeight: Int,
    fallbackSeed: String,
): Rect {
    val viewportWidth = viewport.width.coerceAtLeast(1f)
    val viewportHeight = viewport.height.coerceAtLeast(1f)
    val aspectRatio = if (imageWidth > 0 && imageHeight > 0) {
        imageWidth.toFloat() / imageHeight.toFloat()
    } else {
        fallbackAspectRatioFor(fallbackSeed)
    }.coerceAtLeast(0.01f)
    val viewportAspectRatio = viewportWidth / viewportHeight
    val fittedWidth: Float
    val fittedHeight: Float
    if (aspectRatio > viewportAspectRatio) {
        fittedWidth = viewportWidth
        fittedHeight = viewportWidth / aspectRatio
    } else {
        fittedHeight = viewportHeight
        fittedWidth = viewportHeight * aspectRatio
    }
    val centerX = viewport.left + viewportWidth / 2f
    val centerY = viewport.top + viewportHeight / 2f
    return Rect(
        left = centerX - fittedWidth / 2f,
        top = centerY - fittedHeight / 2f,
        right = centerX + fittedWidth / 2f,
        bottom = centerY + fittedHeight / 2f,
    )
}

private suspend fun awaitSharedElementBounds(
    registry: SharedElementRegistry,
    key: String,
    timeoutMs: Long,
): Rect? {
    val safeTimeoutMs = timeoutMs.coerceAtLeast(16L)
    val startedAt = SystemClock.uptimeMillis()
    var bounds = registry.getBounds(key)
    while (bounds == null && SystemClock.uptimeMillis() - startedAt < safeTimeoutMs) {
        kotlinx.coroutines.delay(16L)
        bounds = registry.getBounds(key)
    }
    return bounds
}

@Composable
private fun NeighborImagePrefetch(
    images: List<Video>,
    currentPage: Int,
    imageQuality: ImageQuality,
    radius: Int,
    localImageLoader: ImageLoader,
) {
    val context = LocalContext.current
    val safeRadius = radius.coerceIn(1, 6)
    val neighborUris = remember(images, currentPage, safeRadius) {
        buildList {
            for (offset in 1..safeRadius) {
                images.getOrNull(currentPage - offset)?.displayUriString()?.let(::add)
                images.getOrNull(currentPage + offset)?.displayUriString()?.let(::add)
            }
        }.distinct()
    }
    if (neighborUris.isEmpty()) return

    Row(
        modifier = Modifier
            .size(1.dp)
            .graphicsLayer { alpha = 0f },
    ) {
        neighborUris.forEach { uri ->
            AsyncImage(
                model = buildImageRequest(
                    context = context,
                    data = uri,
                    quality = imageQuality,
                    profile = ImageRequestProfile.PREFETCH,
                    thumbnailMaxEdgePx = ImageViewerStore.imageBrowserThumbnailSizePx,
                ),
                imageLoader = resolveImageLoader(context, uri, localImageLoader),
                contentDescription = null,
                modifier = Modifier.size(1.dp),
            )
        }
    }
}

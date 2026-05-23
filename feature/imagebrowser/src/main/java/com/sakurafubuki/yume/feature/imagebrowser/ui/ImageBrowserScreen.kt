package com.sakurafubuki.yume.feature.imagebrowser.ui

import android.content.Context
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
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
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.SuccessResult
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
import com.sakurafubuki.yume.core.ui.motion.yumePageSpatialSpringSpec
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

private val LocalGridLockState = compositionLocalOf { mutableStateOf(false) }

object ImageViewerStore {
    var images: List<Video> = emptyList()
    var previewQuality: ImageQuality = DEFAULT_IMAGE_QUALITY
    var imageBrowserMemoryCachePercent: Int = ApplicationPreferences.DEFAULT_IMAGE_BROWSER_MEMORY_CACHE_PERCENT
    var imageBrowserThumbnailSizePx: Int = ApplicationPreferences.DEFAULT_IMAGE_BROWSER_THUMBNAIL_SIZE_PX
    var imageBrowserPreloadPageCount: Int = ApplicationPreferences.DEFAULT_IMAGE_BROWSER_PRELOAD_PAGE_COUNT
    var imageCloudDiskCacheEnabled: Boolean = true
    var launchUri: String? = null
    var launchOriginBounds: Rect? = null
    var lockGridScroll: Boolean = false
    var ensureTargetBounds: (suspend (String) -> Rect?)? = null
    var isViewerShowing by mutableStateOf(false)
    var viewerIndex: Int = -1
    var bottomBarAlpha by mutableFloatStateOf(1f)
    var heroTransitionImageUri: String? by mutableStateOf(null)

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
private val IMAGE_BROWSER_TOP_BAR_SCROLL_FALLBACK_HEIGHT = 112.dp
private const val IMAGE_VIEWER_ARC_MIN_PX = 56f
private const val IMAGE_VIEWER_ARC_MAX_PX = 220f
private const val IMAGE_VIEWER_MEMORY_PRELOAD_RADIUS = 1
private const val IMAGE_VIEWER_DISK_PRELOAD_RADIUS_MAX = 2
private const val IMAGE_VIEWER_PRELOAD_CONCURRENCY = 2
private const val IMAGE_GRID_SCREEN_PRELOAD_CONCURRENCY = 4
private const val IMAGE_GRID_MIN_SCREEN_PRELOAD_ITEMS = 8
private const val CLOUD_SERVER_PATH_PREFIX = "__cloud_server__"
private val DEFAULT_IMAGE_QUALITY = ImageQuality.HIGH
private val VIEWER_IMAGE_QUALITY = ImageQuality.ORIGINAL
private val IMAGE_VIEWER_OPEN_ANIMATION = spring<Float>(
    dampingRatio = 1f,
    stiffness = 420f,
)
private val IMAGE_VIEWER_CLOSE_ANIMATION = spring<Float>(
    dampingRatio = 1f,
    stiffness = 560f,
)

private const val BG_ALPHA_CURVE_POWER = 0.4f
private const val IMAGE_VIEWER_PROGRESS_EPSILON = 0.001f

private enum class CloseTrigger {
    BackPress,
    PredictiveBack,
}

private object ImageCellLoadState {
    const val LOADING = 0
    const val SUCCESS = 1
    const val ERROR = 2
}

internal object GridImageLoadMemory {
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
        when {
            routeCloudServerId == null &&
                uiState.mode == MediaMode.LOCAL &&
                normalizePath(uiState.localPath) != normalizedRoutePath -> {
                viewModel.onEvent(ImageBrowserUiEvent.OpenLocalFolder(normalizedRoutePath))
            }

            routeCloudServerId != null &&
                uiState.mode == MediaMode.CLOUD &&
                (
                    uiState.selectedCloudServerId != routeCloudServerId ||
                        normalizePath(uiState.cloudPath) != normalizedRoutePath
                    ) -> {
                viewModel.onEvent(
                    ImageBrowserUiEvent.OpenCloudFolder(
                        "$CLOUD_SERVER_PATH_PREFIX$routeCloudServerId:$normalizedRoutePath",
                    ),
                )
            }
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
    var showStorageMenu by remember { mutableStateOf(false) }
    var topBarHeightPx by remember { mutableIntStateOf(0) }
    var topBarScrollProgress by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val topBarHeight = if (topBarHeightPx > 0) {
        with(density) { topBarHeightPx.toDp() }
    } else {
        IMAGE_BROWSER_TOP_BAR_SCROLL_FALLBACK_HEIGHT
    }
    val topBarInteractive = topBarScrollProgress < 0.98f

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
        uiState.preferences.imageBrowserPreloadPageCount,
        uiState.preferences.imageCloudDiskCacheEnabled,
        uiState.preferences.imageQuality,
    ) {
        ImageViewerStore.images = currentFolder?.mediaList ?: emptyList()
        ImageViewerStore.previewQuality = uiState.preferences.imageQuality
        ImageViewerStore.imageBrowserMemoryCachePercent = uiState.preferences.imageBrowserMemoryCachePercent
        ImageViewerStore.imageBrowserThumbnailSizePx = uiState.preferences.imageBrowserThumbnailSizePx
        ImageViewerStore.imageBrowserPreloadPageCount = uiState.preferences.imageBrowserPreloadPageCount
        ImageViewerStore.imageCloudDiskCacheEnabled = uiState.preferences.imageCloudDiskCacheEnabled
    }

    LaunchedEffect(currentGalleryState) {
        if (currentGalleryState !is ImageGalleryUiState.Content) {
            topBarScrollProgress = 0f
        }
    }

    LaunchedEffect(topBarInteractive) {
        if (!topBarInteractive) {
            showStorageMenu = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        val modeSwitchSpatialSpec = yumePageSpatialSpringSpec()
        val contentBackgroundOffsetPx = with(density) { topBarHeight.roundToPx() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset {
                    val offsetY = contentBackgroundOffsetPx * (1f - topBarScrollProgress.coerceIn(0f, 1f))
                    IntOffset(x = 0, y = offsetY.roundToInt())
                }
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(MaterialTheme.colorScheme.background),
        )
        val mediaContentPadding = PaddingValues(
            top = topBarHeight,
            bottom = 8.dp,
        )
        AnimatedContent(
            targetState = uiState.mode,
            transitionSpec = {
                if (targetState == MediaMode.CLOUD) {
                    slideInHorizontally(
                        animationSpec = modeSwitchSpatialSpec,
                        initialOffsetX = { fullWidth -> fullWidth },
                    ) togetherWith slideOutHorizontally(
                        animationSpec = modeSwitchSpatialSpec,
                        targetOffsetX = { fullWidth -> (-fullWidth * 0.3f).toInt() },
                    )
                } else {
                    slideInHorizontally(
                        animationSpec = modeSwitchSpatialSpec,
                        initialOffsetX = { fullWidth -> (-fullWidth * 0.3f).toInt() },
                    ) togetherWith slideOutHorizontally(
                        animationSpec = modeSwitchSpatialSpec,
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
                            onNavigateToPath(normalizedPath, null)
                        },
                        onMediaClick = { uri ->
                            val index = currentFolder?.mediaList?.indexOfFirst { it.uriString == uri.toString() } ?: -1
                            if (index >= 0) ImageViewerStore.showViewer(index)
                        },
                        onAddServer = onNavigateToSettings,
                        servers = emptyList(),
                        selectedServerId = null,
                        mediaContentPadding = mediaContentPadding,
                        topBarScrollDistance = topBarHeight,
                        onTopBarScrollProgressChanged = { topBarScrollProgress = it },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                MediaMode.CLOUD -> FolderPane(
                    galleryState = uiState.cloudGalleryState,
                    refreshing = uiState.cloudRefreshing,
                    isCloudMode = true,
                    cloudLoadingPhase = uiState.cloudLoadingPhase,
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
                    mediaContentPadding = mediaContentPadding,
                    topBarScrollDistance = topBarHeight,
                    onTopBarScrollProgressChanged = { topBarScrollProgress = it },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        if (topBarScrollProgress < 1f) {
            NextTopAppBar(
                modifier = Modifier
                    .onSizeChanged { size -> topBarHeightPx = size.height }
                    .offset {
                        val offsetY = -topBarHeightPx * topBarScrollProgress.coerceIn(0f, 1f)
                        IntOffset(x = 0, y = offsetY.roundToInt())
                    },
                title = when (uiState.mode) {
                    MediaMode.LOCAL -> if (normalizePath(uiState.localPath) == "/") {
                        stringResource(R.string.app_name)
                    } else {
                        currentFolder?.name ?: stringResource(R.string.app_name)
                    }
                    MediaMode.CLOUD -> if (normalizePath(uiState.cloudPath) == "/") {
                        stringResource(R.string.app_name)
                    } else {
                        currentFolder?.name ?: selectedCloudServer?.name ?: stringResource(R.string.app_name)
                    }
                },
                navigationIcon = {
                    when {
                        canNavigateCloudUp -> {
                            FilledTonalIconButton(
                                enabled = topBarInteractive,
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
                                enabled = topBarInteractive,
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
                    Box {
                        IconButton(
                            enabled = topBarInteractive,
                            onClick = { showStorageMenu = true },
                        ) {
                            Icon(
                                imageVector = if (uiState.mode == MediaMode.CLOUD) NextIcons.Cloud else NextIcons.Folder,
                                contentDescription = stringResource(R.string.switch_mode),
                            )
                        }
                        DropdownMenu(
                            expanded = showStorageMenu && topBarInteractive,
                            onDismissRequest = { showStorageMenu = false },
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (uiState.mode == MediaMode.CLOUD) {
                                            stringResource(R.string.switch_to_local_mode)
                                        } else {
                                            stringResource(R.string.switch_to_cloud_mode)
                                        },
                                    )
                                },
                                onClick = {
                                    showStorageMenu = false
                                    showModeSwitchDialog = true
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = if (uiState.mode == MediaMode.CLOUD) NextIcons.Folder else NextIcons.Cloud,
                                        contentDescription = null,
                                    )
                                },
                            )
                            if (uiState.mode == MediaMode.CLOUD) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.select_webdav_storage)) },
                                    onClick = {
                                        showStorageMenu = false
                                        showCloudServerSelectorDialog = true
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = NextIcons.Settings,
                                            contentDescription = null,
                                        )
                                    },
                                )
                            }
                        }
                    }
                    IconButton(
                        enabled = topBarInteractive,
                        onClick = { showQuickSettingsDialog = true },
                    ) {
                        Icon(imageVector = NextIcons.DashBoard, contentDescription = stringResource(R.string.menu))
                    }
                },
            )
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
    cloudLoadingPhase: ImageCloudLoadingPhase = ImageCloudLoadingPhase.READING_SNAPSHOT,
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
    mediaContentPadding: PaddingValues,
    topBarScrollDistance: Dp,
    onTopBarScrollProgressChanged: (Float) -> Unit,
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
                cloudLoadingPhase = cloudLoadingPhase,
                isCloudMode = isCloudMode,
                preferences = preferences,
                imageQuality = imageQuality,
                localImageLoader = localImageLoader,
                onRefresh = onRefresh,
                onFolderClick = onFolderClick,
                onMediaClick = onMediaClick,
                onProbeCloudDimensions = onProbeCloudDimensions,
                mediaContentPadding = mediaContentPadding,
                topBarScrollDistance = topBarScrollDistance,
                onTopBarScrollProgressChanged = onTopBarScrollProgressChanged,
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
        cloudLoadingPhase = cloudLoadingPhase,
        isCloudMode = isCloudMode,
        preferences = preferences,
        imageQuality = imageQuality,
        localImageLoader = localImageLoader,
        onRefresh = onRefresh,
        onFolderClick = onFolderClick,
        onMediaClick = onMediaClick,
        onProbeCloudDimensions = onProbeCloudDimensions,
        mediaContentPadding = mediaContentPadding,
        topBarScrollDistance = topBarScrollDistance,
        onTopBarScrollProgressChanged = onTopBarScrollProgressChanged,
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
    cloudLoadingPhase: ImageCloudLoadingPhase,
    isCloudMode: Boolean,
    preferences: ApplicationPreferences,
    imageQuality: ImageQuality,
    localImageLoader: ImageLoader,
    onRefresh: () -> Unit,
    onFolderClick: (String) -> Unit,
    onMediaClick: (Uri) -> Unit,
    onProbeCloudDimensions: (String, Int, Int) -> Unit,
    mediaContentPadding: PaddingValues,
    topBarScrollDistance: Dp,
    onTopBarScrollProgressChanged: (Float) -> Unit,
    modifier: Modifier = Modifier,
    cloudHasMore: Boolean = false,
    cloudLoadingMore: Boolean = false,
    cloudError: String? = null,
    onLoadMore: () -> Unit = {},
    onRetryLoadMore: () -> Unit = {},
) {
    val pullToRefreshState = rememberPullToRefreshState()
    PullToRefreshBox(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)),
        state = pullToRefreshState,
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        indicator = {
            if (refreshing || pullToRefreshState.distanceFraction > 0f) {
                PullToRefreshDefaults.Indicator(
                    state = pullToRefreshState,
                    isRefreshing = refreshing,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        },
    ) {
        when (galleryState) {
            ImageGalleryUiState.Loading -> LoadingGalleryState(
                isRefreshing = refreshing,
                cloudLoadingPhase = cloudLoadingPhase,
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
                    contentPadding = mediaContentPadding,
                    topBarScrollDistance = topBarScrollDistance,
                    onTopBarScrollProgressChanged = onTopBarScrollProgressChanged,
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
    topBarScrollDistance: Dp = 0.dp,
    onTopBarScrollProgressChanged: (Float) -> Unit = {},
    cloudHasMore: Boolean = false,
    cloudLoadingMore: Boolean = false,
    cloudError: String? = null,
    onLoadMore: () -> Unit = {},
    onRetryLoadMore: () -> Unit = {},
) {
    val gridState = rememberLazyStaggeredGridState()
    val sharedElementRegistry = LocalSharedElementRegistry.current
    val context = LocalContext.current
    val density = LocalDensity.current

    val contentHorizontalPadding = 8.dp
    val itemSpacing = if (preferences.imageLayoutMode == MediaLayoutMode.LIST) 8.dp else 4.dp
    val columns = if (preferences.imageLayoutMode == MediaLayoutMode.LIST) 1 else 2
    val folderCount = rootFolder.folderList.size
    val mediaCount = rootFolder.mediaList.size
    val totalItems = folderCount + mediaCount
    val mediaIndexByUri = remember(
        rootFolder.path,
        mediaCount,
        rootFolder.mediaList.firstOrNull()?.uriString,
        rootFolder.mediaList.lastOrNull()?.uriString,
    ) {
        rootFolder.mediaList
            .asSequence()
            .mapIndexed { index, video -> video.uriString to index }
            .toMap()
    }
    val cellBoundsMap = remember(rootFolder.path, folderCount, mediaCount, mediaIndexByUri) {
        mutableMapOf<String, Rect>()
    }

    LaunchedEffect(rootFolder.path) {
        gridState.scrollToItem(0)
    }

    LaunchedEffect(gridState, totalItems, density, topBarScrollDistance) {
        val scrollDistancePx = with(density) { topBarScrollDistance.toPx() }
            .coerceAtLeast(1f)
        snapshotFlow {
            when {
                totalItems <= 0 -> 0f
                gridState.firstVisibleItemIndex > 0 -> 1f
                else -> gridState.firstVisibleItemScrollOffset.toFloat() / scrollDistancePx
            }.coerceIn(0f, 1f)
        }
            .distinctUntilChanged()
            .collect(onTopBarScrollProgressChanged)
    }

    LaunchedEffect(
        gridState,
        rootFolder.path,
        folderCount,
        mediaCount,
        isCloudMode,
        preferences.imageBrowserPreloadPageCount,
        preferences.imageQuality,
        preferences.imageBrowserThumbnailSizePx,
        localImageLoader,
    ) {
        val pagesToPreload = preferences.imageBrowserPreloadPageCount.coerceIn(
            ApplicationPreferences.MIN_IMAGE_BROWSER_PRELOAD_PAGE_COUNT,
            ApplicationPreferences.MAX_IMAGE_BROWSER_PRELOAD_PAGE_COUNT,
        )
        if (!isCloudMode || pagesToPreload <= 0 || mediaCount <= 0) return@LaunchedEffect

        snapshotFlow {
            val visibleMediaIndexes = gridState.layoutInfo.visibleItemsInfo
                .asSequence()
                .map { item -> item.index - folderCount }
                .filter { index -> index in 0 until mediaCount }
                .toList()
            if (visibleMediaIndexes.isEmpty()) {
                null
            } else {
                val screenItemCount = max(visibleMediaIndexes.size, IMAGE_GRID_MIN_SCREEN_PRELOAD_ITEMS)
                val preloadStart = visibleMediaIndexes.maxOrNull()!! + 1
                val preloadEndExclusive = (preloadStart + screenItemCount * pagesToPreload).coerceAtMost(mediaCount)
                preloadStart to preloadEndExclusive
            }
        }
            .distinctUntilChanged()
            .collectLatest { range ->
                val (start, endExclusive) = range ?: return@collectLatest
                if (start >= endExclusive) return@collectLatest
                preloadGridScreenThumbnails(
                    context = context,
                    localImageLoader = localImageLoader,
                    images = rootFolder.mediaList.subList(start, endExclusive),
                    imageQuality = preferences.imageQuality,
                    thumbnailMaxEdgePx = preferences.imageBrowserThumbnailSizePx,
                )
            }
    }

    LaunchedEffect(gridState, totalItems, cloudHasMore, cloudLoadingMore) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .collect { lastVisibleIndex ->
                if (totalItems > 0 && lastVisibleIndex >= totalItems - 5 && cloudHasMore && !cloudLoadingMore) {
                    onLoadMore()
                }
            }
    }

    DisposableEffect(rootFolder.path, folderCount, mediaCount, mediaIndexByUri) {
        val boundsResolver: suspend (String) -> Rect? = boundsResolver@{ uri ->
            val mediaIndex = mediaIndexByUri[uri]
            val lastKnownBounds = sharedElementRegistry.getLastKnownBounds(uri)

            val cachedBounds = cellBoundsMap[uri]
            if (cachedBounds != null) {
                return@boundsResolver cachedBounds
            }

            val directBounds = awaitSharedElementBounds(
                registry = sharedElementRegistry,
                key = uri,
                timeoutMs = SHARED_ELEMENT_BOUNDS_TIMEOUT_MS / 3,
            )
            if (directBounds != null) return@boundsResolver directBounds

            if (mediaIndex == null) {
                return@boundsResolver lastKnownBounds
            }

            val targetIndex = folderCount + mediaIndex
            try {
                val viewportHeight = gridState.layoutInfo.viewportSize.height
                val centerOffset = if (viewportHeight > 0) -(viewportHeight / 3) else -300
                gridState.scrollToItem(index = targetIndex, scrollOffset = centerOffset)
            } catch (_: Exception) {
                return@boundsResolver lastKnownBounds
            }

            withFrameNanos { }
            withFrameNanos { }
            val freshBounds = cellBoundsMap[uri]
            if (freshBounds != null) {
                return@boundsResolver freshBounds
            }
            val finalBounds = sharedElementRegistry.getBounds(uri) ?: lastKnownBounds
            finalBounds
        }
        ImageViewerStore.ensureTargetBounds = boundsResolver
        onDispose {
            if (ImageViewerStore.ensureTargetBounds === boundsResolver) {
                ImageViewerStore.ensureTargetBounds = null
            }
        }
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
                onBoundsChanged = { bounds -> cellBoundsMap[image.uriString] = bounds },
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
    val coverData: Any = remember(coverDisplayUri, isLocalCover) {
        if (isLocalCover) {
            File(coverDisplayUri.toUri().path ?: coverDisplayUri)
        } else {
            coverDisplayUri
        }
    }
    val coverImageRequest = remember(context, coverData, imageQuality, thumbnailMaxEdgePx) {
        buildImageRequest(
            context = context,
            data = coverData,
            quality = imageQuality,
            profile = ImageRequestProfile.THUMBNAIL,
            thumbnailMaxEdgePx = thumbnailMaxEdgePx,
        )
    }
    val coverImageLoader = remember(context, isLocalCover, localImageLoader) {
        if (isLocalCover) {
            localImageLoader
        } else {
            SingletonImageLoader.get(context)
        }
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
        model = coverImageRequest,
        imageLoader = coverImageLoader,
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
    onBoundsChanged: ((Rect) -> Unit)? = null,
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
    val imageRequest = remember(context, displayUri, imageQuality, thumbnailMaxEdgePx) {
        buildImageRequest(
            context = context,
            data = displayUri,
            quality = imageQuality,
            profile = ImageRequestProfile.THUMBNAIL,
            thumbnailMaxEdgePx = thumbnailMaxEdgePx,
        )
    }
    val imageLoader = remember(context, displayUri, localImageLoader) {
        resolveImageLoader(context, displayUri, localImageLoader)
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
                val bounds = sharedElementBoundsInWindow(coordinates) ?: return@onGloballyPositioned
                onBoundsChanged?.invoke(bounds)
                val now = SystemClock.elapsedRealtime()
                if (now - lastBoundsUpdateMs[0] >= 32L) {
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
                model = imageRequest,
                imageLoader = imageLoader,
                contentDescription = image.nameWithExtension,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = if (ImageViewerStore.heroTransitionImageUri == image.uriString) 0f else fadeInAlpha
                },
                onLoading = {
                    if (!GridImageLoadMemory.contains(displayUri)) {
                        loadState = ImageCellLoadState.LOADING
                    }
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

private fun sharedElementBoundsInWindow(coordinates: LayoutCoordinates): Rect? = runCatching { coordinates.boundsInWindow(clipBounds = false) }.getOrNull()

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
    cloudLoadingPhase: ImageCloudLoadingPhase,
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
                val titleRes = if (isRefreshing) {
                    R.string.refreshing_gallery
                } else {
                    when (cloudLoadingPhase) {
                        ImageCloudLoadingPhase.READING_SNAPSHOT -> R.string.loading_gallery_snapshot
                        ImageCloudLoadingPhase.REFRESHING_REMOTE -> R.string.loading_gallery_cloud
                        ImageCloudLoadingPhase.PREPARING_COVERS -> R.string.loading_gallery_covers
                    }
                }
                Text(text = stringResource(titleRes))
                Text(
                    text = stringResource(
                        when (cloudLoadingPhase) {
                            ImageCloudLoadingPhase.READING_SNAPSHOT -> R.string.preparing_local_snapshot
                            ImageCloudLoadingPhase.REFRESHING_REMOTE -> R.string.preparing_thumbnails
                            ImageCloudLoadingPhase.PREPARING_COVERS -> R.string.preparing_folder_covers
                        },
                    ),
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

private suspend fun preloadGridScreenThumbnails(
    context: Context,
    localImageLoader: ImageLoader,
    images: List<Video>,
    imageQuality: ImageQuality,
    thumbnailMaxEdgePx: Int,
) {
    val targets = images
        .asSequence()
        .map { image -> image.displayUriString() }
        .filter { uri -> uri.isNotBlank() && !GridImageLoadMemory.contains(uri) }
        .distinct()
        .toList()
    if (targets.isEmpty()) return

    withContext(Dispatchers.IO) {
        val semaphore = Semaphore(IMAGE_GRID_SCREEN_PRELOAD_CONCURRENCY)
        val successCount = targets.map { uri ->
            async {
                semaphore.withPermit {
                    val loader = resolveImageLoader(context, uri, localImageLoader)
                    val result = runCatching {
                        loader.execute(
                            buildImageRequest(
                                context = context,
                                data = uri,
                                quality = imageQuality,
                                profile = ImageRequestProfile.THUMBNAIL,
                                thumbnailMaxEdgePx = thumbnailMaxEdgePx,
                            ),
                        )
                    }.getOrNull()
                    if (result is SuccessResult) {
                        GridImageLoadMemory.add(uri)
                        true
                    } else {
                        false
                    }
                }
            }
        }.awaitAll().count { it }
        Logger.d(TAG, "gridScreenPreload done targets=${targets.size} success=$successCount")
    }
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

private suspend fun preloadAdjacentViewerImages(
    context: Context,
    localImageLoader: ImageLoader,
    images: List<Video>,
    currentPage: Int,
) {
    val configuredPreload = ImageViewerStore.imageBrowserPreloadPageCount.coerceIn(
        ApplicationPreferences.MIN_IMAGE_BROWSER_PRELOAD_PAGE_COUNT,
        ApplicationPreferences.MAX_IMAGE_BROWSER_PRELOAD_PAGE_COUNT,
    )
    if (configuredPreload <= 0 || images.size <= 1) return
    val radius = if (ImageViewerStore.imageCloudDiskCacheEnabled) {
        min(configuredPreload, IMAGE_VIEWER_DISK_PRELOAD_RADIUS_MAX)
    } else {
        min(configuredPreload, IMAGE_VIEWER_MEMORY_PRELOAD_RADIUS)
    }
    if (radius <= 0) return

    val targets = viewerPreloadPageIndices(
        currentPage = currentPage,
        pageCount = images.size,
        radius = radius,
    ).mapNotNull { page -> images.getOrNull(page) }
    if (targets.isEmpty()) return

    withContext(Dispatchers.IO) {
        targets.chunked(IMAGE_VIEWER_PRELOAD_CONCURRENCY).forEach { batch ->
            coroutineScope {
                batch.map { image ->
                    async {
                        preloadViewerImage(
                            context = context,
                            localImageLoader = localImageLoader,
                            image = image,
                        )
                    }
                }.awaitAll()
            }
        }
    }
}

private fun viewerPreloadPageIndices(
    currentPage: Int,
    pageCount: Int,
    radius: Int,
): List<Int> = (1..radius)
    .flatMap { offset -> listOf(currentPage + offset, currentPage - offset) }
    .filter { page -> page in 0 until pageCount }
    .distinct()

private suspend fun preloadViewerImage(
    context: Context,
    localImageLoader: ImageLoader,
    image: Video,
) {
    val uri = image.uriString.takeIf { it.isNotBlank() } ?: return
    val displayUri = image.displayUriString()
    val loader = resolveImageLoader(context, uri, localImageLoader)
    try {
        loader.execute(
            buildImageRequest(
                context = context,
                data = displayUri,
                quality = ImageViewerStore.previewQuality,
                profile = ImageRequestProfile.THUMBNAIL,
                thumbnailMaxEdgePx = ImageViewerStore.imageBrowserThumbnailSizePx,
            ),
        )
        loader.execute(
            buildImageRequest(
                context = context,
                data = uri,
                quality = VIEWER_IMAGE_QUALITY,
                profile = ImageRequestProfile.VIEWER,
                thumbnailMaxEdgePx = ImageViewerStore.imageBrowserThumbnailSizePx,
            )
                .newBuilder()
                .placeholderMemoryCacheKey(
                    thumbnailMemoryCacheKey(
                        displayUri,
                        ImageViewerStore.previewQuality,
                        ImageViewerStore.imageBrowserThumbnailSizePx,
                    ),
                )
                .build(),
        )
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Logger.d(TAG, "preloadViewerImage: failed for $uri", error)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ImageViewerRoute(
    initialIndex: Int,
    onBack: () -> Unit,
) {
    val transitionEngine = LocalTransitionEngine.current
    val sharedElementRegistry = LocalSharedElementRegistry.current
    val context = LocalContext.current
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

    suspend fun resolveDestinationBounds(uri: String): Rect? {
        val direct = sharedElementRegistry.getBounds(uri)
        if (direct != null) return direct
        val ensured = ImageViewerStore.ensureTargetBounds?.invoke(uri)
        if (ensured != null) return ensured
        val lastKnown = sharedElementRegistry.getLastKnownBounds(uri)
        return lastKnown
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

    fun beginCloseTransition(
        currentUri: String,
        destinationBounds: Rect?,
        startRectOverride: Rect? = null,
        initialProgress: Float = 0f,
    ) {
        val visualStart = startRectOverride ?: imageDisplayBounds(currentUri)
        val start = if (destinationBounds != null) {
            projectStartRectForCurrentProgress(
                current = visualStart,
                end = destinationBounds,
                progress = initialProgress,
            )
        } else {
            visualStart
        }
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

    fun completeCloseTransition() {
        ImageViewerStore.heroTransitionImageUri = null
        awaitRoutePop()
        requestNavigateBackOnce()
    }

    fun startSharedElementCloseAnimation(initialProgress: Float, isGestureDriven: Boolean = false) {
        transitionEngine.start(
            type = TransitionType.SharedElement,
            direction = Direction.Backward,
            initialProgress = initialProgress.coerceIn(0f, 1f),
            isGestureDriven = isGestureDriven,
        )
    }

    suspend fun animateCloseToEnd(initialProgress: Float) {
        animate(
            initialValue = initialProgress.coerceIn(0f, 1f),
            targetValue = 1f,
            animationSpec = IMAGE_VIEWER_CLOSE_ANIMATION,
        ) { value, _ ->
            transitionEngine.updateProgress(value)
        }
        completeCloseTransition()
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
        beginCloseTransition(
            currentUri = currentUri,
            destinationBounds = destinationBounds,
            startRectOverride = startRectOverride,
            initialProgress = initialProgress,
        )
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
        animateCloseToEnd(initialProgress)
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

        val launchImageUri = launchUri
            ?.takeIf { images.any { img -> img.uriString == it } }
            ?: images[launchIndex].uriString
        hasPlayedLaunchTransition = true
        transitionStartBounds = startBounds
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

    LaunchedEffect(
        pagerState.currentPage,
        images,
        ImageViewerStore.imageCloudDiskCacheEnabled,
        ImageViewerStore.imageBrowserPreloadPageCount,
        ImageViewerStore.imageBrowserThumbnailSizePx,
    ) {
        preloadAdjacentViewerImages(
            context = context,
            localImageLoader = localImageLoader,
            images = images,
            currentPage = pagerState.currentPage,
        )
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
                    initialProgress = releaseProgress,
                )
                showHeroOverlay = true
                startSharedElementCloseAnimation(releaseProgress)

                if (syncBounds == null) {
                    scope.launch {
                        val resolvedBounds = resolveDestinationBounds(currentUri)
                        if (resolvedBounds != null) {
                            transitionEndBounds = resolvedBounds
                            useFallbackCloseAnimation = false
                        }
                    }
                }

                animateCloseToEnd(releaseProgress)
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
                                beginCloseTransition(
                                    currentUri = currentUri,
                                    destinationBounds = resolvedBounds,
                                    startRectOverride = currentRect,
                                    initialProgress = capturedProgress,
                                )
                                showHeroOverlay = true
                                startSharedElementCloseAnimation(capturedProgress)

                                animateCloseToEnd(capturedProgress)
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
    val thumbnailMaxEdgePx = ImageViewerStore.imageBrowserThumbnailSizePx
    val heroWidth = with(density) { heroRect.width.coerceAtLeast(1f).toDp() }
    val heroHeight = with(density) { heroRect.height.coerceAtLeast(1f).toDp() }
    val placeholderKey = remember(displayUri, imageQuality, thumbnailMaxEdgePx) {
        thumbnailMemoryCacheKey(
            data = displayUri,
            quality = imageQuality,
            thumbnailMaxEdgePx = thumbnailMaxEdgePx,
        )
    }
    val overlayImageRequest = remember(context, displayUri, imageQuality, overlayProfile, thumbnailMaxEdgePx, placeholderKey) {
        buildImageRequest(
            context = context,
            data = displayUri,
            quality = imageQuality,
            profile = overlayProfile,
            thumbnailMaxEdgePx = thumbnailMaxEdgePx,
        )
            .newBuilder()
            .placeholderMemoryCacheKey(placeholderKey)
            .build()
    }
    val overlayImageLoader = remember(context, displayUri, localImageLoader) {
        resolveImageLoader(context, displayUri, localImageLoader)
    }
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
            AsyncImage(
                model = overlayImageRequest,
                imageLoader = overlayImageLoader,
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
    val arcHeight = sharedElementArcHeight(
        distance = abs(end.center.y - start.center.y),
        sizeDelta = abs(end.height - start.height),
    )
    val bump = sharedElementArcBump(fraction)
    return linearY - arcHeight * bump
}

private fun projectStartRectForCurrentProgress(current: Rect, end: Rect, progress: Float): Rect {
    val safeProgress = progress.coerceIn(0f, 1f)
    if (safeProgress <= IMAGE_VIEWER_PROGRESS_EPSILON) return current
    val remainingProgress = (1f - safeProgress).coerceAtLeast(IMAGE_VIEWER_PROGRESS_EPSILON)
    val startWidth = projectStartValue(current.width, end.width, safeProgress, remainingProgress)
        .coerceAtLeast(1f)
    val startHeight = projectStartValue(current.height, end.height, safeProgress, remainingProgress)
        .coerceAtLeast(1f)
    val startCenterX = projectStartValue(current.center.x, end.center.x, safeProgress, remainingProgress)
    val arcBump = sharedElementArcBump(safeProgress)
    var startCenterY = projectStartValue(current.center.y, end.center.y, safeProgress, remainingProgress)
    repeat(4) {
        val arcHeight = sharedElementArcHeight(
            distance = abs(end.center.y - startCenterY),
            sizeDelta = abs(end.height - startHeight),
        )
        startCenterY = (current.center.y - end.center.y * safeProgress + arcHeight * arcBump) / remainingProgress
    }
    return Rect(
        left = startCenterX - startWidth / 2f,
        top = startCenterY - startHeight / 2f,
        right = startCenterX + startWidth / 2f,
        bottom = startCenterY + startHeight / 2f,
    )
}

private fun projectStartValue(current: Float, end: Float, progress: Float, remainingProgress: Float): Float = (current - end * progress) / remainingProgress

private fun sharedElementArcHeight(distance: Float, sizeDelta: Float): Float = max(
    IMAGE_VIEWER_ARC_MIN_PX,
    min(IMAGE_VIEWER_ARC_MAX_PX, distance * 0.28f + sizeDelta * 0.08f),
)

private fun sharedElementArcBump(fraction: Float): Float {
    val t = fraction.coerceIn(0f, 1f)
    return 16f * t * t * (1f - t) * (1f - t)
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

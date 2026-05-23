package com.sakurafubuki.yume.feature.imagebrowser.ui

import android.content.ContentUris
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Precision
import coil3.size.Size
import coil3.toBitmap
import com.sakurafubuki.yume.core.cache.ImageCacheManager
import com.sakurafubuki.yume.core.common.Logger
import com.sakurafubuki.yume.core.common.Utils
import com.sakurafubuki.yume.core.common.extensions.stripUserInfoFromHttpUrl
import com.sakurafubuki.yume.core.data.cache.CloudDirectoryItemCache
import com.sakurafubuki.yume.core.data.openlist.FsSearchItem
import com.sakurafubuki.yume.core.data.openlist.OpenListApi
import com.sakurafubuki.yume.core.data.openlist.toApiPath
import com.sakurafubuki.yume.core.data.openlist.toWebDavMediaItem
import com.sakurafubuki.yume.core.data.repository.LibyuvBitmapDecoder
import com.sakurafubuki.yume.core.data.repository.PreferencesRepository
import com.sakurafubuki.yume.core.data.repository.WebDavServerRepository
import com.sakurafubuki.yume.core.data.webdav.WebDavRepository
import com.sakurafubuki.yume.core.model.ApplicationPreferences
import com.sakurafubuki.yume.core.model.Folder
import com.sakurafubuki.yume.core.model.LocalImage
import com.sakurafubuki.yume.core.model.MediaMode
import com.sakurafubuki.yume.core.model.MediaViewMode
import com.sakurafubuki.yume.core.model.Sort
import com.sakurafubuki.yume.core.model.Video
import com.sakurafubuki.yume.core.model.WebDavMediaItem
import com.sakurafubuki.yume.core.model.WebDavServer
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

@HiltViewModel
class ImageBrowserViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val preferencesRepository: PreferencesRepository,
    private val webDavServerRepository: WebDavServerRepository,
    private val webDavRepository: WebDavRepository,
    private val imageDimensionCache: ImageDimensionCache,
    private val webDavDirectoryCache: WebDavDirectoryCache,
    private val cloudDirectoryItemCache: CloudDirectoryItemCache,
    private val cloudVideoMetadataRepository: com.sakurafubuki.yume.core.data.repository.CloudVideoMetadataRepository,
    private val openListApi: OpenListApi,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val initialLocalPath = savedStateHandle.get<String>(IMAGE_LOCAL_PATH_KEY)
        ?.let(::normalizePath)
        ?: ROOT_PATH
    private val initialCloudPath = savedStateHandle.get<String>(IMAGE_CLOUD_PATH_KEY)
        ?.let(::normalizePath)
        ?: ROOT_PATH
    private val initialCloudServerId = savedStateHandle.get<Int>(IMAGE_CLOUD_SERVER_ID_KEY)
    private val initialCloudServerIds = savedStateHandle.get<ArrayList<Int>>(IMAGE_CLOUD_SERVER_IDS_KEY)?.toSet()

    private var cloudLoadJob: Job? = null
    private var cloudLoadRequestToken: Long = 0L
    private var localPublishJob: Job? = null

    private var localImages = emptyList<LocalImage>()

    private val indexedCloudImageCache = ConcurrentHashMap<String, List<CloudIndexedImage>>()
    private val imageHostingSearchCache = ConcurrentHashMap<String, List<FsSearchItem>>()
    private val preloadJobs = ConcurrentHashMap<String, Job>()
    private val cloudPreviewSemaphore = Semaphore(MAX_CLOUD_PREVIEW_CONCURRENT_REQUESTS)
    private val pendingCloudDimensionUpdates = mutableMapOf<String, Pair<Int, Int>>()
    private var cloudDimensionFlushJob: Job? = null

    private val uiStateInternal = MutableStateFlow(
        ImageBrowserUiState(
            preferences = preferencesRepository.applicationPreferences.value,
            mode = preferencesRepository.applicationPreferences.value.imageLastMediaMode,
            selectedCloudServerIds = initialCloudServerIds
                ?: preferencesRepository.applicationPreferences.value.imageLastSelectedCloudServerIds.ifEmpty {
                    initialCloudServerId?.let(::setOf) ?: emptySet()
                },
            selectedCloudServerId = initialCloudServerId,
            cloudPath = initialCloudPath,
        ),
    )
    val uiState: StateFlow<ImageBrowserUiState> = uiStateInternal.asStateFlow()

    private var _localImageLoader: ImageLoader? = null
    val localImageLoader: ImageLoader
        get() = _localImageLoader ?: createImageBrowserLoader(context).also { _localImageLoader = it }

    override fun onCleared() {
        super.onCleared()
        _localImageLoader?.shutdown()
    }

    private fun createImageBrowserLoader(context: Context): ImageLoader {
        val percent = preferencesRepository.applicationPreferences.value.imageBrowserMemoryCachePercent
            .coerceIn(
                ApplicationPreferences.MIN_IMAGE_BROWSER_MEMORY_CACHE_PERCENT,
                ApplicationPreferences.MAX_IMAGE_BROWSER_MEMORY_CACHE_PERCENT,
            )
        val cacheBytes = ImageCacheManager.memoryCacheBytesFromRamPercent(context, percent)
        return ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizeBytes(cacheBytes)
                    .build()
            }
            .components { add(LibyuvBitmapDecoder.Factory()) }
            .diskCachePolicy(CachePolicy.DISABLED)
            .diskCache(null)
            .build()
    }

    init {
        viewModelScope.launch {
            var previousPreferences = preferencesRepository.applicationPreferences.value
            preferencesRepository.applicationPreferences.collect { preferences ->
                val shouldReloadCloud = uiStateInternal.value.mode == MediaMode.CLOUD &&
                    preferences.hasDifferentImageCloudDisplayShape(previousPreferences)
                ImageViewerStore.imageCloudDiskCacheEnabled = preferences.imageCloudDiskCacheEnabled
                uiStateInternal.update { it.copy(preferences = preferences) }
                publishLocalFolder()
                if (shouldReloadCloud) {
                    loadCloudDirectory()
                }
                previousPreferences = preferences
            }
        }

        viewModelScope.launch {
            var hadServers = false
            webDavServerRepository.observeServers().collect { servers ->
                val serversJustBecameAvailable = !hadServers && servers.isNotEmpty()
                hadServers = servers.isNotEmpty()
                uiStateInternal.update { state ->
                    val selectedId = state.selectedCloudServerId
                    val selectedServerStillAvailable = selectedId != null && servers.any { it.id == selectedId }
                    val selectedIds = state.selectedCloudServerIds.filter { id -> servers.any { it.id == id } }.toSet()
                    val inferredServerId = findBestServerIdForPath(servers = servers, path = state.cloudPath)
                    val nextServerId = when {
                        selectedServerStillAvailable -> selectedId
                        inferredServerId != null -> inferredServerId
                        selectedIds.isNotEmpty() -> selectedIds.first()
                        servers.isNotEmpty() -> servers.first().id
                        else -> null
                    }
                    val nextPath = when {
                        selectedIds.size > 1 -> ROOT_PATH
                        selectedServerStillAvailable -> state.cloudPath
                        inferredServerId != null && normalizePath(state.cloudPath) != ROOT_PATH -> state.cloudPath

                        else -> servers.firstOrNull()?.basePath.orEmpty()
                    }.let(::normalizePath)

                    state.copy(
                        webDavServers = servers,
                        selectedCloudServerIds = when {
                            selectedIds.isNotEmpty() -> selectedIds
                            servers.isNotEmpty() -> setOf(servers.first().id)
                            else -> emptySet()
                        },
                        selectedCloudServerId = nextServerId,
                        cloudPath = nextPath,
                    )
                }
                persistCloudState(
                    path = uiStateInternal.value.cloudPath,
                    serverId = uiStateInternal.value.selectedCloudServerId,
                )
                if (uiStateInternal.value.mode == MediaMode.CLOUD && serversJustBecameAvailable) {
                    loadCloudDirectory()
                }
            }
        }

        loadLocalImages()
    }

    fun onEvent(event: ImageBrowserUiEvent) {
        when (event) {
            ImageBrowserUiEvent.ToggleMode -> toggleMode()
            is ImageBrowserUiEvent.UpdateMenu -> updateMenu(event.preferences)
            is ImageBrowserUiEvent.OpenLocalFolder -> openLocalFolder(event.path)
            ImageBrowserUiEvent.NavigateLocalUp -> navigateLocalUp()
            is ImageBrowserUiEvent.SelectCloudServer -> selectCloudServer(event.serverId)
            is ImageBrowserUiEvent.ToggleCloudServerSelection -> toggleCloudServerSelection(event.serverId)
            is ImageBrowserUiEvent.OpenCloudFolder -> openCloudFolder(event.path)
            is ImageBrowserUiEvent.PreloadCloudPath -> preloadCloudPath(event.path)
            ImageBrowserUiEvent.NavigateCloudUp -> navigateCloudUp()
            ImageBrowserUiEvent.RefreshCloud -> loadCloudDirectory(refreshing = true)
            ImageBrowserUiEvent.RefreshLocal -> loadLocalImages(refreshing = true)
            ImageBrowserUiEvent.LoadNextCloudPage -> loadNextCloudPage()
            is ImageBrowserUiEvent.ReportCloudImageDimensions -> reportCloudImageDimensions(event.uriString, event.width, event.height)
        }
    }

    private fun toggleMode() {
        val currentState = uiStateInternal.value
        val nextMode = if (uiStateInternal.value.mode == MediaMode.LOCAL) MediaMode.CLOUD else MediaMode.LOCAL
        val selectedServer = currentState.webDavServers.firstOrNull { it.id == currentState.selectedCloudServerId }
            ?: currentState.webDavServers.firstOrNull()
        val selectedServerBasePath = selectedServer?.basePath?.let(::normalizePath)
        val currentCloudPath = normalizePath(currentState.cloudPath)
        val currentLocalPath = normalizePath(currentState.localPath)

        val nextCloudPath = if (
            nextMode == MediaMode.CLOUD &&
            selectedServerBasePath != null &&
            (currentCloudPath == currentLocalPath || currentCloudPath == ROOT_PATH)
        ) {
            selectedServerBasePath
        } else {
            currentCloudPath
        }

        uiStateInternal.update {
            it.copy(
                mode = nextMode,
                cloudPath = nextCloudPath,
            )
        }
        if (nextMode == MediaMode.CLOUD) {
            persistCloudState(path = nextCloudPath, serverId = selectedServer?.id)
        }
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences { it.copy(imageLastMediaMode = nextMode) }
        }
        if (nextMode == MediaMode.CLOUD) {
            loadCloudDirectory()
        }
    }

    private fun updateMenu(preferences: ApplicationPreferences) {
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences { preferences }
        }
    }

    private fun openLocalFolder(path: String) {
        val normalizedPath = normalizePath(path)
        savedStateHandle[IMAGE_LOCAL_PATH_KEY] = normalizedPath
        uiStateInternal.update { it.copy(localPath = normalizedPath) }
        publishLocalFolder()
    }

    private fun navigateLocalUp() {
        val state = uiStateInternal.value
        val current = normalizePath(state.localPath)
        if (current == ROOT_PATH) return
        val parent = when (effectiveImageViewMode(state.preferences.imageViewMode)) {
            MediaViewMode.FOLDERS -> ROOT_PATH
            else -> current.substringBeforeLast('/', ROOT_PATH).ifBlank { ROOT_PATH }
        }
        savedStateHandle[IMAGE_LOCAL_PATH_KEY] = parent
        uiStateInternal.update { it.copy(localPath = parent) }
        publishLocalFolder()
    }

    private fun loadLocalImages(refreshing: Boolean = false) {
        viewModelScope.launch {
            if (!refreshing) {
                uiStateInternal.update { it.copy(localGalleryState = ImageGalleryUiState.Loading) }
            }
            runCatching {
                withContext(Dispatchers.IO) { queryImages(context) }
            }
                .onSuccess { images ->
                    localImages = images
                    publishLocalFolder()
                }
                .onFailure { error ->
                    uiStateInternal.update { it.copy(localGalleryState = ImageGalleryUiState.Error(error.message ?: "Failed to load images", error)) }
                }
        }
    }

    private fun publishLocalFolder() {
        val state = uiStateInternal.value
        val currentPath = normalizePath(state.localPath)
        val images = localImages
        val prefs = state.preferences
        localPublishJob?.cancel()
        localPublishJob = viewModelScope.launch(Dispatchers.Default) {
            val folder = mapLocalFolder(localImages = images, currentPath = currentPath, preferences = prefs)
            uiStateInternal.update {
                it.copy(
                    localGalleryState = if (folder.folderList.isEmpty() && folder.mediaList.isEmpty()) {
                        ImageGalleryUiState.Empty
                    } else {
                        ImageGalleryUiState.Content(folder)
                    },
                    localPath = currentPath,
                )
            }
        }
    }

    private fun selectCloudServer(serverId: Int) {
        val server = uiStateInternal.value.webDavServers.firstOrNull { it.id == serverId } ?: return
        val nextServerIds = setOf(serverId)
        uiStateInternal.update {
            it.copy(
                selectedCloudServerId = serverId,
                selectedCloudServerIds = nextServerIds,
                cloudPath = normalizePath(server.basePath),
            )
        }
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences { it.copy(imageLastSelectedCloudServerIds = nextServerIds) }
        }
        loadCloudDirectory()
    }

    private fun toggleCloudServerSelection(serverId: Int) {
        val state = uiStateInternal.value
        if (state.webDavServers.none { it.id == serverId }) return

        val current = state.selectedCloudServerIds.ifEmpty {
            state.selectedCloudServerId?.let(::setOf) ?: emptySet()
        }
        val next = if (current.contains(serverId)) {
            if (current.size == 1) current else current - serverId
        } else {
            current + serverId
        }
        val nextActiveServerId = when {
            next.contains(state.selectedCloudServerId) -> state.selectedCloudServerId
            next.isNotEmpty() -> next.first()
            else -> null
        }
        val nextPath = if (next.size > 1) {
            ROOT_PATH
        } else {
            val nextServer = state.webDavServers.firstOrNull { it.id == nextActiveServerId }
            normalizePath(nextServer?.basePath.orEmpty())
        }
        persistCloudState(path = nextPath, serverId = nextActiveServerId)
        uiStateInternal.update {
            it.copy(
                selectedCloudServerIds = next,
                selectedCloudServerId = nextActiveServerId,
                cloudPath = nextPath,
            )
        }
        savedStateHandle[IMAGE_CLOUD_SERVER_IDS_KEY] = ArrayList(next)
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences { it.copy(imageLastSelectedCloudServerIds = next) }
        }
        loadCloudDirectory()
    }

    private fun openCloudFolder(path: String) {
        val currentServerId = uiStateInternal.value.selectedCloudServerId
        val target = decodeCloudFolderPath(path)
        if (target != null) {
            persistCloudState(path = normalizePath(target.second), serverId = target.first)
            uiStateInternal.update {
                it.copy(
                    selectedCloudServerId = target.first,
                    cloudPath = normalizePath(target.second),
                )
            }
        } else {
            val normalizedPath = normalizePath(path)
            persistCloudState(path = normalizedPath, serverId = currentServerId)
            uiStateInternal.update { it.copy(cloudPath = normalizePath(path)) }
        }
        loadCloudDirectory()
    }

    private fun navigateCloudUp() {
        val state = uiStateInternal.value
        val server = state.webDavServers.firstOrNull { it.id == state.selectedCloudServerId } ?: return
        val root = normalizePath(server.basePath)
        val current = normalizePath(state.cloudPath)
        if (current == root) return
        val parent = current.substringBeforeLast('/', root).ifBlank { root }
        persistCloudState(path = normalizePath(parent), serverId = state.selectedCloudServerId)
        uiStateInternal.update { it.copy(cloudPath = normalizePath(parent)) }
        loadCloudDirectory()
    }

    private fun persistCloudState(path: String, serverId: Int?) {
        savedStateHandle[IMAGE_CLOUD_PATH_KEY] = normalizePath(path)
        savedStateHandle[IMAGE_CLOUD_SERVER_ID_KEY] = serverId
        savedStateHandle[IMAGE_CLOUD_SERVER_IDS_KEY] = ArrayList(uiStateInternal.value.selectedCloudServerIds)
    }

    private fun loadCloudDirectory(refreshing: Boolean = false) {
        val state = uiStateInternal.value
        val selectedServerIds = state.selectedCloudServerIds.ifEmpty {
            state.selectedCloudServerId?.let(::setOf) ?: emptySet()
        }

        if (selectedServerIds.size > 1 && normalizePath(state.cloudPath) == ROOT_PATH) {
            loadMultiCloudRootDirectory(refreshing = refreshing, selectedServerIds = selectedServerIds)
            return
        }
        val server = state.webDavServers.firstOrNull { it.id == state.selectedCloudServerId }
            ?: state.webDavServers.firstOrNull()
            ?: run {
                uiStateInternal.update { it.copy(cloudRefreshing = false) }
                return
            }
        val path = normalizePath(state.cloudPath.ifBlank { server.basePath })
        val preferences = state.preferences

        cloudLoadJob?.cancel()
        val requestToken = ++cloudLoadRequestToken

        uiStateInternal.update {
            it.copy(
                selectedCloudServerId = server.id,
                cloudPath = path,
                cloudRefreshing = refreshing,
                cloudGalleryState = when {
                    refreshing -> it.cloudGalleryState
                    else -> ImageGalleryUiState.Loading
                },
                cloudLoadingPhase = if (refreshing) ImageCloudLoadingPhase.REFRESHING_REMOTE else ImageCloudLoadingPhase.READING_SNAPSHOT,
                cloudPage = 1,
                cloudHasMore = true,
                cloudTotalItems = 0,
                cloudLoadingMore = false,
                cloudError = null,
            )
        }

        cloudLoadJob = viewModelScope.launch {
            val perPage = 50
            if (!refreshing) {
                if (server.isImageHosting && effectiveImageViewMode(preferences.imageViewMode).showsImagesOnly()) {
                    publishCachedImageHostingImages(
                        server = server,
                        path = path,
                        preferences = preferences,
                        requestToken = requestToken,
                    )
                } else {
                    publishCachedCloudDirectory(
                        server = server,
                        path = path,
                        preferences = preferences,
                        requestToken = requestToken,
                    )
                }
            }
            if (server.isImageHosting && effectiveImageViewMode(preferences.imageViewMode).showsImagesOnly()) {
                loadImageHostingImagePage(
                    server = server,
                    path = path,
                    page = 1,
                    perPage = perPage,
                    preferences = preferences,
                    requestToken = requestToken,
                    refreshing = refreshing,
                )
                return@launch
            }
            val indexedFolder = withContext(Dispatchers.IO) {
                buildIndexedCloudFolder(
                    server = server,
                    path = path,
                    preferences = preferences,
                    refreshing = refreshing,
                )
            }
            if (indexedFolder != null) {
                if (requestToken != cloudLoadRequestToken) return@launch
                val displayFolder = enrichFolderWithDbMetadata(server.id, indexedFolder)
                uiStateInternal.update {
                    it.copy(
                        cloudGalleryState = if (displayFolder.folderList.isEmpty() && displayFolder.mediaList.isEmpty()) {
                            ImageGalleryUiState.Empty
                        } else {
                            ImageGalleryUiState.Content(displayFolder)
                        },
                        cloudPage = 1,
                        cloudHasMore = false,
                        cloudTotalItems = displayFolder.folderList.size + displayFolder.mediaList.size,
                        cloudLoadingMore = false,
                        cloudRefreshing = false,
                        cloudError = null,
                    )
                }
                launch(Dispatchers.IO) {
                    saveImageFolderMetadataToDb(server, displayFolder)
                }
                if (displayFolder.folderList.isNotEmpty()) {
                    launch {
                        refreshCloudFolderPreviewsAsync(
                            server = server,
                            folder = displayFolder,
                            preferences = preferences,
                            requestToken = requestToken,
                        )
                    }
                }
                val dimensionTargets = collectMissingDimensionTargets(displayFolder)
                if (dimensionTargets.isNotEmpty()) {
                    launch { probeMissingCloudDimensions(dimensionTargets) }
                }
                return@launch
            }

            val apiPath = server.toApiPath(path)
            openListApi.listDirectory(server, apiPath, page = 1, perPage = perPage, refresh = refreshing)
                .onSuccess { data ->
                    if (requestToken != cloudLoadRequestToken) return@onSuccess
                    val items = data.content.orEmpty().map { it.toWebDavMediaItem(server, apiPath) }
                    webDavDirectoryCache.put(server.id, path, items)
                    launch(Dispatchers.IO) {
                        cloudDirectoryItemCache.put(server.id, path, items)
                    }
                    val sourceFolder = mapCloudFolder(server = server, path = apiPath, preferences = preferences, items = items)
                    val rawFolder = resolveCloudLeafFolders(server = server, folder = sourceFolder, preferences = preferences)
                    val folder = enrichFolderWithDbMetadata(server.id, rawFolder)
                    val total = data.total
                    val hasMore = 1 * perPage < total

                    val currentContent = uiStateInternal.value.cloudGalleryState as? ImageGalleryUiState.Content
                    val mergedFolder = if (currentContent != null) {
                        mergeDimensionsFromOldState(server.id, currentContent.folder, folder)
                    } else {
                        enrichFolderWithDimensionCache(server.id, folder)
                    }

                    val displayFolder = withContext(Dispatchers.IO) {
                        enrichFolderWithDbMetadata(server.id, mergedFolder)
                    }

                    uiStateInternal.update {
                        it.copy(
                            cloudGalleryState = if (displayFolder.folderList.isEmpty() && displayFolder.mediaList.isEmpty()) {
                                ImageGalleryUiState.Empty
                            } else {
                                ImageGalleryUiState.Content(displayFolder)
                            },
                            cloudPage = 1,
                            cloudHasMore = hasMore,
                            cloudTotalItems = total,
                            cloudLoadingMore = false,
                            cloudRefreshing = false,
                            cloudError = null,
                        )
                    }
                    if (server.isImageHosting) {
                        launch(Dispatchers.IO) {
                            saveCurrentImageFolderMetadataToDb(server, displayFolder)
                        }
                    }

                    val dimensionTargets = if (server.isImageHosting) {
                        collectMissingDimensionTargets(displayFolder).take(imageHostingDimensionProbeLimit(preferences, perPage))
                    } else {
                        collectMissingDimensionTargets(displayFolder)
                    }
                    if (dimensionTargets.isNotEmpty()) {
                        launch {
                            probeMissingCloudDimensions(
                                targets = dimensionTargets,
                                concurrency = if (server.isImageHosting) IMAGE_HOSTING_DIMENSION_PROBE_CONCURRENCY else DEFAULT_CLOUD_DIMENSION_PROBE_CONCURRENCY,
                            )
                        }
                    }

                    if (folder.folderList.isNotEmpty()) {
                        launch {
                            saveImageFolderMetadataToDb(server, folder)
                        }
                        launch {
                            refreshCloudFolderPreviewsAsync(
                                server = server,
                                folder = folder,
                                preferences = preferences,
                                requestToken = requestToken,
                            )
                        }
                    }
                    preloadCloudPagesAhead(
                        server = server,
                        path = path,
                        currentFolder = displayFolder,
                        currentPage = 1,
                        perPage = perPage,
                        totalItems = total,
                        preferences = preferences,
                        requestToken = requestToken,
                    )
                }
                .onFailure { error ->
                    if (error is CancellationException || requestToken != cloudLoadRequestToken) return@onFailure
                    uiStateInternal.update {
                        it.copy(
                            cloudGalleryState = ImageGalleryUiState.Error(formatCloudErrorMessage(error), error),
                            cloudLoadingMore = false,
                            cloudRefreshing = false,
                            cloudError = formatCloudErrorMessage(error),
                        )
                    }
                }
        }
    }

    private fun loadNextCloudPage() {
        val state = uiStateInternal.value
        if (!state.cloudHasMore || state.cloudLoadingMore) return
        val server = state.webDavServers.firstOrNull { it.id == state.selectedCloudServerId }
            ?: state.webDavServers.firstOrNull()
            ?: return
        val path = normalizePath(state.cloudPath)
        val nextPage = state.cloudPage + 1
        val perPage = 50
        val preferences = state.preferences

        uiStateInternal.update { it.copy(cloudLoadingMore = true, cloudError = null) }

        viewModelScope.launch {
            if (server.isImageHosting && effectiveImageViewMode(preferences.imageViewMode).showsImagesOnly()) {
                loadImageHostingImagePage(
                    server = server,
                    path = path,
                    page = nextPage,
                    perPage = perPage,
                    preferences = preferences,
                    requestToken = cloudLoadRequestToken,
                    append = true,
                )
                return@launch
            }
            val apiPath = server.toApiPath(path)
            openListApi.listDirectory(server, apiPath, page = nextPage, perPage = perPage)
                .onSuccess { data ->
                    val newItems = data.content.orEmpty().map { it.toWebDavMediaItem(server, apiPath) }

                    val newFolder = mapCloudFolder(server = server, path = apiPath, preferences = preferences, items = newItems)
                    preloadCloudPageAssets(newFolder, preferences)
                    val currentContent = uiStateInternal.value.cloudGalleryState as? ImageGalleryUiState.Content
                    val folder = currentContent?.folder?.copy(
                        mediaList = currentContent.folder.mediaList + newFolder.mediaList,
                    ) ?: enrichFolderWithDbMetadata(server.id, newFolder)
                    val total = data.total
                    val hasMore = nextPage * perPage < total
                    uiStateInternal.update {
                        it.copy(
                            cloudGalleryState = ImageGalleryUiState.Content(folder),
                            cloudPage = nextPage,
                            cloudHasMore = hasMore,
                            cloudTotalItems = total,
                            cloudLoadingMore = false,
                            cloudError = null,
                        )
                    }

                    val dimensionTargets = if (server.isImageHosting) {
                        collectMissingDimensionTargets(folder).take(imageHostingDimensionProbeLimit(preferences, perPage))
                    } else {
                        collectMissingDimensionTargets(folder)
                    }
                    if (dimensionTargets.isNotEmpty()) {
                        launch {
                            probeMissingCloudDimensions(
                                targets = dimensionTargets,
                                concurrency = if (server.isImageHosting) IMAGE_HOSTING_DIMENSION_PROBE_CONCURRENCY else DEFAULT_CLOUD_DIMENSION_PROBE_CONCURRENCY,
                            )
                        }
                    }

                    preloadCloudPagesAhead(
                        server = server,
                        path = path,
                        currentFolder = folder,
                        currentPage = nextPage,
                        perPage = perPage,
                        totalItems = total,
                        preferences = preferences,
                        requestToken = cloudLoadRequestToken,
                    )
                }
                .onFailure { error ->
                    if (error is CancellationException) return@onFailure
                    uiStateInternal.update {
                        it.copy(
                            cloudLoadingMore = false,
                            cloudError = formatCloudErrorMessage(error),
                        )
                    }
                }
        }
    }

    private suspend fun loadImageHostingImagePage(
        server: WebDavServer,
        path: String,
        page: Int,
        perPage: Int,
        preferences: ApplicationPreferences,
        requestToken: Long,
        refreshing: Boolean = false,
        append: Boolean = false,
    ) {
        val apiPath = server.toApiPath(path)
        val cacheKey = imageHostingSearchCacheKey(server.id, path)
        val searchResult = runCatching {
            val cachedItems = if (!refreshing) imageHostingSearchCache[cacheKey] else null
            if (cachedItems != null) {
                FsSearchDataSnapshot(total = cachedItems.size, content = cachedItems)
            } else {
                val data = openListApi.search(
                    server = server,
                    parent = apiPath,
                    keywords = "",
                    scope = 2,
                    page = 1,
                    perPage = IMAGE_HOSTING_INDEX_ALL_COUNT,
                ).getOrThrow()
                val content = data.content.orEmpty()
                imageHostingSearchCache[cacheKey] = content
                FsSearchDataSnapshot(
                    total = data.total.takeIf { it > 0 } ?: content.size,
                    content = content,
                )
            }
        }
        searchResult
            .onSuccess { data ->
                if (requestToken != cloudLoadRequestToken) return@onSuccess
                val sort = imageSort(preferences)
                val syntheticItems = data.content
                    .filter { it.isIndexedCloudImageFile() }
                    .map { item ->
                        val parentPath = normalizePath(item.parent)
                        item.toSyntheticImageWebDavMediaItem(
                            server = server,
                            parentPath = parentPath,
                        )
                    }
                val images = syntheticItems
                    .map { item ->
                        val parentPath = normalizePath(resolveRelativePath(server, item.href).substringBeforeLast('/', path))
                        mapCloudImageItemToVideo(
                            server = server,
                            path = parentPath,
                            item = item,
                        )
                    }
                    .orderedCloudVideos(server, sort)
                val currentFolder = (uiStateInternal.value.cloudGalleryState as? ImageGalleryUiState.Content)?.folder
                val cachedSnapshotImages = currentFolder
                    ?.takeIf { !append && normalizePath(it.path) == normalizePath(path) && images.isEmpty() }
                    ?.mediaList
                    .orEmpty()
                val sortedImages = when {
                    cachedSnapshotImages.isNotEmpty() -> images + cachedSnapshotImages
                    else -> images
                }
                    .distinctBy { stableCacheKey(it.uriString) }
                    .orderedCloudVideos(server, sort)
                val total = maxOf(data.total, sortedImages.size)
                val pageLimit = (page * perPage).coerceAtLeast(perPage)
                val folderImages = sortedImages.take(pageLimit)
                val pageImages = sortedImages.drop(((page - 1).coerceAtLeast(0) * perPage)).take(perPage)
                Logger.d(
                    TAG,
                    "imageHostingPage loaded page=$page append=$append pageImages=${pageImages.size} visible=${folderImages.size} total=$total",
                )
                if (append) {
                    warmCloudThumbnailCache(pageImages, preferences)
                }
                val folder = Folder(
                    name = cloudFolderDisplayName(server, path),
                    path = normalizePath(path),
                    dateModified = folderImages.maxOfOrNull { it.dateModified } ?: System.currentTimeMillis(),
                    mediaList = folderImages,
                    folderList = emptyList(),
                    mediaCount = folderImages.size,
                    folderCount = 0,
                    cachedMediaSize = folderImages.sumOf { it.size },
                )
                uiStateInternal.update {
                    it.copy(
                        cloudGalleryState = if (folder.mediaList.isEmpty()) {
                            ImageGalleryUiState.Empty
                        } else {
                            ImageGalleryUiState.Content(folder)
                        },
                        cloudPage = page,
                        cloudHasMore = pageLimit < total,
                        cloudTotalItems = total,
                        cloudLoadingMore = false,
                        cloudRefreshing = false,
                        cloudError = null,
                    )
                }
                viewModelScope.launch(Dispatchers.IO) {
                    saveImageHostingImageItemsSnapshot(server.id, path, syntheticItems, append)
                    saveCurrentImageFolderMetadataToDb(server, folder)
                }
                val dimensionTargets = collectMissingDimensionTargets(folder)
                    .take(imageHostingDimensionProbeLimit(preferences, perPage))
                if (dimensionTargets.isNotEmpty()) {
                    viewModelScope.launch {
                        probeMissingCloudDimensions(
                            targets = dimensionTargets,
                            concurrency = IMAGE_HOSTING_DIMENSION_PROBE_CONCURRENCY,
                        )
                    }
                }
                if (!append) {
                    preloadImageHostingImagePagesAhead(
                        server = server,
                        path = path,
                        currentPage = page,
                        perPage = perPage,
                        totalItems = total,
                        preferences = preferences,
                        requestToken = requestToken,
                    )
                }
            }
            .onFailure { error ->
                if (error is CancellationException || requestToken != cloudLoadRequestToken) return@onFailure
                uiStateInternal.update {
                    it.copy(
                        cloudGalleryState = if (append) it.cloudGalleryState else ImageGalleryUiState.Error(formatCloudErrorMessage(error), error),
                        cloudLoadingMore = false,
                        cloudRefreshing = false,
                        cloudError = formatCloudErrorMessage(error),
                    )
                }
            }
    }

    private fun preloadImageHostingImagePagesAhead(
        server: WebDavServer,
        path: String,
        currentPage: Int,
        perPage: Int,
        totalItems: Int,
        preferences: ApplicationPreferences,
        requestToken: Long,
    ) {
        val pagesToPreload = preferences.imageBrowserPreloadPageCount.coerceIn(
            ApplicationPreferences.MIN_IMAGE_BROWSER_PRELOAD_PAGE_COUNT,
            ApplicationPreferences.MAX_IMAGE_BROWSER_PRELOAD_PAGE_COUNT,
        )
        if (pagesToPreload <= 0 || currentPage * perPage >= totalItems) return

        Logger.d(
            TAG,
            "imageHostingPreload start currentPage=$currentPage pages=$pagesToPreload perPage=$perPage total=$totalItems",
        )
        viewModelScope.launch(Dispatchers.IO) {
            var page = currentPage
            repeat(pagesToPreload) {
                if (requestToken != cloudLoadRequestToken || page * perPage >= totalItems) return@launch
                page += 1
                loadImageHostingImagePage(
                    server = server,
                    path = path,
                    page = page,
                    perPage = perPage,
                    preferences = preferences,
                    requestToken = requestToken,
                    append = true,
                )
            }
        }
    }

    private fun imageHostingDimensionProbeLimit(
        preferences: ApplicationPreferences,
        perPage: Int,
    ): Int {
        val pagesToPreload = preferences.imageBrowserPreloadPageCount.coerceIn(
            ApplicationPreferences.MIN_IMAGE_BROWSER_PRELOAD_PAGE_COUNT,
            ApplicationPreferences.MAX_IMAGE_BROWSER_PRELOAD_PAGE_COUNT,
        )
        return perPage * (pagesToPreload + 1)
    }

    private fun imageHostingImageSnapshotPath(path: String): String = "$IMAGE_HOSTING_IMAGE_SNAPSHOT_PREFIX${normalizePath(path)}"

    private fun imageHostingSearchCacheKey(serverId: Int, path: String): String = "$serverId:${normalizePath(path)}"

    private suspend fun publishCachedImageHostingImages(
        server: WebDavServer,
        path: String,
        preferences: ApplicationPreferences,
        requestToken: Long,
    ) {
        val cachedItems = cloudDirectoryItemCache.get(server.id, imageHostingImageSnapshotPath(path))
        if (cachedItems.isEmpty()) return
        val sort = imageSort(preferences)
        val images = cachedItems
            .filter { it.isImage }
            .map { item ->
                val parentPath = normalizePath(resolveRelativePath(server, item.href).substringBeforeLast('/', path))
                mapCloudImageItemToVideo(
                    server = server,
                    path = parentPath,
                    item = item,
                )
            }
            .orderedCloudVideos(server, sort)
        if (images.isEmpty() || requestToken != cloudLoadRequestToken) return

        val folder = Folder(
            name = cloudFolderDisplayName(server, path),
            path = normalizePath(path),
            dateModified = images.maxOfOrNull { it.dateModified } ?: System.currentTimeMillis(),
            mediaList = images,
            folderList = emptyList(),
            mediaCount = images.size,
            folderCount = 0,
            cachedMediaSize = images.sumOf { it.size },
        )
        uiStateInternal.update {
            it.copy(
                cloudGalleryState = ImageGalleryUiState.Content(folder),
                cloudPage = 1,
                cloudHasMore = false,
                cloudTotalItems = images.size,
                cloudLoadingMore = false,
                cloudRefreshing = false,
                cloudLoadingPhase = ImageCloudLoadingPhase.REFRESHING_REMOTE,
                cloudError = null,
            )
        }
    }

    private suspend fun saveImageHostingImageItemsSnapshot(
        serverId: Int,
        path: String,
        items: List<WebDavMediaItem>,
        append: Boolean,
    ) {
        if (items.isEmpty()) return
        val snapshotPath = imageHostingImageSnapshotPath(path)
        val mergedItems = if (append) {
            val existing = cloudDirectoryItemCache.get(serverId, snapshotPath)
            (existing + items).distinctBy { it.href }
        } else {
            items
        }
        cloudDirectoryItemCache.put(serverId, snapshotPath, mergedItems)
    }

    private suspend fun publishCachedCloudDirectory(
        server: WebDavServer,
        path: String,
        preferences: ApplicationPreferences,
        requestToken: Long,
    ) {
        val cachedItems = getCachedCloudDirectoryItems(server.id, path) ?: return
        val apiPath = server.toApiPath(path)
        val sourceFolder = mapCloudFolder(server = server, path = apiPath, preferences = preferences, items = cachedItems)
        val rawFolder = resolveCloudLeafFolders(server = server, folder = sourceFolder, preferences = preferences)
        val displayFolder = enrichFolderWithDbMetadata(server.id, rawFolder)
        if (requestToken != cloudLoadRequestToken) return
        uiStateInternal.update {
            it.copy(
                cloudGalleryState = if (displayFolder.folderList.isEmpty() && displayFolder.mediaList.isEmpty()) {
                    ImageGalleryUiState.Empty
                } else {
                    ImageGalleryUiState.Content(displayFolder)
                },
                cloudTotalItems = displayFolder.folderList.size + displayFolder.mediaList.size,
                cloudRefreshing = false,
                cloudError = null,
            )
        }
    }

    private fun preloadCloudPagesAhead(
        server: WebDavServer,
        path: String,
        currentFolder: Folder,
        currentPage: Int,
        perPage: Int,
        totalItems: Int,
        preferences: ApplicationPreferences,
        requestToken: Long,
    ) {
        val pagesToPreload = preferences.imageBrowserPreloadPageCount.coerceIn(
            ApplicationPreferences.MIN_IMAGE_BROWSER_PRELOAD_PAGE_COUNT,
            ApplicationPreferences.MAX_IMAGE_BROWSER_PRELOAD_PAGE_COUNT,
        )
        if (pagesToPreload <= 0 || currentPage * perPage >= totalItems) return

        Logger.d(
            TAG,
            "cloudPreload start currentPage=$currentPage pages=$pagesToPreload perPage=$perPage total=$totalItems media=${currentFolder.mediaList.size}",
        )
        viewModelScope.launch(Dispatchers.IO) {
            var page = currentPage
            var folder = currentFolder
            val apiPath = server.toApiPath(path)
            repeat(pagesToPreload) {
                if (requestToken != cloudLoadRequestToken || page * perPage >= totalItems) return@launch
                val nextPage = page + 1
                val data = openListApi.listDirectory(server, apiPath, page = nextPage, perPage = perPage)
                    .getOrNull()
                    ?: return@launch
                if (requestToken != cloudLoadRequestToken) return@launch

                val newItems = data.content.orEmpty().map { it.toWebDavMediaItem(server, apiPath) }
                val newFolder = mapCloudFolder(server = server, path = apiPath, preferences = preferences, items = newItems)
                Logger.d(TAG, "cloudPreload pageFetched page=$nextPage media=${newFolder.mediaList.size} folders=${newFolder.folderList.size}")
                preloadCloudPageAssets(newFolder, preferences)
                folder = appendCloudPageFolder(server.id, folder, newFolder)
                page = nextPage

                withContext(Dispatchers.Main) {
                    if (requestToken != cloudLoadRequestToken) return@withContext
                    uiStateInternal.update { state ->
                        val currentContent = state.cloudGalleryState as? ImageGalleryUiState.Content
                        if (
                            currentContent != null &&
                            state.selectedCloudServerId == server.id &&
                            normalizePath(state.cloudPath) == normalizePath(path) &&
                            state.cloudPage < page
                        ) {
                            state.copy(
                                cloudGalleryState = ImageGalleryUiState.Content(folder),
                                cloudPage = page,
                                cloudHasMore = page * perPage < data.total,
                                cloudTotalItems = data.total,
                                cloudError = null,
                            )
                        } else {
                            state
                        }
                    }
                }
            }
        }
    }

    private suspend fun preloadCloudPageAssets(
        folder: Folder,
        preferences: ApplicationPreferences,
    ) = coroutineScope {
        Logger.d(TAG, "cloudPreload assets start media=${folder.mediaList.size} folders=${folder.folderList.size}")
        val thumbnailWarmJob = async {
            warmCloudThumbnailCache(folder.mediaList, preferences)
        }
        val dimensionTargets = collectMissingDimensionTargets(folder)
        val dimensionProbeJob = if (dimensionTargets.isNotEmpty()) {
            async {
                probeMissingCloudDimensions(dimensionTargets)
            }
        } else {
            null
        }
        thumbnailWarmJob.await()
        dimensionProbeJob?.await()
        Logger.d(TAG, "cloudPreload assets done media=${folder.mediaList.size} dimensions=${dimensionTargets.size}")
    }

    private suspend fun appendCloudPageFolder(serverId: Int, current: Folder, next: Folder): Folder {
        val existingFolderPaths = current.folderList.map { it.path }.toHashSet()
        val existingMediaUris = current.mediaList.map { it.uriString }.toHashSet()
        return enrichFolderWithDbMetadata(
            serverId,
            current.copy(
                folderList = current.folderList + next.folderList.filter { existingFolderPaths.add(it.path) },
                mediaList = current.mediaList + next.mediaList.filter { existingMediaUris.add(it.uriString) },
            ),
        )
    }

    private fun loadMultiCloudRootDirectory(
        refreshing: Boolean,
        selectedServerIds: Set<Int>,
    ) {
        val state = uiStateInternal.value
        val selectedServers = state.webDavServers.filter { it.id in selectedServerIds }
        if (selectedServers.isEmpty()) {
            uiStateInternal.update { it.copy(cloudRefreshing = false) }
            return
        }

        cloudLoadJob?.cancel()
        val requestToken = ++cloudLoadRequestToken
        cloudLoadJob = viewModelScope.launch {
            uiStateInternal.update {
                val keepExistingData = !refreshing && it.cloudGalleryState is ImageGalleryUiState.Content
                it.copy(
                    cloudPath = ROOT_PATH,
                    cloudRefreshing = refreshing,
                    cloudGalleryState = when {
                        refreshing || keepExistingData -> it.cloudGalleryState
                        else -> ImageGalleryUiState.Loading
                    },
                )
            }

            val sort = imageSort(state.preferences)
            val viewMode = effectiveImageViewMode(state.preferences.imageViewMode)
            val mergedFolders = mutableListOf<Folder>()
            val mergedImages = mutableListOf<Video>()
            var anySuccess = false
            var firstError: Throwable? = null

            val indexedRoot = withContext(Dispatchers.IO) {
                buildIndexedMultiCloudRoot(
                    selectedServers = selectedServers,
                    preferences = state.preferences,
                    refreshing = refreshing,
                )
            }
            if (indexedRoot != null) {
                if (requestToken != cloudLoadRequestToken) return@launch
                uiStateInternal.update {
                    it.copy(
                        cloudRefreshing = false,
                        cloudGalleryState = ImageGalleryUiState.Content(indexedRoot),
                    )
                }
                val dimensionTargets = collectMissingDimensionTargets(indexedRoot)
                if (dimensionTargets.isNotEmpty()) {
                    launch { probeMissingCloudDimensions(dimensionTargets) }
                }
                return@launch
            }

            val semaphore = Semaphore(MULTI_CLOUD_MAX_CONCURRENT_REQUESTS)
            val serverResults = coroutineScope {
                selectedServers.map { server ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            server to runCatching {
                                val apiPath = server.toApiPath(normalizePath(server.basePath))
                                val data = openListApi.listDirectory(server, apiPath, page = 1, perPage = 200).getOrThrow()
                                data.content.orEmpty().map { it.toWebDavMediaItem(server, apiPath) }
                            }
                        }
                    }
                }.awaitAll()
            }

            serverResults.forEach { (server, result) ->
                result.onFailure { if (firstError == null) firstError = it }
                val items = result.getOrNull() ?: return@forEach
                anySuccess = true

                items.filter { it.isDirectory }.forEach { item ->
                    val folderPath = normalizePath(resolveRelativePath(server, item.href))
                    val displayName = Uri.decode(item.name).ifBlank { folderPath.substringAfterLast('/') }
                    val (coverMedia, mediaCount) = buildCloudFolderPreview(
                        server = server,
                        folderPath = folderPath,
                        sort = sort,
                    )
                    mergedFolders += Folder(
                        name = displayName,
                        path = encodeCloudFolderPath(server.id, folderPath),
                        dateModified = item.lastModified?.time ?: 0L,
                        parentPath = toWebDavAbsolutePath(server, ROOT_PATH),
                        coverMedia = coverMedia,
                        mediaCount = mediaCount,
                    )
                }

                items.filter { it.isImage }.forEach { item ->
                    val imageUrl = webDavRepository.getStreamUrl(item, server).stripUserInfoFromHttpUrl()
                    val decodedPath = Uri.decode(item.href.toUri().path.orEmpty())
                    val displayName = Uri.decode(item.name).ifBlank { decodedPath.substringAfterLast('/') }
                    val cached = imageDimensionCache.get(server.id, imageUrl)
                    val resolvedWidth = item.width?.takeIf { it > 0 } ?: cached?.first ?: 0
                    val resolvedHeight = item.height?.takeIf { it > 0 } ?: cached?.second ?: 0
                    mergedImages += Video(
                        id = imageUrl.hashCode().toLong(),
                        path = decodedPath.ifBlank { normalizePath(server.basePath) },
                        parentPath = decodedPath.substringBeforeLast('/', ""),
                        duration = 1L,
                        uriString = imageUrl,
                        nameWithExtension = displayName,
                        width = resolvedWidth,
                        height = resolvedHeight,
                        size = item.size,
                        playbackPosition = 0L,
                        dateModified = item.lastModified?.time ?: 0L,
                        formattedDuration = "",
                        formattedFileSize = Utils.formatFileSize(item.size),
                    )
                }
            }

            if (requestToken != cloudLoadRequestToken) return@launch
            if (!anySuccess) {
                uiStateInternal.update {
                    it.copy(
                        cloudRefreshing = false,
                        cloudGalleryState = ImageGalleryUiState.Error(
                            formatCloudErrorMessage(firstError ?: IllegalStateException("Failed to load images")),
                            firstError,
                        ),
                    )
                }
                return@launch
            }

            val (displayFolders, displayImages) = when (viewMode) {
                MediaViewMode.IMAGE,
                MediaViewMode.VIDEOS,
                -> emptyList<Folder>() to mergedImages.sortedWith(sort.videoComparator())
                else -> deduplicateFolderNames(mergedFolders).sortedWith(sort.folderComparator()) to mergedImages.sortedWith(sort.videoComparator())
            }

            val folder = Folder(
                name = "Cloud",
                path = ROOT_PATH,
                dateModified = System.currentTimeMillis(),
                folderList = displayFolders,
                mediaList = displayImages,
            )
            uiStateInternal.update {
                it.copy(
                    cloudRefreshing = false,
                    cloudGalleryState = if (folder.folderList.isEmpty() && folder.mediaList.isEmpty()) {
                        ImageGalleryUiState.Empty
                    } else {
                        ImageGalleryUiState.Content(folder)
                    },
                )
            }

            launch {
                var enrichedFolder = folder
                serverResults.forEach { (server, _) ->
                    val serverFolders = enrichedFolder.folderList.filter {
                        decodeCloudFolderPath(it.path)?.first == server.id
                    }
                    if (serverFolders.isNotEmpty()) {
                        val serverOnlyFolder = folder.copy(folderList = serverFolders)
                        saveImageFolderMetadataToDb(server, serverOnlyFolder)
                        enrichedFolder = enrichFolderWithDbMetadata(server.id, enrichedFolder)
                    }
                }
                if (enrichedFolder.folderList != folder.folderList) {
                    uiStateInternal.update {
                        val currentState = it.cloudGalleryState
                        if (currentState is ImageGalleryUiState.Content) {
                            it.copy(cloudGalleryState = ImageGalleryUiState.Content(enrichedFolder))
                        } else {
                            it
                        }
                    }
                }
            }
        }
    }

    private suspend fun refreshCloudFolderPreviewsAsync(
        server: WebDavServer,
        folder: Folder,
        preferences: ApplicationPreferences,
        requestToken: Long,
    ) {
        if (folder.folderList.isEmpty()) return

        val sort = imageSort(preferences)
        val refreshTargets = if (server.isImageHosting) {
            folder.folderList.take(IMAGE_HOSTING_FOLDER_COVER_PREFETCH_LIMIT)
        } else {
            folder.folderList
        }
        val semaphore = Semaphore(
            if (server.isImageHosting) {
                IMAGE_HOSTING_FOLDER_COVER_PREFETCH_CONCURRENCY
            } else {
                DEFAULT_CLOUD_DIMENSION_PROBE_CONCURRENCY
            },
        )
        val refreshedByPath = coroutineScope {
            refreshTargets.map { child ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        val childPath = decodeCloudFolderPath(child.path)?.second ?: normalizePath(child.path)
                        if (effectiveImageViewMode(preferences.imageViewMode) == MediaViewMode.FOLDER_TREE) {
                            val enriched = enrichCloudTreeFolderItem(server, child.copy(path = childPath), sort)
                            return@withPermit child.path to enriched.copy(path = child.path)
                        }
                        val items = loadCloudPreviewItems(server, childPath)
                            ?: return@withPermit child.path to child
                        val images = items.filter { it.isImage }
                            .map { item -> mapCloudImageItemToVideo(server, childPath, item) }
                            .orderedCloudVideos(server, sort)
                        val directFolderCount = items.count { it.isDirectory }
                        child.path to child.copy(
                            coverMedia = images.firstOrNull() ?: child.coverMedia,
                            mediaCount = if (images.isNotEmpty()) images.size else child.mediaCount,
                            folderCount = maxOf(child.folderCount, directFolderCount),
                        )
                    }
                }
            }.awaitAll().toMap()
        }
        if (requestToken != cloudLoadRequestToken) return

        var changed = false
        val refreshedFolder = folder.copy(
            folderList = folder.folderList.map { child ->
                val refreshed = refreshedByPath[child.path] ?: child
                if (refreshed != child) changed = true
                refreshed
            }.orderedCloudFolders(server, sort),
        )
        if (!changed) return

        saveImageFolderMetadataToDb(server, refreshedFolder)
        val displayFolder = enrichFolderWithDbMetadata(server.id, refreshedFolder)
        if (requestToken != cloudLoadRequestToken) return

        uiStateInternal.update {
            val currentState = it.cloudGalleryState
            if (currentState is ImageGalleryUiState.Content && normalizePath(currentState.folder.path) == normalizePath(folder.path)) {
                it.copy(cloudGalleryState = ImageGalleryUiState.Content(displayFolder))
            } else {
                it
            }
        }
    }

    private fun preloadCloudPath(path: String) {
        val state = uiStateInternal.value
        val server = state.webDavServers.firstOrNull { it.id == state.selectedCloudServerId }
            ?: state.webDavServers.firstOrNull()
            ?: return
        if (server.isImageHosting) return
        val normalizedPath = normalizePath(path)

        if (webDavDirectoryCache.get(server.id, normalizedPath) != null) return

        preloadJobs[normalizedPath]?.cancel()
        preloadJobs[normalizedPath] = viewModelScope.launch {
            delay(300)
            try {
                val apiPath = server.toApiPath(normalizedPath)
                openListApi.listDirectory(server, apiPath, page = 1, perPage = 200)
                    .onSuccess { data ->
                        val items = data.content.orEmpty().map { it.toWebDavMediaItem(server, apiPath) }
                        webDavDirectoryCache.put(
                            server.id,
                            normalizedPath,
                            items,
                        )
                        cloudDirectoryItemCache.put(server.id, normalizedPath, items)
                    }
            } finally {
                preloadJobs.remove(normalizedPath)
            }
        }
    }

    private suspend fun mapCloudFolder(
        server: WebDavServer,
        path: String,
        preferences: ApplicationPreferences,
        items: List<com.sakurafubuki.yume.core.model.WebDavMediaItem>,
    ): Folder {
        val sort = imageSort(preferences)
        val viewMode = effectiveImageViewMode(preferences.imageViewMode)
        val folders = items.filter { it.isDirectory }.map { item ->
            val folderPath = normalizePath(resolveRelativePath(server, item.href))
            val displayName = Uri.decode(item.name).ifBlank { folderPath.substringAfterLast('/') }
            val (coverMedia, mediaCount) = if (server.isImageHosting) {
                null to 0
            } else {
                buildCloudFolderPreview(
                    server = server,
                    folderPath = folderPath,
                    sort = sort,
                )
            }
            Folder(
                name = displayName,
                path = folderPath,
                dateModified = item.lastModified?.time ?: 0L,
                coverMedia = coverMedia,
                mediaCount = mediaCount,
            )
        }.orderedCloudFolders(server, sort)

        var zeroDimCount = 0
        var cachedDimCount = 0
        var apiDimCount = 0
        val images = items.filter { it.isImage }.map { item ->
            mapCloudImageItemToVideo(
                server = server,
                path = path,
                item = item,
            ).also { video ->
                when {
                    video.width > 0 && video.height > 0 -> {
                        val apiW = item.width ?: 0
                        if (apiW > 0) apiDimCount++ else cachedDimCount++
                    }
                    else -> zeroDimCount++
                }
            }
        }.orderedCloudVideos(server, sort)

        val (displayFolders, displayImages) = when (viewMode) {
            MediaViewMode.IMAGE,
            MediaViewMode.VIDEOS,
            -> emptyList<Folder>() to images
            else -> deduplicateFolderNames(folders) to images
        }

        return Folder(
            name = cloudFolderDisplayName(server, path),
            path = normalizePath(path),
            dateModified = System.currentTimeMillis(),
            mediaList = displayImages,
            folderList = displayFolders,
        )
    }

    private fun mapLocalFolder(
        localImages: List<LocalImage>,
        currentPath: String,
        preferences: ApplicationPreferences,
    ): Folder {
        val normalizedCurrentPath = normalizePath(currentPath)
        val sort = imageSort(preferences)
        val viewMode = effectiveImageViewMode(preferences.imageViewMode)
        val videos = localImages.map(::mapLocalImageToVideo)

        val videosByParent = videos.groupBy { normalizePath(it.parentPath) }

        val folderChildren = when (viewMode) {
            MediaViewMode.FOLDERS ->
                videosByParent
                    .filter { it.key != ROOT_PATH }
                    .map { (folderPath, imagesInFolderSorted) ->
                        val imagesInFolder = imagesInFolderSorted.sortedWith(sort.videoComparator())
                        Folder(
                            name = folderPath.substringAfterLast('/'),
                            path = folderPath,
                            dateModified = imagesInFolder.maxOfOrNull { it.dateModified } ?: 0L,
                            parentPath = folderPath.substringBeforeLast('/', ROOT_PATH).ifBlank { ROOT_PATH },
                            coverMedia = imagesInFolder.firstOrNull(),
                            mediaCount = imagesInFolder.size,
                        )
                    }
                    .sortedWith(sort.folderComparator())
            MediaViewMode.FOLDER_TREE ->
                videosByParent.keys
                    .mapNotNull { parentPath ->
                        immediateChildPath(currentPath = normalizedCurrentPath, candidatePath = parentPath)
                    }
                    .distinct()
                    .map { folderPath ->
                        val imagesInFolder = videosByParent.entries
                            .filter { (parentPath, _) ->
                                parentPath == folderPath || parentPath.startsWith("$folderPath/")
                            }
                            .flatMap { it.value }
                            .sortedWith(sort.videoComparator())
                        Folder(
                            name = folderPath.substringAfterLast('/'),
                            path = folderPath,
                            dateModified = imagesInFolder.maxOfOrNull { it.dateModified } ?: 0L,
                            coverMedia = imagesInFolder.firstOrNull(),
                            mediaCount = imagesInFolder.size,
                            folderList = buildLocalChildFolderPreviews(
                                videosByParent = videosByParent,
                                currentPath = folderPath,
                                sort = sort,
                            ),
                        )
                    }
                    .sortedWith(sort.folderComparator())
            MediaViewMode.IMAGE,
            MediaViewMode.VIDEOS,
            -> emptyList()
        }

        val imagesInCurrent = (videosByParent[normalizedCurrentPath] ?: emptyList())
            .sortedWith(sort.videoComparator())

        val (displayFolders, displayImages) = when (viewMode) {
            MediaViewMode.FOLDERS -> if (normalizedCurrentPath == ROOT_PATH) folderChildren to imagesInCurrent else emptyList<Folder>() to imagesInCurrent
            MediaViewMode.IMAGE,
            MediaViewMode.VIDEOS,
            -> emptyList<Folder>() to videos.sortedWith(sort.videoComparator())
            MediaViewMode.FOLDER_TREE -> folderChildren to imagesInCurrent
        }

        return Folder(
            name = if (normalizedCurrentPath == ROOT_PATH) "Images" else normalizedCurrentPath.substringAfterLast('/'),
            path = normalizedCurrentPath,
            dateModified = System.currentTimeMillis(),
            folderList = displayFolders,
            mediaList = displayImages,
        )
    }

    private suspend fun buildCloudFolderPreview(
        server: WebDavServer,
        folderPath: String,
        sort: Sort,
    ): Pair<Video?, Int> {
        val normalizedPath = normalizePath(folderPath)
        val cachedItems = webDavDirectoryCache.get(server.id, normalizedPath)
        if (cachedItems != null) {
            val media = cachedItems
                .filter { it.isImage }
                .map { item ->
                    mapCloudImageItemToVideo(
                        server = server,
                        path = folderPath,
                        item = item,
                    )
                }
                .orderedCloudVideos(server, sort)
            return media.firstOrNull() to media.size
        }
        val diskItems = getCachedCloudDirectoryItems(server.id, normalizedPath)
        if (diskItems != null) {
            val media = diskItems
                .filter { it.isImage }
                .map { item ->
                    mapCloudImageItemToVideo(
                        server = server,
                        path = folderPath,
                        item = item,
                    )
                }
                .orderedCloudVideos(server, sort)
            return media.firstOrNull() to media.size
        }
        return null to 0
    }

    private fun buildLocalChildFolderPreviews(
        videosByParent: Map<String, List<Video>>,
        currentPath: String,
        sort: Sort,
    ): List<Folder> {
        val normalizedCurrentPath = normalizePath(currentPath)
        return videosByParent.keys
            .mapNotNull { parentPath ->
                immediateChildPath(currentPath = normalizedCurrentPath, candidatePath = parentPath)
            }
            .distinct()
            .map { folderPath ->
                val imagesInFolder = videosByParent.entries
                    .filter { (parentPath, _) ->
                        parentPath == folderPath || parentPath.startsWith("$folderPath/")
                    }
                    .flatMap { it.value }
                    .sortedWith(sort.videoComparator())
                Folder(
                    name = folderPath.substringAfterLast('/'),
                    path = folderPath,
                    dateModified = imagesInFolder.maxOfOrNull { it.dateModified } ?: 0L,
                    coverMedia = imagesInFolder.firstOrNull(),
                    mediaCount = imagesInFolder.size,
                )
            }
            .sortedWith(sort.folderComparator())
    }

    private suspend fun getCachedCloudDirectoryItems(
        serverId: Int,
        path: String,
    ): List<WebDavMediaItem>? {
        val normalizedPath = normalizePath(path)
        webDavDirectoryCache.get(serverId, normalizedPath)?.let { return it }
        val diskItems = cloudDirectoryItemCache.get(serverId, normalizedPath)
        if (diskItems.isEmpty()) return null
        webDavDirectoryCache.put(serverId, normalizedPath, diskItems)
        return diskItems
    }

    private suspend fun buildIndexedMultiCloudRoot(
        selectedServers: List<WebDavServer>,
        preferences: ApplicationPreferences,
        refreshing: Boolean,
    ): Folder? = coroutineScope {
        val viewMode = effectiveImageViewMode(preferences.imageViewMode)
        if (viewMode == MediaViewMode.FOLDER_TREE) return@coroutineScope null
        val sort = imageSort(preferences)
        val indexedRoots = selectedServers
            .map { server ->
                async(Dispatchers.IO) {
                    server to buildIndexedCloudFolder(
                        server = server,
                        path = normalizePath(server.basePath),
                        preferences = preferences,
                        refreshing = refreshing,
                    )
                }
            }
            .awaitAll()
            .filter { it.second != null }
        if (indexedRoots.isEmpty()) return@coroutineScope null

        when (viewMode) {
            MediaViewMode.FOLDERS -> {
                val folders = indexedRoots
                    .flatMap { (server, folder) ->
                        folder!!.folderList.map { child ->
                            child.copy(path = encodeCloudFolderPath(server.id, child.path))
                        }
                    }
                    .sortedWith(sort.folderComparator())
                if (folders.isEmpty()) return@coroutineScope null
                Folder.rootFolder.copy(
                    name = "Cloud",
                    folderList = folders,
                    mediaList = emptyList(),
                    folderCount = folders.size,
                    mediaCount = 0,
                    cachedMediaSize = folders.sumOf { it.mediaSize },
                )
            }
            MediaViewMode.IMAGE,
            MediaViewMode.VIDEOS,
            -> {
                val images = indexedRoots
                    .flatMap { (_, folder) -> folder!!.mediaList }
                    .sortedWith(sort.videoComparator())
                if (images.isEmpty()) return@coroutineScope null
                Folder.rootFolder.copy(
                    name = "Cloud",
                    folderList = emptyList(),
                    mediaList = images,
                    folderCount = 0,
                    mediaCount = images.size,
                    cachedMediaSize = images.sumOf { it.size },
                )
            }
            MediaViewMode.FOLDER_TREE -> null
        }
    }

    private suspend fun buildIndexedCloudFolder(
        server: WebDavServer,
        path: String,
        preferences: ApplicationPreferences,
        refreshing: Boolean,
    ): Folder? {
        if (server.isImageHosting) return null
        val viewMode = effectiveImageViewMode(preferences.imageViewMode)
        if (viewMode == MediaViewMode.FOLDER_TREE) return null
        val indexedImages = searchIndexedCloudImages(server = server, path = path, refreshing = refreshing) ?: return null
        if (indexedImages.isEmpty()) return null
        val sort = imageSort(preferences)
        val indexedByParent = indexedImages.groupBy { normalizePath(it.parentPath) }
        if (viewMode == MediaViewMode.FOLDERS) {
            return buildIndexedCloudImageFoldersOnly(
                server = server,
                path = path,
                indexedByParent = indexedByParent,
                sort = sort,
                refreshing = refreshing,
            )
        }

        val parentListSemaphore = Semaphore(CLOUD_INDEX_PARENT_LIST_MAX_CONCURRENCY)
        val itemsByParent = coroutineScope {
            indexedByParent.map { (parentPath, images) ->
                async(Dispatchers.IO) {
                    parentListSemaphore.withPermit {
                        parentPath to loadIndexedParentImageItems(
                            server = server,
                            parentPath = parentPath,
                            indexedImages = images,
                            refreshing = refreshing,
                        )
                    }
                }
            }.awaitAll().toMap()
        }
        val images = itemsByParent
            .flatMap { (parentPath, items) ->
                items.map { item -> mapCloudImageItemToVideo(server = server, path = parentPath, item = item) }
            }
            .sortedWith(sort.videoComparator())
        if (images.isEmpty()) return null
        return Folder(
            name = cloudFolderDisplayName(server, path),
            path = normalizePath(path),
            dateModified = images.maxOfOrNull { it.dateModified } ?: 0L,
            folderList = emptyList(),
            mediaList = images,
            mediaCount = images.size,
            folderCount = 0,
            cachedMediaSize = images.sumOf { it.size },
        )
    }

    private suspend fun buildIndexedCloudImageFoldersOnly(
        server: WebDavServer,
        path: String,
        indexedByParent: Map<String, List<CloudIndexedImage>>,
        sort: Sort,
        refreshing: Boolean,
    ): Folder? {
        val normalizedPath = normalizePath(path)
        val directIndexedImages = indexedByParent[normalizedPath].orEmpty()
        val directImages = if (directIndexedImages.isEmpty()) {
            emptyList()
        } else {
            loadIndexedParentImageItems(
                server = server,
                parentPath = normalizedPath,
                indexedImages = directIndexedImages,
                refreshing = refreshing,
            ).map { item ->
                mapCloudImageItemToVideo(server = server, path = normalizedPath, item = item)
            }.sortedWith(sort.videoComparator())
        }

        val folderPreviews = indexedByParent
            .filterKeys { normalizePath(it) != normalizedPath }
            .mapNotNull { (parentPath, images) ->
                val coverMedia = images
                    .map { indexedImage ->
                        mapCloudImageItemToVideo(
                            server = server,
                            path = parentPath,
                            item = indexedImage.item.toSyntheticImageWebDavMediaItem(
                                server = server,
                                parentPath = parentPath,
                            ),
                        )
                    }
                    .orderedCloudVideos(server, sort)
                    .firstOrNull()
                    ?: return@mapNotNull null
                Folder(
                    name = cloudFolderDisplayName(server, parentPath),
                    path = parentPath,
                    dateModified = 0L,
                    parentPath = parentPath.substringBeforeLast('/', ROOT_PATH).ifBlank { ROOT_PATH },
                    mediaList = emptyList(),
                    folderList = emptyList(),
                    coverMedia = coverMedia,
                    mediaCount = images.size,
                    folderCount = 0,
                    cachedMediaSize = images.sumOf { it.item.size },
                )
            }

        val folders = folderPreviews
            .filter { it.mediaCount > 0 || it.coverMedia != null }
            .distinctBy { it.path }
            .sortedWith(sort.folderComparator())
        if (folders.isEmpty() && directImages.isEmpty()) return null
        return Folder(
            name = cloudFolderDisplayName(server, normalizedPath),
            path = normalizedPath,
            dateModified = directImages.maxOfOrNull { it.dateModified } ?: 0L,
            folderList = folders,
            mediaList = directImages,
            mediaCount = directImages.size,
            folderCount = folders.size,
            cachedMediaSize = folders.sumOf { it.mediaSize } + directImages.sumOf { it.size },
        )
    }

    private suspend fun searchIndexedCloudImages(
        server: WebDavServer,
        path: String,
        refreshing: Boolean,
    ): List<CloudIndexedImage>? {
        val cacheKey = "${server.id}:${normalizePath(path)}"
        if (!refreshing) {
            indexedCloudImageCache[cacheKey]?.let { return it }
        } else {
            indexedCloudImageCache.remove(cacheKey)
        }
        val indexed = searchIndexedCloudImages(
            server = server,
            path = path,
            keywords = "",
        ) ?: return null
        indexedCloudImageCache[cacheKey] = indexed
        return indexed
    }

    private suspend fun searchIndexedCloudImages(
        server: WebDavServer,
        path: String,
        keywords: String,
    ): List<CloudIndexedImage>? {
        val apiParentPath = normalizePath(server.toApiPath(path))
        val files = mutableListOf<CloudIndexedImage>()
        var page = 1
        var total: Int? = null
        var seenRawItems = 0
        while ((total == null || seenRawItems < total) && seenRawItems < CLOUD_INDEX_SEARCH_MAX_RESULTS) {
            val data = openListApi.search(
                server = server,
                parent = apiParentPath,
                keywords = keywords,
                scope = 2,
                page = page,
                perPage = CLOUD_INDEX_SEARCH_PAGE_SIZE,
            ).getOrElse { throwable ->
                Logger.d(TAG, "image search unavailable server=${server.id} path=$path keywords=$keywords: ${throwable.message}")
                return null
            }
            total = data.total
            if (data.total > CLOUD_INDEX_SEARCH_MAX_RESULTS) {
                Logger.w(
                    TAG,
                    "image search result capped server=${server.id} path=$path keywords=$keywords total=${data.total} cap=$CLOUD_INDEX_SEARCH_MAX_RESULTS",
                )
            }
            val content = data.content.orEmpty()
            if (content.isEmpty()) break
            seenRawItems += content.size
            val normalizedRoot = apiParentPath
            files += content
                .asSequence()
                .filter { it.isIndexedCloudImageFile() }
                .map { item ->
                    CloudIndexedImage(
                        item = item,
                        parentPath = normalizePath(item.parent),
                    )
                }
                .filter { indexed ->
                    val normalizedParent = normalizePath(indexed.parentPath)
                    normalizedRoot == ROOT_PATH ||
                        normalizedParent == normalizedRoot ||
                        normalizedParent.startsWith("$normalizedRoot/")
                }
            page += 1
        }
        return files
    }

    private suspend fun loadIndexedParentImageItems(
        server: WebDavServer,
        parentPath: String,
        indexedImages: List<CloudIndexedImage>,
        refreshing: Boolean,
    ): List<WebDavMediaItem> {
        val indexedNames = indexedImages.map { Uri.decode(it.item.name) }.toSet()
        val directoryItems = if (!refreshing) {
            webDavDirectoryCache.get(server.id, parentPath)
        } else {
            null
        } ?: runCatching {
            val apiPath = server.toApiPath(parentPath)
            openListApi.listDirectory(
                server = server,
                path = apiPath,
                page = 1,
                perPage = CLOUD_INDEX_PARENT_LIST_PAGE_SIZE,
                refresh = refreshing,
            ).getOrThrow().content.orEmpty()
                .map { it.toWebDavMediaItem(server, apiPath) }
                .also {
                    webDavDirectoryCache.put(server.id, parentPath, it)
                    cloudDirectoryItemCache.put(server.id, parentPath, it)
                }
        }.getOrNull()

        val listedImages = directoryItems
            ?.filter { it.isImage && it.name in indexedNames }
            .orEmpty()
        val listedNames = listedImages.map { it.name }.toSet()
        if (listedNames.size == indexedNames.size) return listedImages

        val missingSyntheticImages = indexedImages
            .filterNot { Uri.decode(it.item.name) in listedNames }
            .map { indexed ->
                indexed.item.toSyntheticImageWebDavMediaItem(
                    server = server,
                    parentPath = parentPath,
                )
            }
        return listedImages + missingSyntheticImages
    }

    private suspend fun mapCloudImageItemToVideo(
        server: WebDavServer,
        path: String,
        item: com.sakurafubuki.yume.core.model.WebDavMediaItem,
    ): Video {
        val rawUrl = item.rawVideoUrl
        val imageUrl = if (
            rawUrl != null &&
            (server.isImageHosting || rawUrl.toUri().getQueryParameter("sign") != null)
        ) {
            rawUrl.stripUserInfoFromHttpUrl()
        } else {
            webDavRepository.getStreamUrl(item, server).stripUserInfoFromHttpUrl()
        }
        val thumbnailUrl = item.apiThumbnailUrl
            ?.stripUserInfoFromHttpUrl()
            ?.takeIf { it.isNotBlank() && it != imageUrl }
        val dimensionUrl = thumbnailUrl ?: imageUrl
        val decodedUriPath = Uri.decode(imageUrl.toUri().path.orEmpty())
        val decodedPath = if (server.isImageHosting) {
            decodedUriPath.removePrefix("/file").ifBlank { decodedUriPath }
        } else {
            decodedUriPath
        }
        val displayName = Uri.decode(item.name).ifBlank { decodedPath.substringAfterLast('/') }
        val cached = resolveDimensionsFromCache(server.id, imageUrl)
            ?: resolveDimensionsFromCache(server.id, dimensionUrl)
        val resolvedWidth = item.width?.takeIf { it > 0 } ?: cached?.first ?: 0
        val resolvedHeight = item.height?.takeIf { it > 0 } ?: cached?.second ?: 0
        val apiWidth = item.width ?: 0
        val apiHeight = item.height ?: 0
        if (apiWidth > 0 && apiHeight > 0) {
            imageDimensionCache.put(server.id, imageUrl, apiWidth, apiHeight)
            imageDimensionCache.put(server.id, stableUrlForDimensionCache(imageUrl), apiWidth, apiHeight)
            if (dimensionUrl != imageUrl) {
                imageDimensionCache.put(server.id, dimensionUrl, apiWidth, apiHeight)
                imageDimensionCache.put(server.id, stableUrlForDimensionCache(dimensionUrl), apiWidth, apiHeight)
            }
        }
        return Video(
            id = imageUrl.hashCode().toLong(),
            path = decodedPath.ifBlank { path },
            parentPath = decodedPath.substringBeforeLast('/', ""),
            duration = 1L,
            uriString = imageUrl,
            nameWithExtension = displayName,
            width = resolvedWidth,
            height = resolvedHeight,
            size = item.size,
            playbackPosition = 0L,
            dateModified = item.lastModified?.time ?: 0L,
            formattedDuration = "",
            formattedFileSize = Utils.formatFileSize(item.size),
            thumbnailUriString = thumbnailUrl,
        )
    }

    private fun deduplicateFolderNames(folders: List<Folder>): List<Folder> {
        val nameCounts = mutableMapOf<String, Int>()
        for (folder in folders) {
            nameCounts[folder.name] = (nameCounts[folder.name] ?: 0) + 1
        }
        return folders.map { folder ->
            val count = nameCounts[folder.name] ?: 1
            if (count > 1) {
                val parentName = folder.path.substringBeforeLast('/', "")
                    .substringAfterLast('/')
                    .ifBlank { null }
                if (parentName != null) {
                    folder.copy(name = "$parentName/${folder.name}")
                } else {
                    folder
                }
            } else {
                folder
            }
        }
    }

    private suspend fun saveCurrentImageFolderMetadataToDb(server: WebDavServer, folder: Folder) {
        val folderPath = normalizePath(folder.path)
        val existing = cloudVideoMetadataRepository.getFolderMetadata(server.id, listOf(folderPath))[folderPath]
        val coverUriToSave = folder.coverMedia?.uriString
            ?.takeIf(::isRemoteImageData)
            ?: folder.mediaList.firstOrNull { isRemoteImageData(it.uriString) }?.uriString
            ?: existing?.coverImageUri

        if (coverUriToSave != null &&
            isRemoteImageData(coverUriToSave) &&
            (coverUriToSave != existing?.coverImageUri || findLocalFolderCoverFile(coverUriToSave) == null)
        ) {
            persistFolderCoverLocally(coverUriToSave)
        }

        val imageCount = when {
            folder.mediaCount > 0 -> folder.mediaCount
            folder.mediaList.isNotEmpty() -> folder.mediaList.size
            else -> existing?.imageCount ?: 0
        }
        val folderCount = when {
            folder.folderCount > 0 -> folder.folderCount
            folder.folderList.isNotEmpty() -> folder.folderList.size
            else -> existing?.folderCount ?: 0
        }
        val cachedMediaSize = folder.cachedMediaSize ?: 0L
        val totalSize = when {
            cachedMediaSize > 0 -> cachedMediaSize
            folder.mediaList.isNotEmpty() -> folder.mediaList.sumOf { it.size }
            else -> existing?.totalSize ?: 0L
        }
        val mediaCount = maxOf(existing?.mediaCount ?: 0, imageCount)
        val totalDurationMs = existing?.totalDurationMs ?: 0L
        val videoCount = existing?.videoCount ?: 0
        val metadataChanged = existing == null ||
            existing.totalDurationMs != totalDurationMs ||
            existing.totalSize != totalSize ||
            existing.mediaCount != mediaCount ||
            existing.folderCount != folderCount ||
            existing.coverImageUri != coverUriToSave ||
            existing.videoCount != videoCount ||
            existing.imageCount != imageCount

        if (metadataChanged) {
            cloudVideoMetadataRepository.saveFolderMetadata(
                serverId = server.id,
                folderPath = folderPath,
                totalDurationMs = totalDurationMs,
                totalSize = totalSize,
                mediaCount = mediaCount,
                folderCount = folderCount,
                coverImageUri = coverUriToSave,
                videoCount = videoCount,
                imageCount = imageCount,
            )
        }
    }

    private suspend fun saveImageFolderMetadataToDb(server: WebDavServer, folder: Folder) {
        val existingMetadata = cloudVideoMetadataRepository.getFolderMetadata(
            server.id,
            folder.folderList.map { it.path },
        )
        folder.folderList.forEach { child ->
            val newCoverUri = child.coverMedia?.uriString
            val existingDbUri = existingMetadata[child.path]?.coverImageUri
            val coverUriToSave = if (newCoverUri != null && isRemoteImageData(newCoverUri)) {
                newCoverUri
            } else {
                existingDbUri
            }
            if (coverUriToSave != null &&
                isRemoteImageData(coverUriToSave) &&
                (coverUriToSave != existingDbUri || findLocalFolderCoverFile(coverUriToSave) == null)
            ) {
                persistFolderCoverLocally(coverUriToSave)
            }
            val existing = existingMetadata[child.path]
            val resolvedImageCount = when {
                child.mediaCount > 0 -> child.mediaCount
                child.mediaList.isNotEmpty() -> child.mediaList.size
                else -> existing?.imageCount ?: 0
            }
            val resolvedTotalDurationMs = existing?.totalDurationMs ?: 0L
            val resolvedTotalSize = existing?.totalSize ?: 0L
            val resolvedMediaCount = existing?.mediaCount ?: 0
            val resolvedFolderCount = existing?.folderCount ?: 0
            val resolvedVideoCount = existing?.videoCount ?: 0
            val metadataChanged = existing == null ||
                existing.totalDurationMs != resolvedTotalDurationMs ||
                existing.totalSize != resolvedTotalSize ||
                existing.mediaCount != resolvedMediaCount ||
                existing.folderCount != resolvedFolderCount ||
                existing.coverImageUri != coverUriToSave ||
                existing.videoCount != resolvedVideoCount ||
                existing.imageCount != resolvedImageCount
            if (metadataChanged) {
                cloudVideoMetadataRepository.saveFolderMetadata(
                    serverId = server.id,
                    folderPath = child.path,
                    totalDurationMs = resolvedTotalDurationMs,
                    totalSize = resolvedTotalSize,
                    mediaCount = resolvedMediaCount,
                    folderCount = resolvedFolderCount,
                    coverImageUri = coverUriToSave,
                    videoCount = resolvedVideoCount,
                    imageCount = resolvedImageCount,
                )
            }
        }
    }

    private suspend fun persistFolderCoverLocally(coverImageUri: String): String? = withContext(Dispatchers.IO) {
        try {
            val coverCacheKey = folderCoverCacheKey(coverImageUri)
            val existingFile = findLocalFolderCoverFile(coverImageUri)
            if (existingFile != null) return@withContext Uri.fromFile(existingFile).toString()
            persistRemoteFolderCoverBytes(coverImageUri)?.let { savedFile ->
                readImageBounds(savedFile)?.let { (width, height) ->
                    reportCloudImageDimensions(coverImageUri, width, height)
                }
                return@withContext Uri.fromFile(savedFile).toString()
            }
            val imageLoader = SingletonImageLoader.get(context)
            val uri = coverImageUri.toUri()
            val request = ImageRequest.Builder(context)
                .data(uri)
                .size(Size.ORIGINAL)
                .precision(Precision.EXACT)
                .allowHardware(false)
                .diskCachePolicy(CachePolicy.DISABLED)
                .memoryCacheKey(coverCacheKey)
                .build()
            val result = imageLoader.execute(request) as? SuccessResult ?: return@withContext null
            val bitmap = result.image.toBitmap()
            val outputFile = localFolderCoverFile(coverImageUri, FOLDER_COVER_FALLBACK_EXTENSION)
            outputFile.outputStream().use { output ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.WEBP_LOSSLESS, 100, output)
            }
            reportCloudImageDimensions(coverImageUri, bitmap.width, bitmap.height)
            Uri.fromFile(outputFile).toString()
        } catch (e: Exception) {
            Logger.e(TAG, "persistFolderCoverLocally: failed for $coverImageUri", e)
            null
        }
    }

    private fun folderCoverCacheDir(): File = File(context.cacheDir, FOLDER_COVERS_DIR).apply { mkdirs() }

    private fun persistRemoteFolderCoverBytes(coverImageUri: String): File? {
        val scheme = coverImageUri.toUri().scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null

        var connection: HttpURLConnection? = null
        return runCatching {
            connection = (URL(coverImageUri).openConnection() as HttpURLConnection).apply {
                connectTimeout = FOLDER_COVER_DOWNLOAD_TIMEOUT_MS
                readTimeout = FOLDER_COVER_DOWNLOAD_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("Accept", "image/avif,image/webp,image/*,*/*")
            }
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) return@runCatching null
            val contentType = connection.contentType.orEmpty()
            val extension = folderCoverExtension(contentType, coverImageUri)
            val outputFile = localFolderCoverFile(coverImageUri, extension)
            val tempFile = File(outputFile.parentFile, "${outputFile.name}.tmp")
            connection.inputStream.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            if (tempFile.length() <= 0L) {
                tempFile.delete()
                return@runCatching null
            }
            if (outputFile.exists()) outputFile.delete()
            if (!tempFile.renameTo(outputFile)) {
                tempFile.copyTo(outputFile, overwrite = true)
                tempFile.delete()
            }
            outputFile
        }.getOrElse { error ->
            Logger.w(TAG, "persistRemoteFolderCoverBytes: failed for $coverImageUri", error)
            null
        }.also {
            connection?.disconnect()
        }
    }

    private fun localFolderCoverFile(coverImageUri: String, extension: String): File = File(
        folderCoverCacheDir(),
        "${sha256(folderCoverCacheKey(coverImageUri))}.$extension",
    )

    private fun findLocalFolderCoverFile(coverImageUri: String): File? {
        val baseName = sha256(folderCoverCacheKey(coverImageUri))
        return FOLDER_COVER_EXTENSIONS
            .asSequence()
            .map { extension -> File(folderCoverCacheDir(), "$baseName.$extension") }
            .firstOrNull { it.exists() && it.length() > 0L }
    }

    private fun folderCoverExtension(contentType: String, coverImageUri: String): String {
        val lowerContentType = contentType.substringBefore(';').trim().lowercase()
        CONTENT_TYPE_TO_FOLDER_COVER_EXTENSION[lowerContentType]?.let { return it }
        val extension = coverImageUri
            .substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('.', "")
            .lowercase()
        return extension.takeIf { it in FOLDER_COVER_EXTENSIONS } ?: FOLDER_COVER_FALLBACK_EXTENSION
    }

    private fun readImageBounds(file: File): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        val width = options.outWidth
        val height = options.outHeight
        return if (width > 0 && height > 0) width to height else null
    }

    private fun folderCoverCacheKey(coverImageUri: String): String = "${stableCacheKey(coverImageUri)}|folder-cover-source-v3"

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private suspend fun enrichFolderWithDbMetadata(serverId: Int, folder: Folder): Folder {
        if (folder.folderList.isEmpty()) return folder
        val paths = folder.folderList.map { it.path }
        val metadataMap = cloudVideoMetadataRepository.getFolderMetadata(serverId, paths)
        if (metadataMap.isEmpty()) return folder
        val enriched = folder.folderList.map { child ->
            val meta = metadataMap[child.path] ?: return@map child
            var result = child
            if (result.mediaCount == 0 && meta.imageCount > 0) {
                result = result.copy(mediaCount = meta.imageCount)
            }
            if (result.folderCount == 0 && meta.folderCount > 0) {
                result = result.copy(folderCount = meta.folderCount)
            }
            if (meta.coverImageUri != null) {
                val dbCoverUri = meta.coverImageUri!!
                val localFile = findLocalFolderCoverFile(dbCoverUri)
                val localCoverUri = localFile?.let { Uri.fromFile(it).toString() }
                val cachedDims = resolveDimensionsFromCache(serverId, dbCoverUri)
                    ?: localCoverUri?.let { resolveDimensionsFromCache(serverId, it) }
                    ?: localFile?.let(::readImageBounds)

                result = result.copy(
                    coverMedia = Video(
                        id = dbCoverUri.hashCode().toLong(),
                        path = child.path,
                        duration = 1L,
                        uriString = dbCoverUri,
                        nameWithExtension = "",
                        width = cachedDims?.first ?: 0,
                        height = cachedDims?.second ?: 0,
                        size = 0,
                        dateModified = 0L,
                        thumbnailUriString = localCoverUri,
                    ),
                )
            }
            result
        }
        return folder.copy(folderList = enriched)
    }

    private fun effectiveImageViewMode(mediaViewMode: MediaViewMode): MediaViewMode = when (mediaViewMode) {
        MediaViewMode.VIDEOS -> MediaViewMode.IMAGE
        else -> mediaViewMode
    }

    private suspend fun resolveCloudLeafFolders(
        server: WebDavServer,
        folder: Folder,
        preferences: ApplicationPreferences,
    ): Folder {
        val viewMode = effectiveImageViewMode(preferences.imageViewMode)
        if (viewMode == MediaViewMode.IMAGE) return folder
        if (folder.folderList.isEmpty()) return folder

        val sort = imageSort(preferences)
        if (viewMode == MediaViewMode.FOLDER_TREE) {
            return enrichCloudTreeFolders(server, folder, sort)
        }

        val resolvedFolders = folder.folderList.flatMap { child ->
            resolveLeafFolderItem(server, child, sort, MAX_CLOUD_LEAF_DEPTH)
        }
        return folder.copy(
            folderList = deduplicateFolderNames(resolvedFolders.orderedCloudFolders(server, sort)),
        )
    }

    private suspend fun enrichCloudTreeFolders(
        server: WebDavServer,
        folder: Folder,
        sort: Sort,
    ): Folder {
        val enrichedFolders = folder.folderList.mapNotNull { child ->
            enrichCloudTreeFolderItem(server, child, sort)
                .takeIf(::hasDisplayableCloudImageFolder)
        }
        return folder.copy(folderList = deduplicateFolderNames(enrichedFolders.orderedCloudFolders(server, sort)))
    }

    private suspend fun enrichCloudTreeFolderItem(
        server: WebDavServer,
        child: Folder,
        sort: Sort,
    ): Folder {
        val path = normalizePath(child.path)
        val items = loadCloudPreviewItems(server, path) ?: return child
        val images = items.filter { it.isImage }
            .map { item -> mapCloudImageItemToVideo(server, path, item) }
            .orderedCloudVideos(server, sort)
        val childFolders = mutableListOf<Folder>()
        for (item in items.filter { it.isDirectory }) {
            val folderPath = normalizePath(resolveRelativePath(server, item.href))
            val displayName = Uri.decode(item.name).ifBlank { folderPath.substringAfterLast('/') }
            val (coverMedia, mediaCount) = resolveCloudFolderPreview(
                server = server,
                folderPath = folderPath,
                sort = sort,
            )
            val previewFolder = Folder(
                name = displayName,
                path = folderPath,
                dateModified = item.lastModified?.time ?: 0L,
                coverMedia = coverMedia,
                mediaCount = mediaCount,
            )
            if (hasDisplayableCloudImageFolder(previewFolder)) {
                childFolders += previewFolder
            }
        }
        val orderedChildFolders = childFolders.orderedCloudFolders(server, sort)

        val coverMedia = images.firstOrNull()
            ?: orderedChildFolders.firstNotNullOfOrNull { it.coverMedia }
            ?: child.coverMedia.takeIf { child.mediaCount > 0 }
        val mediaCount = when {
            images.isNotEmpty() -> images.size
            orderedChildFolders.any { it.mediaCount > 0 } -> orderedChildFolders.sumOf { it.mediaCount }
            else -> 0
        }

        return child.copy(
            coverMedia = coverMedia,
            mediaCount = mediaCount,
            dateModified = images.firstOrNull()?.dateModified
                ?: orderedChildFolders.maxOfOrNull { it.dateModified }
                ?: child.dateModified,
            folderList = orderedChildFolders,
            folderCount = orderedChildFolders.size,
        )
    }

    private fun hasDisplayableCloudImageFolder(folder: Folder): Boolean = folder.mediaList.isNotEmpty() ||
        folder.mediaCount > 0 ||
        folder.folderList.isNotEmpty() ||
        folder.coverMedia != null

    private suspend fun loadCloudPreviewItems(
        server: WebDavServer,
        path: String,
    ): List<com.sakurafubuki.yume.core.model.WebDavMediaItem>? {
        val normalizedPath = normalizePath(path)
        getCachedCloudDirectoryItems(server.id, normalizedPath)?.let { return it }
        val apiPath = server.toApiPath(normalizedPath)
        val data = cloudPreviewSemaphore.withPermit {
            openListApi.listDirectory(server, apiPath, page = 1, perPage = 200).getOrNull()
        } ?: return null
        return data.content.orEmpty().map { it.toWebDavMediaItem(server, apiPath) }
            .also {
                webDavDirectoryCache.put(server.id, normalizedPath, it)
                cloudDirectoryItemCache.put(server.id, normalizedPath, it)
            }
    }

    private suspend fun resolveCloudFolderPreview(
        server: WebDavServer,
        folderPath: String,
        sort: Sort,
        remainingDepth: Int = MAX_CLOUD_LEAF_DEPTH,
    ): Pair<Video?, Int> {
        if (remainingDepth <= 0) return buildCloudFolderPreview(server, folderPath, sort)
        val cachedPreview = buildCloudFolderPreview(server, folderPath, sort)
        if (cachedPreview.first != null || cachedPreview.second > 0) return cachedPreview

        val items = loadCloudPreviewItems(server, folderPath) ?: return cachedPreview
        val images = items.filter { it.isImage }
            .map { item -> mapCloudImageItemToVideo(server, folderPath, item) }
            .orderedCloudVideos(server, sort)
        if (images.isNotEmpty()) return images.first() to images.size

        val childPreviews = items.filter { it.isDirectory }
            .map { item ->
                val childPath = normalizePath(resolveRelativePath(server, item.href))
                resolveCloudFolderPreview(server, childPath, sort, remainingDepth - 1)
            }
            .filter { it.first != null || it.second > 0 }
        return childPreviews.firstNotNullOfOrNull { it.first } to childPreviews.sumOf { it.second }
    }

    private suspend fun resolveLeafFolderItem(
        server: WebDavServer,
        child: Folder,
        sort: Sort,
        remainingDepth: Int,
    ): List<Folder> {
        if (remainingDepth <= 0) return listOf(child)
        val path = normalizePath(child.path)

        var cachedItems = getCachedCloudDirectoryItems(server.id, path)

        if (cachedItems == null) {
            val apiPath = server.toApiPath(path)
            val apiResult = openListApi.listDirectory(server, apiPath, page = 1, perPage = 200).getOrNull()
            cachedItems = apiResult?.content?.map { it.toWebDavMediaItem(server, apiPath) }
                ?.also {
                    webDavDirectoryCache.put(server.id, path, it)
                    cloudDirectoryItemCache.put(server.id, path, it)
                }
        }
        if (cachedItems == null) return listOf(child)

        val images = cachedItems.filter { it.isImage }
        val directories = cachedItems.filter { it.isDirectory }

        if (images.isNotEmpty()) {
            val media = images.map { item ->
                mapCloudImageItemToVideo(server, path, item)
            }.orderedCloudVideos(server, sort)
            return listOf(
                child.copy(
                    coverMedia = media.firstOrNull(),
                    mediaCount = media.size,
                    dateModified = media.firstOrNull()?.dateModified ?: child.dateModified,
                ),
            )
        }

        if (directories.isEmpty()) return emptyList()

        return directories.flatMap { dirItem ->
            val childPath = normalizePath(resolveRelativePath(server, dirItem.href))
            val childName = Uri.decode(dirItem.name).ifBlank { childPath.substringAfterLast('/') }
            resolveLeafFolderItem(
                server = server,
                child = Folder(
                    name = childName,
                    path = childPath,
                    dateModified = dirItem.lastModified?.time ?: 0L,
                ),
                sort = sort,
                remainingDepth = remainingDepth - 1,
            ).map { it.copy(name = "${child.name}/${it.name}") }
        }
    }

    private fun imageSort(preferences: ApplicationPreferences): Sort {
        val sortBy = if (preferences.imageSortBy == Sort.By.LENGTH) Sort.By.TITLE else preferences.imageSortBy
        return Sort(by = sortBy, order = preferences.imageSortOrder)
    }

    private fun List<Video>.orderedCloudVideos(
        server: WebDavServer,
        sort: Sort,
    ): List<Video> = sortedWith(sort.videoComparator())

    private fun List<Folder>.orderedCloudFolders(
        server: WebDavServer,
        sort: Sort,
    ): List<Folder> = sortedWith(sort.folderComparator())

    private fun mapLocalImageToVideo(image: LocalImage): Video {
        val parentPath = normalizePath(image.relativePath)
        val fullPath = normalizePath("$parentPath/${image.name}")
        return Video(
            id = image.id,
            path = fullPath,
            parentPath = parentPath,
            duration = 1L,
            uriString = image.uri,
            nameWithExtension = image.name,
            width = image.width,
            height = image.height,
            size = image.size,
            playbackPosition = 0L,
            dateModified = image.dateModified,
            formattedDuration = "",
            formattedFileSize = Utils.formatFileSize(image.size),
        )
    }

    private fun queryImages(context: Context): List<LocalImage> {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.RELATIVE_PATH,
        )
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        val cursor = context.contentResolver.query(
            collection,
            projection,
            null,
            null,
            "${MediaStore.Images.Media.DATE_MODIFIED} DESC",
        )

        if (cursor == null) {
            Logger.w(TAG, "queryImages: contentResolver.query() returned null (likely permission denied)")
            return emptyList()
        }

        return buildList {
            cursor.use {
                val idIndex = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameIndex = it.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val widthIndex = it.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                val heightIndex = it.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
                val modifiedIndex = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
                val sizeIndex = it.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val relativePathIndex = it.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)

                while (it.moveToNext()) {
                    val id = it.getLong(idIndex)
                    val relativePath = it.getString(relativePathIndex).orEmpty().trimEnd('/')
                    add(
                        LocalImage(
                            id = id,
                            name = it.getString(nameIndex).orEmpty(),
                            uri = ContentUris.withAppendedId(collection, id).toString(),
                            width = it.getInt(widthIndex),
                            height = it.getInt(heightIndex),
                            dateModified = it.getLong(modifiedIndex),
                            size = it.getLong(sizeIndex),
                            relativePath = if (relativePath.isBlank()) ROOT_PATH else "/$relativePath",
                        ),
                    )
                }
            }
        }.also { images ->
            if (images.isEmpty()) {
                Logger.w(TAG, "queryImages: MediaStore query returned 0 images")
            }
        }
    }

    private fun resolveRelativePath(server: WebDavServer, href: String): String {
        val itemPath = normalizePath(Uri.decode(href.toUri().path.orEmpty()))
        val davIndex = itemPath.indexOf("/dav")
        return if (davIndex >= 0) {
            normalizePath(itemPath.substring(davIndex + 4).ifBlank { ROOT_PATH })
        } else {
            itemPath
        }
    }

    private fun parentPathOf(path: String): String {
        val normalized = normalizePath(path)
        if (normalized == ROOT_PATH) return ROOT_PATH
        return normalized.substringBeforeLast('/', ROOT_PATH).ifBlank { ROOT_PATH }
    }

    private fun immediateChildPath(currentPath: String, candidatePath: String): String? {
        val normalizedCurrentPath = normalizePath(currentPath)
        val normalizedCandidatePath = normalizePath(candidatePath)
        if (normalizedCandidatePath == ROOT_PATH) return null
        if (normalizedCurrentPath == ROOT_PATH) {
            val firstSegment = normalizedCandidatePath.trimStart('/').substringBefore('/').ifBlank { return null }
            return normalizePath("/$firstSegment")
        }
        if (normalizedCandidatePath == normalizedCurrentPath) return null
        if (!normalizedCandidatePath.startsWith("$normalizedCurrentPath/")) return null
        val nextSegment = normalizedCandidatePath
            .removePrefix("$normalizedCurrentPath/")
            .substringBefore('/')
            .ifBlank { return null }
        return normalizePath("$normalizedCurrentPath/$nextSegment")
    }

    private fun normalizePath(path: String): String {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return ROOT_PATH
        val withLeadingSlash = if (trimmed.startsWith('/')) trimmed else "/$trimmed"
        return withLeadingSlash.removeSuffix("/").ifBlank { ROOT_PATH }
    }

    private fun toWebDavAbsolutePath(server: WebDavServer, relativePath: String): String {
        val normalizedBase = cloudStorageDisplayBasePath(server)
        val normalizedRelative = normalizePath(relativePath)
        if (normalizedRelative == ROOT_PATH) return normalizedBase
        if (normalizedRelative == normalizedBase || normalizedRelative.startsWith("$normalizedBase/")) {
            return normalizedRelative
        }
        if (normalizedBase == ROOT_PATH) return normalizedRelative
        return normalizePath("$normalizedBase/$normalizedRelative")
    }

    private fun cloudStorageDisplayBasePath(server: WebDavServer): String {
        val normalizedBasePath = normalizePath(server.basePath)
        if (normalizedBasePath != ROOT_PATH) return normalizedBasePath
        val urlPath = normalizePath(Uri.decode(server.url.toUri().path.orEmpty()))
        val davIndex = urlPath.indexOf("/dav")
        if (davIndex < 0) return normalizedBasePath
        return normalizePath(urlPath.substring(davIndex + 4).ifBlank { ROOT_PATH })
    }

    private fun cloudFolderDisplayName(server: WebDavServer, path: String): String {
        val absolutePath = toWebDavAbsolutePath(server, path)
        val folderName = Uri.decode(absolutePath.substringAfterLast('/'))
        return folderName.ifBlank { server.name }
    }

    private fun encodeCloudFolderPath(serverId: Int, path: String): String = "$CLOUD_SERVER_PATH_PREFIX$serverId:$path"

    private fun decodeCloudFolderPath(path: String): Pair<Int, String>? {
        if (!path.startsWith(CLOUD_SERVER_PATH_PREFIX)) return null
        val payload = path.removePrefix(CLOUD_SERVER_PATH_PREFIX)
        val separator = payload.indexOf(':')
        if (separator <= 0) return null
        val serverId = payload.substring(0, separator).toIntOrNull() ?: return null
        val serverPath = payload.substring(separator + 1).ifBlank { ROOT_PATH }
        return serverId to normalizePath(serverPath)
    }

    private fun findBestServerIdForPath(
        servers: List<WebDavServer>,
        path: String,
    ): Int? {
        val normalizedPath = normalizePath(path)
        if (normalizedPath == ROOT_PATH) return null

        return servers
            .asSequence()
            .map { it to normalizePath(it.basePath) }
            .filter { (_, basePath) ->
                normalizedPath == basePath || normalizedPath.startsWith("$basePath/")
            }
            .maxByOrNull { (_, basePath) -> basePath.length }
            ?.first
            ?.id
    }

    private fun stableUrlForDimensionCache(url: String): String = url.substringBefore('?')

    private suspend fun enrichFolderWithDimensionCache(serverId: Int, folder: Folder): Folder {
        var mediaEnriched = 0
        var mediaStillZero = 0
        val updatedMedia = folder.mediaList.map { video ->
            if (video.width > 0 && video.height > 0) {
                video
            } else {
                val dims = resolveVideoDimensionsFromCache(serverId, video)
                if (dims != null) {
                    mediaEnriched++
                    video.copy(width = dims.first, height = dims.second)
                } else {
                    mediaStillZero++
                    video
                }
            }
        }
        var folderEnriched = 0
        var folderStillZero = 0
        val updatedFolders = folder.folderList.map { f ->
            val cover = f.coverMedia ?: return@map f
            if (cover.width > 0 && cover.height > 0) {
                f
            } else {
                val dims = resolveVideoDimensionsFromCache(serverId, cover)
                if (dims != null) {
                    folderEnriched++
                    f.copy(coverMedia = cover.copy(width = dims.first, height = dims.second))
                } else {
                    folderStillZero++
                    f
                }
            }
        }
        if (updatedMedia === folder.mediaList && updatedFolders === folder.folderList) return folder
        return folder.copy(mediaList = updatedMedia, folderList = updatedFolders)
    }

    private suspend fun mergeDimensionsFromOldState(serverId: Int, oldFolder: Folder, newFolder: Folder): Folder {
        val oldMediaByStableUrl = mutableMapOf<String, Video>()
        for (v in oldFolder.mediaList) {
            oldMediaByStableUrl[stableUrlForDimensionCache(v.uriString)] = v
        }
        val oldCoverByStableUrl = mutableMapOf<String, Video>()
        for (f in oldFolder.folderList) {
            val cover = f.coverMedia ?: continue
            oldCoverByStableUrl[stableUrlForDimensionCache(cover.uriString)] = cover
        }

        var mediaMerged = 0
        var coverMerged = 0
        var urlPreserved = 0

        val mergedMedia = newFolder.mediaList.map { video ->
            val oldVideo = oldMediaByStableUrl[stableUrlForDimensionCache(video.uriString)]
            if (oldVideo != null) {
                urlPreserved++

                if (oldVideo.width > 0 && oldVideo.height > 0) {
                    oldVideo
                } else if (video.width > 0 && video.height > 0) {
                    mediaMerged++
                    oldVideo.copy(width = video.width, height = video.height)
                } else {
                    val dims = resolveVideoDimensionsFromCache(serverId, video)
                    if (dims != null) {
                        mediaMerged++
                        oldVideo.copy(width = dims.first, height = dims.second)
                    } else {
                        oldVideo
                    }
                }
            } else if (video.width > 0 && video.height > 0) {
                video
            } else {
                val dims = resolveVideoDimensionsFromCache(serverId, video)
                if (dims != null) {
                    mediaMerged++
                    video.copy(width = dims.first, height = dims.second)
                } else {
                    video
                }
            }
        }

        val mergedFolders = newFolder.folderList.map { f ->
            val cover = f.coverMedia ?: return@map f
            val oldCover = oldCoverByStableUrl[stableUrlForDimensionCache(cover.uriString)]
            if (oldCover != null) {
                urlPreserved++

                if (oldCover.width > 0 && oldCover.height > 0) {
                    f.copy(coverMedia = oldCover)
                } else if (cover.width > 0 && cover.height > 0) {
                    coverMerged++
                    f.copy(coverMedia = oldCover.copy(width = cover.width, height = cover.height))
                } else {
                    val dims = resolveVideoDimensionsFromCache(serverId, cover)
                    if (dims != null) {
                        coverMerged++
                        f.copy(coverMedia = oldCover.copy(width = dims.first, height = dims.second))
                    } else {
                        f.copy(coverMedia = oldCover)
                    }
                }
            } else if (cover.width > 0 && cover.height > 0) {
                f
            } else {
                val dims = resolveVideoDimensionsFromCache(serverId, cover)
                if (dims != null) {
                    coverMerged++
                    f.copy(coverMedia = cover.copy(width = dims.first, height = dims.second))
                } else {
                    f
                }
            }
        }

        return newFolder.copy(mediaList = mergedMedia, folderList = mergedFolders)
    }

    private fun collectMissingDimensionTargets(folder: Folder): List<DimensionProbeTarget> {
        val targets = mutableListOf<DimensionProbeTarget>()
        for (v in folder.mediaList) {
            if (v.uriString.isNotBlank() && (v.width <= 0 || v.height <= 0)) {
                targets.add(DimensionProbeTarget(probeUrl = v.thumbnailUriString ?: v.uriString, cacheUrl = v.uriString))
            }
        }
        for (f in folder.folderList) {
            val cover = f.coverMedia ?: continue
            if (cover.uriString.isNotBlank() && (cover.width <= 0 || cover.height <= 0)) {
                targets.add(DimensionProbeTarget(probeUrl = cover.thumbnailUriString ?: cover.uriString, cacheUrl = cover.uriString))
            }
        }
        return targets.distinctBy { stableUrlForDimensionCache(it.probeUrl) to stableUrlForDimensionCache(it.cacheUrl) }
    }

    private suspend fun probeMissingCloudDimensions(
        targets: List<DimensionProbeTarget>,
        concurrency: Int = DEFAULT_CLOUD_DIMENSION_PROBE_CONCURRENCY,
    ) {
        val semaphore = Semaphore(concurrency.coerceAtLeast(1))
        var successCount = 0
        var failCount = 0
        targets.map { target ->
            viewModelScope.async(Dispatchers.IO) {
                semaphore.withPermit {
                    openListApi.probeImageDimensions(target.probeUrl)
                        .onSuccess { (w, h) ->
                            successCount++
                            reportCloudImageDimensions(target.cacheUrl, w, h)
                        }
                        .onFailure {
                            failCount++
                        }
                }
            }
        }.awaitAll()
    }

    private suspend fun warmCloudThumbnailCache(
        images: List<Video>,
        preferences: ApplicationPreferences,
    ) {
        if (images.isEmpty()) return
        val warmLimit = if (preferences.imageCloudDiskCacheEnabled) {
            CLOUD_THUMBNAIL_WARM_DISK_MAX_PER_PAGE
        } else {
            CLOUD_THUMBNAIL_WARM_MEMORY_MAX_PER_PAGE
        }
        val targets = images
            .asSequence()
            .map { it.thumbnailUriString?.takeIf(String::isNotBlank) ?: it.uriString }
            .filter { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
            .distinct()
            .take(warmLimit)
            .toList()
        if (targets.isEmpty()) return

        Logger.d(TAG, "warmCloudThumbnailCache start images=${images.size} targets=${targets.size}")
        withContext(Dispatchers.IO) {
            val imageLoader = SingletonImageLoader.get(context)
            val semaphore = Semaphore(CLOUD_THUMBNAIL_WARM_CONCURRENCY)
            val successCount = AtomicInteger(0)
            val failureCount = AtomicInteger(0)
            targets.map { uri ->
                async {
                    semaphore.withPermit {
                        runCatching {
                            val result = imageLoader.execute(
                                buildImageRequest(
                                    context = context,
                                    data = uri,
                                    quality = preferences.imageQuality,
                                    profile = ImageRequestProfile.THUMBNAIL,
                                    thumbnailMaxEdgePx = preferences.imageBrowserThumbnailSizePx,
                                ),
                            )
                            if (result is SuccessResult) {
                                GridImageLoadMemory.add(uri)
                                successCount.incrementAndGet()
                            } else {
                                failureCount.incrementAndGet()
                            }
                        }.onFailure {
                            failureCount.incrementAndGet()
                        }
                    }
                }
            }.awaitAll()
            Logger.d(TAG, "warmCloudThumbnailCache done targets=${targets.size} success=${successCount.get()} failure=${failureCount.get()}")
        }
    }

    private suspend fun resolveDimensionsFromCache(serverId: Int, uri: String): Pair<Int, Int>? = imageDimensionCache.get(serverId, uri)
        ?: imageDimensionCache.get(serverId, stableUrlForDimensionCache(uri))

    private suspend fun resolveVideoDimensionsFromCache(serverId: Int, video: Video): Pair<Int, Int>? = resolveDimensionsFromCache(serverId, video.uriString)
        ?: video.thumbnailUriString?.let { resolveDimensionsFromCache(serverId, it) }

    private fun formatCloudErrorMessage(error: Throwable): String {
        val raw = error.message.orEmpty().ifBlank { "Failed to load images" }
        return WEB_URL_REGEX.replace(raw) { match ->
            Uri.decode(match.value)
        }
    }

    private fun reportCloudImageDimensions(uriString: String, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return

        pendingCloudDimensionUpdates[uriString] = width to height
        cloudDimensionFlushJob?.cancel()
        cloudDimensionFlushJob = viewModelScope.launch {
            delay(80)
            flushPendingCloudDimensions()
        }
    }

    private fun flushPendingCloudDimensions() {
        if (pendingCloudDimensionUpdates.isEmpty()) return
        val state = uiStateInternal.value
        val serverId = state.selectedCloudServerId ?: return
        val pending = pendingCloudDimensionUpdates.toMap()
        pendingCloudDimensionUpdates.clear()

        pending.forEach { (uri, size) ->
            imageDimensionCache.put(serverId, stableUrlForDimensionCache(uri), size.first, size.second)
        }

        val currentFolder = (state.cloudGalleryState as? ImageGalleryUiState.Content)?.folder ?: return
        var changed = false

        val updatedMediaList = currentFolder.mediaList.map { video ->
            val newSize = pending[video.uriString]
            if (newSize != null && (video.width <= 0 || video.height <= 0)) {
                changed = true
                video.copy(width = newSize.first, height = newSize.second)
            } else {
                video
            }
        }

        val updatedFolderList = currentFolder.folderList.map { folder ->
            val cover = folder.coverMedia
            if (cover != null) {
                val newSize = pending[cover.uriString]
                if (newSize != null && (cover.width <= 0 || cover.height <= 0)) {
                    changed = true
                    folder.copy(coverMedia = cover.copy(width = newSize.first, height = newSize.second))
                } else {
                    folder
                }
            } else {
                folder
            }
        }

        if (changed) {
            val updatedFolder = currentFolder.copy(
                mediaList = updatedMediaList,
                folderList = updatedFolderList,
            )
            uiStateInternal.update {
                it.copy(cloudGalleryState = ImageGalleryUiState.Content(updatedFolder))
            }
        }
    }
}

data class ImageBrowserUiState(
    val mode: MediaMode = MediaMode.LOCAL,
    val preferences: ApplicationPreferences = ApplicationPreferences(),
    val localPath: String = ROOT_PATH,
    val localGalleryState: ImageGalleryUiState = ImageGalleryUiState.Loading,
    val webDavServers: List<WebDavServer> = emptyList(),
    val selectedCloudServerIds: Set<Int> = emptySet(),
    val selectedCloudServerId: Int? = null,
    val cloudPath: String = ROOT_PATH,
    val cloudRefreshing: Boolean = false,
    val cloudGalleryState: ImageGalleryUiState = ImageGalleryUiState.Loading,
    val cloudPage: Int = 1,
    val cloudHasMore: Boolean = true,
    val cloudTotalItems: Int = 0,
    val cloudLoadingMore: Boolean = false,
    val cloudLoadingPhase: ImageCloudLoadingPhase = ImageCloudLoadingPhase.READING_SNAPSHOT,
    val cloudError: String? = null,
)

sealed interface ImageGalleryUiState {
    data object Loading : ImageGalleryUiState
    data object Empty : ImageGalleryUiState
    data class Error(val message: String, val cause: Throwable? = null) : ImageGalleryUiState
    data class Content(val folder: Folder) : ImageGalleryUiState
}

enum class ImageCloudLoadingPhase {
    READING_SNAPSHOT,
    REFRESHING_REMOTE,
    PREPARING_COVERS,
}

sealed interface ImageBrowserUiEvent {
    data object ToggleMode : ImageBrowserUiEvent
    data class UpdateMenu(val preferences: ApplicationPreferences) : ImageBrowserUiEvent
    data class OpenLocalFolder(val path: String) : ImageBrowserUiEvent
    data object NavigateLocalUp : ImageBrowserUiEvent
    data object RefreshLocal : ImageBrowserUiEvent
    data class SelectCloudServer(val serverId: Int) : ImageBrowserUiEvent
    data class ToggleCloudServerSelection(val serverId: Int) : ImageBrowserUiEvent
    data class OpenCloudFolder(val path: String) : ImageBrowserUiEvent
    data class PreloadCloudPath(val path: String) : ImageBrowserUiEvent
    data object NavigateCloudUp : ImageBrowserUiEvent
    data object RefreshCloud : ImageBrowserUiEvent
    data object LoadNextCloudPage : ImageBrowserUiEvent
    data class ReportCloudImageDimensions(val uriString: String, val width: Int, val height: Int) : ImageBrowserUiEvent
}

private const val ROOT_PATH = "/"
private const val TAG = "ImageBrowserVM"
private const val FOLDER_COVERS_DIR = "folder_cover_images"
private const val IMAGE_LOCAL_PATH_KEY = "image_local_path"
private const val IMAGE_CLOUD_PATH_KEY = "image_cloud_path"
private const val IMAGE_CLOUD_SERVER_ID_KEY = "image_cloud_server_id"
private const val IMAGE_CLOUD_SERVER_IDS_KEY = "image_cloud_server_ids"
private const val CLOUD_SERVER_PATH_PREFIX = "__cloud_server__"
private const val MULTI_CLOUD_MAX_CONCURRENT_REQUESTS = 2
private const val MAX_CLOUD_PREVIEW_CONCURRENT_REQUESTS = 4
private const val MAX_CLOUD_LEAF_DEPTH = 6
private const val CLOUD_INDEX_SEARCH_PAGE_SIZE = 10_000
private const val CLOUD_INDEX_SEARCH_MAX_RESULTS = 50_000
private const val CLOUD_INDEX_PARENT_LIST_PAGE_SIZE = 5_000
private const val CLOUD_INDEX_PARENT_LIST_MAX_CONCURRENCY = 8
private const val DEFAULT_CLOUD_DIMENSION_PROBE_CONCURRENCY = 8
private const val IMAGE_HOSTING_DIMENSION_PROBE_CONCURRENCY = 2
private const val IMAGE_HOSTING_FOLDER_COVER_PREFETCH_CONCURRENCY = 2
private const val IMAGE_HOSTING_FOLDER_COVER_PREFETCH_LIMIT = 24
private const val CLOUD_THUMBNAIL_WARM_CONCURRENCY = 4
private const val CLOUD_THUMBNAIL_WARM_DISK_MAX_PER_PAGE = 50
private const val CLOUD_THUMBNAIL_WARM_MEMORY_MAX_PER_PAGE = 24
private const val IMAGE_HOSTING_INDEX_ALL_COUNT = -1
private const val IMAGE_HOSTING_IMAGE_SNAPSHOT_PREFIX = "__image_hosting_images__:"
private const val FOLDER_COVER_DOWNLOAD_TIMEOUT_MS = 15_000
private const val FOLDER_COVER_FALLBACK_EXTENSION = "webp"

private val FOLDER_COVER_EXTENSIONS = setOf("webp", "avif", "jpg", "jpeg", "png", "gif")
private val CONTENT_TYPE_TO_FOLDER_COVER_EXTENSION = mapOf(
    "image/webp" to "webp",
    "image/avif" to "avif",
    "image/jpeg" to "jpg",
    "image/jpg" to "jpg",
    "image/png" to "png",
    "image/gif" to "gif",
)

private data class DimensionProbeTarget(
    val probeUrl: String,
    val cacheUrl: String,
)

private data class CloudIndexedImage(
    val item: FsSearchItem,
    val parentPath: String,
)

private data class FsSearchDataSnapshot(
    val total: Int,
    val content: List<FsSearchItem>,
)

private val WEB_URL_REGEX = Regex("https?://\\S+")

private val CLOUD_INDEX_IMAGE_EXTENSIONS = setOf(
    "jpg", "jpeg", "png", "webp", "gif", "bmp", "avif", "heic", "heif", "tif", "tiff",
)

private fun ApplicationPreferences.hasDifferentImageCloudDisplayShape(
    other: ApplicationPreferences,
): Boolean = imageViewMode != other.imageViewMode ||
    imageSortBy != other.imageSortBy ||
    imageSortOrder != other.imageSortOrder

private fun MediaViewMode.showsImagesOnly(): Boolean = this == MediaViewMode.IMAGE || this == MediaViewMode.VIDEOS

private fun FsSearchItem.isIndexedCloudImageFile(): Boolean {
    if (is_dir) return false
    if (type == 5) return true
    val extension = name
        .substringBefore('?')
        .substringBefore('#')
        .substringAfterLast('/', name)
        .substringAfterLast('.', "")
        .lowercase()
    return extension in CLOUD_INDEX_IMAGE_EXTENSIONS
}

private fun FsSearchItem.toSyntheticImageWebDavMediaItem(
    server: WebDavServer,
    parentPath: String,
): WebDavMediaItem {
    val serverUri = Uri.parse(server.url)
    val authority = if (serverUri.port != -1) "${serverUri.host}:${serverUri.port}" else serverUri.host.orEmpty()
    val rootBaseUrl = "${serverUri.scheme}://$authority"
    val apiParentPath = server.toApiPath(parentPath)
    val encodedDirSegments = apiParentPath.removePrefix("/")
        .split('/')
        .filter { it.isNotBlank() }
        .joinToString("/") { Uri.encode(Uri.decode(it)) }
    val decodedName = Uri.decode(name)
    val encodedName = Uri.encode(decodedName)
    val filePath = if (encodedDirSegments.isBlank()) {
        encodedName
    } else {
        "$encodedDirSegments/$encodedName"
    }
    val href = if (server.isImageHosting) {
        "$rootBaseUrl/file/$filePath"
    } else if (encodedDirSegments.isBlank()) {
        "$rootBaseUrl/d/$encodedName"
    } else {
        "$rootBaseUrl/d/$encodedDirSegments/$encodedName"
    }
    return WebDavMediaItem(
        name = decodedName,
        href = href,
        contentType = guessImageContentType(decodedName),
        size = size,
        width = width.takeIf { it > 0 },
        height = height.takeIf { it > 0 },
        lastModified = parseCloudFlareImgBedTimestamp(modified),
        isDirectory = false,
        serverId = server.id,
        rawVideoUrl = href,
    )
}

private fun parseCloudFlareImgBedTimestamp(value: String): Date? {
    if (value.isBlank()) return null
    value.toLongOrNull()?.let { timestamp ->
        val millis = if (timestamp < 10_000_000_000L) timestamp * 1000L else timestamp
        return Date(millis)
    }
    return try {
        Date.from(Instant.parse(value))
    } catch (_: DateTimeParseException) {
        null
    }
}

private fun guessImageContentType(name: String): String = when (
    name.substringBefore('?')
        .substringBefore('#')
        .substringAfterLast('.', "")
        .lowercase()
) {
    "webp" -> "image/webp"
    "avif" -> "image/avif"
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "gif" -> "image/gif"
    "bmp" -> "image/bmp"
    "heic" -> "image/heic"
    "heif" -> "image/heif"
    else -> ""
}

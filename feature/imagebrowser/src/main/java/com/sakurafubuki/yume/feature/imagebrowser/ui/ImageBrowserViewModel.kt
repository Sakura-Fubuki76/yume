package com.sakurafubuki.yume.feature.imagebrowser.ui

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import com.sakurafubuki.yume.core.cache.ImageCacheManager
import com.sakurafubuki.yume.core.common.Logger
import com.sakurafubuki.yume.core.common.Utils
import com.sakurafubuki.yume.core.common.extensions.stripUserInfoFromHttpUrl
import com.sakurafubuki.yume.core.data.cache.CloudFolderCache
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
import com.sakurafubuki.yume.core.model.WebDavServer
import com.sakurafubuki.yume.feature.imagebrowser.navigation.imageBrowserCloudServerIdArg
import com.sakurafubuki.yume.feature.imagebrowser.navigation.imageBrowserPathArg
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
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
    private val cloudFolderCache: CloudFolderCache,
    private val cloudVideoMetadataRepository: com.sakurafubuki.yume.core.data.repository.CloudVideoMetadataRepository,
    private val openListApi: OpenListApi,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val routePath = normalizePath(
        Uri.decode(savedStateHandle.get<String>(imageBrowserPathArg).orEmpty()).ifBlank { ROOT_PATH },
    )
    private val initialCloudServerIdFromRoute = savedStateHandle.get<String>(imageBrowserCloudServerIdArg)?.toIntOrNull()
    private val initialLocalPath = savedStateHandle.get<String>(IMAGE_LOCAL_PATH_KEY)
        ?.let(::normalizePath)
        ?: routePath.takeIf { initialCloudServerIdFromRoute == null }
        ?: ROOT_PATH
    private val initialCloudPath = savedStateHandle.get<String>(IMAGE_CLOUD_PATH_KEY)
        ?.let(::normalizePath)
        ?: routePath.takeIf { initialCloudServerIdFromRoute != null }
        ?: ROOT_PATH
    private val initialCloudServerId = savedStateHandle.get<Int>(IMAGE_CLOUD_SERVER_ID_KEY)
        ?: initialCloudServerIdFromRoute
    private val initialCloudServerIds = savedStateHandle.get<ArrayList<Int>>(IMAGE_CLOUD_SERVER_IDS_KEY)?.toSet()

    private var cloudLoadJob: Job? = null
    private var cloudLoadRequestToken: Long = 0L
    private var localPublishJob: Job? = null

    private var localImages = emptyList<LocalImage>()

    private val preloadJobs = ConcurrentHashMap<String, Job>()
    private val cloudPreviewSemaphore = Semaphore(MAX_CLOUD_PREVIEW_CONCURRENT_REQUESTS)
    private val pendingCloudDimensionUpdates = mutableMapOf<String, Pair<Int, Int>>()
    private var cloudDimensionFlushJob: Job? = null
    private var cloudFolderCacheJob: Job? = null

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
            .build()
    }

    init {
        viewModelScope.launch {
            preferencesRepository.applicationPreferences.collect { preferences ->
                uiStateInternal.update { it.copy(preferences = preferences) }
                publishLocalFolder()
            }
        }

        viewModelScope.launch {
            var hadServers = false
            webDavServerRepository.observeServers().collect { servers ->
                val serversJustBecameAvailable = !hadServers && servers.isNotEmpty()
                hadServers = servers.isNotEmpty()
                ImageViewerStore.imageHostingBaseUrls = servers
                    .filter { it.isImageHosting }
                    .map { it.url.trimEnd('/') }
                    .toSet()
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
            ?: return
        val path = normalizePath(state.cloudPath.ifBlank { server.basePath })
        val preferences = state.preferences

        cloudLoadJob?.cancel()
        val requestToken = ++cloudLoadRequestToken

        val cachedFolderForColdStart = if (!refreshing) {
            cloudFolderCache.get(server.id, path, "image")
        } else {
            null
        }

        uiStateInternal.update {
            it.copy(
                selectedCloudServerId = server.id,
                cloudPath = path,
                cloudGalleryState = when {
                    refreshing -> it.cloudGalleryState
                    cachedFolderForColdStart != null -> {
                        ImageGalleryUiState.Content(cachedFolderForColdStart)
                    }
                    else -> ImageGalleryUiState.Loading
                },
                cloudPage = 1,
                cloudHasMore = true,
                cloudTotalItems = 0,
                cloudLoadingMore = false,
                cloudError = null,
            )
        }

        if (cachedFolderForColdStart != null && cachedFolderForColdStart.folderList.isNotEmpty()) {
            val coldStartToken = requestToken
            viewModelScope.launch {
                val enriched = enrichFolderWithDbMetadata(server.id, cachedFolderForColdStart)
                if (coldStartToken != cloudLoadRequestToken) return@launch
                if (enriched.folderList != cachedFolderForColdStart.folderList) {
                    uiStateInternal.update {
                        val currentState = it.cloudGalleryState
                        if (currentState is ImageGalleryUiState.Content) {
                            it.copy(cloudGalleryState = ImageGalleryUiState.Content(currentState.folder.copy(folderList = enriched.folderList)))
                        } else {
                            it
                        }
                    }
                }
            }
        }

        cloudLoadJob = viewModelScope.launch {
            val perPage = 50
            val apiPath = server.toApiPath(path)
            openListApi.listDirectory(server, apiPath, page = 1, perPage = perPage, refresh = refreshing)
                .onSuccess { data ->
                    if (requestToken != cloudLoadRequestToken) return@onSuccess
                    val items = data.content.orEmpty().map { it.toWebDavMediaItem(server, apiPath) }
                    val rawFolder = mapCloudFolder(server = server, path = apiPath, preferences = preferences, items = items)
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
                            cloudError = null,
                        )
                    }

                    launch(Dispatchers.IO) {
                        cloudFolderCache.put(server.id, path, displayFolder, "image")
                    }

                    val dimensionTargets = collectMissingDimensionTargets(displayFolder)
                    if (dimensionTargets.isNotEmpty()) {
                        launch { probeMissingCloudDimensions(dimensionTargets) }
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
            val apiPath = server.toApiPath(path)
            openListApi.listDirectory(server, apiPath, page = nextPage, perPage = perPage)
                .onSuccess { data ->
                    val newItems = data.content.orEmpty().map { it.toWebDavMediaItem(server, apiPath) }

                    val newFolder = mapCloudFolder(server = server, path = apiPath, preferences = preferences, items = newItems)
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

                    val dimensionTargets = collectMissingDimensionTargets(folder)
                    if (dimensionTargets.isNotEmpty()) {
                        launch { probeMissingCloudDimensions(dimensionTargets) }
                    }

                    scheduleCloudFolderCache(server.id, path, folder)
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
                scheduleCloudFolderCache(server.id, path, folder)
            }
        }
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
        if (selectedServers.isEmpty()) return

        cloudLoadJob?.cancel()
        val requestToken = ++cloudLoadRequestToken
        cloudLoadJob = viewModelScope.launch {
            uiStateInternal.update {
                val keepExistingData = !refreshing && it.cloudGalleryState is ImageGalleryUiState.Content
                it.copy(
                    cloudPath = ROOT_PATH,
                    cloudRefreshing = refreshing || keepExistingData,
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
                MediaViewMode.VIDEOS -> emptyList<Folder>() to mergedImages.sortedWith(sort.videoComparator())
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
        val refreshedByPath = coroutineScope {
            folder.folderList.map { child ->
                async(Dispatchers.IO) {
                    val childPath = normalizePath(child.path)
                    val items = loadCloudPreviewItems(server, childPath)
                        ?: return@async childPath to child
                    val images = items.filter { it.isImage }
                        .map { item -> mapCloudImageItemToVideo(server, childPath, item) }
                        .sortedWith(sort.videoComparator())
                    val directFolderCount = items.count { it.isDirectory }
                    childPath to child.copy(
                        coverMedia = images.firstOrNull() ?: child.coverMedia,
                        mediaCount = if (images.isNotEmpty()) images.size else child.mediaCount,
                        folderCount = maxOf(child.folderCount, directFolderCount),
                    )
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
            },
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
        scheduleCloudFolderCache(server.id, folder.path, displayFolder)
    }

    private fun preloadCloudPath(path: String) {
        val state = uiStateInternal.value
        val server = state.webDavServers.firstOrNull { it.id == state.selectedCloudServerId }
            ?: state.webDavServers.firstOrNull()
            ?: return
        val normalizedPath = normalizePath(path)

        if (webDavDirectoryCache.get(server.id, normalizedPath) != null) return

        preloadJobs[normalizedPath]?.cancel()
        preloadJobs[normalizedPath] = viewModelScope.launch {
            delay(300)
            try {
                val apiPath = server.toApiPath(normalizedPath)
                openListApi.listDirectory(server, apiPath, page = 1, perPage = 200)
                    .onSuccess { data ->
                        webDavDirectoryCache.put(
                            server.id,
                            normalizedPath,
                            data.content.orEmpty().map { it.toWebDavMediaItem(server, apiPath) },
                        )
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
            val (coverMedia, mediaCount) = buildCloudFolderPreview(
                server = server,
                folderPath = folderPath,
                sort = sort,
            )
            Folder(
                name = displayName,
                path = folderPath,
                dateModified = item.lastModified?.time ?: 0L,
                coverMedia = coverMedia,
                mediaCount = mediaCount,
            )
        }.sortedWith(sort.folderComparator())

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
        }.sortedWith(sort.videoComparator())

        val (displayFolders, displayImages) = when (viewMode) {
            MediaViewMode.VIDEOS -> emptyList<Folder>() to images
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
            MediaViewMode.VIDEOS -> emptyList()
        }

        val imagesInCurrent = (videosByParent[normalizedCurrentPath] ?: emptyList())
            .sortedWith(sort.videoComparator())

        val (displayFolders, displayImages) = when (viewMode) {
            MediaViewMode.FOLDERS -> if (normalizedCurrentPath == ROOT_PATH) folderChildren to imagesInCurrent else emptyList<Folder>() to imagesInCurrent
            MediaViewMode.VIDEOS -> emptyList<Folder>() to videos.sortedWith(sort.videoComparator())
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
                .sortedWith(sort.videoComparator())
            return media.firstOrNull() to media.size
        }
        val cachedFolder = cloudFolderCache.get(server.id, normalizedPath, "image")
        if (cachedFolder != null) {
            return cachedFolder.coverMedia to cachedFolder.mediaCount
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

    private suspend fun mapCloudImageItemToVideo(
        server: WebDavServer,
        path: String,
        item: com.sakurafubuki.yume.core.model.WebDavMediaItem,
    ): Video {
        val imageUrl = item.rawVideoUrl
            ?.stripUserInfoFromHttpUrl()
            ?: webDavRepository.getStreamUrl(item, server).stripUserInfoFromHttpUrl()
        val thumbnailUrl = item.apiThumbnailUrl
            ?.stripUserInfoFromHttpUrl()
            ?.takeIf { it.isNotBlank() && it != imageUrl }
        val dimensionUrl = thumbnailUrl ?: imageUrl
        val decodedPath = Uri.decode(imageUrl.toUri().path.orEmpty())
        val displayName = Uri.decode(item.name).ifBlank { decodedPath.substringAfterLast('/') }
        val cached = resolveDimensionsFromCache(server.id, imageUrl)
            ?: resolveDimensionsFromCache(server.id, dimensionUrl)
        val resolvedWidth = item.width?.takeIf { it > 0 } ?: cached?.first ?: 0
        val resolvedHeight = item.height?.takeIf { it > 0 } ?: cached?.second ?: 0
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
            if (!server.isImageHosting &&
                coverUriToSave != null &&
                isRemoteImageData(coverUriToSave) &&
                (coverUriToSave != existingDbUri || !localFolderCoverFile(coverUriToSave).exists())
            ) {
                persistFolderCoverLocally(coverUriToSave)
            }
            val existing = existingMetadata[child.path]
            val resolvedMediaCount = when {
                child.mediaCount > 0 -> child.mediaCount
                child.mediaList.isNotEmpty() -> child.mediaList.size
                else -> existing?.mediaCount ?: 0
            }
            val resolvedFolderCount = when {
                child.folderCount > 0 -> child.folderCount
                child.folderList.isNotEmpty() -> child.folderList.size
                else -> existing?.folderCount ?: 0
            }
            val resolvedTotalDurationMs = existing?.totalDurationMs ?: 0L
            val resolvedTotalSize = existing?.totalSize ?: child.mediaSize
            val resolvedImageCount = if (resolvedMediaCount > 0) resolvedMediaCount else existing?.imageCount ?: 0
            val metadataChanged = existing == null ||
                existing.totalDurationMs != resolvedTotalDurationMs ||
                existing.totalSize != resolvedTotalSize ||
                existing.mediaCount != resolvedMediaCount ||
                existing.folderCount != resolvedFolderCount ||
                existing.coverImageUri != coverUriToSave ||
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
                    imageCount = resolvedImageCount,
                )
            }
        }
    }

    private suspend fun persistFolderCoverLocally(coverImageUri: String): String? = withContext(Dispatchers.IO) {
        try {
            val stableKey = stableCacheKey(coverImageUri)
            val outputFile = localFolderCoverFile(coverImageUri)
            if (outputFile.exists()) return@withContext Uri.fromFile(outputFile).toString()
            val imageLoader = SingletonImageLoader.get(context)
            val uri = coverImageUri.toUri()
            val request = ImageRequest.Builder(context)
                .data(uri)
                .size(512, 512)
                .diskCacheKey(stableKey)
                .memoryCacheKey(stableKey)
                .build()
            imageLoader.execute(request)
            val diskCache = imageLoader.diskCache
            if (diskCache == null) {
                Logger.w(TAG, "persistFolderCoverLocally: diskCache is null, cannot save local cover")
                return@withContext null
            }
            diskCache.openSnapshot(stableKey)?.use { snapshot ->
                val cachedFile = snapshot.data.toFile()
                cachedFile.copyTo(outputFile, overwrite = true)
                return@withContext Uri.fromFile(outputFile).toString()
            }
            null
        } catch (e: Exception) {
            Logger.e(TAG, "persistFolderCoverLocally: failed for $coverImageUri", e)
            null
        }
    }

    private fun folderCoverCacheDir(): File = File(context.cacheDir, FOLDER_COVERS_DIR).apply { mkdirs() }

    private fun localFolderCoverFile(coverImageUri: String): File {
        val stableKey = stableCacheKey(coverImageUri)
        return File(folderCoverCacheDir(), "${sha256(stableKey)}.jpg")
    }

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
            if (result.mediaCount == 0 && meta.mediaCount > 0) {
                result = result.copy(mediaCount = meta.mediaCount)
            }
            if (result.folderCount == 0 && meta.folderCount > 0) {
                result = result.copy(folderCount = meta.folderCount)
            }
            if (meta.coverImageUri != null) {
                val dbCoverUri = meta.coverImageUri!!
                val localFile = localFolderCoverFile(dbCoverUri)
                val displayUri = if (localFile.exists()) {
                    Uri.fromFile(localFile).toString()
                } else {
                    dbCoverUri
                }

                val cachedDims = resolveDimensionsFromCache(serverId, displayUri)
                result = result.copy(
                    coverMedia = Video(
                        id = displayUri.hashCode().toLong(),
                        path = child.path,
                        duration = 1L,
                        uriString = displayUri,
                        nameWithExtension = "",
                        width = cachedDims?.first ?: 0,
                        height = cachedDims?.second ?: 0,
                        size = 0,
                        dateModified = 0L,
                    ),
                )
            }
            result
        }
        return folder.copy(folderList = enriched)
    }

    private fun effectiveImageViewMode(mediaViewMode: MediaViewMode): MediaViewMode = mediaViewMode

    private suspend fun resolveCloudLeafFolders(
        server: WebDavServer,
        folder: Folder,
        preferences: ApplicationPreferences,
    ): Folder {
        val viewMode = effectiveImageViewMode(preferences.imageViewMode)
        if (viewMode == MediaViewMode.VIDEOS) return folder
        if (folder.folderList.isEmpty()) return folder

        val sort = imageSort(preferences)
        if (viewMode == MediaViewMode.FOLDER_TREE) {
            return enrichCloudTreeFolders(server, folder, sort)
        }

        val resolvedFolders = folder.folderList.flatMap { child ->
            resolveLeafFolderItem(server, child, sort, MAX_CLOUD_LEAF_DEPTH)
        }
        return folder.copy(
            folderList = deduplicateFolderNames(resolvedFolders.sortedWith(sort.folderComparator())),
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
        return folder.copy(folderList = deduplicateFolderNames(enrichedFolders.sortedWith(sort.folderComparator())))
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
            .sortedWith(sort.videoComparator())
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
        childFolders.sortWith(sort.folderComparator())

        val coverMedia = images.firstOrNull()
            ?: childFolders.firstNotNullOfOrNull { it.coverMedia }
            ?: child.coverMedia.takeIf { child.mediaCount > 0 }
        val mediaCount = when {
            images.isNotEmpty() -> images.size
            childFolders.any { it.mediaCount > 0 } -> childFolders.sumOf { it.mediaCount }
            else -> 0
        }

        return child.copy(
            coverMedia = coverMedia,
            mediaCount = mediaCount,
            folderList = childFolders,
            folderCount = childFolders.size,
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
        webDavDirectoryCache.get(server.id, normalizedPath)?.let { return it }
        val data = cloudPreviewSemaphore.withPermit {
            openListApi.listDirectory(server, normalizedPath, page = 1, perPage = 200).getOrNull()
        } ?: return null
        return data.content.orEmpty().map { it.toWebDavMediaItem(server, normalizedPath) }
            .also { webDavDirectoryCache.put(server.id, normalizedPath, it) }
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
            .sortedWith(sort.videoComparator())
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

        var cachedItems = webDavDirectoryCache.get(server.id, path)
        if (cachedItems == null) {
            val diskFolder = cloudFolderCache.get(server.id, path, "image")
            if (diskFolder != null) {
                if (diskFolder.mediaCount > 0 || diskFolder.mediaList.isNotEmpty()) {
                    return listOf(
                        child.copy(
                            coverMedia = diskFolder.coverMedia,
                            mediaCount = diskFolder.mediaCount.coerceAtLeast(diskFolder.mediaList.size),
                        ),
                    )
                }
                if (diskFolder.folderList.isNotEmpty()) {
                    val subLeaves = diskFolder.folderList.flatMap { subChild ->
                        resolveLeafFolderItem(server, subChild, sort, remainingDepth - 1)
                            .map { it.copy(name = "${child.name}/${it.name}") }
                    }
                    if (subLeaves.isNotEmpty()) return subLeaves
                }
            }
        }

        if (cachedItems == null) {
            val apiResult = openListApi.listDirectory(server, path, page = 1, perPage = 200).getOrNull()
            cachedItems = apiResult?.content?.map { it.toWebDavMediaItem(server, path) }
                ?.also { webDavDirectoryCache.put(server.id, path, it) }
        }
        if (cachedItems == null) return listOf(child)

        val images = cachedItems.filter { it.isImage }
        val directories = cachedItems.filter { it.isDirectory }

        if (images.isNotEmpty()) {
            val media = images.map { item ->
                mapCloudImageItemToVideo(server, path, item)
            }.sortedWith(sort.videoComparator())
            return listOf(
                child.copy(
                    coverMedia = media.firstOrNull(),
                    mediaCount = media.size,
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
        val serverPath = normalizePath(Uri.decode(server.url.toUri().path.orEmpty()))
        val itemPath = normalizePath(Uri.decode(href.toUri().path.orEmpty()))
        return if (itemPath.startsWith(serverPath)) {
            normalizePath(itemPath.removePrefix(serverPath))
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
        val normalizedBase = normalizePath(server.basePath)
        val normalizedRelative = normalizePath(relativePath)
        if (normalizedRelative == ROOT_PATH) return normalizedBase
        if (normalizedRelative == normalizedBase || normalizedRelative.startsWith("$normalizedBase/")) {
            return normalizedRelative
        }
        if (normalizedBase == ROOT_PATH) return normalizedRelative
        return normalizePath("$normalizedBase/$normalizedRelative")
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

    private suspend fun probeMissingCloudDimensions(targets: List<DimensionProbeTarget>) {
        val semaphore = Semaphore(8)
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

            scheduleCloudFolderCache(serverId, state.cloudPath, updatedFolder)
        }
    }

    private fun scheduleCloudFolderCache(serverId: Int, path: String, folder: Folder) {
        cloudFolderCacheJob?.cancel()
        cloudFolderCacheJob = viewModelScope.launch(Dispatchers.IO) {
            delay(3000L)
            cloudFolderCache.put(serverId, path, folder, "image")
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
    val cloudError: String? = null,
)

sealed interface ImageGalleryUiState {
    data object Loading : ImageGalleryUiState
    data object Empty : ImageGalleryUiState
    data class Error(val message: String, val cause: Throwable? = null) : ImageGalleryUiState
    data class Content(val folder: Folder) : ImageGalleryUiState
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

private data class DimensionProbeTarget(
    val probeUrl: String,
    val cacheUrl: String,
)

private val WEB_URL_REGEX = Regex("https?://\\S+")

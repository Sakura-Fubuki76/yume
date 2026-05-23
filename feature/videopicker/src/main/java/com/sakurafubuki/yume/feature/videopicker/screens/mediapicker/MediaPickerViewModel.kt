package com.sakurafubuki.yume.feature.videopicker.screens.mediapicker

import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sakurafubuki.yume.core.common.Logger
import com.sakurafubuki.yume.core.common.Utils
import com.sakurafubuki.yume.core.common.extensions.prettyName
import com.sakurafubuki.yume.core.common.extensions.stripUserInfoFromHttpUrl
import com.sakurafubuki.yume.core.data.cache.CloudDirectoryItemCache
import com.sakurafubuki.yume.core.data.openlist.FsSearchItem
import com.sakurafubuki.yume.core.data.openlist.OpenListApi
import com.sakurafubuki.yume.core.data.openlist.toApiPath
import com.sakurafubuki.yume.core.data.openlist.toWebDavMediaItem
import com.sakurafubuki.yume.core.data.repository.CloudVideoMetadataRepository
import com.sakurafubuki.yume.core.data.repository.MediaRepository
import com.sakurafubuki.yume.core.data.repository.PreferencesRepository
import com.sakurafubuki.yume.core.data.repository.WebDavServerRepository
import com.sakurafubuki.yume.core.data.webdav.WebDavRepository
import com.sakurafubuki.yume.core.database.dao.MediumStateDao
import com.sakurafubuki.yume.core.database.entities.MediumStateEntity
import com.sakurafubuki.yume.core.domain.GetSortedMediaUseCase
import com.sakurafubuki.yume.core.media.services.MediaService
import com.sakurafubuki.yume.core.media.sync.MediaInfoSynchronizer
import com.sakurafubuki.yume.core.media.sync.MediaSynchronizer
import com.sakurafubuki.yume.core.model.ApplicationPreferences
import com.sakurafubuki.yume.core.model.CloudFolderMetadata
import com.sakurafubuki.yume.core.model.CloudVideoMetadata
import com.sakurafubuki.yume.core.model.Folder
import com.sakurafubuki.yume.core.model.MediaMode
import com.sakurafubuki.yume.core.model.MediaViewMode
import com.sakurafubuki.yume.core.model.Sort
import com.sakurafubuki.yume.core.model.Video
import com.sakurafubuki.yume.core.model.WebDavMediaItem
import com.sakurafubuki.yume.core.model.WebDavServer
import com.sakurafubuki.yume.core.ui.base.DataState
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

@HiltViewModel
class MediaPickerViewModel @Inject constructor(
    private val getSortedMediaUseCase: GetSortedMediaUseCase,
    savedStateHandle: SavedStateHandle,
    private val mediaService: MediaService,
    private val preferencesRepository: PreferencesRepository,
    private val webDavServerRepository: WebDavServerRepository,
    private val webDavRepository: WebDavRepository,
    private val cloudVideoMetadataRepository: CloudVideoMetadataRepository,
    private val mediumStateDao: MediumStateDao,
    private val mediaRepository: MediaRepository,
    private val mediaInfoSynchronizer: MediaInfoSynchronizer,
    private val mediaSynchronizer: MediaSynchronizer,
    private val webDavVideoDirectoryCache: WebDavVideoDirectoryCache,
    private val cloudFolderSummaryScanner: CloudFolderSummaryScanner,
    private val cloudDirectoryItemCache: CloudDirectoryItemCache,
    private val openListApi: OpenListApi,
) : ViewModel() {

    private val modeStateHandle = savedStateHandle

    private val initialFolderPath: String? = null
    private val initialCloudPath = "/"
    private val initialCloudServerId: Int? = null
    private val initialCloudServerIds = modeStateHandle.get<ArrayList<Int>>(CLOUD_SERVER_IDS_KEY)?.toSet()
    private var localMediaJob: Job? = null
    private var cloudLoadJob: Job? = null
    private var cloudAuxWorkJob: Job? = null
    private var cloudCachedRefreshJob: Job? = null
    private var cloudLoadRequestToken: Long = 0L
    private var activeCloudLoadKey: String? = null

    private val cloudPlaybackStates = MutableStateFlow<Map<String, MediumStateEntity>>(emptyMap())

    private val rawCloudFolder = MutableStateFlow<Folder?>(null)

    private val preloadingCloudPaths = mutableSetOf<String>()
    private val preloadingLock = Any()

    private val uiStateInternal = MutableStateFlow(
        MediaPickerUiState(
            folderName = initialFolderPath?.let { File(it).prettyName },
            folderPath = initialFolderPath,
            preferences = preferencesRepository.applicationPreferences.value,
            mode = preferencesRepository.applicationPreferences.value.lastMediaMode,
            selectedCloudServerIds = initialCloudServerIds
                ?: preferencesRepository.applicationPreferences.value.lastSelectedCloudServerIds.ifEmpty {
                    initialCloudServerId?.let(::setOf) ?: emptySet()
                },
            selectedCloudServerId = initialCloudServerId,
            cloudPath = initialCloudPath,
        ),
    )
    val uiState = uiStateInternal.asStateFlow()

    init {
        observeLocalMedia(initialFolderPath)

        viewModelScope.launch {
            preferencesRepository.applicationPreferences.collect { preferences ->
                val previousPreferences = uiStateInternal.value.preferences
                uiStateInternal.update { currentState ->
                    currentState.copy(
                        preferences = preferences,
                    )
                }
                if (
                    uiStateInternal.value.mode == MediaMode.CLOUD &&
                    previousPreferences.hasDifferentCloudDisplayShape(preferences)
                ) {
                    loadCloudDirectory()
                }
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
                    val isVirtualCloudRoot = selectedId == null && normalizePath(state.cloudPath) == "/"
                    val nextServerId = when {
                        isVirtualCloudRoot -> null
                        selectedServerStillAvailable -> selectedId
                        inferredServerId != null -> inferredServerId
                        else -> null
                    }
                    val nextPath = when {
                        isVirtualCloudRoot -> "/"
                        selectedIds.size > 1 && normalizePath(state.cloudPath) == "/" -> "/"
                        selectedIds.size > 1 -> state.cloudPath
                        selectedServerStillAvailable -> state.cloudPath
                        inferredServerId != null && normalizePath(state.cloudPath) != "/" -> state.cloudPath
                        else -> "/"
                    }.let(::normalizePath)
                    state.copy(
                        webDavServers = servers,
                        cloudServersLoaded = true,
                        selectedCloudServerIds = when {
                            selectedIds.isNotEmpty() -> selectedIds
                            servers.isNotEmpty() -> setOf(servers.first().id)
                            else -> emptySet()
                        },
                        selectedCloudServerId = nextServerId,
                        cloudPath = nextPath,
                    )
                }
                if (serversJustBecameAvailable && uiStateInternal.value.mode == MediaMode.CLOUD) {
                    loadCloudDirectory()
                }
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            mediumStateDao.getAll().collect { allStates ->
                val cloudStates = allStates
                    .filter { it.uriString.isHttpUri() }
                    .associateBy { it.uriString }
                cloudPlaybackStates.value = cloudStates
                uiStateInternal.update { state ->
                    state.copy(
                        recentlyPlayedCloudUri = cloudStates.values
                            .filter { it.lastPlayedTime != null }
                            .maxByOrNull { it.lastPlayedTime ?: Long.MIN_VALUE }
                            ?.uriString,
                    )
                }
            }
        }

        viewModelScope.launch(Dispatchers.Default) {
            combine(rawCloudFolder, cloudPlaybackStates) { raw, states -> raw to states }
                .collect { (raw, states) ->
                    if (raw == null) return@collect
                    val filled = fillPositionsFromMap(raw, states)
                    uiStateInternal.update { it.copy(cloudDataState = DataState.Success(filled)) }
                }
        }
    }

    fun onEvent(event: MediaPickerUiEvent) {
        when (event) {
            is MediaPickerUiEvent.DeleteFolders -> deleteFolders(event.folders)
            is MediaPickerUiEvent.DeleteVideos -> deleteVideos(event.videos)
            is MediaPickerUiEvent.ShareVideos -> shareVideos(event.videos)
            is MediaPickerUiEvent.Refresh -> refresh()
            is MediaPickerUiEvent.RenameVideo -> renameVideo(event.uri, event.to)
            is MediaPickerUiEvent.AddToSync -> addToMediaInfoSynchronizer(event.uri)
            is MediaPickerUiEvent.UpdateMenu -> updateMenu(event.preferences)
            MediaPickerUiEvent.ToggleMode -> toggleMode()
            is MediaPickerUiEvent.OpenLocalFolder -> openLocalFolder(event.folderPath)
            is MediaPickerUiEvent.SelectCloudServer -> selectCloudServer(event.serverId)
            is MediaPickerUiEvent.ToggleCloudServerSelection -> toggleCloudServerSelection(event.serverId)
            is MediaPickerUiEvent.OpenCloudFolder -> openCloudFolder(event.path)
            is MediaPickerUiEvent.PreloadCloudPath -> preloadCloudPath(event.path)
            MediaPickerUiEvent.NavigateCloudUp -> navigateCloudUp()
            MediaPickerUiEvent.RefreshCloud -> refreshCloud()
        }
    }

    private fun openLocalFolder(folderPath: String?) {
        val targetFolderPath = folderPath?.ifBlank { null }
        if (uiStateInternal.value.folderPath == targetFolderPath) return
        observeLocalMedia(targetFolderPath)
    }

    private fun observeLocalMedia(folderPath: String?) {
        localMediaJob?.cancel()
        uiStateInternal.update {
            it.copy(
                folderName = folderPath?.let { path -> File(path).prettyName },
                folderPath = folderPath,
                mediaDataState = DataState.Loading,
            )
        }
        localMediaJob = viewModelScope.launch {
            getSortedMediaUseCase.invoke(folderPath).collect { folder ->
                uiStateInternal.update { currentState ->
                    currentState.copy(
                        mediaDataState = DataState.Success(folder),
                    )
                }
            }
        }
    }

    private fun deleteFolders(folders: List<Folder>) {
        viewModelScope.launch {
            val uris = folders.flatMap { folder ->
                folder.allMediaList.map { video ->
                    video.uriString.toUri()
                }
            }
            mediaService.deleteMedia(uris)
        }
    }

    private fun deleteVideos(uris: List<String>) {
        viewModelScope.launch {
            mediaService.deleteMedia(uris.map { it.toUri() })
        }
    }

    private fun shareVideos(uris: List<String>) {
        viewModelScope.launch {
            mediaService.shareMedia(uris.map { it.toUri() })
        }
    }

    private fun addToMediaInfoSynchronizer(uri: Uri) {
        viewModelScope.launch {
            mediaInfoSynchronizer.sync(uri)
        }
    }

    private fun renameVideo(uri: Uri, to: String) {
        viewModelScope.launch {
            mediaService.renameMedia(uri, to)
        }
    }

    private fun refresh() {
        viewModelScope.launch {
            uiStateInternal.update { it.copy(refreshing = true) }
            mediaSynchronizer.refresh()
            uiStateInternal.update { it.copy(refreshing = false) }
        }
    }

    private fun updateMenu(preferences: ApplicationPreferences) {
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences { preferences }
        }
    }

    private fun toggleMode() {
        val nextMode = if (uiStateInternal.value.mode == MediaMode.LOCAL) MediaMode.CLOUD else MediaMode.LOCAL
        uiStateInternal.update { state -> state.copy(mode = nextMode, selectedCloudServerId = null, cloudPath = "/") }

        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences { it.copy(lastMediaMode = nextMode) }
        }
        if (uiStateInternal.value.mode == MediaMode.CLOUD) {
            loadCloudDirectory()
        }
    }

    private fun selectCloudServer(serverId: Int) {
        val server = uiStateInternal.value.webDavServers.firstOrNull { it.id == serverId } ?: return
        val nextServerIds = setOf(serverId)
        uiStateInternal.update {
            it.copy(
                selectedCloudServerId = null,
                selectedCloudServerIds = nextServerIds,
                cloudPath = "/",
            )
        }
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences { it.copy(lastSelectedCloudServerIds = nextServerIds) }
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
        val nextActiveServerId: Int? = null
        val nextPath = "/"
        uiStateInternal.update {
            it.copy(
                selectedCloudServerIds = next,
                selectedCloudServerId = nextActiveServerId,
                cloudPath = nextPath,
            )
        }
        modeStateHandle[CLOUD_SERVER_IDS_KEY] = ArrayList(next)
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences { it.copy(lastSelectedCloudServerIds = next) }
        }
        loadCloudDirectory()
    }

    private fun openCloudFolder(path: String) {
        val target = decodeCloudFolderPath(path)
        if (target != null) {
            uiStateInternal.update {
                it.copy(
                    selectedCloudServerId = target.first,
                    selectedCloudServerIds = setOf(target.first),
                    cloudPath = normalizePath(target.second),
                )
            }
        } else {
            uiStateInternal.update { it.copy(cloudPath = normalizePath(path)) }
        }
        loadCloudDirectory()
    }

    private fun navigateCloudUp() {
        val state = uiStateInternal.value
        val server = state.webDavServers.firstOrNull { it.id == state.selectedCloudServerId } ?: return
        val root = normalizePath(server.basePath)
        val current = normalizePath(state.cloudPath)
        if (current == "/") return
        val parent = if (current == root) "/" else current.substringBeforeLast('/', root).ifBlank { root }
        uiStateInternal.update {
            it.copy(
                selectedCloudServerId = if (parent == "/") null else state.selectedCloudServerId,
                cloudPath = normalizePath(parent),
            )
        }
        loadCloudDirectory()
    }

    private fun refreshCloud() {
        loadCloudDirectory(refreshing = true)
    }

    private suspend fun listCloudDirectory(
        server: WebDavServer,
        path: String,
        perPage: Int = 2000,
        refreshing: Boolean = false,
    ): Pair<List<com.sakurafubuki.yume.core.model.WebDavMediaItem>, Int?> {
        val apiPath = server.toApiPath(path)
        return try {
            val result = openListApi.listDirectory(server, apiPath, page = 1, perPage = perPage, refresh = refreshing)
            result.fold(
                onSuccess = { data ->
                    val items = data.content.orEmpty().map { it.toWebDavMediaItem(server, apiPath) }
                    items to data.total
                },
                onFailure = { throwable ->
                    Logger.w(CLOUD_LOG_TAG, "OpenList API failed server=${server.id}, falling back to WebDAV: ${throwable.message}")
                    throw throwable
                },
            )
        } catch (_: Exception) {
            val items = webDavRepository.listDirectory(server, path)
            items to null
        }
    }

    private fun loadCloudDirectory(refreshing: Boolean = false) {
        val state = uiStateInternal.value
        val selectedServerIds = state.selectedCloudServerIds.ifEmpty {
            state.selectedCloudServerId?.let(::setOf) ?: emptySet()
        }
        if (selectedServerIds.isNotEmpty() && normalizePath(state.cloudPath) == "/" && state.selectedCloudServerId == null) {
            loadMultiCloudRootDirectory(refreshing = refreshing, selectedServerIds = selectedServerIds)
            return
        }
        val server = state.webDavServers.firstOrNull { it.id == state.selectedCloudServerId }
            ?: state.webDavServers.firstOrNull()
            ?: return
        val path = normalizePath(state.cloudPath.ifBlank { server.basePath })
        val preferences = state.preferences
        val loadKey = "${server.id}:$path:${preferences.mediaViewMode}:${preferences.sortBy}:${preferences.sortOrder}"

        if (!refreshing && cloudLoadJob?.isActive == true && activeCloudLoadKey == loadKey) {
            return
        }

        cloudLoadJob?.cancel()
        cloudAuxWorkJob?.cancel()
        cloudCachedRefreshJob?.cancel()
        val cloudAuxParent = SupervisorJob()
        cloudAuxWorkJob = cloudAuxParent
        activeCloudLoadKey = loadKey
        val requestToken = ++cloudLoadRequestToken
        cloudLoadJob = viewModelScope.launch {
            val cachedItems = if (!refreshing) getCachedCloudDirectoryItems(server.id, path) else null

            if (cachedItems != null) {
                val metadataByHref = buildCloudMetadataMap(
                    server = server,
                    items = cachedItems,
                    recurse = preferences.mediaViewMode == MediaViewMode.FOLDER_TREE,
                )
                val subDirPaths = cachedItems.cloudDirectoryItems().map {
                    normalizePath(resolveRelativePath(server, it.href))
                }
                val allPaths = subDirPaths + normalizePath(path)
                val folderMetadataMap = cloudVideoMetadataRepository.getFolderMetadata(server.id, allPaths)
                val rootCachedMetadata = folderMetadataMap[normalizePath(path)]
                val immediateFolder = withContext(Dispatchers.Default) {
                    mapCloudFolder(
                        server = server,
                        path = path,
                        preferences = preferences,
                        items = cachedItems,
                        metadataByHref = metadataByHref,
                        folderMetadataMap = folderMetadataMap,
                        rootCachedMetadata = rootCachedMetadata,
                        hideUnknownFolders = preferences.mediaViewMode == MediaViewMode.FOLDER_TREE,
                    )
                }
                val immediateDisplayFolder = resolveCloudFolderForViewMode(
                    folder = immediateFolder,
                    preferences = preferences,
                )
                if (shouldApplyCloudDisplayFolder(preferences, immediateFolder, immediateDisplayFolder)) {
                    rawCloudFolder.value = immediateDisplayFolder
                }
                uiStateInternal.update {
                    it.copy(
                        selectedCloudServerId = server.id,
                        cloudPath = path,
                        cloudRefreshing = refreshing,
                    )
                }

                refreshCloudFolderWithMetadataAsync(
                    cloudAuxParent = cloudAuxParent,
                    requestToken = requestToken,
                    server = server,
                    path = path,
                    preferences = preferences,
                    items = cachedItems,
                )
            } else {
                uiStateInternal.update {
                    val keepExistingData = !refreshing && it.cloudDataState is DataState.Success
                    it.copy(
                        selectedCloudServerId = server.id,
                        cloudPath = path,
                        cloudRefreshing = refreshing,
                        cloudDataState = when {
                            refreshing -> it.cloudDataState
                            keepExistingData -> it.cloudDataState
                            else -> DataState.Loading
                        },
                    )
                }
            }

            runCatching { listCloudDirectory(server, path, refreshing = refreshing).first }
                .onSuccess { items ->
                    webDavVideoDirectoryCache.put(server.id, path, items)
                    cloudDirectoryItemCache.put(server.id, path, items)
                    if (requestToken != cloudLoadRequestToken) return@onSuccess
                    val metadataByHref = buildCloudMetadataMap(
                        server = server,
                        items = items,
                        recurse = preferences.mediaViewMode == MediaViewMode.FOLDER_TREE,
                    )
                    val subDirPaths = items.cloudDirectoryItems().map {
                        normalizePath(resolveRelativePath(server, it.href))
                    }
                    val folderMetadataMap = cloudVideoMetadataRepository.getFolderMetadata(server.id, subDirPaths)
                    val stableVisibleFolderPaths = stableVisibleCloudFolderPaths(
                        enabled = refreshing && preferences.mediaViewMode == MediaViewMode.FOLDER_TREE,
                        path = path,
                    )
                    val rootFolder = withContext(Dispatchers.Default) {
                        mapCloudFolder(
                            server = server,
                            path = path,
                            preferences = preferences,
                            items = items,
                            metadataByHref = metadataByHref,
                            folderMetadataMap = folderMetadataMap,
                            stableVisibleFolderPaths = stableVisibleFolderPaths,
                            hideUnknownFolders = preferences.mediaViewMode == MediaViewMode.FOLDER_TREE,
                        )
                    }
                    val resolvedDisplayFolder = buildIndexedCloudFolder(
                        server = server,
                        path = path,
                        preferences = preferences,
                        refreshing = refreshing,
                    ) ?: resolveCloudFolderForViewMode(
                        folder = rootFolder,
                        preferences = preferences,
                    )
                    val displayFolder = if (shouldApplyCloudDisplayFolder(preferences, rootFolder, resolvedDisplayFolder)) {
                        resolvedDisplayFolder
                    } else {
                        rootFolder
                    }
                    saveFolderMetadataPreserving(
                        serverId = server.id,
                        folderPath = normalizePath(path),
                        totalDurationMs = displayFolder.mediaDuration,
                        totalSize = displayFolder.mediaSize,
                        mediaCount = displayFolder.mediaCount,
                        folderCount = displayFolder.folderCount,
                        videoCount = items.cloudDisplayVideoFiles().size.takeIf { it > 0 } ?: -1,
                        preserveExistingZeros = shouldPreserveZeroFolderSummary(
                            server = server,
                            items = items,
                            folderMetadataMap = folderMetadataMap,
                            displayFolder = displayFolder,
                        ),
                    )
                    rawCloudFolder.value = displayFolder
                    uiStateInternal.update {
                        it.copy(cloudRefreshing = false)
                    }

                    cloudAuxWorkJob?.cancel()
                    val networkAuxParent = SupervisorJob()
                    cloudAuxWorkJob = networkAuxParent
                    refreshDirectChildFolderSummariesAsync(
                        cloudAuxParent = networkAuxParent,
                        requestToken = requestToken,
                        server = server,
                        path = path,
                        preferences = preferences,
                        items = items,
                    )
                    refreshCloudVideoMetadataAsync(
                        cloudAuxParent = networkAuxParent,
                        requestToken = requestToken,
                        server = server,
                        path = path,
                        preferences = preferences,
                        items = items,
                    )
                }
                .onFailure { throwable ->
                    if (throwable is CancellationException || requestToken != cloudLoadRequestToken) return@onFailure
                    Logger.e(
                        CLOUD_LOG_TAG,
                        "loadCloudDirectory failed token=$requestToken server=${server.id} path=$path error=${throwable.message}",
                        throwable,
                    )
                    uiStateInternal.update {
                        if (it.cloudDataState is DataState.Success) {
                            it.copy(cloudRefreshing = false)
                        } else {
                            it.copy(
                                cloudDataState = DataState.Error(throwable),
                                cloudRefreshing = false,
                            )
                        }
                    }
                }
        }
    }

    private fun loadMultiCloudRootDirectory(
        refreshing: Boolean,
        selectedServerIds: Set<Int>,
    ) {
        val state = uiStateInternal.value
        val selectedServers = state.webDavServers.filter { it.id in selectedServerIds }
        if (selectedServers.isEmpty()) return

        cloudLoadJob?.cancel()
        cloudAuxWorkJob?.cancel()
        cloudCachedRefreshJob?.cancel()
        val requestToken = ++cloudLoadRequestToken

        cloudLoadJob = viewModelScope.launch {
            val preferences = state.preferences
            val folder = if (preferences.mediaViewMode == MediaViewMode.FOLDER_TREE) {
                buildCloudStorageRoot(selectedServers, preferences)
            } else {
                buildIndexedMultiCloudRoot(
                    selectedServers = selectedServers,
                    preferences = preferences,
                    refreshing = refreshing,
                ) ?: buildCloudStorageRoot(selectedServers, preferences)
            }
            rawCloudFolder.value = folder
            uiStateInternal.update {
                it.copy(
                    selectedCloudServerId = null,
                    cloudPath = "/",
                    cloudRefreshing = refreshing,
                )
            }
            if (preferences.mediaViewMode == MediaViewMode.FOLDER_TREE) {
                refreshCloudStorageRootSummaries(
                    requestToken = requestToken,
                    selectedServers = selectedServers,
                    preferences = preferences,
                    refreshing = refreshing,
                )
            } else {
                uiStateInternal.update { it.copy(cloudRefreshing = false) }
            }
        }
    }

    private suspend fun buildCloudStorageRoot(
        selectedServers: List<WebDavServer>,
        preferences: ApplicationPreferences,
    ): Folder {
        val sort = Sort(by = preferences.sortBy, order = preferences.sortOrder)
        val storageFolders = selectedServers.map { server ->
            val rootPath = normalizePath(server.basePath)
            val metadata = cloudVideoMetadataRepository.getFolderMetadata(server.id, listOf(rootPath))[rootPath]
            val cachedItems = getCachedCloudDirectoryItems(server.id, rootPath)
            val cachedVideos = cachedItems?.cloudDisplayVideoFiles()?.size ?: 0
            val cachedFolders = cachedItems?.cloudDirectoryItems()?.size ?: 0
            Folder(
                name = server.name,
                path = encodeCloudFolderPath(server.id, rootPath),
                dateModified = server.createdAt,
                parentPath = "/",
                mediaCount = metadata?.mediaCount ?: cachedVideos,
                folderCount = metadata?.folderCount ?: cachedFolders,
                cachedMediaSize = metadata?.totalSize,
                cachedMediaDuration = metadata?.totalDurationMs,
            )
        }
        return Folder(
            name = "Yume",
            path = "/",
            dateModified = storageFolders.maxOfOrNull { it.dateModified } ?: 0L,
            folderList = storageFolders.sortedWith(sort.folderComparator()),
            folderCount = storageFolders.size,
        )
    }

    private suspend fun buildIndexedMultiCloudRoot(
        selectedServers: List<WebDavServer>,
        preferences: ApplicationPreferences,
        refreshing: Boolean,
    ): Folder? = coroutineScope {
        val sort = Sort(by = preferences.sortBy, order = preferences.sortOrder)
        val indexedRoots = selectedServers
            .map { server ->
                async(Dispatchers.IO) {
                    buildIndexedCloudFolder(
                        server = server,
                        path = normalizePath(server.basePath),
                        preferences = preferences,
                        refreshing = refreshing,
                    )
                }
            }
            .awaitAll()
            .filterNotNull()
        if (indexedRoots.isEmpty()) return@coroutineScope null

        when (preferences.mediaViewMode) {
            MediaViewMode.FOLDER_TREE -> buildCloudStorageRoot(selectedServers, preferences)
            MediaViewMode.FOLDERS -> {
                val folders = indexedRoots
                    .flatMap { it.folderList }
                    .sortedWith(sort.folderComparator())
                if (folders.isEmpty()) return@coroutineScope null
                Folder.rootFolder.copy(
                    name = "Yume",
                    folderList = folders,
                    mediaList = emptyList(),
                    folderCount = folders.size,
                    mediaCount = 0,
                    cachedMediaSize = folders.sumOf { it.mediaSize },
                    cachedMediaDuration = folders.sumOf { it.mediaDuration },
                )
            }
            MediaViewMode.IMAGE,
            MediaViewMode.VIDEOS,
            -> {
                val videos = indexedRoots
                    .flatMap { it.mediaList }
                    .sortedWith(sort.videoComparator())
                if (videos.isEmpty()) return@coroutineScope null
                Folder.rootFolder.copy(
                    name = "Yume",
                    folderList = emptyList(),
                    mediaList = videos,
                    folderCount = 0,
                    mediaCount = videos.size,
                    cachedMediaSize = videos.sumOf { it.size },
                    cachedMediaDuration = videos.sumOf { it.duration },
                )
            }
        }
    }

    private suspend fun buildIndexedCloudFolder(
        server: WebDavServer,
        path: String,
        preferences: ApplicationPreferences,
        refreshing: Boolean,
    ): Folder? {
        if (preferences.mediaViewMode == MediaViewMode.FOLDER_TREE) return null
        val indexedVideos = searchIndexedCloudVideos(server = server, path = path) ?: return null
        if (indexedVideos.isEmpty()) return null
        val sort = Sort(by = preferences.sortBy, order = preferences.sortOrder)
        val indexedByParent = indexedVideos.groupBy { it.parentPath }
        if (preferences.mediaViewMode == MediaViewMode.FOLDERS) {
            return buildIndexedCloudFoldersOnly(
                server = server,
                path = path,
                indexedByParent = indexedByParent,
                sort = sort,
                refreshing = refreshing,
            )
        }

        val parentListSemaphore = Semaphore(CLOUD_INDEX_PARENT_LIST_MAX_CONCURRENCY)
        val itemsByParent = coroutineScope {
            indexedByParent.map { (parentPath, videos) ->
                async(Dispatchers.IO) {
                    parentListSemaphore.withPermit {
                        parentPath to loadIndexedParentVideoItems(
                            server = server,
                            parentPath = parentPath,
                            indexedVideos = videos,
                            refreshing = refreshing,
                        )
                    }
                }
            }.awaitAll().toMap()
        }
        val allItems = itemsByParent.values.flatten()
        val metadataByHref = if (allItems.isEmpty()) {
            emptyMap()
        } else {
            cloudVideoMetadataRepository.getMetadata(
                serverId = server.id,
                hrefs = allItems.map { it.href }.distinct(),
            )
        }
        val videosByParent = itemsByParent.mapValues { (parentPath, items) ->
            items.map { item ->
                mapCloudVideo(
                    server = server,
                    currentPath = parentPath,
                    item = item,
                    metadataByHref = metadataByHref,
                )
            }.sortedWith(sort.videoComparator())
        }

        return when (preferences.mediaViewMode) {
            MediaViewMode.FOLDER_TREE -> null
            MediaViewMode.FOLDERS -> null
            MediaViewMode.IMAGE,
            MediaViewMode.VIDEOS,
            -> {
                val videos = videosByParent.values.flatten().sortedWith(sort.videoComparator())
                if (videos.isEmpty()) return null
                Folder(
                    name = cloudFolderDisplayName(server, path),
                    path = encodeCloudFolderPath(server.id, path),
                    dateModified = videos.maxOfOrNull { it.dateModified } ?: 0L,
                    folderList = emptyList(),
                    mediaList = videos,
                    mediaCount = videos.size,
                    folderCount = 0,
                    cachedMediaSize = videos.sumOf { it.size },
                    cachedMediaDuration = videos.sumOf { it.duration },
                )
            }
        }
    }

    private suspend fun buildIndexedCloudFoldersOnly(
        server: WebDavServer,
        path: String,
        indexedByParent: Map<String, List<CloudIndexedVideo>>,
        sort: Sort,
        refreshing: Boolean,
    ): Folder? {
        val normalizedPath = normalizePath(path)
        val directIndexedVideos = indexedByParent[normalizedPath].orEmpty()
        val directVideos = if (directIndexedVideos.isEmpty()) {
            emptyList()
        } else {
            val directItems = loadIndexedParentVideoItems(
                server = server,
                parentPath = normalizedPath,
                indexedVideos = directIndexedVideos,
                refreshing = refreshing,
            )
            val metadataByHref = cloudVideoMetadataRepository.getMetadata(
                serverId = server.id,
                hrefs = directItems.map { it.href }.distinct(),
            )
            directItems.map { item ->
                mapCloudVideo(
                    server = server,
                    currentPath = normalizedPath,
                    item = item,
                    metadataByHref = metadataByHref,
                )
            }.sortedWith(sort.videoComparator())
        }

        val folders = indexedByParent
            .filterKeys { normalizePath(it) != normalizedPath }
            .mapNotNull { (parentPath, indexedVideos) ->
                if (indexedVideos.isEmpty()) return@mapNotNull null
                Folder(
                    name = cloudFolderDisplayName(server, parentPath),
                    path = encodeCloudFolderPath(server.id, parentPath),
                    dateModified = 0L,
                    parentPath = cloudFolderParentDisplayPath(server, parentPath),
                    mediaList = emptyList(),
                    folderList = emptyList(),
                    mediaCount = indexedVideos.size,
                    folderCount = 0,
                    cachedMediaSize = indexedVideos.sumOf { it.item.size },
                    cachedMediaDuration = 0L,
                )
            }
            .sortedWith(sort.folderComparator())

        if (folders.isEmpty() && directVideos.isEmpty()) return null
        return Folder(
            name = cloudFolderDisplayName(server, normalizedPath),
            path = normalizePath(path),
            dateModified = directVideos.maxOfOrNull { it.dateModified } ?: 0L,
            folderList = folders,
            mediaList = directVideos,
            mediaCount = directVideos.size,
            folderCount = folders.size,
            cachedMediaSize = folders.sumOf { it.mediaSize } + directVideos.sumOf { it.size },
            cachedMediaDuration = directVideos.sumOf { it.duration },
        )
    }

    private suspend fun searchIndexedCloudVideos(
        server: WebDavServer,
        path: String,
    ): List<CloudIndexedVideo>? {
        val keyedResults = mutableListOf<CloudIndexedVideo>()
        val seen = mutableSetOf<String>()
        for (extension in CLOUD_INDEX_VIDEO_EXTENSIONS) {
            val extensionResults = searchIndexedCloudVideos(
                server = server,
                path = path,
                keywords = ".$extension",
            ) ?: return null
            extensionResults.forEach { indexed ->
                val key = "${normalizePath(indexed.parentPath)}/${indexed.item.name}"
                if (seen.add(key)) keyedResults += indexed
            }
        }
        return keyedResults
    }

    private suspend fun searchIndexedCloudVideos(
        server: WebDavServer,
        path: String,
        keywords: String,
    ): List<CloudIndexedVideo>? {
        val apiParentPath = normalizePath(server.toApiPath(path))
        val files = mutableListOf<CloudIndexedVideo>()
        var page = 1
        var total: Int? = null
        var seenRawItems = 0
        while (total == null || seenRawItems < total) {
            val data = openListApi.search(
                server = server,
                parent = apiParentPath,
                keywords = keywords,
                scope = 2,
                page = page,
                perPage = CLOUD_INDEX_SEARCH_PAGE_SIZE,
            ).getOrElse { throwable ->
                Logger.d(
                    CLOUD_SEARCH_LOG_TAG,
                    "search unavailable server=${server.id} path=$path keywords=$keywords: ${throwable.message}",
                )
                return null
            }
            total = data.total
            if (data.total > CLOUD_INDEX_SEARCH_MAX_RESULTS) {
                Logger.w(
                    CLOUD_SEARCH_LOG_TAG,
                    "search result too large server=${server.id} path=$path keywords=$keywords total=${data.total}",
                )
                return null
            }
            val content = data.content.orEmpty()
            if (content.isEmpty()) break
            seenRawItems += content.size
            files += content
                .asSequence()
                .filter { it.isIndexedCloudVideoFile() }
                .map { item ->
                    CloudIndexedVideo(
                        item = item,
                        parentPath = server.fromApiPath(item.parent),
                    )
                }
                .filter { indexed ->
                    val normalizedParent = normalizePath(indexed.parentPath)
                    val normalizedRoot = normalizePath(path)
                    normalizedRoot == "/" ||
                        normalizedParent == normalizedRoot ||
                        normalizedParent.startsWith("$normalizedRoot/")
                }
            page += 1
        }
        return files
    }

    private suspend fun loadIndexedParentVideoItems(
        server: WebDavServer,
        parentPath: String,
        indexedVideos: List<CloudIndexedVideo>,
        refreshing: Boolean,
    ): List<WebDavMediaItem> {
        val indexedNames = indexedVideos.map { it.item.name }.toSet()
        val directoryItems = if (!refreshing) {
            getCachedCloudDirectoryItems(server.id, parentPath)
        } else {
            null
        } ?: runCatching {
            listCloudDirectory(
                server = server,
                path = parentPath,
                perPage = CLOUD_INDEX_PARENT_LIST_PAGE_SIZE,
                refreshing = refreshing,
            ).first.also { items ->
                webDavVideoDirectoryCache.put(server.id, parentPath, items)
                cloudDirectoryItemCache.put(server.id, parentPath, items)
            }
        }.getOrNull()

        val listedVideos = directoryItems
            ?.cloudDisplayVideoFiles()
            ?.filter { it.name in indexedNames }
            .orEmpty()
        val listedNames = listedVideos.map { it.name }.toSet()
        if (listedNames.size == indexedNames.size) return listedVideos

        val missingSyntheticVideos = indexedVideos
            .filterNot { it.item.name in listedNames }
            .map { indexed ->
                indexed.item.toSyntheticWebDavMediaItem(
                    server = server,
                    parentPath = parentPath,
                )
            }
        return listedVideos + missingSyntheticVideos
    }

    private suspend fun refreshCloudStorageRootSummaries(
        requestToken: Long,
        selectedServers: List<WebDavServer>,
        preferences: ApplicationPreferences,
        refreshing: Boolean,
    ) {
        cloudFolderSummaryScanner.refreshStorageRootSummaries(
            selectedServers = selectedServers,
            refreshing = refreshing,
            isCurrent = { requestToken == cloudLoadRequestToken },
        )
        if (requestToken != cloudLoadRequestToken) return
        rawCloudFolder.value = buildCloudStorageRoot(selectedServers, preferences)
        uiStateInternal.update { it.copy(cloudRefreshing = false) }
    }

    private fun refreshDirectChildFolderSummariesAsync(
        cloudAuxParent: Job,
        requestToken: Long,
        server: WebDavServer,
        path: String,
        preferences: ApplicationPreferences,
        items: List<com.sakurafubuki.yume.core.model.WebDavMediaItem>,
    ) {
        viewModelScope.launch(cloudAuxParent + Dispatchers.IO) {
            cloudFolderSummaryScanner.refreshDirectChildSummaries(
                server = server,
                parentPath = path,
                parentItems = items,
                isCurrent = { requestToken == cloudLoadRequestToken },
            )
            if (requestToken != cloudLoadRequestToken) return@launch
            cacheCloudTreeVideoMetadata(
                server = server,
                path = path,
                preferences = preferences,
                items = getCachedCloudDirectoryItems(server.id, path) ?: items,
            )
        }
    }

    private fun stableVisibleCloudFolderPaths(
        enabled: Boolean,
        path: String,
    ): Set<String>? {
        if (!enabled) return null
        val state = uiStateInternal.value
        if (normalizePath(state.cloudPath) != normalizePath(path)) return null
        val folder = (state.cloudDataState as? DataState.Success)?.value ?: return null
        return folder.folderList.mapTo(mutableSetOf()) { normalizePath(it.path) }
    }

    private suspend fun saveFolderMetadataPreserving(
        serverId: Int,
        folderPath: String,
        totalDurationMs: Long,
        totalSize: Long,
        mediaCount: Int,
        folderCount: Int,
        coverImageUri: String? = null,
        videoCount: Int = 0,
        imageCount: Int = 0,
        preserveExistingZeros: Boolean = true,
    ) {
        val normalizedPath = normalizePath(folderPath)
        val existing = cloudVideoMetadataRepository.getFolderMetadata(serverId, listOf(normalizedPath))[normalizedPath]
        val savedTotalDurationMs = if (!preserveExistingZeros || totalDurationMs > 0L) totalDurationMs else existing?.totalDurationMs ?: 0L
        val savedTotalSize = if (!preserveExistingZeros || totalSize > 0L) totalSize else existing?.totalSize ?: 0L
        val savedMediaCount = when {
            preserveExistingZeros && existing != null -> existing.mediaCount
            mediaCount > 0 -> mediaCount
            else -> 0
        }
        val savedFolderCount = when {
            preserveExistingZeros && existing != null -> existing.folderCount
            folderCount > 0 -> folderCount
            else -> 0
        }
        val savedVideoCount = when {
            preserveExistingZeros && existing != null -> existing.videoCount
            videoCount > 0 -> videoCount
            else -> videoCount
        }
        val savedImageCount = when {
            preserveExistingZeros && existing != null -> existing.imageCount
            imageCount > 0 -> imageCount
            else -> 0
        }
        if (existing != null && (existing.folderCount != folderCount || existing.folderCount != savedFolderCount)) {
            Logger.d(
                CLOUD_FLOW_LOG_TAG,
                "saveFolderMetadataPreserving path=$normalizedPath preserve=$preserveExistingZeros " +
                    "incoming(media=$mediaCount folder=$folderCount video=$videoCount size=$totalSize) " +
                    "existing(media=${existing.mediaCount} folder=${existing.folderCount} video=${existing.videoCount} size=${existing.totalSize}) " +
                    "saved(media=$savedMediaCount folder=$savedFolderCount video=$savedVideoCount size=$savedTotalSize)",
            )
        }
        cloudVideoMetadataRepository.saveFolderMetadata(
            serverId = serverId,
            folderPath = normalizedPath,
            totalDurationMs = savedTotalDurationMs,
            totalSize = savedTotalSize,
            mediaCount = savedMediaCount,
            folderCount = savedFolderCount,
            coverImageUri = coverImageUri ?: existing?.coverImageUri,
            videoCount = savedVideoCount,
            imageCount = savedImageCount,
        )
    }

    private fun preloadCloudPath(path: String) {
        val parent = cloudAuxWorkJob ?: SupervisorJob().also { cloudAuxWorkJob = it }
        preloadCloudPath(
            cloudAuxParent = parent,
            requestToken = cloudLoadRequestToken,
            path = path,
        )
    }

    private fun preloadCloudPath(
        cloudAuxParent: Job,
        requestToken: Long,
        path: String,
    ) {
        val state = uiStateInternal.value
        val server = state.webDavServers.firstOrNull { it.id == state.selectedCloudServerId }
            ?: state.webDavServers.firstOrNull()
            ?: return
        val normalizedPath = normalizePath(path)
        val preloadKey = "${server.id}:$normalizedPath"

        val cachedItems = webDavVideoDirectoryCache.get(server.id, normalizedPath)
        if (cachedItems != null) {
            return
        }

        synchronized(preloadingLock) {
            if (!preloadingCloudPaths.add(preloadKey)) return
        }

        viewModelScope.launch(cloudAuxParent + Dispatchers.IO) {
            if (requestToken != cloudLoadRequestToken) return@launch
            runCatching { listCloudDirectory(server, normalizedPath).first }
                .onSuccess { items ->
                    if (requestToken != cloudLoadRequestToken) return@onSuccess
                    webDavVideoDirectoryCache.put(server.id, normalizedPath, items)
                    cloudDirectoryItemCache.put(server.id, normalizedPath, items)
                }
            synchronized(preloadingLock) {
                preloadingCloudPaths.remove(preloadKey)
            }
        }
    }

    private suspend fun getCachedCloudDirectoryItems(
        serverId: Int,
        path: String,
    ): List<com.sakurafubuki.yume.core.model.WebDavMediaItem>? {
        webDavVideoDirectoryCache.get(serverId, path)?.let { return it }
        val diskItems = cloudDirectoryItemCache.get(serverId, path)
        if (diskItems.isEmpty()) return null
        webDavVideoDirectoryCache.put(serverId, path, diskItems)
        return diskItems
    }

    private suspend fun resolveCloudFolderForViewMode(
        folder: Folder,
        preferences: ApplicationPreferences,
    ): Folder {
        val sort = Sort(by = preferences.sortBy, order = preferences.sortOrder)
        return when (preferences.mediaViewMode) {
            MediaViewMode.FOLDER_TREE -> {
                val folders = folder.folderList.sortedWith(sort.folderComparator())
                folder.copy(
                    folderList = folders,
                    mediaCount = folder.mediaList.size,
                    folderCount = folders.size,
                )
            }

            MediaViewMode.FOLDERS -> {
                val folders = folder.folderList
                    .flatMap { it.collectCloudVideoLeafFolders() }
                    .distinctBy { it.path }
                    .sortedWith(sort.folderComparator())
                if (folders.isEmpty() && folder.mediaList.isNotEmpty()) {
                    folder.copy(
                        folderList = emptyList(),
                        mediaCount = folder.mediaList.size,
                        folderCount = 0,
                        cachedMediaSize = folder.mediaList.sumOf { it.size },
                        cachedMediaDuration = folder.mediaList.sumOf { it.duration },
                    )
                } else {
                    folder.copy(
                        mediaList = emptyList(),
                        folderList = folders,
                        mediaCount = 0,
                        folderCount = folders.size,
                        cachedMediaSize = folders.sumOf { it.mediaSize },
                        cachedMediaDuration = folders.sumOf { it.mediaDuration },
                    )
                }
            }

            MediaViewMode.IMAGE,
            MediaViewMode.VIDEOS,
            -> {
                val videos = folder.allMediaList.sortedWith(sort.videoComparator())
                folder.copy(
                    mediaList = videos,
                    folderList = emptyList(),
                    mediaCount = videos.size,
                    folderCount = 0,
                    cachedMediaSize = videos.sumOf { it.size },
                    cachedMediaDuration = videos.sumOf { it.duration },
                )
            }
        }
    }

    private fun Folder.collectCloudVideoLeafFolders(): List<Folder> {
        val current = if (mediaList.isNotEmpty()) listOf(copy(folderList = emptyList())) else emptyList()
        return current + folderList.flatMap { it.collectCloudVideoLeafFolders() }
    }

    private fun mapCloudFolder(
        server: WebDavServer,
        path: String,
        preferences: ApplicationPreferences,
        items: List<com.sakurafubuki.yume.core.model.WebDavMediaItem>,
        metadataByHref: Map<String, CloudVideoMetadata>,
        folderMetadataMap: Map<String, CloudFolderMetadata> = emptyMap(),
        rootCachedMetadata: CloudFolderMetadata? = null,
        stableVisibleFolderPaths: Set<String>? = null,
        hideUnknownFolders: Boolean = false,
    ): Folder {
        val sort = Sort(by = preferences.sortBy, order = preferences.sortOrder)
        val absoluteCurrentPath = toWebDavAbsolutePath(server, path)
        val folders = items.cloudDirectoryItems()
            .mapNotNull { item ->
                val folderPath = normalizePath(resolveRelativePath(server, item.href))
                val cached = folderMetadataMap[folderPath]
                if (isKnownEmptyCloudFolder(cached)) {
                    return@mapNotNull null
                }
                val cachedChildItems = webDavVideoDirectoryCache.get(server.id, folderPath)
                if (hideUnknownFolders && cachedChildItems == null && !cached.hasKnownCloudVideoContent()) {
                    return@mapNotNull null
                }
                if (
                    stableVisibleFolderPaths != null &&
                    folderPath !in stableVisibleFolderPaths &&
                    cached == null &&
                    cachedChildItems == null
                ) {
                    return@mapNotNull null
                }
                mapCachedCloudFolder(
                    server = server,
                    folderName = item.name,
                    folderPath = folderPath,
                    lastModified = item.lastModified?.time ?: 0L,
                    sort = sort,
                    metadataByHref = metadataByHref,
                    visitedPaths = setOf(normalizePath(path)),
                    parentDisplayPath = absoluteCurrentPath,
                    folderMetadataMap = folderMetadataMap,
                    hideUnknownFolders = hideUnknownFolders,
                )
            }
            .sortedWith(sort.folderComparator())

        val videos = items.cloudDisplayVideoFiles()
            .map { item ->
                mapCloudVideo(
                    server = server,
                    currentPath = path,
                    item = item,
                    metadataByHref = metadataByHref,
                )
            }
            .sortedWith(sort.videoComparator())

        val (displayFolders, displayVideos) = folders to videos
        val resolvedMediaSize = displayVideos.sumOf { it.size } + displayFolders.sumOf { it.mediaSize }
        val resolvedMediaDuration = displayVideos.sumOf { it.duration } + displayFolders.sumOf { it.mediaDuration }
        return Folder(
            name = cloudFolderDisplayName(server, path),
            path = normalizePath(path),
            dateModified = items.maxOfOrNull { it.lastModified?.time ?: 0L } ?: 0L,
            mediaList = displayVideos,
            folderList = displayFolders,
            mediaCount = displayVideos.size,
            folderCount = displayFolders.size,
            cachedMediaSize = rootCachedMetadata?.totalSize?.takeIf { it > resolvedMediaSize } ?: resolvedMediaSize,
            cachedMediaDuration = rootCachedMetadata?.totalDurationMs?.takeIf { it > resolvedMediaDuration } ?: resolvedMediaDuration,
        )
    }

    private fun mapCachedCloudFolder(
        server: WebDavServer,
        folderName: String,
        folderPath: String,
        lastModified: Long,
        sort: Sort,
        metadataByHref: Map<String, CloudVideoMetadata>,
        visitedPaths: Set<String>,
        parentDisplayPath: String,
        folderMetadataMap: Map<String, CloudFolderMetadata> = emptyMap(),
        hideUnknownFolders: Boolean = false,
    ): Folder? {
        val normalizedPath = normalizePath(folderPath)
        val cached = folderMetadataMap[normalizedPath]
        if (normalizedPath in visitedPaths) {
            return Folder(
                name = folderName,
                path = normalizedPath,
                dateModified = lastModified,
                parentPath = parentDisplayPath,
                mediaCount = cached?.videoCount?.takeIf { it > 0 } ?: 0,
                cachedMediaSize = cached?.totalSize,
                cachedMediaDuration = cached?.totalDurationMs,
                folderCount = cached?.folderCount ?: 0,
            )
        }

        val cachedChildItems = webDavVideoDirectoryCache.get(server.id, normalizedPath)
        val nextVisited = visitedPaths + normalizedPath

        if (cachedChildItems == null) {
            if (hideUnknownFolders && !cached.hasKnownCloudVideoContent()) return null
            if (cached != null) {
                return Folder(
                    name = folderName,
                    path = normalizedPath,
                    dateModified = lastModified,
                    parentPath = parentDisplayPath,
                    mediaCount = if (cached.videoCount > 0) cached.videoCount else 0,
                    cachedMediaSize = cached.totalSize,
                    cachedMediaDuration = cached.totalDurationMs,
                    folderCount = cached.folderCount,
                )
            }
            return Folder(
                name = folderName,
                path = normalizedPath,
                dateModified = lastModified,
                parentPath = parentDisplayPath,
            )
        }

        val childItems = cachedChildItems
        if (childItems.isEmpty()) return null

        val childFolders = childItems.cloudDirectoryItems()
            .mapNotNull { child ->
                val childPath = normalizePath(resolveRelativePath(server, child.href))
                val childCached = folderMetadataMap[childPath]
                if (isKnownEmptyCloudFolder(childCached)) {
                    return@mapNotNull null
                }
                mapCachedCloudFolder(
                    server = server,
                    folderName = child.name,
                    folderPath = childPath,
                    lastModified = child.lastModified?.time ?: 0L,
                    sort = sort,
                    metadataByHref = metadataByHref,
                    visitedPaths = nextVisited,
                    parentDisplayPath = toWebDavAbsolutePath(server, normalizedPath),
                    folderMetadataMap = folderMetadataMap,
                    hideUnknownFolders = hideUnknownFolders,
                )
            }
            .sortedWith(sort.folderComparator())

        val childVideos = childItems.cloudDisplayVideoFiles()
            .map { child ->
                mapCloudVideo(
                    server = server,
                    currentPath = normalizedPath,
                    item = child,
                    metadataByHref = metadataByHref,
                )
            }
            .sortedWith(sort.videoComparator())

        val resolvedFolderCount = cached?.folderCount ?: childFolders.size
        val resolvedMediaSize = childVideos.sumOf { it.size } + childFolders.sumOf { it.mediaSize }
        val resolvedMediaDuration = childVideos.sumOf { it.duration } + childFolders.sumOf { it.mediaDuration }
        if (childVideos.isEmpty() && childFolders.isEmpty()) return null
        if (cached != null && cached.folderCount != childFolders.size) {
            Logger.d(
                CLOUD_FLOW_LOG_TAG,
                "mapCachedCloudFolder path=$normalizedPath uses cached folderCount=${cached.folderCount} " +
                    "instead of cachedTreeChildFolders=${childFolders.size} media=${cached.videoCount} childItems=${childItems.size}",
            )
        }
        return Folder(
            name = folderName,
            path = normalizedPath,
            dateModified = lastModified,
            parentPath = parentDisplayPath,
            mediaList = childVideos,
            folderList = childFolders,
            mediaCount = cached?.videoCount?.takeIf { it > 0 } ?: childVideos.size,
            cachedMediaSize = cached?.totalSize?.takeIf { it > resolvedMediaSize } ?: resolvedMediaSize,
            cachedMediaDuration = cached?.totalDurationMs?.takeIf { it > resolvedMediaDuration } ?: resolvedMediaDuration,
            folderCount = resolvedFolderCount,
        )
    }

    private fun mapCloudVideo(
        server: WebDavServer,
        currentPath: String,
        item: com.sakurafubuki.yume.core.model.WebDavMediaItem,
        metadataByHref: Map<String, CloudVideoMetadata>,
    ): Video {
        val streamUrl = webDavRepository.getStreamUrl(item, server)
        val playbackUrl = streamUrl.stripUserInfoFromHttpUrl()
        val decodedPath = Uri.decode(item.href.toUri().path.orEmpty())
        val metadata = metadataByHref[item.href]
        val resolvedDurationMs = metadata?.durationMs?.takeIf { it > 0L } ?: 0L
        return Video(
            id = playbackUrl.hashCode().toLong(),
            path = decodedPath.ifBlank { currentPath },
            parentPath = decodedPath.substringBeforeLast('/', ""),
            duration = resolvedDurationMs.coerceAtLeast(1L),
            uriString = playbackUrl,
            nameWithExtension = item.name,
            width = item.width ?: metadata?.width ?: 0,
            height = item.height ?: metadata?.height ?: 0,
            size = item.size,
            playbackPosition = 0L,
            dateModified = item.lastModified?.time ?: 0L,
            formattedDuration = Utils.formatDurationMillis(resolvedDurationMs),
            formattedFileSize = Utils.formatFileSize(item.size),
            thumbnailUriString = metadata?.thumbnailPath?.let { thumbnailPath ->
                if (thumbnailPath.startsWith("http://", ignoreCase = true) ||
                    thumbnailPath.startsWith("https://", ignoreCase = true)
                ) {
                    thumbnailPath
                } else {
                    Uri.fromFile(File(thumbnailPath)).toString()
                }
            },
        )
    }

    private fun resolveRelativePath(server: WebDavServer, href: String): String {
        val itemPath = normalizePath(Uri.decode(href.toUri().path.orEmpty()))
        val davIndex = itemPath.indexOf("/dav")
        return if (davIndex >= 0) {
            normalizePath(itemPath.substring(davIndex + 4).ifBlank { "/" })
        } else {
            itemPath
        }
    }

    private fun normalizePath(path: String): String {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return "/"
        val withLeadingSlash = if (trimmed.startsWith('/')) trimmed else "/$trimmed"
        return withLeadingSlash.removeSuffix("/").ifBlank { "/" }
    }

    private fun WebDavServer.fromApiPath(apiPath: String): String = normalizePath(apiPath)

    private fun toWebDavAbsolutePath(server: WebDavServer, relativePath: String): String {
        val normalizedBase = cloudStorageDisplayBasePath(server)
        val normalizedRelative = normalizePath(relativePath)
        if (normalizedRelative == "/") return normalizedBase
        if (normalizedRelative == normalizedBase || normalizedRelative.startsWith("$normalizedBase/")) {
            return normalizedRelative
        }
        if (normalizedBase == "/") return normalizedRelative
        return normalizePath("$normalizedBase/$normalizedRelative")
    }

    private fun cloudStorageDisplayBasePath(server: WebDavServer): String {
        val normalizedBasePath = normalizePath(server.basePath)
        if (normalizedBasePath != "/") return normalizedBasePath
        val urlPath = normalizePath(Uri.decode(server.url.toUri().path.orEmpty()))
        val davIndex = urlPath.indexOf("/dav")
        if (davIndex < 0) return normalizedBasePath
        val suffixAfterDav = urlPath.substring(davIndex + 4).ifBlank { "/" }
        return normalizePath(suffixAfterDav)
    }

    private fun cloudFolderDisplayName(server: WebDavServer, path: String): String {
        val absolutePath = toWebDavAbsolutePath(server, path)
        val folderName = Uri.decode(absolutePath.substringAfterLast('/'))
        return folderName.ifBlank { server.name }
    }

    private fun cloudFolderParentDisplayPath(server: WebDavServer, path: String): String {
        val normalizedPath = normalizePath(path)
        val parent = normalizedPath.substringBeforeLast('/', "/").ifBlank { "/" }
        return toWebDavAbsolutePath(server, parent)
    }

    private fun encodeCloudFolderPath(serverId: Int, path: String?): String = "$CLOUD_SERVER_PATH_PREFIX$serverId:$path"

    private fun decodeCloudFolderPath(path: String): Pair<Int, String>? {
        if (!path.startsWith(CLOUD_SERVER_PATH_PREFIX)) return null
        val payload = path.removePrefix(CLOUD_SERVER_PATH_PREFIX)
        val separator = payload.indexOf(':')
        if (separator <= 0) return null
        val serverId = payload.substring(0, separator).toIntOrNull() ?: return null
        val serverPath = payload.substring(separator + 1).ifBlank { "/" }
        return serverId to normalizePath(serverPath)
    }

    private fun findBestServerIdForPath(
        servers: List<WebDavServer>,
        path: String,
    ): Int? {
        val normalizedPath = normalizePath(path)
        if (normalizedPath == "/") return null

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

    private fun String.isHttpUri(): Boolean = startsWith("http://", ignoreCase = true) || startsWith("https://", ignoreCase = true)

    private fun fillPositionsFromMap(folder: Folder, stateMap: Map<String, MediumStateEntity>): Folder {
        fun fill(video: Video): Video {
            val state = stateMap[video.uriString] ?: return video
            return video.copy(
                playbackPosition = state.playbackPosition,
                lastPlayedAt = state.lastPlayedTime?.let { java.util.Date(it) },
            )
        }
        fun fillFolder(f: Folder): Folder = f.copy(
            mediaList = f.mediaList.map { fill(it) },
            folderList = f.folderList.map { fillFolder(it) },
        )
        return fillFolder(folder)
    }

    private fun refreshCloudVideoMetadataAsync(
        cloudAuxParent: Job,
        requestToken: Long,
        server: WebDavServer,
        path: String,
        preferences: ApplicationPreferences,
        items: List<com.sakurafubuki.yume.core.model.WebDavMediaItem>,
    ) {
        refreshCloudFolderWithMetadataAsync(
            cloudAuxParent = cloudAuxParent,
            requestToken = requestToken,
            server = server,
            path = path,
            preferences = preferences,
            items = items,
        )
    }

    private fun refreshCloudFolderWithMetadataAsync(
        cloudAuxParent: Job,
        requestToken: Long,
        server: WebDavServer,
        path: String,
        preferences: ApplicationPreferences,
        items: List<com.sakurafubuki.yume.core.model.WebDavMediaItem>,
    ) {
        viewModelScope.launch(cloudAuxParent + Dispatchers.IO) {
            val normalizedPath = normalizePath(path)
            val recurse = preferences.mediaViewMode == MediaViewMode.FOLDER_TREE
            val metadataHrefs = buildSet {
                if (recurse) {
                    collectVideoHrefsFromCachedTree(
                        server = server,
                        parentPath = normalizedPath,
                        items = items,
                        visitedPaths = mutableSetOf(),
                        collector = this,
                    )
                } else {
                    items.cloudDisplayVideoFiles().forEach { item -> add(item.href) }
                }
            }.toList()
            val folderPaths = buildSet {
                add(normalizedPath)
                if (recurse) {
                    collectFolderPathsFromCachedTree(
                        server = server,
                        parentPath = normalizedPath,
                        items = items,
                        visitedPaths = mutableSetOf(),
                        collector = this,
                    )
                } else {
                    items.asSequence()
                        .cloudDirectoryItems()
                        .map { item -> normalizePath(resolveRelativePath(server, item.href)) }
                        .forEach(::add)
                }
            }.toList()

            combine(
                cloudVideoMetadataRepository.observeMetadata(server.id, metadataHrefs),
                cloudVideoMetadataRepository.observeFolderMetadata(server.id, folderPaths),
            ) { metadataMap, folderMetadataMap ->
                metadataMap to folderMetadataMap
            }.collectLatest { (metadataMap, folderMetadataMap) ->
                if (requestToken != cloudLoadRequestToken) return@collectLatest

                val refreshedFolder = withContext(Dispatchers.Default) {
                    mapCloudFolder(
                        server = server,
                        path = path,
                        preferences = preferences,
                        items = items,
                        metadataByHref = metadataMap,
                        folderMetadataMap = folderMetadataMap,
                        rootCachedMetadata = folderMetadataMap[normalizedPath],
                        hideUnknownFolders = preferences.mediaViewMode == MediaViewMode.FOLDER_TREE,
                    )
                }
                val displayFolder = resolveCloudFolderForViewMode(
                    folder = refreshedFolder,
                    preferences = preferences,
                )

                if (requestToken != cloudLoadRequestToken) return@collectLatest
                if (!shouldApplyCloudDisplayFolder(preferences, refreshedFolder, displayFolder)) return@collectLatest

                val currentFolderMetadata = folderMetadataMap[normalizedPath]
                if (currentFolderMetadata == null ||
                    currentFolderMetadata.totalDurationMs != displayFolder.mediaDuration ||
                    currentFolderMetadata.totalSize != displayFolder.mediaSize ||
                    currentFolderMetadata.mediaCount != displayFolder.mediaCount ||
                    currentFolderMetadata.folderCount != displayFolder.folderCount
                ) {
                    val directVideoCount = items.cloudDisplayVideoFiles().size
                    saveFolderMetadataPreserving(
                        serverId = server.id,
                        folderPath = normalizedPath,
                        totalDurationMs = displayFolder.mediaDuration,
                        totalSize = displayFolder.mediaSize,
                        mediaCount = displayFolder.mediaCount,
                        folderCount = displayFolder.folderCount,
                        videoCount = directVideoCount.takeIf { it > 0 } ?: -1,
                        preserveExistingZeros = shouldPreserveZeroFolderSummary(
                            server = server,
                            items = items,
                            folderMetadataMap = folderMetadataMap,
                            displayFolder = displayFolder,
                        ),
                    )
                }
                rawCloudFolder.value = displayFolder
            }
        }

        viewModelScope.launch(cloudAuxParent + Dispatchers.IO) {
            if (requestToken != cloudLoadRequestToken) return@launch
            cacheCloudTreeVideoMetadata(
                server = server,
                path = path,
                preferences = preferences,
                items = items,
            )
        }
    }

    private suspend fun cacheCloudTreeVideoMetadata(
        server: WebDavServer,
        path: String,
        preferences: ApplicationPreferences,
        items: List<com.sakurafubuki.yume.core.model.WebDavMediaItem>,
    ) {
        val videoItems = if (preferences.mediaViewMode == MediaViewMode.FOLDER_TREE) {
            buildMap {
                collectVideoItemsFromCachedTree(
                    server = server,
                    parentPath = path,
                    items = items,
                    visitedPaths = mutableSetOf(),
                    collector = this,
                )
            }.values.toList()
        } else {
            items.cloudDisplayVideoFiles()
        }
        cloudVideoMetadataRepository.cacheMissingMetadata(server, videoItems)
    }

    private fun shouldPreserveZeroFolderSummary(
        server: WebDavServer,
        items: List<com.sakurafubuki.yume.core.model.WebDavMediaItem>,
        folderMetadataMap: Map<String, CloudFolderMetadata>,
        displayFolder: Folder,
    ): Boolean {
        val directFolderPaths = items
            .asSequence()
            .cloudDirectoryItems()
            .map { normalizePath(resolveRelativePath(server, it.href)) }
            .distinct()
            .toList()
        return directFolderPaths.any { it !in folderMetadataMap }
    }

    private suspend fun buildCloudMetadataMap(
        server: WebDavServer,
        items: List<com.sakurafubuki.yume.core.model.WebDavMediaItem>,
        recurse: Boolean = false,
    ): Map<String, CloudVideoMetadata> = withContext(Dispatchers.IO) {
        val hrefs = buildSet {
            if (recurse) {
                collectVideoHrefsFromCachedTree(
                    server = server,
                    parentPath = normalizePath(uiStateInternal.value.cloudPath),
                    items = items,
                    visitedPaths = mutableSetOf(),
                    collector = this,
                )
            } else {
                items.cloudDisplayVideoFiles().forEach { item -> add(item.href) }
            }
        }
        if (hrefs.isEmpty()) {
            emptyMap()
        } else {
            cloudVideoMetadataRepository.getMetadata(server.id, hrefs.toList())
        }
    }

    private fun collectVideoHrefsFromCachedTree(
        server: WebDavServer,
        parentPath: String,
        items: List<com.sakurafubuki.yume.core.model.WebDavMediaItem>,
        visitedPaths: MutableSet<String>,
        collector: MutableSet<String>,
    ) {
        val normalizedParentPath = normalizePath(parentPath)
        if (!visitedPaths.add(normalizedParentPath)) return

        items.cloudDisplayVideoFiles().forEach { item -> collector.add(item.href) }

        items.asSequence()
            .cloudDirectoryItems()
            .map { normalizePath(resolveRelativePath(server, it.href)) }
            .distinct()
            .forEach { childPath ->
                val childItems = webDavVideoDirectoryCache.get(server.id, childPath).orEmpty()
                collectVideoHrefsFromCachedTree(
                    server = server,
                    parentPath = childPath,
                    items = childItems,
                    visitedPaths = visitedPaths,
                    collector = collector,
                )
            }
    }

    private fun collectVideoItemsFromCachedTree(
        server: WebDavServer,
        parentPath: String,
        items: List<com.sakurafubuki.yume.core.model.WebDavMediaItem>,
        visitedPaths: MutableSet<String>,
        collector: MutableMap<String, com.sakurafubuki.yume.core.model.WebDavMediaItem>,
    ) {
        val normalizedParentPath = normalizePath(parentPath)
        if (!visitedPaths.add(normalizedParentPath)) return

        items.cloudDisplayVideoFiles().forEach { item -> collector[item.href] = item }

        items.asSequence()
            .cloudDirectoryItems()
            .map { normalizePath(resolveRelativePath(server, it.href)) }
            .distinct()
            .forEach { childPath ->
                val childItems = webDavVideoDirectoryCache.get(server.id, childPath).orEmpty()
                collectVideoItemsFromCachedTree(
                    server = server,
                    parentPath = childPath,
                    items = childItems,
                    visitedPaths = visitedPaths,
                    collector = collector,
                )
            }
    }

    private fun collectFolderPathsFromCachedTree(
        server: WebDavServer,
        parentPath: String,
        items: List<com.sakurafubuki.yume.core.model.WebDavMediaItem>,
        visitedPaths: MutableSet<String>,
        collector: MutableSet<String>,
    ) {
        val normalizedParentPath = normalizePath(parentPath)
        if (!visitedPaths.add(normalizedParentPath)) return

        items.asSequence()
            .cloudDirectoryItems()
            .map { normalizePath(resolveRelativePath(server, it.href)) }
            .distinct()
            .forEach { childPath ->
                collector.add(childPath)
                val childItems = webDavVideoDirectoryCache.get(server.id, childPath).orEmpty()
                collectFolderPathsFromCachedTree(
                    server = server,
                    parentPath = childPath,
                    items = childItems,
                    visitedPaths = visitedPaths,
                    collector = collector,
                )
            }
    }
}

private const val CLOUD_LOG_TAG = "CloudMediaPicker"
private const val CLOUD_FLOW_LOG_TAG = "CloudFolderFlow"
private const val CLOUD_SEARCH_LOG_TAG = "CloudSearchMediaPicker"
private const val CLOUD_SERVER_PATH_PREFIX = "__cloud_server__"
private const val CLOUD_INDEX_SEARCH_PAGE_SIZE = 10_000
private const val CLOUD_INDEX_SEARCH_MAX_RESULTS = 50_000
private const val CLOUD_INDEX_PARENT_LIST_PAGE_SIZE = 5_000
private const val CLOUD_INDEX_PARENT_LIST_MAX_CONCURRENCY = 8
private val CLOUD_INDEX_VIDEO_EXTENSIONS = setOf(
    "mp4", "mkv", "webm", "mov", "avi", "m4v", "flv", "wmv", "ts", "m2ts", "3gp", "mpg", "mpeg", "rmvb",
)

private data class CloudIndexedVideo(
    val item: FsSearchItem,
    val parentPath: String,
)

private fun ApplicationPreferences.hasDifferentCloudDisplayShape(
    other: ApplicationPreferences,
): Boolean = mediaViewMode != other.mediaViewMode ||
    sortBy != other.sortBy ||
    sortOrder != other.sortOrder

private fun Folder.hasCloudDisplayContent(): Boolean = folderList.isNotEmpty() || mediaList.isNotEmpty()

private fun CloudFolderMetadata?.hasKnownCloudVideoContent(): Boolean {
    if (this == null) return false
    return videoCount > 0 || folderCount > 0 || totalDurationMs > 0L || totalSize > 0L
}

private fun shouldApplyCloudDisplayFolder(
    preferences: ApplicationPreferences,
    sourceFolder: Folder,
    displayFolder: Folder,
): Boolean = preferences.mediaViewMode == MediaViewMode.FOLDER_TREE ||
    displayFolder.hasCloudDisplayContent() ||
    sourceFolder.folderList.isEmpty()

private fun FsSearchItem.isIndexedCloudVideoFile(): Boolean {
    if (is_dir || size <= 0L) return false
    val extension = name
        .substringBefore('?')
        .substringBefore('#')
        .substringAfterLast('/', name)
        .substringAfterLast('.', "")
        .lowercase()
    return extension in CLOUD_INDEX_VIDEO_EXTENSIONS
}

private fun FsSearchItem.toSyntheticWebDavMediaItem(
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
    val encodedName = Uri.encode(Uri.decode(name))
    val href = if (encodedDirSegments.isBlank()) {
        "$rootBaseUrl/d/$encodedName"
    } else {
        "$rootBaseUrl/d/$encodedDirSegments/$encodedName"
    }
    return WebDavMediaItem(
        name = Uri.decode(name),
        href = href,
        contentType = "",
        size = size,
        lastModified = null,
        isDirectory = false,
        serverId = server.id,
        rawVideoUrl = href,
    )
}

@Stable
data class MediaPickerUiState(
    val folderName: String?,
    val folderPath: String? = null,
    val mediaDataState: DataState<Folder?> = DataState.Loading,
    val refreshing: Boolean = false,
    val preferences: ApplicationPreferences = ApplicationPreferences(),
    val mode: MediaMode = MediaMode.LOCAL,
    val webDavServers: List<WebDavServer> = emptyList(),
    val cloudServersLoaded: Boolean = false,
    val selectedCloudServerIds: Set<Int> = emptySet(),
    val selectedCloudServerId: Int? = null,
    val cloudPath: String = "/",
    val cloudRefreshing: Boolean = false,
    val cloudDataState: DataState<Folder?> = DataState.Loading,
    val recentlyPlayedCloudUri: String? = null,
)

sealed interface MediaPickerUiEvent {
    data class DeleteVideos(val videos: List<String>) : MediaPickerUiEvent
    data class DeleteFolders(val folders: List<Folder>) : MediaPickerUiEvent
    data class ShareVideos(val videos: List<String>) : MediaPickerUiEvent
    data object Refresh : MediaPickerUiEvent
    data class RenameVideo(val uri: Uri, val to: String) : MediaPickerUiEvent
    data class AddToSync(val uri: Uri) : MediaPickerUiEvent
    data class UpdateMenu(val preferences: ApplicationPreferences) : MediaPickerUiEvent
    data object ToggleMode : MediaPickerUiEvent
    data class OpenLocalFolder(val folderPath: String?) : MediaPickerUiEvent
    data class SelectCloudServer(val serverId: Int) : MediaPickerUiEvent
    data class ToggleCloudServerSelection(val serverId: Int) : MediaPickerUiEvent
    data class OpenCloudFolder(val path: String) : MediaPickerUiEvent
    data class PreloadCloudPath(val path: String) : MediaPickerUiEvent
    data object NavigateCloudUp : MediaPickerUiEvent
    data object RefreshCloud : MediaPickerUiEvent
}

private const val CLOUD_SERVER_IDS_KEY = "cloud_server_ids"

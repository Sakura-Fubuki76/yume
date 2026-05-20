package com.sakurafubuki.yume.feature.videopicker.screens.search

import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sakurafubuki.yume.core.common.Logger
import com.sakurafubuki.yume.core.common.Utils
import com.sakurafubuki.yume.core.common.extensions.stripUserInfoFromHttpUrl
import com.sakurafubuki.yume.core.data.openlist.FsSearchItem
import com.sakurafubuki.yume.core.data.openlist.OpenListApi
import com.sakurafubuki.yume.core.data.openlist.toApiPath
import com.sakurafubuki.yume.core.data.openlist.toWebDavMediaItem
import com.sakurafubuki.yume.core.data.repository.PreferencesRepository
import com.sakurafubuki.yume.core.data.repository.SearchHistoryRepository
import com.sakurafubuki.yume.core.data.repository.WebDavServerRepository
import com.sakurafubuki.yume.core.data.webdav.WebDavRepository
import com.sakurafubuki.yume.core.domain.GetPopularFoldersUseCase
import com.sakurafubuki.yume.core.domain.SearchMediaUseCase
import com.sakurafubuki.yume.core.domain.SearchResults
import com.sakurafubuki.yume.core.media.sync.MediaInfoSynchronizer
import com.sakurafubuki.yume.core.model.ApplicationPreferences
import com.sakurafubuki.yume.core.model.Folder
import com.sakurafubuki.yume.core.model.Video
import com.sakurafubuki.yume.core.model.WebDavMediaItem
import com.sakurafubuki.yume.core.model.WebDavServer
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val searchMediaUseCase: SearchMediaUseCase,
    private val getPopularFoldersUseCase: GetPopularFoldersUseCase,
    private val searchHistoryRepository: SearchHistoryRepository,
    private val preferencesRepository: PreferencesRepository,
    private val webDavServerRepository: WebDavServerRepository,
    private val openListApi: OpenListApi,
    private val webDavRepository: WebDavRepository,
    private val mediaInfoSynchronizer: MediaInfoSynchronizer,
) : ViewModel() {

    private val uiStateInternal = MutableStateFlow(SearchUiState())
    val uiState = uiStateInternal.asStateFlow()

    private val searchQuery = MutableStateFlow("")
    private val searchScope = MutableStateFlow<SearchScope>(SearchScope.Local)

    init {
        collectSearchHistory()
        collectPopularFolders()
        collectPreferences()
        collectSearchResults()
    }

    private fun collectSearchHistory() {
        viewModelScope.launch {
            searchHistoryRepository.searchHistory.collect { history ->
                uiStateInternal.update { it.copy(searchHistory = history) }
            }
        }
    }

    private fun collectPopularFolders() {
        viewModelScope.launch {
            getPopularFoldersUseCase(limit = 5).collect { folders ->
                uiStateInternal.update { it.copy(popularFolders = folders) }
            }
        }
    }

    private fun collectPreferences() {
        viewModelScope.launch {
            preferencesRepository.applicationPreferences.collect { prefs ->
                uiStateInternal.update { it.copy(preferences = prefs) }
            }
        }
    }

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    private fun collectSearchResults() {
        viewModelScope.launch {
            searchQuery
                .debounce(SEARCH_DEBOUNCE_MS)
                .combine(searchScope) { query, scope -> query to scope }
                .flatMapLatest { (query, scope) ->
                    when (scope) {
                        SearchScope.Local -> searchMediaUseCase(query)
                        is SearchScope.Cloud -> flow {
                            emit(searchCloudMedia(query = query, scope = scope))
                        }
                    }
                }
                .collect { results ->
                    uiStateInternal.update {
                        it.copy(
                            searchResults = results,
                            isSearching = false,
                        )
                    }
                }
        }
    }

    fun onEvent(event: SearchUiEvent) {
        when (event) {
            is SearchUiEvent.OnQueryChange -> onQueryChange(event.query)
            is SearchUiEvent.OnSearch -> onSearch(event.query)
            is SearchUiEvent.OnHistoryItemClick -> onHistoryItemClick(event.query)
            is SearchUiEvent.OnRemoveHistoryItem -> removeHistoryItem(event.query)
            is SearchUiEvent.OnClearHistory -> clearHistory()
            is SearchUiEvent.AddToSync -> addToMediaInfoSynchronizer(event.uri)
            is SearchUiEvent.SetCloudScope -> setCloudScope(event)
        }
    }

    private fun setCloudScope(event: SearchUiEvent.SetCloudScope) {
        val serverIds = event.cloudServerIds.distinct()
        searchScope.value = if (event.cloudPath == null && event.cloudServerId == null && serverIds.isEmpty()) {
            SearchScope.Local
        } else {
            SearchScope.Cloud(
                path = normalizePath(event.cloudPath ?: ROOT_PATH),
                serverId = event.cloudServerId,
                serverIds = serverIds,
            )
        }
        val isCloudSearch = searchScope.value is SearchScope.Cloud
        uiStateInternal.update { it.copy(isCloudSearch = isCloudSearch) }
    }

    private fun onQueryChange(query: String) {
        uiStateInternal.update { it.copy(query = query, isSearching = query.isNotBlank()) }
        searchQuery.value = query
    }

    private fun onSearch(query: String) {
        if (query.isBlank()) return
        viewModelScope.launch {
            searchHistoryRepository.addSearchQuery(query)
        }
    }

    private fun onHistoryItemClick(query: String) {
        uiStateInternal.update { it.copy(query = query, isSearching = true) }
        searchQuery.value = query
        onSearch(query)
    }

    private fun removeHistoryItem(query: String) {
        viewModelScope.launch {
            searchHistoryRepository.removeSearchQuery(query)
        }
    }

    private fun clearHistory() {
        viewModelScope.launch {
            searchHistoryRepository.clearHistory()
        }
    }

    private fun addToMediaInfoSynchronizer(uri: Uri) {
        viewModelScope.launch {
            mediaInfoSynchronizer.sync(uri)
        }
    }

    private suspend fun searchCloudMedia(
        query: String,
        scope: SearchScope.Cloud,
    ): SearchResults = withContext(Dispatchers.IO) {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) return@withContext SearchResults()

        val servers = webDavServerRepository.observeServers().first()
        val selectedServerIds = scope.serverIds.ifEmpty {
            scope.serverId?.let(::listOf).orEmpty()
        }
        val selectedServers = if (selectedServerIds.isEmpty()) {
            scope.serverId?.let { id -> servers.filter { it.id == id } }.orEmpty()
        } else {
            servers.filter { it.id in selectedServerIds }
        }.ifEmpty {
            scope.serverId?.let { id -> servers.filter { it.id == id } }.orEmpty()
        }
        if (selectedServers.isEmpty()) return@withContext SearchResults()

        val serverResults = coroutineScope {
            selectedServers.map { server ->
                async {
                    val rootPath = if (selectedServers.size > 1 && scope.path == ROOT_PATH) {
                        normalizePath(server.basePath)
                    } else {
                        scope.path
                    }
                    searchCloudServer(
                        server = server,
                        rootPath = rootPath,
                        query = normalizedQuery,
                    )
                }
            }.awaitAll()
        }

        SearchResults(
            folders = serverResults.flatMap { it.folders },
            videos = serverResults.flatMap { it.videos },
        )
    }

    private suspend fun searchCloudServer(
        server: WebDavServer,
        rootPath: String,
        query: String,
    ): SearchResults {
        val items = searchOpenListItems(server = server, rootPath = rootPath, query = query)
        if (items.isEmpty()) return SearchResults()

        val folders = items
            .asSequence()
            .filter { it.is_dir }
            .map { item ->
                val folderPath = normalizePath("${normalizePath(item.parent)}/${item.name}")
                Folder(
                    name = Uri.decode(item.name).ifBlank { folderPath.substringAfterLast('/') },
                    path = encodeCloudFolderPath(server.id, folderPath),
                    dateModified = 0L,
                    parentPath = folderPath.substringBeforeLast('/', ROOT_PATH).ifBlank { ROOT_PATH },
                )
            }
            .distinctBy { it.path }
            .toList()

        val indexedVideos = items
            .asSequence()
            .filter { it.isIndexedCloudVideoFile() }
            .map { CloudSearchVideo(item = it, parentPath = normalizePath(it.parent)) }
            .toList()
        val videos = resolveCloudSearchVideos(server = server, indexedVideos = indexedVideos)
        return SearchResults(folders = folders, videos = videos)
    }

    private suspend fun searchOpenListItems(
        server: WebDavServer,
        rootPath: String,
        query: String,
    ): List<FsSearchItem> {
        val apiParentPath = normalizePath(server.toApiPath(rootPath))
        val items = mutableListOf<FsSearchItem>()
        var page = 1
        var total: Int? = null
        var seenRawItems = 0
        while ((total == null || seenRawItems < total) && seenRawItems < CLOUD_SEARCH_MAX_RESULTS) {
            val data = openListApi.search(
                server = server,
                parent = apiParentPath,
                keywords = query,
                scope = 0,
                page = page,
                perPage = CLOUD_SEARCH_PAGE_SIZE,
            ).getOrElse { throwable ->
                Logger.d(CLOUD_SEARCH_LOG_TAG, "search unavailable server=${server.id} path=$rootPath query=$query: ${throwable.message}")
                return emptyList()
            }
            total = data.total
            if (total > CLOUD_SEARCH_MAX_RESULTS) {
                Logger.w(
                    CLOUD_SEARCH_LOG_TAG,
                    "search result capped server=${server.id} path=$rootPath query=$query total=$total cap=$CLOUD_SEARCH_MAX_RESULTS",
                )
            }
            val content = data.content.orEmpty()
            if (content.isEmpty()) break
            seenRawItems += content.size
            val normalizedRoot = apiParentPath
            items += content.filter { item ->
                val itemPath = if (item.is_dir) {
                    normalizePath("${normalizePath(item.parent)}/${item.name}")
                } else {
                    normalizePath(item.parent)
                }
                normalizedRoot == ROOT_PATH ||
                    itemPath == normalizedRoot ||
                    itemPath.startsWith("$normalizedRoot/")
            }
            page += 1
        }
        return items
    }

    private suspend fun resolveCloudSearchVideos(
        server: WebDavServer,
        indexedVideos: List<CloudSearchVideo>,
    ): List<Video> = coroutineScope {
        val parentSemaphore = Semaphore(CLOUD_SEARCH_PARENT_LIST_MAX_CONCURRENCY)
        indexedVideos
            .groupBy { it.parentPath }
            .map { (parentPath, videos) ->
                async {
                    parentSemaphore.withPermit {
                        loadSearchParentVideoItems(
                            server = server,
                            parentPath = parentPath,
                            indexedVideos = videos,
                        ).map { item ->
                            mapCloudVideo(server = server, currentPath = parentPath, item = item)
                        }
                    }
                }
            }
            .awaitAll()
            .flatten()
            .distinctBy { it.uriString }
    }

    private suspend fun loadSearchParentVideoItems(
        server: WebDavServer,
        parentPath: String,
        indexedVideos: List<CloudSearchVideo>,
    ): List<WebDavMediaItem> {
        val indexedNames = indexedVideos.map { Uri.decode(it.item.name) }.toSet()
        val apiPath = server.toApiPath(parentPath)
        val listedVideos = openListApi.listDirectory(
            server = server,
            path = apiPath,
            page = 1,
            perPage = CLOUD_SEARCH_PARENT_LIST_PAGE_SIZE,
        ).getOrNull()
            ?.content
            .orEmpty()
            .map { it.toWebDavMediaItem(server, apiPath) }
            .filter { it.isVideo && it.name in indexedNames }
        val listedNames = listedVideos.map { it.name }.toSet()
        if (listedNames.size == indexedNames.size) return listedVideos

        val missingSyntheticVideos = indexedVideos
            .filterNot { Uri.decode(it.item.name) in listedNames }
            .map { indexed ->
                indexed.item.toSyntheticWebDavMediaItem(
                    server = server,
                    parentPath = parentPath,
                )
            }
        return listedVideos + missingSyntheticVideos
    }

    private fun mapCloudVideo(
        server: WebDavServer,
        currentPath: String,
        item: WebDavMediaItem,
    ): Video {
        val playbackUrl = webDavRepository.getStreamUrl(item, server).stripUserInfoFromHttpUrl()
        val decodedPath = Uri.decode(playbackUrl.toUri().path.orEmpty())
        val displayName = Uri.decode(item.name).ifBlank { decodedPath.substringAfterLast('/') }
        return Video(
            id = playbackUrl.hashCode().toLong(),
            path = decodedPath.ifBlank { currentPath },
            parentPath = decodedPath.substringBeforeLast('/', ""),
            duration = 1L,
            uriString = playbackUrl,
            nameWithExtension = displayName,
            width = item.width ?: 0,
            height = item.height ?: 0,
            size = item.size,
            playbackPosition = 0L,
            dateModified = item.lastModified?.time ?: 0L,
            formattedDuration = "",
            formattedFileSize = Utils.formatFileSize(item.size),
            thumbnailUriString = item.apiThumbnailUrl?.stripUserInfoFromHttpUrl(),
        )
    }

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 300L
    }
}

@Stable
data class SearchUiState(
    val query: String = "",
    val searchHistory: List<String> = emptyList(),
    val popularFolders: List<Folder> = emptyList(),
    val searchResults: SearchResults = SearchResults(),
    val isSearching: Boolean = false,
    val isCloudSearch: Boolean = false,
    val preferences: ApplicationPreferences = ApplicationPreferences(),
)

sealed interface SearchUiEvent {
    data class OnQueryChange(val query: String) : SearchUiEvent
    data class OnSearch(val query: String) : SearchUiEvent
    data class OnHistoryItemClick(val query: String) : SearchUiEvent
    data class OnRemoveHistoryItem(val query: String) : SearchUiEvent
    data object OnClearHistory : SearchUiEvent
    data class AddToSync(val uri: Uri) : SearchUiEvent
    data class SetCloudScope(
        val cloudPath: String?,
        val cloudServerId: Int?,
        val cloudServerIds: List<Int>,
    ) : SearchUiEvent
}

private sealed interface SearchScope {
    data object Local : SearchScope
    data class Cloud(
        val path: String,
        val serverId: Int?,
        val serverIds: List<Int>,
    ) : SearchScope
}

private data class CloudSearchVideo(
    val item: FsSearchItem,
    val parentPath: String,
)

private fun FsSearchItem.isIndexedCloudVideoFile(): Boolean {
    if (is_dir || size <= 0L) return false
    val extension = name
        .substringBefore('?')
        .substringBefore('#')
        .substringAfterLast('/', name)
        .substringAfterLast('.', "")
        .lowercase()
    return extension in CLOUD_SEARCH_VIDEO_EXTENSIONS
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
    val decodedName = Uri.decode(name)
    val encodedName = Uri.encode(decodedName)
    val href = if (encodedDirSegments.isBlank()) {
        "$rootBaseUrl/d/$encodedName"
    } else {
        "$rootBaseUrl/d/$encodedDirSegments/$encodedName"
    }
    return WebDavMediaItem(
        name = decodedName,
        href = href,
        contentType = "",
        size = size,
        lastModified = null,
        isDirectory = false,
        serverId = server.id,
        rawVideoUrl = href,
    )
}

private fun normalizePath(path: String): String {
    val trimmed = path.trim()
    if (trimmed.isEmpty()) return ROOT_PATH
    val withLeadingSlash = if (trimmed.startsWith('/')) trimmed else "/$trimmed"
    return withLeadingSlash.removeSuffix("/").ifBlank { ROOT_PATH }
}

private fun encodeCloudFolderPath(serverId: Int, path: String): String = "$CLOUD_SERVER_PATH_PREFIX$serverId:$path"

private const val ROOT_PATH = "/"
private const val CLOUD_SERVER_PATH_PREFIX = "__cloud_server__"
private const val CLOUD_SEARCH_LOG_TAG = "CloudSearchScreen"
private const val CLOUD_SEARCH_PAGE_SIZE = 500
private const val CLOUD_SEARCH_MAX_RESULTS = 10_000
private const val CLOUD_SEARCH_PARENT_LIST_PAGE_SIZE = 5_000
private const val CLOUD_SEARCH_PARENT_LIST_MAX_CONCURRENCY = 8
private val CLOUD_SEARCH_VIDEO_EXTENSIONS = setOf(
    "mp4", "mkv", "webm", "mov", "avi", "m4v", "flv", "wmv", "ts", "m2ts", "3gp", "mpg", "mpeg", "rmvb",
)

package com.sakurafubuki.yume.feature.videopicker.screens.mediapicker

import android.net.Uri
import com.sakurafubuki.yume.core.common.Logger
import com.sakurafubuki.yume.core.data.cache.CloudDirectoryItemCache
import com.sakurafubuki.yume.core.data.openlist.FsSearchItem
import com.sakurafubuki.yume.core.data.openlist.OpenListApi
import com.sakurafubuki.yume.core.data.openlist.toApiPath
import com.sakurafubuki.yume.core.data.openlist.toWebDavMediaItem
import com.sakurafubuki.yume.core.data.repository.CloudVideoMetadataRepository
import com.sakurafubuki.yume.core.data.repository.MetadataRequestPriority
import com.sakurafubuki.yume.core.data.webdav.WebDavRepository
import com.sakurafubuki.yume.core.model.WebDavMediaItem
import com.sakurafubuki.yume.core.model.WebDavServer
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

@Singleton
class CloudFolderSummaryScanner @Inject constructor(
    private val openListApi: OpenListApi,
    private val webDavRepository: WebDavRepository,
    private val cloudVideoMetadataRepository: CloudVideoMetadataRepository,
    private val webDavVideoDirectoryCache: WebDavVideoDirectoryCache,
    private val cloudDirectoryItemCache: CloudDirectoryItemCache,
) {
    suspend fun refreshStorageRootSummaries(
        selectedServers: List<WebDavServer>,
        refreshing: Boolean,
        isCurrent: () -> Boolean,
    ) {
        val semaphore = Semaphore(ROOT_SUMMARY_MAX_CONCURRENT_REQUESTS)
        coroutineScope {
            selectedServers.map { server ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        if (!isCurrent()) return@withPermit
                        runCatching {
                            val rootPath = normalizePath(server.basePath)
                            val items = listCloudDirectory(server, rootPath, perPage = SUMMARY_LIST_PER_PAGE, refreshing = refreshing)
                            webDavVideoDirectoryCache.put(server.id, rootPath, items)
                            cloudDirectoryItemCache.put(server.id, rootPath, items)
                            if (!refreshIndexedVideoSummaries(server, rootPath, items)) {
                                saveScannedFolderMetadata(server, rootPath, items)
                            }
                        }.onFailure { throwable ->
                            if (throwable !is CancellationException) {
                                Logger.w(CLOUD_SUMMARY_LOG_TAG, "refresh root summary failed server=${server.id}", throwable)
                            }
                        }
                    }
                }
            }.awaitAll()
        }
    }

    suspend fun refreshDirectChildSummaries(
        server: WebDavServer,
        parentPath: String,
        parentItems: List<WebDavMediaItem>,
        isCurrent: () -> Boolean,
    ) {
        val childPaths = parentItems
            .asSequence()
            .cloudDirectoryItems()
            .map { normalizePath(resolveRelativePath(server, it.href)) }
            .distinct()
            .toList()
        if (childPaths.isEmpty()) return

        val usedIndexedSummary = refreshIndexedVideoSummaries(server, parentPath, parentItems)

        val semaphore = Semaphore(CHILD_SUMMARY_MAX_CONCURRENT_REQUESTS)
        coroutineScope {
            val jobs = childPaths.map { childPath ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        if (!isCurrent()) return@withPermit
                        runCatching {
                            refreshFolderSummaryTree(
                                server = server,
                                folderPath = childPath,
                                isCurrent = isCurrent,
                                visitedPaths = mutableSetOf(normalizePath(parentPath)),
                            )
                        }.onFailure { throwable ->
                            if (throwable !is CancellationException) {
                                Logger.w(
                                    CLOUD_SUMMARY_LOG_TAG,
                                    "refresh child summary failed server=${server.id} path=$childPath",
                                    throwable,
                                )
                            }
                        }
                    }
                }
            }
            jobs.awaitAll()
            if (isCurrent() && !usedIndexedSummary) {
                saveScannedFolderMetadata(server, parentPath, parentItems)
            }
        }
    }

    private suspend fun refreshFolderSummaryTree(
        server: WebDavServer,
        folderPath: String,
        isCurrent: () -> Boolean,
        visitedPaths: MutableSet<String>,
        depth: Int = 0,
    ) {
        val normalizedPath = normalizePath(folderPath)
        if (!isCurrent()) return
        if (!visitedPaths.add(normalizedPath)) return

        val items = listCloudDirectory(server, normalizedPath, perPage = SUMMARY_LIST_PER_PAGE)
        webDavVideoDirectoryCache.put(server.id, normalizedPath, items)
        cloudDirectoryItemCache.put(server.id, normalizedPath, items)
        cloudVideoMetadataRepository.cacheMissingMetadata(
            server,
            items.cloudDisplayVideoFiles(),
            priority = MetadataRequestPriority.BACKGROUND,
        )

        if (depth < SUMMARY_SCAN_MAX_DEPTH && items.cloudDisplayVideoFiles().isEmpty()) {
            val childPaths = items
                .asSequence()
                .cloudDirectoryItems()
                .map { normalizePath(resolveRelativePath(server, it.href)) }
                .distinct()
                .toList()
            for (childPath in childPaths) {
                refreshFolderSummaryTree(
                    server = server,
                    folderPath = childPath,
                    isCurrent = isCurrent,
                    visitedPaths = visitedPaths,
                    depth = depth + 1,
                )
            }
        }

        if (!isCurrent()) return
        saveScannedFolderMetadata(server, normalizedPath, items)
    }

    private suspend fun refreshIndexedVideoSummaries(
        server: WebDavServer,
        parentPath: String,
        parentItems: List<WebDavMediaItem>,
    ): Boolean {
        val directFolderItems = parentItems.cloudDirectoryItems()
        if (directFolderItems.isEmpty()) return false

        Logger.d(
            CLOUD_SEARCH_LOG_TAG,
            "search summary start server=${server.id} path=$parentPath directFolders=${directFolderItems.size}",
        )
        val indexedFiles = searchIndexedFiles(server, parentPath) ?: return false

        val apiParentPath = normalizePath(server.toApiPath(parentPath))
        val videoFiles = indexedFiles.filter { it.isIndexedVideoFile() }

        val directFoldersByName = directFolderItems.associate { item ->
            item.name to normalizePath(resolveRelativePath(server, item.href))
        }
        val existingMetadata = cloudVideoMetadataRepository.getFolderMetadata(
            server.id,
            listOf(normalizePath(parentPath)) + directFoldersByName.values,
        )
        val videosByDirectChild = videoFiles
            .mapNotNull { file ->
                val childName = immediateChildName(apiParentPath, normalizePath(file.parent)) ?: return@mapNotNull null
                if (childName !in directFoldersByName) return@mapNotNull null
                childName to file
            }
            .groupBy({ it.first }, { it.second })

        val directParentVideos = videoFiles.filter { normalizePath(it.parent) == apiParentPath }
        val displayableChildNames = videosByDirectChild.keys

        saveIndexedFolderMetadata(
            serverId = server.id,
            folderPath = normalizePath(parentPath),
            directVideoCount = directParentVideos.size,
            folderCount = displayableChildNames.size,
            totalSize = videoFiles.sumOf { it.size },
            existing = existingMetadata[normalizePath(parentPath)],
        )

        directFoldersByName.forEach { (childName, childPath) ->
            val childApiPath = normalizePath("$apiParentPath/$childName")
            val childVideos = videosByDirectChild[childName].orEmpty()
            val directChildVideos = childVideos.count { normalizePath(it.parent) == childApiPath }
            val displayableGrandChildCount = childVideos
                .mapNotNull { immediateChildName(childApiPath, normalizePath(it.parent)) }
                .distinct()
                .count()
            saveIndexedFolderMetadata(
                serverId = server.id,
                folderPath = childPath,
                directVideoCount = directChildVideos,
                folderCount = displayableGrandChildCount,
                totalSize = childVideos.sumOf { it.size },
                existing = existingMetadata[childPath],
            )
        }

        Logger.d(
            CLOUD_SEARCH_LOG_TAG,
            "search summary applied server=${server.id} path=$parentPath files=${indexedFiles.size} videos=${videoFiles.size} visibleChildFolders=${displayableChildNames.size} zeroChildren=${directFoldersByName.size - displayableChildNames.size}",
        )
        return true
    }

    private suspend fun searchIndexedFiles(
        server: WebDavServer,
        parentPath: String,
    ): List<FsSearchItem>? {
        val apiParentPath = normalizePath(server.toApiPath(parentPath))
        val files = mutableListOf<FsSearchItem>()
        var page = 1
        var total = Int.MAX_VALUE
        while (files.size < total) {
            val data = openListApi.search(
                server = server,
                parent = apiParentPath,
                keywords = "",
                scope = 2,
                page = page,
                perPage = SEARCH_INDEX_PAGE_SIZE,
            ).getOrElse { throwable ->
                Logger.d(CLOUD_SEARCH_LOG_TAG, "search unavailable server=${server.id} path=$parentPath: ${throwable.message}")
                return null
            }
            val content = data.content.orEmpty()
            total = data.total
            if (total > SEARCH_INDEX_MAX_RESULTS) {
                Logger.w(CLOUD_SEARCH_LOG_TAG, "search result too large server=${server.id} path=$parentPath total=$total")
                return null
            }
            Logger.d(
                CLOUD_SEARCH_LOG_TAG,
                "search page server=${server.id} path=$parentPath page=$page total=$total returned=${content.size}",
            )
            if (content.isEmpty()) break
            files += content
            if (files.size >= total) break
            page += 1
        }
        return files
    }

    private suspend fun saveIndexedFolderMetadata(
        serverId: Int,
        folderPath: String,
        directVideoCount: Int,
        folderCount: Int,
        totalSize: Long,
        existing: com.sakurafubuki.yume.core.model.CloudFolderMetadata?,
    ) {
        cloudVideoMetadataRepository.saveFolderMetadata(
            serverId = serverId,
            folderPath = normalizePath(folderPath),
            totalDurationMs = existing?.totalDurationMs ?: 0L,
            totalSize = totalSize,
            mediaCount = directVideoCount,
            folderCount = folderCount,
            coverImageUri = existing?.coverImageUri,
            videoCount = directVideoCount,
            imageCount = existing?.imageCount ?: 0,
        )
    }

    private suspend fun listCloudDirectory(
        server: WebDavServer,
        path: String,
        perPage: Int,
        refreshing: Boolean = false,
    ): List<WebDavMediaItem> {
        val apiPath = server.toApiPath(path)
        return try {
            val result = openListApi.listDirectory(server, apiPath, page = 1, perPage = perPage, refresh = refreshing)
            result.fold(
                onSuccess = { data -> data.content.orEmpty().map { it.toWebDavMediaItem(server, apiPath) } },
                onFailure = { throwable -> throw throwable },
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Logger.w(CLOUD_SUMMARY_LOG_TAG, "OpenList API failed server=${server.id}, falling back to WebDAV: ${e.message}")
            webDavRepository.listDirectory(server, path)
        }
    }

    private suspend fun saveScannedFolderMetadata(
        server: WebDavServer,
        folderPath: String,
        items: List<WebDavMediaItem>,
    ) {
        val normalizedPath = normalizePath(folderPath)
        val videos = items.cloudDisplayVideoFiles()
        val childPaths = items
            .asSequence()
            .cloudDirectoryItems()
            .map { normalizePath(resolveRelativePath(server, it.href)) }
            .distinct()
            .toList()
        val childMetadata = cloudVideoMetadataRepository.getFolderMetadata(server.id, childPaths)
        val hasUnresolvedChildFolders = childPaths.any { it !in childMetadata }
        val displayableFolderCount = childPaths.count { !isKnownEmptyCloudFolder(childMetadata[it]) }
        val isKnownEmpty = videos.isEmpty() && displayableFolderCount == 0 && !hasUnresolvedChildFolders
        val existing = cloudVideoMetadataRepository.getFolderMetadata(server.id, listOf(normalizedPath))[normalizedPath]

        cloudVideoMetadataRepository.saveFolderMetadata(
            serverId = server.id,
            folderPath = normalizedPath,
            totalDurationMs = if (isKnownEmpty) 0L else existing?.totalDurationMs ?: 0L,
            totalSize = when {
                videos.isNotEmpty() -> videos.sumOf { it.size }
                isKnownEmpty -> 0L
                else -> existing?.totalSize ?: 0L
            },
            mediaCount = when {
                videos.isNotEmpty() -> videos.size
                isKnownEmpty -> 0
                hasUnresolvedChildFolders -> existing?.mediaCount ?: 0
                else -> 0
            },
            folderCount = displayableFolderCount,
            coverImageUri = existing?.coverImageUri,
            videoCount = when {
                videos.isNotEmpty() -> videos.size
                isKnownEmpty -> 0
                else -> existing?.videoCount ?: UNKNOWN_VIDEO_COUNT
            },
            imageCount = existing?.imageCount ?: 0,
        )
    }

    private fun resolveRelativePath(server: WebDavServer, href: String): String {
        val serverPath = normalizePath(Uri.decode(Uri.parse(server.url).path.orEmpty()))
        val itemPath = normalizePath(Uri.decode(Uri.parse(href).path.orEmpty()))
        return if (itemPath.startsWith(serverPath)) {
            normalizePath(itemPath.removePrefix(serverPath))
        } else {
            itemPath
        }
    }
}

internal fun WebDavMediaItem.isCloudDisplayVideoFile(): Boolean = !isDirectory && isVideo && size > 0L

internal fun Iterable<WebDavMediaItem>.cloudDisplayVideoFiles(): List<WebDavMediaItem> = filter { it.isCloudDisplayVideoFile() }

internal fun Sequence<WebDavMediaItem>.cloudDisplayVideoFiles(): Sequence<WebDavMediaItem> = filter { it.isCloudDisplayVideoFile() }

internal fun Iterable<WebDavMediaItem>.cloudDirectoryItems(): List<WebDavMediaItem> = filter { it.isDirectory }

internal fun Sequence<WebDavMediaItem>.cloudDirectoryItems(): Sequence<WebDavMediaItem> = filter { it.isDirectory }

internal fun List<WebDavMediaItem>.hasKnownCloudDisplayContent(): Boolean = cloudDisplayVideoFiles().isNotEmpty() || cloudDirectoryItems().isNotEmpty()

internal fun isKnownEmptyCloudFolder(metadata: com.sakurafubuki.yume.core.model.CloudFolderMetadata?): Boolean {
    if (metadata == null || metadata.videoCount != 0 || metadata.folderCount != 0) return false
    return metadata.mediaCount == 0 || metadata.mediaCount == metadata.imageCount
}

private fun FsSearchItem.isIndexedVideoFile(): Boolean {
    if (is_dir || size <= 0L) return false
    val extension = name
        .substringBefore('?')
        .substringBefore('#')
        .substringAfterLast('/', name)
        .substringAfterLast('.', "")
        .lowercase()
    return extension in SEARCH_VIDEO_EXTENSIONS
}

private fun immediateChildName(parentPath: String, childParentPath: String): String? {
    val normalizedParent = normalizePath(parentPath)
    val normalizedChildParent = normalizePath(childParentPath)
    val prefix = if (normalizedParent == "/") "/" else "$normalizedParent/"
    if (!normalizedChildParent.startsWith(prefix)) return null
    val rest = normalizedChildParent.removePrefix(prefix)
    if (rest.isBlank()) return null
    return rest.substringBefore('/').takeIf { it.isNotBlank() }
}

private fun normalizePath(path: String): String {
    val trimmed = path.trim()
    if (trimmed.isEmpty()) return "/"
    val withLeadingSlash = if (trimmed.startsWith('/')) trimmed else "/$trimmed"
    return withLeadingSlash.removeSuffix("/").ifBlank { "/" }
}

private const val CLOUD_SUMMARY_LOG_TAG = "CloudFolderSummary"
private const val CLOUD_SEARCH_LOG_TAG = "CloudSearchSummary"
private const val ROOT_SUMMARY_MAX_CONCURRENT_REQUESTS = 4
private const val CHILD_SUMMARY_MAX_CONCURRENT_REQUESTS = 4
private const val SUMMARY_SCAN_MAX_DEPTH = 16
private const val SUMMARY_LIST_PER_PAGE = 5000
private const val SEARCH_INDEX_PAGE_SIZE = 10_000
private const val SEARCH_INDEX_MAX_RESULTS = 50_000
private const val UNKNOWN_VIDEO_COUNT = -1
private val SEARCH_VIDEO_EXTENSIONS = setOf(
    "mp4", "mkv", "webm", "mov", "avi", "m4v", "flv", "wmv", "ts", "m2ts", "3gp", "mpg", "mpeg", "rmvb",
)

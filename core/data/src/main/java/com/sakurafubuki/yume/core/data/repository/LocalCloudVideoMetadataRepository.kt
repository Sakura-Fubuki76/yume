package com.sakurafubuki.yume.core.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build.VERSION.SDK_INT
import android.util.Base64
import coil3.ImageLoader
import coil3.memory.MemoryCache
import com.sakurafubuki.yume.core.common.Logger
import com.sakurafubuki.yume.core.common.extensions.stripUserInfoFromHttpUrl
import com.sakurafubuki.yume.core.data.webdav.WebDavRepository
import com.sakurafubuki.yume.core.database.dao.WebDavFolderMetadataDao
import com.sakurafubuki.yume.core.database.dao.WebDavVideoMetadataDao
import com.sakurafubuki.yume.core.database.entities.WebDavFolderMetadataEntity
import com.sakurafubuki.yume.core.database.entities.WebDavVideoMetadataEntity
import com.sakurafubuki.yume.core.model.CloudFolderMetadata
import com.sakurafubuki.yume.core.model.CloudVideoMetadata
import com.sakurafubuki.yume.core.model.ThumbnailGenerationStrategy
import com.sakurafubuki.yume.core.model.WebDavMediaItem
import com.sakurafubuki.yume.core.model.WebDavServer
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.sakurafubuki.yume.nativelib.mediainfo.MediaThumbnailRetriever
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

@Singleton
class LocalCloudVideoMetadataRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferencesRepository: PreferencesRepository,
    private val webDavRepository: WebDavRepository,
    private val webDavVideoMetadataDao: WebDavVideoMetadataDao,
    private val webDavFolderMetadataDao: WebDavFolderMetadataDao,
    private val imageLoader: ImageLoader,
    private val okHttpClient: OkHttpClient,
) : CloudVideoMetadataRepository {

    private val mp4KeyframeExtractor by lazy { Mp4KeyframeExtractor(okHttpClient) }

    private val metadataRetryLock = Any()
    private val metadataRetryAfterMs = mutableMapOf<String, Long>()
    private val metadataInFlightLock = Any()
    private val metadataInFlightKeys = mutableSetOf<String>()

    override suspend fun getMetadata(serverId: Int, hrefs: List<String>): Map<String, CloudVideoMetadata> {
        if (hrefs.isEmpty()) return emptyMap()
        return withContext(Dispatchers.IO) {
            hrefs.distinct().chunked(SQL_BIND_CHUNK_SIZE).flatMap { chunk ->
                webDavVideoMetadataDao.getByServerAndHrefs(serverId, chunk)
            }
                .associate { entity ->
                    entity.href to CloudVideoMetadata(
                        href = entity.href,
                        durationMs = entity.durationMs,
                        thumbnailPath = entity.thumbnailPath,
                        width = entity.width,
                        height = entity.height,
                    )
                }
        }
    }

    override fun observeMetadata(serverId: Int): Flow<Map<String, CloudVideoMetadata>> = webDavVideoMetadataDao.observeByServer(serverId)
        .map { entities ->
            entities.associate { entity ->
                entity.href to CloudVideoMetadata(
                    href = entity.href,
                    durationMs = entity.durationMs,
                    thumbnailPath = entity.thumbnailPath,
                    width = entity.width,
                    height = entity.height,
                )
            }
        }
        .distinctUntilChanged()

    override fun observeMetadata(
        serverId: Int,
        hrefs: List<String>,
    ): Flow<Map<String, CloudVideoMetadata>> {
        if (hrefs.isEmpty()) return flowOf(emptyMap())
        val distinctHrefs = hrefs.distinct()
        val hrefSet = distinctHrefs.toSet()
        val source = if (distinctHrefs.size > SQL_BIND_CHUNK_SIZE) {
            webDavVideoMetadataDao.observeByServer(serverId).map { entities -> entities.filter { it.href in hrefSet } }
        } else {
            webDavVideoMetadataDao.observeByServerAndHrefs(serverId, distinctHrefs)
        }
        return source.map { entities ->
            entities.associate { entity ->
                entity.href to CloudVideoMetadata(
                    href = entity.href,
                    durationMs = entity.durationMs,
                    thumbnailPath = entity.thumbnailPath,
                    width = entity.width,
                    height = entity.height,
                )
            }
        }.distinctUntilChanged()
    }

    override fun observeFolderMetadata(
        serverId: Int,
        folderPaths: List<String>,
    ): Flow<Map<String, CloudFolderMetadata>> {
        if (folderPaths.isEmpty()) return flowOf(emptyMap())
        val distinctPaths = folderPaths.distinct()
        val pathSet = distinctPaths.toSet()
        val source = if (distinctPaths.size > SQL_BIND_CHUNK_SIZE) {
            webDavFolderMetadataDao.observeByServer(serverId).map { entities -> entities.filter { it.folderPath in pathSet } }
        } else {
            webDavFolderMetadataDao.observeByServerAndPaths(serverId, distinctPaths)
        }
        return source.map { entities ->
            entities.associate { entity ->
                entity.folderPath to CloudFolderMetadata(
                    totalDurationMs = entity.totalDurationMs,
                    totalSize = entity.totalSize,
                    mediaCount = entity.mediaCount,
                    folderCount = entity.folderCount,
                    coverImageUri = entity.coverImageUri,
                    videoCount = entity.videoCount,
                    imageCount = entity.imageCount,
                )
            }
        }.distinctUntilChanged()
    }

    override fun observeFolderMetadata(serverId: Int): Flow<Map<String, CloudFolderMetadata>> = webDavFolderMetadataDao.observeByServer(serverId)
        .map { entities ->
            entities.associate { entity ->
                entity.folderPath to CloudFolderMetadata(
                    totalDurationMs = entity.totalDurationMs,
                    totalSize = entity.totalSize,
                    mediaCount = entity.mediaCount,
                    folderCount = entity.folderCount,
                    coverImageUri = entity.coverImageUri,
                    videoCount = entity.videoCount,
                    imageCount = entity.imageCount,
                )
            }
        }
        .distinctUntilChanged()

    override suspend fun cacheMissingMetadata(server: WebDavServer, items: List<WebDavMediaItem>): Boolean {
        val videoItems = items.filter { it.isVideo && !it.isDirectory && it.size > 0L }
        if (videoItems.isEmpty()) return false
        val inFlightKey = metadataInFlightKey(server.id, videoItems)
        val acquired = synchronized(metadataInFlightLock) {
            metadataInFlightKeys.add(inFlightKey)
        }
        if (!acquired) {
            return false
        }

        return try {
            withContext(Dispatchers.IO) {
                val existing = webDavVideoMetadataDao.getByServerAndHrefs(
                    serverId = server.id,
                    hrefs = videoItems.map { it.href },
                ).associateBy { it.href }.toMutableMap()
                val existingLock = Any()

                val now = System.currentTimeMillis()

                val (apiThumbItems, localThumbItems) = videoItems.partition { it.apiThumbnailUrl != null }
                if (apiThumbItems.isNotEmpty()) {
                    val apiMetadataSemaphore = Semaphore(metadataConcurrency())
                    apiThumbItems
                        .filter { item ->
                            val cached = existing[item.href]
                            val hasValidThumbnail = cached?.thumbnailPath?.let { path ->
                                path == item.apiThumbnailUrl || File(path).exists()
                            } == true
                            cached == null || !hasValidThumbnail || cached.durationMs <= 0L
                        }
                        .map { item ->
                            async {
                                apiMetadataSemaphore.withPermit {
                                    val extension = item.name.substringAfterLast('.', "")
                                    val rawUrl = item.rawVideoUrl
                                    Logger.d(TAG, "[API_THUMB] name=${item.name} rawUrl=${rawUrl?.take(100)}...")
                                    val durationMs = if (rawUrl != null) {
                                        probeVideoDurationMs(rawUrl, okHttpClient, extension, mp4KeyframeExtractor) ?: 0L
                                    } else {
                                        0L
                                    }
                                    Logger.d(TAG, "[API_THUMB] name=${item.name} durationMs=$durationMs")

                                    val localThumbPath = item.apiThumbnailUrl?.let { url ->
                                        downloadApiThumbnail(url, "${server.id}|${item.href}")
                                    }
                                    WebDavVideoMetadataEntity(
                                        serverId = server.id,
                                        href = item.href,
                                        durationMs = durationMs,
                                        thumbnailPath = localThumbPath ?: item.apiThumbnailUrl,
                                        width = item.width ?: 0,
                                        height = item.height ?: 0,
                                        updatedAt = now,
                                    ).also { entity ->
                                        webDavVideoMetadataDao.upsertAll(listOf(entity))
                                        synchronized(existingLock) {
                                            existing[entity.href] = entity
                                        }
                                    }
                                }
                            }
                        }
                        .awaitAll()
                }

                if (localThumbItems.isEmpty()) {
                    return@withContext apiThumbItems.isNotEmpty()
                }

                val orphanRecoveries = mutableListOf<WebDavVideoMetadataEntity>()
                for (item in localThumbItems) {
                    val cached = existing[item.href]
                    val hasValidThumbnail = cached?.thumbnailPath?.let { File(it).exists() } ?: false
                    if (!hasValidThumbnail) {
                        val expectedFile = existingThumbnailFile("${server.id}|${item.href}")
                        if (expectedFile.exists()) {
                            orphanRecoveries.add(
                                WebDavVideoMetadataEntity(
                                    serverId = server.id,
                                    href = item.href,
                                    durationMs = cached?.durationMs?.takeIf { it > 0L } ?: 0L,
                                    thumbnailPath = expectedFile.absolutePath,
                                    width = item.width ?: 0,
                                    height = item.height ?: 0,
                                    updatedAt = now,
                                ),
                            )
                        }
                    }
                }
                if (orphanRecoveries.isNotEmpty()) {
                    webDavVideoMetadataDao.upsertAll(orphanRecoveries)
                    for (entity in orphanRecoveries) {
                        val prev = existing[entity.href]
                        val prevThumbnailPath = prev?.thumbnailPath
                        if (prev == null || prevThumbnailPath.isNullOrBlank() || !File(prevThumbnailPath).exists()) {
                            existing[entity.href] = entity
                        }
                    }
                }

                val durationMissingItems = localThumbItems.filter {
                    (existing[it.href]?.durationMs ?: 0L) <= 0L
                }
                val durationProbeJobs = if (durationMissingItems.isNotEmpty()) {
                    val durationSemaphore = Semaphore(durationProbeConcurrency())
                    durationMissingItems.map { item ->
                        async {
                            durationSemaphore.withPermit {
                                val probeUrl = item.rawVideoUrl
                                    ?: webDavRepository.getStreamUrl(item, server)
                                val extension = item.name.substringAfterLast('.', "")
                                Logger.d(TAG, "[LOCAL_THUMB] name=${item.name} probeUrl=${probeUrl.take(100)}...")
                                val durationMs = probeVideoDurationMs(probeUrl, okHttpClient, extension, mp4KeyframeExtractor) ?: 0L
                                Logger.d(TAG, "[LOCAL_THUMB] name=${item.name} durationMs=$durationMs")
                                if (durationMs <= 0L) {
                                    return@withPermit 0
                                }
                                val entity = WebDavVideoMetadataEntity(
                                    serverId = server.id,
                                    href = item.href,
                                    durationMs = durationMs,
                                    thumbnailPath = synchronized(existingLock) { existing[item.href]?.thumbnailPath },
                                    width = item.width ?: 0,
                                    height = item.height ?: 0,
                                    updatedAt = System.currentTimeMillis(),
                                )
                                webDavVideoMetadataDao.upsertAll(listOf(entity))
                                synchronized(existingLock) {
                                    existing[entity.href] = entity
                                }
                                1
                            }
                        }
                    }
                } else {
                    emptyList()
                }

                val itemsToRefresh = localThumbItems.filter { item ->
                    val cached = existing[item.href]
                    val thumbnailPath = cached?.thumbnailPath
                    when {
                        cached == null -> true
                        cached.durationMs <= 0L -> true
                        thumbnailPath.isNullOrBlank() -> true
                        !File(thumbnailPath).exists() -> true
                        else -> false
                    }
                }
                if (itemsToRefresh.isEmpty()) {
                    durationProbeJobs.awaitAll()
                    return@withContext apiThumbItems.isNotEmpty()
                }

                val semaphore = Semaphore(metadataConcurrency())
                val entities = itemsToRefresh.map { item ->
                    async {
                        semaphore.withPermit {
                            currentCoroutineContext().ensureActive()
                            val existingEntry = existing[item.href]
                            val retryKey = "${server.id}|${item.href}"
                            val shouldRetryNow = synchronized(metadataRetryLock) {
                                (metadataRetryAfterMs[retryKey] ?: 0L) <= now
                            }
                            val captured = if (shouldRetryNow) {
                                try {
                                    captureMetadata(server, item)
                                } catch (e: Exception) {
                                    if (e is CancellationException) throw e
                                    Logger.w(TAG, "captureMetadata: unhandled exception server=${server.id} href=${item.href}", e)
                                    CapturedMetadata(durationMs = 0L, thumbnailPath = null)
                                }
                            } else {
                                CapturedMetadata(durationMs = 0L, thumbnailPath = null)
                            }
                            val latestEntry = synchronized(existingLock) { existing[item.href] }
                            val resolvedDurationMs = when {
                                captured.durationMs > 0L -> captured.durationMs
                                (latestEntry?.durationMs ?: 0L) > 0L -> latestEntry?.durationMs ?: 0L
                                (existingEntry?.durationMs ?: 0L) > 0L -> existingEntry?.durationMs ?: 0L
                                else -> 0L
                            }
                            val existingThumbnailPath = (latestEntry?.thumbnailPath ?: existingEntry?.thumbnailPath)?.takeIf {
                                !it.isNullOrBlank() && File(it).exists()
                            }
                            val resolvedThumbnailPath = captured.thumbnailPath ?: existingThumbnailPath
                            val captureFailed = captured.durationMs <= 0L && captured.thumbnailPath.isNullOrBlank()
                            synchronized(metadataRetryLock) {
                                if (!shouldRetryNow) {
                                } else if (captureFailed) {
                                    metadataRetryAfterMs[retryKey] = now + METADATA_RETRY_BACKOFF_MS
                                } else {
                                    metadataRetryAfterMs.remove(retryKey)
                                }
                            }
                            val entity = WebDavVideoMetadataEntity(
                                serverId = server.id,
                                href = item.href,
                                durationMs = resolvedDurationMs,
                                thumbnailPath = resolvedThumbnailPath,
                                width = item.width ?: captured.width ?: 0,
                                height = item.height ?: captured.height ?: 0,
                                updatedAt = now,
                            )
                            runCatching {
                                webDavVideoMetadataDao.upsertAll(listOf(entity))
                                synchronized(existingLock) {
                                    existing[entity.href] = entity
                                }
                            }.onFailure { e ->
                                Logger.w(TAG, "cacheMissingMetadata: upsert failed server=${server.id} href=${item.href}", e)
                            }
                            if (resolvedDurationMs > 0L || !resolvedThumbnailPath.isNullOrBlank()) {
                                runCatching {
                                    if (!resolvedThumbnailPath.isNullOrBlank()) {
                                        val thumbnailCacheKey = if (resolvedThumbnailPath.startsWith(
                                                "http://",
                                                true,
                                            ) ||
                                            resolvedThumbnailPath.startsWith("https://", true)
                                        ) {
                                            resolvedThumbnailPath
                                        } else {
                                            Uri.fromFile(File(resolvedThumbnailPath)).toString()
                                        }
                                        imageLoader.memoryCache?.remove(MemoryCache.Key(thumbnailCacheKey))
                                    }
                                }
                            }
                            entity
                        }
                    }
                }.awaitAll()

                durationProbeJobs.awaitAll()
                return@withContext apiThumbItems.isNotEmpty() || entities.isNotEmpty()
            }
        } finally {
            synchronized(metadataInFlightLock) {
                metadataInFlightKeys.remove(inFlightKey)
            }
        }
    }

    override suspend fun getFolderMetadata(
        serverId: Int,
        folderPaths: List<String>,
    ): Map<String, CloudFolderMetadata> {
        if (folderPaths.isEmpty()) return emptyMap()
        return withContext(Dispatchers.IO) {
            folderPaths.distinct().chunked(SQL_BIND_CHUNK_SIZE).flatMap { chunk ->
                webDavFolderMetadataDao.getByServerAndPaths(serverId, chunk)
            }
                .associate { entity ->
                    entity.folderPath to CloudFolderMetadata(
                        totalDurationMs = entity.totalDurationMs,
                        totalSize = entity.totalSize,
                        mediaCount = entity.mediaCount,
                        folderCount = entity.folderCount,
                        coverImageUri = entity.coverImageUri,
                        videoCount = entity.videoCount,
                        imageCount = entity.imageCount,
                    )
                }
        }
    }

    override suspend fun saveFolderMetadata(
        serverId: Int,
        folderPath: String,
        totalDurationMs: Long,
        totalSize: Long,
        mediaCount: Int,
        folderCount: Int,
        coverImageUri: String?,
        videoCount: Int,
        imageCount: Int,
    ) {
        withContext(Dispatchers.IO) {
            webDavFolderMetadataDao.upsert(
                WebDavFolderMetadataEntity(
                    serverId = serverId,
                    folderPath = folderPath,
                    totalDurationMs = totalDurationMs,
                    totalSize = totalSize,
                    mediaCount = mediaCount,
                    folderCount = folderCount,
                    coverImageUri = coverImageUri,
                    updatedAt = System.currentTimeMillis(),
                    videoCount = videoCount,
                    imageCount = imageCount,
                ),
            )
        }
    }

    private suspend fun captureMetadata(server: WebDavServer, item: WebDavMediaItem): CapturedMetadata {
        val streamUrl = webDavRepository.getStreamUrl(item, server)
        val extension = item.name.substringAfterLast('.', "").lowercase()
        val preferences = preferencesRepository.applicationPreferences.first()
        val thumbnailGenerationStrategy = preferences.thumbnailGenerationStrategy
        val thumbnailFramePosition = preferences.thumbnailFramePosition

        if (extension in BINARY_MP4_EXTENSIONS && shouldUseBinaryKeyframeExtractor(streamUrl)) {
            val binaryStartMs = System.currentTimeMillis()
            val targetPercent = when (thumbnailGenerationStrategy) {
                ThumbnailGenerationStrategy.FIRST_FRAME -> 0.00f
                ThumbnailGenerationStrategy.FRAME_AT_PERCENTAGE -> thumbnailFramePosition
                ThumbnailGenerationStrategy.HYBRID -> 0.00f
            }
            val strategyLabel = "$thumbnailGenerationStrategy target=${(targetPercent * 100).toInt()}%"

            val binaryResult = try {
                kotlinx.coroutines.withTimeout(15000L) {
                    mp4KeyframeExtractor.extractKeyframeWithMetadata(streamUrl, targetPercent)
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } catch (e: OutOfMemoryError) {
                val elapsed = System.currentTimeMillis() - binaryStartMs
                Logger.w(TAG, "captureMetadata: binary MP4 OOM after ${elapsed}ms server=${server.id} href=${item.href}", e)
                null
            }

            val finalResult = if (thumbnailGenerationStrategy == ThumbnailGenerationStrategy.HYBRID && binaryResult != null) {
                if (binaryResult.bitmap.isMostlySolidColor()) {
                    val retryResult = try {
                        kotlinx.coroutines.withTimeout(15000L) {
                            mp4KeyframeExtractor.extractKeyframeWithMetadata(streamUrl, thumbnailFramePosition)
                        }
                    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                        null
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    } catch (e: OutOfMemoryError) {
                        null
                    }
                    if (retryResult != null) {
                        binaryResult.bitmap.recycle()
                        retryResult
                    } else {
                        binaryResult
                    }
                } else {
                    binaryResult
                }
            } else {
                binaryResult
            }
            if (finalResult != null) {
                val bitmap = finalResult.bitmap
                val thumbnailPath = saveFirstFrame(server.id, item.href, bitmap)
                bitmap.recycle()
                if (!thumbnailPath.isNullOrBlank()) {
                    val elapsed = System.currentTimeMillis() - binaryStartMs

                    val durationMs = finalResult.durationMs ?: 0L
                    return CapturedMetadata(durationMs = durationMs, thumbnailPath = thumbnailPath, width = finalResult.width, height = finalResult.height)
                }
            }
            val elapsed = System.currentTimeMillis() - binaryStartMs
        }

        if (shouldSkipRemoteRetriever(streamUrl)) {
            return CapturedMetadata(durationMs = 0L, thumbnailPath = null)
        }

        val retrieverUrl = streamUrl.stripUserInfoFromHttpUrl()
        val candidates = buildDataSourceCandidates(server, retrieverUrl, streamUrl)

        candidates.forEach { candidate ->
            val result = try {
                Result.success(
                    kotlinx.coroutines.withTimeout(5000L) {
                        kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
                            extractMetadata(
                                server = server,
                                item = item,
                                dataSourceUrl = candidate.url,
                                headers = candidate.headers,
                                thumbnailGenerationStrategy = thumbnailGenerationStrategy,
                                thumbnailFramePosition = thumbnailFramePosition,
                            )
                        }
                    },
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (oom: OutOfMemoryError) {
                Logger.w(TAG, "captureMetadata: retriever OOM server=${server.id} mode=${candidate.label} href=${item.href}", oom)
                Result.failure(RuntimeException("Retriever OOM", oom))
            } catch (exception: Exception) {
                Result.failure(exception)
            }
            result.onSuccess { metadata ->
                if (metadata.durationMs > 0L || !metadata.thumbnailPath.isNullOrBlank()) {
                    return metadata
                }
                Logger.w(
                    TAG,
                    "captureMetadata: empty result server=${server.id} mode=${candidate.label} href=${item.href}",
                )
            }.onFailure { throwable ->
                Logger.w(
                    TAG,
                    "captureMetadata: failed server=${server.id} mode=${candidate.label} href=${item.href} error=${throwable.message}",
                    throwable,
                )
            }
        }

        return CapturedMetadata(durationMs = 0L, thumbnailPath = null)
    }

    private fun extractMetadata(
        server: WebDavServer,
        item: WebDavMediaItem,
        dataSourceUrl: String,
        headers: Map<String, String>,
        thumbnailGenerationStrategy: com.sakurafubuki.yume.core.model.ThumbnailGenerationStrategy,
        thumbnailFramePosition: Float,
    ): CapturedMetadata {
        val nativeResult = MediaMetadataRetriever().useRetriever { nativeRetriever ->
            if (!runCatching { nativeRetriever.setDataSource(dataSourceUrl, headers) }.isSuccess) {
                return@useRetriever null
            }
            val durationMs = runCatching {
                nativeRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            }.getOrNull()?.toLongOrNull() ?: 0L
            val nativeFrameTarget = resolveNativeFrameTarget(nativeRetriever, MAX_EDGE)
            val nativeWidth = runCatching {
                nativeRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            }.getOrNull()?.takeIf { it > 0 }
            val nativeHeight = runCatching {
                nativeRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            }.getOrNull()?.takeIf { it > 0 }

            val embedded = decodeEmbeddedPicture(
                runCatching { nativeRetriever.embeddedPicture }.getOrNull(),
            )
            if (embedded != null) {
                return@useRetriever NativeResult(durationMs, thumbnail = embedded, width = nativeWidth, height = nativeHeight)
            }

            val frame = tryGetFrame(
                nativeRetriever = nativeRetriever,
                durationMs = durationMs,
                strategy = thumbnailGenerationStrategy,
                framePosition = thumbnailFramePosition,
                isNative = true,
                nativeFrameTarget = nativeFrameTarget,
            )
            NativeResult(durationMs, thumbnail = frame, width = nativeWidth, height = nativeHeight)
        }

        if (nativeResult != null && nativeResult.thumbnail != null) {
            val thumbnailPath = saveFirstFrame(server.id, item.href, nativeResult.thumbnail)
            return CapturedMetadata(durationMs = nativeResult.durationMs, thumbnailPath = thumbnailPath, width = nativeResult.width, height = nativeResult.height)
        }

        val nativeDurationMs = nativeResult?.durationMs ?: 0L

        val ffmpegThumbnail = MediaThumbnailRetriever().useFfmpeg { ffmpegRetriever ->
            if (!runCatching { ffmpegRetriever.setDataSource(context, android.net.Uri.parse(dataSourceUrl)) }.isSuccess) {
                return@useFfmpeg if (nativeResult != null) {
                    nativeResult.thumbnail
                } else {
                    null
                }
            }

            val ffmpegDurationMs = if (nativeDurationMs <= 0L) {
                runCatching { ffmpegRetriever.getFrameAtTime(0) }
                0L
            } else {
                nativeDurationMs
            }

            val embedded = decodeEmbeddedPicture(
                runCatching { ffmpegRetriever.getEmbeddedPicture() }.getOrNull(),
            )
            if (embedded != null) return@useFfmpeg embedded

            tryGetFrame(ffmpegRetriever = ffmpegRetriever, durationMs = nativeDurationMs, strategy = thumbnailGenerationStrategy, framePosition = thumbnailFramePosition, isNative = false)
                ?: nativeResult?.thumbnail
        }

        val durationMs = nativeDurationMs
        val thumbnailPath = ffmpegThumbnail?.let { saveFirstFrame(server.id, item.href, it) }
        return CapturedMetadata(durationMs = durationMs, thumbnailPath = thumbnailPath, width = nativeResult?.width, height = nativeResult?.height)
    }

    private data class NativeResult(
        val durationMs: Long,
        val thumbnail: Bitmap?,
        val width: Int? = null,
        val height: Int? = null,
    )

    private fun decodeEmbeddedPicture(pictureBytes: ByteArray?): Bitmap? {
        if (pictureBytes == null) return null
        return runCatching {
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(pictureBytes, 0, pictureBytes.size, boundsOpts)
            val srcWidth = boundsOpts.outWidth
            val srcHeight = boundsOpts.outHeight
            if (srcWidth <= 0 || srcHeight <= 0) {
                BitmapFactory.decodeByteArray(pictureBytes, 0, pictureBytes.size)
            } else {
                var sampleSize = 1
                while (srcWidth / (sampleSize * 2) >= EMBEDDED_MAX_EDGE || srcHeight / (sampleSize * 2) >= EMBEDDED_MAX_EDGE) {
                    sampleSize *= 2
                }
                BitmapFactory.decodeByteArray(pictureBytes, 0, pictureBytes.size, BitmapFactory.Options().apply { inSampleSize = sampleSize })
            }
        }.getOrNull()
    }

    private fun tryGetFrame(
        nativeRetriever: MediaMetadataRetriever? = null,
        ffmpegRetriever: MediaThumbnailRetriever? = null,
        durationMs: Long,
        strategy: com.sakurafubuki.yume.core.model.ThumbnailGenerationStrategy,
        framePosition: Float,
        isNative: Boolean,
        nativeFrameTarget: Pair<Int, Int>? = null,
    ): Bitmap? = when (strategy) {
        com.sakurafubuki.yume.core.model.ThumbnailGenerationStrategy.FIRST_FRAME -> {
            if (isNative && nativeRetriever != null) {
                nativeRetriever.getFrameAtTimeScaled(0, nativeFrameTarget)
            } else if (!isNative && ffmpegRetriever != null) {
                runCatching { ffmpegRetriever.getFrameAtTime(0) }.getOrNull()
            } else {
                null
            }
        }
        com.sakurafubuki.yume.core.model.ThumbnailGenerationStrategy.FRAME_AT_PERCENTAGE -> {
            val timeUs = (durationMs * framePosition * 1000).toLong()
            if (isNative && nativeRetriever != null) {
                nativeRetriever.getFrameAtTimeScaled(timeUs, nativeFrameTarget)
            } else if (!isNative && ffmpegRetriever != null) {
                runCatching { ffmpegRetriever.getFrameAtTime(timeUs) }.getOrNull()
            } else {
                null
            }
        }
        com.sakurafubuki.yume.core.model.ThumbnailGenerationStrategy.HYBRID -> {
            if (isNative && nativeRetriever != null) {
                val probeFrame = nativeRetriever.getFrameAtTimeScaled(
                    timeUs = 0,
                    target = SOLID_PROBE_FRAME_SIZE to SOLID_PROBE_FRAME_SIZE,
                )
                val isProbeSolid = probeFrame?.let { probe ->
                    val solid = probe.isMostlySolidColor()
                    probe.recycle()
                    solid
                } ?: true

                if (isProbeSolid) {
                    val timeUs = (durationMs * framePosition * 1000).toLong()
                    nativeRetriever.getFrameAtTimeScaled(timeUs, nativeFrameTarget)
                        ?: nativeRetriever.getFrameAtTimeScaled(0, nativeFrameTarget)
                } else {
                    nativeRetriever.getFrameAtTimeScaled(0, nativeFrameTarget)
                }
            } else if (!isNative && ffmpegRetriever != null) {
                val firstFrame = runCatching { ffmpegRetriever.getFrameAtTime(0) }.getOrNull()
                if (firstFrame != null && !firstFrame.isMostlySolidColor()) {
                    firstFrame
                } else {
                    val timeUs = (durationMs * framePosition * 1000).toLong()
                    runCatching { ffmpegRetriever.getFrameAtTime(timeUs) }.getOrNull() ?: firstFrame
                }
            } else {
                null
            }
        }
    }

    private fun resolveNativeFrameTarget(
        retriever: MediaMetadataRetriever,
        maxEdge: Int,
    ): Pair<Int, Int>? {
        val rawWidth = runCatching {
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
        }.getOrNull() ?: return null
        val rawHeight = runCatching {
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
        }.getOrNull() ?: return null
        if (rawWidth <= 0 || rawHeight <= 0) return null

        val rotation = runCatching {
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()
        }.getOrNull() ?: 0
        val width = if (rotation == 90 || rotation == 270) rawHeight else rawWidth
        val height = if (rotation == 90 || rotation == 270) rawWidth else rawHeight
        val longestEdge = maxOf(width, height)
        if (longestEdge <= 0) return null
        val scale = minOf(1f, maxEdge.toFloat() / longestEdge.toFloat())
        return ((width * scale).toInt().coerceAtLeast(1)) to
            ((height * scale).toInt().coerceAtLeast(1))
    }

    private fun MediaMetadataRetriever.getFrameAtTimeScaled(
        timeUs: Long,
        target: Pair<Int, Int>?,
    ): Bitmap? {
        if (target != null && SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            val (targetWidth, targetHeight) = target
            return runCatching {
                getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, targetWidth, targetHeight)
            }.getOrNull()
                ?: runCatching {
                    getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, targetWidth, targetHeight)
                }.getOrNull()
        }
        return runCatching { getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) }.getOrNull()
            ?: runCatching { getFrameAtTime(timeUs) }.getOrNull()
    }

    private fun buildRetrieverHeaders(server: WebDavServer): Map<String, String> {
        if (server.username.isBlank()) return emptyMap()
        val credentials = if (server.password.isBlank()) {
            server.username
        } else {
            "${server.username}:${server.password}"
        }
        val token = Base64.encodeToString(credentials.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return mapOf("Authorization" to "Basic $token")
    }

    private fun buildDataSourceCandidates(
        server: WebDavServer,
        retrieverUrl: String,
        streamUrl: String,
    ): List<DataSourceCandidate> {
        val authHeaders = buildRetrieverHeaders(server)
        return buildList {
            add(DataSourceCandidate(url = retrieverUrl, headers = authHeaders, label = "sanitized+auth"))
            if (authHeaders.isNotEmpty()) {
                add(DataSourceCandidate(url = retrieverUrl, headers = emptyMap(), label = "sanitized+noauth"))
            }
            if (streamUrl != retrieverUrl) {
                add(DataSourceCandidate(url = streamUrl, headers = emptyMap(), label = "userinfo+noauth"))
            }
        }
    }

    private fun saveFirstFrame(serverId: Int, href: String, frame: Bitmap): String? = saveThumbnailBitmap("$serverId|$href", frame)

    private fun saveThumbnailBitmap(cacheKey: String, frame: Bitmap): String? = runCatching {
        val resized = try {
            resizeIfNeeded(frame, MAX_EDGE)
        } catch (_: OutOfMemoryError) {
            frame.recycle()
            throw RuntimeException("OOM resizing thumbnail for $cacheKey")
        }
        if (resized !== frame) {
            frame.recycle()
        }
        try {
            val outputFile = thumbnailFile(cacheKey)
            FileOutputStream(outputFile).use { output ->
                resized.compress(Bitmap.CompressFormat.WEBP_LOSSY, WEBP_QUALITY, output)
            }
            outputFile.setLastModified(System.currentTimeMillis())
            outputFile.absolutePath
        } finally {
            resized.recycle()
        }
    }.getOrNull()

    private fun cloudThumbnailCacheDir(): File = File(context.cacheDir, CLOUD_THUMBNAILS_DIR).apply { mkdirs() }

    private fun thumbnailFile(cacheKey: String, extension: String = THUMBNAIL_EXTENSION): File = File(cloudThumbnailCacheDir(), "${sha256(cacheKey)}.$extension")

    private fun existingThumbnailFile(cacheKey: String): File {
        val webpFile = thumbnailFile(cacheKey)
        if (webpFile.exists()) return webpFile
        val legacyFile = thumbnailFile(cacheKey, LEGACY_THUMBNAIL_EXTENSION)
        return legacyFile.takeIf { it.exists() } ?: webpFile
    }

    private fun downloadApiThumbnail(imageUrl: String, cacheKey: String): String? = runCatching {
        val outputFile = thumbnailFile(cacheKey)
        if (outputFile.exists()) return outputFile.absolutePath

        val legacyFile = thumbnailFile(cacheKey, LEGACY_THUMBNAIL_EXTENSION)
        if (legacyFile.exists()) {
            BitmapFactory.decodeFile(legacyFile.absolutePath)?.let { legacyBitmap ->
                saveThumbnailBitmap(cacheKey, legacyBitmap)?.let { return it }
            }
        }

        val request = Request.Builder().url(imageUrl).build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body ?: return null
            val bytes = body.bytes()
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            saveThumbnailBitmap(cacheKey, bitmap)?.let { return it }
        }
        null
    }.getOrNull()

    private fun resizeIfNeeded(source: Bitmap, maxEdge: Int): Bitmap {
        val srcWidth = source.width
        val srcHeight = source.height
        val longestEdge = maxOf(srcWidth, srcHeight)
        if (longestEdge <= maxEdge) return source

        val scale = maxEdge.toFloat() / longestEdge.toFloat()
        val dstWidth = (srcWidth * scale).toInt().coerceAtLeast(1)
        val dstHeight = (srcHeight * scale).toInt().coerceAtLeast(1)
        val argbSource = if (source.config == Bitmap.Config.ARGB_8888) {
            source
        } else {
            source.copy(Bitmap.Config.ARGB_8888, false) ?: source
        }
        return try {
            YuvToBitmapBridge.argbScale(argbSource, dstWidth, dstHeight, FilterMode.BOX)
                ?: Bitmap.createScaledBitmap(source, dstWidth, dstHeight, true)
        } finally {
            if (argbSource !== source) {
                argbSource.recycle()
            }
        }
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun metadataInFlightKey(serverId: Int, items: List<WebDavMediaItem>): String {
        val hrefDigest = sha256(items.map { it.href }.sorted().joinToString(separator = "\n")).take(16)
        return "$serverId:${items.size}:$hrefDigest"
    }

    private data class CapturedMetadata(
        val durationMs: Long,
        val thumbnailPath: String?,
        val width: Int? = null,
        val height: Int? = null,
    )

    private data class DataSourceCandidate(
        val url: String,
        val headers: Map<String, String>,
        val label: String,
    )

    private companion object {
        private const val TAG = "CloudVideoMeta"
        private const val CLOUD_THUMBNAILS_DIR = "thumbnails"
        private const val MAX_EDGE = 1024
        private const val SOLID_PROBE_FRAME_SIZE = 96
        private const val EMBEDDED_MAX_EDGE = 1024
        private const val THUMBNAIL_EXTENSION = "webp"
        private const val LEGACY_THUMBNAIL_EXTENSION = "jpg"
        private const val WEBP_QUALITY = 84
        private const val METADATA_RETRY_BACKOFF_MS = 3 * 60 * 1000L
        private val BINARY_MP4_EXTENSIONS = setOf("mp4", "mov", "m4v")

        private fun isRemoteHttpUrl(url: String): Boolean = url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

        private fun metadataConcurrency(): Int {
            val maxHeapMb = Runtime.getRuntime().maxMemory() / (1024 * 1024)
            return when {
                maxHeapMb < 384 -> 1
                maxHeapMb < 512 -> 2
                maxHeapMb < 1536 -> 3
                else -> 4
            }
        }

        private fun durationProbeConcurrency(): Int {
            val maxHeapMb = Runtime.getRuntime().maxMemory() / (1024 * 1024)
            return when {
                maxHeapMb < 384 -> 1
                maxHeapMb < 512 -> 3
                maxHeapMb < 1536 -> 4
                else -> 6
            }
        }

        private fun shouldUseBinaryKeyframeExtractor(url: String): Boolean {
            val maxHeapMb = Runtime.getRuntime().maxMemory() / (1024 * 1024)
            return isRemoteHttpUrl(url) || maxHeapMb >= 768
        }

        private fun shouldSkipRemoteRetriever(url: String): Boolean {
            val maxHeapMb = Runtime.getRuntime().maxMemory() / (1024 * 1024)
            return isRemoteHttpUrl(url) && maxHeapMb < 384
        }
    }
}

private inline fun <T> MediaMetadataRetriever.useRetriever(block: (MediaMetadataRetriever) -> T): T {
    try {
        return block(this)
    } finally {
        if (SDK_INT >= 29) {
            close()
        } else {
            release()
        }
    }
}

private inline fun <T> MediaThumbnailRetriever.useFfmpeg(block: (MediaThumbnailRetriever) -> T): T {
    try {
        return block(this)
    } finally {
        release()
    }
}

private const val SQL_BIND_CHUNK_SIZE = 900

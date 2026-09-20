package com.sakurafubuki.yume.core.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build.VERSION.SDK_INT
import android.os.SystemClock
import android.util.Base64
import android.util.LruCache
import coil3.ImageLoader
import coil3.memory.MemoryCache
import com.sakurafubuki.yume.core.common.Logger
import com.sakurafubuki.yume.core.common.extensions.stripUserInfoFromHttpUrl
import com.sakurafubuki.yume.core.data.webdav.WebDavRepository
import com.sakurafubuki.yume.core.data.webdav.stableWebDavUrl
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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

    private val metadataHttpClient by lazy {
        okHttpClient.newBuilder().callTimeout(20, java.util.concurrent.TimeUnit.SECONDS).build()
    }
    private val mp4KeyframeExtractor by lazy { Mp4KeyframeExtractor(metadataHttpClient) }

    private val metadataRetryLock = Any()

    // Bounded: entries only removed on success would otherwise accumulate for deleted files.
    private val metadataRetryAfterMs = LruCache<String, Long>(METADATA_RETRY_BACKOFF_MAX_ENTRIES)
    private val metadataQueue = MetadataWorkQueue(metadataConcurrency())
    private val metadataMemoryCache = LruCache<String, WebDavVideoMetadataEntity>(MEMORY_METADATA_CACHE_SIZE)

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
        val source = if (distinctHrefs.size > SQL_BIND_CHUNK_SIZE) {
            combine(
                distinctHrefs.chunked(SQL_BIND_CHUNK_SIZE).map { chunk ->
                    webDavVideoMetadataDao.observeByServerAndHrefs(serverId, chunk)
                },
            ) { chunks -> chunks.flatMap { it.asIterable() } }
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

    override suspend fun cacheMissingMetadata(
        server: WebDavServer,
        items: List<WebDavMediaItem>,
        forceRetry: Boolean,
        priority: MetadataRequestPriority,
    ): Boolean = withContext(Dispatchers.IO) {
        val videos = items.filter { it.isVideo && !it.isDirectory }.distinctBy { it.href }
        val batchStartMs = SystemClock.elapsedRealtime()
        val cachedByHref = videos.map { it.href }.chunked(SQL_BIND_CHUNK_SIZE).flatMap { hrefs ->
            webDavVideoMetadataDao.getByServerAndHrefs(server.id, hrefs)
        }.associateBy { it.href }
        cachedByHref.forEach { (href, entity) ->
            val cacheKey = metadataCacheKey(server.id, href)
            metadataMemoryCache.put(cacheKey, mergeMetadataEntity(metadataMemoryCache.get(cacheKey), entity))
        }
        // Items whose metadata is already complete (or currently in retry backoff) have no
        // work to do; skip them before they cost queue slots, locks and wakeups.
        val pending = videos.filter { item ->
            val cached = metadataMemoryCache.get(metadataCacheKey(server.id, item.href)) ?: cachedByHref[item.href]
            val hasThumbnail = cached?.thumbnailPath?.takeIf { !isRemoteHttpUrl(it) && File(it).length() > 0L } != null
            val hasDuration = (cached?.durationMs ?: 0L) > 0L
            if (hasThumbnail && hasDuration) return@filter false
            if (forceRetry) return@filter true
            val retryKey = "${server.id}|${item.href}"
            synchronized(metadataRetryLock) {
                (metadataRetryAfterMs[retryKey] ?: 0L) <= System.currentTimeMillis()
            }
        }
        val preloadMs = SystemClock.elapsedRealtime() - batchStartMs
        Logger.i(PERF_TAG, "[MD_BATCH] start server=${server.id} priority=$priority total=${videos.size} needed=${pending.size} preloadMs=$preloadMs")
        val waitStats = longArrayOf(0L, 0L)
        var changedCount = 0
        var completedCount = 0
        val submittedAtMs = SystemClock.elapsedRealtime()
        metadataQueue.process(pending, key = { "${server.id}|${it.href}" }, priority = priority) { item ->
            val waitMs = SystemClock.elapsedRealtime() - submittedAtMs
            val timings = MetadataItemTimings()
            try {
                val changed = cacheVideoMetadata(
                    server = server,
                    item = item,
                    forceRetry = forceRetry,
                    cached = metadataMemoryCache.get(metadataCacheKey(server.id, item.href)) ?: cachedByHref[item.href],
                    timings = timings,
                )
                synchronized(waitStats) {
                    waitStats[0] += waitMs
                    waitStats[1] = maxOf(waitStats[1], waitMs)
                    completedCount++
                    if (changed) changedCount++
                }
                Logger.i(
                    PERF_TAG,
                    "[MD_ITEM] server=${server.id} name=${item.name} waitMs=$waitMs probeMs=${timings.probeMs} " +
                        "thumbMs=${timings.existingThumbMs}+${timings.apiThumbMs} captureMs=${timings.captureMs} " +
                        "writeMs=${timings.writeMs} writes=${timings.writes} changed=$changed",
                )
                changed
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                synchronized(waitStats) {
                    waitStats[0] += waitMs
                    waitStats[1] = maxOf(waitStats[1], waitMs)
                    completedCount++
                }
                Logger.w(TAG, "Metadata failed server=${server.id} name=${item.name}", error)
                Logger.i(PERF_TAG, "[MD_ITEM] server=${server.id} name=${item.name} waitMs=$waitMs FAILED error=${error.javaClass.simpleName}")
                false
            }
        }.also {
            val wallMs = SystemClock.elapsedRealtime() - batchStartMs
            Logger.i(
                PERF_TAG,
                "[MD_BATCH] done server=${server.id} priority=$priority total=${videos.size} needed=${pending.size} changed=$changedCount " +
                    "wallMs=$wallMs waitAvgMs=${if (completedCount > 0) waitStats[0] / completedCount else 0L} waitMaxMs=${waitStats[1]}",
            )
        }
    }

    // Stage-level timings for one metadata item, aggregated into [MD_ITEM] logs.
    private class MetadataItemTimings {
        var probeMs = 0L
        var existingThumbMs = 0L
        var apiThumbMs = 0L
        var captureMs = 0L
        var writeMs = 0L
        var writes = 0
    }

    private suspend fun cacheVideoMetadata(
        server: WebDavServer,
        item: WebDavMediaItem,
        forceRetry: Boolean,
        cached: WebDavVideoMetadataEntity?,
        timings: MetadataItemTimings = MetadataItemTimings(),
    ): Boolean {
        var duration = cached?.durationMs ?: 0L
        var thumbnail = cached?.thumbnailPath?.takeIf { !isRemoteHttpUrl(it) && File(it).length() > 0L }
        var width = cached?.width?.takeIf { it > 0 } ?: item.width ?: 0
        var height = cached?.height?.takeIf { it > 0 } ?: item.height ?: 0
        if (duration > 0L && thumbnail != null) return false
        val retryKey = "${server.id}|${item.href}"
        if (!forceRetry &&
            synchronized(metadataRetryLock) {
                (metadataRetryAfterMs[retryKey] ?: 0L) > System.currentTimeMillis()
            }
        ) {
            return false
        }
        var changed = false
        suspend fun persist() {
            currentCoroutineContext().ensureActive()
            val entity = WebDavVideoMetadataEntity(
                serverId = server.id,
                href = item.href,
                durationMs = duration,
                thumbnailPath = thumbnail,
                width = width,
                height = height,
                updatedAt = System.currentTimeMillis(),
            )
            val writeStartMs = SystemClock.elapsedRealtime()
            webDavVideoMetadataDao.mergeMetadata(
                entity.serverId,
                entity.href,
                entity.durationMs,
                entity.thumbnailPath,
                entity.width,
                entity.height,
                entity.updatedAt,
            )
            timings.writeMs += SystemClock.elapsedRealtime() - writeStartMs
            timings.writes++
            val cacheKey = metadataCacheKey(server.id, item.href)
            metadataMemoryCache.put(cacheKey, mergeMetadataEntity(metadataMemoryCache.get(cacheKey), entity))
            // Re-captured thumbnails overwrite the same deterministic path; drop any stale
            // decoded bitmap Coil still holds for that file URI.
            thumbnail?.let { path ->
                val imageKey = if (isRemoteHttpUrl(path)) path else Uri.fromFile(File(path)).toString()
                imageLoader.memoryCache?.remove(MemoryCache.Key(imageKey))
            }
            changed = true
        }

        // Duration is visible information and cheap with Range-capable servers. Publish it
        // before thumbnail work so a slow/failed image endpoint cannot leave a cover-only row.
        if (duration <= 0L) {
            val url = item.rawVideoUrl ?: webDavRepository.getStreamUrl(item, server)
            val probeStartMs = SystemClock.elapsedRealtime()
            duration = probeVideoDurationMs(
                url,
                metadataHttpClient,
                item.name.substringAfterLast('.', ""),
                mp4KeyframeExtractor,
            ) ?: 0L
            timings.probeMs += SystemClock.elapsedRealtime() - probeStartMs
            if (duration > 0L) persist()
        }
        if (thumbnail == null) {
            val thumbStartMs = SystemClock.elapsedRealtime()
            val existing = existingThumbnailFile(stableWebDavUrl(item.href), item.name)
                .takeIf { it.length() > 0L }?.absolutePath
            timings.existingThumbMs += SystemClock.elapsedRealtime() - thumbStartMs
            thumbnail = existing
            val apiThumbnailUrl = item.apiThumbnailUrl
            if (thumbnail == null && apiThumbnailUrl != null) {
                val apiStartMs = SystemClock.elapsedRealtime()
                val downloaded = downloadApiThumbnail(apiThumbnailUrl, stableWebDavUrl(item.href), item.name)
                timings.apiThumbMs += SystemClock.elapsedRealtime() - apiStartMs
                thumbnail = downloaded
            }
            if (thumbnail != null) persist()
        }

        if (duration <= 0L || thumbnail == null) {
            val captureStartMs = SystemClock.elapsedRealtime()
            val captured = captureMetadata(server, item)
            timings.captureMs += SystemClock.elapsedRealtime() - captureStartMs
            duration = captured.durationMs.takeIf { it > 0L } ?: duration
            thumbnail = thumbnail ?: captured.thumbnailPath
            width = item.width?.takeIf { it > 0 } ?: captured.width?.takeIf { it > 0 } ?: width
            height = item.height?.takeIf { it > 0 } ?: captured.height?.takeIf { it > 0 } ?: height
            if (duration > 0L || thumbnail != null) persist()
        }
        synchronized(metadataRetryLock) {
            if (duration > 0L && thumbnail != null) {
                metadataRetryAfterMs.remove(retryKey)
            } else {
                metadataRetryAfterMs.put(retryKey, System.currentTimeMillis() + METADATA_RETRY_BACKOFF_MS)
            }
        }
        return changed
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
                val thumbnailPath = saveFirstFrame(server.id, item.href, item.name, bitmap)
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
            } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                Result.failure(timeout)
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
            val thumbnailPath = saveFirstFrame(server.id, item.href, item.name, nativeResult.thumbnail)
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
        val thumbnailPath = ffmpegThumbnail?.let { saveFirstFrame(server.id, item.href, item.name, it) }
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

    private fun saveFirstFrame(
        serverId: Int,
        href: String,
        mediaName: String,
        frame: Bitmap,
    ): String? = saveThumbnailBitmap(stableWebDavUrl(href), mediaName, frame)

    private fun saveThumbnailBitmap(
        cacheKey: String,
        mediaName: String,
        frame: Bitmap,
    ): String? = runCatching {
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
            val outputFile = thumbnailFile(cacheKey, mediaName)
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

    private fun thumbnailFile(
        cacheKey: String,
        mediaName: String,
        extension: String = THUMBNAIL_EXTENSION,
    ): File = File(
        cloudThumbnailCacheDir(),
        "${thumbnailBaseName(mediaName)}_${sha256(cacheKey).take(THUMBNAIL_HASH_LENGTH)}.$extension",
    )

    private fun legacyThumbnailFile(cacheKey: String, extension: String): File = File(cloudThumbnailCacheDir(), "${sha256(cacheKey)}.$extension")

    private fun existingThumbnailFile(cacheKey: String, mediaName: String): File {
        val webpFile = thumbnailFile(cacheKey, mediaName)
        if (webpFile.exists()) return webpFile
        val legacyHashedWebp = legacyThumbnailFile(cacheKey, THUMBNAIL_EXTENSION)
        if (legacyHashedWebp.exists()) return legacyHashedWebp
        val namedJpegFile = thumbnailFile(cacheKey, mediaName, LEGACY_THUMBNAIL_EXTENSION)
        if (namedJpegFile.exists()) return namedJpegFile
        val legacyHashedJpeg = legacyThumbnailFile(cacheKey, LEGACY_THUMBNAIL_EXTENSION)
        return legacyHashedJpeg.takeIf { it.exists() } ?: webpFile
    }

    private fun downloadApiThumbnail(
        imageUrl: String,
        cacheKey: String,
        mediaName: String,
    ): String? = runCatching {
        val outputFile = thumbnailFile(cacheKey, mediaName)
        if (outputFile.exists()) return outputFile.absolutePath

        val legacyFile = existingThumbnailFile(cacheKey, mediaName)
        if (legacyFile.exists()) {
            BitmapFactory.decodeFile(legacyFile.absolutePath)?.let { legacyBitmap ->
                saveThumbnailBitmap(cacheKey, mediaName, legacyBitmap)?.let { return it }
            }
        }

        val request = Request.Builder().url(imageUrl).build()
        metadataHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Logger.w(TAG, "API thumbnail HTTP ${response.code} name=$mediaName")
                return null
            }
            val body = response.body ?: run {
                Logger.w(TAG, "API thumbnail empty response name=$mediaName")
                return null
            }
            val bytes = body.bytes()
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: run {
                Logger.w(TAG, "API thumbnail decode failed name=$mediaName contentType=${body.contentType()}")
                return null
            }
            saveThumbnailBitmap(cacheKey, mediaName, bitmap)?.let { return it }
        }
        null
    }.onFailure { error ->
        Logger.w(TAG, "API thumbnail request failed name=$mediaName error=${error.message}", error)
    }.getOrNull()

    private fun thumbnailBaseName(mediaName: String): String {
        val nameWithoutExtension = mediaName.substringBeforeLast('.', mediaName)
        return nameWithoutExtension
            .replace(INVALID_FILENAME_CHARS, "_")
            .trim(' ', '.')
            .take(MAX_THUMBNAIL_BASENAME_LENGTH)
            .ifBlank { DEFAULT_THUMBNAIL_BASENAME }
    }

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
        private const val PERF_TAG = "MetaPerf"
        private const val CLOUD_THUMBNAILS_DIR = "thumbnails"
        private const val MAX_EDGE = 1024
        private const val SOLID_PROBE_FRAME_SIZE = 96
        private const val EMBEDDED_MAX_EDGE = 1024
        private const val THUMBNAIL_EXTENSION = "webp"
        private const val LEGACY_THUMBNAIL_EXTENSION = "jpg"
        private const val WEBP_QUALITY = 84
        private const val THUMBNAIL_HASH_LENGTH = 12
        private const val MAX_THUMBNAIL_BASENAME_LENGTH = 80
        private const val DEFAULT_THUMBNAIL_BASENAME = "video"
        private const val METADATA_RETRY_BACKOFF_MS = 3 * 60 * 1000L
        private const val METADATA_RETRY_BACKOFF_MAX_ENTRIES = 4096
        private const val MEMORY_METADATA_CACHE_SIZE = 8192
        private val BINARY_MP4_EXTENSIONS = setOf("mp4", "mov", "m4v")
        private val INVALID_FILENAME_CHARS = Regex("[<>:\"/\\\\|?*\\u0000-\\u001F]")

        private fun isRemoteHttpUrl(url: String): Boolean = url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

        private fun metadataCacheKey(serverId: Int, href: String): String = "$serverId|$href"

        private fun mergeMetadataEntity(
            previous: WebDavVideoMetadataEntity?,
            incoming: WebDavVideoMetadataEntity,
        ): WebDavVideoMetadataEntity = incoming.copy(
            durationMs = incoming.durationMs.takeIf { it > 0L } ?: previous?.durationMs ?: 0L,
            thumbnailPath = incoming.thumbnailPath?.takeIf { it.isNotBlank() } ?: previous?.thumbnailPath,
            width = incoming.width.takeIf { it > 0 } ?: previous?.width ?: 0,
            height = incoming.height.takeIf { it > 0 } ?: previous?.height ?: 0,
            updatedAt = maxOf(previous?.updatedAt ?: 0L, incoming.updatedAt),
        )

        private fun metadataConcurrency(): Int {
            val maxHeapMb = Runtime.getRuntime().maxMemory() / (1024 * 1024)
            return when {
                maxHeapMb < 384 -> 1
                maxHeapMb < 512 -> 2
                maxHeapMb < 1536 -> 3
                else -> 4
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

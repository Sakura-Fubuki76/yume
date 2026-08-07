package com.sakurafubuki.yume

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import coil3.request.crossfade
import com.sakurafubuki.yume.core.cache.ImageCacheManager
import com.sakurafubuki.yume.core.common.Logger
import com.sakurafubuki.yume.core.common.di.ApplicationScope
import com.sakurafubuki.yume.core.data.repository.PreferencesRepository
import com.sakurafubuki.yume.core.data.repository.WebDavServerRepository
import com.sakurafubuki.yume.core.model.ThumbnailGenerationStrategy
import com.sakurafubuki.yume.core.model.WebDavServer
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okio.FileSystem

@Singleton
class AppImageLoaderFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferencesRepository: PreferencesRepository,
    private val webDavServerRepository: WebDavServerRepository,
    @ApplicationScope private val applicationScope: CoroutineScope,
) {

    private companion object {
        private const val IMAGE_CACHE_DIR = "image_cache"
        private const val THUMBNAILS_CACHE_DIR = "thumbnails"
        private const val VIDEO_METADATA_PROCESS_DIR = "video_metadata_process"

        private fun thumbnailCacheBytes(): Long {
            val maxHeapMb = Runtime.getRuntime().maxMemory() / (1024 * 1024)
            val mb = when {
                maxHeapMb < 384 -> 256
                maxHeapMb < 768 -> 512
                else -> 1024
            }
            return mb.toLong() * 1024 * 1024
        }

        private fun imageNetworkDispatcher(): okhttp3.Dispatcher {
            val maxHeapMb = Runtime.getRuntime().maxMemory() / (1024 * 1024)
            val maxRequests = when {
                maxHeapMb < 384 -> 3
                maxHeapMb < 768 -> 8
                maxHeapMb < 1536 -> 12
                else -> 20
            }
            val maxRequestsPerHost = when {
                maxHeapMb < 384 -> 1
                maxHeapMb < 768 -> 4
                maxHeapMb < 1536 -> 6
                else -> 10
            }
            return okhttp3.Dispatcher().apply {
                this.maxRequests = maxRequests
                this.maxRequestsPerHost = maxRequestsPerHost
            }
        }

        private fun imageConnectionPool(): okhttp3.ConnectionPool {
            val maxHeapMb = Runtime.getRuntime().maxMemory() / (1024 * 1024)
            val idleConnections = when {
                maxHeapMb < 384 -> 1
                maxHeapMb < 768 -> 4
                maxHeapMb < 1536 -> 6
                else -> 10
            }
            return okhttp3.ConnectionPool(idleConnections, 5, java.util.concurrent.TimeUnit.MINUTES)
        }
    }

    @Volatile
    private var webDavServersById: Map<Int, WebDavServer> = emptyMap()

    init {
        applicationScope.launch(Dispatchers.IO) {
            webDavServerRepository.observeServers().collect { servers ->
                webDavServersById = servers.associateBy { it.id }
            }
        }
    }

    fun create(diskCacheSizeMb: Int? = null): ImageLoader {
        val imageNetworkClient = OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .dispatcher(imageNetworkDispatcher())
            .connectionPool(imageConnectionPool())
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor { chain ->
                val request = chain.request()
                if (request.header("Authorization") != null) {
                    return@addInterceptor chain.proceed(request)
                }

                val authHeader = buildAuthorizationHeader(request.url)
                val authenticatedRequest = if (authHeader != null) {
                    request.newBuilder()
                        .header("Authorization", authHeader)
                        .build()
                } else {
                    request
                }
                chain.proceed(authenticatedRequest)
            }
            .authenticator { _, response ->
                val request = response.request
                if (request.header("Authorization") != null) {
                    return@authenticator null
                }
                val authHeader = buildAuthorizationHeader(request.url) ?: return@authenticator null
                request.newBuilder()
                    .header("Authorization", authHeader)
                    .build()
            }
            .build()

        val preferences = preferencesRepository.applicationPreferences.value
        val appliedCacheSizeMb = diskCacheSizeMb ?: preferences.diskCacheSizeMb
        val cloudDiskCacheEnabled = preferences.imageCloudDiskCacheEnabled && appliedCacheSizeMb > 0
        val thumbnailCacheMaxBytes = thumbnailCacheBytes()
        val memoryCachePercent = preferences.imageBrowserMemoryCachePercent
            .coerceIn(10, 40)
        val appliedMemoryCacheBytes = ImageCacheManager.memoryCacheBytesFromRamPercent(context, memoryCachePercent)
        ImageCacheManager.clearLegacyCoilDefaultImageCache(context)

        val localThumbnailDiskCache = lazy {
            DiskCache.Builder()
                .fileSystem(FileSystem.SYSTEM)
                .directory(context.cacheDir.resolve(THUMBNAILS_CACHE_DIR))
                .maxSizeBytes(thumbnailCacheMaxBytes)
                .build()
        }
        val remoteThumbnailDiskCache = lazy {
            DiskCache.Builder()
                .fileSystem(FileSystem.SYSTEM)
                .directory(context.cacheDir.resolve(VIDEO_METADATA_PROCESS_DIR))
                .maxSizeBytes(thumbnailCacheMaxBytes / 2)
                .build()
        }

        val builder = ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizeBytes(appliedMemoryCacheBytes)
                    .build()
            }
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { imageNetworkClient }))
                add(
                    VideoThumbnailDecoder.Factory(
                        thumbnailStrategy = {
                            val preferences = preferencesRepository.applicationPreferences.value
                            when (preferences.thumbnailGenerationStrategy) {
                                ThumbnailGenerationStrategy.FIRST_FRAME -> ThumbnailStrategy.FirstFrame
                                ThumbnailGenerationStrategy.FRAME_AT_PERCENTAGE -> ThumbnailStrategy.FrameAtPercentage(preferences.thumbnailFramePosition)
                                ThumbnailGenerationStrategy.HYBRID -> ThumbnailStrategy.Hybrid(preferences.thumbnailFramePosition)
                            }
                        },
                        localThumbnailDiskCache = localThumbnailDiskCache,
                        remoteThumbnailDiskCache = remoteThumbnailDiskCache,
                        webDavServersById = { webDavServersById },
                    ),
                )
            }
            .crossfade(true)

        if (cloudDiskCacheEnabled) {
            builder
                .diskCachePolicy(CachePolicy.ENABLED)
                .diskCache {
                    DiskCache.Builder()
                        .fileSystem(FileSystem.SYSTEM)
                        .directory(context.cacheDir.resolve(IMAGE_CACHE_DIR))
                        .maxSizeBytes(appliedCacheSizeMb.toLong() * 1024 * 1024)
                        .build()
                }
        } else {
            builder
                .diskCachePolicy(CachePolicy.DISABLED)
                .diskCache(null)
        }

        return runCatching { builder.build() }
            .getOrElse { error ->
                Logger.w("AppImageLoaderFactory", "Failed to build image loader with disk cache; falling back to memory-only loader", error)
                ImageCacheManager.clearImageDiskCache(context)
                ImageLoader.Builder(context)
                    .memoryCache {
                        MemoryCache.Builder()
                            .maxSizeBytes(appliedMemoryCacheBytes)
                            .build()
                    }
                    .components {
                        add(OkHttpNetworkFetcherFactory(callFactory = { imageNetworkClient }))
                    }
                    .diskCachePolicy(CachePolicy.DISABLED)
                    .diskCache(null)
                    .crossfade(true)
                    .build()
            }
            .also {
                localThumbnailDiskCache.value
                ImageCacheManager.setRemoteThumbnailDiskCache(remoteThumbnailDiskCache.value)
            }
    }

    private fun buildAuthorizationHeader(url: HttpUrl): String? {
        if (isOpenListSignedResource(url)) return null
        findMatchingWebDavServer(url)?.let { matchedServer ->
            buildAuthorizationHeader(
                username = matchedServer.username,
                password = matchedServer.password,
            )?.let { return it }
        }
        return buildAuthorizationHeader(
            username = url.username,
            password = url.password,
        )
    }

    private fun isOpenListSignedResource(url: HttpUrl): Boolean = url.queryParameter("sign") != null

    private fun findMatchingWebDavServer(url: HttpUrl): WebDavServer? = com.sakurafubuki.yume.core.data.webdav.findMatchingWebDavServer(webDavServersById.values, url)

    private fun buildAuthorizationHeader(username: String, password: String): String? {
        val normalizedUsername = username.trim()
        val normalizedPassword = password.trim()
        if (normalizedUsername.isBlank() && normalizedPassword.isBlank()) {
            return null
        }
        if (normalizedUsername.startsWith("Bearer ", ignoreCase = true)) {
            return normalizedUsername
        }
        if (normalizedUsername.equals("bearer", ignoreCase = true)) {
            return normalizedPassword.takeIf { it.isNotBlank() }?.let { "Bearer $it" }
        }
        return Credentials.basic(normalizedUsername, normalizedPassword)
    }
}

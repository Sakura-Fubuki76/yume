package com.sakurafubuki.yume.core.cache

import android.app.ActivityManager
import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import com.sakurafubuki.yume.core.common.Logger
import com.sakurafubuki.yume.core.model.CacheExpiry
import okio.FileSystem

object ImageCacheManager {

    fun memoryCacheBytesFromRamPercent(context: Context, percent: Int): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val requestedBytes = mi.totalMem * percent / 100
        val maxHeapBytes = Runtime.getRuntime().maxMemory()
        val heapCapPercent = when {
            maxHeapBytes < 384L * 1024L * 1024L -> 6
            maxHeapBytes < 768L * 1024L * 1024L -> 10
            maxHeapBytes < 1536L * 1024L * 1024L -> 14
            else -> 18
        }
        val heapCappedBytes = maxHeapBytes * heapCapPercent / 100
        return minOf(requestedBytes, heapCappedBytes).coerceAtLeast(8L * 1024L * 1024L)
    }

    suspend fun cleanExpiredCloudImageCache(context: Context, expiry: CacheExpiry): Int {
        val millis = expiry.millis ?: return 0
        val diskCache = SingletonImageLoader.get(context).diskCache ?: return 0
        val now = System.currentTimeMillis()
        var removed = 0
        val entries = CacheTimestampStore.allEntries(context)
        entries.forEach { (key, timestamp) ->
            if (now - timestamp > millis) {
                runCatching {
                    diskCache.remove(key)
                }
                CacheTimestampStore.remove(context, key)
                removed++
            }
        }
        if (removed > 0) {
            Logger.d("ImageCacheManager", "Expired $removed cloud image cache entries")
        }
        return removed
    }

    private const val IMAGE_CACHE_DIR = "image_cache"
    private const val FOLDER_COVER_IMAGES_DIR = "folder_cover_images"
    private const val LOCAL_IMAGE_CACHE_DIR = "local_image_browser_cache"
    private const val THUMBNAILS_CACHE_DIR = "thumbnails"
    private const val VIDEO_METADATA_PROCESS_DIR = "video_metadata_process"

    @Volatile
    private var globalImageLoaderRebuilder: ((Int) -> Unit)? = null

    @Volatile
    private var remoteThumbnailDiskCache: DiskCache? = null

    fun registerGlobalImageLoaderRebuilder(rebuilder: (Int) -> Unit) {
        globalImageLoaderRebuilder = rebuilder
    }

    fun setRemoteThumbnailDiskCache(cache: DiskCache) {
        remoteThumbnailDiskCache = cache
    }

    fun getRemoteThumbnailCacheUsageBytes(): Long =
        runCatching { remoteThumbnailDiskCache?.size ?: 0L }.getOrDefault(0L)

    fun removeRemoteThumbnailCacheEntry(key: String) {
        runCatching { remoteThumbnailDiskCache?.remove(key) }
    }

    fun rebuildGlobalImageLoader(diskCacheSizeMb: Int) {
        globalImageLoaderRebuilder?.invoke(diskCacheSizeMb)
    }

    fun buildImageLoader(
        context: Context,
        diskCacheSizeMb: Int,
        memoryCachePercent: Double = 0.25,
    ): ImageLoader = ImageLoader.Builder(context)
        .memoryCache {
            MemoryCache.Builder()
                .maxSizePercent(context, memoryCachePercent)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .fileSystem(FileSystem.SYSTEM)
                .directory(context.cacheDir.resolve(IMAGE_CACHE_DIR))
                .maxSizeBytes(diskCacheSizeMb.toLong() * 1024 * 1024)
                .build()
        }
        .build()

    fun clearAll(imageLoader: ImageLoader) {
        imageLoader.memoryCache?.clear()
        imageLoader.diskCache?.clear()
    }

    fun clearImageDiskCache(context: Context) {
        clearDirectories(
            context.cacheDir.resolve(IMAGE_CACHE_DIR),
            context.cacheDir.resolve(FOLDER_COVER_IMAGES_DIR),
        )
        CacheTimestampStore.clear(context)
    }

    fun clearLocalImageDiskCache(context: Context) {
        clearDirectories(
            context.cacheDir.resolve(LOCAL_IMAGE_CACHE_DIR),
        )
    }

    fun clearThumbnailCache(context: Context) {
        clearDirectories(
            context.cacheDir.resolve(THUMBNAILS_CACHE_DIR),
            context.cacheDir.resolve(VIDEO_METADATA_PROCESS_DIR),
        )
    }

    fun clearDiskCache(context: Context) {
        clearImageDiskCache(context)
        clearLocalImageDiskCache(context)
        clearThumbnailCache(context)
    }

    fun getCurrentUsageMb(context: Context): Long = getCurrentUsageBytes(context) / (1024 * 1024)

    fun getCurrentUsageBytes(context: Context): Long {
        val totalBytes = listOf(
            context.cacheDir.resolve(IMAGE_CACHE_DIR),
            context.cacheDir.resolve(FOLDER_COVER_IMAGES_DIR),
            context.cacheDir.resolve(LOCAL_IMAGE_CACHE_DIR),
            context.cacheDir.resolve(THUMBNAILS_CACHE_DIR),
            context.cacheDir.resolve(VIDEO_METADATA_PROCESS_DIR),
        ).sumOf(::directoryUsageBytes)
        return totalBytes
    }

    fun getImageDiskCacheUsageMb(context: Context): Long = getImageDiskCacheUsageBytes(context) / (1024 * 1024)

    fun getImageDiskCacheUsageBytes(context: Context): Long = listOf(
        context.cacheDir.resolve(IMAGE_CACHE_DIR),
        context.cacheDir.resolve(FOLDER_COVER_IMAGES_DIR),
    ).sumOf(::directoryUsageBytes)

    fun getLocalImageDiskCacheUsageMb(context: Context): Long = getLocalImageDiskCacheUsageBytes(context) / (1024 * 1024)

    fun getLocalImageDiskCacheUsageBytes(context: Context): Long = directoryUsageBytes(context.cacheDir.resolve(LOCAL_IMAGE_CACHE_DIR))

    fun getThumbnailCacheUsageMb(context: Context): Long = getThumbnailCacheUsageBytes(context) / (1024 * 1024)

    fun getThumbnailCacheUsageBytes(context: Context): Long = directoryUsageBytes(context.cacheDir.resolve(THUMBNAILS_CACHE_DIR))

    fun getVideoMetadataProcessCacheUsageBytes(context: Context): Long = directoryUsageBytes(context.cacheDir.resolve(VIDEO_METADATA_PROCESS_DIR))

    private fun clearDirectories(vararg directories: java.io.File) {
        directories.forEach { cacheDir ->
            if (cacheDir.exists()) {
                cacheDir.deleteRecursively()
            }
        }
    }

    private fun directoryUsageBytes(directory: java.io.File): Long {
        if (!directory.exists()) return 0L
        return directory.walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }
    }
}

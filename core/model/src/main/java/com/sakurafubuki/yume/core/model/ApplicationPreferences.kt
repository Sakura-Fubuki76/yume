package com.sakurafubuki.yume.core.model

import kotlinx.serialization.Serializable

@Serializable
data class ApplicationPreferences(
    val sortBy: Sort.By = Sort.By.TITLE,
    val sortOrder: Sort.Order = Sort.Order.ASCENDING,
    val themeConfig: ThemeConfig = ThemeConfig.SYSTEM,
    val useHighContrastDarkTheme: Boolean = false,
    val useDynamicColors: Boolean = true,
    val markLastPlayedMedia: Boolean = true,
    val excludeFolders: List<String> = emptyList(),
    val lastMediaMode: MediaMode = MediaMode.LOCAL,
    val imageLastMediaMode: MediaMode = MediaMode.LOCAL,
    val mediaViewMode: MediaViewMode = MediaViewMode.FOLDERS,
    val mediaLayoutMode: MediaLayoutMode = MediaLayoutMode.LIST,
    val imageViewMode: MediaViewMode = MediaViewMode.FOLDERS,
    val imageSortBy: Sort.By = Sort.By.TITLE,
    val imageSortOrder: Sort.Order = Sort.Order.ASCENDING,
    val imageLayoutMode: MediaLayoutMode = MediaLayoutMode.GRID,

    val lastSelectedCloudServerIds: Set<Int> = emptySet(),
    val imageLastSelectedCloudServerIds: Set<Int> = emptySet(),

    val showDurationField: Boolean = true,
    val showExtensionField: Boolean = false,
    val showPathField: Boolean = true,
    val showResolutionField: Boolean = false,
    val showSizeField: Boolean = false,
    val showThumbnailField: Boolean = true,
    val showPlayedProgress: Boolean = true,

    val thumbnailGenerationStrategy: ThumbnailGenerationStrategy = ThumbnailGenerationStrategy.FRAME_AT_PERCENTAGE,
    val thumbnailFramePosition: Float = DEFAULT_THUMBNAIL_FRAME_POSITION,
    val diskCacheSizeMb: Int = 3072,

    val streamingMinBufferMs: Int = DEFAULT_STREAMING_MIN_BUFFER_MS,
    val streamingMaxBufferMs: Int = DEFAULT_STREAMING_MAX_BUFFER_MS,
    val streamingBufferForPlaybackMs: Int = DEFAULT_STREAMING_BUFFER_FOR_PLAYBACK_MS,
    val streamingBufferForPlaybackAfterRebufferMs: Int = DEFAULT_STREAMING_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
    val streamingAllocatorChunkSizeKb: Int = DEFAULT_STREAMING_ALLOCATOR_CHUNK_SIZE_KB,
    val imageQuality: ImageQuality = ImageQuality.HIGH,
    val imageBrowserMemoryCachePercent: Int = DEFAULT_IMAGE_BROWSER_MEMORY_CACHE_PERCENT,
    val imageBrowserThumbnailSizePx: Int = DEFAULT_IMAGE_BROWSER_THUMBNAIL_SIZE_PX,
    val imageBrowserPreloadRange: Int = DEFAULT_IMAGE_BROWSER_PRELOAD_RANGE,
    val imageBrowserPreloadPageCount: Int = DEFAULT_IMAGE_BROWSER_PRELOAD_PAGE_COUNT,
    val imageCacheExpiry: CacheExpiry = CacheExpiry.NEVER,
) {

    companion object {
        const val DEFAULT_THUMBNAIL_FRAME_POSITION = 0.33f

        const val MIN_DISK_CACHE_SIZE_MB = 0
        const val MAX_DISK_CACHE_SLIDER_MB = 10240

        const val DEFAULT_STREAMING_MIN_BUFFER_MS = 30_000
        const val DEFAULT_STREAMING_MAX_BUFFER_MS = 90_000
        const val DEFAULT_STREAMING_BUFFER_FOR_PLAYBACK_MS = 2_000
        const val DEFAULT_STREAMING_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 5_000
        const val MIN_STREAMING_MIN_BUFFER_MS = 15_000
        const val MAX_STREAMING_MIN_BUFFER_MS = 120_000
        const val MIN_STREAMING_MAX_BUFFER_MS = 30_000
        const val MAX_STREAMING_MAX_BUFFER_MS = 180_000
        const val MIN_STREAMING_BUFFER_FOR_PLAYBACK_MS = 500
        const val MAX_STREAMING_BUFFER_FOR_PLAYBACK_MS = 10_000
        const val MIN_STREAMING_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 1_000
        const val MAX_STREAMING_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 15_000
        const val DEFAULT_STREAMING_ALLOCATOR_CHUNK_SIZE_KB = 2048
        const val MIN_STREAMING_ALLOCATOR_CHUNK_SIZE_KB = 64
        const val MAX_STREAMING_ALLOCATOR_CHUNK_SIZE_KB = 8192
        const val DEFAULT_IMAGE_BROWSER_MEMORY_CACHE_PERCENT = 25
        const val MIN_IMAGE_BROWSER_MEMORY_CACHE_PERCENT = 10
        const val MAX_IMAGE_BROWSER_MEMORY_CACHE_PERCENT = 40
        const val IMAGE_BROWSER_THUMBNAIL_SIZE_ORIGINAL = 0
        const val DEFAULT_IMAGE_BROWSER_THUMBNAIL_SIZE_PX = 512
        val IMAGE_BROWSER_THUMBNAIL_SIZE_OPTIONS = listOf(512, 768, 1024, IMAGE_BROWSER_THUMBNAIL_SIZE_ORIGINAL)
        const val DEFAULT_IMAGE_BROWSER_PRELOAD_RANGE = 2
        const val MIN_IMAGE_BROWSER_PRELOAD_RANGE = 1
        const val MAX_IMAGE_BROWSER_PRELOAD_RANGE = 6
        const val DEFAULT_IMAGE_BROWSER_PRELOAD_PAGE_COUNT = 2
        const val MIN_IMAGE_BROWSER_PRELOAD_PAGE_COUNT = 0
        const val MAX_IMAGE_BROWSER_PRELOAD_PAGE_COUNT = 5
    }
}

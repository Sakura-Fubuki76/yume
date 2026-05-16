package com.sakurafubuki.yume.settings.screens.medialibrary

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sakurafubuki.yume.core.cache.ImageCacheManager
import com.sakurafubuki.yume.core.common.Dispatcher
import com.sakurafubuki.yume.core.common.NextDispatchers
import com.sakurafubuki.yume.core.data.repository.PreferencesRepository
import com.sakurafubuki.yume.core.data.repository.WebDavServerRepository
import com.sakurafubuki.yume.core.data.webdav.WebDavRepository
import com.sakurafubuki.yume.core.model.ApplicationPreferences
import com.sakurafubuki.yume.core.model.CacheExpiry
import com.sakurafubuki.yume.core.model.WebDavServer
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
@OptIn(FlowPreview::class)
class MediaLibraryPreferencesViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferencesRepository: PreferencesRepository,
    private val webDavServerRepository: WebDavServerRepository,
    private val webDavRepository: WebDavRepository,
    @Dispatcher(NextDispatchers.IO) private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val uiStateInternal = MutableStateFlow(MediaLibraryPreferencesUiState())
    val uiState: StateFlow<MediaLibraryPreferencesUiState> = uiStateInternal.asStateFlow()
    private val cacheRefreshTrigger = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    init {
        viewModelScope.launch {
            preferencesRepository.applicationPreferences.collect {
                uiStateInternal.update { currentState ->
                    currentState.copy(
                        preferences = it,
                        imageCacheSizeMb = it.diskCacheSizeMb,
                        imageCacheExpiry = it.imageCacheExpiry,

                        streamingMinBufferMs = it.streamingMinBufferMs,
                        streamingMaxBufferMs = it.streamingMaxBufferMs,
                        streamingBufferForPlaybackMs = it.streamingBufferForPlaybackMs,
                        streamingBufferForPlaybackAfterRebufferMs = it.streamingBufferForPlaybackAfterRebufferMs,
                        imageBrowserMemoryCachePercent = it.imageBrowserMemoryCachePercent,
                        imageBrowserThumbnailSizePx = it.imageBrowserThumbnailSizePx,
                        imageBrowserPreloadRange = it.imageBrowserPreloadRange,
                        imageBrowserPreloadPageCount = it.imageBrowserPreloadPageCount,
                    )
                }
            }
        }

        viewModelScope.launch {
            webDavServerRepository.observeServers().collect { servers ->
                uiStateInternal.update { it.copy(servers = servers) }
            }
        }

        viewModelScope.launch {
            cacheRefreshTrigger
                .onStart { emit(Unit) }
                .debounce(CACHE_REFRESH_DEBOUNCE_MS)
                .collectLatest {
                    refreshCacheUsageNow()
                }
        }
    }

    fun onEvent(event: MediaLibraryPreferencesUiEvent) {
        when (event) {
            MediaLibraryPreferencesUiEvent.ToggleMarkLastPlayedMedia -> toggleMarkLastPlayedMedia()
            is MediaLibraryPreferencesUiEvent.AddWebDavServer -> addWebDavServer(event.server)
            is MediaLibraryPreferencesUiEvent.DeleteWebDavServer -> deleteWebDavServer(event.server)
            is MediaLibraryPreferencesUiEvent.TestWebDavServer -> testWebDavServer(event.server)
            is MediaLibraryPreferencesUiEvent.UpdateImageCacheSize -> setImageCacheSize(event.sizeMb)
            MediaLibraryPreferencesUiEvent.ClearImageCache -> clearImageCache()

            is MediaLibraryPreferencesUiEvent.UpdateStreamingMinBufferMs -> setStreamingMinBufferMs(event.value)
            is MediaLibraryPreferencesUiEvent.UpdateStreamingMaxBufferMs -> setStreamingMaxBufferMs(event.value)
            is MediaLibraryPreferencesUiEvent.UpdateStreamingBufferForPlaybackMs -> setStreamingBufferForPlaybackMs(event.value)
            is MediaLibraryPreferencesUiEvent.UpdateStreamingBufferForPlaybackAfterRebufferMs -> setStreamingBufferForPlaybackAfterRebufferMs(event.value)
            is MediaLibraryPreferencesUiEvent.UpdateImageBrowserMemoryCachePercent -> setImageBrowserMemoryCachePercent(event.percent)
            is MediaLibraryPreferencesUiEvent.UpdateImageBrowserThumbnailSizePx -> setImageBrowserThumbnailSizePx(event.sizePx)
            is MediaLibraryPreferencesUiEvent.UpdateImageBrowserPreloadRange -> setImageBrowserPreloadRange(event.range)
            is MediaLibraryPreferencesUiEvent.UpdateImageBrowserPreloadPageCount -> setImageBrowserPreloadPageCount(event.count)
            is MediaLibraryPreferencesUiEvent.UpdateImageCacheExpiry -> setImageCacheExpiry(event.expiry)
        }
    }

    private fun toggleMarkLastPlayedMedia() {
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences {
                it.copy(markLastPlayedMedia = !it.markLastPlayedMedia)
            }
        }
    }

    private fun addWebDavServer(server: WebDavServer) {
        viewModelScope.launch {
            webDavServerRepository.addServer(server)
        }
    }

    private fun deleteWebDavServer(server: WebDavServer) {
        viewModelScope.launch {
            webDavServerRepository.deleteServer(server)
        }
    }

    private fun testWebDavServer(server: WebDavServer) {
        viewModelScope.launch {
            val result = webDavRepository.testConnection(server)
            uiStateInternal.update {
                it.copy(lastConnectionTestResult = result.isSuccess)
            }
        }
    }

    private fun setImageCacheSize(sizeMb: Int) {
        viewModelScope.launch {
            val normalizedSizeMb = sizeMb.coerceAtLeast(ApplicationPreferences.MIN_DISK_CACHE_SIZE_MB)
            preferencesRepository.updateApplicationPreferences {
                it.copy(
                    diskCacheSizeMb = normalizedSizeMb,
                )
            }
            ImageCacheManager.rebuildGlobalImageLoader(normalizedSizeMb)
            requestCacheUsageRefresh()
        }
    }

    private fun setImageCacheExpiry(expiry: CacheExpiry) {
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences {
                it.copy(imageCacheExpiry = expiry)
            }
            if (expiry.millis != null) {
                ImageCacheManager.cleanExpiredCloudImageCache(context, expiry)
            }
        }
    }

    private fun clearImageCache() {
        viewModelScope.launch(ioDispatcher) {
            ImageCacheManager.clearImageDiskCache(context)
            requestCacheUsageRefresh()
        }
    }

    private fun setStreamingMinBufferMs(value: Int) {
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences { preferences ->
                val normalized = value.coerceIn(
                    ApplicationPreferences.MIN_STREAMING_MIN_BUFFER_MS,
                    ApplicationPreferences.MAX_STREAMING_MIN_BUFFER_MS,
                )
                val normalizedMax = preferences.streamingMaxBufferMs.coerceAtLeast(normalized)
                preferences.copy(
                    streamingMinBufferMs = normalized,
                    streamingMaxBufferMs = normalizedMax,
                )
            }
        }
    }

    private fun setStreamingMaxBufferMs(value: Int) {
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences { preferences ->
                val normalized = value.coerceIn(
                    ApplicationPreferences.MIN_STREAMING_MAX_BUFFER_MS,
                    ApplicationPreferences.MAX_STREAMING_MAX_BUFFER_MS,
                )
                val normalizedMin = preferences.streamingMinBufferMs.coerceAtMost(normalized)
                preferences.copy(
                    streamingMinBufferMs = normalizedMin,
                    streamingMaxBufferMs = normalized,
                )
            }
        }
    }

    private fun setStreamingBufferForPlaybackMs(value: Int) {
        viewModelScope.launch {
            val normalized = value.coerceIn(
                ApplicationPreferences.MIN_STREAMING_BUFFER_FOR_PLAYBACK_MS,
                ApplicationPreferences.MAX_STREAMING_BUFFER_FOR_PLAYBACK_MS,
            )
            preferencesRepository.updateApplicationPreferences {
                it.copy(streamingBufferForPlaybackMs = normalized)
            }
        }
    }

    private fun setStreamingBufferForPlaybackAfterRebufferMs(value: Int) {
        viewModelScope.launch {
            val normalized = value.coerceIn(
                ApplicationPreferences.MIN_STREAMING_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
                ApplicationPreferences.MAX_STREAMING_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
            )
            preferencesRepository.updateApplicationPreferences {
                it.copy(streamingBufferForPlaybackAfterRebufferMs = normalized)
            }
        }
    }

    private fun setImageBrowserMemoryCachePercent(percent: Int) {
        viewModelScope.launch {
            val normalized = percent.coerceIn(
                ApplicationPreferences.MIN_IMAGE_BROWSER_MEMORY_CACHE_PERCENT,
                ApplicationPreferences.MAX_IMAGE_BROWSER_MEMORY_CACHE_PERCENT,
            )
            preferencesRepository.updateApplicationPreferences {
                it.copy(imageBrowserMemoryCachePercent = normalized)
            }
            val diskCacheSize = preferencesRepository.applicationPreferences.value.diskCacheSizeMb
            ImageCacheManager.rebuildGlobalImageLoader(diskCacheSize)
        }
    }

    private fun setImageBrowserThumbnailSizePx(sizePx: Int) {
        viewModelScope.launch {
            val normalized = if (sizePx in ApplicationPreferences.IMAGE_BROWSER_THUMBNAIL_SIZE_OPTIONS) {
                sizePx
            } else {
                ApplicationPreferences.DEFAULT_IMAGE_BROWSER_THUMBNAIL_SIZE_PX
            }
            preferencesRepository.updateApplicationPreferences {
                it.copy(imageBrowserThumbnailSizePx = normalized)
            }
        }
    }

    private fun setImageBrowserPreloadRange(range: Int) {
        viewModelScope.launch {
            val normalized = range.coerceIn(
                ApplicationPreferences.MIN_IMAGE_BROWSER_PRELOAD_RANGE,
                ApplicationPreferences.MAX_IMAGE_BROWSER_PRELOAD_RANGE,
            )
            preferencesRepository.updateApplicationPreferences {
                it.copy(imageBrowserPreloadRange = normalized)
            }
        }
    }

    private fun setImageBrowserPreloadPageCount(count: Int) {
        viewModelScope.launch {
            val normalized = count.coerceIn(
                ApplicationPreferences.MIN_IMAGE_BROWSER_PRELOAD_PAGE_COUNT,
                ApplicationPreferences.MAX_IMAGE_BROWSER_PRELOAD_PAGE_COUNT,
            )
            preferencesRepository.updateApplicationPreferences {
                it.copy(imageBrowserPreloadPageCount = normalized)
            }
        }
    }

    private fun requestCacheUsageRefresh() {
        cacheRefreshTrigger.tryEmit(Unit)
    }

    private suspend fun refreshCacheUsageNow() {
        val imageCacheUsageBytes = withContext(ioDispatcher) {
            ImageCacheManager.getImageDiskCacheUsageBytes(context)
        }

        uiStateInternal.update {
            it.copy(
                currentImageCacheUsageMb = (imageCacheUsageBytes / (1024 * 1024)).coerceAtLeast(0),
            )
        }
    }
}

private const val CACHE_REFRESH_DEBOUNCE_MS = 300L

data class MediaLibraryPreferencesUiState(
    val preferences: ApplicationPreferences = ApplicationPreferences(),
    val servers: List<WebDavServer> = emptyList(),
    val imageCacheSizeMb: Int = 3072,
    val imageCacheExpiry: CacheExpiry = CacheExpiry.NEVER,

    val streamingMinBufferMs: Int = ApplicationPreferences.DEFAULT_STREAMING_MIN_BUFFER_MS,
    val streamingMaxBufferMs: Int = ApplicationPreferences.DEFAULT_STREAMING_MAX_BUFFER_MS,
    val streamingBufferForPlaybackMs: Int = ApplicationPreferences.DEFAULT_STREAMING_BUFFER_FOR_PLAYBACK_MS,
    val streamingBufferForPlaybackAfterRebufferMs: Int = ApplicationPreferences.DEFAULT_STREAMING_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
    val currentImageCacheUsageMb: Long = 0,
    val imageBrowserMemoryCachePercent: Int = ApplicationPreferences.DEFAULT_IMAGE_BROWSER_MEMORY_CACHE_PERCENT,
    val imageBrowserThumbnailSizePx: Int = ApplicationPreferences.DEFAULT_IMAGE_BROWSER_THUMBNAIL_SIZE_PX,
    val imageBrowserPreloadRange: Int = ApplicationPreferences.DEFAULT_IMAGE_BROWSER_PRELOAD_RANGE,
    val imageBrowserPreloadPageCount: Int = ApplicationPreferences.DEFAULT_IMAGE_BROWSER_PRELOAD_PAGE_COUNT,
    val lastConnectionTestResult: Boolean? = null,
)

sealed interface MediaLibraryPreferencesUiEvent {
    data object ToggleMarkLastPlayedMedia : MediaLibraryPreferencesUiEvent
    data class AddWebDavServer(val server: WebDavServer) : MediaLibraryPreferencesUiEvent
    data class DeleteWebDavServer(val server: WebDavServer) : MediaLibraryPreferencesUiEvent
    data class TestWebDavServer(val server: WebDavServer) : MediaLibraryPreferencesUiEvent
    data class UpdateImageCacheSize(val sizeMb: Int) : MediaLibraryPreferencesUiEvent

    data class UpdateStreamingMinBufferMs(val value: Int) : MediaLibraryPreferencesUiEvent
    data class UpdateStreamingMaxBufferMs(val value: Int) : MediaLibraryPreferencesUiEvent
    data class UpdateStreamingBufferForPlaybackMs(val value: Int) : MediaLibraryPreferencesUiEvent
    data class UpdateStreamingBufferForPlaybackAfterRebufferMs(val value: Int) : MediaLibraryPreferencesUiEvent
    data class UpdateImageBrowserMemoryCachePercent(val percent: Int) : MediaLibraryPreferencesUiEvent
    data class UpdateImageBrowserThumbnailSizePx(val sizePx: Int) : MediaLibraryPreferencesUiEvent
    data class UpdateImageBrowserPreloadRange(val range: Int) : MediaLibraryPreferencesUiEvent
    data class UpdateImageBrowserPreloadPageCount(val count: Int) : MediaLibraryPreferencesUiEvent
    data class UpdateImageCacheExpiry(val expiry: CacheExpiry) : MediaLibraryPreferencesUiEvent
    data object ClearImageCache : MediaLibraryPreferencesUiEvent
}

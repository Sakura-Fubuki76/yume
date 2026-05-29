package com.sakurafubuki.yume.feature.player.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Intent
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Player.DISCONTINUITY_REASON_AUTO_TRANSITION
import androidx.media3.common.Player.DISCONTINUITY_REASON_REMOVE
import androidx.media3.common.Player.DISCONTINUITY_REASON_SEEK
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.effect.GlEffect
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.session.CommandButton
import androidx.media3.session.CommandButton.ICON_UNDEFINED
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import coil3.ImageLoader
import coil3.request.ImageRequest
import com.google.common.util.concurrent.ListenableFuture
import com.sakurafubuki.yume.core.common.Logger
import com.sakurafubuki.yume.core.common.Utils
import com.sakurafubuki.yume.core.common.extensions.deleteFiles
import com.sakurafubuki.yume.core.common.extensions.fuzzyMatchNames
import com.sakurafubuki.yume.core.common.extensions.getFilenameFromUri
import com.sakurafubuki.yume.core.common.extensions.getLocalSubtitles
import com.sakurafubuki.yume.core.common.extensions.getPath
import com.sakurafubuki.yume.core.common.extensions.subtitleCacheDir
import com.sakurafubuki.yume.core.data.di.StreamingHttpClient
import com.sakurafubuki.yume.core.data.openlist.OpenListApi
import com.sakurafubuki.yume.core.data.repository.CloudVideoMetadataRepository
import com.sakurafubuki.yume.core.data.repository.MediaRepository
import com.sakurafubuki.yume.core.data.repository.MkvKeyframeExtractor
import com.sakurafubuki.yume.core.data.repository.MoovIndexCache
import com.sakurafubuki.yume.core.data.repository.Mp4KeyframeExtractor
import com.sakurafubuki.yume.core.data.repository.PreferencesRepository
import com.sakurafubuki.yume.core.data.repository.WebDavServerRepository
import com.sakurafubuki.yume.core.model.Anime4KRestoreMode
import com.sakurafubuki.yume.core.model.Anime4KUpscaleMode
import com.sakurafubuki.yume.core.model.CloudVideoMetadata
import com.sakurafubuki.yume.core.model.DecoderPriority
import com.sakurafubuki.yume.core.model.LoopMode
import com.sakurafubuki.yume.core.model.PlayerPreferences
import com.sakurafubuki.yume.core.model.Resume
import com.sakurafubuki.yume.core.model.VideoEffectType
import com.sakurafubuki.yume.core.model.WebDavServer
import com.sakurafubuki.yume.core.ui.R as coreUiR
import com.sakurafubuki.yume.feature.player.PlayerActivity
import com.sakurafubuki.yume.feature.player.R
import com.sakurafubuki.yume.feature.player.ass.AssSubtitleState
import com.sakurafubuki.yume.feature.player.audio.SoundTouchRenderersFactory
import com.sakurafubuki.yume.feature.player.effect.Anime4KClampHighlightsEffect
import com.sakurafubuki.yume.feature.player.effect.Anime4KRestoreEffect
import com.sakurafubuki.yume.feature.player.effect.Anime4KUpscaleEffect
import com.sakurafubuki.yume.feature.player.effect.DebandEffect
import com.sakurafubuki.yume.feature.player.effect.DitherEffect
import com.sakurafubuki.yume.feature.player.extensions.addAdditionalSubtitleConfiguration
import com.sakurafubuki.yume.feature.player.extensions.applySubtitleTimingToRenderers
import com.sakurafubuki.yume.feature.player.extensions.audioTrackIndex
import com.sakurafubuki.yume.feature.player.extensions.copy
import com.sakurafubuki.yume.feature.player.extensions.getManuallySelectedTrackIndex
import com.sakurafubuki.yume.feature.player.extensions.playbackSpeed
import com.sakurafubuki.yume.feature.player.extensions.positionMs
import com.sakurafubuki.yume.feature.player.extensions.selectedSubtitleUri
import com.sakurafubuki.yume.feature.player.extensions.setExtras
import com.sakurafubuki.yume.feature.player.extensions.setIsScrubbingModeEnabled
import com.sakurafubuki.yume.feature.player.extensions.subtitleDelayMilliseconds
import com.sakurafubuki.yume.feature.player.extensions.subtitleSpeed
import com.sakurafubuki.yume.feature.player.extensions.subtitleTrackIndex
import com.sakurafubuki.yume.feature.player.extensions.switchTrack
import com.sakurafubuki.yume.feature.player.extensions.uriToSubtitleConfiguration
import com.sakurafubuki.yume.feature.player.extensions.videoZoom
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody

private const val TAG = "PlayerService"
private const val PLAYER_MEDIA_ITEM_METADATA_CONCURRENCY = 4
private const val STREAMING_CACHE_DIR = "streaming_media"
private const val STREAMING_CACHE_MAX_BYTES = 64L * 1024L * 1024L
private const val NEXT_MEDIA_PREBUFFER_BYTES = 2L * 1024L * 1024L
private const val REMOTE_SUBTITLE_PROBE_CACHE_TTL_MS = 5 * 60 * 1000L
private const val REMOTE_SUBTITLE_PROBE_CACHE_MAX_ENTRIES = 64
private const val SUBTITLE_DISCOVERY_CACHE_MAX_ENTRIES = 256
private val SUBTITLE_EPISODE_REGEX = listOf(
    Regex("""(?i)(?:^|[\s._\-\[(])(?:ep?|episode)\s*0*(\d{1,4})(?=$|[\s._\-\]\)])"""),
    Regex("""(?:^|[\s._\-\[(第])0*(\d{1,4})(?:v\d+)?(?:话|話|集)?(?=$|[\s._\-\]\)])"""),
)
private val COMMON_NON_EPISODE_NUMBERS = setOf(480, 720, 1080, 1440, 2160, 264, 265)

@OptIn(UnstableApi::class)
@AndroidEntryPoint
class PlayerService : MediaSessionService() {

    private val serviceScope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var mediaSession: MediaSession? = null
    private var artworkLoadJob: Job? = null
    private var prebufferJob: Job? = null
    private val subtitleDiscoveryInFlight = ConcurrentHashMap.newKeySet<String>()
    private val subtitleDiscoveryCompleted = Collections.newSetFromMap(
        Collections.synchronizedMap(
            object : LinkedHashMap<String, Boolean>(SUBTITLE_DISCOVERY_CACHE_MAX_ENTRIES, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > SUBTITLE_DISCOVERY_CACHE_MAX_ENTRIES
            },
        ),
    )

    @Volatile
    private var prebufferKey: String? = null
    private var loadControl: ScrubbingAwareLoadControl? = null
    private var scrubPrefetchJob: Job? = null

    @Inject
    lateinit var preferencesRepository: PreferencesRepository

    @Inject
    lateinit var mediaRepository: MediaRepository

    @Inject
    lateinit var webDavServerRepository: WebDavServerRepository

    @Inject
    lateinit var openListApi: OpenListApi

    @Inject
    lateinit var cloudVideoMetadataRepository: CloudVideoMetadataRepository

    @Inject
    lateinit var imageLoader: ImageLoader

    private val playerPreferences: PlayerPreferences
        get() = preferencesRepository.playerPreferences.value

    private val customCommands = CustomCommands.asSessionCommands()

    private var isMediaItemReady = false

    @Volatile
    private var webDavServersById: Map<Int, WebDavServer> = emptyMap()

    private val remoteSubtitleProbeCacheLock = Any()
    private val remoteSubtitleProbeCache = object : LinkedHashMap<String, RemoteSubtitleProbeCacheEntry>(
        REMOTE_SUBTITLE_PROBE_CACHE_MAX_ENTRIES,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, RemoteSubtitleProbeCacheEntry>?,
        ): Boolean = size > REMOTE_SUBTITLE_PROBE_CACHE_MAX_ENTRIES
    }

    @Inject
    @StreamingHttpClient
    lateinit var okHttpClient: OkHttpClient

    private lateinit var mp4Extractor: Mp4KeyframeExtractor
    private lateinit var mkvExtractor: MkvKeyframeExtractor
    private var streamingCache: SimpleCache? = null
    private var streamingCacheDataSourceFactory: CacheDataSource.Factory? = null

    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var currentVolumeGain: Int = 0

    @Volatile
    private var hdrMaxLuma: Float? = null

    private val hdrMetadataListener = object : AnalyticsListener {
        override fun onVideoInputFormatChanged(eventTime: EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
            val newMaxLuma = parseHdrMaxLuma(format.colorInfo?.hdrStaticInfo)
            if (hdrMaxLuma != newMaxLuma) {
                Logger.i(TAG, "HDR max_luma changed: $hdrMaxLuma → $newMaxLuma nits, rebuilding effects")
                hdrMaxLuma = newMaxLuma
                (mediaSession?.player as? ExoPlayer)?.applyVideoEffects(playerPreferences, newMaxLuma)
            }
        }
    }

    private fun parseHdrMaxLuma(hdrStaticInfo: ByteArray?): Float? {
        if (hdrStaticInfo == null || hdrStaticInfo.size < 18) return null
        val offset = if (hdrStaticInfo.size >= 25) 17 else 16
        val lo = hdrStaticInfo[offset].toInt() and 0xFF
        val hi = hdrStaticInfo[offset + 1].toInt() and 0xFF
        val nits = ((hi shl 8) or lo).toFloat()
        return if (nits > 0f) nits else null
    }

    private val playbackStateListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            super.onMediaItemTransition(mediaItem, reason)
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) return
            isMediaItemReady = false
            loadArtworkForCurrentMediaItem()
            mediaItem?.mediaMetadata?.let { metadata ->
                mediaSession?.player?.run {
                    setPlaybackSpeed(metadata.playbackSpeed ?: playerPreferences.defaultPlaybackSpeed)
                    playerSpecificSubtitleDelayMilliseconds = metadata.subtitleDelayMilliseconds ?: 0L
                    playerSpecificSubtitleSpeed = metadata.subtitleSpeed ?: 1f
                    applySubtitleTimingToRenderers()
                }

                metadata.positionMs?.takeIf { playerPreferences.resume == Resume.YES }?.let {
                    mediaSession?.player?.seekTo(it)
                }
            }

            mediaItem?.let { item ->
                discoverSubtitlesForMediaItemAtCurrentIndex(item)
                serviceScope.launch(Dispatchers.IO) {
                    resolveChaptersForMediaItem(item)
                }
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            super.onPositionDiscontinuity(oldPosition, newPosition, reason)

            if (reason == DISCONTINUITY_REASON_SEEK && loadControl?.isScrubbing == false) {
                loadControl?.isScrubbing = true
                serviceScope.launch {
                    kotlinx.coroutines.delay(2000L)
                    loadControl?.isScrubbing = false
                }
            }

            val oldMediaItem = oldPosition.mediaItem ?: return

            when (reason) {
                DISCONTINUITY_REASON_SEEK,
                DISCONTINUITY_REASON_AUTO_TRANSITION,
                -> {
                    if (newPosition.mediaItem == null || oldMediaItem == newPosition.mediaItem) return

                    val updatedPosition = oldPosition.positionMs.takeIf { reason == DISCONTINUITY_REASON_SEEK } ?: C.TIME_UNSET
                    mediaSession?.player?.replaceMediaItem(
                        oldPosition.mediaItemIndex,
                        oldMediaItem.copy(positionMs = updatedPosition),
                    )
                    serviceScope.launch {
                        mediaRepository.updateMediumPosition(
                            uri = oldMediaItem.mediaId,
                            position = updatedPosition,
                        )
                    }
                }

                DISCONTINUITY_REASON_REMOVE -> {
                    serviceScope.launch {
                        val durationMs = oldMediaItem.mediaMetadata.durationMs
                        val isAtEnd = durationMs != null && oldPosition.positionMs >= durationMs - 1000
                        mediaRepository.updateMediumPosition(
                            uri = oldMediaItem.mediaId,
                            position = if (isAtEnd) C.TIME_UNSET else oldPosition.positionMs,
                        )
                    }
                }

                else -> return
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            super.onTracksChanged(tracks)
            if (!isMediaItemReady && tracks.groups.isNotEmpty()) {
                isMediaItemReady = true

                mediaSession?.player?.run {
                    playerSpecificSubtitleDelayMilliseconds = mediaMetadata.subtitleDelayMilliseconds ?: 0L
                    playerSpecificSubtitleSpeed = mediaMetadata.subtitleSpeed ?: 1f
                    applySubtitleTimingToRenderers()
                }
                if (!playerPreferences.rememberSelections) return
                mediaSession?.player?.mediaMetadata?.audioTrackIndex?.let {
                    mediaSession?.player?.switchTrack(C.TRACK_TYPE_AUDIO, it)
                }
                mediaSession?.player?.run {
                    val selectedSubtitleUri = mediaMetadata.selectedSubtitleUri
                    val selectedSubtitleTrackIndex = selectedSubtitleUri
                        ?.let { findSubtitleTrackIndexByUri(it.toUri()) }
                        ?: mediaMetadata.subtitleTrackIndex
                    selectedSubtitleTrackIndex?.let {
                        switchTrack(C.TRACK_TYPE_TEXT, it)
                    }
                }
            }
        }

        override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) {
            super.onTrackSelectionParametersChanged(parameters)
            val player = mediaSession?.player ?: return
            val currentMediaItem = player.currentMediaItem ?: return

            val audioTrackIndex = player.getManuallySelectedTrackIndex(C.TRACK_TYPE_AUDIO)
            val subtitleTrackIndex = player.getManuallySelectedTrackIndex(C.TRACK_TYPE_TEXT)
            val selectedSubtitleUri = subtitleTrackIndex
                ?.takeIf { it >= 0 }
                ?.let { player.findSubtitleUriByTrackIndex(it) }

            if (audioTrackIndex != null) {
                serviceScope.launch {
                    mediaRepository.updateMediumAudioTrack(
                        uri = currentMediaItem.mediaId,
                        audioTrackIndex = audioTrackIndex,
                    )
                }
            }

            if (subtitleTrackIndex != null) {
                serviceScope.launch {
                    mediaRepository.updateMediumSubtitleSelection(
                        uri = currentMediaItem.mediaId,
                        subtitleTrackIndex = subtitleTrackIndex,
                        selectedSubtitleUri = selectedSubtitleUri,
                    )
                }
            }

            player.replaceMediaItem(
                player.currentMediaItemIndex,
                currentMediaItem.copy(
                    audioTrackIndex = audioTrackIndex,
                    subtitleTrackIndex = subtitleTrackIndex,
                    selectedSubtitleUri = if (subtitleTrackIndex != null) {
                        selectedSubtitleUri?.toString()
                    } else {
                        currentMediaItem.mediaMetadata.selectedSubtitleUri
                    },
                ),
            )
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            super.onPlaybackParametersChanged(playbackParameters)
            val player = mediaSession?.player ?: return
            val currentMediaItem = player.currentMediaItem ?: return
            val playbackSpeed = playbackParameters.speed

            serviceScope.launch {
                mediaRepository.updateMediumPlaybackSpeed(
                    uri = currentMediaItem.mediaId,
                    playbackSpeed = playbackSpeed,
                )
            }
            player.replaceMediaItem(
                player.currentMediaItemIndex,
                currentMediaItem.copy(playbackSpeed = playbackSpeed),
            )
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            super.onPlaybackStateChanged(playbackState)

            if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
                mediaSession?.player?.trackSelectionParameters = TrackSelectionParameters.DEFAULT
                mediaSession?.player?.setPlaybackSpeed(playerPreferences.defaultPlaybackSpeed)
            }

            if (playbackState == Player.STATE_READY) {
                mediaSession?.player?.let {
                    serviceScope.launch {
                        mediaRepository.updateMediumLastPlayedTime(
                            uri = it.currentMediaItem?.mediaId ?: return@launch,
                            lastPlayedTime = System.currentTimeMillis(),
                        )
                    }
                }
                maybeDiscoverNextMediaItemSubtitles()
                maybePrebufferNext()
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            super.onPlayWhenReadyChanged(playWhenReady, reason)

            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                if (mediaSession?.player?.repeatMode != Player.REPEAT_MODE_OFF) {
                    mediaSession?.player?.seekTo(0)
                    mediaSession?.player?.play()
                    return
                }
                mediaSession?.run {
                    player.clearMediaItems()
                    player.stop()
                }
                stopSelf()
            }
        }

        override fun onRenderedFirstFrame() {
            super.onRenderedFirstFrame()
            val player = mediaSession?.player ?: return
            val currentMediaItem = player.currentMediaItem ?: return

            player.replaceMediaItem(
                player.currentMediaItemIndex,
                currentMediaItem.copy(durationMs = player.duration.coerceAtLeast(0)),
            )
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            super.onIsPlayingChanged(isPlaying)
            mediaSession?.run {
                serviceScope.launch {
                    mediaRepository.updateMediumPosition(
                        uri = player.currentMediaItem?.mediaId ?: return@launch,
                        position = player.currentPosition,
                    )
                }
            }
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            super.onRepeatModeChanged(repeatMode)
            serviceScope.launch {
                preferencesRepository.updatePlayerPreferences {
                    it.copy(
                        loopMode = when (repeatMode) {
                            Player.REPEAT_MODE_OFF -> LoopMode.OFF
                            Player.REPEAT_MODE_ONE -> LoopMode.ONE
                            Player.REPEAT_MODE_ALL -> LoopMode.ALL
                            else -> LoopMode.OFF
                        },
                    )
                }
            }
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            super.onAudioSessionIdChanged(audioSessionId)
            if (!playerPreferences.enableVolumeBoost) return
            if (audioSessionId == C.AUDIO_SESSION_ID_UNSET) return
            try {
                loudnessEnhancer?.release()
                loudnessEnhancer = LoudnessEnhancer(audioSessionId)
                if (currentVolumeGain > 0) {
                    setEnhancerTargetGain(currentVolumeGain)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                loudnessEnhancer = null
            }
        }
    }

    private fun maybePrebufferNext() {
        val player = mediaSession?.player ?: return
        val durationMs = player.duration
        if (durationMs <= 0 || player.currentPosition < durationMs * 0.8) return

        val nextIndex = player.currentMediaItemIndex + 1
        if (nextIndex >= player.mediaItemCount) return
        val nextItem = player.getMediaItemAt(nextIndex)
        discoverSubtitlesForMediaItemAt(nextIndex, nextItem)
        val nextUrl = nextItem.mediaId
        if (!nextUrl.isHttpUrl()) return
        val nextKey = nextUrl

        if (prebufferKey == nextKey) return
        prebufferKey = nextKey

        prebufferJob?.cancel()
        prebufferJob = serviceScope.launch(Dispatchers.IO) {
            cacheHttpRange(
                url = nextUrl,
                position = 0L,
                length = NEXT_MEDIA_PREBUFFER_BYTES,
            )
        }
    }

    private fun maybeDiscoverNextMediaItemSubtitles() {
        val player = mediaSession?.player ?: return
        val nextIndex = player.currentMediaItemIndex + 1
        if (nextIndex >= player.mediaItemCount) return
        discoverSubtitlesForMediaItemAt(nextIndex, player.getMediaItemAt(nextIndex))
    }

    private fun discoverSubtitlesForMediaItemAtCurrentIndex(mediaItem: MediaItem) {
        val player = mediaSession?.player ?: return
        val currentIndex = player.currentMediaItemIndex.takeIf { it in 0 until player.mediaItemCount } ?: return
        discoverSubtitlesForMediaItemAt(currentIndex, mediaItem)
    }

    private fun discoverSubtitlesForMediaItemAt(index: Int, mediaItem: MediaItem) {
        val mediaId = mediaItem.mediaId.takeIf { it.isNotBlank() } ?: return
        if (mediaId in subtitleDiscoveryCompleted) return
        if (!subtitleDiscoveryInFlight.add(mediaId)) return

        serviceScope.launch(Dispatchers.IO) {
            try {
                val updatedMediaItem = updatedMediaItemWithMetadata(
                    mediaItem = mediaItem,
                    shouldDiscoverSubtitles = true,
                )
                withContext(Dispatchers.Main) {
                    val player = mediaSession?.player ?: return@withContext
                    if (index !in 0 until player.mediaItemCount) return@withContext
                    val currentItemAtIndex = player.getMediaItemAt(index)
                    if (currentItemAtIndex.mediaId != mediaId) return@withContext
                    if (currentItemAtIndex == updatedMediaItem) return@withContext

                    if (index == player.currentMediaItemIndex) {
                        player.replaceCurrentMediaItemPreservingPosition(
                            updatedMediaItem.copy(positionMs = player.currentPosition),
                        )
                    } else {
                        player.replaceMediaItem(index, updatedMediaItem)
                    }
                }
                subtitleDiscoveryCompleted.add(mediaId)
            } finally {
                subtitleDiscoveryInFlight.remove(mediaId)
            }
        }
    }

    private fun Player.replaceCurrentMediaItemPreservingPosition(updatedMediaItem: MediaItem) {
        val index = currentMediaItemIndex.takeIf { it in 0 until mediaItemCount } ?: return
        val positionMs = currentPosition.coerceAtLeast(0L)
        val shouldPlayWhenReady = playWhenReady
        addMediaItem(index + 1, updatedMediaItem)
        seekTo(index + 1, positionMs)
        removeMediaItem(index)
        playWhenReady = shouldPlayWhenReady
    }

    private fun prefetchScrubKeyframe() {
        scrubPrefetchJob?.cancel()
        val player = mediaSession?.player ?: return
        val url = player.currentMediaItem?.mediaId ?: return
        if (!url.isHttpUrl()) return
        val currentPosMs = player.currentPosition
        if (currentPosMs <= 0) return
        scrubPrefetchJob = serviceScope.launch(Dispatchers.IO) {
            val keyframe = MoovIndexCache.findNearestKeyframe(url, currentPosMs)
                ?: return@launch

            cacheHttpRange(
                url = url,
                position = keyframe.byteOffset,
                length = keyframe.byteSize.toLong(),
            )
        }
    }

    private fun String.isHttpUrl(): Boolean = startsWith("http://", ignoreCase = true) || startsWith("https://", ignoreCase = true)

    private fun cacheHttpRange(
        url: String,
        position: Long,
        length: Long,
    ) {
        if (length <= 0L) return
        val cacheDataSourceFactory = streamingCacheDataSourceFactory ?: return
        runCatching {
            val dataSpec = DataSpec.Builder()
                .setUri(url)
                .setKey(url)
                .setPosition(position)
                .setLength(length)
                .setFlags(DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION or DataSpec.FLAG_MIGHT_NOT_USE_FULL_NETWORK_SPEED)
                .build()
            CacheWriter(
                cacheDataSourceFactory.createDataSourceForDownloading(),
                dataSpec,
                ByteArray(CacheWriter.DEFAULT_BUFFER_SIZE_BYTES),
                null,
            ).cache()
        }.onFailure { error ->
            Logger.d(TAG, "cacheHttpRange failed: ${error.message}")
        }
    }

    private fun setEnhancerTargetGain(gain: Int) {
        val enhancer = loudnessEnhancer ?: return

        try {
            enhancer.setTargetGain(gain)
            enhancer.enabled = gain > 0
            currentVolumeGain = enhancer.targetGain.toInt()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private val mediaSessionCallback = object : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val connectionResult = super.onConnect(session, controller)
            return MediaSession.ConnectionResult.accept(
                connectionResult.availableSessionCommands
                    .buildUpon()
                    .addSessionCommands(customCommands)
                    .build(),
                connectionResult.availablePlayerCommands,
            )
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = serviceScope.future(Dispatchers.Default) {
            val subtitleDiscoveryIndex = startIndex.takeIf { it in mediaItems.indices }
            val updatedMediaItems = updatedMediaItemsWithMetadata(
                mediaItems = mediaItems,
                subtitleDiscoveryIndex = subtitleDiscoveryIndex,
            )
            subtitleDiscoveryIndex?.let { index ->
                updatedMediaItems.getOrNull(index)?.mediaId?.let(subtitleDiscoveryCompleted::add)
            }
            return@future MediaSession.MediaItemsWithStartPosition(updatedMediaItems, startIndex, startPositionMs)
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = serviceScope.future(Dispatchers.Default) {
            val updatedMediaItems = updatedMediaItemsWithMetadata(mediaItems)
            updatedMediaItems.forEach { subtitleDiscoveryCompleted.add(it.mediaId) }
            return@future updatedMediaItems.toMutableList()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> = serviceScope.future {
            val command = CustomCommands.fromSessionCommand(customCommand)
                ?: return@future SessionResult(SessionError.ERROR_BAD_VALUE)

            when (command) {
                CustomCommands.ADD_SUBTITLE_TRACK -> {
                    val subtitleUri = args.getString(CustomCommands.SUBTITLE_TRACK_URI_KEY)?.toUri()
                        ?: return@future SessionResult(SessionError.ERROR_BAD_VALUE)

                    val isAssSubtitle = subtitleUri.lastPathSegment
                        ?.let { it.endsWith(".ass", ignoreCase = true) || it.endsWith(".ssa", ignoreCase = true) }
                        ?: false

                    mediaSession?.player?.let { player ->
                        val currentMediaItem = player.currentMediaItem ?: return@let
                        val textTracks = player.currentTracks.groups.filter {
                            it.type == C.TRACK_TYPE_TEXT && it.isSupported
                        }

                        mediaRepository.updateMediumPosition(
                            uri = currentMediaItem.mediaId,
                            position = player.currentPosition,
                        )
                        mediaRepository.updateMediumSubtitleTrack(
                            uri = currentMediaItem.mediaId,
                            subtitleTrackIndex = textTracks.size,
                        )
                        mediaRepository.addExternalSubtitleToMedium(
                            uri = currentMediaItem.mediaId,
                            subtitleUri = subtitleUri,
                        )
                        if (!isAssSubtitle) {
                            val newSubConfiguration = uriToSubtitleConfiguration(
                                uri = subtitleUri,
                                subtitleEncoding = playerPreferences.subtitleTextEncoding,
                            )
                            player.addAdditionalSubtitleConfiguration(newSubConfiguration)
                        }
                    }
                    return@future SessionResult(SessionResult.RESULT_SUCCESS)
                }

                CustomCommands.SET_SKIP_SILENCE_ENABLED -> {
                    val enabled = args.getBoolean(CustomCommands.SKIP_SILENCE_ENABLED_KEY)
                    mediaSession?.player?.playerSpecificSkipSilenceEnabled = enabled
                    mediaSession?.sessionExtras = Bundle().apply {
                        putBoolean(CustomCommands.SKIP_SILENCE_ENABLED_KEY, enabled)
                    }
                    return@future SessionResult(SessionResult.RESULT_SUCCESS)
                }

                CustomCommands.GET_SKIP_SILENCE_ENABLED -> {
                    val enabled = mediaSession?.player?.playerSpecificSkipSilenceEnabled ?: false
                    return@future SessionResult(
                        SessionResult.RESULT_SUCCESS,
                        Bundle().apply {
                            putBoolean(CustomCommands.SKIP_SILENCE_ENABLED_KEY, enabled)
                        },
                    )
                }

                CustomCommands.SET_IS_SCRUBBING_MODE_ENABLED -> {
                    val enabled = args.getBoolean(CustomCommands.IS_SCRUBBING_MODE_ENABLED_KEY)
                    mediaSession?.player?.setIsScrubbingModeEnabled(enabled)
                    loadControl?.isScrubbing = enabled
                    if (enabled) {
                        prefetchScrubKeyframe()
                    } else {
                        scrubPrefetchJob?.cancel()
                    }
                    return@future SessionResult(SessionResult.RESULT_SUCCESS)
                }

                CustomCommands.IS_LOUDNESS_GAIN_SUPPORTED -> {
                    val isSupported = loudnessEnhancer != null
                    return@future SessionResult(
                        SessionResult.RESULT_SUCCESS,
                        Bundle().apply {
                            putBoolean(CustomCommands.IS_LOUDNESS_GAIN_SUPPORTED_KEY, isSupported)
                        },
                    )
                }

                CustomCommands.SET_LOUDNESS_GAIN -> {
                    val gain = args.getInt(CustomCommands.LOUDNESS_GAIN_KEY, 0)
                    setEnhancerTargetGain(gain)
                    return@future SessionResult(SessionResult.RESULT_SUCCESS)
                }

                CustomCommands.GET_LOUDNESS_GAIN -> {
                    return@future SessionResult(
                        SessionResult.RESULT_SUCCESS,
                        Bundle().apply {
                            putInt(CustomCommands.LOUDNESS_GAIN_KEY, currentVolumeGain)
                        },
                    )
                }

                CustomCommands.GET_SUBTITLE_DELAY -> {
                    val subtitleDelay = mediaSession?.player?.playerSpecificSubtitleDelayMilliseconds ?: 0
                    return@future SessionResult(
                        SessionResult.RESULT_SUCCESS,
                        Bundle().apply {
                            putLong(CustomCommands.SUBTITLE_DELAY_KEY, subtitleDelay)
                        },
                    )
                }

                CustomCommands.SET_SUBTITLE_DELAY -> {
                    val subtitleDelay = args.getLong(CustomCommands.SUBTITLE_DELAY_KEY)
                    mediaSession?.player?.playerSpecificSubtitleDelayMilliseconds = subtitleDelay
                    return@future SessionResult(SessionResult.RESULT_SUCCESS)
                }

                CustomCommands.GET_SUBTITLE_SPEED -> {
                    val subtitleSpeed = mediaSession?.player?.playerSpecificSubtitleSpeed ?: 0f
                    return@future SessionResult(
                        SessionResult.RESULT_SUCCESS,
                        Bundle().apply {
                            putFloat(CustomCommands.SUBTITLE_SPEED_KEY, subtitleSpeed)
                        },
                    )
                }

                CustomCommands.SET_SUBTITLE_SPEED -> {
                    val subtitleSpeed = args.getFloat(CustomCommands.SUBTITLE_SPEED_KEY)
                    mediaSession?.player?.playerSpecificSubtitleSpeed = subtitleSpeed
                    return@future SessionResult(SessionResult.RESULT_SUCCESS)
                }

                CustomCommands.STOP_PLAYER_SESSION -> {
                    mediaSession?.run {
                        serviceScope.launch {
                            mediaRepository.updateMediumPosition(
                                uri = player.currentMediaItem?.mediaId ?: return@launch,
                                position = player.currentPosition,
                            )
                        }
                    }
                    mediaSession?.run {
                        player.clearMediaItems()
                        player.stop()
                    }
                    stopSelf()
                    return@future SessionResult(SessionResult.RESULT_SUCCESS)
                }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onCreate() {
        super.onCreate()
        val appPreferences = preferencesRepository.applicationPreferences.value
        val renderersFactory = SoundTouchRenderersFactory(applicationContext, playerPreferences.audioOutputMode)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(
                when (playerPreferences.decoderPriority) {
                    DecoderPriority.DEVICE_ONLY -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
                    DecoderPriority.PREFER_DEVICE -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                    DecoderPriority.PREFER_APP -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                },
            )

        val trackSelector = DefaultTrackSelector(applicationContext).apply {
            setParameters(
                buildUponParameters()
                    .setPreferredAudioLanguage(playerPreferences.preferredAudioLanguage)
                    .setPreferredTextLanguage(playerPreferences.preferredSubtitleLanguage),
            )
        }

        okHttpClient = okHttpClient.newBuilder()
            .addInterceptor { chain ->
                val request = chain.request()

                if (request.method == "HEAD") {
                    val cachedLength = com.sakurafubuki.yume.core.data.repository.ContentLengthCache.get(request.url.toString())
                    if (cachedLength != null) {
                        return@addInterceptor okhttp3.Response.Builder()
                            .request(request)
                            .protocol(okhttp3.Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK (cached)")
                            .header("Content-Length", cachedLength.toString())
                            .header("Accept-Ranges", "bytes")
                            .body(ByteArray(0).toResponseBody(null))
                            .build()
                    }
                }

                var finalRequest = request.withStreamingNetworkHeaders()

                if (finalRequest.header("Authorization") != null) {
                    return@addInterceptor chain.proceed(finalRequest)
                }

                if (finalRequest.url.queryParameter("sign") != null) {
                    return@addInterceptor chain.proceed(finalRequest)
                }
                val authHeader = buildAuthorizationHeader(finalRequest.url)
                if (authHeader != null) {
                    finalRequest = finalRequest.newBuilder()
                        .header("Authorization", authHeader)
                        .build()
                }
                chain.proceed(finalRequest)
            }
            .addNetworkInterceptor { chain ->
                chain.proceed(chain.request().withStreamingNetworkHeaders())
            }
            .authenticator { _, response ->
                val requestUri = response.request.url
                if (response.request.header("Authorization") != null) {
                    return@authenticator null
                }
                val authHeader = buildAuthorizationHeader(requestUri) ?: return@authenticator null
                response.request.newBuilder()
                    .header("Authorization", authHeader)
                    .build()
            }
            .build()

        mp4Extractor = Mp4KeyframeExtractor(okHttpClient)
        mkvExtractor = MkvKeyframeExtractor(okHttpClient)

        serviceScope.launch(Dispatchers.IO) {
            webDavServerRepository.observeServers().collect { servers ->
                webDavServersById = servers.associateBy { it.id }
            }
        }

        val upstreamFactory = OkHttpDataSource.Factory(okHttpClient)
        val streamingCache = createStreamingCache()
        this.streamingCache = streamingCache
        val prefetchCacheDataSourceFactory = streamingCache?.let { cache ->
            CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(upstreamFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        }
        streamingCacheDataSourceFactory = prefetchCacheDataSourceFactory
        val playbackDataSourceFactory = streamingCache?.let { cache ->
            CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(upstreamFactory)
                .setCacheWriteDataSinkFactory(null)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        }

        val defaultDataSourceFactory = DefaultDataSource.Factory(
            applicationContext,
            playbackDataSourceFactory ?: upstreamFactory,
        )

        val loadControl = ScrubbingAwareLoadControl(
            allocator = DefaultAllocator(true, appPreferences.streamingAllocatorChunkSizeKb * 1024),
            normalMinBufferMs = appPreferences.streamingMinBufferMs,
            normalMaxBufferMs = appPreferences.streamingMaxBufferMs,
            normalBufferForPlaybackMs = appPreferences.streamingBufferForPlaybackMs,
            normalBufferForPlaybackAfterRebufferMs = appPreferences.streamingBufferForPlaybackAfterRebufferMs,
        )
        this.loadControl = loadControl

        val player = ExoPlayer.Builder(applicationContext)
            .setRenderersFactory(renderersFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(defaultDataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                playerPreferences.requireAudioFocus,
            )
            .setHandleAudioBecomingNoisy(playerPreferences.pauseOnHeadsetDisconnect)
            .build()
            .also { it ->
                val effects = playerPreferences.buildVideoEffects(hdrMaxLuma)
                Logger.i(TAG, "Effect pipeline (${effects.size} active): ${effects.joinToString(" → ") { it::class.simpleName ?: "?" }}")
                Logger.i(TAG, "DownscalePre=${playerPreferences.anime4KAutoDownscalePreMode} Upscale=${playerPreferences.anime4KUpscaleMode}")
                it.setVideoEffects(effects)
                it.addAnalyticsListener(hdrMetadataListener)
                it.addListener(playbackStateListener)
                it.pauseAtEndOfMediaItems = !playerPreferences.autoplay
                it.repeatMode = when (playerPreferences.loopMode) {
                    LoopMode.OFF -> Player.REPEAT_MODE_OFF
                    LoopMode.ONE -> Player.REPEAT_MODE_ONE
                    LoopMode.ALL -> Player.REPEAT_MODE_ALL
                }
            }

        serviceScope.launch {
            preferencesRepository.playerPreferences
                .distinctUntilChanged { old, new -> old.videoEffectsKey() == new.videoEffectsKey() }
                .collect { preferences ->
                    player.applyVideoEffects(preferences, hdrMaxLuma)
                }
        }

        try {
            mediaSession = MediaSession.Builder(this, player).apply {
                setSessionActivity(
                    PendingIntent.getActivity(
                        this@PlayerService,
                        0,
                        Intent(this@PlayerService, PlayerActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
                setCallback(mediaSessionCallback)
                setCustomLayout(
                    listOf(
                        CommandButton.Builder(ICON_UNDEFINED)
                            .setCustomIconResId(coreUiR.drawable.ic_close)
                            .setDisplayName(getString(coreUiR.string.stop_player_session))
                            .setSessionCommand(CustomCommands.STOP_PLAYER_SESSION.sessionCommand)
                            .setEnabled(true)
                            .build(),
                    ),
                )
            }.build()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player!!
        if (!player.playWhenReady || player.mediaItemCount == 0 || player.playbackState == Player.STATE_ENDED) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        artworkLoadJob?.cancel()
        prebufferJob?.cancel()
        scrubPrefetchJob?.cancel()
        subtitleDiscoveryInFlight.clear()
        subtitleDiscoveryCompleted.clear()
        loudnessEnhancer?.release()
        loudnessEnhancer = null
        mediaSession?.run {
            player.clearMediaItems()
            player.stop()
            (player as? ExoPlayer)?.removeAnalyticsListener(hdrMetadataListener)
            player.removeListener(playbackStateListener)
            player.release()
            release()
            mediaSession = null
        }
        subtitleCacheDir.deleteFiles()
        streamingCacheDataSourceFactory = null
        streamingCache?.release()
        streamingCache = null
        serviceScope.cancel()
    }

    @SuppressLint("UseKtx")
    private suspend fun updatedMediaItemsWithMetadata(
        mediaItems: List<MediaItem>,
        subtitleDiscoveryIndex: Int? = null,
    ): List<MediaItem> = supervisorScope {
        val metadataSemaphore = Semaphore(PLAYER_MEDIA_ITEM_METADATA_CONCURRENCY)
        mediaItems.mapIndexed { index, mediaItem ->
            async {
                metadataSemaphore.withPermit {
                    updatedMediaItemWithMetadata(
                        mediaItem = mediaItem,
                        shouldDiscoverSubtitles = subtitleDiscoveryIndex == null || index == subtitleDiscoveryIndex,
                    )
                }
            }
        }.awaitAll()
    }

    private suspend fun updatedMediaItemWithMetadata(
        mediaItem: MediaItem,
        shouldDiscoverSubtitles: Boolean,
    ): MediaItem {
        val uri = mediaItem.mediaId.toUri()
        val video = mediaRepository.getVideoByUri(uri = mediaItem.mediaId)
        val videoState = mediaRepository.getVideoState(uri = mediaItem.mediaId)

        val externalSubs = if (shouldDiscoverSubtitles) {
            videoState?.externalSubs?.filter { subUri ->
                when {
                    subUri.scheme.equals("http", ignoreCase = true) ||
                        subUri.scheme.equals("https", ignoreCase = true) -> true
                    else -> try {
                        contentResolver.openInputStream(subUri)?.use { true } ?: false
                    } catch (_: Exception) {
                        false
                    }
                }
            } ?: emptyList()
        } else {
            emptyList()
        }

        val resolvedPath = if (shouldDiscoverSubtitles) getPath(uri) ?: videoState?.path else videoState?.path
        if (shouldDiscoverSubtitles) {
            Logger.d(
                "PlayerService",
                "subtitleDiscovery: uri=$uri, resolvedPath=$resolvedPath, videoStatePath=${videoState?.path}",
            )
        }
        val localSubs = if (shouldDiscoverSubtitles) {
            when {
                uri.scheme.equals("http", ignoreCase = true) ||
                    uri.scheme.equals("https", ignoreCase = true) -> {
                    probeRemoteSubtitles(uri.toString(), externalSubs)
                }
                else -> {
                    resolvedPath?.let {
                        File(it).getLocalSubtitles(
                            context = this@PlayerService,
                            excludeSubsList = externalSubs,
                        )
                    } ?: emptyList()
                }
            }
        } else {
            emptyList()
        }
        if (shouldDiscoverSubtitles) {
            Logger.d("PlayerService", "subtitleDiscovery: localSubs=$localSubs, externalSubs=$externalSubs")
        }

        val existingSubConfigurations = mediaItem.localConfiguration?.subtitleConfigurations?.filter { config ->
            val subUri = config.uri.toString()
            when {
                subUri.startsWith("http://") || subUri.startsWith("https://") -> true
                else -> try {
                    val u = subUri.toUri()
                    contentResolver.openInputStream(u)?.use { true } ?: false
                } catch (_: Exception) {
                    false
                }
            }
        } ?: emptyList()

        val savedSubtitleTrackIndex = mediaItem.mediaMetadata.subtitleTrackIndex ?: videoState?.subtitleTrackIndex
        val savedSubtitleUri = mediaItem.mediaMetadata.selectedSubtitleUri?.toUri() ?: videoState?.selectedSubtitleUri
        val hasPriorTrackSelection = savedSubtitleTrackIndex != null
        val hasPriorSubConfigSelection = existingSubConfigurations.any {
            it.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0
        }

        val allSubUris = (localSubs + externalSubs).distinctBy { it.toString() }
        val savedCandidateUri = savedSubtitleUri?.let { savedUri ->
            allSubUris.firstOrNull { it.toString() == savedUri.toString() }
        }
        val shouldAutoSelect = savedCandidateUri == null &&
            savedSubtitleTrackIndex != -1 &&
            !hasPriorTrackSelection &&
            !hasPriorSubConfigSelection &&
            playerPreferences.rememberSelections
        val bestCandidateUri = savedCandidateUri ?: if (shouldAutoSelect) {
            findBestSubtitleCandidate(mediaItem, allSubUris)
        } else {
            null
        }
        if (shouldDiscoverSubtitles) {
            Logger.d(
                "PlayerService",
                "subtitleAutoSelect: shouldAutoSelect=$shouldAutoSelect, " +
                    "savedCandidate=$savedCandidateUri, " +
                    "bestCandidate=$bestCandidateUri, allSubs=$allSubUris",
            )
        }

        val (assUris, nonAssUris) = allSubUris.partition { uri ->
            val path = uri.lastPathSegment ?: uri.path ?: ""
            path.endsWith(".ass", ignoreCase = true) || path.endsWith(".ssa", ignoreCase = true)
        }
        if (shouldDiscoverSubtitles) {
            AssSubtitleState.availableAssFilesByMediaId[mediaItem.mediaId] = assUris
        }
        val bestAssUri = if (bestCandidateUri != null && bestCandidateUri in assUris) {
            bestCandidateUri
        } else {
            assUris.firstOrNull()
        }
        if (bestCandidateUri != null && bestCandidateUri == bestAssUri) {
            AssSubtitleState.autoSelectAssByMediaId[mediaItem.mediaId] = bestAssUri
        }
        if (shouldAutoSelect && bestCandidateUri != null) {
            mediaRepository.updateMediumSubtitleSelection(
                uri = mediaItem.mediaId,
                subtitleTrackIndex = if (bestCandidateUri == bestAssUri) -1 else null,
                selectedSubtitleUri = bestCandidateUri,
            )
        }
        val existingSubConfigurationKeys = existingSubConfigurations
            .flatMap { config -> listOfNotNull(config.id, config.uri.toString()) }
            .toSet()
        val subConfigurations = nonAssUris
            .filterNot { subtitleUri -> subtitleUri.toString() in existingSubConfigurationKeys }
            .map { subtitleUri ->
                uriToSubtitleConfiguration(
                    uri = subtitleUri,
                    subtitleEncoding = playerPreferences.subtitleTextEncoding,
                    isSelected = subtitleUri == bestCandidateUri,
                )
            }

        val existingArtworkUri = mediaItem.mediaMetadata.artworkUri
        val isDefaultArtwork = existingArtworkUri != null && existingArtworkUri.scheme == ContentResolver.SCHEME_ANDROID_RESOURCE
        val artworkUri = when {
            existingArtworkUri != null && !isDefaultArtwork -> existingArtworkUri
            else -> {
                val cloudThumbnail = resolveCloudVideoThumbnail(mediaItem.mediaId)
                cloudThumbnail ?: getDefaultArtworkUri()
            }
        }

        var resolvedDurationMs = video?.duration?.takeIf { it > 0L }
        if (resolvedDurationMs == null || resolvedDurationMs <= 0L) {
            resolvedDurationMs = resolveCloudVideoDuration(mediaItem.mediaId)
        }

        val title = mediaItem.mediaMetadata.title ?: video?.nameWithExtension ?: getFilenameFromUri(uri)
        val positionMs = mediaItem.mediaMetadata.positionMs ?: videoState?.position
        val videoScale = mediaItem.mediaMetadata.videoZoom ?: videoState?.videoScale
        val playbackSpeed = mediaItem.mediaMetadata.playbackSpeed ?: videoState?.playbackSpeed
        val audioTrackIndex = mediaItem.mediaMetadata.audioTrackIndex ?: videoState?.audioTrackIndex
        val subtitleTrackIndex = if (bestCandidateUri != null && bestCandidateUri in assUris) {
            -1
        } else {
            savedSubtitleTrackIndex
        }
        val selectedSubtitleUri = bestCandidateUri ?: savedSubtitleUri
        val subtitleDelay = mediaItem.mediaMetadata.subtitleDelayMilliseconds ?: videoState?.subtitleDelayMilliseconds
        val subtitleSpeed = mediaItem.mediaMetadata.subtitleSpeed ?: videoState?.subtitleSpeed

        return mediaItem.buildUpon().apply {
            setSubtitleConfigurations(existingSubConfigurations + subConfigurations)
            setMediaMetadata(
                MediaMetadata.Builder().apply {
                    setTitle(title)
                    setArtworkUri(artworkUri)
                    resolvedDurationMs?.let { setDurationMs(it) }
                    setExtras(
                        positionMs = positionMs,
                        videoScale = videoScale,
                        playbackSpeed = playbackSpeed,
                        audioTrackIndex = audioTrackIndex,
                        subtitleTrackIndex = subtitleTrackIndex,
                        selectedSubtitleUri = selectedSubtitleUri?.toString(),
                        subtitleDelayMilliseconds = subtitleDelay,
                        subtitleSpeed = subtitleSpeed,
                    )
                }.build(),
            )
        }.build()
    }

    private fun findBestSubtitleCandidate(subtitleUris: List<Uri>): Uri? {
        if (subtitleUris.isEmpty()) return null

        val chsPatterns = listOf(
            "chs", "sc", "简", "简体", "chi", "zh-hans", "zh_cn", "zh-cn",
            "chinese", "中文", "汉", "hans", "cn",
        )
        val assExtensions = setOf("ass", "ssa")

        for (uri in subtitleUris) {
            val name = (uri.lastPathSegment ?: uri.path ?: uri.toString()).lowercase()
            if (chsPatterns.any { name.contains(it, ignoreCase = true) }) {
                return uri
            }
        }

        val assSubs = subtitleUris.filter { uri ->
            val path = uri.path ?: uri.toString()
            assExtensions.any { path.endsWith(".$it", ignoreCase = true) }
        }
        if (assSubs.size == 1) return assSubs.first()

        return null
    }

    private fun findBestSubtitleCandidate(mediaItem: MediaItem, subtitleUris: List<Uri>): Uri? {
        if (subtitleUris.isEmpty()) return null

        val videoName = mediaItem.mediaMetadata.title?.toString()
            ?: getFilenameFromUri(mediaItem.mediaId.toUri())
        val scoredCandidates = subtitleUris.mapIndexedNotNull { index, uri ->
            scoreSubtitleCandidate(videoName, uri)?.let { score ->
                ScoredSubtitleCandidate(uri = uri, score = score, index = index)
            }
        }
        return scoredCandidates.maxWithOrNull(
            compareBy<ScoredSubtitleCandidate> { it.score }
                .thenBy { -it.index },
        )?.uri
    }

    private fun scoreSubtitleCandidate(videoName: String, subtitleUri: Uri): Int? {
        val subtitleName = subtitleUri.subtitleFileName()
        val titleScore = subtitleTitleScore(videoName, subtitleName) ?: return null
        val videoEpisode = extractSubtitleEpisode(videoName)
        val subtitleEpisode = extractSubtitleEpisode(subtitleName)
        if (videoEpisode != null && subtitleEpisode != null && videoEpisode != subtitleEpisode) return null

        val episodeScore = when {
            videoEpisode != null && subtitleEpisode == videoEpisode -> 100
            videoEpisode == null && subtitleEpisode == null -> 20
            else -> 0
        }
        return titleScore +
            episodeScore +
            subtitleLanguageScore(subtitleName) +
            subtitleExtensionScore(subtitleName)
    }

    private fun subtitleTitleScore(videoName: String, subtitleName: String): Int? {
        val normalizedVideoName = normalizeSubtitleMatchName(videoName)
        val normalizedSubtitleName = normalizeSubtitleMatchName(subtitleName)
        return when {
            normalizedVideoName.isNotEmpty() && normalizedVideoName == normalizedSubtitleName -> 80
            normalizedVideoName.isNotEmpty() && normalizedSubtitleName.startsWith(normalizedVideoName) -> 60
            fuzzyMatchNames(videoName, subtitleName) -> 30
            else -> null
        }
    }

    private fun subtitleLanguageScore(name: String): Int {
        val lowerName = name.lowercase()
        val compactName = lowerName.replace(Regex("""[\s._\-\[\]()]"""), "")
        val tokens = lowerName.split(Regex("""[^a-z0-9]+""")).filter { it.isNotBlank() }.toSet()
        val simplifiedTokens = setOf("sc", "chs", "gb", "gb2312", "gbk", "hans", "cn")
        val simplifiedTextMarkers = listOf("简", "简体", "简中", "中简", "zhhans", "zhcn")
        val chineseTextMarkers = listOf("中文", "双语")
        return when {
            tokens.any { it in simplifiedTokens } ||
                simplifiedTextMarkers.any { compactName.contains(it) } -> 60
            chineseTextMarkers.any { compactName.contains(it) } ||
                tokens.any { it == "chi" || it == "zh" || it == "chinese" } -> 30
            else -> 0
        }
    }

    private fun subtitleExtensionScore(name: String): Int = when {
        name.endsWith(".ass", ignoreCase = true) -> 20
        name.endsWith(".ssa", ignoreCase = true) -> 18
        name.endsWith(".srt", ignoreCase = true) -> 12
        name.endsWith(".vtt", ignoreCase = true) -> 8
        else -> 0
    }

    private fun normalizeSubtitleMatchName(name: String): String = name
        .substringBeforeLast('.', name)
        .replace(Regex("""(?i)\b(?:sc|chs|gb|gb2312|gbk|zh[-_ ]?hans|zh[-_ ]?cn|hans|cn|chi|chinese)\b"""), " ")
        .replace(Regex("""简体|简中|中简|中文|双语"""), " ")
        .replace(Regex("""(?i)\b(?:ass|ssa|srt|vtt|ttml)\b"""), " ")
        .replace(Regex("""[\s._\-\[\]()/+&|]+"""), " ")
        .trim()
        .lowercase()

    private fun extractSubtitleEpisode(name: String): Int? {
        val matches = SUBTITLE_EPISODE_REGEX.flatMap { regex -> regex.findAll(name).toList() }
        return matches
            .mapNotNull { match -> match.groupValues.getOrNull(1)?.toIntOrNull() }
            .firstOrNull { it !in COMMON_NON_EPISODE_NUMBERS }
    }

    private fun Uri.subtitleFileName(): String = lastPathSegment ?: path?.substringAfterLast('/') ?: toString()

    private fun getDefaultArtworkUri(): Uri = Uri.Builder().apply {
        val defaultArtwork = R.drawable.artwork_default
        scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
        authority(resources.getResourcePackageName(defaultArtwork))
        appendPath(resources.getResourceTypeName(defaultArtwork))
        appendPath(resources.getResourceEntryName(defaultArtwork))
    }.build()

    private suspend fun resolveChaptersForMediaItem(item: MediaItem) {
        try {
            val uri = item.localConfiguration?.uri?.toString() ?: item.mediaId
            if (MoovIndexCache.getChapters(uri).isNotEmpty()) return

            val path = uri.substringBefore('?').lowercase()
            Logger.d("BUG4_Chapters", "resolveChaptersForMediaItem: uri=${uri.take(80)}")
            when {
                path.endsWith(".mkv") || path.endsWith(".webm") -> {
                    mkvExtractor.loadParsedMkv(uri)
                    Logger.d("BUG4_Chapters", "MKV loadParsedMkv completed")
                }
                path.endsWith(".mp4") || path.endsWith(".mov") || path.endsWith(".m4v") -> {
                    mp4Extractor.loadParsedMoov(uri)
                    Logger.d("BUG4_Chapters", "MP4 loadParsedMoov completed")
                }
            }
        } catch (e: Exception) {
            Logger.e("BUG4_Chapters", "resolveChaptersForMediaItem failed: ${e.message}")
        }
    }

    private suspend fun resolveCloudVideoDuration(mediaId: String): Long? {
        MoovIndexCache.get(mediaId)?.durationMs?.takeIf { it > 0L }?.let { return it }
        return resolveCloudVideoMetadata(mediaId)?.durationMs?.takeIf { it > 0L }
    }

    @SuppressLint("UseKtx")
    private suspend fun resolveCloudVideoThumbnail(mediaId: String): Uri? = resolveCloudVideoMetadata(mediaId)?.thumbnailPath?.let { path ->
        runCatching {
            if (path.startsWith("http://", ignoreCase = true) ||
                path.startsWith("https://", ignoreCase = true)
            ) {
                path.toUri()
            } else {
                Uri.fromFile(File(path))
            }
        }.getOrNull()
    }

    private suspend fun resolveCloudVideoMetadata(mediaId: String): CloudVideoMetadata? {
        val url = runCatching { mediaId.toHttpUrl() }.getOrNull() ?: return null
        val matchedServer = findMatchingWebDavServer(url) ?: return null

        val candidates = buildList {
            add(normalizePath(url.encodedPath))
            add(normalizePath(Uri.decode(url.encodedPath)))

            val serverBase = normalizePath(matchedServer.basePath)
            val encodedRelative = normalizePath(url.encodedPath).removePrefix(serverBase)
            if (encodedRelative.isNotEmpty()) {
                add(normalizePath(encodedRelative))
            }
        }.distinct()

        for (candidate in candidates) {
            val metadata = cloudVideoMetadataRepository.getMetadata(matchedServer.id, listOf(candidate))
            val result = metadata[candidate]
            if (result != null) return result
        }
        return null
    }

    private fun loadArtworkForCurrentMediaItem() {
        artworkLoadJob?.cancel()
        artworkLoadJob = serviceScope.launch(Dispatchers.Main) {
            val player = mediaSession?.player ?: return@launch
            val currentMediaItem = player.currentMediaItem ?: return@launch
            if (currentMediaItem.mediaMetadata.artworkData != null) return@launch

            val artworkUri = loadArtworkForMediaItem(currentMediaItem) ?: return@launch

            val updatedPlayer = mediaSession?.player ?: return@launch
            val updatedMediaItem = updatedPlayer.currentMediaItem ?: return@launch
            if (updatedMediaItem.mediaId != currentMediaItem.mediaId) return@launch

            updatedPlayer.replaceMediaItem(
                updatedPlayer.currentMediaItemIndex,
                updatedMediaItem.withArtwork(artworkUri),
            )
        }
    }
    private suspend fun loadArtworkForMediaItem(mediaItem: MediaItem): Uri? = withContext(Dispatchers.IO) {
        val uri = mediaItem.mediaId.toUri()
        return@withContext try {
            val request = ImageRequest.Builder(this@PlayerService)
                .data(uri)
                .size(512, 512)
                .build()
            imageLoader.execute(request)
            val diskCache = imageLoader.diskCache ?: return@withContext null
            return@withContext diskCache.openSnapshot(uri.toString())?.use { snapshot ->
                snapshot.data.toFile().toUri()
            }
        } catch (_: Throwable) {
            null
        }
    }
    private fun MediaItem.withArtwork(uri: Uri): MediaItem = buildUpon()
        .setMediaMetadata(
            mediaMetadata.buildUpon()
                .setArtworkUri(uri)
                .build(),
        )
        .build()

    private suspend fun probeRemoteSubtitles(
        videoUrl: String,
        excludeSubs: List<Uri>,
    ): List<Uri> = withContext(Dispatchers.IO) {
        Logger.d("PlayerService", "probeRemoteSubtitles: videoUrl=$videoUrl")
        val excludeUrls = excludeSubs.mapNotNull { it.toString().takeIf { u -> u.startsWith("http") } }.toSet()
        val url = runCatching { videoUrl.toHttpUrl() }.getOrNull() ?: return@withContext emptyList()

        val cacheKey = remoteSubtitleProbeCacheKey(url)
        getCachedRemoteSubtitles(cacheKey)?.let { cached ->
            Logger.d("PlayerService", "probeRemoteSubtitles: cache hit ${cached.size} subtitles")
            return@withContext cached.filterNot { it.toString() in excludeUrls }
        }

        val pathSegments = url.encodedPathSegments
        if (pathSegments.isEmpty()) return@withContext emptyList()
        val fileName = pathSegments.last()
        val dotIndex = fileName.lastIndexOf('.')
        val baseName = if (dotIndex > 0) fileName.substring(0, dotIndex) else fileName
        if (baseName.isEmpty()) return@withContext emptyList()

        val apiServer = findMatchingWebDavServer(url)
        if (apiServer != null) {
            val apiResult = probeSubtitlesViaApi(url, baseName)
            Logger.d(
                "PlayerService",
                "probeRemoteSubtitles: API found ${apiResult.subtitles.size} subtitles authoritative=${apiResult.authoritative}",
            )
            if (apiResult.authoritative) {
                putCachedRemoteSubtitles(cacheKey, apiResult.subtitles)
                return@withContext apiResult.subtitles.filterNot { it.toString() in excludeUrls }
            }
        }

        val candidateExtensions = listOf("ass", "ssa", "srt", "vtt", "ttml")
        val langSuffixes = listOf(
            "sc", "chs", "cht", "en", "ja", "ko", "tc", "zh",
            "zh-hans", "zh-hant", "gb", "fr", "de", "es", "it",
            "pt", "pt-br", "ru", "ar",
        )
        val parentPath = if (pathSegments.size > 1) {
            pathSegments.dropLast(1).joinToString("/", "/", "/")
        } else {
            "/"
        }

        val results = mutableListOf<Uri>()
        for (ext in candidateExtensions) {
            doProbeHead(url, "$parentPath$baseName.$ext", excludeUrls, results)
        }
        for (lang in langSuffixes) {
            for (ext in listOf("ass", "ssa", "srt")) {
                doProbeHead(url, "$parentPath$baseName.$lang.$ext", excludeUrls, results)
            }
        }
        Logger.d("PlayerService", "probeRemoteSubtitles: found ${results.size} remote subtitles: $results")
        putCachedRemoteSubtitles(cacheKey, results)
        results
    }

    @SuppressLint("UseKtx")
    private suspend fun probeSubtitlesViaApi(
        url: HttpUrl,
        baseName: String,
    ): RemoteSubtitleProbeResult {
        val server = findMatchingWebDavServer(url) ?: run {
            Logger.d("PlayerService", "probeSubtitlesViaApi: no matching server for $url")
            return RemoteSubtitleProbeResult(subtitles = emptyList(), authoritative = false)
        }
        val pathSegments = url.encodedPathSegments
        val apiDirPath = if (pathSegments.size <= 2) {
            "/"
        } else {
            pathSegments.drop(1).dropLast(1).joinToString("/", "/", "/") { Uri.decode(it) }
        }
        Logger.d("PlayerService", "probeSubtitlesViaApi: server=${server.name}, baseName=$baseName, dir=$apiDirPath")
        val listResult = runCatching { openListApi.listDirectory(server, apiDirPath, page = 1, perPage = 2000, refresh = false) }
            .getOrNull()
        if (listResult == null || listResult.isFailure) {
            Logger.d("PlayerService", "probeSubtitlesViaApi: listDir failed for $apiDirPath")
            return RemoteSubtitleProbeResult(subtitles = emptyList(), authoritative = false)
        }
        val items = listResult.getOrNull()?.content.orEmpty()
        Logger.d(
            "PlayerService",
            "probeSubtitlesViaApi: listDir returned ${items.size} items, " +
                "success=${listResult.isSuccess}, " +
                "hasContent=${listResult.getOrNull()?.content != null}",
        )
        val rootBaseUrl = Uri.parse(server.url).let { serverUri ->
            val authority = if (serverUri.port != -1) "${serverUri.host}:${serverUri.port}" else serverUri.host.orEmpty()
            "${serverUri.scheme}://$authority"
        }
        val decodedBase = Uri.decode(baseName)
        val subtitles = items.filter { item ->
            if (item.is_dir) return@filter false
            val itemName = Uri.decode(item.name)
            val isSubExt = item.name.let { it.endsWith(".ass", true) || it.endsWith(".ssa", true) || it.endsWith(".srt", true) || it.endsWith(".vtt", true) || it.endsWith(".ttml", true) }
            if (!isSubExt) return@filter false

            itemName.startsWith(decodedBase) || fuzzyMatchNames(decodedBase, itemName)
        }.mapNotNull { item ->
            val encodedName = Uri.encode(Uri.decode(item.name))
            val encodedDirPath = apiDirPath.removePrefix("/")
                .split('/').filter { it.isNotBlank() }
                .joinToString("/") { Uri.encode(Uri.decode(it)) }
            val rawPath = if (apiDirPath == "/") "/d/$encodedName" else "/d/$encodedDirPath/$encodedName"
            val signSuffix = if (item.sign.isNotBlank()) "?sign=${item.sign}" else ""
            "$rootBaseUrl$rawPath$signSuffix".toUri()
        }
        return RemoteSubtitleProbeResult(subtitles = subtitles, authoritative = true)
    }

    @SuppressLint("UseKtx")
    private fun doProbeHead(
        baseUrl: HttpUrl,
        path: String,
        excludeUrls: Set<String>,
        results: MutableList<Uri>,
    ) {
        val candidateWithQuery = baseUrl.newBuilder().encodedPath(path).build()
        val candidateWithoutQuery = baseUrl.newBuilder().encodedPath(path).encodedQuery(null).build()

        for (candidateUrl in listOf(candidateWithQuery, candidateWithoutQuery)) {
            val candStr = candidateUrl.toString()
            if (candStr in excludeUrls) continue
            try {
                val request = Request.Builder().url(candidateUrl).method("HEAD", null).build()
                val response = okHttpClient.newCall(request).execute()
                Logger.d("PlayerService", "probeRemoteSubtitles HEAD: $candStr → ${response.code}")
                if (response.isSuccessful) {
                    results.add(candStr.toUri())
                    response.close()
                    return
                }
                response.close()
            } catch (e: Exception) {
                Logger.d("PlayerService", "probeRemoteSubtitles HEAD: $candStr → ${e.message}")
            }
        }
    }

    private fun getCachedRemoteSubtitles(cacheKey: String): List<Uri>? = synchronized(remoteSubtitleProbeCacheLock) {
        val entry = remoteSubtitleProbeCache[cacheKey] ?: return@synchronized null
        if (System.currentTimeMillis() - entry.cachedAtMs > REMOTE_SUBTITLE_PROBE_CACHE_TTL_MS) {
            remoteSubtitleProbeCache.remove(cacheKey)
            null
        } else {
            entry.subtitles
        }
    }

    private fun putCachedRemoteSubtitles(cacheKey: String, subtitles: List<Uri>) {
        synchronized(remoteSubtitleProbeCacheLock) {
            remoteSubtitleProbeCache[cacheKey] = RemoteSubtitleProbeCacheEntry(
                subtitles = subtitles,
                cachedAtMs = System.currentTimeMillis(),
            )
        }
    }

    private fun remoteSubtitleProbeCacheKey(url: HttpUrl): String = url.newBuilder()
        .username("")
        .password("")
        .encodedQuery(null)
        .fragment(null)
        .build()
        .toString()

    private fun createStreamingCache(): SimpleCache? = runCatching {
        SimpleCache(
            File(cacheDir, STREAMING_CACHE_DIR),
            LeastRecentlyUsedCacheEvictor(STREAMING_CACHE_MAX_BYTES),
            StandaloneDatabaseProvider(applicationContext),
        )
    }.onFailure { error ->
        Logger.w(TAG, "Failed to create streaming cache", error)
    }.getOrNull()

    private fun Request.withStreamingNetworkHeaders(): Request {
        var builder = newBuilder()
        if (header("Range") != null) {
            builder = builder.header("Accept-Encoding", "identity")
        }
        if (Utils.isBaiduNetdiskUrl(url.toString())) {
            builder = builder.header("User-Agent", "pan.baidu.com")
        }
        return builder.build()
    }

    private fun Player.findSubtitleTrackIndexByUri(uri: Uri): Int? {
        val target = uri.toString()
        val subtitleConfigs = currentMediaItem?.localConfiguration?.subtitleConfigurations.orEmpty()
        val matchingConfig = subtitleConfigs.firstOrNull { config ->
            config.id == target || config.uri.toString() == target
        } ?: return null
        val matchingId = matchingConfig.id ?: matchingConfig.uri.toString()
        return currentTracks.groups
            .filter { it.type == C.TRACK_TYPE_TEXT }
            .indexOfFirst { group ->
                group.mediaTrackGroup.getFormat(0).id == matchingId
            }
            .takeIf { it >= 0 }
    }

    private fun Player.findSubtitleUriByTrackIndex(trackIndex: Int): Uri? {
        val trackGroup = currentTracks.groups
            .filter { it.type == C.TRACK_TYPE_TEXT }
            .getOrNull(trackIndex) ?: return null
        val trackId = trackGroup.mediaTrackGroup.getFormat(0).id
        val matchingConfig = currentMediaItem
            ?.localConfiguration
            ?.subtitleConfigurations
            .orEmpty()
            .firstOrNull { config ->
                config.id == trackId || config.uri.toString() == trackId
            } ?: return null
        return (matchingConfig.id ?: matchingConfig.uri.toString()).toUri()
    }

    private fun buildAuthorizationHeader(url: HttpUrl): String? {
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

    @SuppressLint("UseKtx")
    private fun findMatchingWebDavServer(url: HttpUrl): WebDavServer? {
        val normalizedPath = normalizePath(url.encodedPath)

        val pathMatch = webDavServersById.values
            .asSequence()
            .filter { server ->
                val serverUri = server.url.toUri()
                val serverScheme = serverUri.scheme.orEmpty()
                val serverHost = serverUri.host.orEmpty()
                if (!serverScheme.equals(url.scheme, ignoreCase = true)) return@filter false
                if (!serverHost.equals(url.host, ignoreCase = true)) return@filter false
                val serverPort = if (serverUri.port != -1) serverUri.port else defaultPort(serverScheme)
                val requestPort = if (url.port != -1) url.port else defaultPort(url.scheme)
                if (serverPort != requestPort) return@filter false
                normalizedPath.startsWith(normalizePath(server.basePath))
            }
            .maxByOrNull { normalizePath(it.basePath).length }
        if (pathMatch != null) return pathMatch

        return webDavServersById.values.firstOrNull { server ->
            val serverUri = Uri.parse(server.url)
            serverUri.scheme.equals(url.scheme, ignoreCase = true) &&
                serverUri.host.equals(url.host, ignoreCase = true) &&
                (serverUri.port.let { if (it != -1) it else defaultPort(serverUri.scheme.orEmpty()) }) ==
                (url.port.let { if (it != -1) it else defaultPort(url.scheme) })
        }
    }

    private fun normalizePath(path: String): String {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return "/"
        val withLeadingSlash = if (trimmed.startsWith('/')) trimmed else "/$trimmed"
        return withLeadingSlash.removeSuffix("/").ifBlank { "/" }
    }

    private fun defaultPort(scheme: String): Int = if (scheme.equals("https", ignoreCase = true)) 443 else 80

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

private data class RemoteSubtitleProbeResult(
    val subtitles: List<Uri>,
    val authoritative: Boolean,
)

private data class RemoteSubtitleProbeCacheEntry(
    val subtitles: List<Uri>,
    val cachedAtMs: Long,
)

private data class ScoredSubtitleCandidate(
    val uri: Uri,
    val score: Int,
    val index: Int,
)

@get:UnstableApi
@set:UnstableApi
private var Player.playerSpecificSkipSilenceEnabled: Boolean
    @OptIn(UnstableApi::class)
    get() = when (this) {
        is ExoPlayer -> this.skipSilenceEnabled
        else -> false
    }
    set(value) {
        when (this) {
            is ExoPlayer -> this.skipSilenceEnabled = value
        }
    }

@get:UnstableApi
@set:UnstableApi
private var Player.playerSpecificSubtitleDelayMilliseconds: Long
    @OptIn(UnstableApi::class)
    get() = when (this) {
        is ExoPlayer -> this.subtitleDelayMilliseconds
        else -> 0L
    }
    set(value) {
        when (this) {
            is ExoPlayer -> this.subtitleDelayMilliseconds = value
        }
    }

@get:UnstableApi
@set:UnstableApi
private var Player.playerSpecificSubtitleSpeed: Float
    @OptIn(UnstableApi::class)
    get() = when (this) {
        is ExoPlayer -> this.subtitleSpeed
        else -> 0f
    }
    set(value) {
        when (this) {
            is ExoPlayer -> this.subtitleSpeed = value
        }
    }

@OptIn(UnstableApi::class)
private fun ExoPlayer.applyVideoEffects(preferences: PlayerPreferences, maxLuma: Float? = null) {
    val effects = preferences.buildVideoEffects(maxLuma)
    Logger.i(TAG, "Effect pipeline (${effects.size} active): ${effects.joinToString(" -> ") { it::class.simpleName ?: "?" }}")
    Logger.i(TAG, "DownscalePre=${preferences.anime4KAutoDownscalePreMode} Upscale=${preferences.anime4KUpscaleMode} HdrMaxLuma=$maxLuma")
    setVideoEffects(effects)
}

private fun PlayerPreferences.buildVideoEffects(maxLuma: Float?): List<GlEffect> = videoEffectsOrder.mapNotNull { type ->
    when (type) {
        VideoEffectType.AUTODOWNSCALEPRE -> null
        VideoEffectType.UPSCALE -> anime4KUpscaleMode.takeIf { it != Anime4KUpscaleMode.OFF }
            ?.let { Anime4KUpscaleEffect(it, anime4KAutoDownscalePreMode) }
        VideoEffectType.RESTORE -> anime4KRestoreMode.takeIf { it != Anime4KRestoreMode.OFF }
            ?.let { Anime4KRestoreEffect(it) }
        VideoEffectType.DEBAND -> if (enableDeband) DebandEffect(maxLuma = maxLuma) else null
        VideoEffectType.CLAMP_HIGHLIGHTS -> if (enableAnime4KClampHighlights) Anime4KClampHighlightsEffect() else null
        VideoEffectType.DITHER -> if (enableDither) DitherEffect() else null
    }
}

private fun PlayerPreferences.videoEffectsKey(): List<Any> = listOf(
    anime4KRestoreMode,
    anime4KAutoDownscalePreMode,
    anime4KUpscaleMode,
    enableAnime4KClampHighlights,
    enableDeband,
    enableDither,
    videoEffectsOrder,
)

@OptIn(UnstableApi::class)
private class ScrubbingAwareLoadControl(
    allocator: DefaultAllocator,
    normalMinBufferMs: Int,
    normalMaxBufferMs: Int,
    normalBufferForPlaybackMs: Int,
    normalBufferForPlaybackAfterRebufferMs: Int,
) : LoadControl {

    @Volatile
    var isScrubbing: Boolean = false

    private val normal = DefaultLoadControl.Builder()
        .setAllocator(allocator)
        .setBufferDurationsMs(
            normalMinBufferMs,
            normalMaxBufferMs,
            normalBufferForPlaybackMs,
            normalBufferForPlaybackAfterRebufferMs,
        )
        .build()

    private val scrub = DefaultLoadControl.Builder()
        .setAllocator(allocator)
        .setBufferDurationsMs(
            SCRUB_MIN_BUFFER_MS,
            SCRUB_MAX_BUFFER_MS,
            SCRUB_BUFFER_FOR_PLAYBACK_MS,
            SCRUB_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
        )
        .build()

    private val delegate: DefaultLoadControl
        get() = if (isScrubbing) scrub else normal

    override fun onPrepared(playerId: PlayerId) {
        normal.onPrepared(playerId)
        scrub.onPrepared(playerId)
    }

    override fun onStopped(playerId: PlayerId) = delegate.onStopped(playerId)

    override fun onReleased(playerId: PlayerId) {
        normal.onReleased(playerId)
        scrub.onReleased(playerId)
    }

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean = delegate.shouldStartPlayback(parameters)

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean = delegate.shouldContinueLoading(parameters)

    override fun getAllocator(playerId: PlayerId): Allocator = delegate.getAllocator(playerId)

    override fun getBackBufferDurationUs(playerId: PlayerId): Long = delegate.getBackBufferDurationUs(playerId)

    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean = delegate.retainBackBufferFromKeyframe(playerId)

    override fun onTracksSelected(
        parameters: LoadControl.Parameters,
        trackGroups: TrackGroupArray,
        trackSelections: Array<out ExoTrackSelection?>,
    ) {
        delegate.onTracksSelected(parameters, trackGroups, trackSelections)
    }

    companion object {

        private const val SCRUB_MIN_BUFFER_MS = 1500

        private const val SCRUB_MAX_BUFFER_MS = 4000

        private const val SCRUB_BUFFER_FOR_PLAYBACK_MS = 300

        private const val SCRUB_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 1500
    }
}

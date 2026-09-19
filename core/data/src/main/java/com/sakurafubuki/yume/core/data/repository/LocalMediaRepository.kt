package com.sakurafubuki.yume.core.data.repository

import android.net.Uri
import com.sakurafubuki.yume.core.common.Utils
import com.sakurafubuki.yume.core.data.mappers.toFolder
import com.sakurafubuki.yume.core.data.mappers.toVideo
import com.sakurafubuki.yume.core.data.mappers.toVideoState
import com.sakurafubuki.yume.core.data.models.VideoState
import com.sakurafubuki.yume.core.data.webdav.findMatchingWebDavServer
import com.sakurafubuki.yume.core.data.webdav.normalizeWebDavPath
import com.sakurafubuki.yume.core.database.dao.DirectoryDao
import com.sakurafubuki.yume.core.database.dao.MediumDao
import com.sakurafubuki.yume.core.database.dao.MediumStateDao
import com.sakurafubuki.yume.core.database.dao.WebDavDirectoryItemDao
import com.sakurafubuki.yume.core.database.entities.MediumStateEntity
import com.sakurafubuki.yume.core.database.entities.WebDavDirectoryItemEntity
import com.sakurafubuki.yume.core.database.relations.DirectoryWithMedia
import com.sakurafubuki.yume.core.database.relations.MediumWithInfo
import com.sakurafubuki.yume.core.model.CloudVideoMetadata
import com.sakurafubuki.yume.core.model.Folder
import com.sakurafubuki.yume.core.model.Video
import com.sakurafubuki.yume.core.model.WebDavServer
import java.util.Date
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

class LocalMediaRepository @Inject constructor(
    private val mediumDao: MediumDao,
    private val mediumStateDao: MediumStateDao,
    private val directoryDao: DirectoryDao,
    private val webDavServerRepository: WebDavServerRepository,
    private val cloudVideoMetadataRepository: CloudVideoMetadataRepository,
    private val webDavDirectoryItemDao: WebDavDirectoryItemDao,
) : MediaRepository {

    companion object {
        private const val MAX_MEDIA_LOAD_COUNT = 5000
    }

    override fun getVideosFlow(): Flow<List<Video>> = mediumDao.getAllWithInfoPaginated(limit = MAX_MEDIA_LOAD_COUNT, offset = 0)
        .map { it.map(MediumWithInfo::toVideo) }
        .distinctUntilChanged()

    override fun getVideosFlowFromFolderPath(folderPath: String): Flow<List<Video>> = mediumDao.getAllWithInfoFromDirectoryPaginated(
        directoryPath = folderPath,
        limit = MAX_MEDIA_LOAD_COUNT,
        offset = 0,
    ).map { it.map(MediumWithInfo::toVideo) }
        .distinctUntilChanged()

    override fun getFoldersFlow(): Flow<List<Folder>> = directoryDao.getAllWithMediaPaginated(limit = MAX_MEDIA_LOAD_COUNT, offset = 0)
        .map { it.map(DirectoryWithMedia::toFolder) }
        .distinctUntilChanged()

    override fun getRecentlyPlayedVideosFlow(limit: Int): Flow<List<Video>> = combine(
        mediumDao.getRecentlyPlayedWithInfo(limit = limit)
            .map { it.map(MediumWithInfo::toVideo) },
        observeCloudRecentlyPlayed(limit = limit),
    ) { local, cloud ->
        (local + cloud)
            .sortedByDescending { it.lastPlayedAt?.time ?: Long.MIN_VALUE }
            .take(limit)
    }.distinctUntilChanged()

    /**
     * WebDAV videos never appear in the local [MediumDao] table, so the recent list
     * must also read [MediumStateDao] rows whose uri is an http(s) URL and merge in
     * cloud metadata (duration/size/thumbnail) when the cloud scan already captured it.
     */
    private fun observeCloudRecentlyPlayed(limit: Int): Flow<List<Video>> = webDavServerRepository.observeServers()
        .distinctUntilChanged()
        .flatMapLatest { servers ->
            mediumStateDao.observeRecentlyPlayedStates(limit = limit)
                .flatMapLatest { states ->
                    flow {
                        emit(buildCloudRecentlyPlayedVideos(states, servers))
                    }
                }
        }

    private suspend fun buildCloudRecentlyPlayedVideos(
        states: List<MediumStateEntity>,
        servers: List<WebDavServer>,
    ): List<Video> {
        val httpStates = states.filter { it.uriString.isHttpUrl() }
        if (httpStates.isEmpty()) return emptyList()

        val videos = mutableListOf<Video>()
        for (state in httpStates) {
            val url = runCatching { state.uriString.toHttpUrl() }.getOrNull()
            val server = url?.let { findMatchingWebDavServer(servers, it) }
            // Directory items store decoded parent paths and names, so locate the file
            // by decoded path + name; its stored href (same scan's signed URL) then keys
            // the video metadata lookup without depending on the current sign value.
            val directoryItem = if (server != null && url != null) {
                val fileName = Uri.decode(url.encodedPath.substringAfterLast('/'))
                parentCandidates(url).firstNotNullOfOrNull { parent ->
                    webDavDirectoryItemDao.getByParent(server.id, parent)
                        .firstOrNull { it.name == fileName }
                }
            } else {
                null
            }
            val metadata = if (server != null && directoryItem != null) {
                cloudVideoMetadataRepository.getMetadata(server.id, listOf(directoryItem.href))[directoryItem.href]
            } else {
                null
            }
            videos += cloudVideoFromState(state, metadata, directoryItem)
        }
        return videos
    }

    private fun parentCandidates(url: HttpUrl): List<String> = buildList {
        val decoded = Uri.decode(url.encodedPath)
        // OpenList URLs carry a /d prefix which the scan stores without; keep both forms.
        add(normalizeWebDavPath(decoded.removePrefix("/d").substringBeforeLast('/').ifBlank { "/" }))
        add(normalizeWebDavPath(decoded.substringBeforeLast('/')))
    }.distinct()

    private fun cloudVideoFromState(
        state: MediumStateEntity,
        metadata: CloudVideoMetadata?,
        directoryItem: WebDavDirectoryItemEntity?,
    ): Video {
        val uri = state.uriString
        val name = Uri.decode(uri.substringAfterLast('/').substringBefore('?')).ifBlank { uri }
        val durationMs = metadata?.durationMs?.takeIf { it > 0L } ?: 0L
        val size = directoryItem?.size ?: 0L
        val width = metadata?.width?.takeIf { it > 0 } ?: directoryItem?.width ?: 0
        val height = metadata?.height?.takeIf { it > 0 } ?: directoryItem?.height ?: 0
        // Prefer the locally cached scan thumbnail; fall back to the server's own
        // thumbnail URL (api_thumbnail_url) which coil can fetch directly.
        val thumbnail = metadata?.thumbnailPath
            ?: directoryItem?.apiThumbnailUrl
        return Video(
            id = 0L,
            path = uri,
            uriString = uri,
            nameWithExtension = name,
            duration = durationMs,
            width = width,
            height = height,
            size = size,
            playbackPosition = state.playbackPosition,
            lastPlayedAt = state.lastPlayedTime?.let(::Date),
            formattedDuration = Utils.formatDurationMillis(durationMs),
            formattedFileSize = size.takeIf { it > 0L }?.let(Utils::formatFileSize) ?: "",
            thumbnailUriString = thumbnail,
        )
    }

    private fun String.isHttpUrl(): Boolean = startsWith("http://", ignoreCase = true) || startsWith("https://", ignoreCase = true)

    override suspend fun getVideoByUri(uri: String): Video? = mediumDao.getWithInfo(uri)?.toVideo()

    override suspend fun getVideoState(uri: String): VideoState? = mediumStateDao.get(uri)?.toVideoState()

    private suspend fun ensureStateRow(uri: String) {
        mediumStateDao.insertIgnore(MediumStateEntity(uriString = uri))
    }

    override suspend fun updateMediumLastPlayedTime(uri: String, lastPlayedTime: Long) {
        ensureStateRow(uri)
        mediumStateDao.updateLastPlayedTime(uri, lastPlayedTime)
    }

    override suspend fun clearRecentlyPlayed(uri: String) {
        mediumStateDao.clearLastPlayedTime(uri)
    }

    override suspend fun updateMediumPosition(uri: String, position: Long) {
        ensureStateRow(uri)
        mediumStateDao.updatePosition(
            uri = uri,
            position = position,
            lastPlayedTime = System.currentTimeMillis(),
        )
    }

    override suspend fun updateMediumPlaybackSpeed(uri: String, playbackSpeed: Float) {
        ensureStateRow(uri)
        mediumStateDao.updatePlaybackSpeed(
            uri = uri,
            playbackSpeed = playbackSpeed,
            lastPlayedTime = System.currentTimeMillis(),
        )
    }

    override suspend fun updateMediumAudioTrack(uri: String, audioTrackIndex: Int) {
        ensureStateRow(uri)
        mediumStateDao.updateAudioTrack(
            uri = uri,
            audioTrackIndex = audioTrackIndex,
            lastPlayedTime = System.currentTimeMillis(),
        )
    }

    override suspend fun updateMediumSubtitleTrack(uri: String, subtitleTrackIndex: Int) {
        updateMediumSubtitleSelection(
            uri = uri,
            subtitleTrackIndex = subtitleTrackIndex,
            selectedSubtitleUri = null,
        )
    }

    override suspend fun updateMediumSubtitleSelection(
        uri: String,
        subtitleTrackIndex: Int?,
        selectedSubtitleUri: Uri?,
    ) {
        ensureStateRow(uri)
        mediumStateDao.updateSubtitleSelection(
            uri = uri,
            subtitleTrackIndex = subtitleTrackIndex,
            selectedSubtitleUri = selectedSubtitleUri?.toString(),
            lastPlayedTime = System.currentTimeMillis(),
        )
    }

    override suspend fun updateMediumZoom(uri: String, zoom: Float) {
        ensureStateRow(uri)
        mediumStateDao.updateZoom(
            uri = uri,
            zoom = zoom,
            lastPlayedTime = System.currentTimeMillis(),
        )
    }

    override suspend fun addExternalSubtitleToMedium(uri: String, subtitleUri: Uri) {
        mediumStateDao.addExternalSubtitle(
            uri = uri,
            subtitleUri = subtitleUri,
            lastPlayedTime = System.currentTimeMillis(),
        )
    }

    override suspend fun updateSubtitleDelay(uri: String, delay: Long) {
        ensureStateRow(uri)
        mediumStateDao.updateSubtitleDelay(
            uri = uri,
            delay = delay,
            lastPlayedTime = System.currentTimeMillis(),
        )
    }

    override suspend fun updateSubtitleSpeed(uri: String, speed: Float) {
        ensureStateRow(uri)
        mediumStateDao.updateSubtitleSpeed(
            uri = uri,
            speed = speed,
            lastPlayedTime = System.currentTimeMillis(),
        )
    }
}

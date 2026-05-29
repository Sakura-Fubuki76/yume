package com.sakurafubuki.yume.core.data.repository

import android.net.Uri
import com.sakurafubuki.yume.core.data.mappers.toFolder
import com.sakurafubuki.yume.core.data.mappers.toVideo
import com.sakurafubuki.yume.core.data.mappers.toVideoState
import com.sakurafubuki.yume.core.data.models.VideoState
import com.sakurafubuki.yume.core.database.dao.DirectoryDao
import com.sakurafubuki.yume.core.database.dao.MediumDao
import com.sakurafubuki.yume.core.database.dao.MediumStateDao
import com.sakurafubuki.yume.core.database.entities.MediumStateEntity
import com.sakurafubuki.yume.core.database.relations.DirectoryWithMedia
import com.sakurafubuki.yume.core.database.relations.MediumWithInfo
import com.sakurafubuki.yume.core.model.Folder
import com.sakurafubuki.yume.core.model.Video
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class LocalMediaRepository @Inject constructor(
    private val mediumDao: MediumDao,
    private val mediumStateDao: MediumStateDao,
    private val directoryDao: DirectoryDao,
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

    override suspend fun getVideoByUri(uri: String): Video? = mediumDao.getWithInfo(uri)?.toVideo()

    override suspend fun getVideoState(uri: String): VideoState? = mediumStateDao.get(uri)?.toVideoState()

    private suspend fun ensureStateRow(uri: String) {
        mediumStateDao.insertIgnore(MediumStateEntity(uriString = uri))
    }

    override suspend fun updateMediumLastPlayedTime(uri: String, lastPlayedTime: Long) {
        ensureStateRow(uri)
        mediumStateDao.updateLastPlayedTime(uri, lastPlayedTime)
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

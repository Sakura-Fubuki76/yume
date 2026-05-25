package com.sakurafubuki.yume.core.data.repository.fake

import android.net.Uri
import com.sakurafubuki.yume.core.data.models.VideoState
import com.sakurafubuki.yume.core.data.repository.MediaRepository
import com.sakurafubuki.yume.core.model.Folder
import com.sakurafubuki.yume.core.model.Video
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class FakeMediaRepository : MediaRepository {

    val videos = mutableListOf<Video>()
    val directories = mutableListOf<Folder>()

    override fun getVideosFlow(): Flow<List<Video>> = flowOf(videos)

    override fun getVideosFlowFromFolderPath(folderPath: String): Flow<List<Video>> = flowOf(videos)

    override fun getFoldersFlow(): Flow<List<Folder>> = flowOf(directories)

    override suspend fun getVideoByUri(uri: String): Video? = videos.find { it.path == uri }

    override suspend fun getVideoState(uri: String): VideoState? = null

    override suspend fun updateMediumLastPlayedTime(uri: String, lastPlayedTime: Long) {
    }

    override suspend fun updateMediumPosition(uri: String, position: Long) {
    }

    override suspend fun updateMediumPlaybackSpeed(uri: String, playbackSpeed: Float) {
    }

    override suspend fun updateMediumAudioTrack(uri: String, audioTrackIndex: Int) {
    }

    override suspend fun updateMediumSubtitleTrack(uri: String, subtitleTrackIndex: Int) {
    }

    override suspend fun updateMediumSubtitleSelection(uri: String, subtitleTrackIndex: Int?, selectedSubtitleUri: Uri?) {
    }

    override suspend fun updateMediumZoom(uri: String, zoom: Float) {
    }

    override suspend fun addExternalSubtitleToMedium(uri: String, subtitleUri: Uri) {
    }

    override suspend fun updateSubtitleDelay(uri: String, delay: Long) {
    }

    override suspend fun updateSubtitleSpeed(uri: String, speed: Float) {
    }
}

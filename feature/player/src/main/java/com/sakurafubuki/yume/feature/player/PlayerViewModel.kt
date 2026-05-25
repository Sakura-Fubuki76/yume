package com.sakurafubuki.yume.feature.player

import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sakurafubuki.yume.core.data.repository.MediaRepository
import com.sakurafubuki.yume.core.data.repository.PreferencesRepository
import com.sakurafubuki.yume.core.domain.GetSortedPlaylistUseCase
import com.sakurafubuki.yume.core.model.Anime4KAutoDownscalePreMode
import com.sakurafubuki.yume.core.model.Anime4KRestoreMode
import com.sakurafubuki.yume.core.model.Anime4KUpscaleMode
import com.sakurafubuki.yume.core.model.LoopMode
import com.sakurafubuki.yume.core.model.PlayerPreferences
import com.sakurafubuki.yume.core.model.Video
import com.sakurafubuki.yume.core.model.VideoContentScale
import com.sakurafubuki.yume.feature.player.ass.AssSubtitleState
import com.sakurafubuki.yume.feature.player.state.SubtitleOptionsEvent
import com.sakurafubuki.yume.feature.player.state.VideoZoomEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val mediaRepository: MediaRepository,
    private val preferencesRepository: PreferencesRepository,
    private val getSortedPlaylistUseCase: GetSortedPlaylistUseCase,
) : ViewModel() {

    var playWhenReady: Boolean = true

    val assState = AssSubtitleState()

    private val internalUiState = MutableStateFlow(
        PlayerUiState(
            playerPreferences = preferencesRepository.playerPreferences.value,
        ),
    )
    val uiState = internalUiState.asStateFlow()

    init {
        viewModelScope.launch {
            preferencesRepository.playerPreferences.collect { prefs ->
                internalUiState.update { it.copy(playerPreferences = prefs) }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        assState.release()
    }

    suspend fun getPlaylistFromUri(uri: Uri): List<Video> = getSortedPlaylistUseCase.invoke(uri)

    fun updateVideoZoom(uri: String, zoom: Float) {
        viewModelScope.launch {
            mediaRepository.updateMediumZoom(uri, zoom)
        }
    }

    fun updatePlayerBrightness(value: Float) {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences { it.copy(playerBrightness = value) }
        }
    }

    fun updateVideoContentScale(contentScale: VideoContentScale) {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences { it.copy(playerVideoZoom = contentScale) }
        }
    }

    fun setLoopMode(loopMode: LoopMode) {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences { it.copy(loopMode = loopMode) }
        }
    }

    fun toggleAnime4KEffects() {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences { preferences ->
                if (preferences.isAnime4KEnabled()) {
                    preferences.copy(
                        anime4KRestoreMode = Anime4KRestoreMode.OFF,
                        anime4KAutoDownscalePreMode = Anime4KAutoDownscalePreMode.OFF,
                        anime4KUpscaleMode = Anime4KUpscaleMode.OFF,
                        enableAnime4KClampHighlights = false,
                    )
                } else {
                    preferences.copy(
                        anime4KRestoreMode = Anime4KRestoreMode.M,
                        anime4KAutoDownscalePreMode = Anime4KAutoDownscalePreMode.X2,
                        anime4KUpscaleMode = Anime4KUpscaleMode.CNN_X2_M,
                        enableAnime4KClampHighlights = true,
                    )
                }
            }
        }
    }

    fun onVideoZoomEvent(event: VideoZoomEvent) {
        when (event) {
            is VideoZoomEvent.ContentScaleChanged -> {
                updateVideoContentScale(event.contentScale)
            }
            is VideoZoomEvent.ZoomChanged -> {
                updateVideoZoom(event.mediaItem.mediaId, event.zoom)
            }
        }
    }

    fun onSubtitleOptionEvent(event: SubtitleOptionsEvent) {
        when (event) {
            is SubtitleOptionsEvent.DelayChanged -> {
                updateSubtitleDelay(event.mediaItem.mediaId, event.delay)
            }
            is SubtitleOptionsEvent.SpeedChanged -> {
                updateSubtitleSpeed(event.mediaItem.mediaId, event.speed)
            }
        }
    }

    fun updateSelectedSubtitle(uri: String, subtitleTrackIndex: Int?, selectedSubtitleUri: Uri?) {
        viewModelScope.launch {
            mediaRepository.updateMediumSubtitleSelection(
                uri = uri,
                subtitleTrackIndex = subtitleTrackIndex,
                selectedSubtitleUri = selectedSubtitleUri,
            )
        }
    }

    private fun updateSubtitleDelay(uri: String, delay: Long) {
        viewModelScope.launch {
            mediaRepository.updateSubtitleDelay(uri, delay)
        }
    }

    private fun updateSubtitleSpeed(uri: String, speed: Float) {
        viewModelScope.launch {
            mediaRepository.updateSubtitleSpeed(uri, speed)
        }
    }
}

@Stable
data class PlayerUiState(
    val playerPreferences: PlayerPreferences? = null,
)

sealed interface PlayerEvent

private fun PlayerPreferences.isAnime4KEnabled(): Boolean = anime4KRestoreMode != Anime4KRestoreMode.OFF ||
    anime4KAutoDownscalePreMode != Anime4KAutoDownscalePreMode.OFF ||
    anime4KUpscaleMode != Anime4KUpscaleMode.OFF ||
    enableAnime4KClampHighlights

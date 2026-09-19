package com.sakurafubuki.yume.feature.videopicker.screens.recent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sakurafubuki.yume.core.data.repository.MediaRepository
import com.sakurafubuki.yume.core.data.repository.PreferencesRepository
import com.sakurafubuki.yume.core.model.ApplicationPreferences
import com.sakurafubuki.yume.core.model.Video
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class RecentViewModel @Inject constructor(
    private val mediaRepository: MediaRepository,
    private val preferencesRepository: PreferencesRepository,
) : ViewModel() {

    val uiState: StateFlow<RecentUiState> = combine(
        mediaRepository.getRecentlyPlayedVideosFlow(limit = MAX_RECENT_VIDEOS),
        preferencesRepository.applicationPreferences,
    ) { videos, preferences ->
        RecentUiState(
            videos = videos,
            preferences = preferences,
            loaded = true,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = RecentUiState(),
    )

    fun onClearRecentlyPlayed(uri: String) {
        viewModelScope.launch {
            mediaRepository.clearRecentlyPlayed(uri)
        }
    }

    private companion object {
        const val MAX_RECENT_VIDEOS = 100
    }
}

data class RecentUiState(
    val videos: List<Video> = emptyList(),
    val preferences: ApplicationPreferences = ApplicationPreferences(),
    val loaded: Boolean = false,
)

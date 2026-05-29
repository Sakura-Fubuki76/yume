package com.sakurafubuki.yume.core.database.dao

import android.net.Uri
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.sakurafubuki.yume.core.database.converter.UriListConverter
import com.sakurafubuki.yume.core.database.entities.MediumStateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MediumStateDao {

    @Upsert
    suspend fun upsert(mediumState: MediumStateEntity)

    @Upsert
    suspend fun upsertAll(mediaStates: List<MediumStateEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(mediumState: MediumStateEntity): Long

    @Query("SELECT * FROM media_state WHERE uri = :uri")
    suspend fun get(uri: String): MediumStateEntity?

    @Query("SELECT * FROM media_state WHERE uri = :uri")
    fun getAsFlow(uri: String): Flow<MediumStateEntity?>

    @Query("SELECT * FROM media_state")
    fun getAll(): Flow<List<MediumStateEntity>>

    @Query("SELECT * FROM media_state WHERE uri IN (:uris)")
    suspend fun getAllByUris(uris: List<String>): List<MediumStateEntity>

    @Query("UPDATE media_state SET last_played_time = :lastPlayedTime WHERE uri = :uri")
    suspend fun updateLastPlayedTime(uri: String, lastPlayedTime: Long): Int

    @Query(
        "UPDATE media_state SET playback_position = :position, last_played_time = :lastPlayedTime WHERE uri = :uri",
    )
    suspend fun updatePosition(uri: String, position: Long, lastPlayedTime: Long): Int

    @Query(
        "UPDATE media_state SET playback_speed = :playbackSpeed, last_played_time = :lastPlayedTime WHERE uri = :uri",
    )
    suspend fun updatePlaybackSpeed(uri: String, playbackSpeed: Float, lastPlayedTime: Long): Int

    @Query(
        "UPDATE media_state SET audio_track_index = :audioTrackIndex, last_played_time = :lastPlayedTime WHERE uri = :uri",
    )
    suspend fun updateAudioTrack(uri: String, audioTrackIndex: Int, lastPlayedTime: Long): Int

    @Query(
        "UPDATE media_state SET subtitle_track_index = :subtitleTrackIndex, selected_subtitle_uri = :selectedSubtitleUri, last_played_time = :lastPlayedTime WHERE uri = :uri",
    )
    suspend fun updateSubtitleSelection(
        uri: String,
        subtitleTrackIndex: Int?,
        selectedSubtitleUri: String?,
        lastPlayedTime: Long,
    ): Int

    @Query(
        "UPDATE media_state SET video_scale = :zoom, last_played_time = :lastPlayedTime WHERE uri = :uri",
    )
    suspend fun updateZoom(uri: String, zoom: Float, lastPlayedTime: Long): Int

    @Query(
        "UPDATE media_state SET external_subs = :externalSubs, last_played_time = :lastPlayedTime WHERE uri = :uri",
    )
    suspend fun updateExternalSubs(uri: String, externalSubs: String, lastPlayedTime: Long): Int

    @Transaction
    suspend fun addExternalSubtitle(uri: String, subtitleUri: Uri, lastPlayedTime: Long): Boolean {
        insertIgnore(MediumStateEntity(uriString = uri))
        val stateEntity = get(uri) ?: return false
        val currentExternalSubs = UriListConverter.fromStringToList(stateEntity.externalSubs)
        if (currentExternalSubs.contains(subtitleUri)) return false

        updateExternalSubs(
            uri = uri,
            externalSubs = UriListConverter.fromListToString(currentExternalSubs + subtitleUri),
            lastPlayedTime = lastPlayedTime,
        )
        return true
    }

    @Query(
        "UPDATE media_state SET subtitle_delay = :delay, last_played_time = :lastPlayedTime WHERE uri = :uri",
    )
    suspend fun updateSubtitleDelay(uri: String, delay: Long, lastPlayedTime: Long): Int

    @Query(
        "UPDATE media_state SET subtitle_speed = :speed, last_played_time = :lastPlayedTime WHERE uri = :uri",
    )
    suspend fun updateSubtitleSpeed(uri: String, speed: Float, lastPlayedTime: Long): Int

    @Query("DELETE FROM media_state WHERE uri in (:uris)")
    suspend fun delete(uris: List<String>)
}

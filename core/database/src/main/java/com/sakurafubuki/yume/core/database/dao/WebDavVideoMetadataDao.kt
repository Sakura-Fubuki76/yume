package com.sakurafubuki.yume.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.sakurafubuki.yume.core.database.entities.WebDavVideoMetadataEntity

@Dao
interface WebDavVideoMetadataDao {

    @Query("SELECT * FROM webdav_video_metadata WHERE server_id = :serverId AND href IN (:hrefs)")
    suspend fun getByServerAndHrefs(serverId: Int, hrefs: List<String>): List<WebDavVideoMetadataEntity>

    @Query("SELECT * FROM webdav_video_metadata WHERE server_id = :serverId")
    fun observeByServer(serverId: Int): kotlinx.coroutines.flow.Flow<List<WebDavVideoMetadataEntity>>

    @Query("SELECT * FROM webdav_video_metadata WHERE server_id = :serverId AND href IN (:hrefs)")
    fun observeByServerAndHrefs(
        serverId: Int,
        hrefs: List<String>,
    ): kotlinx.coroutines.flow.Flow<List<WebDavVideoMetadataEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<WebDavVideoMetadataEntity>)

    // Probes and thumbnail jobs can finish in either order. Missing fields are not deletions.
    @Transaction
    suspend fun mergeMetadata(entities: List<WebDavVideoMetadataEntity>) {
        for (incoming in entities) {
            val previous = getByServerAndHrefs(incoming.serverId, listOf(incoming.href)).firstOrNull()
            upsertAll(
                listOf(
                    incoming.copy(
                        durationMs = incoming.durationMs.takeIf { it > 0L } ?: previous?.durationMs ?: 0L,
                        thumbnailPath = incoming.thumbnailPath?.takeIf { it.isNotBlank() } ?: previous?.thumbnailPath,
                        width = incoming.width.takeIf { it > 0 } ?: previous?.width ?: 0,
                        height = incoming.height.takeIf { it > 0 } ?: previous?.height ?: 0,
                    ),
                ),
            )
        }
    }

    @Query("UPDATE webdav_video_metadata SET thumbnail_path = NULL")
    suspend fun clearAllThumbnailPaths()

    @Query("DELETE FROM webdav_video_metadata WHERE server_id = :serverId")
    suspend fun deleteByServerId(serverId: Int)
}

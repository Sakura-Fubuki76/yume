package com.sakurafubuki.yume.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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

    // One statement atomically preserves fields produced by another probe.
    @Query(
        """
        INSERT INTO webdav_video_metadata(server_id, href, duration_ms, thumbnail_path, width, height, updated_at)
        VALUES (:serverId, :href, :durationMs, :thumbnailPath, :width, :height, :updatedAt)
        ON CONFLICT(server_id, href) DO UPDATE SET
            duration_ms = CASE WHEN excluded.duration_ms > 0 THEN excluded.duration_ms ELSE duration_ms END,
            thumbnail_path = COALESCE(NULLIF(excluded.thumbnail_path, ''), thumbnail_path),
            width = CASE WHEN excluded.width > 0 THEN excluded.width ELSE width END,
            height = CASE WHEN excluded.height > 0 THEN excluded.height ELSE height END,
            updated_at = MAX(updated_at, excluded.updated_at)
        """,
    )
    suspend fun mergeMetadata(
        serverId: Int,
        href: String,
        durationMs: Long,
        thumbnailPath: String?,
        width: Int,
        height: Int,
        updatedAt: Long,
    )

    suspend fun mergeMetadata(entities: List<WebDavVideoMetadataEntity>) {
        for (entity in entities) {
            mergeMetadata(entity.serverId, entity.href, entity.durationMs, entity.thumbnailPath, entity.width, entity.height, entity.updatedAt)
        }
    }

    @Query("UPDATE webdav_video_metadata SET thumbnail_path = NULL")
    suspend fun clearAllThumbnailPaths()

    @Query("DELETE FROM webdav_video_metadata")
    suspend fun clearAll(): Int

    @Query("SELECT COUNT(*) FROM webdav_video_metadata")
    suspend fun countAll(): Int

    @Query("DELETE FROM webdav_video_metadata WHERE server_id = :serverId")
    suspend fun deleteByServerId(serverId: Int)
}

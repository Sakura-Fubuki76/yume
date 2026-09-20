package com.sakurafubuki.yume.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.sakurafubuki.yume.core.database.entities.WebDavFolderMetadataEntity

@Dao
interface WebDavFolderMetadataDao {

    @Query("SELECT * FROM webdav_folder_metadata WHERE server_id = :serverId AND folder_path IN (:folderPaths)")
    suspend fun getByServerAndPaths(serverId: Int, folderPaths: List<String>): List<WebDavFolderMetadataEntity>

    @Query("SELECT * FROM webdav_folder_metadata WHERE server_id = :serverId AND folder_path IN (:folderPaths)")
    fun observeByServerAndPaths(
        serverId: Int,
        folderPaths: List<String>,
    ): kotlinx.coroutines.flow.Flow<List<WebDavFolderMetadataEntity>>

    @Query("SELECT * FROM webdav_folder_metadata WHERE server_id = :serverId")
    fun observeByServer(serverId: Int): kotlinx.coroutines.flow.Flow<List<WebDavFolderMetadataEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WebDavFolderMetadataEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<WebDavFolderMetadataEntity>)

    @Query("DELETE FROM webdav_folder_metadata")
    suspend fun clearAll(): Int

    @Query("SELECT COUNT(*) FROM webdav_folder_metadata")
    suspend fun countAll(): Int

    @Query("DELETE FROM webdav_folder_metadata WHERE server_id = :serverId")
    suspend fun deleteByServerId(serverId: Int)
}

package com.sakurafubuki.yume.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.sakurafubuki.yume.core.database.entities.WebDavDirectoryItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WebDavDirectoryItemDao {

    @Query("SELECT * FROM webdav_directory_items WHERE server_id = :serverId AND parent_path = :parentPath ORDER BY is_directory DESC, name COLLATE NOCASE ASC")
    suspend fun getByParent(serverId: Int, parentPath: String): List<WebDavDirectoryItemEntity>

    @Query("SELECT MAX(updated_at) FROM webdav_directory_items WHERE server_id = :serverId AND parent_path = :parentPath")
    suspend fun getLastUpdatedAt(serverId: Int, parentPath: String): Long?

    @Query("SELECT * FROM webdav_directory_items WHERE server_id = :serverId AND parent_path = :parentPath ORDER BY is_directory DESC, name COLLATE NOCASE ASC")
    fun observeByParent(serverId: Int, parentPath: String): Flow<List<WebDavDirectoryItemEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<WebDavDirectoryItemEntity>)

    @Query("DELETE FROM webdav_directory_items WHERE server_id = :serverId AND parent_path = :parentPath")
    suspend fun deleteByParent(serverId: Int, parentPath: String)

    @Query("DELETE FROM webdav_directory_items WHERE server_id = :serverId")
    suspend fun deleteByServerId(serverId: Int)

    @Transaction
    suspend fun replaceForParent(serverId: Int, parentPath: String, items: List<WebDavDirectoryItemEntity>) {
        deleteByParent(serverId, parentPath)
        if (items.isNotEmpty()) {
            upsertAll(items)
        }
    }
}

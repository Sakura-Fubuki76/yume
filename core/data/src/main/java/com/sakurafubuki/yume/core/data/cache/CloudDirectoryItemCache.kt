package com.sakurafubuki.yume.core.data.cache

import com.sakurafubuki.yume.core.database.dao.WebDavDirectoryItemDao
import com.sakurafubuki.yume.core.database.entities.WebDavDirectoryItemEntity
import com.sakurafubuki.yume.core.model.WebDavMediaItem
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@Singleton
class CloudDirectoryItemCache @Inject constructor(
    private val dao: WebDavDirectoryItemDao,
) {
    suspend fun get(serverId: Int, parentPath: String): List<WebDavMediaItem> = withContext(Dispatchers.IO) {
        dao.getByParent(serverId, parentPath).map { it.toModel() }
    }

    /**
     * 若该目录在 [ttlMs] 内被刷过（有缓存且未过期），则视为新鲜，
     * 浏览时可跳过网络请求直接展示缓存。
     */
    suspend fun isFresh(serverId: Int, parentPath: String, ttlMs: Long): Boolean = withContext(Dispatchers.IO) {
        val lastUpdatedAt = dao.getLastUpdatedAt(serverId, parentPath) ?: return@withContext false
        System.currentTimeMillis() - lastUpdatedAt <= ttlMs
    }

    fun observe(serverId: Int, parentPath: String): Flow<List<WebDavMediaItem>> = dao.observeByParent(serverId, parentPath)
        .map { entities -> entities.map { it.toModel() } }
        .distinctUntilChanged()

    suspend fun put(serverId: Int, parentPath: String, items: List<WebDavMediaItem>) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        dao.replaceForParent(
            serverId = serverId,
            parentPath = parentPath,
            items = items.map { it.toEntity(parentPath, now) },
        )
    }

    private fun WebDavMediaItem.toEntity(parentPath: String, updatedAt: Long): WebDavDirectoryItemEntity = WebDavDirectoryItemEntity(
        serverId = serverId,
        parentPath = parentPath,
        name = name,
        href = href,
        contentType = contentType,
        size = size,
        width = width,
        height = height,
        lastModified = lastModified?.time,
        isDirectory = isDirectory,
        apiThumbnailUrl = apiThumbnailUrl,
        rawVideoUrl = rawVideoUrl,
        updatedAt = updatedAt,
    )

    private fun WebDavDirectoryItemEntity.toModel(): WebDavMediaItem = WebDavMediaItem(
        name = name,
        href = href,
        contentType = contentType,
        size = size,
        width = width,
        height = height,
        lastModified = lastModified?.let(::Date),
        isDirectory = isDirectory,
        serverId = serverId,
        apiThumbnailUrl = apiThumbnailUrl,
        rawVideoUrl = rawVideoUrl,
    )
}

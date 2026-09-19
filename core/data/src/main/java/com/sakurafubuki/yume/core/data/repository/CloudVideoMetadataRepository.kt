package com.sakurafubuki.yume.core.data.repository

import com.sakurafubuki.yume.core.model.CloudFolderMetadata
import com.sakurafubuki.yume.core.model.CloudVideoMetadata
import com.sakurafubuki.yume.core.model.WebDavMediaItem
import com.sakurafubuki.yume.core.model.WebDavServer

interface CloudVideoMetadataRepository {
    suspend fun getMetadata(serverId: Int, hrefs: List<String>): Map<String, CloudVideoMetadata>

    fun observeMetadata(serverId: Int): kotlinx.coroutines.flow.Flow<Map<String, CloudVideoMetadata>>

    fun observeMetadata(
        serverId: Int,
        hrefs: List<String>,
    ): kotlinx.coroutines.flow.Flow<Map<String, CloudVideoMetadata>>

    fun observeFolderMetadata(
        serverId: Int,
        folderPaths: List<String>,
    ): kotlinx.coroutines.flow.Flow<Map<String, CloudFolderMetadata>>

    fun observeFolderMetadata(serverId: Int): kotlinx.coroutines.flow.Flow<Map<String, CloudFolderMetadata>>

    suspend fun cacheMissingMetadata(server: WebDavServer, items: List<WebDavMediaItem>, forceRetry: Boolean = false): Boolean

    suspend fun getFolderMetadata(serverId: Int, folderPaths: List<String>): Map<String, CloudFolderMetadata>

    suspend fun saveFolderMetadata(
        serverId: Int,
        folderPath: String,
        totalDurationMs: Long,
        totalSize: Long,
        mediaCount: Int,
        folderCount: Int,
        coverImageUri: String? = null,
        videoCount: Int = 0,
        imageCount: Int = 0,
    )
}

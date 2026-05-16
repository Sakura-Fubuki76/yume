package com.sakurafubuki.yume.core.data.cache

import android.content.Context
import com.jakewharton.disklrucache.DiskLruCache
import com.sakurafubuki.yume.core.common.Logger
import com.sakurafubuki.yume.core.model.Folder
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CloudFolderCache @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val diskCache: DiskLruCache by lazy {
        val dir = File(context.filesDir, CACHE_DIR_NAME)
        dir.mkdirs()
        DiskLruCache.open(dir, APP_VERSION, 1, CACHE_MAX_SIZE_BYTES)
    }

    fun get(serverId: Int, path: String, type: String = ""): Folder? = try {
        val key = cacheKey(serverId, path, type)
        diskCache.get(key)?.use { snapshot ->
            ObjectInputStream(BufferedInputStream(snapshot.getInputStream(0))).use { stream ->
                stream.readObject() as? Folder
            }
        }
    } catch (e: Exception) {
        Logger.w(TAG, "Failed to read cloud folder cache: ${e.message}")
        null
    }

    fun put(serverId: Int, path: String, folder: Folder, type: String = "") {
        try {
            val key = cacheKey(serverId, path, type)
            val editor = diskCache.edit(key) ?: return
            try {
                val bytes = ByteArrayOutputStream().also { baos ->
                    ObjectOutputStream(BufferedOutputStream(baos)).use { oos ->
                        oos.writeObject(folder)
                    }
                }.toByteArray()
                editor.newOutputStream(0).use { it.write(bytes) }
                editor.commit()
            } catch (e: Exception) {
                editor.abort()
                throw e
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to write cloud folder cache: ${e.message}")
        }
    }

    fun remove(serverId: Int, path: String, type: String = "") = try {
        diskCache.remove(cacheKey(serverId, path, type))
    } catch (e: Exception) {
        Logger.w(TAG, "Failed to remove cloud folder cache entry: ${e.message}")
    }

    private fun cacheKey(serverId: Int, path: String, type: String): String {
        val raw = "${serverId}_${type}_$path"
        return MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .take(16)
            .joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "CloudFolderCache"
        private const val CACHE_DIR_NAME = "cloud_folder_cache"
        private const val APP_VERSION = 2
        private const val CACHE_MAX_SIZE_BYTES = 50L * 1024 * 1024
    }
}

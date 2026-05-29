package com.sakurafubuki.yume.core.data.repository

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.sakurafubuki.yume.core.common.Logger
import java.io.File
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object SpriteSheetCache {

    private const val TAG = "SpriteSheetCache"
    private const val MAX_MEMORY_ENTRIES = 5
    private const val MAX_DISK_SIZE_BYTES = 50L * 1024 * 1024

    data class CachedSheet(
        val bitmap: Bitmap,
        val metadata: SpriteSheetMetadata,
    )

    private val lock = Any()
    private val loadLocks = ConcurrentHashMap<String, Any>()
    private val evictionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val evictionScheduled = AtomicBoolean(false)
    private val memoryCache = object : LinkedHashMap<String, CachedSheet>(MAX_MEMORY_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedSheet>?): Boolean {
            if (size > MAX_MEMORY_ENTRIES) {
                eldest?.value?.bitmap?.recycle()
                return true
            }
            return false
        }
    }

    private var cacheDir: File? = null

    fun init(dir: File) {
        val d = File(dir, "sprite_sheets").also { it.mkdirs() }
        cacheDir = d

        evictDiskAsync()
    }

    fun get(key: String): CachedSheet? {
        synchronized(lock) {
            memoryCache[key]?.let { return it }
        }

        val loadLock = loadLocks.computeIfAbsent(key) { Any() }
        return try {
            synchronized(loadLock) {
                synchronized(lock) {
                    memoryCache[key]?.let { return it }
                }

                val dir = cacheDir ?: return null
                val spriteFile = File(dir, "$key.webp")
                val metaFile = File(dir, "$key.json")
                if (!spriteFile.exists() || !metaFile.exists()) return null

                spriteFile.setLastModified(System.currentTimeMillis())
                metaFile.setLastModified(System.currentTimeMillis())

                val metadata = try {
                    SpriteSheetMetadata.fromJson(metaFile.readText())
                } catch (_: Exception) {
                    return null
                }

                val bitmap = BitmapFactory.decodeFile(spriteFile.absolutePath) ?: return null
                val entry = CachedSheet(bitmap, metadata)
                putMemory(key, entry)
                entry
            }
        } finally {
            loadLocks.remove(key, loadLock)
        }
    }

    fun put(key: String, result: SpriteSheetResult) {
        val bitmap = BitmapFactory.decodeFile(result.file.absolutePath) ?: return
        val entry = CachedSheet(bitmap, result.metadata)
        putMemory(key, entry)
        evictDiskAsync()
    }

    fun loadFresh(key: String): CachedSheet? {
        val loadLock = loadLocks.computeIfAbsent(key) { Any() }
        return try {
            synchronized(loadLock) {
                val dir = cacheDir ?: return null
                val spriteFile = File(dir, "$key.webp")
                val metaFile = File(dir, "$key.json")
                if (!spriteFile.exists() || !metaFile.exists()) return null

                val metadata = try {
                    SpriteSheetMetadata.fromJson(metaFile.readText())
                } catch (_: Exception) {
                    return null
                }

                val cached = synchronized(lock) { memoryCache[key] }
                if (cached != null && cached.metadata == metadata) return cached

                val bitmap = BitmapFactory.decodeFile(spriteFile.absolutePath) ?: return null
                val entry = CachedSheet(bitmap, metadata)
                putMemory(key, entry)
                evictDiskAsync()
                entry
            }
        } finally {
            loadLocks.remove(key, loadLock)
        }
    }

    fun getCacheDir(): File? = cacheDir

    fun evictAll() {
        synchronized(lock) {
            memoryCache.values.forEach { it.bitmap.recycle() }
            memoryCache.clear()
        }
    }

    private fun putMemory(key: String, entry: CachedSheet) {
        synchronized(lock) {
            memoryCache[key] = entry
        }
    }

    private fun evictDiskAsync() {
        val dir = cacheDir ?: return
        if (!evictionScheduled.compareAndSet(false, true)) return
        evictionScope.launch {
            try {
                evictDisk(dir)
            } catch (e: Exception) {
                Logger.w(TAG, "Disk eviction failed", e)
            } finally {
                evictionScheduled.set(false)
            }
        }
    }

    private fun evictDisk(dir: File) {
        val files = dir.listFiles()?.toList() ?: return
        if (files.isEmpty()) return

        val sorted = files.sortedBy { it.lastModified() }
        var totalSize = sorted.sumOf { it.length() }

        if (totalSize <= MAX_DISK_SIZE_BYTES) return

        for (file in sorted) {
            if (totalSize <= MAX_DISK_SIZE_BYTES) break
            val size = file.length()
            if (file.delete()) {
                totalSize -= size
                Logger.d(TAG, "Evicted: ${file.name} (${size / 1024}KB), remaining: ${totalSize / 1024 / 1024}MB")
            }
        }
    }
}

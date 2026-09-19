package com.sakurafubuki.yume.core.database.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sakurafubuki.yume.core.database.MediaDatabase
import com.sakurafubuki.yume.core.database.entities.WebDavVideoMetadataEntity
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class WebDavVideoMetadataDaoTest {

    private lateinit var webDavVideoMetadataDao: WebDavVideoMetadataDao
    private lateinit var db: MediaDatabase

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        db = Room.inMemoryDatabaseBuilder(
            context,
            MediaDatabase::class.java,
        ).build()
        webDavVideoMetadataDao = db.webDavVideoMetadataDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun mergeMetadata_preserves_partial_results_in_either_completion_order() = runTest {
        val duration = WebDavVideoMetadataEntity(1, "https://example.com/video.mp4", 12345, null, updatedAt = 1)
        val thumbnail = duration.copy(durationMs = 0, thumbnailPath = "/tmp/cover.webp", width = 1920, height = 1080)
        for (records in listOf(listOf(duration, thumbnail), listOf(thumbnail, duration))) {
            webDavVideoMetadataDao.deleteByServerId(1)
            records.forEach { webDavVideoMetadataDao.mergeMetadata(listOf(it)) }
            val result = webDavVideoMetadataDao.getByServerAndHrefs(1, listOf(duration.href)).single()
            assertEquals(12345L, result.durationMs)
            assertEquals("/tmp/cover.webp", result.thumbnailPath)
            assertEquals(1920, result.width)
            assertEquals(1080, result.height)
        }
    }

    @Test
    fun mergeMetadata_concurrent_failed_probes_do_not_erase_success() = runTest {
        val original = WebDavVideoMetadataEntity(1, "https://example.com/video.mp4", 0, null, updatedAt = 1)
        (0 until 40).map { index ->
            async {
                webDavVideoMetadataDao.mergeMetadata(
                    listOf(original.copy(durationMs = if (index == 10) 12345 else 0, thumbnailPath = if (index == 20) "/tmp/cover.webp" else null)),
                )
            }
        }.awaitAll()
        val result = webDavVideoMetadataDao.getByServerAndHrefs(1, listOf(original.href)).single()
        assertEquals(12345L, result.durationMs)
        assertEquals("/tmp/cover.webp", result.thumbnailPath)
        webDavVideoMetadataDao.clearAllThumbnailPaths()
        assertEquals(null, webDavVideoMetadataDao.getByServerAndHrefs(1, listOf(original.href)).single().thumbnailPath)
    }

    @Test
    fun webDavVideoMetadataDao_getByServerAndHrefs_returns_only_matching_records() = runTest {
        val records = listOf(
            WebDavVideoMetadataEntity(
                serverId = 1,
                href = "https://example.com/a.mp4",
                durationMs = 1000,
                thumbnailPath = "/tmp/a.jpg",
                updatedAt = 10,
            ),
            WebDavVideoMetadataEntity(
                serverId = 1,
                href = "https://example.com/b.mp4",
                durationMs = 2000,
                thumbnailPath = "/tmp/b.jpg",
                updatedAt = 20,
            ),
            WebDavVideoMetadataEntity(
                serverId = 2,
                href = "https://example.com/a.mp4",
                durationMs = 3000,
                thumbnailPath = "/tmp/c.jpg",
                updatedAt = 30,
            ),
        )

        webDavVideoMetadataDao.upsertAll(records)

        val result = webDavVideoMetadataDao.getByServerAndHrefs(
            serverId = 1,
            hrefs = listOf("https://example.com/a.mp4", "https://example.com/missing.mp4"),
        )

        assert(result.size == 1)
        assert(result.first() == records.first())
    }

    @Test
    fun webDavVideoMetadataDao_upsertAll_replaces_record_with_same_primary_key() = runTest {
        val original = WebDavVideoMetadataEntity(
            serverId = 1,
            href = "https://example.com/a.mp4",
            durationMs = 1000,
            thumbnailPath = "/tmp/a.jpg",
            updatedAt = 10,
        )
        val updated = original.copy(
            durationMs = 5000,
            thumbnailPath = "/tmp/a-new.jpg",
            updatedAt = 99,
        )

        webDavVideoMetadataDao.upsertAll(listOf(original))
        webDavVideoMetadataDao.upsertAll(listOf(updated))

        val result = webDavVideoMetadataDao.getByServerAndHrefs(
            serverId = 1,
            hrefs = listOf(original.href),
        )

        assert(result.size == 1)
        assert(result.first() == updated)
    }
}

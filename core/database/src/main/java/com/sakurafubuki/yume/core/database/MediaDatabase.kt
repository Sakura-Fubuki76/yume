package com.sakurafubuki.yume.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.sakurafubuki.yume.core.database.dao.DirectoryDao
import com.sakurafubuki.yume.core.database.dao.ImageDimensionDao
import com.sakurafubuki.yume.core.database.dao.MediumDao
import com.sakurafubuki.yume.core.database.dao.MediumStateDao
import com.sakurafubuki.yume.core.database.dao.WebDavDirectoryItemDao
import com.sakurafubuki.yume.core.database.dao.WebDavFolderMetadataDao
import com.sakurafubuki.yume.core.database.dao.WebDavServerDao
import com.sakurafubuki.yume.core.database.dao.WebDavVideoMetadataDao
import com.sakurafubuki.yume.core.database.entities.AudioStreamInfoEntity
import com.sakurafubuki.yume.core.database.entities.DirectoryEntity
import com.sakurafubuki.yume.core.database.entities.ImageDimensionEntity
import com.sakurafubuki.yume.core.database.entities.MediumEntity
import com.sakurafubuki.yume.core.database.entities.MediumStateEntity
import com.sakurafubuki.yume.core.database.entities.SubtitleStreamInfoEntity
import com.sakurafubuki.yume.core.database.entities.VideoStreamInfoEntity
import com.sakurafubuki.yume.core.database.entities.WebDavDirectoryItemEntity
import com.sakurafubuki.yume.core.database.entities.WebDavFolderMetadataEntity
import com.sakurafubuki.yume.core.database.entities.WebDavServerEntity
import com.sakurafubuki.yume.core.database.entities.WebDavVideoMetadataEntity

@Database(
    entities = [
        DirectoryEntity::class,
        MediumEntity::class,
        MediumStateEntity::class,
        VideoStreamInfoEntity::class,
        AudioStreamInfoEntity::class,
        SubtitleStreamInfoEntity::class,
        WebDavServerEntity::class,
        WebDavVideoMetadataEntity::class,
        WebDavFolderMetadataEntity::class,
        WebDavDirectoryItemEntity::class,
        ImageDimensionEntity::class,
    ],
    version = 14,
    exportSchema = true,
)
abstract class MediaDatabase : RoomDatabase() {

    abstract fun mediumDao(): MediumDao

    abstract fun mediumStateDao(): MediumStateDao

    abstract fun directoryDao(): DirectoryDao

    abstract fun webDavServerDao(): WebDavServerDao

    abstract fun webDavVideoMetadataDao(): WebDavVideoMetadataDao

    abstract fun webDavFolderMetadataDao(): WebDavFolderMetadataDao

    abstract fun webDavDirectoryItemDao(): WebDavDirectoryItemDao

    abstract fun imageDimensionDao(): ImageDimensionDao

    companion object {
        const val DATABASE_NAME = "media_db"

        val ALL_MIGRATIONS: Array<Migration> by lazy {
            arrayOf(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8,
                MIGRATION_8_9,
                MIGRATION_9_10,
                MIGRATION_10_11,
                MIGRATION_11_12,
                MIGRATION_12_13,
                MIGRATION_13_14,
            )
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `media_state` (
                        `uri` TEXT NOT NULL,
                        `playback_position` INTEGER NOT NULL DEFAULT 0,
                        `audio_track_index` INTEGER,
                        `subtitle_track_index` INTEGER,
                        `playback_speed` REAL,
                        `last_played_time` INTEGER,
                        `external_subs` TEXT NOT NULL DEFAULT '',
                        `video_scale` REAL NOT NULL DEFAULT 1,
                        PRIMARY KEY(`uri`)
                    )
                    """,
                )

                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS `index_media_state_uri` ON `media_state` (`uri`)
                    """,
                )

                db.execSQL(
                    """
                    INSERT INTO `media_state` (
                        `uri`,
                        `playback_position`,
                        `audio_track_index`,
                        `subtitle_track_index`,
                        `playback_speed`,
                        `last_played_time`,
                        `external_subs`,
                        `video_scale`
                    )
                    SELECT
                        `uri`,
                        `playback_position`,
                        `audio_track_index`,
                        `subtitle_track_index`,
                        `playback_speed`,
                        `last_played_time`,
                        `external_subs`,
                        `video_scale`
                    FROM `media`
                    """,
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `media_new` (
                        `uri` TEXT NOT NULL,
                        `path` TEXT NOT NULL,
                        `filename` TEXT NOT NULL,
                        `parent_path` TEXT NOT NULL,
                        `last_modified` INTEGER NOT NULL,
                        `size` INTEGER NOT NULL,
                        `width` INTEGER NOT NULL,
                        `height` INTEGER NOT NULL,
                        `duration` INTEGER NOT NULL,
                        `media_store_id` INTEGER NOT NULL,
                        `format` TEXT,
                        `thumbnail_path` TEXT,
                        PRIMARY KEY(`uri`)
                    )
                    """,
                )

                db.execSQL(
                    """
                    INSERT INTO `media_new` (
                        `uri`,
                        `path`,
                        `filename`,
                        `parent_path`,
                        `last_modified`,
                        `size`,
                        `width`,
                        `height`,
                        `duration`,
                        `media_store_id`,
                        `format`,
                        `thumbnail_path`
                    )
                    SELECT
                        `uri`,
                        `path`,
                        `filename`,
                        `parent_path`,
                        `last_modified`,
                        `size`,
                        `width`,
                        `height`,
                        `duration`,
                        `media_store_id`,
                        `format`,
                        `thumbnail_path`
                    FROM `media`
                    """,
                )

                db.execSQL("DROP TABLE `media`")

                db.execSQL("ALTER TABLE `media_new` RENAME TO `media`")

                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS `index_media_uri` ON `media` (`uri`)
                    """,
                )
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS `index_media_path` ON `media` (`path`)
                    """,
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX IF EXISTS `index_media_path`")

                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_media_path` ON `media` (`path`)
                    """,
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `media_state` ADD COLUMN `subtitle_delay` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `media_state` ADD COLUMN `subtitle_speed` REAL NOT NULL DEFAULT 1")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `webdav_servers` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `url` TEXT NOT NULL,
                        `username` TEXT NOT NULL,
                        `base_path` TEXT NOT NULL,
                        `created_at` INTEGER NOT NULL
                    )
                    """,
                )
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `webdav_video_metadata` (
                        `server_id` INTEGER NOT NULL,
                        `href` TEXT NOT NULL,
                        `duration_ms` INTEGER NOT NULL,
                        `thumbnail_path` TEXT,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`server_id`, `href`)
                    )
                    """,
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_webdav_video_metadata_server_id` ON `webdav_video_metadata` (`server_id`)
                    """,
                )
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `webdav_folder_metadata` (
                        `server_id` INTEGER NOT NULL,
                        `folder_path` TEXT NOT NULL,
                        `total_duration_ms` INTEGER NOT NULL,
                        `total_size` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`server_id`, `folder_path`)
                    )
                    """,
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_webdav_folder_metadata_server_id` ON `webdav_folder_metadata` (`server_id`)
                    """,
                )
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    ALTER TABLE `webdav_folder_metadata` ADD COLUMN `media_count` INTEGER NOT NULL DEFAULT 0
                    """,
                )
                db.execSQL(
                    """
                    ALTER TABLE `webdav_folder_metadata` ADD COLUMN `folder_count` INTEGER NOT NULL DEFAULT 0
                    """,
                )
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    ALTER TABLE `webdav_folder_metadata` ADD COLUMN `cover_image_uri` TEXT
                    """,
                )
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    ALTER TABLE `webdav_folder_metadata` ADD COLUMN `video_count` INTEGER NOT NULL DEFAULT 0
                    """,
                )
                db.execSQL(
                    """
                    ALTER TABLE `webdav_folder_metadata` ADD COLUMN `image_count` INTEGER NOT NULL DEFAULT 0
                    """,
                )
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `image_dimensions` (
                        `server_id` INTEGER NOT NULL,
                        `uri` TEXT NOT NULL,
                        `width` INTEGER NOT NULL,
                        `height` INTEGER NOT NULL,
                        `last_accessed_at` INTEGER NOT NULL,
                        PRIMARY KEY(`server_id`, `uri`)
                    )
                    """,
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_image_dimensions_server_id` ON `image_dimensions` (`server_id`)
                    """,
                )
                db.execSQL(
                    """
                    ALTER TABLE `webdav_video_metadata` ADD COLUMN `width` INTEGER NOT NULL DEFAULT 0
                    """,
                )
                db.execSQL(
                    """
                    ALTER TABLE `webdav_video_metadata` ADD COLUMN `height` INTEGER NOT NULL DEFAULT 0
                    """,
                )
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    db.execSQL("ALTER TABLE `webdav_video_metadata` ADD COLUMN `width` INTEGER NOT NULL DEFAULT 0")
                } catch (_: Exception) { }
                try {
                    db.execSQL("ALTER TABLE `webdav_video_metadata` ADD COLUMN `height` INTEGER NOT NULL DEFAULT 0")
                } catch (_: Exception) { }
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    db.execSQL("ALTER TABLE `webdav_servers` ADD COLUMN `is_image_hosting` INTEGER NOT NULL DEFAULT 0")
                } catch (_: Exception) { }
            }
        }

        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `webdav_directory_items` (
                        `server_id` INTEGER NOT NULL,
                        `parent_path` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `href` TEXT NOT NULL,
                        `content_type` TEXT NOT NULL,
                        `size` INTEGER NOT NULL,
                        `width` INTEGER,
                        `height` INTEGER,
                        `last_modified` INTEGER,
                        `is_directory` INTEGER NOT NULL,
                        `api_thumbnail_url` TEXT,
                        `raw_video_url` TEXT,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`server_id`, `parent_path`, `href`)
                    )
                    """,
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_webdav_directory_items_server_id_parent_path`
                    ON `webdav_directory_items` (`server_id`, `parent_path`)
                    """,
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_webdav_directory_items_server_id_updated_at`
                    ON `webdav_directory_items` (`server_id`, `updated_at`)
                    """,
                )
            }
        }
    }
}

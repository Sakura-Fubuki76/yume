package com.sakurafubuki.yume.core.cache

import android.content.Context

object CacheTimestampStore {
    private const val PREFS_NAME = "image_cache_timestamps"

    fun record(context: Context, key: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(key, System.currentTimeMillis())
            .apply()
    }

    fun allEntries(context: Context): Map<String, Long> =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .all
            .mapValues { it.value as Long }

    fun remove(context: Context, key: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(key)
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }
}

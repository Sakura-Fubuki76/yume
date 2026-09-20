package com.sakurafubuki.yume.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.sakurafubuki.yume.core.database.dao.WebDavFolderMetadataDao
import com.sakurafubuki.yume.core.database.dao.WebDavVideoMetadataDao
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Debug-only adb hook for metadata performance testing.
 *
 * adb shell am broadcast -n com.sakurafubuki.yume.debug/com.sakurafubuki.yume.debug.MetadataTestReceiver \
 *     -a com.sakurafubuki.yume.debug.action.CLEAR_METADATA
 * adb shell am broadcast -n ... -a com.sakurafubuki.yume.debug.action.DUMP_METADATA
 */
@AndroidEntryPoint
class MetadataTestReceiver : BroadcastReceiver() {

    @Inject
    lateinit var webDavVideoMetadataDao: WebDavVideoMetadataDao

    @Inject
    lateinit var webDavFolderMetadataDao: WebDavFolderMetadataDao

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    ACTION_CLEAR_METADATA -> {
                        val videos = webDavVideoMetadataDao.clearAll()
                        val folders = webDavFolderMetadataDao.clearAll()
                        Log.i(PERF_TAG, "[MD_TEST] cleared metadata rows videos=$videos folders=$folders")
                    }
                    ACTION_DUMP_METADATA -> {
                        val videos = webDavVideoMetadataDao.countAll()
                        val folders = webDavFolderMetadataDao.countAll()
                        Log.i(PERF_TAG, "[MD_TEST] rows videos=$videos folders=$folders")
                    }
                    else -> Log.w(PERF_TAG, "[MD_TEST] unknown action=${intent.action}")
                }
            } catch (error: Exception) {
                Log.e(PERF_TAG, "[MD_TEST] action=${intent.action} failed", error)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val PERF_TAG = "MetaPerf"
        const val ACTION_CLEAR_METADATA = "com.sakurafubuki.yume.debug.action.CLEAR_METADATA"
        const val ACTION_DUMP_METADATA = "com.sakurafubuki.yume.debug.action.DUMP_METADATA"
    }
}

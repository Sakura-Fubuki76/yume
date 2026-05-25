package com.sakurafubuki.yume.core.common

import android.Manifest
import android.content.res.Resources
import android.os.Build
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

val storagePermissions = buildList {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        add(Manifest.permission.READ_MEDIA_IMAGES)
        add(Manifest.permission.READ_MEDIA_VIDEO)
        add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.READ_MEDIA_IMAGES)
        add(Manifest.permission.READ_MEDIA_VIDEO)
    } else {
        add(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}

val storagePermission = storagePermissions.first()

val imagePermission = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> Manifest.permission.READ_MEDIA_IMAGES
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Manifest.permission.READ_EXTERNAL_STORAGE
    else -> Manifest.permission.WRITE_EXTERNAL_STORAGE
}

val imagePermissions = buildList {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.READ_MEDIA_IMAGES)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    }
    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
        add(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}

val webDavPermissions = buildList {
    if (Build.VERSION.SDK_INT >= 37) {
        add(Manifest.permission.ACCESS_LOCAL_NETWORK)
    }
    add(Manifest.permission.INTERNET)
}

object Utils {

    fun pxToDp(px: Float): Float = px / Resources.getSystem().displayMetrics.density

    fun formatDurationMillis(millis: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(millis)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) - TimeUnit.HOURS.toMinutes(hours)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) -
            TimeUnit.MINUTES.toSeconds(minutes) -
            TimeUnit.HOURS.toSeconds(hours)
        return if (hours > 0) {
            String.format("%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    fun formatDurationMillisSign(millis: Long): String = if (millis >= 0) {
        "+${formatDurationMillis(millis)}"
    } else {
        "-${formatDurationMillis(abs(millis))}"
    }

    fun formatFileSize(size: Long): String {
        val kb = 1024
        val mb = kb * 1024
        val gb = mb * 1024

        return when {
            size < kb -> "$size B"
            size < mb -> "%.2f KB".format(size / kb.toDouble())
            size < gb -> "%.2f MB".format(size / mb.toDouble())
            else -> "%.2f GB".format(size / gb.toDouble())
        }
    }

    fun formatBitrate(bitrate: Long): String? {
        if (bitrate <= 0) {
            return null
        }

        val kiloBitrate = bitrate.toDouble() / 1000.0
        val megaBitrate = kiloBitrate / 1000.0
        val gigaBitrate = megaBitrate / 1000.0

        return when {
            gigaBitrate >= 1.0 -> String.format("%.1f Gbps", gigaBitrate)
            megaBitrate >= 1.0 -> String.format("%.1f Mbps", megaBitrate)
            kiloBitrate >= 1.0 -> String.format("%.1f kbps", kiloBitrate)
            else -> String.format("%d bps", bitrate)
        }
    }

    fun formatLanguage(language: String?): String? = language?.let { lang -> Locale.forLanguageTag(lang).displayLanguage.takeIf { it.isNotEmpty() } }

    fun isBaiduNetdiskUrl(url: String): Boolean {
        if (url.contains("pan.baidu.com", ignoreCase = true)) return true
        if (url.contains("baidupcs.com", ignoreCase = true)) return true
        if (url.contains("baidupcs.net", ignoreCase = true)) return true
        if (url.contains("%E7%99%BE%E5%BA%A6%E7%BD%91%E7%9B%98")) return true
        return false
    }
}

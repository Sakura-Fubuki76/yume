package com.sakurafubuki.yume.feature.imagebrowser.ui

import com.sakurafubuki.yume.core.model.ApplicationPreferences
import com.sakurafubuki.yume.core.model.Video

internal fun Video.imageBrowserDisplayUri(thumbnailSizePx: Int): String {
    val normalizedSizePx = ApplicationPreferences.normalizeImageBrowserThumbnailSizePx(thumbnailSizePx)
    if (normalizedSizePx == ApplicationPreferences.IMAGE_BROWSER_THUMBNAIL_SIZE_ORIGINAL) {
        return uriString
    }
    return thumbnailUriString
        ?.takeIf { it.isNotBlank() }
        ?.withImageBrowserServerThumbnailSize(normalizedSizePx)
        ?: uriString
}

internal fun String.withImageBrowserServerThumbnailSize(sizePx: Int): String {
    val normalizedSizePx = ApplicationPreferences.normalizeImageBrowserThumbnailSizePx(sizePx)
    if (normalizedSizePx == ApplicationPreferences.IMAGE_BROWSER_THUMBNAIL_SIZE_ORIGINAL) {
        return this
    }
    val marker = "/thumb/"
    val markerIndex = indexOf(marker)
    if (markerIndex < 0) return this

    val sizeStart = markerIndex + marker.length
    val sizeEnd = indexOf('/', startIndex = sizeStart)
    if (sizeEnd <= sizeStart) return this

    val currentSize = substring(sizeStart, sizeEnd)
    if (currentSize.any { !it.isDigit() }) return this

    return replaceRange(sizeStart, sizeEnd, normalizedSizePx.toString())
}

internal fun Any.isImageBrowserServerThumbnailUrl(): Boolean {
    val value = toString()
    val marker = "/thumb/"
    val markerIndex = value.indexOf(marker)
    if (markerIndex < 0) return false

    val sizeStart = markerIndex + marker.length
    val sizeEnd = value.indexOf('/', startIndex = sizeStart)
    if (sizeEnd <= sizeStart) return false

    return value.substring(sizeStart, sizeEnd).all { it.isDigit() }
}

internal fun String.toImageBrowserOriginalUri(): String {
    val marker = "/thumb/"
    val markerIndex = indexOf(marker)
    if (markerIndex < 0) return this

    val sizeStart = markerIndex + marker.length
    val sizeEnd = indexOf('/', startIndex = sizeStart)
    if (sizeEnd <= sizeStart) return this

    val currentSize = substring(sizeStart, sizeEnd)
    if (currentSize.any { !it.isDigit() }) return this

    return replaceRange(markerIndex, sizeEnd, "/d")
}

internal fun String.withImageBrowserVersion(version: Long?): String {
    val normalizedVersion = version?.takeIf { it > 0L } ?: return this
    val base = substringBefore('?')
    val query = substringAfter('?', "")
        .split('&')
        .filter { it.isNotBlank() && it.substringBefore('=') != "v" }
        .joinToString("&")
    return if (query.isBlank()) {
        "$base?v=$normalizedVersion"
    } else {
        "$base?$query&v=$normalizedVersion"
    }
}

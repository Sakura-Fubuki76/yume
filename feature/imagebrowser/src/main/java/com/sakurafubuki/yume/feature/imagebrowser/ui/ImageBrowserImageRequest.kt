package com.sakurafubuki.yume.feature.imagebrowser.ui

import android.content.Context
import androidx.core.net.toUri
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.allowRgb565
import coil3.request.crossfade
import coil3.size.Precision
import coil3.size.Size
import com.sakurafubuki.yume.core.cache.CacheTimestampStore
import com.sakurafubuki.yume.core.model.ApplicationPreferences
import com.sakurafubuki.yume.core.model.ImageQuality

internal enum class ImageRequestProfile {
    THUMBNAIL,
    VIEWER,
}

internal fun stableCacheKey(data: Any): String {
    val raw = data.toString()
    val stable = raw.substringBefore('?')
    return if (stable.startsWith("http://") || stable.startsWith("https://")) {
        raw.toUri().getQueryParameter("v")?.takeIf { it.isNotBlank() }?.let { version ->
            "$stable?v=$version"
        } ?: stable
    } else {
        raw
    }
}

internal fun buildImageRequest(
    context: Context,
    data: Any,
    quality: ImageQuality,
    profile: ImageRequestProfile = ImageRequestProfile.VIEWER,
    thumbnailMaxEdgePx: Int = ApplicationPreferences.DEFAULT_IMAGE_BROWSER_THUMBNAIL_SIZE_PX,
): ImageRequest {
    val cacheKeySuffix = when (profile) {
        ImageRequestProfile.THUMBNAIL -> "thumb"
        ImageRequestProfile.VIEWER -> "viewer"
    }
    val normalizedThumbnailSizePx = ApplicationPreferences.normalizeImageBrowserThumbnailSizePx(thumbnailMaxEdgePx)
    val isRemote = isRemoteImageData(data)
    val cacheKey = if (isRemote) stableCacheKey(data) else data.toString()
    val useRemoteDiskCache = isRemote && ImageViewerStore.imageCloudDiskCacheEnabled
    return ImageRequest.Builder(context)
        .data(data)
        .crossfade(false)
        .memoryCacheKey("$cacheKey|$quality|$cacheKeySuffix|$normalizedThumbnailSizePx")
        .apply {
            if (useRemoteDiskCache) {
                diskCacheKey("$cacheKey|$quality|$cacheKeySuffix|$normalizedThumbnailSizePx")
            } else {
                diskCachePolicy(CachePolicy.DISABLED)
                memoryCachePolicy(CachePolicy.ENABLED)
            }
        }
        .applyImageQuality(
            quality = quality,
            profile = profile,
            thumbnailMaxEdgePx = normalizedThumbnailSizePx,
            isServerThumbnail = isRemote && data.isImageBrowserServerThumbnailUrl(),
        )
        .apply {
            if (profile != ImageRequestProfile.VIEWER || quality != ImageQuality.ORIGINAL) {
                allowRgb565(true)
            }
        }
        .listener(
            onSuccess = { _, result ->
                if (useRemoteDiskCache && result.dataSource == coil3.decode.DataSource.NETWORK) {
                    CacheTimestampStore.record(context, cacheKey)
                }
            },
        )
        .build()
}

internal fun thumbnailMemoryCacheKey(
    data: Any,
    quality: ImageQuality,
    thumbnailMaxEdgePx: Int = ApplicationPreferences.DEFAULT_IMAGE_BROWSER_THUMBNAIL_SIZE_PX,
): String {
    val cacheKey = if (isRemoteImageData(data)) stableCacheKey(data) else data.toString()
    val normalizedThumbnailSizePx = ApplicationPreferences.normalizeImageBrowserThumbnailSizePx(thumbnailMaxEdgePx)
    return "$cacheKey|$quality|thumb|$normalizedThumbnailSizePx"
}

internal fun isRemoteImageData(data: Any): Boolean {
    val scheme = when (data) {
        is android.net.Uri -> data.scheme
        is String -> data.toUri().scheme
        else -> null
    }?.lowercase()
    return scheme == "http" || scheme == "https"
}

internal fun resolveImageLoader(
    context: Context,
    data: Any,
    localImageLoader: ImageLoader,
): ImageLoader = if (isRemoteImageData(data)) {
    SingletonImageLoader.get(context)
} else {
    localImageLoader
}

private fun ImageRequest.Builder.applyImageQuality(
    quality: ImageQuality,
    profile: ImageRequestProfile,
    thumbnailMaxEdgePx: Int,
    isServerThumbnail: Boolean,
): ImageRequest.Builder {
    if (quality == ImageQuality.ORIGINAL && profile == ImageRequestProfile.VIEWER) {
        size(Size.ORIGINAL)
        precision(Precision.EXACT)
        return this
    }
    if (isServerThumbnail && profile == ImageRequestProfile.THUMBNAIL) {
        size(Size.ORIGINAL)
        precision(Precision.EXACT)
        return this
    }
    if (
        thumbnailMaxEdgePx == ApplicationPreferences.IMAGE_BROWSER_THUMBNAIL_SIZE_ORIGINAL &&
        profile != ImageRequestProfile.VIEWER
    ) {
        size(Size.ORIGINAL)
        precision(Precision.EXACT)
        return this
    }
    val thumbnailSizePx = thumbnailMaxEdgePx
        .takeIf { it > 0 }
        ?: ApplicationPreferences.DEFAULT_IMAGE_BROWSER_THUMBNAIL_SIZE_PX
    val (baseWidth, baseHeight) = when (profile) {
        ImageRequestProfile.THUMBNAIL -> thumbnailSizePx to thumbnailSizePx
        ImageRequestProfile.VIEWER -> 1600 to 1600
    }
    val ratio = quality.compressionRatio
    val minEdge = if (profile == ImageRequestProfile.VIEWER) 640 else 256
    size(
        width = (baseWidth * ratio).toInt().coerceAtLeast(minEdge),
        height = (baseHeight * ratio).toInt().coerceAtLeast(minEdge),
    )
    precision(if (profile == ImageRequestProfile.VIEWER) Precision.EXACT else Precision.INEXACT)
    return this
}

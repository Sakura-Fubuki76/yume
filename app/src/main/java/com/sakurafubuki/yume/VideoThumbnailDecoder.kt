package com.sakurafubuki.yume

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build.VERSION.SDK_INT
import android.util.Base64
import androidx.core.graphics.applyCanvas
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import coil3.ImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.asImage
import coil3.decode.ContentMetadata
import coil3.decode.DecodeResult
import coil3.decode.DecodeUtils
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.disk.DiskCache
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.request.maxBitmapSize
import coil3.size.Precision
import coil3.size.Size
import coil3.size.pxOrElse
import coil3.toAndroidUri
import coil3.util.component1
import coil3.util.component2
import com.sakurafubuki.yume.core.data.repository.FilterMode
import com.sakurafubuki.yume.core.data.repository.YuvToBitmapBridge
import com.sakurafubuki.yume.core.data.repository.isMostlySolidColor
import io.github.sakurafubuki.yume.nativelib.mediainfo.MediaThumbnailRetriever
import kotlin.math.roundToInt
import okio.FileSystem

class VideoThumbnailDecoder(
    private val source: ImageSource,
    private val options: Options,
    private val strategy: ThumbnailStrategy,
    private val localThumbnailDiskCache: Lazy<DiskCache?>,
    private val remoteThumbnailDiskCache: Lazy<DiskCache?>,
    private val webDavServersById: () -> Map<Int, com.sakurafubuki.yume.core.model.WebDavServer>,
) : Decoder {

    private val activeDiskCache: Lazy<DiskCache?>
        get() = if (isRemoteSource()) remoteThumbnailDiskCache else localThumbnailDiskCache

    companion object {
        private const val TINY_PROBE_EDGE = 32
        private const val FALLBACK_MAX_EDGE = 1920
        const val ThumbnailTargetSize = 1280
        private fun embeddedMaxEdge(): Int {
            val maxHeapMb = Runtime.getRuntime().maxMemory() / (1024 * 1024)
            return when {
                maxHeapMb < 384 -> 384
                maxHeapMb < 768 -> 512
                else -> 768
            }
        }
    }

    private val diskCacheKey: String
        get() = options.diskCacheKey ?: run {
            val metadata = source.metadata
            val baseKey = when {
                metadata is ContentMetadata -> {
                    val uri = metadata.uri.toAndroidUri()
                    val isHttp = uri.scheme?.lowercase() in listOf("http", "https")
                    if (isHttp) {
                        val authority = uri.encodedAuthority
                        val normalizedAuthority = authority?.substringAfter('@', authority)
                        uri.buildUpon()
                            .encodedAuthority(normalizedAuthority)
                            .encodedFragment(null)
                            .build()
                            .toString()
                    } else {
                        uri.toString()
                    }
                }
                source.fileSystem === FileSystem.SYSTEM -> source.file().toFile().path
                else -> error("Not supported")
            }

            val (targetW, targetH) = computeThumbnailTargetSize()
            "${baseKey}_${targetW}x$targetH"
        }

    @OptIn(ExperimentalCoilApi::class)
    override suspend fun decode(): DecodeResult {
        readFromDiskCache()?.use { snapshot ->
            val file = snapshot.data.toFile()

            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            file.inputStream().use { BitmapFactory.decodeStream(it, null, boundsOpts) }
            val cachedWidth = boundsOpts.outWidth
            val cachedHeight = boundsOpts.outHeight

            val requestedWidth = options.size.width.pxOrElse { 0 }
            val requestedHeight = options.size.height.pxOrElse { 0 }

            val effectiveReqW = if (requestedWidth > 0) requestedWidth else ThumbnailTargetSize
            val effectiveReqH = if (requestedHeight > 0) requestedHeight else ThumbnailTargetSize
            val cacheIsSufficient = effectiveReqW <= cachedWidth && effectiveReqH <= cachedHeight

            if (cacheIsSufficient) {
                val decodeOptions = BitmapFactory.Options().apply {
                    inSampleSize = computeInSampleSize(
                        srcWidth = cachedWidth,
                        srcHeight = cachedHeight,
                        requestedWidth = requestedWidth,
                        requestedHeight = requestedHeight,
                    )
                }
                val sampledBitmap = file.inputStream().use {
                    BitmapFactory.decodeStream(it, null, decodeOptions)
                } ?: return decodeFreshThumbnail()
                val dstSize = computeDstSize(sampledBitmap.width, sampledBitmap.height)
                val normalizedBitmap = normalizeBitmap(
                    inBitmap = sampledBitmap,
                    srcWidth = sampledBitmap.width,
                    srcHeight = sampledBitmap.height,
                    dstSize = dstSize,
                )
                return DecodeResult(
                    image = normalizedBitmap.toDrawable(options.context.resources).asImage(),
                    isSampled = DecodeUtils.computeSizeMultiplier(
                        srcWidth = cachedWidth,
                        srcHeight = cachedHeight,
                        dstWidth = normalizedBitmap.width,
                        dstHeight = normalizedBitmap.height,
                        scale = options.scale,
                        maxSize = options.maxBitmapSize,
                    ) < 1.0,
                )
            }
        }

        return decodeFreshThumbnail()
    }

    private suspend fun decodeFreshThumbnail(): DecodeResult {
        val (targetW, targetH) = computeThumbnailTargetSize()
        val rawBitmap = MediaMetadataRetriever().use { nativeRetriever ->
            MediaThumbnailRetriever().use { ffmpegRetriever ->
                val nativeOk = runCatching { nativeRetriever.setDataSource(source) }.isSuccess
                val ffmpegOk = runCatching { ffmpegRetriever.setDataSource(source) }.isSuccess

                if (!nativeOk && !ffmpegOk) throw IllegalStateException("Both retrievers failed to set data source")

                val embeddedPicture = if (nativeOk) {
                    runCatching { nativeRetriever.embeddedPicture }.getOrNull()
                } else {
                    if (ffmpegOk) {
                        runCatching { ffmpegRetriever.getEmbeddedPicture() }.getOrNull()
                    } else {
                        null
                    }
                }

                val embeddedPictureBitmap = embeddedPicture?.let { pictureBytes ->
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(pictureBytes, 0, pictureBytes.size, bounds)
                    val sampleSize = computeInSampleSize(
                        srcWidth = bounds.outWidth,
                        srcHeight = bounds.outHeight,
                        requestedWidth = options.size.width.pxOrElse { embeddedMaxEdge() },
                        requestedHeight = options.size.height.pxOrElse { embeddedMaxEdge() },
                    )
                    BitmapFactory.decodeByteArray(
                        pictureBytes,
                        0,
                        pictureBytes.size,
                        BitmapFactory.Options().apply { inSampleSize = sampleSize },
                    )
                }

                if (embeddedPictureBitmap != null) return@use embeddedPictureBitmap

                val videoDuration = if (nativeOk) {
                    runCatching { nativeRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION) }.getOrNull()?.toLongOrNull() ?: 0L
                } else {
                    0L
                }

                return@use when (strategy) {
                    is ThumbnailStrategy.FirstFrame -> {
                        val frame = if (nativeOk) nativeRetriever.getScaledFrame(0, targetW, targetH) else null
                        frame ?: if (ffmpegOk) runCatching { ffmpegRetriever.getFrameAtTime(0) }.getOrNull() else null
                    }

                    is ThumbnailStrategy.FrameAtPercentage -> {
                        val timeUs = (videoDuration * strategy.percentage * 1000).toLong()
                        val frame = if (nativeOk) nativeRetriever.getScaledFrame(timeUs, targetW, targetH) else null
                        frame ?: if (ffmpegOk) runCatching { ffmpegRetriever.getFrameAtTime(timeUs) }.getOrNull() else null
                    }

                    is ThumbnailStrategy.Hybrid -> {
                        val isProbeSolid = if (nativeOk) {
                            nativeRetriever.getScaledFrame(0, TINY_PROBE_EDGE, TINY_PROBE_EDGE)?.let { probe ->
                                val solid = probe.isMostlySolidColor()
                                probe.recycle()
                                solid
                            } ?: true
                        } else {
                            true
                        }
                        if (isProbeSolid) {
                            val timeUs = (videoDuration * strategy.percentage * 1000).toLong()
                            val frame = if (nativeOk) nativeRetriever.getScaledFrame(timeUs, targetW, targetH) else null
                            frame
                                ?: if (ffmpegOk) {
                                    runCatching { ffmpegRetriever.getFrameAtTime(timeUs) }.getOrNull()
                                } else {
                                    if (nativeOk) nativeRetriever.getScaledFrame(0, targetW, targetH) else null
                                }
                        } else {
                            nativeRetriever.getScaledFrame(0, targetW, targetH)
                        }
                    }
                } ?: throw IllegalStateException("Failed to get video thumbnail.")
            }
        }

        val srcWidth = rawBitmap.width
        val srcHeight = rawBitmap.height
        val dstSize = computeDstSize(srcWidth, srcHeight)
        val scaledBitmap = normalizeBitmap(
            inBitmap = rawBitmap,
            srcWidth = srcWidth,
            srcHeight = srcHeight,
            dstSize = dstSize,
        )

        writeToDiskCache(scaledBitmap)

        return DecodeResult(
            image = scaledBitmap.toDrawable(options.context.resources).asImage(),
            isSampled = DecodeUtils.computeSizeMultiplier(
                srcWidth = srcWidth,
                srcHeight = srcHeight,
                dstWidth = scaledBitmap.width,
                dstHeight = scaledBitmap.height,
                scale = options.scale,
                maxSize = options.maxBitmapSize,
            ) < 1.0,
        )
    }

    private fun computeInSampleSize(
        srcWidth: Int,
        srcHeight: Int,
        requestedWidth: Int,
        requestedHeight: Int,
    ): Int {
        if (srcWidth <= 0 || srcHeight <= 0) return 1
        val targetWidth = requestedWidth.takeIf { it > 0 } ?: srcWidth
        val targetHeight = requestedHeight.takeIf { it > 0 } ?: srcHeight
        var inSampleSize = 1
        while (
            srcWidth / (inSampleSize * 2) >= targetWidth &&
            srcHeight / (inSampleSize * 2) >= targetHeight
        ) {
            inSampleSize *= 2
        }
        return inSampleSize.coerceAtLeast(1)
    }

    private fun MediaMetadataRetriever.setDataSource(source: ImageSource) {
        val metadata = source.metadata
        when {
            metadata is ContentMetadata -> {
                val uri = metadata.uri.toAndroidUri()
                if (uri.scheme?.lowercase() in listOf("http", "https")) {
                    val headers = buildWebDavAuthHeaders(uri)
                    if (headers != null) {
                        setDataSource(uri.toString(), headers)
                    } else {
                        setDataSource(options.context, uri)
                    }
                } else {
                    setDataSource(options.context, uri)
                }
            }

            source.fileSystem === FileSystem.SYSTEM -> {
                setDataSource(source.file().toFile().path)
            }

            else -> error("Not supported")
        }
    }

    private fun MediaThumbnailRetriever.setDataSource(source: ImageSource) {
        val metadata = source.metadata
        when {
            metadata is ContentMetadata -> {
                val uri = metadata.uri.toAndroidUri()
                if (uri.scheme?.lowercase() in listOf("http", "https")) {
                    val authUri = buildWebDavAuthUri(uri)
                    if (authUri != null) {
                        setDataSource(options.context, authUri)
                    } else {
                        setDataSource(options.context, uri)
                    }
                } else {
                    setDataSource(options.context, uri)
                }
            }

            source.fileSystem === FileSystem.SYSTEM -> {
                setDataSource(source.file().toFile().path)
            }

            else -> error("Not supported")
        }
    }

    private fun buildWebDavAuthHeaders(uri: Uri): Map<String, String>? {
        val servers = webDavServersById().values
        for (server in servers) {
            val serverUri = Uri.parse(server.url)
            if (serverUri.scheme.equals(uri.scheme, ignoreCase = true) &&
                serverUri.host.equals(uri.host, ignoreCase = true) &&
                serverUri.port == uri.port &&
                uri.path?.startsWith(server.basePath) == true
            ) {
                val userInfo = buildString {
                    append(server.username)
                    if (server.password.isNotBlank()) {
                        append(':')
                        append(server.password)
                    }
                }
                return mapOf("Authorization" to "Basic ${Base64.encodeToString(userInfo.toByteArray(), Base64.NO_WRAP)}")
            }
        }
        return null
    }

    private fun buildWebDavAuthUri(uri: Uri): Uri? {
        val servers = webDavServersById().values
        for (server in servers) {
            val serverUri = Uri.parse(server.url)
            if (serverUri.scheme.equals(uri.scheme, ignoreCase = true) &&
                serverUri.host.equals(uri.host, ignoreCase = true) &&
                serverUri.port == uri.port &&
                uri.path?.startsWith(server.basePath) == true
            ) {
                val userInfo = buildString {
                    append(Uri.encode(server.username))
                    if (server.password.isNotBlank()) {
                        append(':')
                        append(Uri.encode(server.password))
                    }
                }
                return uri.buildUpon()
                    .encodedAuthority("$userInfo@${uri.encodedAuthority}")
                    .build()
            }
        }
        return null
    }

    private fun readFromDiskCache(): DiskCache.Snapshot? = activeDiskCache.value?.openSnapshot(diskCacheKey)

    private fun writeToDiskCache(inBitmap: Bitmap) {
        val editor = activeDiskCache.value?.openEditor(diskCacheKey) ?: return
        try {
            editor.data.toFile().outputStream().use { output ->
                inBitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 84, output)
            }
            editor.commit()
        } catch (_: Exception) {
            runCatching { editor.abort() }
        }
    }

    private fun normalizeBitmap(inBitmap: Bitmap, srcWidth: Int, srcHeight: Int, dstSize: Size): Bitmap {
        val scale = DecodeUtils.computeSizeMultiplier(
            srcWidth = srcWidth,
            srcHeight = srcHeight,
            dstWidth = dstSize.width.pxOrElse { inBitmap.width },
            dstHeight = dstSize.height.pxOrElse { inBitmap.height },
            scale = options.scale,
            maxSize = options.maxBitmapSize,
        ).toFloat()

        if (scale == 1f) return inBitmap

        val dstWidth = (scale * inBitmap.width).roundToInt()
        val dstHeight = (scale * inBitmap.height).roundToInt()

        val argbSource = if (inBitmap.config == Bitmap.Config.ARGB_8888) {
            inBitmap
        } else {
            inBitmap.copy(Bitmap.Config.ARGB_8888, false) ?: inBitmap
        }
        val outBitmap = try {
            YuvToBitmapBridge.argbScale(argbSource, dstWidth, dstHeight, FilterMode.BOX)
        } finally {
            if (argbSource !== inBitmap) {
                argbSource.recycle()
            }
        } ?: run {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val fallbackBitmap = createBitmap(dstWidth, dstHeight, inBitmap.config ?: Bitmap.Config.ARGB_8888)
            fallbackBitmap.applyCanvas {
                scale(scale, scale)
                drawBitmap(inBitmap, 0f, 0f, paint)
            }
            fallbackBitmap
        }
        inBitmap.recycle()
        return outBitmap
    }

    @OptIn(ExperimentalCoilApi::class)
    private fun computeDstSize(srcWidth: Int, srcHeight: Int): Size {
        if (srcWidth <= 0 || srcHeight <= 0) return Size.ORIGINAL

        val (dstWidth, dstHeight) = DecodeUtils.computeDstSize(
            srcWidth = srcWidth,
            srcHeight = srcHeight,
            targetSize = options.size,
            scale = options.scale,
            maxSize = options.maxBitmapSize,
        )
        val rawScale = DecodeUtils.computeSizeMultiplier(
            srcWidth = srcWidth,
            srcHeight = srcHeight,
            dstWidth = dstWidth,
            dstHeight = dstHeight,
            scale = options.scale,
            maxSize = options.maxBitmapSize,
        )
        val finalScale = if (options.precision == Precision.INEXACT) {
            rawScale.coerceAtMost(1.0)
        } else {
            rawScale
        }
        return Size(
            (finalScale * srcWidth).roundToInt(),
            (finalScale * srcHeight).roundToInt(),
        )
    }

    private fun computeThumbnailTargetSize(): Pair<Int, Int> {
        val w = options.size.width.pxOrElse { 0 }
        val h = options.size.height.pxOrElse { 0 }
        return (
            if (w > 0) maxOf(w, ThumbnailTargetSize) else ThumbnailTargetSize
            ) to (
            if (h > 0) maxOf(h, ThumbnailTargetSize) else ThumbnailTargetSize
            )
    }

    private fun MediaMetadataRetriever.getScaledFrame(timeUs: Long, targetWidth: Int, targetHeight: Int): Bitmap? = runCatching {
        getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, targetWidth, targetHeight)
    }.getOrNull()
        ?: runCatching {
            getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, targetWidth, targetHeight)
        }.getOrNull()
        ?: runCatching {
            getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, FALLBACK_MAX_EDGE, FALLBACK_MAX_EDGE)
        }.getOrNull()
        ?: runCatching {
            getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, FALLBACK_MAX_EDGE, FALLBACK_MAX_EDGE)
        }.getOrNull()

    private fun isRemoteSource(): Boolean {
        val metadata = source.metadata
        if (metadata is ContentMetadata) {
            val scheme = metadata.uri.toAndroidUri().scheme?.lowercase()
            return scheme == "http" || scheme == "https"
        }
        return false
    }

    class Factory(
        private val thumbnailStrategy: () -> ThumbnailStrategy,
        private val localThumbnailDiskCache: Lazy<DiskCache?>,
        private val remoteThumbnailDiskCache: Lazy<DiskCache?>,
        private val webDavServersById: () -> Map<Int, com.sakurafubuki.yume.core.model.WebDavServer>,
    ) : Decoder.Factory {
        override fun create(
            result: SourceFetchResult,
            options: Options,
            imageLoader: ImageLoader,
        ): Decoder? {
            if (!isApplicable(result.mimeType)) return null
            return VideoThumbnailDecoder(
                source = result.source,
                options = options,
                strategy = thumbnailStrategy(),
                localThumbnailDiskCache = localThumbnailDiskCache,
                remoteThumbnailDiskCache = remoteThumbnailDiskCache,
                webDavServersById = webDavServersById,
            )
        }

        private fun isApplicable(mimeType: String?): Boolean = mimeType != null && mimeType.startsWith("video/")
    }
}

private inline fun <T> MediaMetadataRetriever.use(block: (MediaMetadataRetriever) -> T): T {
    try {
        return block(this)
    } finally {
        if (SDK_INT >= 29) {
            close()
        } else {
            release()
        }
    }
}

sealed class ThumbnailStrategy {
    data object FirstFrame : ThumbnailStrategy()
    data class FrameAtPercentage(val percentage: Float = 0.33f) : ThumbnailStrategy()
    data class Hybrid(val percentage: Float = 0.33f) : ThumbnailStrategy()
}

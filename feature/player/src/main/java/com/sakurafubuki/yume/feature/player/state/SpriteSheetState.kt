package com.sakurafubuki.yume.feature.player.state

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Base64
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.Player
import com.sakurafubuki.yume.core.common.Logger
import com.sakurafubuki.yume.core.common.Utils
import com.sakurafubuki.yume.core.data.repository.SpriteSheetCache
import com.sakurafubuki.yume.core.data.repository.SpriteSheetGenerator
import com.sakurafubuki.yume.core.model.WebDavServer
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Stable
class SpriteSheetState(
    private val context: Context,
    private val scope: CoroutineScope,
    private val webDavServersProvider: () -> Map<Int, WebDavServer>,
) {

    companion object {
        private const val TAG = "SpriteSheetState"
    }

    var cachedSheet by mutableStateOf<SpriteSheetCache.CachedSheet?>(null)
        private set

    var isGenerating by mutableStateOf(false)
        private set

    private var currentMediaId: String? = null
    private var currentDurationMs: Long = 0L
    private var generationJob: Job? = null

    fun onMediaReady(player: Player) {
        val mediaItem = player.currentMediaItem ?: return
        val mediaId = mediaItem.mediaId
        if (mediaId != currentMediaId) {
            generationJob?.cancel()
            cachedSheet = null
            isGenerating = false
            currentMediaId = mediaId
            currentDurationMs = 0L
        }
        val durationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: return

        if (mediaId == currentMediaId && durationMs == currentDurationMs && cachedSheet != null) return
        val mediaChanged = durationMs != currentDurationMs
        currentMediaId = mediaId
        currentDurationMs = durationMs
        if (mediaChanged) {
            generationJob?.cancel()
            cachedSheet = null
            isGenerating = false
        }

        val cacheKey = cacheKey(mediaId, durationMs)
        val cached = SpriteSheetCache.get(cacheKey)
        if (cached != null) {
            cachedSheet = cached
            return
        }

        val source = resolveSource(mediaId)
        val httpHeaders = resolveHttpHeaders(mediaId)

        Logger.d(
            "BUG4_SpriteSheet",
            "generate: mediaId=${mediaId.take(100)} source=${source.take(100)} " +
                "durationMs=$durationMs httpHeaders=$httpHeaders",
        )

        val cacheDir = SpriteSheetCache.getCacheDir() ?: return

        isGenerating = true
        generationJob = scope.launch {
            try {
                val result = SpriteSheetGenerator.generate(
                    source = source,
                    httpHeaders = httpHeaders,
                    durationMs = durationMs,
                    cacheDir = cacheDir,
                    cacheKey = cacheKey,
                    context = context,
                )
                if (result != null) {
                    val loaded = SpriteSheetCache.loadFresh(cacheKey)
                    withContext(Dispatchers.Main) {
                        if (currentMediaId == mediaId && currentDurationMs == durationMs) {
                            cachedSheet = loaded
                        }
                    }
                }
            } finally {
                withContext(Dispatchers.Main) {
                    if (currentMediaId == mediaId && currentDurationMs == durationMs) {
                        isGenerating = false
                    }
                }
            }
        }
    }

    fun getFrameIndex(positionMs: Long): Int {
        val meta = cachedSheet?.metadata ?: return 0
        if (meta.durationMs <= 0 || meta.frameCount <= 0) return 0
        val ratio = positionMs.toDouble() / meta.durationMs
        return (ratio * meta.frameCount).toInt().coerceIn(0, meta.frameCount - 1)
    }

    fun release() {
        generationJob?.cancel()
        generationJob = null
        cachedSheet = null
        currentMediaId = null
        currentDurationMs = 0L
        isGenerating = false
    }

    private fun cacheKey(mediaId: String, durationMs: Long): String {
        val input = "${stableMediaIdForCache(mediaId)}|$durationMs"
        return MessageDigest.getInstance("MD5").digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun stableMediaIdForCache(mediaId: String): String {
        val parsed = mediaId.toHttpUrlOrNull() ?: return mediaId
        val builder = parsed.newBuilder()
            .username("")
            .password("")
            .fragment(null)
        builder.removeAllQueryParameters("sign")
        return builder.build().toString()
    }

    private fun resolveSource(mediaId: String): String = when {
        mediaId.startsWith("/") -> mediaId
        mediaId.startsWith("file://") -> {
            Uri.parse(mediaId).path ?: mediaId
        }
        mediaId.startsWith("content://") -> resolveContentUri(mediaId)
        else -> {
            if (mediaId.startsWith("http://", ignoreCase = true) ||
                mediaId.startsWith("https://", ignoreCase = true)
            ) {
                try {
                    val uri = java.net.URI(mediaId)
                    if (uri.rawUserInfo != null) {
                        java.net.URI(
                            uri.scheme,
                            null,
                            uri.host,
                            uri.port,
                            uri.path,
                            uri.query,
                            uri.fragment,
                        ).toString()
                    } else {
                        mediaId
                    }
                } catch (_: Exception) {
                    mediaId
                }
            } else {
                mediaId
            }
        }
    }

    private fun resolveContentUri(mediaId: String): String {
        try {
            val uri = Uri.parse(mediaId)
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.Video.Media.DATA),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                    val path = cursor.getString(idx)
                    if (path != null && File(path).exists()) {
                        Logger.d(TAG, "Resolved content:// to file path: $path")
                        return path
                    }
                }
            }
        } catch (_: Exception) { }
        Logger.d(TAG, "Cannot resolve content:// to file path, will use MediaExtractor fallback: $mediaId")
        return mediaId
    }

    private fun resolveHttpHeaders(mediaId: String): Map<String, String>? {
        if (!mediaId.startsWith("http://", ignoreCase = true) &&
            !mediaId.startsWith("https://", ignoreCase = true)
        ) {
            return null
        }

        if (Utils.isBaiduNetdiskUrl(mediaId)) {
            Logger.d("BUG4_SpriteSheet", "resolveHttpHeaders: Baidu detected -> User-Agent: pan.baidu.com")
            return mapOf("User-Agent" to "pan.baidu.com")
        }

        if (mediaId.contains("?sign=")) return null

        val uri = try {
            android.net.Uri.parse(mediaId)
        } catch (_: Exception) {
            return null
        }

        val matchedServer = findMatchingWebDavServer(uri)
        if (matchedServer != null) {
            val userInfo = buildString {
                append(matchedServer.username)
                if (matchedServer.password.isNotBlank()) {
                    append(':')
                    append(matchedServer.password)
                }
            }
            if (userInfo.isNotBlank()) {
                Logger.d(TAG, "Matched WebDavServer id=${matchedServer.id} for $mediaId")
                return mapOf(
                    "Authorization" to "Basic ${Base64.encodeToString(userInfo.toByteArray(), Base64.NO_WRAP)}",
                )
            }
        } else {
            Logger.d(TAG, "No WebDavServer match for $mediaId, servers count=${webDavServersProvider().size}")
        }

        return try {
            val javaUri = java.net.URI(mediaId)
            val userInfo = javaUri.rawUserInfo ?: return null
            val encoded = Base64.encodeToString(userInfo.toByteArray(), Base64.NO_WRAP)
            Logger.d(TAG, "Using URL-embedded credentials for $mediaId")
            mapOf("Authorization" to "Basic $encoded")
        } catch (_: Exception) {
            null
        }
    }

    private fun findMatchingWebDavServer(uri: android.net.Uri): WebDavServer? {
        val requestPath = normalizePath(uri.path ?: return@findMatchingWebDavServer null)
        return webDavServersProvider().values
            .asSequence()
            .filter { server ->
                val serverUri = android.net.Uri.parse(server.url)
                val serverScheme = serverUri.scheme.orEmpty()
                val serverHost = serverUri.host.orEmpty()
                if (!serverScheme.equals(uri.scheme, ignoreCase = true)) return@filter false
                if (!serverHost.equals(uri.host, ignoreCase = true)) return@filter false

                val serverPort = if (serverUri.port != -1) serverUri.port else defaultPort(serverScheme)
                val requestPort = if (uri.port != -1) uri.port else defaultPort(uri.scheme.orEmpty())
                if (serverPort != requestPort) return@filter false

                requestPath.startsWith(normalizePath(server.basePath))
            }
            .maxByOrNull { normalizePath(it.basePath).length }
    }

    private fun normalizePath(path: String): String {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return "/"
        val withLeadingSlash = if (trimmed.startsWith('/')) trimmed else "/$trimmed"
        return withLeadingSlash.removeSuffix("/").ifBlank { "/" }
    }

    private fun defaultPort(scheme: String): Int = if (scheme.equals("https", ignoreCase = true)) 443 else 80
}

@Composable
fun rememberSpriteSheetState(
    context: Context,
    scope: CoroutineScope,
    webDavServersProvider: () -> Map<Int, WebDavServer>,
): SpriteSheetState = remember { SpriteSheetState(context, scope, webDavServersProvider) }

package com.sakurafubuki.yume.feature.player.ass

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.Player
import com.jakewharton.disklrucache.DiskLruCache
import com.sakurafubuki.yume.core.common.Logger
import com.sakurafubuki.yume.feature.player.extensions.getSubtitleAdjustedPositionMs
import com.sakurafubuki.yume.feature.player.ui.SubtitleConfiguration
import java.io.File
import java.security.MessageDigest
import kotlin.text.Charsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

private val FONT_EXTENSIONS = setOf("otf", "ttf", "ttc", "otc", "pfb", "pfa")
private val httpClient = OkHttpClient()

private const val ASS_CACHE_DIR = "ass_cache"
private const val ASS_CACHE_MAX_BYTES = 50L * 1024 * 1024

@Volatile
private var assDiskCache: DiskLruCache? = null
private val assDiskCacheLock = Any()

private fun getAssDiskCache(context: Context): DiskLruCache {
    assDiskCache?.let { return it }
    synchronized(assDiskCacheLock) {
        assDiskCache?.let { return it }
        val dir = File(context.cacheDir, ASS_CACHE_DIR)
        dir.mkdirs()
        assDiskCache = DiskLruCache.open(dir, 1, 1, ASS_CACHE_MAX_BYTES)
        return assDiskCache!!
    }
}

private fun assCacheKey(uri: String): String = MessageDigest.getInstance("SHA-256")
    .digest(uri.toByteArray(Charsets.UTF_8))
    .take(16)
    .joinToString("") { "%02x".format(it) }

@Composable
fun rememberAssSubtitleState(
    state: AssSubtitleState,
    player: Player,
    assFileUri: Uri?,
    configuration: SubtitleConfiguration,
) {
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        val configFile = File(context.cacheDir, "fonts.conf")
        val fontCacheDir = File(context.cacheDir, "fontconfig_cache")
        fontCacheDir.mkdirs()
        configFile.writeText(
            "<?xml version=\"1.0\"?>\n" +
                "<fontconfig>\n" +
                "    <dir>/system/fonts</dir>\n" +
                "    <dir>/product/fonts</dir>\n" +
                "    <dir>/vendor/fonts</dir>\n" +
                "    <cachedir>" + fontCacheDir.absolutePath + "</cachedir>\n" +
                "\n" +
                "    <!-- Fallback to system CJK fonts for glyphs not covered by custom fonts -->\n" +
                "    <!-- Also provides proper Latin glyphs (not thin/raised) when custom CJK font lacks them -->\n" +
                "    <match target=\"pattern\">\n" +
                "        <edit name=\"family\" mode=\"append\">\n" +
                "            <string>MiSans VF</string>\n" +
                "            <string>Noto Sans CJK SC</string>\n" +
                "        </edit>\n" +
                "    </match>\n" +
                "\n" +
                "    <match target=\"pattern\">\n" +
                "        <edit name=\"lang\" mode=\"assign\">\n" +
                "            <string>und</string>\n" +
                "        </edit>\n" +
                "    </match>\n" +
                "</fontconfig>\n",
        )
        AssRenderer.nativeSetFontConfig(state.handle, configFile.absolutePath)
    }

    LaunchedEffect(assFileUri) {
        Logger.d("AssSubtitleState", "loadTrack triggered: assFileUri=$assFileUri")
        state.clearTrack()
        if (assFileUri == null) {
            return@LaunchedEffect
        }
        val key = assFileUri.toString()
        val bytes = withContext(Dispatchers.IO) {
            val cache = getAssDiskCache(context)
            val cacheKey = assCacheKey(key)
            val cached = runCatching {
                cache.get(cacheKey)?.use { snapshot ->
                    snapshot.getInputStream(0).use { it.readBytes() }
                }
            }.getOrNull()
            cached
                ?: try {
                    val downloaded = when (assFileUri.scheme) {
                        "http", "https" -> {
                            val request = Request.Builder().url(key).build()
                            httpClient.newCall(request).execute().use { response ->
                                if (response.isSuccessful) response.body?.bytes() else null
                            }
                        }

                        else -> {
                            context.contentResolver.openInputStream(assFileUri)?.use { it.readBytes() }
                        }
                    }

                    if (downloaded != null && downloaded.size > 1024) {
                        runCatching {
                            cache.edit(cacheKey)?.let { editor ->
                                try {
                                    editor.newOutputStream(0).use { it.write(downloaded) }
                                    editor.commit()
                                } catch (e: Exception) {
                                    editor.abort()
                                }
                            }
                        }
                    }
                    downloaded
                } catch (e: Exception) {
                    Logger.e("AssSubtitleState", "failed to read $assFileUri: ${e.message}")
                    null
                }
        }
        if (bytes != null) {
            Logger.d("AssSubtitleState", "read ${bytes.size} bytes, loading track")
            state.loadTrack(bytes)
            Logger.d("AssSubtitleState", "track loaded, isLoaded=${state.isLoaded}")
            withContext(Dispatchers.IO) {
                state.loadFontsForTrack(context, bytes)
            }
        } else {
            Logger.e("AssSubtitleState", "failed to read bytes from $assFileUri")
        }
    }

    LaunchedEffect(configuration) {
        state.applyStyleOverride(configuration)
    }

    val isPlaying by produceState(initialValue = player.isPlaying) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                value = isPlaying
            }
        }
        player.addListener(listener)
        awaitDispose {
            player.removeListener(listener)
        }
    }

    val embeddedActive by produceState(false) {
        while (true) {
            val active = AssSubtitleState.embeddedTrackActive
            if (value != active) value = active
            delay(200L)
        }
    }

    LaunchedEffect(state.isLoaded, state.fontsReady, isPlaying, embeddedActive, state.loadGeneration) {
        if ((!state.isLoaded && !embeddedActive) || !state.fontsReady) return@LaunchedEffect

        val capturedGen = state.loadGeneration
        state.renderFrame(player.getSubtitleAdjustedPositionMs())

        while (true) {
            if (state.loadGeneration != capturedGen) return@LaunchedEffect
            state.renderFrame(player.getSubtitleAdjustedPositionMs())
            delay(16L)
        }
    }
}

class AssSubtitleState {
    companion object {
        @Volatile
        private var globalHandle: Long = 0

        @Volatile
        private var fontsAlreadyLoaded: Boolean = false

        @Volatile
        var embeddedTrackActive: Boolean = false

        val availableAssFilesByMediaId: MutableMap<String, List<android.net.Uri>> =
            java.util.concurrent.ConcurrentHashMap()
        val autoSelectAssByMediaId: MutableMap<String, android.net.Uri> =
            java.util.concurrent.ConcurrentHashMap()

        @Synchronized
        fun getOrCreateHandle(): Long {
            if (globalHandle == 0L) {
                globalHandle = AssRenderer.nativeInit(null)
                Logger.d("AssSubtitleState", "nativeInit handle=$globalHandle (global)")
            }
            return globalHandle
        }

        fun markFontsLoaded() {
            fontsAlreadyLoaded = true
        }

        fun areFontsLoaded(): Boolean = fontsAlreadyLoaded
    }

    var isLoaded by mutableStateOf(false)
        private set

    var fontsReady by mutableStateOf(areFontsLoaded())
        private set

    fun setFontsReady() {
        fontsReady = true
        markFontsLoaded()
    }

    val handle: Long = getOrCreateHandle()

    @Volatile
    var loadGeneration: Int = 0
        private set

    private var safTreeUri: String? = null

    fun loadFontsFromSafTree(context: Context, safTreeUri: String) {
        if (handle == 0L) return
        if (areFontsLoaded()) {
            fontsReady = true
            this.safTreeUri = safTreeUri
            return
        }
        val uri = try {
            safTreeUri.toUri()
        } catch (_: Exception) {
            Logger.e("AssSubtitleState", "loadFonts: failed to parse URI: $safTreeUri")
            return
        }

        this.safTreeUri = safTreeUri
        Logger.d("AssSubtitleState", "loadFonts: raw=$safTreeUri, scheme=${uri.scheme}, authority=${uri.authority}")
        val scheme = uri.scheme
        if (scheme == "content") {
            val realPath = resolveTreeUriToPath(uri)
            if (realPath != null) {
                Logger.d("AssSubtitleState", "loadFonts: resolved to real path, fontconfig will scan directly — $realPath")
                AssRenderer.nativeSetFontsDir(handle, realPath)
                AssRenderer.nativeRebuildFontCache(handle)
            } else {
                Logger.d("AssSubtitleState", "loadFonts: cannot resolve path, selective loading will be used")
            }
        } else {
            Logger.d("AssSubtitleState", "loadFonts: direct filesystem path — $safTreeUri")
            AssRenderer.nativeSetFontsDir(handle, safTreeUri)
        }
        Logger.d("AssSubtitleState", "loadFonts: dir registered, waiting for selective font load")
    }

    private fun resolveTreeUriToPath(uri: Uri): String? {
        if (uri.authority != "com.android.externalstorage.documents") {
            Logger.d("AssSubtitleState", "resolvePath: authority=${uri.authority}, not externalstorage")
            return null
        }
        return try {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val colon = docId.indexOf(':')
            if (colon < 0) {
                Logger.d("AssSubtitleState", "resolvePath: no colon in docId=$docId")
                return null
            }
            val volume = docId.substring(0, colon)
            val path = docId.substring(colon + 1)
            val root = if (volume == "primary") "/storage/emulated/0" else "/storage/$volume"
            val realPath = "$root/$path"
            Logger.d("AssSubtitleState", "resolvePath: docId=$docId, volume=$volume, path=$path, result=$realPath")
            realPath
        } catch (_: Exception) {
            Logger.d("AssSubtitleState", "resolvePath: exception resolving URI")
            null
        }
    }

    fun parseAssFontNames(assBytes: ByteArray): Set<String> {
        val text = String(assBytes, Charsets.UTF_8)
        val fonts = mutableSetOf<String>()

        for (line in text.lines()) {
            val trimmed = line.trimStart()
            if (trimmed.startsWith("Style:", ignoreCase = true)) {
                val parts = trimmed.substringAfter(":").split(",")
                if (parts.size >= 2) {
                    val fontName = parts[1].trim()
                    if (fontName.isNotEmpty() && !fontName.startsWith("@")) {
                        fonts.add(fontName)
                    }
                }
            }

            var idx = 0
            while (true) {
                idx = line.indexOf("\\fn", idx)
                if (idx < 0) break
                idx += 3
                val end = line.indexOfAny(charArrayOf('\\', '{', '}'), idx)
                val name = if (end < 0) line.substring(idx).trim() else line.substring(idx, end).trim()
                if (name.isNotEmpty()) {
                    fonts.add(name)
                }
            }
        }

        return fonts
    }

    fun loadFontsForTrack(context: Context, assBytes: ByteArray) {
        if (areFontsLoaded()) {
            fontsReady = true
            return
        }
        val treeUri = safTreeUri
        if (treeUri == null) {
            Logger.d("AssSubtitleState", "loadFontsForTrack: no SAF tree URI stored, marking ready")
            finishFontLoading()
            return
        }
        if (handle == 0L) return

        val fontNames = parseAssFontNames(assBytes)
        if (fontNames.isEmpty()) {
            Logger.d("AssSubtitleState", "loadFontsForTrack: no font names referenced in ASS")
            finishFontLoading()
            return
        }
        Logger.d("AssSubtitleState", "loadFontsForTrack: referenced fonts: $fontNames")

        val uri = treeUri.toUri()
        val rootDoc = DocumentFile.fromTreeUri(context, uri)
        if (rootDoc == null) {
            Logger.e("AssSubtitleState", "loadFontsForTrack: DocumentFile.fromTreeUri returned null")
            finishFontLoading()
            return
        }

        var loadedCount = 0
        searchAndLoadFonts(context, rootDoc, fontNames) { loadedCount++ }

        if (loadedCount > 0) {
            AssRenderer.nativeRebuildFontCache(handle)
        }

        Logger.d("AssSubtitleState", "loadFontsForTrack: loaded $loadedCount fonts")
        finishFontLoading()
    }

    private fun finishFontLoading() {
        fontsReady = true
        markFontsLoaded()
    }

    private fun searchAndLoadFonts(
        context: Context,
        document: DocumentFile,
        fontNames: Set<String>,
        onLoaded: () -> Unit,
    ) {
        val files = try {
            document.listFiles()
        } catch (_: Exception) {
            return
        }
        val resolver = context.contentResolver

        for (file in files) {
            if (file.isDirectory) {
                searchAndLoadFonts(context, file, fontNames, onLoaded)
            } else {
                val name = file.name ?: continue
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext !in FONT_EXTENSIONS) continue

                val baseName = name.substringBeforeLast('.')
                val matches = fontNames.any { fn ->
                    baseName.equals(fn, ignoreCase = true) ||
                        (
                            baseName.length > fn.length &&
                                baseName.startsWith(fn, ignoreCase = true) &&
                                baseName[fn.length] in charArrayOf(' ', '-', '_')
                            )
                }
                if (!matches) continue

                try {
                    resolver.openInputStream(file.uri)?.use { input ->
                        val data = input.readBytes()
                        if (data.isNotEmpty()) {
                            AssRenderer.nativeAddFont(handle, name, data)
                            onLoaded()
                            Logger.d("AssSubtitleState", "loadFontsForTrack: loaded $name (${data.size} bytes)")
                        }
                    }
                } catch (_: Exception) { }
            }
        }
    }

    fun setFrameSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        Logger.d("AssSubtitleState", "setFrameSize: ${width}x$height")
        if (handle != 0L) {
            AssRenderer.nativeSetFrameSize(handle, width, height)
        }
    }

    fun loadTrack(bytes: ByteArray) {
        loadGeneration++
        AssRenderer.nativeLoadTrack(handle, bytes, bytes.size)
        isLoaded = true
    }

    fun clearTrack() {
        loadGeneration++
        isLoaded = false
        AssRenderer.nativeLoadTrack(handle, ByteArray(0), 0)
    }

    fun applyStyleOverride(configuration: SubtitleConfiguration) {
        if (handle == 0L) return

        AssRenderer.nativeSetStyleOverride(
            handle,
            configuration.textSize.toFloat(),
            configuration.textColor,
            configuration.showBackground,
            configuration.applyEmbeddedStyles,
        )
    }

    fun flushEvents() {
        if (handle != 0L) {
            AssRenderer.nativeFlushEvents(handle)
        }
    }

    fun renderFrame(timeMs: Long) {
        AssRenderer.nativeRenderFrame(handle, timeMs)
    }

    fun release() {
        isLoaded = false
    }
}

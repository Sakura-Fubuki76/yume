package com.sakurafubuki.yume.core.common.extensions

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.core.net.toUri
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun File.getSubtitles(): List<File> = withContext(Dispatchers.IO) {
    val mediaName = this@getSubtitles.nameWithoutExtension
    val parentDir = this@getSubtitles.parentFile
    val subtitleExtensions = listOf("srt", "ssa", "ass", "vtt", "ttml")
    val extSet = subtitleExtensions.map { it.lowercase() }.toSet()

    val exactMatches = subtitleExtensions.mapNotNull { ext ->
        File(parentDir, "$mediaName.$ext").takeIf { it.exists() && it.isFile }
    }

    val variantMatches = parentDir.listFiles()?.filter { file ->
        file.isFile &&
            file.name.startsWith("$mediaName.") &&
            file.extension.lowercase() in extSet &&
            file.name !in exactMatches.map { it.name }
    } ?: emptyList()

    val strictMatches = exactMatches + variantMatches
    val strictNames = strictMatches.map { it.name }.toSet()
    val fuzzyMatches = parentDir.listFiles()?.filter { file ->
        file.isFile &&
            file.extension.lowercase() in extSet &&
            file.name !in strictNames &&
            fuzzyMatchNames(mediaName, file.nameWithoutExtension)
    } ?: emptyList()

    strictMatches + fuzzyMatches
}

suspend fun File.getLocalSubtitles(
    context: Context,
    excludeSubsList: List<Uri> = emptyList(),
): List<Uri> = withContext(Dispatchers.Default) {
    val excludeSubsPathSet = excludeSubsList.mapNotNull { context.getPath(it) }.toSet()

    getSubtitles().mapNotNull { file ->
        if (file.path !in excludeSubsPathSet) {
            file.toUri()
        } else {
            null
        }
    }
}

fun String.getThumbnail(): File? {
    val filePathWithoutExtension = this.substringBeforeLast(".")
    val imageExtensions = listOf("png", "jpg", "jpeg")
    for (imageExtension in imageExtensions) {
        val file = File("$filePathWithoutExtension.$imageExtension")
        if (file.exists()) return file
    }
    return null
}

fun File.isSubtitle(): Boolean {
    val subtitleExtensions = listOf("srt", "ssa", "ass", "vtt", "ttml")
    return extension.lowercase() in subtitleExtensions
}

fun File.deleteFiles() {
    try {
        listFiles()?.onEach {
            it.delete()
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

val File.prettyName: String
    get() = this.name.takeIf { this.path != Environment.getExternalStorageDirectory()?.path } ?: "Internal Storage"

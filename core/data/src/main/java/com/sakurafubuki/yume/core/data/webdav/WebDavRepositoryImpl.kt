package com.sakurafubuki.yume.core.data.webdav

import android.net.Uri
import android.webkit.MimeTypeMap
import com.sakurafubuki.yume.core.model.WebDavMediaItem
import com.sakurafubuki.yume.core.model.WebDavServer
import com.thegrizzlylabs.sardineandroid.Sardine
import java.io.InputStream
import java.util.Date
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class WebDavRepositoryImpl @Inject constructor(
    private val sardineFactory: SardineFactory,
) : WebDavRepository {

    override suspend fun listDirectory(
        server: WebDavServer,
        path: String,
    ): List<WebDavMediaItem> = withContext(Dispatchers.IO) {
        withSardine(server) {
            list(buildDirectoryUrl(server, path, forceTrailingSlash = true))
        }
            .drop(1)
            .map { resource ->
                val (width, height) = extractDimensions(resource)
                WebDavMediaItem(
                    name = resource.name ?: resource.href?.path?.substringAfterLast('/') ?: "",
                    href = resource.href.toString(),
                    contentType = resource.contentType ?: guessMimeType(resource.name ?: ""),
                    size = resource.contentLength ?: 0L,
                    width = width,
                    height = height,
                    lastModified = resource.modified ?: Date(),
                    isDirectory = resource.isDirectory,
                    serverId = server.id,
                )
            }
    }

    override suspend fun fileExists(server: WebDavServer, path: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            withSardine(server) {
                exists(buildDirectoryUrl(server, path))
            }
        }.getOrDefault(false)
    }

    override suspend fun downloadFile(server: WebDavServer, path: String): InputStream? = withContext(Dispatchers.IO) {
        runCatching {
            withSardine(server) {
                get(buildDirectoryUrl(server, path))
            }
        }.getOrNull()
    }

    override suspend fun testConnection(server: WebDavServer): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            withSardine(server) {
                list(buildDirectoryUrl(server, server.basePath, forceTrailingSlash = true))
            }
        }
        Unit
    }

    private val sardineByServer = java.util.concurrent.ConcurrentHashMap<Int, Sardine>()

    private fun getSardine(server: WebDavServer): Sardine = sardineByServer.computeIfAbsent(server.id) {
        sardineFactory.create().apply {
            setCredentials(server.username, server.password, true)
        }
    }

    private suspend fun <T> withSardine(server: WebDavServer, block: Sardine.() -> T): T = withContext(Dispatchers.IO) {
        getSardine(server).block()
    }

    fun onServerRemoved(serverId: Int) {
        sardineByServer.remove(serverId)
    }

    override fun getStreamUrl(item: WebDavMediaItem, server: WebDavServer): String {
        val resolvedUri = resolveStreamUri(item.href, server)
        if (item.rawVideoUrl != null && resolvedUri.getQueryParameter("sign") != null) {
            return resolvedUri.toString()
        }
        if (server.username.isBlank()) {
            val result = resolvedUri.toString()
            return result
        }

        val hostPart = resolvedUri.encodedAuthority?.substringAfter("@", resolvedUri.encodedAuthority.orEmpty())
            ?: return resolvedUri.toString()
        val userInfo = buildString {
            append(Uri.encode(server.username))
            if (server.password.isNotBlank()) {
                append(':')
                append(Uri.encode(server.password))
            }
        }
        return resolvedUri.buildUpon().encodedAuthority("$userInfo@$hostPart").build().toString()
    }

    private fun buildDirectoryUrl(server: WebDavServer, path: String, forceTrailingSlash: Boolean = false): String {
        val baseUrl = server.url.trimEnd('/')
        val normalizedPath = normalizePath(path.ifBlank { server.basePath })
        val encodedPath = normalizedPath
            .split('/')
            .filter { it.isNotBlank() }
            .joinToString("/") { Uri.encode(Uri.decode(it)) }
        val url = if (encodedPath.isBlank()) {
            "$baseUrl/"
        } else {
            "$baseUrl/$encodedPath"
        }
        return if (forceTrailingSlash && !url.endsWith('/')) "$url/" else url
    }

    private fun normalizePath(path: String): String {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return "/"
        val withLeadingSlash = if (trimmed.startsWith('/')) trimmed else "/$trimmed"
        return withLeadingSlash.removeSuffix("/").ifBlank { "/" }
    }

    private fun resolveStreamUri(href: String, server: WebDavServer): Uri {
        val parsedHref = Uri.parse(href)
        val hasHttpScheme = parsedHref.scheme.equals("http", ignoreCase = true) ||
            parsedHref.scheme.equals("https", ignoreCase = true)
        if (hasHttpScheme && !parsedHref.encodedAuthority.isNullOrBlank()) {
            return parsedHref
        }

        val baseUri = Uri.parse(server.url)
        val resolvedPath = normalizePath(
            when {
                href.startsWith("/") -> href
                !parsedHref.path.isNullOrBlank() -> parsedHref.path.orEmpty()
                else -> href
            },
        )
        val encodedPath = resolvedPath
            .split('/')
            .filter { it.isNotBlank() }
            .joinToString("/", prefix = "/") { Uri.encode(Uri.decode(it)) }

        return baseUri.buildUpon().apply {
            encodedPath(encodedPath)
            encodedQuery(parsedHref.encodedQuery)
            encodedFragment(parsedHref.encodedFragment)
        }.build()
    }

    private fun guessMimeType(filename: String): String {
        val extension = filename.substringAfterLast('.', "")
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"
    }

    private fun extractDimensions(resource: Any): Pair<Int?, Int?> {
        val properties = runCatching {
            resource.javaClass.methods
                .firstOrNull { it.name == "getCustomProps" }
                ?.invoke(resource)
        }.getOrNull() as? Map<*, *> ?: return null to null

        val width = findDimension(properties, "width", "imagewidth", "image-width", "oc:image-width")
        val height = findDimension(properties, "height", "imageheight", "image-height", "oc:image-height")
        return width to height
    }

    private fun findDimension(properties: Map<*, *>, vararg keys: String): Int? {
        return properties.entries.firstNotNullOfOrNull { (key, value) ->
            val normalizedKey = key.toString().lowercase()
            if (!keys.any { normalizedKey.contains(it) }) return@firstNotNullOfOrNull null
            value.toString().toIntOrNull()?.takeIf { it > 0 }
        }
    }
}

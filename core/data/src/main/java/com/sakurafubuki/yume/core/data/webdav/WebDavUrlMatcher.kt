package com.sakurafubuki.yume.core.data.webdav

import android.net.Uri
import com.sakurafubuki.yume.core.model.WebDavServer
import okhttp3.HttpUrl

/**
 * 在给定服务器集合中查找匹配 URL 的服务器（供播放器、图片加载等共用）。
 *
 * 规则：
 * 1. scheme/host/port 一致 + 请求路径以 server.basePath 为前缀，取 basePath 最长者（最具体）；
 * 2. 无路径匹配时回退到仅按 host/port 匹配。
 */
fun findMatchingWebDavServer(servers: Collection<WebDavServer>, url: HttpUrl): WebDavServer? {
    val normalizedPath = normalizeWebDavPath(url.encodedPath)

    val pathMatch = servers
        .asSequence()
        .filter { server ->
            val serverUri = Uri.parse(server.url)
            val serverScheme = serverUri.scheme.orEmpty()
            val serverHost = serverUri.host.orEmpty()
            if (!serverScheme.equals(url.scheme, ignoreCase = true)) return@filter false
            if (!serverHost.equals(url.host, ignoreCase = true)) return@filter false
            val serverPort = if (serverUri.port != -1) serverUri.port else defaultWebDavPort(serverScheme)
            val requestPort = if (url.port != -1) url.port else defaultWebDavPort(url.scheme)
            if (serverPort != requestPort) return@filter false
            normalizedPath.startsWith(normalizeWebDavPath(server.basePath))
        }
        .maxByOrNull { normalizeWebDavPath(it.basePath).length }
    if (pathMatch != null) return pathMatch

    return servers.firstOrNull { server ->
        val serverUri = Uri.parse(server.url)
        serverUri.scheme.equals(url.scheme, ignoreCase = true) &&
            serverUri.host.equals(url.host, ignoreCase = true) &&
            (serverUri.port.let { if (it != -1) it else defaultWebDavPort(serverUri.scheme.orEmpty()) }) ==
            (url.port.let { if (it != -1) it else defaultWebDavPort(url.scheme) })
    }
}

fun normalizeWebDavPath(path: String): String {
    val trimmed = path.trim()
    if (trimmed.isEmpty()) return "/"
    val withLeadingSlash = if (trimmed.startsWith('/')) trimmed else "/$trimmed"
    return withLeadingSlash.removeSuffix("/").ifBlank { "/" }
}

private fun defaultWebDavPort(scheme: String): Int = if (scheme.equals("https", ignoreCase = true)) 443 else 80

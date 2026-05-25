package com.sakurafubuki.yume.core.data.openlist

import android.graphics.BitmapFactory
import androidx.core.net.toUri
import com.sakurafubuki.yume.core.common.Logger
import com.sakurafubuki.yume.core.model.WebDavServer
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Singleton
class OpenListApiImpl @Inject constructor(
    private val okHttpClient: OkHttpClient,
) : OpenListApi {

    private val json = Json { ignoreUnknownKeys = true }

    private data class TokenEntry(val token: String, val cachedAt: Long = System.currentTimeMillis())

    private val tokenCache = mutableMapOf<String, TokenEntry>()
    private val loginMutex = Mutex()

    private fun cachedToken(baseUrl: String): String? {
        val entry = tokenCache[baseUrl] ?: return null
        if (System.currentTimeMillis() - entry.cachedAt > TOKEN_TTL_MS) {
            tokenCache.remove(baseUrl)
            return null
        }
        return entry.token
    }

    private fun cacheToken(baseUrl: String, token: String) {
        tokenCache[baseUrl] = TokenEntry(token)
    }

    override suspend fun login(server: WebDavServer): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = extractBaseUrl(server)
            val username = server.username.trim()
            val password = server.password.trim()

            if (username.isBlank() || password.isBlank()) {
                throw RuntimeException("Username and password are required for login")
            }

            val loginBody = json.encodeToString(LoginRequest.serializer(), LoginRequest(username, password))
            val request = Request.Builder()
                .url("$baseUrl/api/auth/login")
                .post(loginBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .header("Accept", "application/json")
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                val bodyString = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw RuntimeException("HTTP ${response.code}: $bodyString")
                }

                val loginResponse = json.decodeFromString(LoginResponse.serializer(), bodyString)
                if (loginResponse.code != 200 || loginResponse.data?.token.isNullOrBlank()) {
                    throw RuntimeException(loginResponse.message.ifBlank { "Login failed" })
                }

                val token = loginResponse.data.token
                cacheToken(baseUrl, token)
                token
            }
        }
    }

    override suspend fun listDirectory(
        server: WebDavServer,
        path: String,
        page: Int,
        perPage: Int,
        refresh: Boolean,
    ): Result<FsListData> = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = extractBaseUrl(server)
            val normalizedPath = path.let {
                val trimmed = it.trim()
                if (trimmed.isEmpty()) {
                    "/"
                } else if (!trimmed.startsWith('/')) {
                    "/$trimmed"
                } else {
                    trimmed
                }
            }
            Logger.d("OpenListApi", "fs/list path=$normalizedPath server=${server.url} basePath=${server.basePath}")
            val requestBody = FsListRequest(
                path = normalizedPath,
                page = page,
                per_page = perPage,
                refresh = refresh,
            )
            val jsonBody = json.encodeToString(FsListRequest.serializer(), requestBody)

            val authHeader = resolveAuthHeader(server, baseUrl)
            var result = executeFsListRequest(baseUrl, jsonBody, authHeader)
            if (result.isAuthFailure && hasLoginCredentials(server)) {
                tokenCache.remove(baseUrl)
                val token = loginMutex.withLock {
                    cachedToken(baseUrl) ?: login(server).getOrThrow()
                }
                result = executeFsListRequest(baseUrl, jsonBody, token)
            } else if (result.isAuthFailure && authHeader != null) {
                result = executeFsListRequest(baseUrl, jsonBody, null)
            }

            result.toData()
        }
    }

    override suspend fun search(
        server: WebDavServer,
        parent: String,
        keywords: String,
        scope: Int,
        page: Int,
        perPage: Int,
    ): Result<FsSearchData> = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = extractBaseUrl(server)
            val normalizedParent = parent.let {
                val trimmed = it.trim()
                if (trimmed.isEmpty()) {
                    "/"
                } else if (!trimmed.startsWith('/')) {
                    "/$trimmed"
                } else {
                    trimmed
                }
            }
            val requestBody = FsSearchRequest(
                parent = normalizedParent,
                keywords = keywords,
                scope = scope,
                page = page,
                per_page = perPage,
                password = "",
            )
            val jsonBody = json.encodeToString(FsSearchRequest.serializer(), requestBody)

            val authHeader = resolveAuthHeader(server, baseUrl)
            var result = executeFsSearchRequest(baseUrl, jsonBody, authHeader)
            if (result.isAuthFailure && hasLoginCredentials(server)) {
                tokenCache.remove(baseUrl)
                val token = loginMutex.withLock {
                    cachedToken(baseUrl) ?: login(server).getOrThrow()
                }
                result = executeFsSearchRequest(baseUrl, jsonBody, token)
            } else if (result.isAuthFailure && authHeader != null) {
                result = executeFsSearchRequest(baseUrl, jsonBody, null)
            }

            result.toData()
        }
    }

    private fun executeFsListRequest(
        baseUrl: String,
        jsonBody: String,
        authHeader: String?,
    ): FsListResult {
        val requestBuilder = Request.Builder()
            .url("$baseUrl/api/fs/list")
            .post(jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Accept", "application/json")

        authHeader?.let { requestBuilder.header("Authorization", it) }

        okHttpClient.newCall(requestBuilder.build()).execute().use { response ->
            val bodyString = response.body?.string().orEmpty()
            val parsed = if (response.isSuccessful && bodyString.isLikelyJson()) {
                runCatching { json.decodeFromString(FsListResponse.serializer(), bodyString) }
                    .getOrElse {
                        throw RuntimeException("OpenList API response could not be parsed: ${bodyString.toPreview()}", it)
                    }
            } else if (response.isSuccessful) {
                throw RuntimeException("OpenList API returned a non-JSON response: ${bodyString.toPreview()}")
            } else {
                null
            }
            return FsListResult(response.code, bodyString, parsed)
        }
    }

    private fun executeFsSearchRequest(
        baseUrl: String,
        jsonBody: String,
        authHeader: String?,
    ): FsSearchResult {
        val requestBuilder = Request.Builder()
            .url("$baseUrl/api/fs/search")
            .post(jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Accept", "application/json")

        authHeader?.let { requestBuilder.header("Authorization", it) }

        okHttpClient.newCall(requestBuilder.build()).execute().use { response ->
            val bodyString = response.body?.string().orEmpty()
            val parsed = if (response.isSuccessful && bodyString.isLikelyJson()) {
                runCatching { json.decodeFromString(FsSearchResponse.serializer(), bodyString) }
                    .getOrElse {
                        throw RuntimeException("OpenList search response could not be parsed: ${bodyString.toPreview()}", it)
                    }
            } else if (response.isSuccessful) {
                throw RuntimeException("OpenList search returned a non-JSON response: ${bodyString.toPreview()}")
            } else {
                null
            }
            return FsSearchResult(response.code, bodyString, parsed)
        }
    }

    private suspend fun resolveAuthHeader(server: WebDavServer, baseUrl: String): String? {
        buildAuthorizationHeader(server)?.let { return it }
        cachedToken(baseUrl)?.let { return it }
        if (!hasLoginCredentials(server)) return null

        val token = loginMutex.withLock {
            cachedToken(baseUrl) ?: login(server).getOrThrow()
        }
        return token
    }

    private fun buildAuthorizationHeader(server: WebDavServer): String? {
        val username = server.username.trim()
        val password = server.password.trim()
        if (server.isImageHosting) {
            if (username.startsWith("Bearer ", ignoreCase = true)) {
                val token = username.substringAfter(' ').trim().takeIf { it.isNotBlank() } ?: return null
                return "Bearer $token"
            }
            if (username.equals("bearer", ignoreCase = true)) {
                val token = password.takeIf { it.isNotBlank() } ?: return null
                return "Bearer $token"
            }
            password.takeIf { it.isNotBlank() }?.let { return "Bearer $it" }
            username.takeIf { it.isNotBlank() }?.let { return "Bearer $it" }
            return null
        }
        if (username.isBlank() && password.isBlank()) return null
        if (username.startsWith("Bearer ", ignoreCase = true)) {
            val token = username.substringAfter(' ').trim().takeIf { it.isNotBlank() } ?: return null
            return token
        }
        if (username.equals("bearer", ignoreCase = true)) {
            val token = password.takeIf { it.isNotBlank() } ?: return null
            return token
        }
        return null
    }

    private fun hasLoginCredentials(server: WebDavServer): Boolean {
        if (server.isImageHosting) return false
        val username = server.username.trim()
        val password = server.password.trim()
        return username.isNotBlank() &&
            password.isNotBlank() &&
            !username.startsWith("Bearer ", ignoreCase = true) &&
            !username.equals("bearer", ignoreCase = true)
    }

    private fun extractBaseUrl(server: WebDavServer): String {
        val serverUri = server.url.toUri()
        val authority = if (serverUri.port != -1) "${serverUri.host}:${serverUri.port}" else serverUri.host.orEmpty()
        return "${serverUri.scheme}://$authority"
    }

    override suspend fun probeImageDimensions(imageUrl: String): Result<Pair<Int, Int>> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(imageUrl)
                .header("Range", "bytes=0-65535")
                .header("Accept", "image/*")
                .build()

            val data = okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw RuntimeException("HTTP ${response.code}")
                }
                val body = response.body ?: throw RuntimeException("Empty response body")
                body.bytes()
            }
            if (data.isEmpty()) {
                throw RuntimeException("Zero-length response")
            }

            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, opts)

            if (opts.outWidth > 0 && opts.outHeight > 0) {
                opts.outWidth to opts.outHeight
            } else {
                throw RuntimeException("BitmapFactory failed to decode dimensions")
            }
        }
    }

    private data class FsListResult(
        val httpCode: Int,
        val body: String,
        val response: FsListResponse?,
    ) {
        val isAuthFailure: Boolean
            get() = httpCode == 401 || httpCode == 403 || response?.code == 401

        fun toData(): FsListData {
            if (httpCode !in 200..299) {
                throw RuntimeException("HTTP $httpCode: $body")
            }
            val fsResponse = response ?: throw RuntimeException("Empty API response")
            if (fsResponse.code != 200) {
                throw RuntimeException(fsResponse.message.ifBlank { "API error code: ${fsResponse.code}" })
            }
            return fsResponse.data ?: FsListData()
        }
    }

    private data class FsSearchResult(
        val httpCode: Int,
        val body: String,
        val response: FsSearchResponse?,
    ) {
        val isAuthFailure: Boolean
            get() = httpCode == 401 || httpCode == 403 || response?.code == 401

        fun toData(): FsSearchData {
            if (httpCode !in 200..299) {
                throw RuntimeException("HTTP $httpCode: $body")
            }
            val searchResponse = response ?: throw RuntimeException("Empty search response")
            if (searchResponse.code != 200) {
                throw RuntimeException(searchResponse.message.ifBlank { "Search API error code: ${searchResponse.code}" })
            }
            return searchResponse.data ?: FsSearchData()
        }
    }

    private fun String.isLikelyJson(): Boolean {
        val first = trimStart().firstOrNull()
        return first == '{' || first == '['
    }

    private fun String.toPreview(maxLength: Int = 240): String {
        val normalized = replace(Regex("\\s+"), " ").trim()
        if (normalized.isBlank()) return "<empty body>"
        return if (normalized.length <= maxLength) normalized else normalized.take(maxLength) + "..."
    }

    private companion object {
        private const val TOKEN_TTL_MS = 30 * 60 * 1000L
    }
}

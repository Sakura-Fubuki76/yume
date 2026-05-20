@file:Suppress("unused")

package com.sakurafubuki.yume.navigation3

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator

fun <T : Any> MutableList<T>.pushSingleTop(key: T) {
    if (lastOrNull() != key) {
        add(key)
    }
}

fun <T : Any> MutableList<T>.popOrFalse(): Boolean {
    if (size <= 1) return false
    removeLastOrNull()
    return true
}

@Composable
fun rememberYumeNavEntryDecorators(): List<NavEntryDecorator<NavKey>> = listOf(
    rememberSaveableStateHolderNavEntryDecorator(),
    rememberViewModelStoreNavEntryDecorator(),
)

fun mediaPickerKey(
    folderId: String? = null,
    cloudPath: String? = null,
    cloudServerId: Int? = null,
): MediaPickerKey = MediaPickerKey(
    folderId = folderId?.ifBlank { null },
    cloudPath = cloudPath?.normalizePathOrRoot(),
    cloudServerId = cloudServerId,
)

fun searchKey(
    cloudPath: String? = null,
    cloudServerId: Int? = null,
    cloudServerIds: Collection<Int> = emptyList(),
): SearchKey = SearchKey(
    cloudPath = cloudPath?.normalizePathOrRoot(),
    cloudServerId = cloudServerId,
    cloudServerIds = cloudServerIds.distinct(),
)

fun imageBrowserKey(
    path: String = "/",
    cloudServerId: Int? = null,
): ImageBrowserKey = ImageBrowserKey(
    path = path.normalizePathOrRoot(),
    cloudServerId = cloudServerId,
)

fun String.normalizePathOrRoot(): String {
    val trimmed = trim()
    if (trimmed.isEmpty()) return "/"
    val withLeadingSlash = if (trimmed.startsWith('/')) trimmed else "/$trimmed"
    return withLeadingSlash.removeSuffix("/").ifBlank { "/" }
}

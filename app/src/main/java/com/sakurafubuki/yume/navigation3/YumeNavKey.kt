@file:Suppress("unused")

package com.sakurafubuki.yume.navigation3

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface YumeNavKey : NavKey

@Serializable
sealed interface MediaNavKey : YumeNavKey

@Serializable
data class MediaPickerKey(
    val folderId: String? = null,
    val cloudPath: String? = null,
    val cloudServerId: Int? = null,
) : MediaNavKey

@Serializable
data class SearchKey(
    val cloudPath: String? = null,
    val cloudServerId: Int? = null,
    val cloudServerIds: List<Int> = emptyList(),
) : MediaNavKey

@Serializable
sealed interface ImageNavKey : YumeNavKey

@Serializable
data class ImageBrowserKey(
    val path: String = "/",
    val cloudServerId: Int? = null,
) : ImageNavKey

@Serializable
sealed interface SettingsNavKey : YumeNavKey

@Serializable
data object SettingsHomeKey : SettingsNavKey

@Serializable
data object AppearancePreferencesKey : SettingsNavKey

@Serializable
data object MediaLibraryPreferencesKey : SettingsNavKey

@Serializable
data object ThumbnailPreferencesKey : SettingsNavKey

@Serializable
data object FolderPreferencesKey : SettingsNavKey

@Serializable
data object PlayerPreferencesKey : SettingsNavKey

@Serializable
data object GesturePreferencesKey : SettingsNavKey

@Serializable
data object DecoderPreferencesKey : SettingsNavKey

@Serializable
data object AudioPreferencesKey : SettingsNavKey

@Serializable
data object SubtitlePreferencesKey : SettingsNavKey

@Serializable
data object PerformancePreferencesKey : SettingsNavKey

@Serializable
data object GeneralPreferencesKey : SettingsNavKey

@Serializable
data object AboutPreferencesKey : SettingsNavKey

@Serializable
data object LibrariesKey : SettingsNavKey

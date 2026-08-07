package com.sakurafubuki.yume.core.model

import kotlinx.serialization.Serializable

@Serializable
data class ChapterEntry(
    val startTimeMs: Long,
    val endTimeMs: Long,
    val title: String,
)

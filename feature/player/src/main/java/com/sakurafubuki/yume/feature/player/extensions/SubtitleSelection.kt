package com.sakurafubuki.yume.feature.player.extensions

import android.net.Uri

const val DISABLED_SUBTITLE_SELECTION_URI = "yume://subtitle-disabled"

fun String?.isDisabledSubtitleSelection(): Boolean = this == DISABLED_SUBTITLE_SELECTION_URI

fun Uri.isDisabledSubtitleSelection(): Boolean = toString().isDisabledSubtitleSelection()

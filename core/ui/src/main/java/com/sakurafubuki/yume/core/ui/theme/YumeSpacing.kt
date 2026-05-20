package com.sakurafubuki.yume.core.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * MD3 Expressive spacing tokens aligned with the 4dp grid system.
 *
 * MD3 Expressive spacing spec defines 16 token values, progressing
 * from 0dp (micro) to 128dp (extended). This object provides both
 * the raw scale and a semantic layer so that callers can express
 * intent without hardcoding dp values.
 */
object YumeSpacing {
    // --- scale tokens ---
    val none: Dp = 0.dp
    val space4: Dp = 4.dp
    val space8: Dp = 8.dp
    val space12: Dp = 12.dp
    val space16: Dp = 16.dp
    val space20: Dp = 20.dp
    val space24: Dp = 24.dp
    val space28: Dp = 28.dp
    val space32: Dp = 32.dp
    val space40: Dp = 40.dp
    val space48: Dp = 48.dp
    val space64: Dp = 64.dp

    // --- semantic tokens ---
    val componentGap: Dp = space8
    val componentPadding: Dp = space16
    val sectionGap: Dp = space24
    val pageMargin: Dp = space16
    val listItemGap: Dp = space8
    val listItemPadding: Dp = space16
    val listSectionTop: Dp = space20
    val listSectionBottom: Dp = space12
    val listSectionStart: Dp = space12
    val dialogMargin: Dp = space16
    val contentAreaMaxWidth: Dp = 840.dp
}

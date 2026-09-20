package com.sakurafubuki.yume.core.ui

import androidx.compose.ui.unit.dp

/**
 * Extra scroll range reserved at the bottom of scrollable pages so their last row can
 * rise above the floating pill navigation bar in compact layouts. This is not a visual
 * padding bar: it extends the scroll range inside the page, so the space revealed when
 * scrolled to the end shows the page's own background.
 */
val FloatingNavigationClearance = 104.dp

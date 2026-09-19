package com.sakurafubuki.yume.core.ui.motion

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntOffset

val YumeTransitionEasing = FastOutSlowInEasing
const val YUME_PAGE_DURATION_MS = 300

fun yumePageSpatialSpec(): FiniteAnimationSpec<IntOffset> = tween(
    durationMillis = YUME_PAGE_DURATION_MS,
    easing = YumeTransitionEasing,
)

fun yumePageEffectsSpec(): FiniteAnimationSpec<Float> = tween(
    durationMillis = YUME_PAGE_DURATION_MS,
    easing = YumeTransitionEasing,
)

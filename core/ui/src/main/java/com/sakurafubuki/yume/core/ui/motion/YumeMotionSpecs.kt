package com.sakurafubuki.yume.core.ui.motion

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.ui.unit.IntOffset

fun yumePageSpatialSpringSpec(): FiniteAnimationSpec<IntOffset> = spring(
    dampingRatio = YUME_PAGE_SPATIAL_DAMPING_RATIO,
    stiffness = YUME_PAGE_SPATIAL_STIFFNESS,
    visibilityThreshold = YUME_PAGE_SPATIAL_VISIBILITY_THRESHOLD,
)

fun yumePageEffectsSpringSpec(): FiniteAnimationSpec<Float> = spring(
    dampingRatio = YUME_PAGE_EFFECTS_DAMPING_RATIO,
    stiffness = YUME_PAGE_EFFECTS_STIFFNESS,
    visibilityThreshold = YUME_PAGE_EFFECTS_VISIBILITY_THRESHOLD,
)

private const val YUME_PAGE_SPATIAL_DAMPING_RATIO = 0.86f
private const val YUME_PAGE_SPATIAL_STIFFNESS = 300f
private const val YUME_PAGE_EFFECTS_DAMPING_RATIO = 1f
private const val YUME_PAGE_EFFECTS_STIFFNESS = 520f
private const val YUME_PAGE_EFFECTS_VISIBILITY_THRESHOLD = 0.02f
private val YUME_PAGE_SPATIAL_VISIBILITY_THRESHOLD = IntOffset(4, 4)

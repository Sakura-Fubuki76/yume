package com.sakurafubuki.yume.navigation3

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.TransformOrigin
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.scene.Scene
import androidx.navigationevent.NavigationEvent
import com.sakurafubuki.yume.core.ui.motion.yumePageEffectsSpringSpec
import com.sakurafubuki.yume.core.ui.motion.yumePageSpatialSpringSpec

data class YumeNavTransitionSpecs(
    val transitionSpec: AnimatedContentTransitionScope<Scene<NavKey>>.() -> ContentTransform,
    val popTransitionSpec: AnimatedContentTransitionScope<Scene<NavKey>>.() -> ContentTransform,
    val predictivePopTransitionSpec: AnimatedContentTransitionScope<Scene<NavKey>>.(
        @NavigationEvent.SwipeEdge Int,
    ) -> ContentTransform,
)

@Composable
fun yumeNavTransitionSpecs(): YumeNavTransitionSpecs {
    val spatialSpec = yumePageSpatialSpringSpec()
    val effectsSpec = yumePageEffectsSpringSpec()

    return YumeNavTransitionSpecs(
        transitionSpec = {
            slideInHorizontally(
                animationSpec = spatialSpec,
                initialOffsetX = { fullWidth -> fullWidth },
            ) + fadeIn(
                animationSpec = effectsSpec,
            ) togetherWith slideOutHorizontally(
                animationSpec = spatialSpec,
                targetOffsetX = { fullWidth -> (-fullWidth * YUME_NAV_POP_OFFSET_RATIO).toInt() },
            ) + fadeOut(
                animationSpec = effectsSpec,
            )
        },
        popTransitionSpec = {
            slideInHorizontally(
                animationSpec = spatialSpec,
                initialOffsetX = { fullWidth -> (-fullWidth * YUME_NAV_POP_OFFSET_RATIO).toInt() },
            ) + fadeIn(
                animationSpec = effectsSpec,
            ) togetherWith slideOutHorizontally(
                animationSpec = spatialSpec,
                targetOffsetX = { fullWidth -> fullWidth },
            ) + fadeOut(
                animationSpec = effectsSpec,
            )
        },
        predictivePopTransitionSpec = {
            fadeIn(
                animationSpec = tween(durationMillis = YUME_NAV_PREDICTIVE_DURATION_MS),
            ) + scaleIn(
                animationSpec = tween(
                    durationMillis = YUME_NAV_PREDICTIVE_DURATION_MS,
                    delayMillis = YUME_NAV_PREDICTIVE_SCALE_DELAY_MS,
                ),
                initialScale = YUME_NAV_PREDICTIVE_SCALE,
                transformOrigin = YUME_NAV_PREDICTIVE_TRANSFORM_ORIGIN,
            ) togetherWith fadeOut(
                animationSpec = tween(durationMillis = YUME_NAV_PREDICTIVE_DURATION_MS),
            ) + scaleOut(
                animationSpec = tween(
                    durationMillis = YUME_NAV_PREDICTIVE_DURATION_MS,
                    delayMillis = YUME_NAV_PREDICTIVE_SCALE_DELAY_MS,
                ),
                targetScale = YUME_NAV_PREDICTIVE_SCALE,
                transformOrigin = YUME_NAV_PREDICTIVE_TRANSFORM_ORIGIN,
            )
        },
    )
}

private const val YUME_NAV_POP_OFFSET_RATIO = 0.3f
private const val YUME_NAV_PREDICTIVE_DURATION_MS = 220
private const val YUME_NAV_PREDICTIVE_SCALE_DELAY_MS = 30
private const val YUME_NAV_PREDICTIVE_SCALE = 0.9f
private val YUME_NAV_PREDICTIVE_TRANSFORM_ORIGIN = TransformOrigin(-1f, 0.5f)

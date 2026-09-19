package com.sakurafubuki.yume.navigation

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldLayout
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import com.sakurafubuki.yume.core.ui.motion.YumeTransitionEasing
import kotlin.math.abs

internal fun WindowSizeClass.navigationSuiteType(): NavigationSuiteType = when {
    isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND) -> NavigationSuiteType.NavigationDrawer
    isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND) -> NavigationSuiteType.NavigationRail
    else -> NavigationSuiteType.ShortNavigationBarCompact
}

private val PillItemSize = 56.dp
private val PillItemWidth = 76.dp
private val PillItemSpacing = 4.dp
private val PillHorizontalPadding = 6.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppAdaptiveNavBar(
    selectedScreen: Screen,
    onNavigate: (Screen) -> Unit,
    windowSizeClass: WindowSizeClass,
    alpha: Float = 1f,
    modifier: Modifier = Modifier,
) {
    val enabled = alpha > 0.99f
    when (windowSizeClass.navigationSuiteType()) {
        NavigationSuiteType.ShortNavigationBarCompact -> {
            Box(
                modifier = modifier.fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                    .padding(horizontal = 20.dp, vertical = 10.dp)
                    .graphicsLayer { this.alpha = alpha },
                contentAlignment = Alignment.Center,
            ) {
                ExpressivePillNavigationBar(
                    selectedScreen = selectedScreen,
                    onNavigate = onNavigate,
                    enabled = enabled,
                    modifier = Modifier.testTag("navigation-compact"),
                )
            }
        }
        NavigationSuiteType.NavigationRail -> {
            NavigationRail(modifier = modifier.testTag("navigation-medium").graphicsLayer { this.alpha = alpha }) {
                appDestinations.forEach { item ->
                    NavigationRailItem(
                        selected = selectedScreen == item.screen,
                        onClick = { onNavigate(item.screen) },
                        enabled = enabled,
                        icon = { Icon(item.icon, contentDescription = null) },
                        label = { Text(stringResource(item.labelRes)) },
                    )
                }
            }
        }
        else -> {
            PermanentDrawerSheet(
                modifier = modifier.width(240.dp).testTag("navigation-expanded").graphicsLayer { this.alpha = alpha },
            ) {
                appDestinations.forEach { item ->
                    NavigationDrawerItem(
                        label = { Text(stringResource(item.labelRes)) },
                        selected = selectedScreen == item.screen,
                        onClick = { if (enabled) onNavigate(item.screen) },
                        icon = { Icon(item.icon, contentDescription = null) },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

/**
 * 悬浮胶囊导航栏，参照 mpvRx 的 ExpressivePillNavigationBar：
 * - 胶囊形 Surface（细边框 + 模糊背景）悬浮于底部，居中对齐，无投影；
 * - 滑动指示器为 [PillItemSize] 高的 primaryContainer 圆角块，等宽地滑过各 tab；
 * - 每个 item 等宽，图标在上、文字在下且始终显示；
 *   布局恒定因此图标位置从不跳变（切换中间 tab 的 fraction 0->1->0 也是纯渐变）。
 */
@Composable
private fun ExpressivePillNavigationBar(
    selectedScreen: Screen,
    onNavigate: (Screen) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val position = appDestinations.indexOfFirst { it.screen == selectedScreen }.coerceAtLeast(0)
    val animatedPosition by animateFloatAsState(
        targetValue = position.toFloat(),
        animationSpec = tween(200, easing = YumeTransitionEasing),
        label = "pill-position",
    )
    val indicatorLeft = PillHorizontalPadding + (PillItemWidth + PillItemSpacing) * animatedPosition

    Surface(
        modifier = modifier,
        shape = CircleShape,
        // blurBehindWindow was removed from Compose 1.12; a translucent surface over the
        // bottom fade approximates the frosted look instead.
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.88f),
        tonalElevation = 6.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
        ),
    ) {
        Box(
            modifier = Modifier
                .wrapContentWidth()
                .padding(horizontal = PillHorizontalPadding, vertical = 6.dp),
        ) {
            // 滑动指示器，绘制在 items 之下。
            Box(
                modifier = Modifier
                    .offset(x = indicatorLeft - PillHorizontalPadding)
                    .width(PillItemWidth)
                    .height(PillItemSize)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
            )
            Row(
                modifier = Modifier.selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(PillItemSpacing, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                appDestinations.forEachIndexed { index, item ->
                    key(item.screen) {
                        val fraction = (1f - abs(animatedPosition - index)).coerceIn(0f, 1f)
                        val label = stringResource(item.labelRes)
                        val contentColor = lerp(
                            start = MaterialTheme.colorScheme.onSurfaceVariant,
                            stop = MaterialTheme.colorScheme.onPrimaryContainer,
                            fraction = fraction,
                        )
                        Box(
                            modifier = Modifier
                                .width(PillItemWidth)
                                .height(PillItemSize)
                                .clip(CircleShape)
                                .selectable(
                                    selected = item.screen == selectedScreen,
                                    onClick = { onNavigate(item.screen) },
                                    enabled = enabled,
                                    role = Role.Tab,
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = ripple(bounded = true),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Icon(
                                    imageVector = item.icon,
                                    contentDescription = label,
                                    tint = contentColor,
                                    modifier = Modifier.size(22.dp),
                                )
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = contentColor,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Clip,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private val CompactFloatingBarGradientHeight = 100.dp
private const val CompactFloatingBarGradientAlpha = 0.45f

@Composable
fun AppAdaptiveNavigationContainer(
    selectedScreen: Screen,
    onNavigate: (Screen) -> Unit,
    windowSizeClass: WindowSizeClass,
    modifier: Modifier = Modifier,
    barAlpha: Float = 1f,
    content: @Composable BoxScope.() -> Unit,
) {
    val type = windowSizeClass.navigationSuiteType()
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (type == NavigationSuiteType.ShortNavigationBarCompact) {
            // Floating bar: content runs edge-to-edge and a translucent vertical fade sits
            // behind the pill, replacing the old reserved bottom band so the bar reads as
            // truly floating instead of leaving a white strip.
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .consumeWindowInsets(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)),
                ) {
                    content()
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(CompactFloatingBarGradientHeight)
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                colorStops = arrayOf(
                                    0f to Color.Transparent,
                                    1f to MaterialTheme.colorScheme.background.copy(alpha = CompactFloatingBarGradientAlpha),
                                ),
                            ),
                        ),
                )
                AppAdaptiveNavBar(
                    selectedScreen = selectedScreen,
                    onNavigate = onNavigate,
                    windowSizeClass = windowSizeClass,
                    alpha = barAlpha,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        } else {
            // Navigation Suite keeps the rail on the left and the drawer sheet on the side.
            // One layout/composition keeps the active destination alive when the window crosses a breakpoint.
            NavigationSuiteScaffoldLayout(
                navigationSuiteType = type,
                navigationSuite = {
                    AppAdaptiveNavBar(selectedScreen, onNavigate, windowSizeClass, alpha = barAlpha)
                },
            ) {
                Box(
                    modifier = Modifier.fillMaxSize().consumeWindowInsets(WindowInsets(0, 0, 0, 0)),
                    content = content,
                )
            }
        }
    }
}

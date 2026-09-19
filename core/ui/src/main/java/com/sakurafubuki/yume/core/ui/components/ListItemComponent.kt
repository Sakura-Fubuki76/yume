package com.sakurafubuki.yume.core.ui.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun NextSegmentedListItem(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    isFirstItem: Boolean = false,
    isLastItem: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    colors: ListItemColors = ListItemDefaults.segmentedColors(),
    shapes: ListItemShapes = ListItemDefaults.shapes(),
    leadingContent: @Composable (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null,
    overlineContent: @Composable (() -> Unit)? = null,
    supportingContent: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit,
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    interactionSource: MutableInteractionSource? = null,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val focused by source.collectIsFocusedAsState()
    val hovered by source.collectIsHoveredAsState()
    val overrideShape = MaterialTheme.shapes.large
    val baseShape = when {
        pressed && enabled -> shapes.pressedShape
        selected -> shapes.selectedShape
        focused && enabled -> shapes.focusedShape
        hovered && enabled -> shapes.hoveredShape
        else -> shapes.shape
    }
    val shape = remember(isFirstItem, isLastItem, baseShape, overrideShape) {
        if (baseShape is CornerBasedShape) {
            baseShape.copy(
                topStart = overrideShape.topStart.takeIf { isFirstItem } ?: baseShape.topStart,
                topEnd = overrideShape.topEnd.takeIf { isFirstItem } ?: baseShape.topEnd,
                bottomStart = overrideShape.bottomStart.takeIf { isLastItem } ?: baseShape.bottomStart,
                bottomEnd = overrideShape.bottomEnd.takeIf { isLastItem } ?: baseShape.bottomEnd,
            )
        } else {
            baseShape
        }
    }
    // InteractiveListItemMeasurePolicy queries child baselines during lazy premeasurement.
    // In Compose 1.12 beta this can place text before its parent enters RectList. A regular
    // Row/Column measures without requesting alignment lines and keeps prefetch enabled.
    Surface(
        modifier = modifier.semantics { this.selected = selected },
        shape = shape,
        color = colors.containerColor(enabled, selected, false),
        contentColor = colors.contentColor(enabled, selected, false),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    interactionSource = source,
                    indication = ripple(),
                    enabled = enabled,
                    onClick = onClick,
                    onLongClick = onLongClick,
                )
                .heightIn(min = 56.dp)
                .padding(contentPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leadingContent?.let {
                CompositionLocalProvider(LocalContentColor provides colors.leadingContentColor(enabled, selected, false)) {
                    Box { it() }
                }
                Spacer(Modifier.width(16.dp))
            }
            Column(Modifier.weight(1f)) {
                overlineContent?.let {
                    CompositionLocalProvider(LocalContentColor provides colors.overlineContentColor(enabled, selected, false)) {
                        ProvideTextStyle(MaterialTheme.typography.labelSmall, it)
                    }
                }
                ProvideTextStyle(MaterialTheme.typography.bodyLarge, content)
                supportingContent?.let {
                    CompositionLocalProvider(LocalContentColor provides colors.supportingContentColor(enabled, selected, false)) {
                        ProvideTextStyle(MaterialTheme.typography.bodyMedium, it)
                    }
                }
            }
            trailingContent?.let {
                Spacer(Modifier.width(16.dp))
                CompositionLocalProvider(LocalContentColor provides colors.trailingContentColor(enabled, selected, false)) {
                    ProvideTextStyle(MaterialTheme.typography.labelSmall) { Box { it() } }
                }
            }
        }
    }
}

@Composable
fun ListSectionTitle(
    modifier: Modifier = Modifier,
    text: String,
    contentPadding: PaddingValues = PaddingValues(
        start = 12.dp,
        top = 20.dp,
        bottom = 10.dp,
    ),
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Text(
        text = text,
        modifier = modifier.padding(contentPadding),
        color = color,
        style = MaterialTheme.typography.labelLarge,
    )
}

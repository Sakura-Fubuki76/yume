package com.sakurafubuki.yume.core.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.sakurafubuki.yume.core.ui.theme.YumeSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NextTopAppBar(
    modifier: Modifier = Modifier,
    title: @Composable () -> Unit,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    colors: TopAppBarColors = TopAppBarDefaults.topAppBarColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        titleContentColor = MaterialTheme.colorScheme.primary,
    ),
) {
    TopAppBar(
        title = {
            CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)) {
                title()
            }
        },
        navigationIcon = navigationIcon,
        actions = actions,
        colors = colors,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = YumeSpacing.componentGap),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NextTopAppBar(
    modifier: Modifier = Modifier,
    title: String,
    fontWeight: FontWeight? = null,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    colors: TopAppBarColors = TopAppBarDefaults.topAppBarColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        titleContentColor = MaterialTheme.colorScheme.primary,
    ),
) {
    NextTopAppBar(
        title = {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = fontWeight ?: FontWeight.Bold,
            )
        },
        navigationIcon = navigationIcon,
        actions = actions,
        colors = colors,
        modifier = modifier,
    )
}

package com.sakurafubuki.yume.settings.screens.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.util.withContext
import com.sakurafubuki.yume.core.ui.FloatingNavigationClearance
import com.sakurafubuki.yume.core.ui.R
import com.sakurafubuki.yume.core.ui.components.NextSegmentedListItem
import com.sakurafubuki.yume.core.ui.components.NextTopAppBar
import com.sakurafubuki.yume.core.ui.designsystem.NextIcons

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LibrariesScreen(
    onNavigateUp: () -> Unit,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    Scaffold(
        topBar = {
            NextTopAppBar(
                title = stringResource(id = R.string.libraries),
                navigationIcon = {
                    FilledTonalIconButton(onClick = onNavigateUp) {
                        Icon(
                            imageVector = NextIcons.ArrowBack,
                            contentDescription = stringResource(id = R.string.navigate_up),
                        )
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) { innerPadding ->
        val libs = remember { Libs.Builder().withContext(context).build() }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(start = 16.dp, end = 16.dp, bottom = FloatingNavigationClearance),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        ) {
            item(key = "app_usage") {
                NextSegmentedListItem(
                    content = {
                        Text(text = stringResource(R.string.app_usage_title))
                    },
                    supportingContent = {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(text = stringResource(R.string.app_usage_description))
                            Text(text = stringResource(R.string.app_usage_stack))
                        }
                    },
                    isFirstItem = true,
                    isLastItem = libs.libraries.isEmpty(),
                )
            }
            itemsIndexed(libs.libraries, key = { _, library -> library.uniqueId }) { index, library ->
                NextSegmentedListItem(
                    content = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = library.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            library.artifactVersion?.let {
                                Text(text = it)
                            }
                        }
                    },
                    supportingContent = {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = library.developers.takeIf { it.isNotEmpty() }
                                    ?.mapNotNull { it.name }
                                    ?.joinToString(", ")
                                    ?: library.organization?.name ?: "",
                            )
                            FlowRow(
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                library.licenses.forEach {
                                    Badge(
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    ) {
                                        Text(text = it.name, modifier = Modifier.padding(horizontal = 2.dp))
                                    }
                                }
                            }
                        }
                    },
                    isFirstItem = false,
                    isLastItem = index == libs.libraries.lastIndex,
                    onClick = {
                        library.website?.takeIf { it.isNotBlank() }?.let {
                            uriHandler.openUriOrShowToast(uri = it, context = context)
                        }
                    },
                )
            }
        }
    }
}

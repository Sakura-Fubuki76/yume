package com.sakurafubuki.yume.settings.screens.general

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sakurafubuki.yume.core.ui.FloatingNavigationClearance
import com.sakurafubuki.yume.core.ui.R
import com.sakurafubuki.yume.core.ui.components.CancelButton
import com.sakurafubuki.yume.core.ui.components.ClickablePreferenceItem
import com.sakurafubuki.yume.core.ui.components.ListSectionTitle
import com.sakurafubuki.yume.core.ui.components.NextDialog
import com.sakurafubuki.yume.core.ui.components.NextTopAppBar
import com.sakurafubuki.yume.core.ui.designsystem.NextIcons

@Composable
fun GeneralPreferencesScreen(
    onNavigateUp: () -> Unit,
    viewModel: GeneralPreferencesViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    GeneralPreferencesContent(
        uiState = uiState,
        onEvent = viewModel::onEvent,
        onNavigateUp = onNavigateUp,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun GeneralPreferencesContent(
    uiState: GeneralPreferencesUiState,
    onEvent: (GeneralPreferencesUiEvent) -> Unit,
    onNavigateUp: () -> Unit,
) {
    Scaffold(
        topBar = {
            NextTopAppBar(
                title = stringResource(id = R.string.general_name),
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(state = rememberScrollState())
                .padding(innerPadding)
                .padding(start = 16.dp, end = 16.dp, bottom = FloatingNavigationClearance),
        ) {
            ListSectionTitle(text = stringResource(id = R.string.user_data))
            Column(
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                ClickablePreferenceItem(
                    title = stringResource(R.string.delete_thumbnail_cache),
                    description = stringResource(R.string.delete_thumbnail_cache_description),
                    icon = NextIcons.Image,
                    onClick = { onEvent(GeneralPreferencesUiEvent.ShowDialog(GeneralPreferencesDialog.ClearThumbnailCacheDialog)) },
                    isFirstItem = true,
                    isLastItem = false,
                )
                ClickablePreferenceItem(
                    title = stringResource(R.string.reset_settings),
                    description = stringResource(R.string.reset_settings_description),
                    icon = NextIcons.History,
                    onClick = { onEvent(GeneralPreferencesUiEvent.ShowDialog(GeneralPreferencesDialog.ResetSettingsDialog)) },
                    isFirstItem = false,
                    isLastItem = true,
                )
            }
        }

        uiState.showDialog?.let { dialog ->
            when (dialog) {
                GeneralPreferencesDialog.ResetSettingsDialog -> {
                    NextDialog(
                        onDismissRequest = { onEvent(GeneralPreferencesUiEvent.ShowDialog(null)) },
                        title = {
                            Text(
                                text = stringResource(R.string.reset_settings),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    onEvent(GeneralPreferencesUiEvent.ResetSettings)
                                    onEvent(GeneralPreferencesUiEvent.ShowDialog(null))
                                },
                            ) {
                                Text(text = stringResource(R.string.reset))
                            }
                        },
                        dismissButton = { CancelButton(onClick = { onEvent(GeneralPreferencesUiEvent.ShowDialog(null)) }) },
                        content = {
                            Text(
                                text = stringResource(R.string.reset_settings_confirmation),
                                style = MaterialTheme.typography.titleSmall,
                            )
                        },
                    )
                }
                GeneralPreferencesDialog.ClearThumbnailCacheDialog -> {
                    NextDialog(
                        onDismissRequest = { onEvent(GeneralPreferencesUiEvent.ShowDialog(null)) },
                        title = { Text(text = stringResource(R.string.delete_thumbnail_cache)) },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    onEvent(GeneralPreferencesUiEvent.ClearThumbnailCache)
                                    onEvent(GeneralPreferencesUiEvent.ShowDialog(null))
                                },
                            ) {
                                Text(text = stringResource(R.string.clear_cache))
                            }
                        },
                        dismissButton = {
                            CancelButton(onClick = { onEvent(GeneralPreferencesUiEvent.ShowDialog(null)) })
                        },
                        content = {
                            Text(
                                text = stringResource(R.string.delete_thumbnail_cache_confirmation),
                                style = MaterialTheme.typography.titleSmall,
                            )
                        },
                    )
                }
            }
        }
    }
}

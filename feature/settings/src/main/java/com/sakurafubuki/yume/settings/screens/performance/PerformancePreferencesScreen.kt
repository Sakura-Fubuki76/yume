package com.sakurafubuki.yume.settings.screens.performance

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sakurafubuki.yume.core.model.ApplicationPreferences
import com.sakurafubuki.yume.core.model.CacheExpiry
import com.sakurafubuki.yume.core.ui.R
import com.sakurafubuki.yume.core.ui.components.CancelButton
import com.sakurafubuki.yume.core.ui.components.ClickablePreferenceItem
import com.sakurafubuki.yume.core.ui.components.ListSectionTitle
import com.sakurafubuki.yume.core.ui.components.NextDialog
import com.sakurafubuki.yume.core.ui.components.NextTopAppBar
import com.sakurafubuki.yume.core.ui.components.PreferenceSlider
import com.sakurafubuki.yume.core.ui.designsystem.NextIcons
import com.sakurafubuki.yume.settings.screens.medialibrary.MediaLibraryPreferencesUiEvent
import com.sakurafubuki.yume.settings.screens.medialibrary.MediaLibraryPreferencesUiState
import com.sakurafubuki.yume.settings.screens.medialibrary.MediaLibraryPreferencesViewModel

@Composable
fun PerformancePreferencesScreen(
    onNavigateUp: () -> Unit,
    viewModel: MediaLibraryPreferencesViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    PerformancePreferencesContent(
        uiState = uiState,
        onNavigateUp = onNavigateUp,
        onEvent = viewModel::onEvent,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PerformancePreferencesContent(
    uiState: MediaLibraryPreferencesUiState,
    onNavigateUp: () -> Unit,
    onEvent: (MediaLibraryPreferencesUiEvent) -> Unit,
) {
    var pendingCustomValueInput by remember { mutableStateOf<CustomValueInputDialogState?>(null) }

    Scaffold(
        topBar = {
            NextTopAppBar(
                title = stringResource(id = R.string.performance),
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
                .padding(horizontal = 16.dp),
        ) {
            val imageCacheSizeTitle = stringResource(R.string.image_cache_size)
            val imageBrowserMemoryCacheSizeTitle = stringResource(R.string.image_browser_memory_cache_size)
            val imageBrowserThumbnailSizeTitle = stringResource(R.string.image_browser_thumbnail_size)
            val imageBrowserPreloadRangeTitle = stringResource(R.string.image_browser_preload_range)
            val imageBrowserPreloadPageCountTitle = stringResource(R.string.image_browser_preload_page_count)
            val streamingMinBufferTitle = stringResource(R.string.streaming_min_buffer)
            val streamingMaxBufferTitle = stringResource(R.string.streaming_max_buffer)
            val streamingBufferForPlaybackTitle = stringResource(R.string.streaming_buffer_for_playback)
            val streamingBufferAfterRebufferTitle = stringResource(R.string.streaming_buffer_after_rebuffer)
            val imageCacheWarning = uiState.imageCacheSizeMb > 0 &&
                uiState.currentImageCacheUsageMb * 10 >= uiState.imageCacheSizeMb * 9L
            ListSectionTitle(text = stringResource(id = R.string.image_browsing_experience))
            Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                PreferenceSlider(
                    title = imageBrowserMemoryCacheSizeTitle,
                    description = stringResource(R.string.cache_size_ram_percent, uiState.imageBrowserMemoryCachePercent),
                    icon = NextIcons.Speed,
                    value = uiState.imageBrowserMemoryCachePercent.toFloat(),
                    valueRange = ApplicationPreferences.MIN_IMAGE_BROWSER_MEMORY_CACHE_PERCENT.toFloat()..ApplicationPreferences.MAX_IMAGE_BROWSER_MEMORY_CACHE_PERCENT.toFloat(),
                    steps = (ApplicationPreferences.MAX_IMAGE_BROWSER_MEMORY_CACHE_PERCENT - ApplicationPreferences.MIN_IMAGE_BROWSER_MEMORY_CACHE_PERCENT) / 5 - 1,
                    onValueChange = {
                        onEvent(MediaLibraryPreferencesUiEvent.UpdateImageBrowserMemoryCachePercent(it.toInt()))
                    },
                    trailingContent = {
                        SliderCustomInputButton {
                            pendingCustomValueInput = CustomValueInputDialogState(
                                title = imageBrowserMemoryCacheSizeTitle,
                                initialValue = uiState.imageBrowserMemoryCachePercent,
                                minValue = ApplicationPreferences.MIN_IMAGE_BROWSER_MEMORY_CACHE_PERCENT,
                                maxValue = ApplicationPreferences.MAX_IMAGE_BROWSER_MEMORY_CACHE_PERCENT,
                                onConfirm = {
                                    onEvent(MediaLibraryPreferencesUiEvent.UpdateImageBrowserMemoryCachePercent(it))
                                },
                            )
                        }
                    },
                    isFirstItem = true,
                    isLastItem = false,
                )
                ImageBrowserThumbnailSizePicker(
                    title = imageBrowserThumbnailSizeTitle,
                    currentSizePx = uiState.imageBrowserThumbnailSizePx,
                    onSizeSelected = { onEvent(MediaLibraryPreferencesUiEvent.UpdateImageBrowserThumbnailSizePx(it)) },
                    isLastItem = false,
                )
                PreferenceSlider(
                    title = imageBrowserPreloadRangeTitle,
                    description = stringResource(R.string.image_browser_preload_range_desc, uiState.imageBrowserPreloadRange),
                    icon = NextIcons.Update,
                    value = uiState.imageBrowserPreloadRange.toFloat(),
                    valueRange = ApplicationPreferences.MIN_IMAGE_BROWSER_PRELOAD_RANGE.toFloat()..ApplicationPreferences.MAX_IMAGE_BROWSER_PRELOAD_RANGE.toFloat(),
                    onValueChange = {
                        onEvent(MediaLibraryPreferencesUiEvent.UpdateImageBrowserPreloadRange(it.toInt()))
                    },
                    trailingContent = {
                        SliderCustomInputButton {
                            pendingCustomValueInput = CustomValueInputDialogState(
                                title = imageBrowserPreloadRangeTitle,
                                initialValue = uiState.imageBrowserPreloadRange,
                                minValue = ApplicationPreferences.MIN_IMAGE_BROWSER_PRELOAD_RANGE,
                                maxValue = ApplicationPreferences.MAX_IMAGE_BROWSER_PRELOAD_RANGE,
                                onConfirm = {
                                    onEvent(MediaLibraryPreferencesUiEvent.UpdateImageBrowserPreloadRange(it))
                                },
                            )
                        }
                    },
                    isFirstItem = false,
                    isLastItem = false,
                )
                PreferenceSlider(
                    title = imageBrowserPreloadPageCountTitle,
                    description = stringResource(R.string.image_browser_preload_page_count_desc, uiState.imageBrowserPreloadPageCount),
                    icon = NextIcons.Update,
                    value = uiState.imageBrowserPreloadPageCount.toFloat(),
                    valueRange = ApplicationPreferences.MIN_IMAGE_BROWSER_PRELOAD_PAGE_COUNT.toFloat()..ApplicationPreferences.MAX_IMAGE_BROWSER_PRELOAD_PAGE_COUNT.toFloat(),
                    onValueChange = {
                        onEvent(MediaLibraryPreferencesUiEvent.UpdateImageBrowserPreloadPageCount(it.toInt()))
                    },
                    trailingContent = {
                        SliderCustomInputButton {
                            pendingCustomValueInput = CustomValueInputDialogState(
                                title = imageBrowserPreloadPageCountTitle,
                                initialValue = uiState.imageBrowserPreloadPageCount,
                                minValue = ApplicationPreferences.MIN_IMAGE_BROWSER_PRELOAD_PAGE_COUNT,
                                maxValue = ApplicationPreferences.MAX_IMAGE_BROWSER_PRELOAD_PAGE_COUNT,
                                onConfirm = {
                                    onEvent(MediaLibraryPreferencesUiEvent.UpdateImageBrowserPreloadPageCount(it))
                                },
                            )
                        }
                    },
                    isFirstItem = false,
                    isLastItem = true,
                )
            }

            ListSectionTitle(text = stringResource(id = R.string.image_cache))
            Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                PreferenceSlider(
                    title = imageCacheSizeTitle,
                    description = stringResource(R.string.cache_size_gb, uiState.imageCacheSizeMb / 1024),
                    icon = NextIcons.Image,
                    value = uiState.imageCacheSizeMb.toFloat(),
                    valueRange = ApplicationPreferences.MIN_DISK_CACHE_SIZE_MB.toFloat()..ApplicationPreferences.MAX_DISK_CACHE_SLIDER_MB.toFloat(),
                    steps = (ApplicationPreferences.MAX_DISK_CACHE_SLIDER_MB - ApplicationPreferences.MIN_DISK_CACHE_SIZE_MB) / 1024 - 1,
                    onValueChange = { onEvent(MediaLibraryPreferencesUiEvent.UpdateImageCacheSize(it.toInt())) },
                    trailingContent = {
                        SliderCustomInputButton {
                            pendingCustomValueInput = CustomValueInputDialogState(
                                title = imageCacheSizeTitle,
                                initialValue = uiState.imageCacheSizeMb,
                                minValue = ApplicationPreferences.MIN_DISK_CACHE_SIZE_MB,
                                maxValue = ApplicationPreferences.MAX_DISK_CACHE_SLIDER_MB,
                                onConfirm = { onEvent(MediaLibraryPreferencesUiEvent.UpdateImageCacheSize(it)) },
                            )
                        }
                    },
                    isFirstItem = true,
                    isLastItem = false,
                )
                ClickablePreferenceItem(
                    title = stringResource(R.string.clear_image_cache),
                    description = stringResource(R.string.current_cache_usage, uiState.currentImageCacheUsageMb),
                    icon = NextIcons.DeleteSweep,
                    onClick = { onEvent(MediaLibraryPreferencesUiEvent.ClearImageCache) },
                    isFirstItem = false,
                    isLastItem = false,
                )
                CloudImageCacheExpiryPicker(
                    currentExpiry = uiState.imageCacheExpiry,
                    onExpirySelected = { onEvent(MediaLibraryPreferencesUiEvent.UpdateImageCacheExpiry(it)) },
                    isLastItem = true,
                )
            }
            if (imageCacheWarning) {
                Text(
                    text = stringResource(R.string.cache_usage_warning),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                )
            }

            ListSectionTitle(text = stringResource(id = R.string.streaming_buffer))
            Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                PreferenceSlider(
                    title = streamingMinBufferTitle,
                    description = stringResource(R.string.buffer_ms_value, uiState.streamingMinBufferMs),
                    icon = NextIcons.Fast,
                    value = uiState.streamingMinBufferMs.toFloat(),
                    valueRange = ApplicationPreferences.MIN_STREAMING_MIN_BUFFER_MS.toFloat()..ApplicationPreferences.MAX_STREAMING_MIN_BUFFER_MS.toFloat(),
                    onValueChange = { onEvent(MediaLibraryPreferencesUiEvent.UpdateStreamingMinBufferMs(it.toInt())) },
                    trailingContent = {
                        SliderCustomInputButton {
                            pendingCustomValueInput = CustomValueInputDialogState(
                                title = streamingMinBufferTitle,
                                initialValue = uiState.streamingMinBufferMs,
                                minValue = ApplicationPreferences.MIN_STREAMING_MIN_BUFFER_MS,
                                maxValue = ApplicationPreferences.MAX_STREAMING_MIN_BUFFER_MS,
                                onConfirm = { onEvent(MediaLibraryPreferencesUiEvent.UpdateStreamingMinBufferMs(it)) },
                            )
                        }
                    },
                    isFirstItem = true,
                    isLastItem = false,
                )
                PreferenceSlider(
                    title = streamingMaxBufferTitle,
                    description = stringResource(R.string.buffer_ms_value, uiState.streamingMaxBufferMs),
                    icon = NextIcons.GraphicEq,
                    value = uiState.streamingMaxBufferMs.toFloat(),
                    valueRange = ApplicationPreferences.MIN_STREAMING_MAX_BUFFER_MS.toFloat()..ApplicationPreferences.MAX_STREAMING_MAX_BUFFER_MS.toFloat(),
                    onValueChange = { onEvent(MediaLibraryPreferencesUiEvent.UpdateStreamingMaxBufferMs(it.toInt())) },
                    trailingContent = {
                        SliderCustomInputButton {
                            pendingCustomValueInput = CustomValueInputDialogState(
                                title = streamingMaxBufferTitle,
                                initialValue = uiState.streamingMaxBufferMs,
                                minValue = ApplicationPreferences.MIN_STREAMING_MAX_BUFFER_MS,
                                maxValue = ApplicationPreferences.MAX_STREAMING_MAX_BUFFER_MS,
                                onConfirm = { onEvent(MediaLibraryPreferencesUiEvent.UpdateStreamingMaxBufferMs(it)) },
                            )
                        }
                    },
                    isFirstItem = false,
                    isLastItem = false,
                )
                PreferenceSlider(
                    title = streamingBufferForPlaybackTitle,
                    description = stringResource(R.string.buffer_ms_value, uiState.streamingBufferForPlaybackMs),
                    icon = NextIcons.Play,
                    value = uiState.streamingBufferForPlaybackMs.toFloat(),
                    valueRange = ApplicationPreferences.MIN_STREAMING_BUFFER_FOR_PLAYBACK_MS.toFloat()..ApplicationPreferences.MAX_STREAMING_BUFFER_FOR_PLAYBACK_MS.toFloat(),
                    onValueChange = { onEvent(MediaLibraryPreferencesUiEvent.UpdateStreamingBufferForPlaybackMs(it.toInt())) },
                    trailingContent = {
                        SliderCustomInputButton {
                            pendingCustomValueInput = CustomValueInputDialogState(
                                title = streamingBufferForPlaybackTitle,
                                initialValue = uiState.streamingBufferForPlaybackMs,
                                minValue = ApplicationPreferences.MIN_STREAMING_BUFFER_FOR_PLAYBACK_MS,
                                maxValue = ApplicationPreferences.MAX_STREAMING_BUFFER_FOR_PLAYBACK_MS,
                                onConfirm = {
                                    onEvent(MediaLibraryPreferencesUiEvent.UpdateStreamingBufferForPlaybackMs(it))
                                },
                            )
                        }
                    },
                    isFirstItem = false,
                    isLastItem = false,
                )
                PreferenceSlider(
                    title = streamingBufferAfterRebufferTitle,
                    description = stringResource(R.string.buffer_ms_value, uiState.streamingBufferForPlaybackAfterRebufferMs),
                    icon = NextIcons.Resume,
                    value = uiState.streamingBufferForPlaybackAfterRebufferMs.toFloat(),
                    valueRange = ApplicationPreferences.MIN_STREAMING_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS.toFloat()..ApplicationPreferences.MAX_STREAMING_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS.toFloat(),
                    onValueChange = {
                        onEvent(
                            MediaLibraryPreferencesUiEvent.UpdateStreamingBufferForPlaybackAfterRebufferMs(
                                it.toInt(),
                            ),
                        )
                    },
                    trailingContent = {
                        SliderCustomInputButton {
                            pendingCustomValueInput = CustomValueInputDialogState(
                                title = streamingBufferAfterRebufferTitle,
                                initialValue = uiState.streamingBufferForPlaybackAfterRebufferMs,
                                minValue = ApplicationPreferences.MIN_STREAMING_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
                                maxValue = ApplicationPreferences.MAX_STREAMING_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
                                onConfirm = {
                                    onEvent(
                                        MediaLibraryPreferencesUiEvent.UpdateStreamingBufferForPlaybackAfterRebufferMs(it),
                                    )
                                },
                            )
                        }
                    },
                    isFirstItem = false,
                    isLastItem = true,
                )
            }

            pendingCustomValueInput?.let { dialogState ->
                CustomValueInputDialog(
                    state = dialogState,
                    onDismiss = { pendingCustomValueInput = null },
                    onConfirm = { value ->
                        dialogState.onConfirm(value)
                        pendingCustomValueInput = null
                    },
                )
            }
        }
    }
}

@Composable
private fun ImageBrowserThumbnailSizePicker(
    title: String,
    currentSizePx: Int,
    onSizeSelected: (Int) -> Unit,
    isLastItem: Boolean,
) {
    var showDialog by remember { mutableStateOf(false) }
    val description = if (currentSizePx == ApplicationPreferences.IMAGE_BROWSER_THUMBNAIL_SIZE_ORIGINAL) {
        stringResource(R.string.image_browser_thumbnail_size_original)
    } else {
        stringResource(R.string.image_browser_thumbnail_size_desc, currentSizePx)
    }

    ClickablePreferenceItem(
        title = title,
        description = description,
        icon = NextIcons.Image,
        onClick = { showDialog = true },
        isFirstItem = false,
        isLastItem = isLastItem,
    )

    if (showDialog) {
        NextDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(text = title) },
            confirmButton = {},
            dismissButton = {
                CancelButton(onClick = { showDialog = false })
            },
            content = {
                Column {
                    ApplicationPreferences.IMAGE_BROWSER_THUMBNAIL_SIZE_OPTIONS.forEach { sizePx ->
                        val label = if (sizePx == ApplicationPreferences.IMAGE_BROWSER_THUMBNAIL_SIZE_ORIGINAL) {
                            stringResource(R.string.image_browser_thumbnail_size_original)
                        } else {
                            stringResource(R.string.image_browser_thumbnail_size_desc, sizePx)
                        }
                        Text(
                            text = label,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSizeSelected(sizePx)
                                    showDialog = false
                                }
                                .padding(vertical = 12.dp, horizontal = 4.dp),
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (sizePx == currentSizePx) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
            },
        )
    }
}

@Composable
private fun SliderCustomInputButton(
    onClick: () -> Unit,
) {
    FilledTonalIconButton(onClick = onClick) {
        Icon(
            imageVector = NextIcons.Edit,
            contentDescription = stringResource(R.string.custom_value_input_button),
        )
    }
}

@Composable
private fun CustomValueInputDialog(
    state: CustomValueInputDialogState,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    var valueText by remember(state) { mutableStateOf(state.initialValue.toString()) }
    val parsed = valueText.toIntOrNull()
    val isValid = parsed != null && parsed in state.minValue..state.maxValue

    LaunchedEffect(state) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    NextDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = state.title) },
        confirmButton = {
            TextButton(
                enabled = isValid,
                onClick = { parsed?.let(onConfirm) },
            ) {
                Text(text = stringResource(R.string.done))
            }
        },
        dismissButton = {
            CancelButton(onClick = onDismiss)
        },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = valueText,
                    onValueChange = { input ->
                        valueText = input.filter(Char::isDigit)
                    },
                    singleLine = true,
                    modifier = Modifier.focusRequester(focusRequester),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                    label = { Text(text = stringResource(R.string.custom_value_input_label)) },
                    supportingText = {
                        Text(
                            text = stringResource(
                                R.string.custom_value_input_range,
                                state.minValue,
                                state.maxValue,
                            ),
                        )
                    },
                    isError = valueText.isNotEmpty() && !isValid,
                )
            }
        },
    )
}

@Composable
private fun CacheExpiry.displayName(): String = when (this) {
    CacheExpiry.HOUR_1 -> stringResource(R.string.cache_expiry_1h)
    CacheExpiry.HOUR_6 -> stringResource(R.string.cache_expiry_6h)
    CacheExpiry.HOUR_12 -> stringResource(R.string.cache_expiry_12h)
    CacheExpiry.DAY_1 -> stringResource(R.string.cache_expiry_1d)
    CacheExpiry.DAY_3 -> stringResource(R.string.cache_expiry_3d)
    CacheExpiry.WEEK_1 -> stringResource(R.string.cache_expiry_1w)
    CacheExpiry.MONTH_1 -> stringResource(R.string.cache_expiry_1m)
    CacheExpiry.NEVER -> stringResource(R.string.cache_expiry_never)
}

@Composable
private fun CloudImageCacheExpiryPicker(
    currentExpiry: CacheExpiry,
    onExpirySelected: (CacheExpiry) -> Unit,
    isLastItem: Boolean,
) {
    var showDialog by remember { mutableStateOf(false) }

    ClickablePreferenceItem(
        title = stringResource(R.string.cache_expiry),
        description = currentExpiry.displayName(),
        icon = NextIcons.Timer,
        onClick = { showDialog = true },
        isFirstItem = false,
        isLastItem = isLastItem,
    )

    if (showDialog) {
        NextDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(text = stringResource(R.string.cache_expiry)) },
            confirmButton = {},
            dismissButton = {
                CancelButton(onClick = { showDialog = false })
            },
            content = {
                Column {
                    CacheExpiry.entries.forEach { expiry ->
                        Text(
                            text = expiry.displayName(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onExpirySelected(expiry)
                                    showDialog = false
                                }
                                .padding(vertical = 12.dp, horizontal = 4.dp),
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (expiry == currentExpiry) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
            },
        )
    }
}

private data class CustomValueInputDialogState(
    val title: String,
    val initialValue: Int,
    val minValue: Int,
    val maxValue: Int,
    val onConfirm: (Int) -> Unit,
)

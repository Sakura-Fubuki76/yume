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
import com.sakurafubuki.yume.core.ui.components.PreferenceSwitch
import com.sakurafubuki.yume.core.ui.designsystem.NextIcons
import com.sakurafubuki.yume.settings.screens.medialibrary.MediaLibraryPreferencesUiEvent
import com.sakurafubuki.yume.settings.screens.medialibrary.MediaLibraryPreferencesUiState
import com.sakurafubuki.yume.settings.screens.medialibrary.MediaLibraryPreferencesViewModel
import kotlin.math.roundToInt

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
            val imageBrowserThumbnailSizeTitle = stringResource(R.string.image_browser_thumbnail_size)
            val imageBrowserPreloadPageCountTitle = stringResource(R.string.image_browser_preload_page_count)
            val streamingMinBufferTitle = stringResource(R.string.streaming_min_buffer)
            val streamingMaxBufferTitle = stringResource(R.string.streaming_max_buffer)
            val streamingBufferForPlaybackTitle = stringResource(R.string.streaming_buffer_for_playback)
            val streamingBufferAfterRebufferTitle = stringResource(R.string.streaming_buffer_after_rebuffer)
            val imageCacheWarning = uiState.imageCacheSizeMb > 0 &&
                uiState.currentImageCacheUsageMb * 10 >= uiState.imageCacheSizeMb * 9L
            ListSectionTitle(text = stringResource(id = R.string.image_browsing_experience))
            Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                ImageBrowserThumbnailSizePicker(
                    title = imageBrowserThumbnailSizeTitle,
                    currentSizePx = uiState.imageBrowserThumbnailSizePx,
                    onSizeSelected = { onEvent(MediaLibraryPreferencesUiEvent.UpdateImageBrowserThumbnailSizePx(it)) },
                    isFirstItem = true,
                    isLastItem = false,
                )
                PreferenceSlider(
                    title = imageBrowserPreloadPageCountTitle,
                    description = stringResource(R.string.image_browser_preload_page_count_desc, uiState.imageBrowserPreloadPageCount),
                    icon = NextIcons.Update,
                    value = uiState.imageBrowserPreloadPageCount.toFloat(),
                    valueRange = ApplicationPreferences.MIN_IMAGE_BROWSER_PRELOAD_PAGE_COUNT.toFloat()..ApplicationPreferences.MAX_IMAGE_BROWSER_PRELOAD_PAGE_COUNT.toFloat(),
                    steps = discreteSliderSteps(
                        minValue = ApplicationPreferences.MIN_IMAGE_BROWSER_PRELOAD_PAGE_COUNT,
                        maxValue = ApplicationPreferences.MAX_IMAGE_BROWSER_PRELOAD_PAGE_COUNT,
                        stepSize = PRELOAD_PAGE_STEP,
                    ),
                    onValueChange = {
                        onEvent(
                            MediaLibraryPreferencesUiEvent.UpdateImageBrowserPreloadPageCount(
                                snapSliderValue(
                                    value = it,
                                    minValue = ApplicationPreferences.MIN_IMAGE_BROWSER_PRELOAD_PAGE_COUNT,
                                    maxValue = ApplicationPreferences.MAX_IMAGE_BROWSER_PRELOAD_PAGE_COUNT,
                                    stepSize = PRELOAD_PAGE_STEP,
                                ),
                            ),
                        )
                    },
                    onClick = {
                        pendingCustomValueInput = CustomValueInputDialogState(
                            title = imageBrowserPreloadPageCountTitle,
                            initialValue = uiState.imageBrowserPreloadPageCount,
                            minValue = ApplicationPreferences.MIN_IMAGE_BROWSER_PRELOAD_PAGE_COUNT,
                            maxValue = ApplicationPreferences.MAX_IMAGE_BROWSER_PRELOAD_PAGE_COUNT,
                            stepSize = PRELOAD_PAGE_STEP,
                            onConfirm = {
                                onEvent(MediaLibraryPreferencesUiEvent.UpdateImageBrowserPreloadPageCount(it))
                            },
                        )
                    },
                    isFirstItem = false,
                    isLastItem = true,
                )
            }

            ListSectionTitle(text = stringResource(id = R.string.image_cache))
            Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                PreferenceSwitch(
                    title = stringResource(R.string.enable_cloud_image_cache),
                    description = stringResource(R.string.enable_cloud_image_cache_desc),
                    icon = NextIcons.Image,
                    isChecked = uiState.imageCloudDiskCacheEnabled,
                    onClick = { onEvent(MediaLibraryPreferencesUiEvent.ToggleImageCloudDiskCache) },
                    isFirstItem = true,
                    isLastItem = false,
                )
                PreferenceSlider(
                    title = imageCacheSizeTitle,
                    description = stringResource(R.string.cache_size_gb, uiState.imageCacheSizeMb / 1024),
                    icon = NextIcons.Image,
                    value = uiState.imageCacheSizeMb.toFloat(),
                    valueRange = ApplicationPreferences.MIN_DISK_CACHE_SIZE_MB.toFloat()..ApplicationPreferences.MAX_DISK_CACHE_SLIDER_MB.toFloat(),
                    steps = discreteSliderSteps(
                        minValue = ApplicationPreferences.MIN_DISK_CACHE_SIZE_MB,
                        maxValue = ApplicationPreferences.MAX_DISK_CACHE_SLIDER_MB,
                        stepSize = DISK_CACHE_STEP_MB,
                    ),
                    onValueChange = {
                        onEvent(
                            MediaLibraryPreferencesUiEvent.UpdateImageCacheSize(
                                snapSliderValue(
                                    value = it,
                                    minValue = ApplicationPreferences.MIN_DISK_CACHE_SIZE_MB,
                                    maxValue = ApplicationPreferences.MAX_DISK_CACHE_SLIDER_MB,
                                    stepSize = DISK_CACHE_STEP_MB,
                                ),
                            ),
                        )
                    },
                    onClick = {
                        pendingCustomValueInput = CustomValueInputDialogState(
                            title = imageCacheSizeTitle,
                            initialValue = uiState.imageCacheSizeMb,
                            minValue = ApplicationPreferences.MIN_DISK_CACHE_SIZE_MB,
                            maxValue = ApplicationPreferences.MAX_DISK_CACHE_SLIDER_MB,
                            stepSize = DISK_CACHE_STEP_MB,
                            onConfirm = { onEvent(MediaLibraryPreferencesUiEvent.UpdateImageCacheSize(it)) },
                        )
                    },
                    isFirstItem = false,
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
                    steps = discreteSliderSteps(
                        minValue = ApplicationPreferences.MIN_STREAMING_MIN_BUFFER_MS,
                        maxValue = ApplicationPreferences.MAX_STREAMING_MIN_BUFFER_MS,
                        stepSize = STREAMING_LARGE_BUFFER_STEP_MS,
                    ),
                    onValueChange = {
                        onEvent(
                            MediaLibraryPreferencesUiEvent.UpdateStreamingMinBufferMs(
                                snapSliderValue(
                                    value = it,
                                    minValue = ApplicationPreferences.MIN_STREAMING_MIN_BUFFER_MS,
                                    maxValue = ApplicationPreferences.MAX_STREAMING_MIN_BUFFER_MS,
                                    stepSize = STREAMING_LARGE_BUFFER_STEP_MS,
                                ),
                            ),
                        )
                    },
                    onClick = {
                        pendingCustomValueInput = CustomValueInputDialogState(
                            title = streamingMinBufferTitle,
                            initialValue = uiState.streamingMinBufferMs,
                            minValue = ApplicationPreferences.MIN_STREAMING_MIN_BUFFER_MS,
                            maxValue = ApplicationPreferences.MAX_STREAMING_MIN_BUFFER_MS,
                            stepSize = STREAMING_LARGE_BUFFER_STEP_MS,
                            onConfirm = { onEvent(MediaLibraryPreferencesUiEvent.UpdateStreamingMinBufferMs(it)) },
                        )
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
                    steps = discreteSliderSteps(
                        minValue = ApplicationPreferences.MIN_STREAMING_MAX_BUFFER_MS,
                        maxValue = ApplicationPreferences.MAX_STREAMING_MAX_BUFFER_MS,
                        stepSize = STREAMING_LARGE_BUFFER_STEP_MS,
                    ),
                    onValueChange = {
                        onEvent(
                            MediaLibraryPreferencesUiEvent.UpdateStreamingMaxBufferMs(
                                snapSliderValue(
                                    value = it,
                                    minValue = ApplicationPreferences.MIN_STREAMING_MAX_BUFFER_MS,
                                    maxValue = ApplicationPreferences.MAX_STREAMING_MAX_BUFFER_MS,
                                    stepSize = STREAMING_LARGE_BUFFER_STEP_MS,
                                ),
                            ),
                        )
                    },
                    onClick = {
                        pendingCustomValueInput = CustomValueInputDialogState(
                            title = streamingMaxBufferTitle,
                            initialValue = uiState.streamingMaxBufferMs,
                            minValue = ApplicationPreferences.MIN_STREAMING_MAX_BUFFER_MS,
                            maxValue = ApplicationPreferences.MAX_STREAMING_MAX_BUFFER_MS,
                            stepSize = STREAMING_LARGE_BUFFER_STEP_MS,
                            onConfirm = { onEvent(MediaLibraryPreferencesUiEvent.UpdateStreamingMaxBufferMs(it)) },
                        )
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
                    steps = discreteSliderSteps(
                        minValue = ApplicationPreferences.MIN_STREAMING_BUFFER_FOR_PLAYBACK_MS,
                        maxValue = ApplicationPreferences.MAX_STREAMING_BUFFER_FOR_PLAYBACK_MS,
                        stepSize = STREAMING_SMALL_BUFFER_STEP_MS,
                    ),
                    onValueChange = {
                        onEvent(
                            MediaLibraryPreferencesUiEvent.UpdateStreamingBufferForPlaybackMs(
                                snapSliderValue(
                                    value = it,
                                    minValue = ApplicationPreferences.MIN_STREAMING_BUFFER_FOR_PLAYBACK_MS,
                                    maxValue = ApplicationPreferences.MAX_STREAMING_BUFFER_FOR_PLAYBACK_MS,
                                    stepSize = STREAMING_SMALL_BUFFER_STEP_MS,
                                ),
                            ),
                        )
                    },
                    onClick = {
                        pendingCustomValueInput = CustomValueInputDialogState(
                            title = streamingBufferForPlaybackTitle,
                            initialValue = uiState.streamingBufferForPlaybackMs,
                            minValue = ApplicationPreferences.MIN_STREAMING_BUFFER_FOR_PLAYBACK_MS,
                            maxValue = ApplicationPreferences.MAX_STREAMING_BUFFER_FOR_PLAYBACK_MS,
                            stepSize = STREAMING_SMALL_BUFFER_STEP_MS,
                            onConfirm = {
                                onEvent(MediaLibraryPreferencesUiEvent.UpdateStreamingBufferForPlaybackMs(it))
                            },
                        )
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
                    steps = discreteSliderSteps(
                        minValue = ApplicationPreferences.MIN_STREAMING_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
                        maxValue = ApplicationPreferences.MAX_STREAMING_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
                        stepSize = STREAMING_SMALL_BUFFER_STEP_MS,
                    ),
                    onValueChange = {
                        onEvent(
                            MediaLibraryPreferencesUiEvent.UpdateStreamingBufferForPlaybackAfterRebufferMs(
                                snapSliderValue(
                                    value = it,
                                    minValue = ApplicationPreferences.MIN_STREAMING_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
                                    maxValue = ApplicationPreferences.MAX_STREAMING_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
                                    stepSize = STREAMING_SMALL_BUFFER_STEP_MS,
                                ),
                            ),
                        )
                    },
                    onClick = {
                        pendingCustomValueInput = CustomValueInputDialogState(
                            title = streamingBufferAfterRebufferTitle,
                            initialValue = uiState.streamingBufferForPlaybackAfterRebufferMs,
                            minValue = ApplicationPreferences.MIN_STREAMING_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
                            maxValue = ApplicationPreferences.MAX_STREAMING_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
                            stepSize = STREAMING_SMALL_BUFFER_STEP_MS,
                            onConfirm = {
                                onEvent(
                                    MediaLibraryPreferencesUiEvent.UpdateStreamingBufferForPlaybackAfterRebufferMs(it),
                                )
                            },
                        )
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
    isFirstItem: Boolean,
    isLastItem: Boolean,
) {
    var showDialog by remember { mutableStateOf(false) }
    val normalizedSizePx = ApplicationPreferences.normalizeImageBrowserThumbnailSizePx(currentSizePx)
    val description = if (normalizedSizePx == ApplicationPreferences.IMAGE_BROWSER_THUMBNAIL_SIZE_ORIGINAL) {
        stringResource(R.string.image_browser_thumbnail_size_original)
    } else {
        stringResource(R.string.image_browser_thumbnail_size_desc, normalizedSizePx)
    }

    ClickablePreferenceItem(
        title = title,
        description = description,
        icon = NextIcons.Image,
        onClick = { showDialog = true },
        isFirstItem = isFirstItem,
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
                            color = if (sizePx == normalizedSizePx) {
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
private fun CustomValueInputDialog(
    state: CustomValueInputDialogState,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    var valueText by remember(state) { mutableStateOf(state.initialValue.toString()) }
    val parsed = valueText.toIntOrNull()
    val isStepAligned = parsed != null && (parsed - state.minValue) % state.stepSize == 0
    val isValid = parsed != null && parsed in state.minValue..state.maxValue && isStepAligned

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
                            text = if (state.stepSize > 1) {
                                stringResource(
                                    R.string.custom_value_input_range_with_step,
                                    state.minValue,
                                    state.maxValue,
                                    state.stepSize,
                                )
                            } else {
                                stringResource(
                                    R.string.custom_value_input_range,
                                    state.minValue,
                                    state.maxValue,
                                )
                            },
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
    val stepSize: Int,
    val onConfirm: (Int) -> Unit,
)

private const val PRELOAD_PAGE_STEP = 1
private const val DISK_CACHE_STEP_MB = 1024
private const val STREAMING_LARGE_BUFFER_STEP_MS = 5_000
private const val STREAMING_SMALL_BUFFER_STEP_MS = 500

private fun discreteSliderSteps(minValue: Int, maxValue: Int, stepSize: Int): Int = ((maxValue - minValue) / stepSize - 1).coerceAtLeast(0)

private fun snapSliderValue(value: Float, minValue: Int, maxValue: Int, stepSize: Int): Int {
    val clamped = value.roundToInt().coerceIn(minValue, maxValue)
    val offset = clamped - minValue
    val snappedOffset = (offset.toFloat() / stepSize).roundToInt() * stepSize
    return (minValue + snappedOffset).coerceIn(minValue, maxValue)
}

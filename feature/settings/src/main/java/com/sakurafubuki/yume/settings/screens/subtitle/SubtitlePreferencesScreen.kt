package com.sakurafubuki.yume.settings.screens.subtitle

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sakurafubuki.yume.core.model.PlayerPreferences
import com.sakurafubuki.yume.core.ui.FloatingNavigationClearance
import com.sakurafubuki.yume.core.ui.R
import com.sakurafubuki.yume.core.ui.components.ClickablePreferenceItem
import com.sakurafubuki.yume.core.ui.components.ListSectionTitle
import com.sakurafubuki.yume.core.ui.components.NextTopAppBar
import com.sakurafubuki.yume.core.ui.components.PreferenceSlider
import com.sakurafubuki.yume.core.ui.components.PreferenceSwitch
import com.sakurafubuki.yume.core.ui.components.PreferenceSwitchWithDivider
import com.sakurafubuki.yume.core.ui.components.RadioTextButton
import com.sakurafubuki.yume.core.ui.designsystem.NextIcons
import com.sakurafubuki.yume.core.ui.theme.YumeTheme
import com.sakurafubuki.yume.settings.composables.OptionsDialog
import com.sakurafubuki.yume.settings.utils.LocalesHelper
import java.nio.charset.Charset

@Composable
fun SubtitlePreferencesScreen(
    onNavigateUp: () -> Unit,
    viewModel: SubtitlePreferencesViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    SubtitlePreferencesContent(
        uiState = uiState,
        onEvent = viewModel::onEvent,
        onNavigateUp = onNavigateUp,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SubtitlePreferencesContent(
    uiState: SubtitlePreferencesUiState,
    onEvent: (SubtitlePreferencesUiEvent) -> Unit,
    onNavigateUp: () -> Unit,
) {
    val languages = remember { listOf(Pair("None", "")) + LocalesHelper.getAvailableLocales() }
    val charsetResource = stringArrayResource(id = R.array.charsets_list)
    val context = LocalContext.current

    val fontsDirLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            onEvent(SubtitlePreferencesUiEvent.UpdateCustomFontsDirectory(uri.toString()))
        }
    }

    val defaultFontsDirDesc = stringResource(R.string.fonts_directory_desc)
    val fontsDirDisplay = remember(uiState.preferences.customFontsDirectory, defaultFontsDirDesc) {
        val raw = uiState.preferences.customFontsDirectory
        if (raw.isBlank()) {
            defaultFontsDirDesc
        } else {
            try {
                val treeUri = android.net.Uri.parse(raw)
                val docId = android.provider.DocumentsContract.getTreeDocumentId(treeUri)

                docId.substringAfterLast(':').ifBlank { defaultFontsDirDesc }
            } catch (_: Exception) {
                defaultFontsDirDesc
            }
        }
    }

    Scaffold(
        topBar = {
            NextTopAppBar(
                title = stringResource(id = R.string.subtitle),
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
            ListSectionTitle(text = stringResource(id = R.string.playback))
            Column(
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                ClickablePreferenceItem(
                    title = stringResource(id = R.string.preferred_subtitle_lang),
                    description = LocalesHelper.getLocaleDisplayLanguage(uiState.preferences.preferredSubtitleLanguage)
                        .takeIf { it.isNotBlank() } ?: stringResource(R.string.preferred_subtitle_lang_description),
                    icon = NextIcons.Language,
                    onClick = { onEvent(SubtitlePreferencesUiEvent.ShowDialog(SubtitlePreferenceDialog.SubtitleLanguageDialog)) },
                    isFirstItem = true,
                )
                ClickablePreferenceItem(
                    title = stringResource(R.string.subtitle_text_encoding),
                    description = charsetResource.first { it.contains(uiState.preferences.subtitleTextEncoding) },
                    icon = NextIcons.Subtitle,
                    onClick = { onEvent(SubtitlePreferencesUiEvent.ShowDialog(SubtitlePreferenceDialog.SubtitleEncodingDialog)) },
                    isLastItem = true,
                )
            }
            ListSectionTitle(text = stringResource(id = R.string.appearance_name))
            Column(
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                PreferenceSwitchWithDivider(
                    title = stringResource(R.string.system_caption_style),
                    description = stringResource(R.string.system_caption_style_desc),
                    icon = NextIcons.Caption,
                    isChecked = uiState.preferences.useSystemCaptionStyle,
                    onChecked = { onEvent(SubtitlePreferencesUiEvent.ToggleUseSystemCaptionStyle) },
                    onClick = { context.startActivity(Intent(Settings.ACTION_CAPTIONING_SETTINGS)) },
                    isFirstItem = true,
                )
                PreferenceSlider(
                    title = stringResource(id = R.string.subtitle_text_size),
                    description = uiState.preferences.subtitleTextSize.toString(),
                    icon = NextIcons.FontSize,
                    enabled = uiState.preferences.useSystemCaptionStyle.not(),
                    value = uiState.preferences.subtitleTextSize.toFloat(),
                    valueRange = 10f..60f,
                    onValueChange = { onEvent(SubtitlePreferencesUiEvent.UpdateSubtitleFontSize(it.toInt())) },
                    trailingContent = {
                        FilledIconButton(
                            enabled = uiState.preferences.useSystemCaptionStyle.not(),
                            onClick = {
                                onEvent(SubtitlePreferencesUiEvent.UpdateSubtitleFontSize(PlayerPreferences.DEFAULT_SUBTITLE_TEXT_SIZE))
                            },
                        ) {
                            Icon(
                                imageVector = NextIcons.History,
                                contentDescription = stringResource(id = R.string.reset_seek_increment),
                            )
                        }
                    },
                )
                PreferenceSwitch(
                    title = stringResource(id = R.string.subtitle_background),
                    description = stringResource(id = R.string.subtitle_background_desc),
                    icon = NextIcons.Background,
                    enabled = uiState.preferences.useSystemCaptionStyle.not(),
                    isChecked = uiState.preferences.subtitleBackground,
                    onClick = { onEvent(SubtitlePreferencesUiEvent.ToggleSubtitleBackground) },
                )
                PreferenceSwitch(
                    title = stringResource(R.string.embedded_styles),
                    description = stringResource(R.string.embedded_styles_desc),
                    icon = NextIcons.Style,
                    isChecked = uiState.preferences.applyEmbeddedStyles,
                    onClick = { onEvent(SubtitlePreferencesUiEvent.ToggleApplyEmbeddedStyles) },
                )
                ClickablePreferenceItem(
                    title = stringResource(R.string.fonts_directory),
                    description = fontsDirDisplay,
                    icon = NextIcons.Folder,
                    onClick = { fontsDirLauncher.launch(null) },
                    isLastItem = true,
                )
            }
        }

        uiState.showDialog?.let { showDialog ->
            when (showDialog) {
                SubtitlePreferenceDialog.SubtitleLanguageDialog -> {
                    OptionsDialog(
                        text = stringResource(id = R.string.preferred_subtitle_lang),
                        onDismissClick = { onEvent(SubtitlePreferencesUiEvent.ShowDialog(null)) },
                    ) {
                        items(languages) {
                            RadioTextButton(
                                text = it.first,
                                selected = it.second == uiState.preferences.preferredSubtitleLanguage,
                                onClick = {
                                    onEvent(SubtitlePreferencesUiEvent.UpdateSubtitleLanguage(it.second))
                                    onEvent(SubtitlePreferencesUiEvent.ShowDialog(null))
                                },
                            )
                        }
                    }
                }

                SubtitlePreferenceDialog.SubtitleEncodingDialog -> {
                    OptionsDialog(
                        text = stringResource(id = R.string.subtitle_text_encoding),
                        onDismissClick = { onEvent(SubtitlePreferencesUiEvent.ShowDialog(null)) },
                    ) {
                        items(charsetResource) {
                            val currentCharset = it.substringAfterLast("(", "").removeSuffix(")")
                            if (currentCharset.isEmpty() || Charset.isSupported(currentCharset)) {
                                RadioTextButton(
                                    text = it,
                                    selected = currentCharset == uiState.preferences.subtitleTextEncoding,
                                    onClick = {
                                        onEvent(SubtitlePreferencesUiEvent.UpdateSubtitleEncoding(currentCharset))
                                        onEvent(SubtitlePreferencesUiEvent.ShowDialog(null))
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@PreviewLightDark
@Composable
private fun SubtitlePreferencesScreenPreview() {
    YumeTheme {
        SubtitlePreferencesContent(
            uiState = SubtitlePreferencesUiState(),
            onEvent = {},
            onNavigateUp = {},
        )
    }
}

package com.sakurafubuki.yume.settings.screens.medialibrary

import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.accompanist.permissions.shouldShowRationale
import com.sakurafubuki.yume.core.common.webDavPermissions
import com.sakurafubuki.yume.core.model.ThumbnailGenerationStrategy
import com.sakurafubuki.yume.core.model.WebDavServer
import com.sakurafubuki.yume.core.ui.R
import com.sakurafubuki.yume.core.ui.components.ClickablePreferenceItem
import com.sakurafubuki.yume.core.ui.components.ListSectionTitle
import com.sakurafubuki.yume.core.ui.components.NextTopAppBar
import com.sakurafubuki.yume.core.ui.components.PreferenceSwitch
import com.sakurafubuki.yume.core.ui.composables.PermissionMissingView
import com.sakurafubuki.yume.core.ui.designsystem.NextIcons
import com.sakurafubuki.yume.core.ui.theme.YumeTheme

@Composable
fun MediaLibraryPreferencesScreen(
    onNavigateUp: () -> Unit,
    onFolderSettingClick: () -> Unit = {},
    onThumbnailSettingClick: () -> Unit = {},
    viewModel: MediaLibraryPreferencesViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    MediaLibraryPreferencesContent(
        uiState = uiState,
        onNavigateUp = onNavigateUp,
        onFolderSettingClick = onFolderSettingClick,
        onThumbnailSettingClick = onThumbnailSettingClick,
        onEvent = viewModel::onEvent,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalPermissionsApi::class)
@Composable
private fun MediaLibraryPreferencesContent(
    uiState: MediaLibraryPreferencesUiState,
    onNavigateUp: () -> Unit,
    onFolderSettingClick: () -> Unit,
    onThumbnailSettingClick: () -> Unit,
    onEvent: (MediaLibraryPreferencesUiEvent) -> Unit,
) {
    val preferences = uiState.preferences
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var showAddImageHostingDialog by rememberSaveable { mutableStateOf(false) }
    var editingServerId by rememberSaveable { mutableStateOf<Int?>(null) }
    val editingServer = editingServerId?.let { id -> uiState.servers.firstOrNull { it.id == id } }

    val webDavPermissionState = rememberMultiplePermissionsState(webDavPermissions)
    val hasWebDavPermission = if (Build.VERSION.SDK_INT >= 36) {
        webDavPermissionState.permissions.any { it.status.isGranted }
    } else {
        true
    }

    Scaffold(
        topBar = {
            NextTopAppBar(
                title = stringResource(id = R.string.media_library),
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
            ListSectionTitle(text = stringResource(id = R.string.media_library))
            Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                PreferenceSwitch(
                    title = stringResource(id = R.string.mark_last_played_media),
                    description = stringResource(id = R.string.mark_last_played_media_desc),
                    icon = NextIcons.Check,
                    isChecked = preferences.markLastPlayedMedia,
                    onClick = { onEvent(MediaLibraryPreferencesUiEvent.ToggleMarkLastPlayedMedia) },
                    isFirstItem = true,
                    isLastItem = true,
                )
            }

            ListSectionTitle(text = stringResource(id = R.string.scan))
            Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                ClickablePreferenceItem(
                    title = stringResource(id = R.string.manage_folders),
                    description = stringResource(id = R.string.manage_folders_desc),
                    icon = NextIcons.FolderOff,
                    onClick = onFolderSettingClick,
                    isFirstItem = true,
                    isLastItem = true,
                )
            }

            ListSectionTitle(text = stringResource(id = R.string.thumbnail))
            Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                ClickablePreferenceItem(
                    title = stringResource(id = R.string.thumbnail_generation),
                    description = when (preferences.thumbnailGenerationStrategy) {
                        ThumbnailGenerationStrategy.FIRST_FRAME -> stringResource(id = R.string.first_frame)
                        ThumbnailGenerationStrategy.FRAME_AT_PERCENTAGE -> stringResource(R.string.frame_at_position)
                        ThumbnailGenerationStrategy.HYBRID -> stringResource(id = R.string.hybrid)
                    },
                    icon = NextIcons.Image,
                    onClick = onThumbnailSettingClick,
                    isFirstItem = true,
                    isLastItem = true,
                )
            }

            ListSectionTitle(text = stringResource(id = R.string.webdav_servers))
            if (Build.VERSION.SDK_INT >= 36) {
                PermissionMissingView(
                    isGranted = hasWebDavPermission,
                    showRationale = webDavPermissionState.permissions.any { it.status.shouldShowRationale },
                    permissions = listOf(stringResource(R.string.local_network)),
                    launchPermissionRequest = { webDavPermissionState.launchMultiplePermissionRequest() },
                ) {
                    WebDavServerList(
                        servers = uiState.servers,
                        onEditServer = { editingServerId = it.id },
                        onDeleteServer = { onEvent(MediaLibraryPreferencesUiEvent.DeleteWebDavServer(it)) },
                        onAddServer = { showAddDialog = true },
                        onAddImageHosting = { showAddImageHostingDialog = true },
                    )
                }
            } else {
                WebDavServerList(
                    servers = uiState.servers,
                    onEditServer = { editingServerId = it.id },
                    onDeleteServer = { onEvent(MediaLibraryPreferencesUiEvent.DeleteWebDavServer(it)) },
                    onAddServer = { showAddDialog = true },
                    onAddImageHosting = { showAddImageHostingDialog = true },
                )
            }
        }
    }

    if (showAddDialog) {
        AddWebDavDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = {
                onEvent(MediaLibraryPreferencesUiEvent.AddWebDavServer(it))
                showAddDialog = false
            },
        )
    }

    if (editingServer != null) {
        if (editingServer.isImageHosting) {
            AddImageHostingDialog(
                initialServer = editingServer,
                onDismiss = { editingServerId = null },
                onConfirm = {
                    onEvent(MediaLibraryPreferencesUiEvent.AddWebDavServer(it))
                    editingServerId = null
                },
            )
        } else {
            AddWebDavDialog(
                initialServer = editingServer,
                onDismiss = { editingServerId = null },
                onConfirm = {
                    onEvent(MediaLibraryPreferencesUiEvent.AddWebDavServer(it))
                    editingServerId = null
                },
            )
        }
    }

    if (showAddImageHostingDialog) {
        AddImageHostingDialog(
            onDismiss = { showAddImageHostingDialog = false },
            onConfirm = {
                onEvent(MediaLibraryPreferencesUiEvent.AddWebDavServer(it))
                showAddImageHostingDialog = false
            },
        )
    }
}

private fun formatWebDavUrlForDisplay(url: String): String = runCatching { Uri.decode(url) }.getOrDefault(url)

@Composable
private fun AddWebDavDialog(
    initialServer: WebDavServer? = null,
    onConfirm: (WebDavServer) -> Unit,
    onDismiss: () -> Unit,
) {
    val initialUri = remember(initialServer?.url) {
        initialServer?.url?.let { runCatching { it.toUri() }.getOrNull() }
    }
    var name by remember(initialServer?.id) { mutableStateOf(initialServer?.name.orEmpty()) }
    var scheme by remember(initialServer?.id) { mutableStateOf(initialUri?.scheme ?: "https") }
    var host by remember(initialServer?.id) { mutableStateOf(initialUri?.host.orEmpty()) }
    var port by remember(initialServer?.id) {
        mutableStateOf(initialUri?.port?.takeIf { it > 0 }?.toString().orEmpty())
    }
    var path by remember(initialServer?.id) {
        mutableStateOf(Uri.decode(initialUri?.encodedPath ?: "/dav").ifBlank { "/dav" })
    }
    var username by remember(initialServer?.id) { mutableStateOf(initialServer?.username.orEmpty()) }
    var password by remember(initialServer?.id) { mutableStateOf(initialServer?.password.orEmpty()) }

    val sanitizedHost = host.trim()
        .removePrefix("https://")
        .removePrefix("http://")
        .substringBefore('/')
        .trim()
    val normalizedPort = port.trim()
    val hasValidPort = normalizedPort.isEmpty() || normalizedPort.toIntOrNull()?.let { it in 1..65535 } == true

    val isBearerAuth = username.trim().startsWith("Bearer ", ignoreCase = true) ||
        username.trim().equals("bearer", ignoreCase = true)
    val canConfirm = name.isNotBlank() &&
        sanitizedHost.isNotBlank() &&
        hasValidPort &&
        (username.isNotBlank() || isBearerAuth) &&
        (password.isNotBlank() || isBearerAuth)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (initialServer == null) {
                    stringResource(R.string.add_webdav_storage)
                } else {
                    stringResource(R.string.edit_webdav_storage)
                },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.name)) },
                )
                OutlinedTextField(
                    value = scheme,
                    onValueChange = { scheme = it },
                    label = { Text(stringResource(R.string.webdav_scheme)) },
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text(stringResource(R.string.webdav_host)) },
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = { Text(stringResource(R.string.webdav_port)) },
                )
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    label = { Text(stringResource(R.string.webdav_path)) },
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(R.string.username)) },
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.password)) },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val normalizedScheme = if (scheme.trim().equals("http", ignoreCase = true)) "http" else "https"
                    val basePath = path.trim().ifBlank { "/dav" }
                    val pathSegments = basePath
                        .removePrefix("/")
                        .split('/')
                        .filter { it.isNotBlank() }
                    val authority = if (normalizedPort.isNotEmpty()) "$sanitizedHost:$normalizedPort" else sanitizedHost
                    val url = Uri.Builder().apply {
                        scheme(normalizedScheme)
                        encodedAuthority(authority)
                        pathSegments.forEach { appendPath(Uri.decode(it)) }
                    }.build().toString()

                    onConfirm(
                        WebDavServer(
                            id = initialServer?.id ?: 0,
                            name = name.trim(),
                            url = url,
                            username = username.trim(),
                            password = password,
                            isImageHosting = initialServer?.isImageHosting ?: false,
                            basePath = initialServer?.basePath ?: "/",
                            createdAt = initialServer?.createdAt ?: System.currentTimeMillis(),
                        ),
                    )
                },
                enabled = canConfirm,
            ) {
                Text(stringResource(R.string.done))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun AddImageHostingDialog(
    initialServer: WebDavServer? = null,
    onConfirm: (WebDavServer) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(initialServer?.id) {
        mutableStateOf(initialServer?.name.orEmpty())
    }
    var url by remember(initialServer?.id) {
        mutableStateOf(initialServer?.url ?: "")
    }
    var token by remember(initialServer?.id) {
        mutableStateOf(initialServer?.username?.removePrefix("Bearer ") ?: "")
    }

    val sanitizedUrl = url.trim()
    val parsedUrl = remember(sanitizedUrl) { runCatching { sanitizedUrl.toUri() }.getOrNull() }
    val validUrl = parsedUrl != null &&
        !parsedUrl.host.isNullOrBlank() &&
        (parsedUrl.scheme == "http" || parsedUrl.scheme == "https")
    val canConfirm = name.isNotBlank() && token.isNotBlank() && validUrl

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialServer == null) stringResource(R.string.add_image_hosting) else stringResource(R.string.edit_image_hosting)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.name)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.image_hosting_url)) },
                    placeholder = { Text(stringResource(R.string.image_hosting_url_placeholder)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text(stringResource(R.string.image_hosting_token)) },
                    placeholder = { Text(stringResource(R.string.image_hosting_token_placeholder)) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        WebDavServer(
                            id = initialServer?.id ?: 0,
                            name = name.trim(),
                            url = sanitizedUrl,
                            username = "Bearer $token".trim(),
                            password = "",
                            isImageHosting = true,
                            basePath = initialServer?.basePath ?: "/",
                            createdAt = initialServer?.createdAt ?: System.currentTimeMillis(),
                        ),
                    )
                },
                enabled = canConfirm,
            ) {
                Text(stringResource(R.string.done))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun WebDavServerList(
    servers: List<WebDavServer>,
    onEditServer: (WebDavServer) -> Unit,
    onDeleteServer: (WebDavServer) -> Unit,
    onAddServer: () -> Unit,
    onAddImageHosting: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        servers.forEachIndexed { index, server ->
            ClickablePreferenceItem(
                title = server.name,
                description = formatWebDavUrlForDisplay(server.url),
                icon = NextIcons.Cloud,
                onClick = { onEditServer(server) },
                trailingContent = {
                    IconButton(onClick = { onDeleteServer(server) }) {
                        Icon(
                            imageVector = NextIcons.Close,
                            contentDescription = stringResource(R.string.delete),
                        )
                    }
                },
                isFirstItem = index == 0,
                isLastItem = false,
            )
        }
        ClickablePreferenceItem(
            title = stringResource(R.string.add_webdav_storage),
            description = stringResource(R.string.add_webdav_storage_desc),
            icon = NextIcons.Add,
            onClick = onAddServer,
            onLongClick = onAddImageHosting,
            isFirstItem = servers.isEmpty(),
            isLastItem = true,
        )
    }
}

@PreviewLightDark
@Composable
private fun MediaLibraryPreferencesScreenPreview() {
    YumeTheme {
        MediaLibraryPreferencesContent(
            uiState = MediaLibraryPreferencesUiState(),
            onNavigateUp = {},
            onFolderSettingClick = {},
            onThumbnailSettingClick = {},
            onEvent = {},
        )
    }
}

package com.sakurafubuki.yume

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat.Type
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation3.runtime.rememberNavBackStack
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.sakurafubuki.yume.core.common.storagePermissions
import com.sakurafubuki.yume.core.media.services.MediaService
import com.sakurafubuki.yume.core.media.sync.MediaSynchronizer
import com.sakurafubuki.yume.core.model.ThemeConfig
import com.sakurafubuki.yume.core.ui.motion.LocalOverlayContentState
import com.sakurafubuki.yume.core.ui.motion.LocalSharedElementRegistry
import com.sakurafubuki.yume.core.ui.motion.LocalTransitionEngine
import com.sakurafubuki.yume.core.ui.motion.OverlayContentState
import com.sakurafubuki.yume.core.ui.motion.OverlayLayer
import com.sakurafubuki.yume.core.ui.motion.SharedElementRegistry
import com.sakurafubuki.yume.core.ui.motion.TransitionEngine
import com.sakurafubuki.yume.core.ui.theme.YumeTheme
import com.sakurafubuki.yume.feature.imagebrowser.ui.ImageViewerRoute
import com.sakurafubuki.yume.feature.imagebrowser.ui.ImageViewerStore
import com.sakurafubuki.yume.navigation.AppBottomNavBar
import com.sakurafubuki.yume.navigation.AppNavHost
import com.sakurafubuki.yume.navigation.Screen
import com.sakurafubuki.yume.navigation3.ImageBrowserKey
import com.sakurafubuki.yume.navigation3.MediaPickerKey
import com.sakurafubuki.yume.navigation3.SettingsHomeKey
import com.sakurafubuki.yume.navigation3.popOrFalse
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var synchronizer: MediaSynchronizer

    @Inject
    lateinit var mediaService: MediaService

    private val viewModel: MainViewModel by viewModels()

    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mediaService.initialize(this@MainActivity)

        var uiState: MainActivityUiState by mutableStateOf(MainActivityUiState.Loading)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    uiState = state
                }
            }
        }

        installSplashScreen().setKeepOnScreenCondition {
            when (uiState) {
                MainActivityUiState.Loading -> true
                is MainActivityUiState.Success -> false
            }
        }

        setContent {
            val shouldUseDarkTheme = shouldUseDarkTheme(uiState = uiState)

            YumeTheme(
                darkTheme = shouldUseDarkTheme,
                highContrastDarkTheme = shouldUseHighContrastDarkTheme(uiState = uiState),
                dynamicColor = shouldUseDynamicTheming(uiState = uiState),
            ) {
                LaunchedEffect(shouldUseDarkTheme) {
                    enableEdgeToEdge(
                        statusBarStyle = SystemBarStyle.auto(
                            lightScrim = Color.TRANSPARENT,
                            darkScrim = Color.TRANSPARENT,
                            detectDarkMode = { shouldUseDarkTheme },
                        ),
                        navigationBarStyle = SystemBarStyle.auto(
                            lightScrim = Color.TRANSPARENT,
                            darkScrim = Color.TRANSPARENT,
                            detectDarkMode = { shouldUseDarkTheme },
                        ),
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                ) {
                    val storagePermissionState = rememberMultiplePermissionsState(permissions = storagePermissions)

                    LifecycleEventEffect(event = Lifecycle.Event.ON_START) {
                        storagePermissionState.launchMultiplePermissionRequest()
                    }

                    val storageGranted = storagePermissionState.permissions.any { it.status.isGranted }

                    LaunchedEffect(key1 = storageGranted) {
                        if (storageGranted) {
                            synchronizer.startSync()
                        }
                    }

                    MainScreen(
                        onExitApp = { finish() },
                    )
                }
            }
        }
    }
}

@Composable
private fun MainScreen(
    onExitApp: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val pagerState = rememberPagerState(initialPage = 0) { 3 }
    val mediaBackStack = rememberNavBackStack(MediaPickerKey())
    val imageBackStack = rememberNavBackStack(ImageBrowserKey())
    val settingsBackStack = rememberNavBackStack(SettingsHomeKey)

    val selectedScreen = pageToScreen(pagerState.currentPage)
    val imageViewerShowing = selectedScreen == Screen.Image && ImageViewerStore.isViewerShowing
    val bottomBarVisible = !imageViewerShowing
    val tabSwipeEnabled = bottomBarVisible
    val imageViewerImmersiveStatusBar = imageViewerShowing

    val transitionEngine = remember { TransitionEngine() }
    val sharedElementRegistry = remember { SharedElementRegistry() }
    val overlayContentState = remember { OverlayContentState() }
    val configuration = LocalConfiguration.current
    var hasAppliedInitialConfiguration by remember { mutableStateOf(false) }

    LaunchedEffect(configuration) {
        if (!hasAppliedInitialConfiguration) {
            hasAppliedInitialConfiguration = true
            return@LaunchedEffect
        }
        sharedElementRegistry.clear()
        overlayContentState.clearAllContent()
        transitionEngine.finish()
    }

    DisposableEffect(context, imageViewerImmersiveStatusBar) {
        val activity = context.findActivity()
        if (activity != null) {
            activity.setStatusBarImmersive(hidden = imageViewerImmersiveStatusBar)
        }
        onDispose {
            if (imageViewerImmersiveStatusBar) {
                activity?.setStatusBarImmersive(hidden = false)
            }
        }
    }

    BackHandler {
        val popped = when (selectedScreen) {
            Screen.Video -> mediaBackStack.popOrFalse()
            Screen.Image -> {
                if (ImageViewerStore.isViewerShowing) {
                    ImageViewerStore.hideViewer()
                    true
                } else {
                    imageBackStack.popOrFalse()
                }
            }
            Screen.Settings -> settingsBackStack.popOrFalse()
        }
        if (popped) return@BackHandler

        onExitApp()
    }

    CompositionLocalProvider(
        LocalTransitionEngine provides transitionEngine,
        LocalSharedElementRegistry provides sharedElementRegistry,
        LocalOverlayContentState provides overlayContentState,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                bottomBar = {
                    val barAlpha = if (imageViewerShowing) 0f else 1f
                    val shouldRenderBar = bottomBarVisible || selectedScreen == Screen.Image
                    if (shouldRenderBar) {
                        Box(
                            modifier = Modifier
                                .graphicsLayer {
                                    alpha = barAlpha.coerceIn(0f, 1f)
                                },
                        ) {
                            AppBottomNavBar(
                                selectedScreen = selectedScreen,
                                onNavigate = { screen ->
                                    val targetPage = screenToPage(screen)
                                    if (targetPage != pagerState.currentPage) {
                                        scope.launch {
                                            pagerState.animateScrollToPage(targetPage)
                                        }
                                    }
                                },
                            )
                        }
                    }
                },
            ) { innerPadding ->
                AppNavHost(
                    context = context,
                    pagerState = pagerState,
                    mediaBackStack = mediaBackStack,
                    imageBackStack = imageBackStack,
                    settingsBackStack = settingsBackStack,
                    onNavigateToSettingsTab = {
                        scope.launch {
                            pagerState.animateScrollToPage(screenToPage(Screen.Settings))
                        }
                    },
                    userScrollEnabled = tabSwipeEnabled,
                    modifier = Modifier
                        .padding(innerPadding)
                        .fillMaxSize(),
                )
            }

            if (imageViewerShowing) {
                ImageViewerRoute(
                    initialIndex = ImageViewerStore.viewerIndex,
                    onBack = ImageViewerStore::hideViewer,
                )

                val overlayBottomBarAlpha = ImageViewerStore.bottomBarAlpha.coerceIn(0f, 1f)
                if (overlayBottomBarAlpha > 0.001f) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .graphicsLayer {
                                alpha = overlayBottomBarAlpha
                            },
                    ) {
                        AppBottomNavBar(
                            selectedScreen = selectedScreen,
                            onNavigate = {},
                        )
                    }
                }
            }

            OverlayLayer()
        }
    }
}

private fun pageToScreen(page: Int): Screen = when (page) {
    0 -> Screen.Video
    1 -> Screen.Image
    else -> Screen.Settings
}

private fun screenToPage(screen: Screen): Int = when (screen) {
    Screen.Video -> 0
    Screen.Image -> 1
    Screen.Settings -> 2
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun Activity.setStatusBarImmersive(hidden: Boolean) {
    WindowCompat.getInsetsController(window, window.decorView).apply {
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (hidden) {
            hide(Type.statusBars())
        } else {
            show(Type.statusBars())
        }
    }
}

@Composable
fun shouldUseDarkTheme(
    uiState: MainActivityUiState,
): Boolean = when (uiState) {
    MainActivityUiState.Loading -> isSystemInDarkTheme()
    is MainActivityUiState.Success -> when (uiState.preferences.themeConfig) {
        ThemeConfig.SYSTEM -> isSystemInDarkTheme()
        ThemeConfig.OFF -> false
        ThemeConfig.ON -> true
    }
}

@Composable
fun shouldUseHighContrastDarkTheme(
    uiState: MainActivityUiState,
): Boolean = when (uiState) {
    MainActivityUiState.Loading -> false
    is MainActivityUiState.Success -> uiState.preferences.useHighContrastDarkTheme
}

@Composable
fun shouldUseDynamicTheming(
    uiState: MainActivityUiState,
): Boolean = when (uiState) {
    MainActivityUiState.Loading -> false
    is MainActivityUiState.Success -> uiState.preferences.useDynamicColors
}

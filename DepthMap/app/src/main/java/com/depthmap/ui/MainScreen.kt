package com.depthmap.ui

import androidx.compose.animation.*
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.depthmap.data.models.ProcessingState
import com.depthmap.ui.screens.*
import com.depthmap.viewmodel.*

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    data object Home : Screen("home", "Home", Icons.Default.Home)
    data object Models : Screen("models", "Models", Icons.Default.Memory)
    data object Image : Screen("image", "Image", Icons.Default.Image)
    data object Video : Screen("video", "Video", Icons.Default.Videocam)
    data object Gallery : Screen("gallery", "Gallery", Icons.Default.Folder)
    data object Settings : Screen("settings", "Settings", Icons.Default.Settings)
    data object About : Screen("about", "About", Icons.Default.Info)
}

val bottomNavItems = listOf(
    Screen.Home,
    Screen.Models,
    Screen.Image,
    Screen.Video,
    Screen.Gallery
)

@Composable
fun MainScreen(
    isDarkMode: Boolean,
    homeViewModel: HomeViewModel,
    modelManagerViewModel: ModelManagerViewModel,
    imageProcessingViewModel: ImageProcessingViewModel,
    videoProcessingViewModel: VideoProcessingViewModel,
    galleryViewModel: GalleryViewModel,
    settingsViewModel: SettingsViewModel,
    onSelectImage: () -> Unit,
    onSelectVideo: () -> Unit,
    onShareFile: (String) -> Unit,
    onViewFile: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val showBottomBar = currentRoute in bottomNavItems.map { it.route }

    Scaffold(
        modifier = modifier,
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = NavigationBarDefaults.Elevation
                ) {
                    bottomNavItems.forEach { screen ->
                        val selected = currentRoute == screen.route
                        NavigationBarItem(
                            icon = { Icon(screen.icon, contentDescription = screen.title) },
                            label = { Text(screen.title) },
                            selected = selected,
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Home.route) {
                HomeScreen(
                    selectedModel = homeViewModel.selectedModel.collectAsState().value,
                    recentOutputs = homeViewModel.recentOutputs.collectAsState().value,
                    storageUsed = homeViewModel.storageUsed.collectAsState().value,
                    onNavigateToModels = { navController.navigate(Screen.Models.route) },
                    onNavigateToImage = { navController.navigate(Screen.Image.route) },
                    onNavigateToVideo = { navController.navigate(Screen.Video.route) },
                    onNavigateToGallery = { navController.navigate(Screen.Gallery.route) }
                )
            }

            composable(Screen.Models.route) {
                LaunchedEffect(Unit) {
                    modelManagerViewModel.refreshModels()
                }
                ModelManagerScreen(
                    models = modelManagerViewModel.models.collectAsState().value,
                    selectedModelId = modelManagerViewModel.selectedModelId.collectAsState().value,
                    storageUsed = modelManagerViewModel.storageUsed.collectAsState().value,
                    onSelectModel = { modelManagerViewModel.selectModel(it) },
                    onDownloadModel = { modelManagerViewModel.downloadModel(it) },
                    onDeleteModel = { modelManagerViewModel.deleteModel(it) },
                    onBack = { navController.popBackStack() }
                )
            }

            composable(Screen.Image.route) {
                val state by imageProcessingViewModel.processingState.collectAsState()
                LaunchedEffect(state) {
                    if (state is ProcessingState.Completed) {
                        // Gallery will refresh when navigated to
                    }
                }
                ImageProcessingScreen(
                    selectedModel = imageProcessingViewModel.selectedModel.collectAsState().value,
                    processingState = state,
                    selectedFileName = imageProcessingViewModel.selectedFileName.collectAsState().value,
                    onSelectImage = onSelectImage,
                    onProcess = { imageProcessingViewModel.processImage() },
                    onCancel = { imageProcessingViewModel.cancelProcessing() },
                    onShare = {
                        val s = state as? ProcessingState.Completed
                        s?.let { onShareFile(it.outputPath) }
                    },
                    onBack = { navController.popBackStack() }
                )
            }

            composable(Screen.Video.route) {
                val state by videoProcessingViewModel.processingState.collectAsState()
                VideoProcessingScreen(
                    selectedModel = videoProcessingViewModel.selectedModel.collectAsState().value,
                    processingState = state,
                    selectedFileName = videoProcessingViewModel.selectedFileName.collectAsState().value,
                    onSelectVideo = onSelectVideo,
                    onProcess = { videoProcessingViewModel.processVideo() },
                    onCancel = { videoProcessingViewModel.cancelProcessing() },
                    onShare = {
                        val s = state as? ProcessingState.Completed
                        s?.let { onShareFile(it.outputPath) }
                    },
                    onBack = { navController.popBackStack() }
                )
            }

            composable(Screen.Gallery.route) {
                LaunchedEffect(Unit) {
                    galleryViewModel.refresh()
                }
                GalleryScreen(
                    outputs = galleryViewModel.outputs.collectAsState().value,
                    onShare = { onShareFile(it.path) },
                    onDelete = { galleryViewModel.deleteOutput(it) },
                    onView = { onViewFile(it.path) },
                    onBack = { navController.popBackStack() }
                )
            }

            composable(Screen.Settings.route) {
                val termuxStatus = settingsViewModel.termuxStatus.collectAsState().value
                SettingsScreen(
                    isDarkMode = isDarkMode,
                    useNNAPI = settingsViewModel.useNNAPI.collectAsState().value,
                    useTermux = settingsViewModel.useTermux.collectAsState().value,
                    termuxStatus = termuxStatus?.message,
                    isTermuxInstalled = settingsViewModel.isTermuxInstalled,
                    onToggleDarkMode = { settingsViewModel.toggleDarkMode(it) },
                    onToggleNNAPI = { settingsViewModel.toggleNNAPI(it) },
                    onToggleTermux = { settingsViewModel.toggleTermux(it) },
                    onAbout = { navController.navigate(Screen.About.route) },
                    onBack = { navController.popBackStack() }
                )
            }

            composable(Screen.About.route) {
                AboutScreen(
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}

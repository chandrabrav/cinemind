package com.cinemind.app

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.cinemind.core.ui.components.AppBackground
import com.cinemind.core.ui.theme.CineMind
import com.cinemind.core.ui.theme.CineMindTheme
import com.cinemind.feature.ai.generation.ui.EditBriefScreen
import com.cinemind.feature.ai.llm.ui.AiModelsScreen
import com.cinemind.feature.editor.timeline.ui.TimelineScreen
import com.cinemind.feature.export.ui.ExportScreen
import com.cinemind.feature.media.ui.MediaLibraryScreen
import com.cinemind.feature.project.ui.ProjectListScreen
import com.cinemind.feature.settings.appearance.AppearanceRepository
import com.cinemind.feature.settings.ui.SettingsScreen
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

private object Routes {
    const val HOME = "home"
    const val MEDIA_LIBRARY = "media_library"
    const val EDITOR = "editor/{projectId}"
    const val BRIEF = "brief/{projectId}"
    const val EXPORT = "export/{projectId}"
    const val AI_MODELS = "ai_models"
    const val SETTINGS = "settings"
    const val ABOUT = "about"
    const val DIAGNOSTICS = "diagnostics"
    fun editor(projectId: String) = "editor/$projectId"
    fun brief(projectId: String) = "brief/$projectId"
    fun export(projectId: String) = "export/$projectId"
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var appearance: AppearanceRepository

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialMode = appearance.current()
        setContent {
            val mode by appearance.themeMode.collectAsState(initial = initialMode)
            val dark = mode.isDark(isSystemInDarkTheme())

            // Status/navigation bar icons follow the app's theme, not just the phone's.
            LaunchedEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT) { dark },
                )
            }

            CineMindTheme(mode = mode) {
                AppBackground {
                    val navController = rememberNavController()
                    val backStackEntry by navController.currentBackStackEntryAsState()
                    val route = backStackEntry?.destination?.route
                    val title = when (route) {
                        Routes.MEDIA_LIBRARY -> "Import media"
                        Routes.EDITOR -> "Editor"
                        Routes.BRIEF -> "Smart edit"
                        Routes.EXPORT -> "Export"
                        Routes.AI_MODELS -> "On-device AI"
                        Routes.SETTINGS -> ""
                        Routes.ABOUT -> "About"
                        Routes.DIAGNOSTICS -> "Diagnostics"
                        else -> com.cinemind.feature.settings.ui.Branding.APP_NAME
                    }

                    Scaffold(
                        containerColor = Color.Transparent,
                        topBar = {
                            TopAppBar(
                                title = {
                                    Text(
                                        text = title,
                                        style = if (route == Routes.HOME) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                                        color = CineMind.colors.textPrimary,
                                    )
                                },
                                navigationIcon = {
                                    if (route != null && route != Routes.HOME) {
                                        IconButton(onClick = { navController.navigateUp() }) {
                                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = CineMind.colors.textPrimary)
                                        }
                                    }
                                },
                                actions = {
                                    if (route == Routes.HOME) {
                                        IconButton(onClick = { navController.navigate(Routes.AI_MODELS) }) {
                                            Icon(Icons.Default.AutoAwesome, contentDescription = "On-device AI", tint = CineMind.colors.accent)
                                        }
                                        IconButton(onClick = { navController.navigate(Routes.SETTINGS) }) {
                                            Icon(Icons.Outlined.Settings, contentDescription = "Settings", tint = CineMind.colors.textPrimary)
                                        }
                                    }
                                },
                                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                            )
                        },
                    ) { paddingValues ->
                        CineMindNavHost(navController = navController, modifier = Modifier.padding(paddingValues))
                    }
                }
                // Brand moment on a cold start only (not on rotation or theme change); tap to skip.
                if (savedInstanceState == null) LaunchBrand()
            }
        }
    }
}

// Short, calm transitions: a small slide plus a fade, cheap enough for 120 Hz.
private const val ENTER_MS = 260
private const val EXIT_MS = 180

private fun AnimatedContentTransitionScope<NavBackStackEntry>.enter(): EnterTransition =
    fadeIn(tween(ENTER_MS, easing = FastOutSlowInEasing)) + slideInHorizontally(tween(ENTER_MS, easing = FastOutSlowInEasing)) { it / 12 }

private fun AnimatedContentTransitionScope<NavBackStackEntry>.exit(): ExitTransition = fadeOut(tween(EXIT_MS))

private fun AnimatedContentTransitionScope<NavBackStackEntry>.popEnter(): EnterTransition =
    fadeIn(tween(ENTER_MS, easing = FastOutSlowInEasing)) + slideInHorizontally(tween(ENTER_MS, easing = FastOutSlowInEasing)) { -it / 12 }

private fun AnimatedContentTransitionScope<NavBackStackEntry>.popExit(): ExitTransition =
    fadeOut(tween(EXIT_MS)) + slideOutHorizontally(tween(EXIT_MS)) { it / 12 }

@Composable
fun CineMindNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        modifier = modifier,
        enterTransition = { enter() },
        exitTransition = { exit() },
        popEnterTransition = { popEnter() },
        popExitTransition = { popExit() },
    ) {
        composable(Routes.HOME) {
            ProjectListScreen(
                onNewProject = { navController.navigate(Routes.MEDIA_LIBRARY) },
                onOpenProject = { id -> navController.navigate(Routes.editor(id)) },
            )
        }
        composable(Routes.MEDIA_LIBRARY) {
            MediaLibraryScreen(
                onProjectCreated = { projectId ->
                    // Replace the import screen so Back from the editor returns to the project list.
                    navController.navigate(Routes.editor(projectId)) {
                        popUpTo(Routes.HOME)
                    }
                },
            )
        }
        composable(Routes.EDITOR) { backStackEntry ->
            val projectId = backStackEntry.arguments?.getString("projectId").orEmpty()
            TimelineScreen(
                projectId = projectId,
                onOpenBrief = { navController.navigate(Routes.brief(projectId)) },
                onExport = { navController.navigate(Routes.export(projectId)) },
                onOpenAiModels = { navController.navigate(Routes.AI_MODELS) },
            )
        }
        composable(Routes.BRIEF) { backStackEntry ->
            EditBriefScreen(
                projectId = backStackEntry.arguments?.getString("projectId").orEmpty(),
                // Open the new edit; Back returns to the project list, where the original stays.
                onSmartEditCreated = { newId -> navController.navigate(Routes.editor(newId)) { popUpTo(Routes.HOME) } },
            )
        }
        composable(Routes.AI_MODELS) { AiModelsScreen() }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onOpenAiModels = { navController.navigate(Routes.AI_MODELS) },
                onOpenAbout = { navController.navigate(Routes.ABOUT) },
                onOpenDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) },
            )
        }
        composable(Routes.ABOUT) { com.cinemind.feature.settings.ui.AboutScreen() }
        composable(Routes.DIAGNOSTICS) { com.cinemind.feature.settings.ui.DiagnosticsScreen() }
        composable(Routes.EXPORT) { backStackEntry ->
            ExportScreen(projectId = backStackEntry.arguments?.getString("projectId").orEmpty())
        }
    }
}

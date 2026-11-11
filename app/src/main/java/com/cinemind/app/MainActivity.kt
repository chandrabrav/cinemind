package com.cinemind.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.cinemind.core.ui.theme.CineMindTheme
import com.cinemind.feature.ai.generation.ui.EditBriefScreen
import com.cinemind.feature.ai.llm.ui.AiModelsScreen
import com.cinemind.feature.editor.timeline.ui.TimelineScreen
import com.cinemind.feature.export.ui.ExportScreen
import com.cinemind.feature.media.ui.MediaLibraryScreen
import com.cinemind.feature.project.ui.ProjectListScreen
import dagger.hilt.android.AndroidEntryPoint

private object Routes {
    const val HOME = "home"
    const val MEDIA_LIBRARY = "media_library"
    const val EDITOR = "editor/{projectId}"
    const val BRIEF = "brief/{projectId}"
    const val EXPORT = "export/{projectId}"
    const val AI_MODELS = "ai_models"
    fun editor(projectId: String) = "editor/$projectId"
    fun brief(projectId: String) = "brief/$projectId"
    fun export(projectId: String) = "export/$projectId"
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            CineMindTheme {
                val navController = rememberNavController()
                val backStackEntry by navController.currentBackStackEntryAsState()
                val route = backStackEntry?.destination?.route
                val title = when (route) {
                    Routes.MEDIA_LIBRARY -> "Import media"
                    Routes.EDITOR -> "Editor"
                    Routes.BRIEF -> "Smart edit"
                    Routes.EXPORT -> "Export"
                    Routes.AI_MODELS -> "On-device AI"
                    else -> "CineMind AI"
                }

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(text = title) },
                            navigationIcon = {
                                if (route != null && route != Routes.HOME) {
                                    IconButton(onClick = { navController.navigateUp() }) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                                    }
                                }
                            },
                            actions = {
                                if (route == Routes.HOME) {
                                    IconButton(onClick = { navController.navigate(Routes.AI_MODELS) }) {
                                        Icon(Icons.Default.AutoAwesome, contentDescription = "On-device AI")
                                    }
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                            ),
                        )
                    },
                ) { paddingValues ->
                    CineMindNavHost(navController = navController, modifier = Modifier.padding(paddingValues))
                }
            }
        }
    }
}

@Composable
fun CineMindNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        modifier = modifier,
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
        composable(Routes.EXPORT) { backStackEntry ->
            ExportScreen(projectId = backStackEntry.arguments?.getString("projectId").orEmpty())
        }
    }
}

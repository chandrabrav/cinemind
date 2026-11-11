package com.cinemind.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.cinemind.core.ui.theme.CineMindTheme
import com.cinemind.feature.editor.timeline.ui.TimelineScreen
import com.cinemind.feature.media.ui.MediaLibraryScreen
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            CineMindTheme {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(text = "CineMind AI") },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                            ),
                        )
                    },
                ) { paddingValues ->
                    CineMindNavHost(modifier = Modifier.padding(paddingValues))
                }
            }
        }
    }
}

@Composable
fun CineMindNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(
        navController = navController,
        startDestination = "media_library",
        modifier = modifier,
    ) {
        composable("media_library") {
            MediaLibraryScreen(
                onProjectSelected = { projectId ->
                    navController.navigate("editor/$projectId")
                },
            )
        }
        composable("editor/{projectId}") { backStackEntry ->
            val projectId = backStackEntry.arguments?.getString("projectId").orEmpty()
            TimelineScreen(projectId = projectId)
        }
    }
}

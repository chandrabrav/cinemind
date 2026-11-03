package com.cinemind.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cinemind.app.di.CineMindApplication
import com.cinemind.core.ui.theme.CineMindTheme
import com.cinemind.feature.editor.timeline.ui.TimelineScreen
import com.cinemind.feature.media.ui.MediaLibraryScreen
import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CineMindTheme {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(text = "CineMind AI") },
                            colors = TopAppBarDefaults.mediumTopAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            )
                        )
                    }
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
    navController: androidx.navigation.NavController = rememberNavController()
) {
    NavHost(
        navController = navController,
        startDestination = "media_library",
        modifier = modifier
    ) {
        composable("media_library") {
            MediaLibraryScreen(
                onProjectSelected = { projectId ->
                    navController.navigate("editor/$projectId")
                }
            )
        }
        composable("editor/{projectId}") {
            val projectId = navController.currentBackStackEntryAsSelf()
                ?.arguments?.getString("projectId") ?: ""
            TimelineScreen(projectId = projectId)
        }
    }
}
package com.cinemind.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.cinemind.core.ui.theme.CineMind
import com.cinemind.feature.ai.cloud.ui.CloudAiScreen
import com.cinemind.feature.ai.llm.ui.AiModelsScreen

/**
 * One place for every AI model: download and manage the on-device models (Gemma), or connect a
 * cloud service (Claude, ChatGPT, Gemini, OpenRouter) with your own key.
 */
@Composable
fun AiHubScreen(initialTab: Int = 0) {
    val c = CineMind.colors
    var tab by rememberSaveable { mutableIntStateOf(initialTab.coerceIn(0, 1)) }
    Column(Modifier.fillMaxSize()) {
        TabRow(
            selectedTabIndex = tab,
            containerColor = c.surfaceRaised,
            contentColor = c.textPrimary,
            indicator = { positions -> TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(positions[tab]), color = c.accent) },
        ) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("On this phone") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Cloud") })
        }
        when (tab) {
            0 -> AiModelsScreen()
            else -> CloudAiScreen()
        }
    }
}

package com.cinemind.feature.media.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cinemind.core.domain.model.MediaAsset
import com.cinemind.core.domain.repository.MediaAssetRepository
import com.cinemind.core.ui.theme.CineMindTheme
import com.cinemind.core.ui.theme.CineMindColorScheme
import com.cinemind.core.ui.theme.formatTimecode
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.hilt.navigation.compose.hiltViewModel
import javax.inject.Inject
import androidx.lifecycle.ViewModel

class MediaLibraryViewModel @Inject constructor(
    private val mediaAssetRepository: MediaAssetRepository
) : ViewModel() {
    val assets by mediaAssetRepository.getAllAssets().stateIn(
        scope = androidx.lifecycle.viewModelScope,
        started = androidx.lifecycle.SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    fun onAssetSelected(asset: MediaAsset) {
        // Will be implemented to open editor
    }
}

@HiltViewModel
class MediaLibraryViewModelHilt @Inject constructor(
    private val mediaAssetRepository: MediaAssetRepository
) : ViewModel() {
    val assets by mediaAssetRepository.getAllAssets().stateIn(
        scope = androidx.lifecycle.viewModelScope,
        started = androidx.lifecycle.SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )
}

@Composable
fun MediaLibraryScreen(
    onProjectSelected: (String) -> Unit,
    viewModel: MediaLibraryViewModelHilt = hiltViewModel()
) {
    CineMindTheme {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = "Media Library",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(16.dp)
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp)
            ) {
                items(
                    items = viewModel.assets,
                    key = { it.id }
                ) { asset ->
                    MediaAssetItem(
                        asset = asset,
                        onClick = { onProjectSelected(asset.id) }
                    )
                }
            }

            FloatingActionButton(
                onClick = { /* New project */ },
                modifier = Modifier.align(Alignment.BottomEnd)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add"
                )
            }
        }
    }
}

@Composable
fun MediaAssetItem(
    asset: MediaAsset,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(bottom = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = asset.uri.substringAfterLast("/"),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = formatTimecode(asset.durationUs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
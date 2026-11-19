package com.cinemind.feature.ai.generation.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cinemind.core.domain.aiedit.ReferenceKind
import com.cinemind.core.domain.aiedit.StyleReference
import com.cinemind.core.domain.aiedit.StyleReferences
import com.cinemind.core.ui.theme.CineMind
import com.cinemind.core.ui.theme.Radius
import com.cinemind.core.ui.theme.Spacing
import kotlinx.coroutines.delay

/**
 * Starts voice input for a text box: Whisper on the phone when it's installed (asks for the
 * microphone first), otherwise the phone's own speech recogniser (offline when it can).
 * Returns the action for a mic button; recognised words go to [onText].
 */
@Composable
internal fun rememberVoiceInput(viewModel: EditBriefViewModel, onText: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val deliver by rememberUpdatedState(onText)
    val recogniser = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { deliver(it) }
        }
    }
    val target = remember { arrayOf<(String) -> Unit>({}) }
    target[0] = { t: String -> deliver(t) }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.startListening(target[0])
    }
    return {
        if (viewModel.voice.onDevice) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) viewModel.startListening(target[0])
            else mic.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Describe your video")
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            runCatching { recogniser.launch(intent) }.onFailure { viewModel.voiceUnavailable() }
        }
    }
}

/** Speak · Sample video · Image, under the prompt. */
@Composable
internal fun PromptInputRow(viewModel: EditBriefViewModel, onSpeak: () -> Unit) {
    val refs by viewModel.references.collectAsStateWithLifecycle()
    val work by viewModel.referenceWork.collectAsStateWithLifecycle()
    val video = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { viewModel.addReference(it, ReferenceKind.VIDEO) } }
    val image = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { viewModel.addReference(it, ReferenceKind.IMAGE) } }
    val canAdd = work == null && refs.size < StyleReferences.MAX
    val c = CineMind.colors

    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), modifier = Modifier.fillMaxWidth()) {
        InputButton(Icons.Default.Mic, "Speak", true, Modifier.weight(1f), onSpeak)
        InputButton(Icons.Outlined.Movie, "Sample video", canAdd, Modifier.weight(1.25f)) {
            video.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
        }
        InputButton(Icons.Outlined.Image, "Image", canAdd, Modifier.weight(1f)) {
            image.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }
    if (refs.isEmpty() && work == null) {
        Text(
            "Add a sample video or image to say “make it like this”. CineMind matches its pace and look; only your own clips are edited.",
            style = MaterialTheme.typography.labelSmall,
            color = c.textTertiary,
            modifier = Modifier.padding(top = Spacing.xs),
        )
    }
    work?.let { step ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = Spacing.s)) {
            CircularProgressIndicator(strokeWidth = 2.dp, color = c.accent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(Spacing.s))
            Text(step, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        }
    }
    refs.forEachIndexed { i, r -> ReferenceRow(r) { viewModel.removeReference(i) } }
}

@Composable
private fun InputButton(icon: ImageVector, label: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = CineMind.colors
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(Radius.m),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Spacing.s, vertical = Spacing.s),
        modifier = modifier,
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled) c.accent else c.textTertiary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(Spacing.xs))
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (enabled) c.textPrimary else c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ReferenceRow(r: StyleReference, onRemove: () -> Unit) {
    val c = CineMind.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.s)
            .background(c.surface, RoundedCornerShape(Radius.m))
            .padding(start = Spacing.m, top = Spacing.xs, bottom = Spacing.xs),
    ) {
        Icon(if (r.kind == ReferenceKind.VIDEO) Icons.Outlined.Movie else Icons.Outlined.Image, contentDescription = null, tint = c.accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(Spacing.s))
        Column(Modifier.weight(1f)) {
            Text(r.name, style = MaterialTheme.typography.labelLarge, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(r.summary().ifBlank { r.kind.label }, style = MaterialTheme.typography.labelSmall, color = c.textSecondary, maxLines = 2)
        }
        IconButton(onClick = onRemove) { Icon(Icons.Default.Close, contentDescription = "Remove ${r.name}", tint = c.textTertiary) }
    }
}

/** "Listening…" while the microphone is on; "Turning speech into text…" afterwards. */
@Composable
internal fun VoiceDialog(viewModel: EditBriefViewModel) {
    val state by viewModel.voiceUi.collectAsStateWithLifecycle()
    val c = CineMind.colors
    when (val s = state) {
        EditBriefViewModel.VoiceUi.Idle -> Unit
        is EditBriefViewModel.VoiceUi.Failed -> AlertDialog(
            onDismissRequest = viewModel::dismissVoiceError,
            title = { Text("Voice input") },
            text = { Text(s.message) },
            confirmButton = { TextButton(onClick = viewModel::dismissVoiceError) { Text("OK") } },
        )
        EditBriefViewModel.VoiceUi.Listening, EditBriefViewModel.VoiceUi.Transcribing -> {
            val listening = s == EditBriefViewModel.VoiceUi.Listening
            var level by remember { mutableFloatStateOf(0f) }
            LaunchedEffect(listening) {
                while (listening) { level = viewModel.voice.level; delay(80) }
            }
            AlertDialog(
                onDismissRequest = {},
                icon = { Icon(Icons.Default.Mic, contentDescription = null, tint = c.accent) },
                title = { Text(if (listening) "Listening…" else "Turning speech into text…") },
                text = {
                    Column {
                        Text(
                            if (listening) "Describe your video in any language. Tap Done when you finish (up to a minute)."
                            else "On this phone, with Whisper. Nothing is uploaded.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(Spacing.m))
                        if (listening) {
                            LinearProgressIndicator(progress = { (level * 2.5f).coerceIn(0.02f, 1f) }, color = c.accent, modifier = Modifier.fillMaxWidth())
                        } else {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = c.accent) }
                        }
                    }
                },
                confirmButton = { if (listening) TextButton(onClick = viewModel::finishListening) { Text("Done") } },
                dismissButton = { if (listening) TextButton(onClick = viewModel::cancelListening) { Text("Cancel") } },
            )
        }
    }
}

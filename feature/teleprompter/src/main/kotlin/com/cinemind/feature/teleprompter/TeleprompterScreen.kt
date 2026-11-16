package com.cinemind.feature.teleprompter

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cinemind.core.domain.teleprompter.Script
import com.cinemind.core.ui.components.GradientButton
import com.cinemind.core.ui.components.SettingRow
import com.cinemind.core.ui.theme.CineMind
import com.cinemind.core.ui.theme.Radius
import com.cinemind.core.ui.theme.Spacing
import kotlin.math.roundToInt

/** Teleprompter Studio: write the script, then read it to the camera segment by segment. */
@Composable
fun TeleprompterScreen(onProjectCreated: (String) -> Unit, viewModel: TeleprompterViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(viewModel) { viewModel.createdFlow.collect { onProjectCreated(it) } }
    LaunchedEffect(ui.error) { ui.error?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() } }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.CAMERA] == true) viewModel.startSession()
        else Toast.makeText(context, "Allow the camera (and microphone) to record with the teleprompter", Toast.LENGTH_LONG).show()
    }
    val openCamera = {
        val needed = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) viewModel.startSession() else permissions.launch(needed.toTypedArray())
    }

    if (ui.phase == TeleprompterUi.Phase.EDIT) {
        ScriptEditor(ui, viewModel, onRecord = openCamera)
    } else {
        BackHandler { if (ui.phase != TeleprompterUi.Phase.RECORDING) viewModel.backToEditor() }
        CameraStage(ui, viewModel)
    }
}

@Composable
private fun ScriptEditor(ui: TeleprompterUi, vm: TeleprompterViewModel, onRecord: () -> Unit) {
    val c = CineMind.colors
    val script = vm.script
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.gutter, vertical = Spacing.m),
    ) {
        Text("Teleprompter", style = MaterialTheme.typography.headlineSmall, color = c.textPrimary)
        Text(
            "Write or paste your script. Leave a blank line between parts: each part is recorded as its own take, so you can redo just one.",
            style = MaterialTheme.typography.bodySmall,
            color = c.textSecondary,
        )
        Spacer(Modifier.height(Spacing.m))
        OutlinedTextField(
            value = ui.script,
            onValueChange = vm::setScript,
            label = { Text("Script") },
            minLines = 8,
            modifier = Modifier.fillMaxWidth(),
        )
        val minutes = Script.durationMs(script.words.size, ui.wpm) / 1000
        Text(
            "${script.segments.size} part(s) · ${script.words.size} words · about ${minutes / 60}:${(minutes % 60).toString().padStart(2, '0')} at ${ui.wpm} words/min",
            style = MaterialTheme.typography.labelSmall,
            color = c.textTertiary,
            modifier = Modifier.padding(top = Spacing.xs),
        )
        Spacer(Modifier.height(Spacing.m))
        LabeledSlider("Reading speed: ${ui.wpm} words/min", ui.wpm.toFloat(), 60f..260f) { vm.setWpm(it.roundToInt()) }
        LabeledSlider("Text size: ${ui.textSize}", ui.textSize.toFloat(), 18f..56f) { vm.setTextSize(it.roundToInt()) }
        SettingRow("Mirror the text", subtitle = "For a beam-splitter teleprompter glass") {
            Switch(checked = ui.mirror, onCheckedChange = vm::setMirror)
        }
        SettingRow(
            "Follow my voice (beta)",
            subtitle = if (ui.voiceAvailable) "The text moves as you speak (on-device speech recognition; nothing is uploaded)" else "Needs Android 13+ with on-device speech recognition; scrolls at your reading speed instead",
        ) {
            Switch(checked = ui.voiceFollow, onCheckedChange = vm::setVoiceFollow, enabled = ui.voiceAvailable)
        }
        Spacer(Modifier.height(Spacing.l))
        GradientButton("Record", onClick = onRecord, icon = Icons.Default.Videocam, enabled = script.segments.isNotEmpty(), modifier = Modifier.fillMaxWidth())
        if (ui.takes.isNotEmpty()) {
            TextButton(onClick = vm::finish, enabled = !ui.saving, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(if (ui.saving) "Making the project…" else "Make a project from ${ui.takes.size} take(s)")
            }
        }
        Text(
            "Takes stay on your phone. The script scrolls at your reading speed; drag it while recording to catch up or wait.",
            style = MaterialTheme.typography.bodySmall,
            color = c.textTertiary,
            modifier = Modifier.padding(top = Spacing.m),
        )
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    val c = CineMind.colors
    var v by remember(value) { mutableFloatStateOf(value) }
    Text(label, style = MaterialTheme.typography.labelLarge, color = c.textSecondary)
    Slider(
        value = v,
        onValueChange = { v = it; onChange(it) },
        valueRange = range,
        colors = SliderDefaults.colors(thumbColor = c.selection, activeTrackColor = c.selection, inactiveTrackColor = c.surfaceSunken),
        modifier = Modifier.semantics { contentDescription = label },
    )
}

@Composable
private fun CameraStage(ui: TeleprompterUi, vm: TeleprompterViewModel) {
    val context = LocalContext.current
    val recorder = remember { CameraRecorder(context) }
    val script = vm.script
    val segment = script.segments.getOrNull(ui.segment)
    val recording = ui.phase == TeleprompterUi.Phase.RECORDING
    val hasMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        CameraPreview(recorder, front = ui.frontCamera, modifier = Modifier.fillMaxSize())

        // Script near the top, where the front camera is, so the eyes stay close to the lens.
        if (segment != null) {
            PromptText(
                script = script,
                firstWord = segment.firstWord,
                lastWord = segment.lastWord,
                position = ui.position,
                textSize = ui.textSize,
                mirror = ui.mirror,
                onNudge = vm::nudge,
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.42f)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = Spacing.l, vertical = Spacing.m),
            )
        }

        if (ui.phase == TeleprompterUi.Phase.COUNTDOWN) {
            Text(
                "${ui.countdown}",
                color = Color.White,
                fontSize = 96.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center).clickable { vm.cancelCountdown() },
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(Spacing.m),
        ) {
            // Segments: ✓ = has a take; tap one to (re)record it.
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                script.segments.forEach { s ->
                    val done = s.index in ui.takes
                    val sel = s.index == ui.segment
                    val shape = RoundedCornerShape(Radius.xl)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(shape)
                            .background(if (sel) Color.White else Color.White.copy(alpha = 0.15f))
                            .border(1.dp, Color.White.copy(alpha = 0.4f), shape)
                            .clickable(enabled = !recording) { vm.selectSegment(s.index) }
                            .padding(horizontal = Spacing.m, vertical = Spacing.xs),
                    ) {
                        if (done) Icon(Icons.Default.Check, contentDescription = "recorded", tint = if (sel) Color.Black else Color.White, modifier = Modifier.size(14.dp))
                        Text("Part ${s.index + 1}", color = if (sel) Color.Black else Color.White, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            Spacer(Modifier.height(Spacing.s))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = vm::backToEditor, enabled = !recording) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to the script", tint = Color.White)
                }
                IconButton(onClick = vm::flipCamera, enabled = !recording) {
                    Icon(Icons.Default.Cameraswitch, contentDescription = "Switch camera", tint = Color.White)
                }
                Spacer(Modifier.weight(1f))
                // Record / stop.
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .border(4.dp, Color.White, CircleShape)
                        .clickable {
                            when (ui.phase) {
                                TeleprompterUi.Phase.READY -> vm.countdown {
                                    // With voice-follow CineMind captures the sound itself (one app can use the mic).
                                    vm.startVoice()
                                    recorder.start(vm.newTakeFile(), withAudio = hasMic && !vm.voiceMode) { file, err -> vm.onTakeRecorded(file, err) }
                                }
                                TeleprompterUi.Phase.RECORDING -> {
                                    vm.stopVoice()
                                    recorder.stop()
                                }
                                TeleprompterUi.Phase.COUNTDOWN -> vm.cancelCountdown()
                                else -> Unit
                            }
                        }
                        .semantics { contentDescription = if (recording) "Stop recording" else "Record this part" },
                ) {
                    Box(
                        Modifier
                            .size(if (recording) 28.dp else 54.dp)
                            .clip(if (recording) RoundedCornerShape(6.dp) else CircleShape)
                            .background(Color(0xFFE53935)),
                    )
                }
                Spacer(Modifier.weight(1f))
                val seconds = ui.elapsedMs / 1000
                Text(
                    if (recording) "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}" else "${ui.takes.size}/${script.segments.size}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.width(48.dp),
                )
                TextButton(onClick = vm::finish, enabled = ui.takes.isNotEmpty() && !recording && !ui.saving) {
                    Text(if (ui.saving) "Saving…" else "Done", color = Color.White)
                }
            }
        }
    }
}

/**
 * The current part of the script: words already read are dimmed, the current one is highlighted,
 * and the text scrolls so the current line stays near the top (close to the camera). Dragging up
 * or down moves the reading point by a few words.
 */
@Composable
private fun PromptText(
    script: Script,
    firstWord: Int,
    lastWord: Int,
    position: Int,
    textSize: Int,
    mirror: Boolean,
    onNudge: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val starts = remember(script, firstWord, lastWord) {
        var offset = 0
        (firstWord..lastWord).map { i -> offset.also { offset += script.words[i].text.length + 1 } }
    }
    val text = remember(script, firstWord, lastWord, position) {
        buildAnnotatedString {
            for (i in firstWord..lastWord) {
                val style = when {
                    i < position -> SpanStyle(color = Color.White.copy(alpha = 0.35f))
                    i == position -> SpanStyle(color = Color(0xFFFFD43B), fontWeight = FontWeight.Bold)
                    else -> SpanStyle(color = Color.White)
                }
                withStyle(style) { append(script.words[i].text) }
                if (i < lastWord) append(" ")
            }
        }
    }
    LaunchedEffect(position, layout) {
        val l = layout ?: return@LaunchedEffect
        val idx = (position - firstWord).coerceIn(0, starts.lastIndex)
        val line = l.getLineForOffset(starts[idx].coerceAtMost(l.layoutInput.text.length - 1).coerceAtLeast(0))
        val target = (l.getLineTop(line) - l.getLineBottom(0) * 0.2f).roundToInt().coerceAtLeast(0)
        scroll.animateScrollTo(target)
    }
    var dragAcc by remember { mutableFloatStateOf(0f) }
    Box(
        modifier = modifier
            .graphicsLayer { if (mirror) scaleX = -1f }
            .pointerInput(Unit) {
                detectVerticalDragGestures(onDragEnd = { dragAcc = 0f }) { change, dy ->
                    change.consume()
                    dragAcc += dy
                    // About three words per 40 px; dragging up moves ahead.
                    val step = 40f
                    while (dragAcc <= -step) { onNudge(3); dragAcc += step }
                    while (dragAcc >= step) { onNudge(-3); dragAcc -= step }
                }
            },
    ) {
        Text(
            text,
            fontSize = textSize.sp,
            lineHeight = (textSize * 1.3f).sp,
            onTextLayout = { layout = it },
            modifier = Modifier.verticalScroll(scroll, enabled = false),
        )
    }
}

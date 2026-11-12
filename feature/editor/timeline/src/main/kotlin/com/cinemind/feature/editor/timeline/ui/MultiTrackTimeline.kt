package com.cinemind.feature.editor.timeline.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.material.icons.filled.FirstPage
import androidx.compose.material.icons.automirrored.filled.LastPage
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.cinemind.core.domain.editing.ClipLimits
import com.cinemind.core.domain.editing.ClipTransition
import com.cinemind.core.domain.model.Clip
import com.cinemind.core.ui.components.hapticClick
import com.cinemind.core.ui.format.formatDuration
import com.cinemind.core.ui.theme.CineMind
import com.cinemind.core.ui.theme.Radius
import com.cinemind.core.ui.theme.Spacing
import com.cinemind.feature.editor.timeline.preview.PreviewClip
import com.cinemind.feature.editor.timeline.preview.TimelineLayout
import kotlin.math.roundToInt

private val VIDEO_LANE = 64.dp
private val MUSIC_LANE = 34.dp
private val CAPTION_LANE = 30.dp
private val OVERLAY_LANE = 34.dp
private val VOICE_LANE = 30.dp
private val RULER = 24.dp
private val LANE_GAP = 6.dp
private val MIN_CLIP = 28.dp
private val HANDLE = 18.dp

/**
 * Time-scaled multitrack timeline: ruler, video lane, music lane and a playhead.
 * Clips are as wide as they are long at the current zoom. Tap the ruler to seek, tap a clip to
 * select it, drag the selected clip's edge handles to trim (committed when the finger lifts).
 */
@Composable
internal fun MultiTrackTimeline(
    clips: List<PreviewClip>,
    music: MusicUi?,
    totalUs: Long,
    positionUs: Long,
    isPlaying: Boolean,
    selectedClipId: String?,
    limitsFor: (Clip) -> ClipLimits,
    onSeek: (Long) -> Unit,
    onClipTap: (Int) -> Unit,
    onTrim: (Long, Long) -> Unit,
    onMusicTap: () -> Unit,
    modifier: Modifier = Modifier,
    captions: List<com.cinemind.core.domain.captions.Caption> = emptyList(),
    onCaptionsTap: () -> Unit = {},
    fps: Int = 30,
    overlays: List<PreviewClip> = emptyList(),
    selectedOverlayId: String? = null,
    onOverlayTap: (String) -> Unit = {},
    onAddOverlay: () -> Unit = {},
    voiceClips: List<PreviewClip> = emptyList(),
    selectedVoiceId: String? = null,
    onVoiceTap: (String) -> Unit = {},
    onAddVoice: () -> Unit = {},
) {
    val c = CineMind.colors
    val density = LocalDensity.current
    var pps by rememberSaveable { mutableFloatStateOf(with(density) { TimelineLayout.DEFAULT_DP_PER_SECOND.dp.toPx() }) }
    val scroll = rememberScrollState()
    // Scroll target computed with the new zoom; applied after the wider/narrower row is laid out.
    var pendingScroll by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(pendingScroll) {
        val target = pendingScroll ?: return@LaunchedEffect
        withFrameNanos { }
        scroll.scrollTo(target.roundToInt())
        pendingScroll = null
    }

    BoxWithConstraints(modifier = modifier) {
        val viewportPx = constraints.maxWidth.toFloat()
        val leadPx = with(density) { Spacing.gutter.toPx() }
        val range = TimelineLayout.zoomRange(totalUs, fps, density.density, viewportPx, leadPx)
        // Keep the zoom valid when the project gets shorter/longer.
        LaunchedEffect(range) { pps = pps.coerceIn(range.start, range.endInclusive) }

        fun zoomTo(target: Float, anchorX: Float) {
            val next = target.coerceIn(range.start, range.endInclusive)
            if (next == pps) return
            val from = pendingScroll ?: scroll.value.toFloat()
            pendingScroll = TimelineLayout.scrollAfterZoom(from, anchorX, leadPx, pps, next)
            pps = next
        }
        fun fit(startUs: Long, endUs: Long) {
            val next = TimelineLayout.fitPxPerSecond(endUs - startUs, viewportPx, leadPx).coerceIn(range.start, range.endInclusive)
            pps = next
            pendingScroll = TimelineLayout.scrollToShow(startUs, leadPx, next)
        }
        val playheadViewportX = (leadPx + TimelineLayout.xForUs(positionUs, pps) - scroll.value).coerceIn(0f, viewportPx)
        val selected = clips.firstOrNull { it.clip.id == selectedClipId }?.clip
        val zoom by rememberUpdatedState<(Float, Float) -> Unit>({ factor, anchor -> zoomTo(pps * factor, anchor) })

    Column {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = Spacing.gutter, end = Spacing.xs)) {
            Text(formatDuration(totalUs), style = MaterialTheme.typography.labelLarge, color = c.textPrimary, modifier = Modifier.weight(1f))
            IconButton(onClick = hapticClick { onSeek(0); pendingScroll = 0f }) {
                Icon(Icons.Default.FirstPage, contentDescription = "Go to start", tint = c.textSecondary)
            }
            IconButton(onClick = hapticClick { onSeek(totalUs); pendingScroll = TimelineLayout.xForUs(totalUs, pps) + leadPx - viewportPx * 0.6f }) {
                Icon(Icons.AutoMirrored.Filled.LastPage, contentDescription = "Go to end", tint = c.textSecondary)
            }
            IconButton(onClick = hapticClick { if (selected != null) fit(selected.timelineInUs, selected.timelineOutUs) else fit(0, totalUs) }) {
                Icon(
                    if (selected != null) Icons.Default.CenterFocusStrong else Icons.Default.FitScreen,
                    contentDescription = if (selected != null) "Zoom to selected clip" else "Zoom to fit the whole video",
                    tint = c.textSecondary,
                )
            }
            IconButton(onClick = hapticClick { zoomTo(pps / 1.6f, playheadViewportX) }, enabled = pps > range.start + 0.01f) {
                Icon(Icons.Default.Remove, contentDescription = "Zoom out", tint = c.textSecondary)
            }
            IconButton(onClick = hapticClick { zoomTo(pps * 1.6f, playheadViewportX) }, enabled = pps < range.endInclusive - 0.01f) {
                Icon(Icons.Default.Add, contentDescription = "Zoom in", tint = c.textSecondary)
            }
        }

        // Tracks scroll vertically when the editor gives the timeline less height than all lanes need.
        Box(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            // Trailing room so the end of the timeline can scroll under the playhead.
            val contentPx = leadPx + TimelineLayout.xForUs(totalUs, pps) + viewportPx * 0.6f
            val playheadX = leadPx + TimelineLayout.xForUs(positionUs, pps)
            val currentPps by rememberUpdatedState(pps)

            // Keep the playhead in view while playing (and when it jumps out of view). Not on zoom:
            // zooming keeps the point under the fingers / playhead fixed instead.
            LaunchedEffect(positionUs, isPlaying) {
                if (pendingScroll != null) return@LaunchedEffect
                val x = leadPx + TimelineLayout.xForUs(positionUs, currentPps)
                val visible = x >= scroll.value && x <= scroll.value + viewportPx
                if (isPlaying || !visible) scroll.scrollTo(TimelineLayout.followScroll(x, viewportPx, contentPx))
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    // Two fingers = zoom around their midpoint. Handled before scrolling and the
                    // clips see the events, and consumed then, so a pinch never scrolls, moves or trims.
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.changes.count { it.pressed } >= 2) {
                                    val factor = event.calculateZoom()
                                    val centroid = event.calculateCentroid(useCurrent = true)
                                    if (factor != 1f && centroid != Offset.Unspecified) zoom(factor, centroid.x)
                                    event.changes.forEach { it.consume() }
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    }
                    .semantics { contentDescription = "Timeline. Pinch with two fingers to zoom, or use the zoom buttons." }
                    .horizontalScroll(scroll),
            ) {
                Column(modifier = Modifier.width(with(density) { contentPx.toDp() })) {
                    Ruler(totalUs, pps, leadPx, fps, scroll, viewportPx, onSeek)
                    Spacer(Modifier.height(Spacing.xs))
                    // Video lane
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(VIDEO_LANE)
                            .background(c.timelineLane),
                    ) {
                        clips.forEachIndexed { index, item ->
                            val clip = item.clip
                            ClipBlock(
                                index = index,
                                item = item,
                                xPx = leadPx + TimelineLayout.xForUs(clip.timelineInUs, pps),
                                widthPx = TimelineLayout.widthForUs(clip.timelineOutUs - clip.timelineInUs, pps, with(density) { MIN_CLIP.toPx() }),
                                pps = pps,
                                selected = clip.id == selectedClipId,
                                limits = limitsFor(clip),
                                onTap = { onClipTap(index) },
                                onTrim = onTrim,
                            )
                        }
                    }
                    Spacer(Modifier.height(LANE_GAP))
                    // Overlay lane (picture-in-picture): clips at free times, drawn above the video.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(OVERLAY_LANE)
                            .background(c.timelineLane),
                    ) {
                        if (overlays.isEmpty()) {
                            OverlayBlock(null, leadPx, viewportPx - leadPx * 2, selected = false, onTap = onAddOverlay)
                        } else {
                            overlays.forEach { o ->
                                OverlayBlock(
                                    item = o,
                                    xPx = leadPx + TimelineLayout.xForUs(o.clip.timelineInUs, pps),
                                    widthPx = TimelineLayout.xForUs(o.clip.timelineOutUs - o.clip.timelineInUs, pps),
                                    selected = o.clip.id == selectedOverlayId,
                                    onTap = { onOverlayTap(o.clip.id) },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(LANE_GAP))
                    // Music lane
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(MUSIC_LANE)
                            .background(c.timelineLane),
                    ) {
                        MusicBlock(
                            music = music,
                            xPx = leadPx,
                            widthPx = if (music != null) TimelineLayout.xForUs(totalUs, pps) else viewportPx - leadPx * 2,
                            onTap = onMusicTap,
                        )
                    }
                    Spacer(Modifier.height(LANE_GAP))
                    // Voiceover lane: recorded narration at its timeline times.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(VOICE_LANE)
                            .background(c.timelineLane),
                    ) {
                        if (voiceClips.isEmpty()) {
                            VoiceBlock(null, leadPx, viewportPx - leadPx * 2, selected = false, onTap = onAddVoice)
                        } else {
                            voiceClips.forEach { v ->
                                VoiceBlock(
                                    item = v,
                                    xPx = leadPx + TimelineLayout.xForUs(v.clip.timelineInUs, pps),
                                    widthPx = TimelineLayout.xForUs(v.clip.timelineOutUs - v.clip.timelineInUs, pps),
                                    selected = v.clip.id == selectedVoiceId,
                                    onTap = { onVoiceTap(v.clip.id) },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(LANE_GAP))
                    // Caption lane: one block per caption, at its timeline time.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(CAPTION_LANE)
                            .background(c.timelineLane),
                    ) {
                        if (captions.isEmpty()) {
                            CaptionBlock(null, leadPx, viewportPx - leadPx * 2, onCaptionsTap)
                        } else {
                            captions.forEach { cap ->
                                CaptionBlock(
                                    caption = cap,
                                    xPx = leadPx + TimelineLayout.xForUs(cap.startUs, pps),
                                    widthPx = TimelineLayout.xForUs(cap.endUs - cap.startUs, pps),
                                    onTap = onCaptionsTap,
                                )
                            }
                        }
                    }
                }
                Playhead(xPx = playheadX)
            }
        }
    }
}
}

@Composable
private fun Ruler(
    totalUs: Long,
    pps: Float,
    leadPx: Float,
    fps: Int,
    scroll: androidx.compose.foundation.ScrollState,
    viewportPx: Float,
    onSeek: (Long) -> Unit,
) {
    val c = CineMind.colors
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // Seconds/minutes when zoomed out, down to single frames (at the project's frame rate) when zoomed in.
    val step = TimelineLayout.rulerStep(pps, with(density) { 56.dp.toPx() }, fps)
    val labelStyle = TextStyle(fontSize = 10.sp, color = c.textTertiary)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(RULER)
            .semantics { contentDescription = "Time ruler; tap to move the playhead" }
            .pointerInput(pps, leadPx) {
                detectTapGestures { offset -> onSeek(TimelineLayout.usForX(offset.x - leadPx, pps).coerceAtMost(totalUs)) }
            },
    ) {
        // Only the visible part is drawn (scroll is read here, in the draw phase: no recomposition).
        val margin = 80.dp.toPx()
        val fromUs = TimelineLayout.usForX(scroll.value - leadPx - margin, pps)
        val toUs = TimelineLayout.usForX(scroll.value + viewportPx - leadPx + margin, pps)
        TimelineLayout.ticksIn(step, fps, fromUs, toUs, totalUs).forEach { t ->
            val x = leadPx + TimelineLayout.xForUs(t, pps)
            drawLine(c.hairline.copy(alpha = 0.9f), Offset(x, size.height * 0.55f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
            val text = if (step.isFrames) TimelineLayout.frameLabel(t, fps) else TimelineLayout.label(t)
            drawText(measurer, text, Offset(x + 3.dp.toPx(), 0f), labelStyle)
            if (!step.isFrames) {
                // Minor tick halfway.
                val mid = x + TimelineLayout.xForUs(step.us / 2, pps)
                if (t + step.us / 2 <= totalUs) {
                    drawLine(c.hairline, Offset(mid, size.height * 0.78f), Offset(mid, size.height), strokeWidth = 1.dp.toPx())
                }
            }
        }
    }
}

@Composable
private fun ClipBlock(
    index: Int,
    item: PreviewClip,
    xPx: Float,
    widthPx: Float,
    pps: Float,
    selected: Boolean,
    limits: ClipLimits,
    onTap: () -> Unit,
    onTrim: (Long, Long) -> Unit,
) {
    val c = CineMind.colors
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val clip = item.clip
    // Live drag offsets (px) while trimming; reset whenever the stored clip changes.
    var dragLeft by remember(clip.id, clip.sourceInUs, clip.sourceOutUs) { mutableFloatStateOf(0f) }
    var dragRight by remember(clip.id, clip.sourceInUs, clip.sourceOutUs) { mutableFloatStateOf(0f) }
    val minPx = with(density) { MIN_CLIP.toPx() }
    val shownWidth = (widthPx - dragLeft + dragRight).coerceAtLeast(minPx)
    val shape = RoundedCornerShape(Radius.s)
    fun us(px: Float): Long = (px / pps * 1_000_000f).toLong()

    Box(
        modifier = Modifier
            .offset { IntOffset((xPx + dragLeft).roundToInt(), 0) }
            .width(with(density) { shownWidth.toDp() })
            .fillMaxHeight()
            .padding(vertical = Spacing.xs, horizontal = 1.dp)
            .clip(shape)
            .background(c.trackVideo)
            .then(if (selected) Modifier.border(2.dp, c.selection, shape) else Modifier)
            .semantics { contentDescription = "Clip ${index + 1}, ${formatDuration(clip.timelineOutUs - clip.timelineInUs)}" + if (selected) ", selected" else "" }
            .clickable(onClick = hapticClick(onClick = onTap)),
    ) {
        val asset = item.asset
        if (asset != null) {
            AsyncImage(
                model = asset.uri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, c.trackVideo.copy(alpha = 0.85f)))),
            )
        }
        Text(
            text = "${index + 1} · ${formatDuration(clip.timelineOutUs - clip.timelineInUs)}" + (speedBadge(clip)?.let { " · $it" } ?: ""),
            color = c.clipLabel,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = if (selected) HANDLE + Spacing.xs else Spacing.s, end = Spacing.s, top = Spacing.xxs, bottom = Spacing.xxs),
        )
        if (index > 0 && ClipTransition.fromName(clip.transitionInType) != ClipTransition.CUT) {
            Icon(
                Icons.Default.Animation,
                contentDescription = "Transition: " + ClipTransition.fromName(clip.transitionInType).label,
                tint = c.onAccent,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(Spacing.xxs)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(c.accent)
                    .padding(2.dp),
            )
        }

        if (selected) {
            val commit = {
                if (dragLeft != 0f || dragRight != 0f) {
                    if (limits.isStill) {
                        onTrim(0, clip.sourceOutUs - clip.sourceInUs + us(dragRight - dragLeft))
                    } else {
                        onTrim(
                            clip.sourceInUs + (us(dragLeft) * com.cinemind.core.domain.editing.SpeedMath.edgeSpeed(clip, atStart = true)).toLong(),
                            clip.sourceOutUs + (us(dragRight) * com.cinemind.core.domain.editing.SpeedMath.edgeSpeed(clip, atStart = false)).toLong(),
                        )
                    }
                }
            }
            if (!limits.isStill) {
                TrimHandle(
                    alignStart = true,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .pointerInput(clip.id, pps) {
                            detectHorizontalDragGestures(
                                onDragStart = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                                onDragEnd = { commit() },
                                onDragCancel = { dragLeft = 0f },
                            ) { _, dx -> dragLeft = (dragLeft + dx).coerceAtMost(widthPx + dragRight - minPx) }
                        },
                )
            }
            TrimHandle(
                alignStart = false,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .pointerInput(clip.id, pps) {
                        detectHorizontalDragGestures(
                            onDragStart = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                            onDragEnd = { commit() },
                            onDragCancel = { dragRight = 0f },
                        ) { _, dx -> dragRight = (dragRight + dx).coerceAtLeast(minPx - widthPx + dragLeft) }
                    },
            )
        }
    }
}

@Composable
private fun TrimHandle(alignStart: Boolean, modifier: Modifier) {
    val c = CineMind.colors
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .width(HANDLE)
            .fillMaxHeight()
            .background(c.selection)
            .semantics { contentDescription = if (alignStart) "Trim start handle" else "Trim end handle" },
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(18.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(c.surface.copy(alpha = 0.9f)),
        )
    }
}

@Composable
private fun MusicBlock(music: MusicUi?, xPx: Float, widthPx: Float, onTap: () -> Unit) {
    val c = CineMind.colors
    val density = LocalDensity.current
    val shape = RoundedCornerShape(Radius.s)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .offset { IntOffset(xPx.roundToInt(), 0) }
            .width(with(density) { widthPx.coerceAtLeast(MIN_CLIP.toPx()).toDp() })
            .fillMaxHeight()
            .padding(vertical = Spacing.xxs)
            .clip(shape)
            .then(
                if (music != null) Modifier.background(c.trackMusic)
                else Modifier.border(1.dp, c.textTertiary.copy(alpha = 0.6f), shape),
            )
            .clickable(onClick = hapticClick(onClick = onTap))
            .padding(horizontal = Spacing.s),
    ) {
        Icon(
            Icons.Default.MusicNote,
            contentDescription = null,
            tint = if (music != null) c.clipLabel else c.textTertiary,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(Spacing.xs))
        Text(
            text = music?.name ?: "Add music",
            color = if (music != null) c.clipLabel else c.textTertiary,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A recording on the voiceover lane (or, with null, the "Record voiceover" hint). */
@Composable
private fun VoiceBlock(item: PreviewClip?, xPx: Float, widthPx: Float, selected: Boolean, onTap: () -> Unit) {
    val c = CineMind.colors
    val density = LocalDensity.current
    val shape = RoundedCornerShape(Radius.xs)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .offset { IntOffset(xPx.roundToInt(), 0) }
            .width(with(density) { widthPx.coerceAtLeast(10.dp.toPx()).toDp() })
            .fillMaxHeight()
            .padding(vertical = Spacing.xxs, horizontal = 1.dp)
            .clip(shape)
            .then(if (item != null) Modifier.background(c.trackMusic) else Modifier.border(1.dp, c.textTertiary.copy(alpha = 0.6f), shape))
            .then(if (selected) Modifier.border(2.dp, c.selection, shape) else Modifier)
            .semantics {
                contentDescription = if (item == null) "Record a voiceover"
                else "Voiceover, ${formatDuration(item.clip.timelineOutUs - item.clip.timelineInUs)}" + if (selected) ", selected" else ""
            }
            .clickable(onClick = hapticClick(onClick = onTap))
            .padding(horizontal = Spacing.xs),
    ) {
        Icon(Icons.Default.Mic, contentDescription = null, tint = if (item != null) c.clipLabel else c.textTertiary, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(Spacing.xs))
        Text(
            text = if (item == null) "Record voiceover" else (item.asset?.displayName?.ifEmpty { null } ?: "Voiceover"),
            color = if (item != null) c.clipLabel else c.textTertiary,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** An overlay clip on the overlay lane (or, with null, the "Add overlay" hint). */
@Composable
private fun OverlayBlock(item: PreviewClip?, xPx: Float, widthPx: Float, selected: Boolean, onTap: () -> Unit) {
    val c = CineMind.colors
    val density = LocalDensity.current
    val shape = RoundedCornerShape(Radius.xs)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .offset { IntOffset(xPx.roundToInt(), 0) }
            .width(with(density) { widthPx.coerceAtLeast(10.dp.toPx()).toDp() })
            .fillMaxHeight()
            .padding(vertical = Spacing.xxs, horizontal = 1.dp)
            .clip(shape)
            .then(
                if (item != null) Modifier.background(c.trackVideo) else Modifier.border(1.dp, c.textTertiary.copy(alpha = 0.6f), shape),
            )
            .then(if (selected) Modifier.border(2.dp, c.selection, shape) else Modifier)
            .semantics {
                contentDescription = if (item == null) "Add overlay (picture in picture)"
                else "Overlay, ${formatDuration(item.clip.timelineOutUs - item.clip.timelineInUs)}" + if (selected) ", selected" else ""
            }
            .clickable(onClick = hapticClick(onClick = onTap))
            .padding(horizontal = Spacing.xs),
    ) {
        Icon(Icons.Default.PictureInPicture, contentDescription = null, tint = if (item != null) c.clipLabel else c.textTertiary, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(Spacing.xs))
        Text(
            text = if (item == null) "Add overlay" else (item.asset?.displayName?.ifEmpty { null } ?: "Overlay"),
            color = if (item != null) c.clipLabel else c.textTertiary,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A caption on the caption lane (or, with null, the "Add captions" hint). */
@Composable
private fun CaptionBlock(caption: com.cinemind.core.domain.captions.Caption?, xPx: Float, widthPx: Float, onTap: () -> Unit) {
    val c = CineMind.colors
    val density = LocalDensity.current
    val shape = RoundedCornerShape(Radius.xs)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .offset { IntOffset(xPx.roundToInt(), 0) }
            .width(with(density) { widthPx.coerceAtLeast(6.dp.toPx()).toDp() })
            .fillMaxHeight()
            .padding(vertical = Spacing.xxs, horizontal = 1.dp)
            .clip(shape)
            .then(
                if (caption != null) Modifier.background(c.accentContainer)
                else Modifier.border(1.dp, c.textTertiary.copy(alpha = 0.6f), shape),
            )
            .clickable(onClick = hapticClick(onClick = onTap))
            .padding(horizontal = Spacing.xs),
    ) {
        if (caption == null) {
            Icon(Icons.Default.ClosedCaption, contentDescription = null, tint = c.textTertiary, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(Spacing.xs))
        }
        Text(
            text = caption?.let { (if (it.uncertain) "● " else "") + it.text } ?: "Add captions",
            color = if (caption != null) c.onAccentContainer else c.textTertiary,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 2 dp line with a knob, drawn over all lanes. */
@Composable
private fun Playhead(xPx: Float) {
    val c = CineMind.colors
    val density = LocalDensity.current
    val knob = 10.dp
    Box(
        modifier = Modifier
            .offset { IntOffset((xPx - with(density) { (knob / 2).toPx() }).roundToInt(), 0) }
            .width(knob)
            .height(RULER + Spacing.xs + VIDEO_LANE + LANE_GAP + OVERLAY_LANE + LANE_GAP + MUSIC_LANE + LANE_GAP + VOICE_LANE + LANE_GAP + CAPTION_LANE),
    ) {
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .size(knob)
                .clip(CircleShape)
                .background(c.playhead),
        )
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = knob / 2)
                .width(2.dp)
                .fillMaxHeight()
                .background(c.playhead),
        )
    }
}

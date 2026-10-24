package com.hedi.beatsense.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.hedi.beatsense.BpmViewModel
import com.hedi.beatsense.Measurement
import com.hedi.beatsense.TempoRange
import com.hedi.beatsense.UiState
import com.hedi.beatsense.genreHint
import com.hedi.beatsense.tempoName
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.exp
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BpmScreen(vm: BpmViewModel, state: UiState) {
    val context = LocalContext.current
    val view = LocalView.current
    var showHistory by remember { mutableStateOf(false) }
    var showSave by remember { mutableStateOf(false) }
    var askedOnce by remember { mutableStateOf(false) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        askedOnce = true
        if (granted) vm.start() else vm.permissionDenied()
    }
    val onMic = {
        if (state.listening) vm.stop()
        else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.start()
        else permission.launch(Manifest.permission.RECORD_AUDIO)
    }

    // Keep the screen awake while listening, and buzz once when the tempo locks in.
    DisposableEffect(state.listening) {
        view.keepScreenOn = state.listening
        onDispose { view.keepScreenOn = false }
    }
    LaunchedEffect(state.locked) {
        if (state.locked) {
            view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Beat.Bg)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("BeatSense", style = MaterialTheme.typography.headlineSmall)
                Text("Hold up your phone, hear the tempo", style = MaterialTheme.typography.bodySmall, color = Beat.Muted)
            }
            IconButton(onClick = { showHistory = true }) { Icon(Icons.Outlined.History, "Saved tempos") }
        }

        PulseDial(state)

        val bpm = state.bpm
        if (bpm != null) {
            Text(
                "${tempoName(bpm)} · ${genreHint(bpm)}",
                style = MaterialTheme.typography.bodyMedium,
                color = Beat.Muted,
                textAlign = TextAlign.Center,
            )
        }

        Visualizer(state)

        state.message?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
        }
        if (state.message?.contains("Settings") == true && askedOnce) {
            TextButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
            }) { Text("Open app settings") }
        }

        MicButton(state.listening, onMic)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallAction("½×", Modifier.weight(1f), enabled = state.bpm != null) { vm.halve() }
            SmallAction("2×", Modifier.weight(1f), enabled = state.bpm != null) { vm.double() }
            SmallAction("💾 Save", Modifier.weight(1.4f), enabled = state.bpm != null || state.tapBpm != null) { showSave = true }
            SmallAction(if (state.metronomeOn) "⏹ Click" else "🔊 Click", Modifier.weight(1.4f), active = state.metronomeOn) { vm.toggleMetronome() }
        }

        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Expected tempo range", style = MaterialTheme.typography.labelLarge, color = Beat.Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TempoRange.entries.forEach { r ->
                    SmallAction(r.label, Modifier.weight(1f), active = state.range == r) { vm.setRange(r) }
                }
            }
            Text(
                "A song can feel like 64 or 128 — pick the range you expect, or fix it with ½× / 2×.",
                style = MaterialTheme.typography.bodySmall,
                color = Beat.Muted,
            )
        }

        TapCard(state, onTap = { vm.tap() }, onReset = { vm.resetTap() })

        Text(
            "Tip: put the phone 30 cm – 1 m from the speaker. Songs with drums lock in within ~6 seconds.",
            style = MaterialTheme.typography.bodySmall,
            color = Beat.Muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 16.dp),
        )
    }

    if (showSave) SaveDialog(onDismiss = { showSave = false }) { name -> vm.save(name); showSave = false }

    if (showHistory) {
        ModalBottomSheet(onDismissRequest = { showHistory = false }, containerColor = Beat.Card) {
            HistoryList(state.history, onPlay = { vm.playAt(it); showHistory = false }, onDelete = { vm.delete(it) })
        }
    }
}

/** The big circle: shows the tempo and pulses on every beat (detected, or from the metronome). */
@Composable
private fun PulseDial(state: UiState) {
    var pulse by remember { mutableFloatStateOf(0f) }
    val active = (state.listening && state.detectedBpm != null) || state.metronomeOn
    LaunchedEffect(active, state.beatAnchorNanos, state.beatPeriodSec, state.multiplier) {
        val period = if (state.metronomeOn) state.beatPeriodSec else state.beatPeriodSec / state.multiplier
        if (!active || period <= 0) { pulse = 0f; return@LaunchedEffect }
        while (true) {
            withFrameNanos { now ->
                val phase = (((now - state.beatAnchorNanos) / 1e9) / period).mod(1.0)
                pulse = exp(-phase * 6).toFloat()
            }
        }
    }
    val progress by animateFloatAsState(
        when {
            state.metronomeOn -> 0f
            state.listening -> state.settle
            state.bpm != null -> 1f
            else -> 0f
        },
        tween(400), label = "progress",
    )
    val ring by animateColorAsState(if (state.locked) Beat.Green else Beat.Violet, label = "ring")

    Box(Modifier.size(270.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2
            // Glow that swells on each beat
            drawCircle(
                Brush.radialGradient(listOf(Beat.Pink.copy(alpha = 0.35f * pulse + 0.05f), Color.Transparent), radius = r * (0.9f + 0.15f * pulse)),
                radius = r,
            )
            val stroke = 10.dp.toPx()
            val inset = stroke / 2 + 6.dp.toPx()
            drawArc(Beat.Card2, 0f, 360f, false, Offset(inset, inset), Size(size.width - 2 * inset, size.height - 2 * inset), style = Stroke(stroke))
            drawArc(ring, -90f, 360f * progress, false, Offset(inset, inset), Size(size.width - 2 * inset, size.height - 2 * inset), style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.scale(1f + 0.06f * pulse)) {
            val bpm = state.bpm ?: state.metronomeBpm.takeIf { state.metronomeOn }
            Text(
                bpm?.roundToInt()?.toString() ?: "– –",
                fontSize = 84.sp,
                fontWeight = FontWeight.Black,
                color = if (bpm == null) Beat.Muted else Color.White,
            )
            Text("BPM", style = MaterialTheme.typography.titleMedium, color = Beat.Muted)
            Spacer(Modifier.height(6.dp))
            when {
                state.locked -> Badge("✓ Locked", Beat.Green)
                state.listening && bpm != null -> Badge("Settling…", Beat.Amber)
                state.metronomeOn -> Badge("🔊 Metronome", Beat.Cyan)
                bpm != null && state.multiplier != 1.0 -> Badge("${formatMultiplier(state.multiplier)} corrected", Beat.Violet)
                else -> {}
            }
            if (bpm != null && state.listening) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Beat clarity ${(state.confidence * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = Beat.Muted,
                )
            }
        }
    }
}

private fun formatMultiplier(m: Double) = if (m < 1) "÷${(1 / m).roundToInt()}" else "${m.roundToInt()}×"

@Composable
private fun Badge(text: String, color: Color) {
    Text(
        text,
        color = color,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** Live onset strength (last ~4 s) plus a level meter. */
@Composable
private fun Visualizer(state: UiState) {
    val bars = state.envelope
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Beat.Card)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Rhythm", style = MaterialTheme.typography.labelLarge, color = Beat.Muted, modifier = Modifier.weight(1f))
            Text("Mic", style = MaterialTheme.typography.labelSmall, color = Beat.Muted)
            Spacer(Modifier.width(6.dp))
            Box(Modifier.width(70.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Beat.Card2)) {
                val lvl by animateFloatAsState(state.level.coerceIn(0f, 1f), tween(120), label = "level")
                Box(Modifier.fillMaxWidth(lvl).height(6.dp).background(if (lvl > 0.9f) Beat.Pink else Beat.Green))
            }
        }
        Spacer(Modifier.height(10.dp))
        Canvas(Modifier.fillMaxWidth().height(70.dp)) {
            if (bars.isEmpty()) {
                drawLine(Beat.Card2, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 2.dp.toPx())
                return@Canvas
            }
            val w = size.width / bars.size
            val brush = Brush.verticalGradient(listOf(Beat.Pink, Beat.Violet))
            bars.forEachIndexed { i, v ->
                val h = (v * size.height).coerceAtLeast(2f)
                drawRoundRect(brush, Offset(i * w + w * 0.15f, size.height - h), Size(w * 0.7f, h), CornerRadius(w * 0.3f))
            }
        }
    }
}

@Composable
private fun MicButton(listening: Boolean, onClick: () -> Unit) {
    val bg = if (listening) Brush.linearGradient(listOf(Beat.Card2, Beat.Card2)) else Beat.gradient
    Row(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(32.dp))
            .background(bg)
            .border(1.dp, if (listening) Beat.Pink else Color.Transparent, RoundedCornerShape(32.dp))
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (listening) "■  Stop" else "🎤  Listen",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun SmallAction(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, active: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier
            .height(44.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (active) Beat.Violet.copy(alpha = 0.22f) else Beat.Card)
            .border(1.dp, if (active) Beat.Violet else Beat.Card2, RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = when {
                !enabled -> Beat.Muted.copy(alpha = 0.5f)
                active -> Color.White
                else -> MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            textAlign = TextAlign.Center,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun TapCard(state: UiState, onTap: () -> Unit, onReset: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Beat.Card)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Tap tempo", style = MaterialTheme.typography.titleMedium)
            Text(
                when {
                    state.tapBpm != null -> "${state.tapBpm.roundToInt()} BPM from ${state.tapCount} taps"
                    state.tapCount > 0 -> "Keep tapping…"
                    else -> "Tap along with the beat to double-check"
                },
                style = MaterialTheme.typography.bodySmall,
                color = Beat.Muted,
            )
            if (state.tapCount > 0) {
                Text("Reset", color = Beat.Violet, style = MaterialTheme.typography.labelMedium, modifier = Modifier.clickable(onClick = onReset).padding(top = 4.dp))
            }
        }
        Box(
            Modifier
                .size(76.dp)
                .clip(CircleShape)
                .background(Beat.gradient)
                .clickable(onClick = onTap),
            contentAlignment = Alignment.Center,
        ) {
            Text("TAP", color = Color.White, fontWeight = FontWeight.Black, fontSize = 18.sp)
        }
    }
}

@Composable
private fun SaveDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Beat.Card,
        title = { Text("Save this tempo") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(60) },
                label = { Text("Song name (optional)") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(name) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun HistoryList(items: List<Measurement>, onPlay: (Double) -> Unit, onDelete: (Long) -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
        Text("Saved tempos", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        if (items.isEmpty()) {
            Text("Nothing saved yet. Measure a song and tap 💾 Save.", color = Beat.Muted, modifier = Modifier.padding(vertical = 24.dp))
            return@Column
        }
        val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault()) }
        LazyColumn(contentPadding = PaddingValues(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.id }) { m ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Beat.Card2)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        m.bpm.roundToInt().toString(),
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Black,
                        color = Beat.Violet,
                        modifier = Modifier.width(64.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(m.name.ifBlank { "Untitled" }, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Text("${tempoName(m.bpm)} · ${fmt.format(Date(m.time))}", style = MaterialTheme.typography.bodySmall, color = Beat.Muted)
                    }
                    IconButton(onClick = { onPlay(m.bpm) }) { Icon(Icons.Outlined.PlayArrow, "Play metronome") }
                    IconButton(onClick = { onDelete(m.id) }) { Icon(Icons.Outlined.Delete, "Delete", tint = Beat.Muted) }
                }
            }
        }
    }
}

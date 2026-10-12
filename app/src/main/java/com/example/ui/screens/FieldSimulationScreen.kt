package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.ConceptParticleEntity
import com.example.viewmodel.NoesisUiState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FieldSimulationScreen(
    uiState: NoesisUiState,
    onSelectParticle: (ConceptParticleEntity) -> Unit,
    onCommitParticlePosition: (ConceptParticleEntity, Float, Float, Float) -> Unit,
    onUpdateParticleParams: (ConceptParticleEntity, Float, Float, Float) -> Unit,
    onInjectConcept: (String, String) -> Unit,
    onRemoveParticle: (Long) -> Unit,
    onResetParticles: () -> Unit,
    onCollideAndSynthesize: () -> Unit,
    onToggleSound: (Boolean) -> Unit,
    onToggleBinaural: (Boolean) -> Unit
) {
    val strings = uiState.strings
    var customConceptInput by remember { mutableStateOf("") }
    var showMetricsExplanation by remember { mutableStateOf(false) }

    // Zero-latency local coordinate map for buttery-smooth 60fps dragging ("Puxou, foi")
    val liveOffsets = remember { mutableStateMapOf<Long, Offset>() }
    var activeDraggingId by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(uiState.particles, activeDraggingId) {
        uiState.particles.forEach { p ->
            if (p.id != activeDraggingId) {
                liveOffsets[p.id] = Offset(p.posX, p.posY)
            }
        }
    }

    val currentParticles by rememberUpdatedState(uiState.particles)
    val selectedParticle = uiState.particles.find { it.id == uiState.selectedParticleId }
        ?: uiState.particles.firstOrNull()

    val infiniteTransition = rememberInfiniteTransition(label = "field_wave")
    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 7000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    val textMeasurer = rememberTextMeasurer()

    val musicalMoodText = when {
        uiState.meanValence > 0.68f -> strings.soundMoodCalm
        uiState.meanValence > 0.50f -> strings.soundMoodDreamy
        uiState.meanValence > 0.35f -> strings.soundMoodVibrant
        else -> strings.soundMoodTense
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag("field_simulation_screen")
    ) {
        // Intro & Sound Controls Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = strings.fieldHeaderTitle,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = strings.fieldHeaderSubtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Quick Intuitive Tip Banner
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                ) {
                    Text(
                        text = strings.fieldQuickTip,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(10.dp)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Sound & Optional Binaural Controls Row
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = uiState.preferences.soundEnabled,
                        onClick = { onToggleSound(!uiState.preferences.soundEnabled) },
                        leadingIcon = {
                            Icon(
                                imageVector = if (uiState.preferences.soundEnabled) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        label = {
                            Text(
                                text = if (uiState.preferences.soundEnabled) strings.soundToggleOn else strings.soundToggleOff,
                                style = MaterialTheme.typography.labelMedium
                            )
                        },
                        modifier = Modifier.testTag("sound_toggle_chip")
                    )

                    if (uiState.preferences.soundEnabled) {
                        FilterChip(
                            selected = uiState.preferences.binauralEnabled,
                            onClick = { onToggleBinaural(!uiState.preferences.binauralEnabled) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.GraphicEq,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            label = {
                                Text(
                                    text = strings.binauralToggleLabel,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            },
                            modifier = Modifier.testTag("binaural_toggle_chip")
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Non-Truncated Clear Metrics Row + Info Toggle
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showMetricsExplanation = !showMetricsExplanation },
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = strings.metricHarmonyLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "${(uiState.phaseCoherence * 100).toInt()}%",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = strings.metricIntensityLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "${(uiState.entropyIndex * 100).toInt()}%",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                    Column(modifier = Modifier.weight(1.3f)) {
                        Text(
                            text = strings.metricMusicalMoodLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = musicalMoodText,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Info",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }

                AnimatedVisibility(visible = showMetricsExplanation) {
                    Column(modifier = Modifier.padding(top = 8.dp)) {
                        Text(
                            text = "• ${strings.metricHarmonyLabel}: ${strings.metricHarmonyHelp}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "• ${strings.metricIntensityLabel}: ${strings.metricIntensityHelp}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Interactive Synesthetic Canvas (Colors + Chords + Ultra-Fluid 60fps Drag)
        val canvasBg = MaterialTheme.colorScheme.surface
        val gridLineColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
        val labelColor = MaterialTheme.colorScheme.onSurface

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(350.dp)
                .testTag("particle_field_canvas"),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = canvasBg),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            var lastDragSpeed by remember { mutableStateOf(10f) }

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            val w = size.width.toFloat().coerceAtLeast(1f)
                            val h = size.height.toFloat().coerceAtLeast(1f)
                            val tapped = currentParticles.minByOrNull { p ->
                                val pos = liveOffsets[p.id] ?: Offset(p.posX, p.posY)
                                hypot(pos.x * w - offset.x, pos.y * h - offset.y)
                            }
                            if (tapped != null) {
                                val pos = liveOffsets[tapped.id] ?: Offset(tapped.posX, tapped.posY)
                                if (hypot(pos.x * w - offset.x, pos.y * h - offset.y) < 130f) {
                                    onSelectParticle(tapped)
                                }
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { startOffset ->
                                val w = size.width.toFloat().coerceAtLeast(1f)
                                val h = size.height.toFloat().coerceAtLeast(1f)
                                val nearest = currentParticles.minByOrNull { p ->
                                    val pos = liveOffsets[p.id] ?: Offset(p.posX, p.posY)
                                    hypot(pos.x * w - startOffset.x, pos.y * h - startOffset.y)
                                }
                                if (nearest != null) {
                                    val pos = liveOffsets[nearest.id] ?: Offset(nearest.posX, nearest.posY)
                                    if (hypot(pos.x * w - startOffset.x, pos.y * h - startOffset.y) < 150f) {
                                        activeDraggingId = nearest.id
                                        onSelectParticle(nearest)
                                    }
                                }
                            },
                            onDragEnd = {
                                val id = activeDraggingId
                                activeDraggingId = null
                                if (id != null) {
                                    val target = currentParticles.find { it.id == id }
                                    val finalPos = liveOffsets[id]
                                    if (target != null && finalPos != null) {
                                        onCommitParticlePosition(target, finalPos.x, finalPos.y, lastDragSpeed)
                                    }
                                }
                            },
                            onDragCancel = {
                                activeDraggingId = null
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val id = activeDraggingId ?: return@detectDragGestures
                                val w = size.width.toFloat().coerceAtLeast(1f)
                                val h = size.height.toFloat().coerceAtLeast(1f)
                                val current = liveOffsets[id] ?: Offset(0.5f, 0.5f)
                                val nextX = (current.x + dragAmount.x / w).coerceIn(0.08f, 0.92f)
                                val nextY = (current.y + dragAmount.y / h).coerceIn(0.10f, 0.90f)
                                liveOffsets[id] = Offset(nextX, nextY)
                                lastDragSpeed = hypot(dragAmount.x, dragAmount.y)
                            }
                        )
                    }
            ) {
                val w = size.width
                val h = size.height
                val center = Offset(w / 2f, h / 2f)

                // 1. Synesthetic Background Color Aura modulated by each sphere's position, size, and color
                currentParticles.forEach { p ->
                    val normPos = liveOffsets[p.id] ?: Offset(p.posX, p.posY)
                    val px = normPos.x * w
                    val py = normPos.y * h
                    val auraColor = Color(p.colorHex)
                    val auraRadius = (110f + p.mass * 55f).dp.toPx()
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                auraColor.copy(alpha = 0.24f),
                                auraColor.copy(alpha = 0.08f),
                                Color.Transparent
                            ),
                            center = Offset(px, py),
                            radius = auraRadius
                        ),
                        radius = auraRadius,
                        center = Offset(px, py)
                    )
                }

                // 2. Subtle Horizon Rings
                for (ring in 1..3) {
                    val radius = (minOf(w, h) * 0.15f * ring) + sin(wavePhase + ring) * 3f
                    drawCircle(
                        color = gridLineColor,
                        radius = radius,
                        center = center,
                        style = Stroke(
                            width = 1.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
                        )
                    )
                }

                // 3. Harmonic Connection Bridges between nearby concepts
                val particles = currentParticles
                for (i in particles.indices) {
                    for (j in i + 1 until particles.size) {
                        val p1 = particles[i]
                        val p2 = particles[j]
                        val n1 = liveOffsets[p1.id] ?: Offset(p1.posX, p1.posY)
                        val n2 = liveOffsets[p2.id] ?: Offset(p2.posX, p2.posY)
                        val o1 = Offset(n1.x * w, n1.y * h)
                        val o2 = Offset(n2.x * w, n2.y * h)
                        val dist = hypot(o2.x - o1.x, o2.y - o1.y)
                        val maxInteractDist = minOf(w, h) * 0.68f

                        if (dist < maxInteractDist) {
                            val coupling = (1f - (dist / maxInteractDist)).coerceIn(0.15f, 1f)
                            val c1 = Color(p1.colorHex)
                            val c2 = Color(p2.colorHex)

                            drawLine(
                                brush = Brush.linearGradient(
                                    colors = listOf(
                                        c1.copy(alpha = coupling * 0.7f),
                                        c2.copy(alpha = coupling * 0.7f)
                                    ),
                                    start = o1,
                                    end = o2
                                ),
                                start = o1,
                                end = o2,
                                strokeWidth = (1.5f + coupling * 3f).dp.toPx()
                            )

                            if (coupling > 0.50f) {
                                val mid = Offset((o1.x + o2.x) / 2f, (o1.y + o2.y) / 2f)
                                drawCircle(
                                    color = c1.copy(alpha = 0.85f),
                                    radius = (4f + sin(wavePhase * 2f) * 2f).dp.toPx(),
                                    center = mid
                                )
                            }
                        }
                    }
                }

                // 4. Draw Each Sphere with Instantaneous Drag Response
                particles.forEachIndexed { idx, p ->
                    val normPos = liveOffsets[p.id] ?: Offset(p.posX, p.posY)
                    val pos = Offset(normPos.x * w, normPos.y * h)
                    val baseColor = Color(p.colorHex)
                    val isSelected = p.id == selectedParticle?.id || p.id == activeDraggingId
                    val pulse = sin(wavePhase * (1f + abs(p.spin) * 0.35f) + idx)
                    val coreRadius = (16f + p.mass * 6f).dp.toPx()
                    val haloRadius = coreRadius * (1.45f + pulse * 0.12f)

                    if (isSelected) {
                        drawCircle(
                            color = baseColor,
                            radius = haloRadius,
                            center = pos,
                            style = Stroke(width = 2.5.dp.toPx())
                        )
                    }

                    val orbitAngle = wavePhase * (if (p.spin >= 0f) 1.4f else -1.4f) + idx
                    val satOffset = Offset(
                        x = pos.x + cos(orbitAngle) * (coreRadius * 1.3f),
                        y = pos.y + sin(orbitAngle) * (coreRadius * 1.3f)
                    )
                    drawCircle(
                        color = baseColor.copy(alpha = 0.9f),
                        radius = 3.5.dp.toPx(),
                        center = satOffset
                    )

                    drawCircle(
                        color = baseColor,
                        radius = coreRadius,
                        center = pos
                    )

                    drawCircle(
                        color = canvasBg,
                        radius = coreRadius * 0.32f,
                        center = pos
                    )

                    val textLayout = textMeasurer.measure(
                        text = p.name,
                        style = TextStyle(
                            color = labelColor,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        )
                    )
                    drawText(
                        textLayoutResult = textLayout,
                        topLeft = Offset(
                            x = (pos.x - textLayout.size.width / 2f).coerceIn(8f, w - textLayout.size.width - 8f),
                            y = (pos.y + coreRadius + 6.dp.toPx()).coerceIn(8f, h - textLayout.size.height - 8f)
                        )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Action Row: Read State in Mirror + Reset Canvas
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = onCollideAndSynthesize,
                modifier = Modifier
                    .weight(1f)
                    .testTag("collide_synthesize_button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = strings.collideAndSynthesizeButton,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = strings.collideAndSynthesizeButton,
                    style = MaterialTheme.typography.titleSmall
                )
            }

            OutlinedButton(
                onClick = onResetParticles,
                modifier = Modifier.testTag("reset_field_button"),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = strings.clearFieldButton,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Selected Sphere Inspector with Plain-Language Explanations for Every Slider
        AnimatedVisibility(visible = selectedParticle != null) {
            selectedParticle?.let { particle ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("selected_particle_card"),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clip(CircleShape)
                                        .background(Color(particle.colorHex))
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = particle.name,
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            if (uiState.particles.size > 2) {
                                IconButton(
                                    onClick = { onRemoveParticle(particle.id) },
                                    modifier = Modifier.testTag("delete_particle_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DeleteOutline,
                                        contentDescription = "Delete",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = particle.philosophicalNote,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        // 1. Size & Importance Slider
                        Text(
                            text = "${strings.particleDetailMass}: ${(particle.mass * 40).toInt()}%",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = strings.particleDetailMassHelp,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Slider(
                            value = particle.mass,
                            onValueChange = { newMass ->
                                onUpdateParticleParams(particle, newMass, particle.charge, particle.frequencyHz)
                            },
                            valueRange = 0.4f..2.5f
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        // 2. Feeling Tone (Heavy/Tense <-> Light/Open) Slider
                        val feelingLabel = when {
                            particle.charge > 0.25f -> "Leve / Aberta"
                            particle.charge < -0.25f -> "Tensa / Pesada"
                            else -> "Neutra / Equilibrada"
                        }
                        Text(
                            text = "${strings.particleDetailCharge}: $feelingLabel",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = strings.particleDetailChargeHelp,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Slider(
                            value = particle.charge,
                            onValueChange = { newCharge ->
                                onUpdateParticleParams(particle, particle.mass, newCharge, particle.frequencyHz)
                            },
                            valueRange = -1.0f..1.0f
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        // 3. Musical Pitch Slider
                        Text(
                            text = "${strings.particleDetailFrequency}: ${particle.frequencyHz.toInt()} Hz",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = strings.particleDetailFrequencyHelp,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Slider(
                            value = particle.frequencyHz,
                            onValueChange = { newFreq ->
                                onUpdateParticleParams(particle, particle.mass, particle.charge, newFreq)
                            },
                            valueRange = 136f..852f
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Add Any Emotion or Abstract Concept Card (Highlighting the creative freedom!)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = strings.injectConceptLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = strings.injectConceptHelper,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = customConceptInput,
                        onValueChange = { customConceptInput = it },
                        placeholder = {
                            Text(
                                text = strings.injectConceptPlaceholder,
                                style = MaterialTheme.typography.bodySmall
                            )
                        },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("inject_concept_input")
                    )
                    Button(
                        onClick = {
                            if (customConceptInput.isNotBlank()) {
                                onInjectConcept(customConceptInput, "CUSTOM")
                                customConceptInput = ""
                            }
                        },
                        modifier = Modifier.testTag("inject_concept_button"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = strings.injectButton,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(strings.injectButton)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = strings.presetsLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))

                val presets = listOf(
                    "Arquitetura" to "CUSTOM",
                    "Colorimetria" to "CUSTOM",
                    "Ambiguidade" to "CUSTOM",
                    "Desejo" to "DESIRE",
                    "Medo" to "FEAR",
                    "Saudade" to "TIME",
                    "Liberdade" to "EMPTINESS",
                    "Foco" to "POWER",
                    "Equilíbrio" to "JUSTICE"
                )

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    presets.forEach { (label, key) ->
                        AssistChip(
                            onClick = { onInjectConcept(label, key) },
                            label = {
                                Text(
                                    text = "+ $label",
                                    style = MaterialTheme.typography.labelMedium
                                )
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        )
                    }
                }
            }
        }
    }
}

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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.ConceptParticleEntity
import com.example.ui.theme.ThemeMode
import com.example.ui.components.drawMentalSpaces
import com.example.ui.components.MentalSpacesInfographic
import com.example.ui.theme.computeSynestheticSphereColor
import com.example.ui.theme.formatConceptTitleCase
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
    onUpdateParticleImportanceAndHeaviness: (ConceptParticleEntity, Float, Float) -> Unit,
    onInjectConcept: (String, String) -> Unit,
    onRemoveParticle: (Long) -> Unit,
    onResetParticles: () -> Unit,
    onCollideAndSynthesize: () -> Unit,
    onStartNewSession: () -> Unit,
    onSelectZone: (String) -> Unit
) {
    val strings = uiState.strings
    var customConceptInput by remember { mutableStateOf("") }
    var showMetricsExplanation by remember { mutableStateOf(false) }

    val isDark = when (uiState.preferences.themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    // Zero-latency local coordinate map for buttery-smooth 60fps dragging
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

    // Animation state read ONLY inside draw scope (no per-frame recomposition).
    val infiniteTransition = rememberInfiniteTransition(label = "field_wave")
    val wavePhaseState = infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 7000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    val textMeasurer = rememberTextMeasurer()
    // Label layouts are expensive to measure: cache per (name, selected, color).
    val labelCache = remember { mutableMapOf<Triple<String, Boolean, Color>, TextLayoutResult>() }
    fun labelLayout(name: String, selected: Boolean, color: Color): TextLayoutResult {
        val key = Triple(name, selected, color)
        return labelCache.getOrPut(key) {
            textMeasurer.measure(
                text = name,
                style = TextStyle(
                    color = color,
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                )
            )
        }
    }

    val moodScore = uiState.meanHeaviness * 0.55f +
        uiState.preferences.bioArousal * 0.30f +
        (1f - uiState.phaseCoherence) * 0.15f
    val musicalMoodText = when {
        moodScore < 0.32f -> strings.soundMoodCalm
        moodScore < 0.52f -> strings.soundMoodDreamy
        moodScore < 0.72f -> strings.soundMoodVibrant
        else -> strings.soundMoodTense
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState(), enabled = activeDraggingId == null)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag("field_simulation_screen")
    ) {
        // Intro Header Card (No Binaural / No Mute Button — Media Notification handles playback)
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

        // Mental Spaces infographic (replica of Tab 2) ABOVE the mix field:
        // tap a room to learn what it means, then place spheres there.
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("spaces_infographic_card"),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = strings.fieldSpacesTitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = strings.fieldSpacesHelper,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                MentalSpacesInfographic(
                    zones = uiState.diagnosedZones,
                    selectedZoneId = uiState.selectedZoneId,
                    onSelectZone = onSelectZone,
                    phase = wavePhaseState.value,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(190.dp)
                )
                uiState.diagnosedZones.firstOrNull { it.zone.zoneId == uiState.selectedZoneId }?.let { dz ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = dz.zone.customTitle,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = dz.zone.conceptDomain,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = dz.zone.architecturalDescription,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Interactive Synesthetic Canvas with Split-Screen Scroll Frame Rails on Left & Right Edges (Item 3)
        val canvasBg = MaterialTheme.colorScheme.surface
        val gridLineColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
        val labelColor = MaterialTheme.colorScheme.onSurface

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(355.dp)
                .testTag("particle_field_canvas"),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = canvasBg),
            border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline)
        ) {
            var lastDragSpeed by remember { mutableStateOf(10f) }

            Box(modifier = Modifier.fillMaxSize()) {
                // Inner interactive area where spheres are dragged
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp, vertical = 12.dp)
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
                                    if (hypot(pos.x * w - offset.x, pos.y * h - offset.y) < 135f) {
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
                                        if (hypot(pos.x * w - startOffset.x, pos.y * h - startOffset.y) < 155f) {
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
                                    val id = activeDraggingId ?: return@detectDragGestures
                                    change.consume()
                                    val w = size.width.toFloat().coerceAtLeast(1f)
                                    val h = size.height.toFloat().coerceAtLeast(1f)
                                    val current = liveOffsets[id]
                                        ?: currentParticles.find { it.id == id }?.let { Offset(it.posX, it.posY) }
                                        ?: Offset(0.5f, 0.5f)
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

                    // 1. Synesthetic Background Color Aura (Dynamic Hue from Importance + Saturation from Heaviness)
                    currentParticles.forEach { p ->
                        val normPos = liveOffsets[p.id] ?: Offset(p.posX, p.posY)
                        val px = normPos.x * w
                        val py = normPos.y * h
                        val auraColor = computeSynestheticSphereColor(
                            importanceMass = p.mass,
                            heavinessWeight = p.charge,
                            isDarkTheme = isDark
                        )
                        val auraRadius = (105f + p.mass * 58f).dp.toPx()
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    auraColor.copy(alpha = 0.28f),
                                    auraColor.copy(alpha = 0.09f),
                                    Color.Transparent
                                ),
                                center = Offset(px, py),
                                radius = auraRadius
                            ),
                            radius = auraRadius,
                            center = Offset(px, py)
                        )
                    }

                    // Overlay with a subtle 15%-opacity replica of the Spaces map (no labels) so
    // the user always sees which room they are dropping a sphere into.
    drawMentalSpaces(
        zones = uiState.diagnosedZones,
        selectedZoneId = null,
        textMeasurer = textMeasurer,
        showText = false,
        alpha = 0.15f,
        phase = wavePhaseState.value
    )

    // 2. Subtle Horizon Rings
                    for (ring in 1..3) {
                        val radius = (minOf(w, h) * 0.15f * ring) + sin(wavePhaseState.value + ring) * 3f
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

                    // 3. Color & Sound Blending Bridges between nearby concepts
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
                            val maxInteractDist = minOf(w, h) * 0.72f

                            if (dist < maxInteractDist) {
                                val coupling = (1f - (dist / maxInteractDist)).coerceIn(0.15f, 1f)
                                val c1 = computeSynestheticSphereColor(p1.mass, p1.charge, isDark)
                                val c2 = computeSynestheticSphereColor(p2.mass, p2.charge, isDark)
                                val midColor = Color(
                                    red = (c1.red + c2.red) * 0.5f,
                                    green = (c1.green + c2.green) * 0.5f,
                                    blue = (c1.blue + c2.blue) * 0.5f,
                                    alpha = 1f
                                )

                                drawLine(
                                    brush = Brush.linearGradient(
                                        colors = listOf(
                                            c1.copy(alpha = coupling * 0.78f),
                                            midColor.copy(alpha = coupling * 0.85f),
                                            c2.copy(alpha = coupling * 0.78f)
                                        ),
                                        start = o1,
                                        end = o2
                                    ),
                                    start = o1,
                                    end = o2,
                                    strokeWidth = (1.8f + coupling * 3.5f).dp.toPx()
                                )

                                if (coupling > 0.45f) {
                                    val mid = Offset((o1.x + o2.x) / 2f, (o1.y + o2.y) / 2f)
                                    drawCircle(
                                        brush = Brush.radialGradient(
                                            colors = listOf(
                                                midColor.copy(alpha = coupling * 0.50f),
                                                Color.Transparent
                                            ),
                                            center = mid,
                                            radius = (26f * coupling).dp.toPx()
                                        ),
                                        radius = (26f * coupling).dp.toPx(),
                                        center = mid
                                    )
                                    drawCircle(
                                        color = midColor.copy(alpha = 0.90f),
                                        radius = (4.5f + sin(wavePhaseState.value * 2f) * 2f).dp.toPx(),
                                        center = mid
                                    )
                                }
                            }
                        }
                    }

                    // 4. Draw Each Sphere with Live Color Hue & Saturation
                    particles.forEachIndexed { idx, p ->
                        val normPos = liveOffsets[p.id] ?: Offset(p.posX, p.posY)
                        val pos = Offset(normPos.x * w, normPos.y * h)
                        val sphereColor = computeSynestheticSphereColor(
                            importanceMass = p.mass,
                            heavinessWeight = p.charge,
                            isDarkTheme = isDark
                        )
                        val isSelected = p.id == selectedParticle?.id || p.id == activeDraggingId
                        val pulse = sin(wavePhaseState.value * (1f + abs(p.spin) * 0.35f) + idx)
                        // Spheres are 40% smaller (room for many concepts); the
                        // emitted light keeps its original size.
                        val coreRadius = (9.6f + p.mass * 3.6f).dp.toPx()
                        val glowBase = (16f + p.mass * 6f).dp.toPx()
                        val haloRadius = glowBase * (1.45f + pulse * 0.12f)

                        if (isSelected) {
                            drawCircle(
                                color = sphereColor,
                                radius = haloRadius,
                                center = pos,
                                style = Stroke(width = 2.6.dp.toPx())
                            )
                        }

                        val orbitAngle = wavePhaseState.value * (if (p.spin >= 0f) 1.4f else -1.4f) + idx
                        val satOffset = Offset(
                            x = pos.x + cos(orbitAngle) * (coreRadius * 1.3f),
                            y = pos.y + sin(orbitAngle) * (coreRadius * 1.3f)
                        )
                        drawCircle(
                            color = sphereColor.copy(alpha = 0.9f),
                            radius = 3.5.dp.toPx(),
                            center = satOffset
                        )

                        drawCircle(
                            color = sphereColor,
                            radius = coreRadius,
                            center = pos
                        )

                        drawCircle(
                            color = canvasBg,
                            radius = coreRadius * 0.30f,
                            center = pos
                        )

                        val cleanLabel = formatConceptTitleCase(p.name)
                        val textLayout = labelLayout(cleanLabel, isSelected, labelColor)
                        drawText(
                            textLayoutResult = textLayout,
                            topLeft = Offset(
                                x = (pos.x - textLayout.size.width / 2f).coerceIn(6f, w - textLayout.size.width - 6f),
                                y = (pos.y + coreRadius + 6.dp.toPx()).coerceIn(6f, h - textLayout.size.height - 6f)
                            )
                        )
                    }
                }

                // Subtle Left & Right Frame Scroll Rails (Untouched by pointerInput so vertical scroll always works on the frame edges in Split-Screen!)
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .width(18.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.10f))
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .width(18.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.10f))
                )
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

        // Selected Sphere Inspector (2 Sliders Only: Importance/Hue & Sensation/Saturation; Pitch is Automatic!)
        AnimatedVisibility(visible = selectedParticle != null) {
            selectedParticle?.let { particle ->
                val liveSphereColor = computeSynestheticSphereColor(
                    importanceMass = particle.mass,
                    heavinessWeight = particle.charge,
                    isDarkTheme = isDark
                )

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("selected_particle_card"),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.5.dp, liveSphereColor.copy(alpha = 0.7f))
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
                                        .size(18.dp)
                                        .clip(CircleShape)
                                        .background(liveSphereColor)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = formatConceptTitleCase(particle.name),
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

                        // 1. Size & Importance Slider (Cold Blue -> Warm Red Hue)
                        val importancePct = (((particle.mass - 0.4f) / 2.1f) * 100f).toInt().coerceIn(0, 100)
                        Text(
                            text = "${strings.particleDetailMass}: $importancePct%",
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
                                onUpdateParticleImportanceAndHeaviness(particle, newMass, particle.charge)
                            },
                            valueRange = 0.4f..2.5f,
                            colors = SliderDefaults.colors(
                                thumbColor = liveSphereColor,
                                activeTrackColor = liveSphereColor
                            )
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // 2. Sensation Slider (0.0 = Leve [Left] <-> 1.0 = Pesada [Right])
                        val feelingLabel = when {
                            particle.charge < 0.35f -> "Leve / Suave (${(particle.charge * 100).toInt()}%)"
                            particle.charge > 0.65f -> "Pesada / Densa (${(particle.charge * 100).toInt()}%)"
                            else -> "Equilibrada (${(particle.charge * 100).toInt()}%)"
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
                            value = particle.charge.coerceIn(0f, 1f),
                            onValueChange = { newHeaviness ->
                                onUpdateParticleImportanceAndHeaviness(particle, particle.mass, newHeaviness)
                            },
                            valueRange = 0.0f..1.0f,
                            colors = SliderDefaults.colors(
                                thumbColor = liveSphereColor,
                                activeTrackColor = liveSphereColor
                            )
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Add Any Emotion or Abstract Concept Card (Auto Title Case enforced!)
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
                                onInjectConcept(formatConceptTitleCase(customConceptInput), "CUSTOM")
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

        Spacer(modifier = Modifier.height(14.dp))

        OutlinedButton(
            onClick = onStartNewSession,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("new_session_button_field"),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = strings.newSessionButton,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = strings.newSessionButton, color = MaterialTheme.colorScheme.primary)
        }
    }
}

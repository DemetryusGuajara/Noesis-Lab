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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
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
    onDragParticle: (ConceptParticleEntity, Float, Float, Float) -> Unit,
    onUpdateParticleParams: (ConceptParticleEntity, Float, Float, Float) -> Unit,
    onInjectConcept: (String, String) -> Unit,
    onRemoveParticle: (Long) -> Unit,
    onResetParticles: () -> Unit,
    onCollideAndSynthesize: () -> Unit,
    onToggleSound: (Boolean) -> Unit
) {
    val strings = uiState.strings
    var customConceptInput by remember { mutableStateOf("") }
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("field_simulation_screen")
    ) {
        // Editorial Hero Header Card with generated manifold artwork
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Image(
                    painter = painterResource(id = R.drawable.img_hero_manifold_1791688052417),
                    contentDescription = strings.fieldHeaderTitle,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(148.dp),
                    contentScale = ContentScale.Crop,
                    alpha = 0.42f
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(148.dp)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
                                )
                            )
                        )
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "NOESIS · PERCEPTUAL MANIFOLD",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            modifier = Modifier
                                .clickable { onToggleSound(!uiState.preferences.soundEnabled) }
                                .testTag("sound_toggle_chip")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (uiState.preferences.soundEnabled) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                                    contentDescription = if (uiState.preferences.soundEnabled) strings.soundToggleOn else strings.soundToggleOff,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (uiState.preferences.soundEnabled) strings.soundToggleOn else strings.soundToggleOff,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = strings.fieldHeaderTitle,
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = strings.fieldHeaderSubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Telemetry Strip (Phase Coherence, Cognitive Entropy, Harmonic Root)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MetricPill(
                label = strings.phaseCoherenceLabel,
                value = "${(uiState.phaseCoherence * 100).toInt()}%",
                modifier = Modifier.weight(1f)
            )
            MetricPill(
                label = strings.entropyIndexLabel,
                value = "${(uiState.entropyIndex * 100).toInt()}%",
                modifier = Modifier.weight(1f)
            )
            MetricPill(
                label = strings.resonantFrequencyLabel,
                value = "${uiState.meanFrequencyHz.toInt()} Hz",
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Interactive Quantum-Affective Particle Canvas
        val canvasBg = MaterialTheme.colorScheme.surface
        val gridLineColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
        val primaryAccent = MaterialTheme.colorScheme.primary
        val labelColor = MaterialTheme.colorScheme.onSurface

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(330.dp)
                .testTag("particle_field_canvas"),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = canvasBg),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            var draggingParticleId by remember { mutableStateOf<Long?>(null) }

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(uiState.particles) {
                        detectTapGestures { offset ->
                            val w = size.width.toFloat().coerceAtLeast(1f)
                            val h = size.height.toFloat().coerceAtLeast(1f)
                            val tapped = uiState.particles.minByOrNull { p ->
                                hypot(p.posX * w - offset.x, p.posY * h - offset.y)
                            }
                            if (tapped != null) {
                                val dist = hypot(tapped.posX * w - offset.x, tapped.posY * h - offset.y)
                                if (dist < 110f) {
                                    onSelectParticle(tapped)
                                }
                            }
                        }
                    }
                    .pointerInput(uiState.particles) {
                        detectDragGestures(
                            onDragStart = { startOffset ->
                                val w = size.width.toFloat().coerceAtLeast(1f)
                                val h = size.height.toFloat().coerceAtLeast(1f)
                                val nearest = uiState.particles.minByOrNull { p ->
                                    hypot(p.posX * w - startOffset.x, p.posY * h - startOffset.y)
                                }
                                if (nearest != null && hypot(nearest.posX * w - startOffset.x, nearest.posY * h - startOffset.y) < 120f) {
                                    draggingParticleId = nearest.id
                                    onSelectParticle(nearest)
                                }
                            },
                            onDragEnd = { draggingParticleId = null },
                            onDragCancel = { draggingParticleId = null },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val id = draggingParticleId ?: return@detectDragGestures
                                val target = uiState.particles.find { it.id == id } ?: return@detectDragGestures
                                val w = size.width.toFloat().coerceAtLeast(1f)
                                val h = size.height.toFloat().coerceAtLeast(1f)
                                val newX = (change.position.x / w).coerceIn(0.08f, 0.92f)
                                val newY = (change.position.y / h).coerceIn(0.10f, 0.90f)
                                val speed = hypot(dragAmount.x, dragAmount.y)
                                onDragParticle(target, newX, newY, speed)
                            }
                        )
                    }
            ) {
                val w = size.width
                val h = size.height
                val center = Offset(w / 2f, h / 2f)

                // Draw Subtle Topological Phase-Space Rings (Merleau-Ponty perceptual horizon)
                for (ring in 1..4) {
                    val radius = (minOf(w, h) * 0.12f * ring) + sin(wavePhase + ring) * 4f
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

                // Draw Deleuzian Rhizomatic Filaments & Wave-Particle Interference between concepts
                val particles = uiState.particles
                for (i in particles.indices) {
                    for (j in i + 1 until particles.size) {
                        val p1 = particles[i]
                        val p2 = particles[j]
                        val o1 = Offset(p1.posX * w, p1.posY * h)
                        val o2 = Offset(p2.posX * w, p2.posY * h)
                        val dist = hypot(o2.x - o1.x, o2.y - o1.y)
                        val maxInteractDist = minOf(w, h) * 0.72f

                        if (dist < maxInteractDist) {
                            val couplingStrength = (1f - (dist / maxInteractDist)).coerceIn(0.1f, 1f)
                            val sameSign = (p1.charge * p2.charge) >= 0f
                            val filamentColor = if (sameSign) {
                                primaryAccent.copy(alpha = couplingStrength * 0.55f)
                            } else {
                                Color(0xFF7A6F9B).copy(alpha = couplingStrength * 0.55f)
                            }

                            drawLine(
                                color = filamentColor,
                                start = o1,
                                end = o2,
                                strokeWidth = (1.5f + couplingStrength * 2.5f).dp.toPx(),
                                pathEffect = if (sameSign) null else PathEffect.dashPathEffect(floatArrayOf(12f, 8f), wavePhase * 10f)
                            )

                            // Midpoint standing wave node (collision locus)
                            if (couplingStrength > 0.52f) {
                                val mid = Offset((o1.x + o2.x) / 2f, (o1.y + o2.y) / 2f)
                                drawCircle(
                                    color = filamentColor.copy(alpha = 0.8f),
                                    radius = (4f + sin(wavePhase * 2f) * 2f).dp.toPx(),
                                    center = mid
                                )
                            }
                        }
                    }
                }

                // Draw Each Concept Particle with Orbital Spin & Harmonic Halo
                particles.forEachIndexed { idx, p ->
                    val pos = Offset(p.posX * w, p.posY * h)
                    val baseColor = Color(p.colorHex)
                    val isSelected = p.id == selectedParticle?.id
                    val pulse = sin(wavePhase * (1f + abs(p.spin) * 0.4f) + idx)
                    val coreRadius = (14f + p.mass * 6f).dp.toPx()
                    val haloRadius = coreRadius * (1.55f + pulse * 0.15f)

                    // Outer wave-particle probability cloud
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                baseColor.copy(alpha = if (isSelected) 0.48f else 0.28f),
                                Color.Transparent
                            ),
                            center = pos,
                            radius = haloRadius * 1.4f
                        ),
                        radius = haloRadius * 1.4f,
                        center = pos
                    )

                    // Selection resonance ring
                    if (isSelected) {
                        drawCircle(
                            color = baseColor,
                            radius = haloRadius,
                            center = pos,
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }

                    // Orbital spin satellite dot
                    val orbitAngle = wavePhase * (if (p.spin >= 0f) 1.5f else -1.5f) + idx
                    val satOffset = Offset(
                        x = pos.x + cos(orbitAngle) * (coreRadius * 1.35f),
                        y = pos.y + sin(orbitAngle) * (coreRadius * 1.35f)
                    )
                    drawCircle(
                        color = baseColor.copy(alpha = 0.9f),
                        radius = 3.5.dp.toPx(),
                        center = satOffset
                    )

                    // Particle Core
                    drawCircle(
                        color = baseColor,
                        radius = coreRadius,
                        center = pos
                    )

                    // Inner Sunyata emptiness aperture (Nagarjuna: form is emptiness)
                    drawCircle(
                        color = canvasBg,
                        radius = coreRadius * 0.34f,
                        center = pos
                    )

                    // Particle Name & Frequency Label
                    val textLayout = textMeasurer.measure(
                        text = "${p.name} · ${p.frequencyHz.toInt()}Hz",
                        style = TextStyle(
                            color = labelColor,
                            fontSize = 11.sp,
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

        // Action Row: Collide & Synthesize in Laboratory + Reset Field
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
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
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

        // Selected Particle Inspector & Real-time Parameter Sliders
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
                                        .size(14.dp)
                                        .clip(CircleShape)
                                        .background(Color(particle.colorHex))
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = particle.name,
                                        style = MaterialTheme.typography.titleLarge,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "ARCHETYPE · ${particle.archetypeKey}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            if (uiState.particles.size > 2) {
                                IconButton(
                                    onClick = { onRemoveParticle(particle.id) },
                                    modifier = Modifier.testTag("delete_particle_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DeleteOutline,
                                        contentDescription = "Delete particle",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = particle.philosophicalNote,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Mass Slider
                        Text(
                            text = "${strings.particleDetailMass}: ${"%.2f".format(particle.mass)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Slider(
                            value = particle.mass,
                            onValueChange = { newMass ->
                                onUpdateParticleParams(particle, newMass, particle.charge, particle.frequencyHz)
                            },
                            valueRange = 0.4f..2.5f
                        )

                        // Affective Charge / Valence Slider
                        Text(
                            text = "${strings.particleDetailCharge}: ${"%.2f".format(particle.charge)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Slider(
                            value = particle.charge,
                            onValueChange = { newCharge ->
                                onUpdateParticleParams(particle, particle.mass, newCharge, particle.frequencyHz)
                            },
                            valueRange = -1.0f..1.0f
                        )

                        // Neural / Acoustic Frequency Slider
                        Text(
                            text = "${strings.particleDetailFrequency}: ${particle.frequencyHz.toInt()} Hz",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface
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

        // Inject New Concept Particle or Archetype
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
                Spacer(modifier = Modifier.height(8.dp))
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
                    "Medo" to "FEAR",
                    "Desejo" to "DESIRE",
                    "Tempo" to "TIME",
                    "Identidade" to "IDENTITY",
                    "Justiça" to "JUSTICE",
                    "Dominação" to "DOMINATION",
                    "Poder" to "POWER",
                    "Raiva" to "ANGER",
                    "Śūnyatā (Vazio)" to "EMPTINESS"
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

@Composable
private fun MetricPill(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

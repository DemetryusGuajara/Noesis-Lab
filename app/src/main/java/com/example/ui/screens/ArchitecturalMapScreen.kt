package com.example.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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
import com.example.viewmodel.NoesisUiState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Tab 2: Automatic Diagnostic Map of Mental Spaces (Items 7, 8, 9):
 * - Single clean visual view (removed Planta dos Espaços / Relevo Emocional toggle).
 * - Read-only diagnostic indicators (no manual sliders or "Harmonizar" button).
 * - Automatically calculates Clarity/Openness and Pressure/Rigidity of each room
 *   from the spheres arranged in Tab 1 and explains what that empirically means in the user's psyche.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ArchitecturalMapScreen(
    uiState: NoesisUiState,
    onSelectZone: (String) -> Unit,
    onStartNewSession: () -> Unit
) {
    val strings = uiState.strings

    val selectedDiagnosed = uiState.diagnosedZones.find { it.zone.zoneId == uiState.selectedZoneId }
        ?: uiState.diagnosedZones.firstOrNull()

    val infiniteTransition = rememberInfiniteTransition(label = "arch_pulse")
    val archPhaseState = infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 9000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "arch_phase"
    )

    val textMeasurer = rememberTextMeasurer()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag("architectural_map_screen")
    ) {
        // Header Card with Automatic Diagnostic Explanation
        Card(
            modifier = Modifier.fillMaxWidth(),
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
                    Text(
                        text = strings.archHeaderTitle,
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Icon(
                        imageVector = Icons.Default.AccountBalance,
                        contentDescription = strings.archHeaderTitle,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = strings.archHeaderSubtitle,
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
                        text = strings.archQuickTip,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Interactive Mental Spaces Diagnostic Map Canvas (Single View)
        val surfaceColor = MaterialTheme.colorScheme.surface
        val outlineColor = MaterialTheme.colorScheme.outline
        val primaryColor = MaterialTheme.colorScheme.primary
        val secondaryColor = MaterialTheme.colorScheme.secondary
        val tertiaryColor = MaterialTheme.colorScheme.tertiary
        val onSurfaceColor = MaterialTheme.colorScheme.onSurface

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(340.dp)
                .testTag("architecture_canvas"),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = surfaceColor),
            border = BorderStroke(1.5.dp, outlineColor)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                        .pointerInput(uiState.diagnosedZones) {
                            detectTapGestures { tapOffset ->
                                val w = size.width.toFloat().coerceAtLeast(1f)
                                val h = size.height.toFloat().coerceAtLeast(1f)
                                val nearest = uiState.diagnosedZones.minByOrNull { dz ->
                                    hypot(dz.zone.mapX * w - tapOffset.x, dz.zone.mapY * h - tapOffset.y)
                                }
                                if (nearest != null && hypot(nearest.zone.mapX * w - tapOffset.x, nearest.zone.mapY * h - tapOffset.y) < 135f) {
                                    onSelectZone(nearest.zone.zoneId)
                                }
                            }
                        }
                ) {
                    val w = size.width
                    val h = size.height

                    val step = 38.dp.toPx()
                    var gx = step
                    while (gx < w) {
                        drawLine(
                            color = outlineColor.copy(alpha = 0.25f),
                            start = Offset(gx, 0f),
                            end = Offset(gx, h),
                            strokeWidth = 1f
                        )
                        gx += step
                    }
                    var gy = step
                    while (gy < h) {
                        drawLine(
                            color = outlineColor.copy(alpha = 0.25f),
                            start = Offset(0f, gy),
                            end = Offset(w, gy),
                            strokeWidth = 1f
                        )
                        gy += step
                    }

                    val zoneMap = uiState.diagnosedZones.associateBy { it.zone.zoneId }

                    uiState.diagnosedZones.forEach { dz ->
                        val zone = dz.zone
                        val start = Offset(zone.mapX * w, zone.mapY * h)
                        val targets = zone.connectedZonesCsv.split(",").map { it.trim() }
                        targets.forEach { targetId ->
                            val targetZone = zoneMap[targetId]?.zone
                            if (targetZone != null) {
                                val end = Offset(targetZone.mapX * w, targetZone.mapY * h)
                                drawLine(
                                    color = primaryColor.copy(alpha = 0.35f),
                                    start = start,
                                    end = end,
                                    strokeWidth = 2.dp.toPx(),
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f), archPhaseState.value * 8f)
                                )
                            }
                        }
                    }

                    uiState.diagnosedZones.forEach { dz ->
                        val zone = dz.zone
                        val center = Offset(zone.mapX * w, zone.mapY * h)
                        val isSelected = zone.zoneId == selectedDiagnosed?.zone?.zoneId
                        val chamberRadius = (22f + dz.diagnosedClarity * 14f).dp.toPx()
                        val strokeColor = when {
                            isSelected -> primaryColor
                            dz.diagnosedPressure > 0.62f -> Color(0xFFD14949)
                            dz.diagnosedClarity > 0.70f -> secondaryColor
                            else -> tertiaryColor
                        }

                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    strokeColor.copy(alpha = dz.diagnosedClarity * 0.35f),
                                    Color.Transparent
                                ),
                                center = center,
                                radius = chamberRadius * 1.6f
                            ),
                            radius = chamberRadius * 1.6f,
                            center = center
                        )

                        when (zone.geometryType) {
                            "LABYRINTH" -> {
                                for (r in 1..3) {
                                    val side = chamberRadius * (0.55f * r)
                                    drawRect(
                                        color = strokeColor.copy(alpha = 0.4f + (if (isSelected) 0.4f else 0f)),
                                        topLeft = Offset(center.x - side / 2f, center.y - side / 2f),
                                        size = Size(side, side),
                                        style = Stroke(width = if (isSelected && r == 3) 2.5.dp.toPx() else 1.5.dp.toPx())
                                    )
                                }
                            }
                            "ATRIUM" -> {
                                for (r in 1..3) {
                                    drawCircle(
                                        color = strokeColor.copy(alpha = 0.3f * r),
                                        radius = chamberRadius * (0.4f * r) + sin(archPhaseState.value) * 3f,
                                        center = center,
                                        style = Stroke(width = 2.dp.toPx())
                                    )
                                }
                            }
                            "TOWER", "LATTICE" -> {
                                val hexPath = Path()
                                for (i in 0..5) {
                                    val angle = (i * 60.0 + (if (isSelected) archPhaseState.value * 15.0 else 0.0)) * PI / 180.0
                                    val px = center.x + cos(angle).toFloat() * chamberRadius
                                    val py = center.y + sin(angle).toFloat() * chamberRadius
                                    if (i == 0) hexPath.moveTo(px, py) else hexPath.lineTo(px, py)
                                }
                                hexPath.close()
                                drawPath(
                                    path = hexPath,
                                    color = strokeColor,
                                    style = Stroke(width = if (isSelected) 3.dp.toPx() else 1.8.dp.toPx())
                                )
                            }
                            "MONOLITH" -> {
                                val tri = Path().apply {
                                    moveTo(center.x, center.y - chamberRadius)
                                    lineTo(center.x - chamberRadius * 0.9f, center.y + chamberRadius * 0.8f)
                                    lineTo(center.x + chamberRadius * 0.9f, center.y + chamberRadius * 0.8f)
                                    close()
                                }
                                drawPath(
                                    path = tri,
                                    color = strokeColor,
                                    style = Stroke(width = if (isSelected) 3.dp.toPx() else 2.dp.toPx())
                                )
                            }
                            else -> {
                                drawCircle(
                                    color = strokeColor,
                                    radius = chamberRadius,
                                    center = center,
                                    style = Stroke(
                                        width = if (isSelected) 2.8.dp.toPx() else 1.6.dp.toPx(),
                                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 6f), archPhaseState.value * 6f)
                                    )
                                )
                            }
                        }

                        val labelLayout = textMeasurer.measure(
                            text = zone.customTitle,
                            style = TextStyle(
                                color = onSurfaceColor,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        )
                        drawText(
                            textLayoutResult = labelLayout,
                            topLeft = Offset(
                                x = (center.x - labelLayout.size.width / 2f).coerceIn(6f, w - labelLayout.size.width - 6f),
                                y = (center.y + chamberRadius + 5.dp.toPx()).coerceIn(6f, h - labelLayout.size.height - 6f)
                            )
                        )
                    }
                }

                // Frame Scroll Rails for Split-Screen scrolling
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

        // Automatic Empirical Room Diagnosis Card (Read-Only Diagnostic Indicators + Contextual Meaning)
        selectedDiagnosed?.let { dz ->
            val zone = dz.zone
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("selected_zone_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = zone.customTitle,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = zone.conceptDomain,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = zone.architecturalDescription,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (dz.influencingConcepts.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            dz.influencingConcepts.forEach { concept ->
                                AssistChip(
                                    onClick = {},
                                    label = {
                                        Text(
                                            text = "Esfera Influente: $concept",
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Read-Only Diagnostic Indicator 1: Openness & Clarity
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = strings.archHorizonDepth,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${(dz.diagnosedClarity * 100).toInt()}%",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.secondary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { dz.diagnosedClarity },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = MaterialTheme.colorScheme.secondary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Read-Only Diagnostic Indicator 2: Accumulated Pressure / Rigidity
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = strings.archStructuralTension,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${(dz.diagnosedPressure * 100).toInt()}%",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { dz.diagnosedPressure },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    Spacer(modifier = Modifier.height(12.dp))

                    // Contextual Empirical Interpretation Box
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Psychology,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = strings.archEmpiricalInterpretationTitle,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            FormattedMarkdownText(
                                markdown = dz.empiricalInterpretation,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        OutlinedButton(
            onClick = onStartNewSession,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("new_session_button_arch"),
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

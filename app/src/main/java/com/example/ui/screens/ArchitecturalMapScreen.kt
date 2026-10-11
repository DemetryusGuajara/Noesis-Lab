package com.example.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import com.example.data.ArchitecturalZoneEntity
import com.example.viewmodel.NoesisUiState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

@Composable
fun ArchitecturalMapScreen(
    uiState: NoesisUiState,
    onSelectZone: (ArchitecturalZoneEntity) -> Unit,
    onTransmuteZone: (ArchitecturalZoneEntity, Float, Float) -> Unit,
    onMeditateInZone: (ArchitecturalZoneEntity) -> Unit
) {
    val strings = uiState.strings
    var isIsometricHorizonMode by remember { mutableStateOf(false) }

    val selectedZone = uiState.zones.find { it.zoneId == uiState.selectedZoneId }
        ?: uiState.zones.firstOrNull()

    val infiniteTransition = rememberInfiniteTransition(label = "arch_pulse")
    val archPhase by infiniteTransition.animateFloat(
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
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("architectural_map_screen")
    ) {
        // Header Card
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
                        text = "ARCHITECTURAL PHENOMENOLOGY",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Icon(
                        imageVector = Icons.Default.AccountBalance,
                        contentDescription = strings.archHeaderTitle,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = strings.archHeaderTitle,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = strings.archHeaderSubtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !isIsometricHorizonMode,
                        onClick = { isIsometricHorizonMode = false },
                        label = { Text(strings.archBlueprintMode) },
                        modifier = Modifier.testTag("blueprint_mode_chip")
                    )
                    FilterChip(
                        selected = isIsometricHorizonMode,
                        onClick = { isIsometricHorizonMode = true },
                        label = { Text(strings.archTopographyMode) },
                        modifier = Modifier.testTag("topography_mode_chip")
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Interactive Architectural Map Canvas
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
            border = BorderStroke(1.dp, outlineColor)
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(uiState.zones) {
                        detectTapGestures { tapOffset ->
                            val w = size.width.toFloat().coerceAtLeast(1f)
                            val h = size.height.toFloat().coerceAtLeast(1f)
                            val nearest = uiState.zones.minByOrNull { z ->
                                hypot(z.mapX * w - tapOffset.x, z.mapY * h - tapOffset.y)
                            }
                            if (nearest != null && hypot(nearest.mapX * w - tapOffset.x, nearest.mapY * h - tapOffset.y) < 120f) {
                                onSelectZone(nearest)
                            }
                        }
                    }
            ) {
                val w = size.width
                val h = size.height

                // Architectural Blueprint Grid / Horizon Lines
                val step = 36.dp.toPx()
                var gx = step
                while (gx < w) {
                    drawLine(
                        color = outlineColor.copy(alpha = 0.28f),
                        start = Offset(gx, 0f),
                        end = Offset(gx, h),
                        strokeWidth = 1f
                    )
                    gx += step
                }
                var gy = step
                while (gy < h) {
                    drawLine(
                        color = outlineColor.copy(alpha = 0.28f),
                        start = Offset(0f, gy),
                        end = Offset(w, gy),
                        strokeWidth = 1f
                    )
                    gy += step
                }

                val zoneMap = uiState.zones.associateBy { it.zoneId }

                // Draw Corridors / Bridges between connected architectural chambers
                uiState.zones.forEach { zone ->
                    val start = Offset(zone.mapX * w, zone.mapY * h)
                    val targets = zone.connectedZonesCsv.split(",").map { it.trim() }
                    targets.forEach { targetId ->
                        val targetZone = zoneMap[targetId]
                        if (targetZone != null) {
                            val end = Offset(targetZone.mapX * w, targetZone.mapY * h)
                            drawLine(
                                color = primaryColor.copy(alpha = 0.38f),
                                start = start,
                                end = end,
                                strokeWidth = 2.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f), archPhase * 8f)
                            )
                        }
                    }
                }

                // Draw each Architectural Chamber with distinct sacred/structural geometry
                uiState.zones.forEach { zone ->
                    val center = Offset(zone.mapX * w, zone.mapY * h)
                    val isSelected = zone.zoneId == selectedZone?.zoneId
                    val chamberRadius = (24f + zone.luminosity * 14f).dp.toPx()
                    val strokeColor = when {
                        isSelected -> primaryColor
                        zone.tensionIndex > 0.7f -> Color(0xFFD14949)
                        zone.elevationLevel > 0.4f -> tertiaryColor
                        else -> secondaryColor
                    }

                    // Horizon shadow / elevation projection in Isometric mode
                    if (isIsometricHorizonMode) {
                        val elevationOffset = -zone.elevationLevel * 22.dp.toPx()
                        drawLine(
                            color = strokeColor.copy(alpha = 0.45f),
                            start = center,
                            end = Offset(center.x, center.y + elevationOffset),
                            strokeWidth = 3.dp.toPx()
                        )
                    }

                    // Draw structural archetype shape
                    when (zone.geometryType) {
                        "LABYRINTH" -> {
                            // Concentric octagonal/square fortress walls (Crypt of Contraction)
                            for (r in 1..3) {
                                val side = chamberRadius * (0.55f * r)
                                drawRect(
                                    color = strokeColor.copy(alpha = 0.35f + (if (isSelected) 0.4f else 0f)),
                                    topLeft = Offset(center.x - side / 2f, center.y - side / 2f),
                                    size = Size(side, side),
                                    style = Stroke(width = if (isSelected && r == 3) 2.5.dp.toPx() else 1.5.dp.toPx())
                                )
                            }
                        }
                        "ATRIUM" -> {
                            // Expanding warm vaulted arches
                            for (r in 1..3) {
                                drawCircle(
                                    color = strokeColor.copy(alpha = 0.3f * r),
                                    radius = chamberRadius * (0.4f * r) + sin(archPhase) * 3f,
                                    center = center,
                                    style = Stroke(width = 2.dp.toPx())
                                )
                            }
                        }
                        "TOWER", "LATTICE" -> {
                            // Hexagonal geodesic sacred structure
                            val hexPath = Path()
                            for (i in 0..5) {
                                val angle = (i * 60.0 + (if (isSelected) archPhase * 15.0 else 0.0)) * PI / 180.0
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
                            // Triangular vector spire
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
                            // Spiral / Sunyata Open Garden
                            drawCircle(
                                color = strokeColor.copy(alpha = 0.22f),
                                radius = chamberRadius * 1.15f,
                                center = center
                            )
                            drawCircle(
                                color = strokeColor,
                                radius = chamberRadius,
                                center = center,
                                style = Stroke(
                                    width = if (isSelected) 2.8.dp.toPx() else 1.6.dp.toPx(),
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 6f), archPhase * 6f)
                                )
                            )
                        }
                    }

                    // Chamber Label
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
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Selected Architectural Chamber Detail & Morphing Controls
        selectedZone?.let { zone ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("selected_zone_card"),
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
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "CHAMBER · ${zone.geometryType} · ${zone.resonantHz.toInt()} Hz",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = zone.customTitle,
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = "Elev: ${if (zone.elevationLevel >= 0) "+" else ""}${"%.1f".format(zone.elevationLevel)}",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = zone.conceptDomain,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = zone.architecturalDescription,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Luminosity / Phenomenological Openness Slider
                    Text(
                        text = "${strings.archHorizonDepth}: ${(zone.luminosity * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Slider(
                        value = zone.luminosity,
                        onValueChange = { newLum ->
                            onTransmuteZone(zone, newLum, zone.tensionIndex)
                        },
                        valueRange = 0.15f..1.0f
                    )

                    // Structural Tectonic Tension Slider
                    Text(
                        text = "${strings.archStructuralTension}: ${(zone.tensionIndex * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Slider(
                        value = zone.tensionIndex,
                        onValueChange = { newTen ->
                            onTransmuteZone(zone, zone.luminosity, newTen)
                        },
                        valueRange = 0.05f..0.95f
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { onMeditateInZone(zone) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("transmute_zone_button"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoFixHigh,
                            contentDescription = strings.archTransmuteZoneButton,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(strings.archTransmuteZoneButton)
                    }
                }
            }
        }
    }
}

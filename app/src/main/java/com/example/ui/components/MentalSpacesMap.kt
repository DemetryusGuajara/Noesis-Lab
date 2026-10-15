package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.NoesisZoneColors
import com.example.viewmodel.DiagnosedZoneState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Shared draw of the Mental Spaces rooms.
 *
 * Faithful replica of the Tab 2 drawing (same shapes, same colors, same
 * anchors/sizes), so the infographic on Tab 1 and the map on Tab 2 read
 * as the same object. Normalized zone anchors match the Tab 1 sphere
 * coordinates, so the background overlay lines up with the drag field.
 *
 * @param showText room labels (infographic mode).
 * @param alpha overall opacity (0.35 for the in-field overlay).
 * @param scale multiplies room radius (overlay is drawn larger).
 */
fun DrawScope.drawMentalSpaces(
    zones: List<DiagnosedZoneState>,
    selectedZoneId: String?,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    showText: Boolean,
    alpha: Float,
    phase: Float,
    scale: Float = 1f
) {
    if (zones.isEmpty()) return
    val w = size.width
    val h = size.height
    val primary = NoesisZoneColors.primary
    val secondary = NoesisZoneColors.secondary
    val tertiary = NoesisZoneColors.tertiary
    val label = NoesisZoneColors.label

    zones.forEach { dz ->
        val zone = dz.zone
        val center = Offset(zone.mapX * w, zone.mapY * h)
        val isSelected = zone.zoneId == selectedZoneId
        val chamberRadius = (22f + dz.diagnosedClarity * 14f).dp.toPx() * scale
        val strokeColor = when {
            isSelected -> primary
            dz.diagnosedPressure > 0.62f -> NoesisZoneColors.pressure
            dz.diagnosedClarity > 0.70f -> secondary
            else -> tertiary
        }

        // Soft aura behind each room (same as Tab 2)
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
                        color = strokeColor.copy(alpha = (0.4f + (if (isSelected) 0.4f else 0f)) * alpha),
                        topLeft = Offset(center.x - side / 2f, center.y - side / 2f),
                        size = Size(side, side),
                        style = Stroke(
                            width = if (isSelected && r == 3) 2.5.dp.toPx() else 1.5.dp.toPx(),
                            join = StrokeJoin.Round
                        )
                    )
                }
            }
            "ATRIUM" -> {
                for (r in 1..3) {
                    drawCircle(
                        color = strokeColor.copy(alpha = 0.3f * r * alpha),
                        radius = chamberRadius * (0.4f * r) + sin(phase) * 3f,
                        center = center,
                        style = Stroke(width = 2.dp.toPx())
                    )
                }
            }
            "TOWER", "LATTICE" -> {
                val hexPath = Path()
                for (i in 0..5) {
                    val angle = (i * 60.0 + (if (isSelected) phase * 15.0 else 0.0)) * PI / 180.0
                    val px = center.x + cos(angle).toFloat() * chamberRadius
                    val py = center.y + sin(angle).toFloat() * chamberRadius
                    if (i == 0) hexPath.moveTo(px, py) else hexPath.lineTo(px, py)
                }
                hexPath.close()
                drawPath(
                    path = hexPath,
                    color = strokeColor.copy(alpha = alpha),
                    style = Stroke(
                        width = if (isSelected) 3.dp.toPx() else 1.8.dp.toPx(),
                        join = StrokeJoin.Round
                    )
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
                    color = strokeColor.copy(alpha = alpha),
                    style = Stroke(
                        width = if (isSelected) 3.dp.toPx() else 2.dp.toPx(),
                        join = StrokeJoin.Round
                    )
                )
            }
            else -> {
                drawCircle(
                    color = strokeColor.copy(alpha = alpha),
                    radius = chamberRadius,
                    center = center,
                    style = Stroke(
                        width = if (isSelected) 2.8.dp.toPx() else 1.6.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 6f), phase * 6f),
                        cap = StrokeCap.Round
                    )
                )
            }
        }

        if (showText) {
            val layout = textMeasurer.measure(
                text = zone.customTitle,
                style = TextStyle(
                    color = label,
                    fontSize = 10.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                )
            )
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(
                    x = (center.x - layout.size.width / 2f)
                        .coerceIn(2f, (w - layout.size.width - 2f).coerceAtLeast(2f)),
                    y = (center.y + chamberRadius + 4.dp.toPx())
                        .coerceIn(2f, (h - layout.size.height - 2f).coerceAtLeast(2f))
                )
            )
        }
    }
}

/**
 * Full infographic replica of the Tab 2 map placed ABOVE the Tab 1 mix field:
 * tappable rooms, labels, read-only meaning panel.
 */
@Composable
fun MentalSpacesInfographic(
    zones: List<DiagnosedZoneState>,
    selectedZoneId: String?,
    onSelectZone: (String) -> Unit,
    phase: Float,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    Canvas(modifier = modifier.pointerInput(zones) {
        detectTapGestures { tap ->
            val w = size.width.toFloat().coerceAtLeast(1f)
            val h = size.height.toFloat().coerceAtLeast(1f)
            val nearest = zones.minByOrNull { dz ->
                val dx = dz.zone.mapX * w - tap.x
                val dy = dz.zone.mapY * h - tap.y
                dx * dx + dy * dy
            }
            if (nearest != null) {
                val dx = nearest.zone.mapX * w - tap.x
                val dy = nearest.zone.mapY * h - tap.y
                val hit = (44f + nearest.diagnosedClarity * 14f) *
                    (size.width.toFloat() / 320f).coerceAtLeast(1f)
                if (dx * dx + dy * dy < hit * hit) {
                    onSelectZone(nearest.zone.zoneId)
                }
            }
        }
    }) {
        drawMentalSpaces(
            zones = zones,
            selectedZoneId = selectedZoneId,
            textMeasurer = textMeasurer,
            showText = true,
            alpha = 1f,
            phase = phase
        )
    }
}
package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.sp
import com.example.viewmodel.DiagnosedZoneState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Shared draw of the "Mental Spaces" infographic.
 * Zone anchors use the same normalized coordinates as the Tab 1 field, so the
 * overlay aligns exactly with where spheres are dragged.
 *
 * @param showText labels of rooms (full infographic mode).
 * @param alpha overall opacity (0.15 for the background overlay).
 */
fun DrawScope.drawMentalSpaces(
    zones: List<DiagnosedZoneState>,
    selectedZoneId: String?,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    showText: Boolean,
    alpha: Float,
    phase: Float
) {
    if (zones.isEmpty()) return
    val w = size.width
    val h = size.height

    // Connections between rooms
    val zoneMap = zones.associateBy { it.zone.zoneId }
    zones.forEach { dz ->
        val start = Offset(dz.zone.mapX * w, dz.zone.mapY * h)
        dz.zone.connectedZonesCsv.split(",").map { it.trim() }.forEach { targetId ->
            val target = zoneMap[targetId]?.zone ?: return@forEach
            drawLine(
                color = Color(0xFF8A7FD4).copy(alpha = 0.30f * alpha),
                start = start,
                end = Offset(target.mapX * w, target.mapY * h),
                strokeWidth = 1.4f,
                cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f, 6f), phase * 3f)
            )
        }
    }

    // Rooms
    zones.forEach { dz ->
        val zone = dz.zone
        val center = Offset(zone.mapX * w, zone.mapY * h)
        val isSelected = zone.zoneId == selectedZoneId
        val radius = 17f

        when (zone.geometryType) {
            "LABYRINTH" -> {
                for (r in 1..3) {
                    val side = radius * (0.62f * r)
                    drawRect(
                        color = Color(0xFF7A6F9B).copy(alpha = 0.55f * alpha),
                        topLeft = Offset(center.x - side / 2f, center.y - side / 2f),
                        size = androidx.compose.ui.geometry.Size(side, side),
                        style = Stroke(width = 1.2f, join = StrokeJoin.Round)
                    )
                }
            }
            "ATRIUM" -> {
                for (r in 1..3) {
                    drawCircle(
                        color = Color(0xFFD97757).copy(alpha = 0.45f * alpha),
                        radius = radius * (0.42f * r),
                        center = center,
                        style = Stroke(width = 1.2f)
                    )
                }
            }
            "TOWER", "LATTICE" -> {
                val path = Path()
                for (i in 0..5) {
                    val angle = (i * 60.0 + (if (isSelected) phase * 6.0 else 0.0)) * PI / 180.0
                    val px = center.x + cos(angle).toFloat() * radius
                    val py = center.y + sin(angle).toFloat() * radius
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                path.close()
                drawPath(
                    path = path,
                    color = Color(0xFF5A8F99).copy(alpha = 0.6f * alpha),
                    style = Stroke(width = if (isSelected) 2f else 1.3f, join = StrokeJoin.Round)
                )
            }
            "MONOLITH" -> {
                val tri = Path().apply {
                    moveTo(center.x, center.y - radius)
                    lineTo(center.x - radius * 0.9f, center.y + radius * 0.8f)
                    lineTo(center.x + radius * 0.9f, center.y + radius * 0.8f)
                    close()
                }
                drawPath(
                    path = tri,
                    color = Color(0xFFD4AF59).copy(alpha = 0.6f * alpha),
                    style = Stroke(width = if (isSelected) 2f else 1.4f, join = StrokeJoin.Round)
                )
            }
            else -> {
                drawCircle(
                    color = Color(0xFF6E8B74).copy(alpha = 0.6f * alpha),
                    radius = radius,
                    center = center,
                    style = Stroke(width = if (isSelected) 2f else 1.3f)
                )
            }
        }

        if (showText) {
            val layout = textMeasurer.measure(
                text = zone.customTitle,
                style = TextStyle(
                    color = Color(0xFF3A3550).copy(alpha = alpha),
                    fontSize = 9.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                )
            )
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(
                    x = (center.x - layout.size.width / 2f).coerceIn(2f, (w - layout.size.width - 2f).coerceAtLeast(2f)),
                    y = (center.y + radius + 3f).coerceIn(2f, (h - layout.size.height - 2f).coerceAtLeast(2f))
                )
            )
        }
    }
}

/**
 * Full infographic replica of Tab 2's map: tappable rooms, labels, read-only.
 * Lives ABOVE the mix field in Tab 1 to guide sphere placement.
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
                if (dx * dx + dy * dy < (60f * (w / 300f)).coerceAtLeast(900f)) {
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
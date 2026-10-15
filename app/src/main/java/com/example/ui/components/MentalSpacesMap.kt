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
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.viewmodel.DiagnosedZoneState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The single source of truth for the "Mental Spaces" drawing.
 *
 * BOTH the Tab 2 map and the Tab 1 infographic/overlay call this function,
 * so the two renderings are replicas by construction (same shapes, same
 * theme colors, same labels, same animations, same anchors). Colors are
 * always passed from the caller's MaterialTheme, never hardcoded.
 */
data class SpacesPalette(
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    val pressure: Color,
    val label: Color,
    val grid: Color
)

/**
 * @param showText room labels (off for the in-field overlay).
 * @param showGrid background grid (on for both maps, off for the overlay).
 * @param alpha overall opacity.
 * @param scale multiplies room radius only — never the layout, so the
 *        infographic can be smaller without being squashed.
 */
fun DrawScope.drawMentalSpaces(
    zones: List<DiagnosedZoneState>,
    selectedZoneId: String?,
    textMeasurer: TextMeasurer?,
    palette: SpacesPalette,
    showText: Boolean,
    showGrid: Boolean,
    alpha: Float,
    phase: Float,
    scale: Float = 1f
) {
    val w = size.width
    val h = size.height

    if (showGrid) {
        val step = 38.dp.toPx()
        var gx = step
        while (gx < w) {
            drawLine(
                color = palette.grid.copy(alpha = 0.25f * alpha),
                start = Offset(gx, 0f),
                end = Offset(gx, h),
                strokeWidth = 1f
            )
            gx += step
        }
        var gy = step
        while (gy < h) {
            drawLine(
                color = palette.grid.copy(alpha = 0.25f * alpha),
                start = Offset(0f, gy),
                end = Offset(w, gy),
                strokeWidth = 1f
            )
            gy += step
        }
    }

    zones.forEach { dz ->
        val zone = dz.zone
        val center = Offset(zone.mapX * w, zone.mapY * h)
        val isSelected = zone.zoneId == selectedZoneId
        val chamberRadius = (22f + dz.diagnosedClarity * 14f).dp.toPx() * scale
        val strokeColor = when {
            isSelected -> palette.primary
            dz.diagnosedPressure > 0.62f -> palette.pressure
            dz.diagnosedClarity > 0.70f -> palette.secondary
            else -> palette.tertiary
        }.copy(alpha = alpha)

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
                        color = strokeColor.copy(alpha = strokeColor.alpha * (0.4f + (if (isSelected) 0.4f else 0f))),
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
                        color = strokeColor.copy(alpha = strokeColor.alpha * 0.3f * r),
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
                    color = strokeColor,
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
                    color = strokeColor,
                    style = Stroke(
                        width = if (isSelected) 3.dp.toPx() else 2.dp.toPx(),
                        join = StrokeJoin.Round
                    )
                )
            }
            else -> {
                drawCircle(
                    color = strokeColor,
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

        if (showText && textMeasurer != null) {
            val layout: TextLayoutResult = textMeasurer.measure(
                text = zone.customTitle,
                style = TextStyle(
                    color = palette.label.copy(alpha = alpha),
                    fontSize = 11.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                )
            )
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(
                    x = (center.x - layout.size.width / 2f)
                        .coerceIn(6f, (w - layout.size.width - 6f).coerceAtLeast(6f)),
                    y = (center.y + chamberRadius + 5.dp.toPx())
                        .coerceIn(6f, (h - layout.size.height - 6f).coerceAtLeast(6f))
                )
            )
        }
    }
}

/** Theme colors for the spaces map, taken from the caller's MaterialTheme. */
@Composable
fun rememberSpacesPalette(): SpacesPalette {
    val cs = androidx.compose.material3.MaterialTheme.colorScheme
    return SpacesPalette(
        primary = cs.primary,
        secondary = cs.secondary,
        tertiary = cs.tertiary,
        pressure = Color(0xFFD14949),
        label = cs.onSurface,
        grid = cs.outline
    )
}

/**
 * Interactive replica of the Tab 2 map used above the Tab 1 mix field.
 * Same drawing routine, same palette, same proportions.
 */
@Composable
fun MentalSpacesInfographic(
    zones: List<DiagnosedZoneState>,
    selectedZoneId: String?,
    onSelectZone: (String) -> Unit,
    phase: Float,
    roomScale: Float = 1f,
    labelSizeScale: Float = 1f,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    val palette = rememberSpacesPalette()
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
                val hit = ((22f + nearest.diagnosedClarity * 14f) * roomScale + 14f) *
                    (size.width.toFloat() / 288f).coerceAtLeast(0.55f)
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
            palette = palette,
            showText = true,
            showGrid = true,
            alpha = 1f,
            phase = phase,
            scale = roomScale
        )
    }
}

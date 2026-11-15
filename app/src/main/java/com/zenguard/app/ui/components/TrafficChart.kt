package com.zenguard.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max

/**
 * MD3 Expressive live network traffic chart.
 * Renders animated smooth Bezier curves for download and upload throughput
 * with soft vertical gradient fills and dynamic auto-scaling.
 */
@Composable
fun TrafficChart(
    downlinkHistory: List<Long>,
    uplinkHistory: List<Long>,
    modifier: Modifier = Modifier,
    height: Dp = 160.dp,
    downlinkColor: Color = MaterialTheme.colorScheme.primary,
    uplinkColor: Color = MaterialTheme.colorScheme.tertiary
) {
    val currentDown = downlinkHistory.lastOrNull() ?: 0L
    val currentUp = uplinkHistory.lastOrNull() ?: 0L

    // Compute max peak across current window, with a minimum floor (128 KB/s) to prevent zero division
    val rawPeak = max(
        (downlinkHistory + uplinkHistory).maxOrNull() ?: 0L,
        128 * 1024L
    )

    val animatedPeak by animateFloatAsState(
        targetValue = rawPeak.toFloat(),
        animationSpec = tween(durationMillis = 600),
        label = "trafficPeakScale"
    )

    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Live Speed Indicators & Legend
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Downlink pill
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(downlinkColor)
                        )
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Rounded.ArrowDownward,
                            contentDescription = "Download",
                            tint = downlinkColor,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(2.dp))
                        Text(
                            text = formatTrafficSpeed(currentDown),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // Uplink pill
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(uplinkColor)
                        )
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Rounded.ArrowUpward,
                            contentDescription = "Upload",
                            tint = uplinkColor,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(2.dp))
                        Text(
                            text = formatTrafficSpeed(currentUp),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                // Peak scale indicator
                Text(
                    text = "Peak: ${formatTrafficSpeed(rawPeak)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(12.dp))

            // Canvas Chart Area
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(height)
            ) {
                val canvasWidth = size.width
                val canvasHeight = size.height
                val peak = if (animatedPeak > 0f) animatedPeak else 1f

                // Draw dashed grid guide lines (25%, 50%, 75%)
                val dashEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
                val gridSteps = 3
                for (i in 1..gridSteps) {
                    val y = canvasHeight * (i.toFloat() / (gridSteps + 1))
                    drawLine(
                        color = gridColor,
                        start = Offset(0f, y),
                        end = Offset(canvasWidth, y),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = dashEffect
                    )
                }

                // Render Downlink Layer (Soft gradient area fill + smooth Bezier curve line)
                if (downlinkHistory.isNotEmpty()) {
                    val downPoints = calculatePlotPoints(downlinkHistory, canvasWidth, canvasHeight, peak)
                    val downAreaPath = buildSmoothPath(downPoints, canvasWidth, canvasHeight, isClosed = true)
                    val downStrokePath = buildSmoothPath(downPoints, canvasWidth, canvasHeight, isClosed = false)

                    drawPath(
                        path = downAreaPath,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                downlinkColor.copy(alpha = 0.32f),
                                downlinkColor.copy(alpha = 0.02f)
                            ),
                            startY = 0f,
                            endY = canvasHeight
                        )
                    )

                    drawPath(
                        path = downStrokePath,
                        color = downlinkColor,
                        style = Stroke(
                            width = 2.5.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }

                // Render Uplink Layer (Soft gradient area fill + smooth Bezier curve line)
                if (uplinkHistory.isNotEmpty()) {
                    val upPoints = calculatePlotPoints(uplinkHistory, canvasWidth, canvasHeight, peak)
                    val upAreaPath = buildSmoothPath(upPoints, canvasWidth, canvasHeight, isClosed = true)
                    val upStrokePath = buildSmoothPath(upPoints, canvasWidth, canvasHeight, isClosed = false)

                    drawPath(
                        path = upAreaPath,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                uplinkColor.copy(alpha = 0.28f),
                                uplinkColor.copy(alpha = 0.02f)
                            ),
                            startY = 0f,
                            endY = canvasHeight
                        )
                    )

                    drawPath(
                        path = upStrokePath,
                        color = uplinkColor,
                        style = Stroke(
                            width = 2.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }
            }
        }
    }
}

/**
 * Computes canvas (x, y) coordinates for a series of byte values,
 * applying top/bottom margin padding to avoid curve clipping.
 */
private fun calculatePlotPoints(
    history: List<Long>,
    width: Float,
    height: Float,
    peak: Float
): List<Offset> {
    if (history.isEmpty()) return emptyList()
    val topPadding = height * 0.08f
    val bottomBaseline = height * 0.94f
    val usableHeight = bottomBaseline - topPadding

    if (history.size == 1) {
        val y = bottomBaseline - ((history[0].toFloat() / peak).coerceIn(0f, 1f) * usableHeight)
        return listOf(Offset(0f, y), Offset(width, y))
    }

    val stepX = width / (history.size - 1)
    return history.mapIndexed { index, value ->
        val x = index * stepX
        val normalized = (value.toFloat() / peak).coerceIn(0f, 1f)
        val y = bottomBaseline - (normalized * usableHeight)
        Offset(x, y)
    }
}

/**
 * Builds a smooth cubic Bezier path passing through the sample points.
 * When [isClosed] is true, completes the path around the bottom edge for gradient fill.
 */
private fun buildSmoothPath(
    points: List<Offset>,
    width: Float,
    height: Float,
    isClosed: Boolean
): Path {
    val path = Path()
    if (points.isEmpty()) return path

    if (points.size == 1) {
        val p = points[0]
        path.moveTo(0f, p.y)
        path.lineTo(width, p.y)
        if (isClosed) {
            path.lineTo(width, height)
            path.lineTo(0f, height)
            path.close()
        }
        return path
    }

    path.moveTo(points[0].x, points[0].y)

    for (i in 1 until points.size) {
        val prev = points[i - 1]
        val curr = points[i]
        // Horizontal tangent midpoint for gentle, fluid wave motion
        val cX = prev.x + (curr.x - prev.x) / 2f

        path.cubicTo(
            x1 = cX, y1 = prev.y,
            x2 = cX, y2 = curr.y,
            x3 = curr.x, y3 = curr.y
        )
    }

    if (isClosed) {
        path.lineTo(points.last().x, height)
        path.lineTo(points.first().x, height)
        path.close()
    }

    return path
}

fun formatTrafficSpeed(bps: Long): String {
    return when {
        bps >= 1_000_000_000 -> String.format("%.2f GB/s", bps / 1_000_000_000f)
        bps >= 1_000_000 -> String.format("%.1f MB/s", bps / 1_000_000f)
        bps >= 1_000 -> String.format("%.1f KB/s", bps / 1_000f)
        else -> "$bps B/s"
    }
}

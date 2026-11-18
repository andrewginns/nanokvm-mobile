package org.nanokvm.mobile.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * Activity indicator for connecting to a host. The adjacent status text owns accessibility.
 */
@Composable
internal fun ConnectionPulse(
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    val colors = MaterialTheme.colorScheme
    val phase = rememberInfiniteTransition(label = "connection")
        .animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 2_400, easing = LinearEasing)),
            label = "signal position",
        )

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Endpoint(Icons.Outlined.Smartphone)
        Canvas(Modifier.size(width = 46.dp, height = 32.dp)) {
            val start = Offset(2.dp.toPx(), center.y)
            val end = Offset(size.width - start.x, center.y)
            drawLine(colors.outline, start, end, strokeWidth = 1.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(colors.onSurfaceVariant, radius = 1.5.dp.toPx(), center = start)
            drawCircle(colors.onSurfaceVariant, radius = 1.5.dp.toPx(), center = end)

            // A sinusoid eases both reversals and leaves the pulse centered when duration scale is zero.
            val travel = (center.x - 6.dp.toPx()) * sin(phase.value * 2 * PI).toFloat()
            val pulse = center.copy(x = center.x + travel)
            drawCircle(accent.copy(alpha = 0.16f), radius = 6.dp.toPx(), center = pulse)
            drawCircle(accent, radius = 2.75.dp.toPx(), center = pulse)
        }
        Endpoint(Icons.Outlined.DesktopWindows)
    }
}

@Composable
private fun Endpoint(icon: ImageVector) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.size(32.dp),
        shape = MaterialTheme.shapes.small,
        color = colors.surface,
        border = BorderStroke(1.dp, colors.outline),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = colors.onSurfaceVariant,
            )
        }
    }
}

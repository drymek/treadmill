package com.craftersy.treadmill.workout.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.craftersy.treadmill.workout.format.PlannedStep
import com.craftersy.treadmill.workout.format.estimate

/** A rounded, slightly translucent card with a hairline border. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    corner: Dp = 28.dp,
    color: Color = Ink.surface,
    border: Color = Ink.outline,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(corner)
    Surface(
        modifier = modifier
            .clip(shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = shape,
        color = color,
        border = BorderStroke(1.dp, border),
        content = content,
    )
}

/** The workout's shape: one bar per step, as wide as its estimated time and as tall as its speed, in intensity colours. */
@Composable
fun ProfileChart(
    steps: List<PlannedStep>,
    modifier: Modifier = Modifier,
    /** Index of the running step; earlier steps are drawn as done, later ones dimmed. */
    current: Int = -1,
    /** Progress of the running step, 0–1. */
    progress: Float = 0f,
    gap: Dp = 2.dp,
    showIncline: Boolean = true,
) {
    Canvas(modifier) {
        if (steps.isEmpty()) return@Canvas
        val widths = steps.map { it.estimate().seconds.coerceAtLeast(1.0) }
        val total = widths.sum()
        val maxTenths = (steps.maxOf { it.speed.tenths }).coerceAtLeast(60)
        val minHeight = size.height * 0.18f
        val g = gap.toPx().coerceAtMost(size.width / steps.size / 3)
        val usable = size.width - g * (steps.size - 1)
        var x = 0f
        val inclinePoints = mutableListOf<Offset>()
        steps.forEachIndexed { i, step ->
            val w = (widths[i] / total * usable).toFloat()
            val h = minHeight + (size.height - minHeight) * step.speed.tenths / maxTenths
            val color = Ink.intensity(step.speed)
            val alpha = when {
                current < 0 -> 1f
                i < current -> 0.95f
                i == current -> 1f
                else -> 0.28f
            }
            val radius = CornerRadius(minOf(w / 2, 6.dp.toPx()))
            drawRoundRect(
                brush = Brush.verticalGradient(listOf(color.copy(alpha = alpha), color.copy(alpha = alpha * 0.55f)), startY = size.height - h, endY = size.height),
                topLeft = Offset(x, size.height - h),
                size = Size(w, h),
                cornerRadius = radius,
            )
            if (i == current && progress > 0f) {
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.22f),
                    topLeft = Offset(x, size.height - h),
                    size = Size(w * progress, h),
                    cornerRadius = radius,
                )
            }
            val y = size.height * (1f - 0.08f - 0.84f * step.incline.level / 22f)
            inclinePoints += Offset(x, y)
            inclinePoints += Offset(x + w, y)
            x += w + g
        }
        if (showIncline && steps.any { it.incline.level > 0 }) {
            val path = Path().apply {
                inclinePoints.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
            }
            // Incline yellow, as everywhere else, over a dark halo so it stands out on every bar colour.
            drawPath(path, Ink.background.copy(alpha = 0.6f), style = Stroke(width = 4.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(path, Ink.warning, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        if (current >= 0) {
            val done = widths.take(current).sum() + widths.getOrElse(current) { 0.0 } * progress
            val px = (done / total * usable).toFloat() + g * current.coerceAtMost(steps.size - 1)
            drawLine(Color.White, Offset(px, 0f), Offset(px, size.height), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(Color.White, radius = 4.dp.toPx(), center = Offset(px, 0f))
        }
    }
}

/** A small label above a value, e.g. "TIME" over "37:00". */
@Composable
fun Stat(label: String, value: String, modifier: Modifier = Modifier, icon: ImageVector? = null, tint: Color = Ink.textMuted) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, Modifier.size(13.dp), tint = tint)
                Spacer(Modifier.width(4.dp))
            }
            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = tint)
        }
        Spacer(Modifier.height(2.dp))
        Text(value, style = MaterialTheme.typography.titleLarge, color = Ink.text)
    }
}

/** A capsule with an icon and a short text. */
@Composable
fun Pill(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, color: Color = Ink.textMuted, background: Color = Ink.surfaceHigh) {
    Row(
        modifier
            .clip(CircleShape)
            .background(background)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(14.dp), tint = color)
            Spacer(Modifier.width(5.dp))
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

/** The main call to action: a lime capsule. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    color: Color = Ink.accent,
    contentColor: Color = Ink.onAccent,
) {
    val bg by animateColorAsState(if (enabled) color else Ink.surfaceHigher, label = "button")
    Row(
        modifier
            .height(52.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        val fg = if (enabled) contentColor else Ink.textFaint
        if (icon != null) {
            Icon(icon, null, Modifier.size(20.dp), tint = fg)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.titleMedium, color = fg)
    }
}

/** A quiet capsule button. */
@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, color: Color = Ink.text) {
    Row(
        modifier
            .height(48.dp)
            .clip(CircleShape)
            .background(Ink.surfaceHigh)
            .border(1.dp, Ink.outline, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(18.dp), tint = color)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.titleSmall, color = color)
    }
}

/** A round icon button on a surface. */
@Composable
fun RoundIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    tint: Color = Ink.text,
    background: Color = Ink.surfaceHigh,
    enabled: Boolean = true,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, Modifier.size(size * 0.46f), tint = if (enabled) tint else Ink.textFaint)
    }
}

/** A two- or three-way switch with a sliding highlight. */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(CircleShape)
            .background(Ink.background)
            .border(1.dp, Ink.outline, CircleShape)
            .padding(3.dp),
    ) {
        for ((value, label) in options) {
            val on = value == selected
            val bg by animateColorAsState(if (on) Ink.surfaceHigher else Color.Transparent, label = "segment")
            Box(
                Modifier
                    .weight(1f)
                    .height(36.dp)
                    .clip(CircleShape)
                    .background(bg)
                    .clickable(role = Role.Tab) { onSelect(value) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.titleSmall, color = if (on) Ink.text else Ink.textMuted)
            }
        }
    }
}

/** A ring that fills clockwise with [progress], in [color]. */
@Composable
fun ProgressRing(progress: Float, color: Color, modifier: Modifier = Modifier, stroke: Dp = 14.dp) {
    val animated by animateFloatAsState(progress, label = "ring")
    Canvas(modifier) {
        val w = stroke.toPx()
        val inset = w / 2
        val arcSize = Size(size.width - w, size.height - w)
        drawArc(Ink.surfaceHigher, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(w))
        if (animated <= 0f) return@Canvas
        // A sweep gradient starts at 3 o'clock; rotating the canvas makes it start at 12 like the arc, and the
        // stops stretch it over the filled part only, so it brightens smoothly towards the head with no seam.
        val dim = color.copy(alpha = 0.55f)
        rotate(-90f) {
            drawArc(
                brush = Brush.sweepGradient(0f to dim, animated.coerceIn(0.001f, 1f) to color, 1f to dim),
                startAngle = 0f,
                sweepAngle = 360f * animated,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(w, cap = StrokeCap.Round),
            )
        }
    }
}

/** A section heading in small caps. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(PaddingValues(start = 4.dp, end = 4.dp)), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = Ink.textMuted, modifier = Modifier.weight(1f))
        trailing()
    }
}

/** A coloured dot. */
@Composable
fun Dot(color: Color, modifier: Modifier = Modifier, size: Dp = 8.dp) {
    Box(modifier.size(size).clip(CircleShape).background(color))
}

@Composable
fun MutedText(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, style = MaterialTheme.typography.bodyMedium, color = Ink.textMuted, fontWeight = FontWeight.Normal)
}

package com.craftersy.treadmill.panel

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp

private const val DIGIT_WIDTH = 0.56f // of the height
private const val GAP = 0.12f
private const val DOT_WIDTH = 0.18f
private const val COLON_WIDTH = 0.22f

private fun widthOf(c: Char): Float = when (c) {
    '.' -> DOT_WIDTH
    ':' -> COLON_WIDTH + GAP
    else -> DIGIT_WIDTH + GAP
}

/**
 * Draws [text] (digits, '-', ' ', '.' and ':') as seven-segment LED digits.
 * A '.' lights the decimal point after the previous digit. Segments that are
 * off still glow faintly, like on the real console.
 */
@Composable
fun SevenSegment(text: String, height: Dp, on: Color, off: Color, modifier: Modifier = Modifier) {
    val units = text.sumOf { widthOf(it).toDouble() }.toFloat()
    Canvas(
        modifier
            .width(height * units)
            .height(height)
            .semantics { contentDescription = text },
    ) {
        val h = size.height
        var x = 0f
        for (c in text) {
            when (c) {
                '.' -> drawCircle(on, radius = h * 0.055f, center = Offset(x + h * 0.02f, h * 0.95f))
                ':' -> {
                    drawCircle(on, radius = h * 0.055f, center = Offset(x + h * COLON_WIDTH / 2, h * 0.32f))
                    drawCircle(on, radius = h * 0.055f, center = Offset(x + h * COLON_WIDTH / 2, h * 0.68f))
                }
                else -> drawDigit(segmentsOf(c), x + h * GAP / 2, h, on, off)
            }
            x += widthOf(c) * h
        }
    }
}

private fun DrawScope.drawDigit(lit: Int, left: Float, h: Float, on: Color, off: Color) {
    val w = h * DIGIT_WIDTH
    val t = h * 0.1f // segment thickness
    val mid = h / 2
    fun horizontal(y: Float) = Path().apply {
        moveTo(left + t * 0.6f, y)
        lineTo(left + t * 1.1f, y - t / 2)
        lineTo(left + w - t * 1.1f, y - t / 2)
        lineTo(left + w - t * 0.6f, y)
        lineTo(left + w - t * 1.1f, y + t / 2)
        lineTo(left + t * 1.1f, y + t / 2)
        close()
    }
    fun vertical(x: Float, top: Float, bottom: Float) = Path().apply {
        moveTo(x, top + t * 0.6f)
        lineTo(x + t / 2, top + t * 1.1f)
        lineTo(x + t / 2, bottom - t * 1.1f)
        lineTo(x, bottom - t * 0.6f)
        lineTo(x - t / 2, bottom - t * 1.1f)
        lineTo(x - t / 2, top + t * 1.1f)
        close()
    }
    val top = t / 2
    val bottom = h - t / 2
    val l = left + t / 2
    val r = left + w - t / 2
    val segments = listOf(
        SEG_A to horizontal(top),
        SEG_B to vertical(r, top, mid),
        SEG_C to vertical(r, mid, bottom),
        SEG_D to horizontal(bottom),
        SEG_E to vertical(l, mid, bottom),
        SEG_F to vertical(l, top, mid),
        SEG_G to horizontal(mid),
    )
    for ((bit, path) in segments) drawPath(path, if (lit and bit != 0) on else off)
}

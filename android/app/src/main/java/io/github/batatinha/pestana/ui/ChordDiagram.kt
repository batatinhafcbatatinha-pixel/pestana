package io.github.batatinha.pestana.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.batatinha.pestana.ChordShape

/** Guitar chord box: strings vertical (low E on the left), 5 frets. */
@Composable
fun ChordDiagram(name: String, width: Dp = 64.dp, leftHanded: Boolean = false, showName: Boolean = true) {
    val t = LocalReading.current
    val shape = remember(name) { ChordShape.of(name) }
    val measurer = rememberTextMeasurer()
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (showName) {
            Text(name, color = t.chord, fontWeight = FontWeight.Bold, fontSize = (width.value / 4.6f).sp, maxLines = 1)
        }
        Canvas(Modifier.size(width, width * 1.15f)) {
            val w = size.width
            val h = size.height
            val left = w * 0.16f
            val right = w * 0.9f
            val top = h * 0.16f
            val bottom = h * 0.97f
            val sx = (right - left) / 5f
            val fy = (bottom - top) / 5f
            val line = t.text.copy(alpha = 0.55f)
            if (shape == null) {
                drawText(measurer, "?", Offset(w / 2 - 6, h / 2 - 12), TextStyle(color = t.muted, fontSize = 18.sp))
                return@Canvas
            }
            val fretted = shape.frets.filter { it > 0 }
            val maxF = fretted.maxOrNull() ?: 0
            val base = if (maxF <= 5) 1 else (fretted.minOrNull() ?: 1)
            fun x(s: Int) = if (leftHanded) right - s * sx else left + s * sx
            // frets
            for (f in 0..5) {
                val y = top + f * fy
                drawLine(line, Offset(left, y), Offset(right, y), strokeWidth = if (f == 0 && base == 1) w * 0.045f else w * 0.012f)
            }
            for (s in 0..5) drawLine(line, Offset(x(s), top), Offset(x(s), bottom), strokeWidth = w * (0.01f + (5 - s) * 0.003f))
            if (base > 1) {
                drawText(measurer, "${base}ª", Offset(0f, top + fy * 0.15f), TextStyle(color = t.muted, fontSize = (w / 7.5f / density).sp))
            }
            val r = sx * 0.36f
            if (shape.barre > 0) {
                val y = top + (shape.barre - base + 0.5f) * fy
                val x1 = x(shape.barreFrom)
                val x2 = x(5)
                drawRoundRect(t.chord, Offset(minOf(x1, x2) - r, y - r), Size(kotlin.math.abs(x2 - x1) + 2 * r, 2 * r), CornerRadius(r))
            }
            shape.frets.forEachIndexed { s, f ->
                val cx = x(s)
                when {
                    f < 0 -> {
                        val m = r * 0.7f
                        val y = top - fy * 0.45f
                        drawLine(t.muted, Offset(cx - m, y - m), Offset(cx + m, y + m), strokeWidth = w * 0.02f)
                        drawLine(t.muted, Offset(cx - m, y + m), Offset(cx + m, y - m), strokeWidth = w * 0.02f)
                    }
                    f == 0 -> drawCircle(t.muted, r * 0.7f, Offset(cx, top - fy * 0.45f), style = Stroke(w * 0.018f))
                    f != shape.barre || s < shape.barreFrom -> drawCircle(t.chord, r, Offset(cx, top + (f - base + 0.5f) * fy))
                }
            }
        }
    }
}

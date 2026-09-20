package com.vw1980.bcm

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.dp

/** Silueta vectorial propia, escalable y adaptada a ambos temas. */
@Composable
fun VehicleIllustration() {
    val ink = MaterialTheme.colorScheme.onSurface
    val window = MaterialTheme.colorScheme.surfaceVariant
    Canvas(Modifier.fillMaxWidth().height(130.dp)) {
        scale(size.width / 320f, size.height / 140f, pivot = Offset.Zero) {
            drawOval(ink.copy(alpha = .08f), Offset(26f, 113f), Size(270f, 12f))
            val body = Path().apply {
                moveTo(25f, 103f); cubicTo(25f, 82f, 40f, 74f, 61f, 73f)
                cubicTo(83f, 26f, 109f, 20f, 142f, 23f)
                cubicTo(177f, 23f, 191f, 39f, 212f, 67f)
                cubicTo(258f, 67f, 284f, 79f, 294f, 99f)
                lineTo(290f, 110f); lineTo(26f, 110f); close()
            }
            drawPath(body, ink)
            val glass = Path().apply {
                moveTo(79f, 69f); cubicTo(94f, 36f, 110f, 32f, 132f, 33f)
                lineTo(132f, 68f); close()
                moveTo(140f, 33f); cubicTo(167f, 34f, 182f, 47f, 196f, 67f)
                lineTo(140f, 68f); close()
            }
            drawPath(glass, window)
            drawLine(window.copy(alpha = .6f), Offset(140f, 76f), Offset(140f, 100f), 1f)
            drawLine(window, Offset(149f, 79f), Offset(162f, 79f), 2f)
            listOf(77f, 242f).forEach { x ->
                drawCircle(window, 24f, Offset(x, 105f))
                drawCircle(ink, 20f, Offset(x, 105f))
                drawCircle(Color(0xFF929FB5), 11f, Offset(x, 105f))
                drawCircle(window, 6f, Offset(x, 105f), style = Stroke(2f))
            }
            drawOval(window, Offset(276f, 83f), Size(8f, 11f))
            drawLine(Color(0xFF929FB5), Offset(20f, 102f), Offset(41f, 102f), 4f)
            drawLine(Color(0xFF929FB5), Offset(276f, 106f), Offset(298f, 106f), 4f)
        }
    }
}

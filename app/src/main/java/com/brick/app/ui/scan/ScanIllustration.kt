package com.brick.app.ui.scan

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal val ScanIllustrationInk = Color(0xFF44505E)
internal val ScanIllustrationScreen = Color(0xFFCDD1D8)

/** Outlined blue-gray phone inside a circle, drawn with Canvas (no bitmap assets). */
@Composable
internal fun PhoneNearBrickIllustration(modifier: Modifier = Modifier, size: Dp = 94.dp) {
    Canvas(
        modifier = modifier
            .size(size)
            .semantics { contentDescription = "Phone held near the Brick" }
    ) {
        val s = this.size.minDimension
        val stroke = s * 0.038f
        drawCircle(
            color = ScanIllustrationInk,
            radius = (s - stroke) / 2f,
            style = Stroke(width = stroke)
        )
        val left = s * 0.29f
        val right = s * 0.71f
        val top = s * 0.27f
        val bottom = s * 0.93f
        val phone = Path().apply {
            moveTo(left + s * 0.01f, top + s * 0.06f)
            quadraticTo(left + s * 0.01f, top, left + s * 0.07f, top)
            lineTo(right - s * 0.07f, top)
            quadraticTo(right, top, right, top + s * 0.07f)
            lineTo(right + s * 0.01f, bottom - s * 0.08f)
            lineTo(left - s * 0.005f, bottom - s * 0.08f)
            close()
        }
        // screen shading: lower-left light triangle area
        val screen = Path().apply {
            moveTo(left + s * 0.03f, top + s * 0.20f)
            lineTo(right - s * 0.01f, bottom - s * 0.20f)
            lineTo(right + s * 0.0f, bottom - s * 0.12f)
            lineTo(left + s * 0.0f, bottom - s * 0.12f)
            close()
        }
        drawPath(screen, ScanIllustrationScreen)
        drawPath(
            phone,
            color = ScanIllustrationInk,
            style = Stroke(width = stroke * 0.8f, join = StrokeJoin.Round, cap = StrokeCap.Round)
        )
        drawRoundRect(
            color = ScanIllustrationInk,
            topLeft = Offset(s * 0.455f, top + s * 0.03f),
            size = Size(s * 0.09f, stroke * 0.8f),
            cornerRadius = CornerRadius(stroke)
        )
        // clip the lower part of the phone so it appears to sit inside the circle
        drawCircle(
            color = ScanIllustrationInk,
            radius = (s - stroke) / 2f,
            style = Stroke(width = stroke)
        )
    }
}

/** Public phone-near-Brick artwork; a caller-supplied size modifier overrides the 94dp default. */
@Composable
fun ScanIllustration(modifier: Modifier = Modifier) {
    PhoneNearBrickIllustration(modifier = modifier)
}

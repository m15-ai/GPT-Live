package com.m15.gptlive.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.*

/**
 * Animated audio-level blob visualizer — ported from Cliff.
 * Draws a set of animated circles whose radii breathe with [level] (0..1).
 */
@Composable
fun AudioBlobVisualizer(
    level: Float,
    modifier: Modifier = Modifier,
    accent: Color = Color.White,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "blob")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    val smoothLevel by animateFloatAsState(
        targetValue = level,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "smoothLevel"
    )

    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val baseRadius = minOf(size.width, size.height) * 0.25f
        val maxExtra  = minOf(size.width, size.height) * 0.20f

        drawBlob(cx, cy, baseRadius, maxExtra, smoothLevel, phase, accent, orbCount = 3)
    }
}

private fun DrawScope.drawBlob(
    cx: Float,
    cy: Float,
    baseRadius: Float,
    maxExtra: Float,
    level: Float,
    phase: Float,
    accent: Color,
    orbCount: Int,
) {
    for (i in 0 until orbCount) {
        val angle = phase + (2 * PI * i / orbCount).toFloat()
        val energy  = level * maxExtra
        val r = baseRadius + energy * sin(angle * 2 + i).toFloat().absoluteValue
        val orbitRadius = energy * 0.3f
        val ox = cx + cos(angle) * orbitRadius
        val oy = cy + sin(angle) * orbitRadius

        val alpha = (0.15f + level * 0.4f).coerceIn(0f, 0.6f)
        drawCircle(
            color = accent.copy(alpha = alpha),
            radius = r,
            center = Offset(ox, oy)
        )
    }

    // Core glow
    val coreAlpha = (0.08f + level * 0.25f).coerceIn(0f, 0.35f)
    drawCircle(
        color = accent.copy(alpha = coreAlpha),
        radius = baseRadius * (0.6f + level * 0.4f),
        center = Offset(cx, cy)
    )
}

package com.aguado.bratagame.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer

/** Animación de “baile” mientras se resuelve el descarte espontáneo (~3 s en el padre). */
fun Modifier.descarteEspontaneoBounce(activo: Boolean): Modifier = composed {
    if (!activo) return@composed Modifier
    val t = rememberInfiniteTransition(label = "espontaneoBounce")
    val wiggle by t.animateFloat(
        initialValue = -6f,
        targetValue = 6f,
        animationSpec = infiniteRepeatable(
            animation = tween(120, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "wiggle"
    )
    val pulse by t.animateFloat(
        initialValue = 1f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(180, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    Modifier.graphicsLayer {
        rotationZ = wiggle
        scaleX = pulse
        scaleY = pulse
    }
}

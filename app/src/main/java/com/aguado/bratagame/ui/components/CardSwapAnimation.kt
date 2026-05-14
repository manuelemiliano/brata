package com.aguado.bratagame.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import com.aguado.bratagame.CartaEnMesa

// ─────────────────────────────────────────────
// FASES DE LA ANIMACIÓN DE INTERCAMBIO
// ─────────────────────────────────────────────

private enum class FaseSwap {
    REPOSO,   // sin animación
    SALTO,    // ambas cartas saltan hacia arriba (3 segundos)
    CRUCE,    // las cartas se cruzan hacia su destino (600ms)
    COMPLETO  // animación terminada → llamar callback
}

// ─────────────────────────────────────────────
// CARD SWAP ANIMATION
//
// Función reutilizable para CUALQUIER intercambio en el juego:
//   poder CAMBIAR VIENDO, CAMBIAR SIN VER, o cualquier otro swap.
//
// Secuencia visual:
//   1. Ambas cartas saltan (offset Y negativo) — 3 segundos
//      para que todos los jugadores vean cuáles se van a cambiar
//   2. Las cartas se cruzan en arco hacia la posición contraria (600ms)
//   3. Callback onAnimacionCompleta → ejecutar la escritura en Firebase
//
// Parámetros:
//   cartaA / cartaB       → las dos cartas que se intercambian
//   mostrarValorA/B       → el jugador local ve sus propias cartas abiertas
//   onAnimacionCompleta   → se llama al final con los propietarios ya cruzados
//
// Uso desde GameTableScreen:
//   swapPendiente = cartaA to cartaB
//   → CardSwapAnimation se renderiza como overlay
//   → onAnimacionCompleta escribe en Firebase y limpia swapPendiente
// ─────────────────────────────────────────────

@Composable
fun CardSwapAnimation(
    cartaA: CartaEnMesa,
    cartaB: CartaEnMesa,
    mostrarValorA: Boolean = false,
    mostrarValorB: Boolean = false,
    onAnimacionCompleta: (nuevaA: CartaEnMesa, nuevaB: CartaEnMesa) -> Unit
) {
    var fase by remember { mutableStateOf(FaseSwap.SALTO) }

    var posicionA by remember { mutableStateOf(Offset.Zero) }
    var posicionB by remember { mutableStateOf(Offset.Zero) }

    // ── Animación de salto (ambas suben mientras el jugador decide) ──────

    val alturaJump = -28f

    val saltoA by animateFloatAsState(
        targetValue = if (fase == FaseSwap.SALTO || fase == FaseSwap.CRUCE) alturaJump else 0f,
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "saltoA"
    )
    val saltoB by animateFloatAsState(
        targetValue = if (fase == FaseSwap.SALTO || fase == FaseSwap.CRUCE) alturaJump else 0f,
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "saltoB"
    )

    // ── Animación de cruce (600ms) ────────────────────────────────────────

    val progresoSwap by animateFloatAsState(
        targetValue = if (fase == FaseSwap.CRUCE) 1f else 0f,
        animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing),
        finishedListener = { if (fase == FaseSwap.CRUCE) fase = FaseSwap.COMPLETO },
        label = "progresoSwap"
    )

    // ── Secuencia: SALTO (3s visibles) → CRUCE → COMPLETO ────────────────

    LaunchedEffect(Unit) {
        fase = FaseSwap.SALTO
        kotlinx.coroutines.delay(3_000L) // 3 segundos de salto para que todos vean
        fase = FaseSwap.CRUCE
    }

    // ── Callback al terminar el cruce ─────────────────────────────────────

    LaunchedEffect(fase) {
        if (fase == FaseSwap.COMPLETO) {
            val nuevaA = cartaA.copy(propietarioId = cartaB.propietarioId, posicion = cartaB.posicion)
            val nuevaB = cartaB.copy(propietarioId = cartaA.propietarioId, posicion = cartaA.posicion)
            onAnimacionCompleta(nuevaA, nuevaB)
        }
    }

    // ── Renderizado ───────────────────────────────────────────────────────

    Box(modifier = Modifier.fillMaxSize()) {

        // Carta A: se desplaza hacia la posición de B
        val offsetXA = (posicionB.x - posicionA.x) * progresoSwap
        val offsetYA = saltoA + (posicionB.y - posicionA.y) * progresoSwap

        Box(
            modifier = Modifier
                .onGloballyPositioned { posicionA = it.positionInRoot() }
                .graphicsLayer { translationX = offsetXA; translationY = offsetYA }
        ) {
            CartaVisual(
                abierta = mostrarValorA,
                valor = cartaA.carta.valor,
                palo = mappingPalo(cartaA.carta.palo)
            )
        }

        // Carta B: se desplaza hacia la posición de A
        val offsetXB = (posicionA.x - posicionB.x) * progresoSwap
        val offsetYB = saltoB + (posicionA.y - posicionB.y) * progresoSwap

        Box(
            modifier = Modifier
                .onGloballyPositioned { posicionB = it.positionInRoot() }
                .graphicsLayer { translationX = offsetXB; translationY = offsetYB }
        ) {
            CartaVisual(
                abierta = mostrarValorB,
                valor = cartaB.carta.valor,
                palo = mappingPalo(cartaB.carta.palo)
            )
        }
    }
}

// ─────────────────────────────────────────────
// ANIMACIÓN DE ESPÍA
// Carta que "salta" para indicar a todos que está siendo espiada.
// Se coloca sobre la carta en la mesa del jugador espiado.
// ─────────────────────────────────────────────

@Composable
fun CartaEspiandoOverlay(
    carta: CartaEnMesa,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "espiaLoop")
    val salto by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = -12f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "saltoContinuo"
    )

    Box(
        modifier = modifier.graphicsLayer { translationY = salto }
    ) {
        // La carta espiada se muestra cerrada para todos excepto
        // el jugador que espía (esa lógica está en GameTableScreen)
        CartaVisual(
            abierta = false,
            valor = carta.carta.valor,
            palo = mappingPalo(carta.carta.palo)
        )
    }
}
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
    SALTO,    // ambas cartas saltan hacia arriba
    CRUCE,    // las cartas se cruzan hacia su destino
    COMPLETO  // animación terminada → llamar callback
}

// ─────────────────────────────────────────────
// CARD SWAP ANIMATION
//
// Muestra dos cartas y anima su intercambio:
//   1. Ambas saltan (offset Y negativo)
//   2. Se desplazan cruzándose hacia la posición contraria
//   3. Llama onAnimacionCompleta con propietarios intercambiados
//
// Este composable se coloca como overlay sobre la mesa.
// GameTableScreen lo activa cuando Firebase reporta un intercambio.
// ─────────────────────────────────────────────

@Composable
fun CardSwapAnimation(
    cartaA: CartaEnMesa,
    cartaB: CartaEnMesa,
    // true = mostrar el valor de la carta (solo para el jugador local y observadores)
    mostrarValorA: Boolean = false,
    mostrarValorB: Boolean = false,
    onAnimacionCompleta: (nuevaA: CartaEnMesa, nuevaB: CartaEnMesa) -> Unit
) {
    var fase by remember { mutableStateOf(FaseSwap.SALTO) }

    // Posiciones absolutas en pantalla de cada carta (se capturan con onGloballyPositioned)
    var posicionA by remember { mutableStateOf(Offset.Zero) }
    var posicionB by remember { mutableStateOf(Offset.Zero) }

    // ── Animaciones ────────────────────────────

    // Salto: desplazamiento Y hacia arriba
    val saltoA by animateFloatAsState(
        targetValue = if (fase == FaseSwap.SALTO || fase == FaseSwap.CRUCE) -24f else 0f,
        animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
        label = "saltoA"
    )
    val saltoB by animateFloatAsState(
        targetValue = if (fase == FaseSwap.SALTO || fase == FaseSwap.CRUCE) -24f else 0f,
        animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
        label = "saltoB"
    )

    // Cruce: progreso 0→1 del desplazamiento hacia la posición contraria
    val progresoSwap by animateFloatAsState(
        targetValue = if (fase == FaseSwap.CRUCE) 1f else 0f,
        animationSpec = tween(durationMillis = 450, easing = FastOutSlowInEasing),
        finishedListener = {
            if (fase == FaseSwap.CRUCE) {
                fase = FaseSwap.COMPLETO
            }
        },
        label = "progresoSwap"
    )

    // Cuando el cruce termina → emitir callback con propietarios intercambiados
    LaunchedEffect(fase) {
        if (fase == FaseSwap.COMPLETO) {
            val nuevaA = cartaA.copy(
                propietarioId = cartaB.propietarioId,
                posicion = cartaB.posicion
            )
            val nuevaB = cartaB.copy(
                propietarioId = cartaA.propietarioId,
                posicion = cartaA.posicion
            )
            onAnimacionCompleta(nuevaA, nuevaB)
        }
    }

    // Iniciar secuencia: salto → esperar 250ms → cruce
    LaunchedEffect(Unit) {
        fase = FaseSwap.SALTO
        kotlinx.coroutines.delay(250)
        fase = FaseSwap.CRUCE
    }

    // ── Renderizado ────────────────────────────
    // Usamos Box con offset calculado a partir de las posiciones capturadas

    Box(modifier = Modifier.fillMaxSize()) {

        // Carta A
        val offsetXA = (posicionB.x - posicionA.x) * progresoSwap
        val offsetYA = saltoA + (posicionB.y - posicionA.y) * progresoSwap

        Box(
            modifier = Modifier
                .onGloballyPositioned { coords ->
                    posicionA = coords.positionInRoot()
                }
                .graphicsLayer {
                    translationX = offsetXA
                    translationY = offsetYA
                }
        ) {
            CartaVisual(
                abierta = mostrarValorA,
                valor = cartaA.carta.valor,
                palo = mappingPalo(cartaA.carta.palo)
            )
        }

        // Carta B
        val offsetXB = (posicionA.x - posicionB.x) * progresoSwap
        val offsetYB = saltoB + (posicionA.y - posicionB.y) * progresoSwap

        Box(
            modifier = Modifier
                .onGloballyPositioned { coords ->
                    posicionB = coords.positionInRoot()
                }
                .graphicsLayer {
                    translationX = offsetXB
                    translationY = offsetYB
                }
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
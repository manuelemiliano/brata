package com.aguado.bratagame.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.aguado.bratagame.CartaEnMesa
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition

object CardSwapAnimConfig {
    const val duracionSaltoEnCasillaMs: Long = 3_000L
    const val saltoSubidaMs: Int = 320
    const val saltoBajadaMs: Int = 320
    const val alturaSaltoPx: Float = -32f
    const val duracionCruceMs: Int = 1_600
    val easingCruce = LinearEasing
}

private enum class FaseSwap {
    ESPERANDO_POSICIONES,
    SALTO,
    CRUCE,
    COMPLETO
}

// ─────────────────────────────────────────────────────────────────────────────
// Convierte un desplazamiento "hacia arriba visualmente" al sistema de
// coordenadas del overlay (que no está rotado), teniendo en cuenta la
// rotación del área del jugador propietario de la carta.
//
// Si el jugador está a 0° (local, BottomCenter) → el salto es -Y puro.
// Si está a 180° (TopCenter) → el salto es +Y.
// Si está a 90° (lateral derecho) → el salto se convierte en -X.
// etc.
// ─────────────────────────────────────────────────────────────────────────────
private fun saltoPorRotacion(rotacionGrados: Float, magnitudPx: Float): Offset {
    val rad = Math.toRadians(rotacionGrados.toDouble())
    // "Arriba visual" de la carta rotada es la dirección (-sin θ, -cos θ) en
    // el sistema del overlay sin rotar.
    val dx = (-sin(rad) * magnitudPx).toFloat()
    val dy = (-cos(rad) * magnitudPx).toFloat()
    return Offset(dx, dy)
}

@Composable
fun CardSwapAnimation(
    cartaA: CartaEnMesa,
    cartaB: CartaEnMesa,
    mostrarValorA: Boolean = false,
    mostrarValorB: Boolean = false,
    onAnimacionCompleta: (nuevaA: CartaEnMesa, nuevaB: CartaEnMesa) -> Unit
) {
    val holder = LocalMesaCardPositions.current
    val density = LocalDensity.current
    val mediaCartaPx = remember(density) {
        with(density) { Offset(25.dp.toPx(), 35.dp.toPx()) }
    }

    var fase by remember { mutableStateOf(FaseSwap.ESPERANDO_POSICIONES) }

    val keyA = remember(cartaA) { MesaCardKey(cartaA.propietarioId, cartaA.posicion) }
    val keyB = remember(cartaB) { MesaCardKey(cartaB.propietarioId, cartaB.posicion) }

    // FIX: guardamos los centros en variables inmutables al inicio del salto
    // para que no cambien si el layout hace re-medición durante la animación.
    var centroA by remember { mutableStateOf<Offset?>(null) }
    var centroB by remember { mutableStateOf<Offset?>(null) }

    val reboteState = remember { mutableFloatStateOf(0f) }

    LaunchedEffect(holder, keyA, keyB) {
        if (holder == null) {
            fase = FaseSwap.COMPLETO
            return@LaunchedEffect
        }
        // FIX: leer centros congelados (capturados ANTES de ocultar las casillas)
        snapshotFlow {
            holder.frozenCenterOf(keyA) to holder.frozenCenterOf(keyB)
        }
            .distinctUntilChanged()
            .filter { it.first != null && it.second != null }
            .first()
            .also { (a, b) ->
                centroA = a
                centroB = b
            }

        kotlinx.coroutines.delay(16L)  // ~1 frame a 60fps
        fase = FaseSwap.SALTO
        reboteState.floatValue = 0f

        val finSalto = System.currentTimeMillis() + CardSwapAnimConfig.duracionSaltoEnCasillaMs
        val specSube = tween<Float>(
            durationMillis = CardSwapAnimConfig.saltoSubidaMs,
            easing = FastOutSlowInEasing
        )
        val specBaja = tween<Float>(
            durationMillis = CardSwapAnimConfig.saltoBajadaMs,
            easing = FastOutSlowInEasing
        )

        while (System.currentTimeMillis() < finSalto && isActive) {
            animate(
                initialValue = reboteState.floatValue,
                targetValue = CardSwapAnimConfig.alturaSaltoPx,
                animationSpec = specSube
            ) { v, _ -> reboteState.floatValue = v }
            if (System.currentTimeMillis() >= finSalto || !isActive) break
            animate(
                initialValue = reboteState.floatValue,
                targetValue = 0f,
                animationSpec = specBaja
            ) { v, _ -> reboteState.floatValue = v }
        }

        reboteState.floatValue = 0f
        fase = FaseSwap.CRUCE
    }

    val progresoSwap by animateFloatAsState(
        targetValue = if (fase == FaseSwap.CRUCE) 1f else 0f,
        animationSpec = tween(
            durationMillis = CardSwapAnimConfig.duracionCruceMs,
            easing = CardSwapAnimConfig.easingCruce
        ),
        finishedListener = { if (fase == FaseSwap.CRUCE) fase = FaseSwap.COMPLETO },
        label = "progresoSwap"
    )

    LaunchedEffect(fase) {
        if (fase == FaseSwap.COMPLETO) {
            holder?.clearFrozen(keyA, keyB)   // ← NUEVO: liberar snapshot
            val nuevaA = cartaA.copy(propietarioId = cartaB.propietarioId, posicion = cartaB.posicion)
            val nuevaB = cartaB.copy(propietarioId = cartaA.propietarioId, posicion = cartaA.posicion)
            onAnimacionCompleta(nuevaA, nuevaB)
        }
    }

    val startA = centroA
    val startB = centroB
    val magnitudSalto = reboteState.floatValue   // valor escalar (negativo = arriba local)

    // FIX: calcular el vector de salto en coordenadas del overlay para cada carta
    // usando la rotación del área del jugador propietario.
    val rotacionA = holder?.rotationOf(cartaA.propietarioId) ?: 0f
    val rotacionB = holder?.rotationOf(cartaB.propietarioId) ?: 0f
    val saltoVectorA = if (fase == FaseSwap.SALTO) saltoPorRotacion(rotacionA, magnitudSalto) else Offset.Zero
    val saltoVectorB = if (fase == FaseSwap.SALTO) saltoPorRotacion(rotacionB, magnitudSalto) else Offset.Zero

    val diffAB = shortestRotationDiff(rotacionA, rotacionB)
    val diffBA = shortestRotationDiff(rotacionB, rotacionA)

    var origenOverlayEnRaiz by remember { mutableStateOf<Offset?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { coords ->
                origenOverlayEnRaiz = coords.positionInRoot()
            }
    ) {
        val o = origenOverlayEnRaiz
        if (holder != null && o != null && startA != null && startB != null
            && fase != FaseSwap.ESPERANDO_POSICIONES
        ) {
            val ax = startA.x - mediaCartaPx.x - o.x
            val ay = startA.y - mediaCartaPx.y - o.y
            val bx = startB.x - mediaCartaPx.x - o.x
            val by_ = startB.y - mediaCartaPx.y - o.y

            val dx = startB.x - startA.x
            val dy = startB.y - startA.y

            // Carta A: parte de su casilla, salta en su propio eje "arriba", cruza a B
            Box(
                modifier = Modifier.graphicsLayer {
                    translationX = ax + saltoVectorA.x + dx * progresoSwap
                    translationY = ay + saltoVectorA.y + dy * progresoSwap
                    rotationZ = rotacionA + diffAB * progresoSwap
                }
            ) {
                CartaVisual(
                    abierta = mostrarValorA,
                    valor = cartaA.carta.valor,
                    palo = mappingPalo(cartaA.carta.palo)
                )
            }

            // Carta B: parte de su casilla, salta en su propio eje "arriba", cruza a A
            Box(
                modifier = Modifier.graphicsLayer {
                    translationX = bx + saltoVectorB.x - dx * progresoSwap
                    translationY = by_ + saltoVectorB.y - dy * progresoSwap
                    rotationZ = rotacionB + diffBA * progresoSwap
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
}

/** Diferencia angular firmada por el camino más corto, en [-180, 180]. */
private fun shortestRotationDiff(from: Float, to: Float): Float {
    var diff = (to - from) % 360f
    if (diff > 180f) diff -= 360f
    if (diff < -180f) diff += 360f
    return diff
}

// ─────────────────────────────────────────────
// ANIMACIÓN DE ESPÍA (sin cambios)
// ─────────────────────────────────────────────

@Composable
fun CartaEspiandoOverlay(
    carta: CartaEnMesa,
    modifier: Modifier = Modifier,
    revelarValor: Boolean = false
) {
    val infiniteTransition = rememberInfiniteTransition(label = "espiaLoop")

    // Aumentamos el desplazamiento de la animación.
    // El tiempo se conserva igual; solo se mueve más.
    val salto by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = -22f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "saltoContinuo"
    )

    Box(modifier = modifier) {
        CartaVisual(
            abierta = revelarValor,
            valor = carta.carta.valor,
            palo = mappingPalo(carta.carta.palo),
            modifier = Modifier.graphicsLayer {
                translationY = salto
            }
        )
    }
}
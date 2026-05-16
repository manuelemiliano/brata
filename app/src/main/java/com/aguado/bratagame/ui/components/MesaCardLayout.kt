package com.aguado.bratagame.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.geometry.Offset

/** Clave estable: jugador + casilla 0–3 del cuadrado 2×2. */
data class MesaCardKey(val jugadorId: String, val posicion: Int)

/**
 * Centros de cada casilla en coordenadas de la raíz (útil para animaciones overlay).
 * Se rellena desde [CuadradoCartasInteractivo]; la animación de swap lo consume.
 *
 * También guarda la rotación visual del área de cada jugador (0/90/180/270°)
 * para que la animación de intercambio pueda renderizar las cartas con la
 * orientación correcta en origen y destino.
 */
class MesaCardPositionHolder {
    internal val centers: SnapshotStateMap<MesaCardKey, Offset> = mutableStateMapOf()
    internal val rotations: SnapshotStateMap<String, Float> = mutableStateMapOf()
    // ── NUEVO: centros congelados al inicio del swap ──────────────
    private val frozenCenters: MutableMap<MesaCardKey, Offset> = mutableMapOf()

    fun updateCenter(key: MesaCardKey, center: Offset) {
        centers[key] = center
    }

    fun remove(key: MesaCardKey) {
        centers.remove(key)
        frozenCenters.remove(key)
    }

    fun centerOf(key: MesaCardKey): Offset? = centers[key]

    /** Congela los centros de las dos claves ANTES de que las casillas se oculten. */
    fun freezeCentersForSwap(keyA: MesaCardKey, keyB: MesaCardKey) {
        centers[keyA]?.let { frozenCenters[keyA] = it }
        centers[keyB]?.let { frozenCenters[keyB] = it }
    }

    /** Devuelve el centro congelado si existe, si no el live. */
    fun frozenCenterOf(key: MesaCardKey): Offset? =
        frozenCenters[key] ?: centers[key]

    fun clearFrozen(keyA: MesaCardKey, keyB: MesaCardKey) {
        frozenCenters.remove(keyA)
        frozenCenters.remove(keyB)
    }

    fun updateRotation(jugadorId: String, rotation: Float) {
        rotations[jugadorId] = rotation
    }

    fun rotationOf(jugadorId: String): Float = rotations[jugadorId] ?: 0f
}

val LocalMesaCardPositions = compositionLocalOf<MesaCardPositionHolder?> { null }

@Composable
fun ProvideMesaCardLayout(
    holder: MesaCardPositionHolder,
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalMesaCardPositions provides holder, content = content)
}

package com.aguado.bratagame.game

import com.aguado.bratagame.Carta
import com.aguado.bratagame.CartaEnMesa
import com.aguado.bratagame.CartaPoderActiva
import com.aguado.bratagame.Sala
import com.aguado.bratagame.TipoPoder

// ─────────────────────────────────────────────
// CARD POWER RESOLVER
//
// Responsabilidades:
//   - Determinar el estado actual de un poder activo
//   - Calcular qué cartas son seleccionables durante un poder
//   - Validar si una acción de poder es legal
//   - Detectar la regla del adelantado
//
// NO escribe en Firebase — eso lo hace GameActions.
// Solo produce información que GameTableScreen consume.
// ─────────────────────────────────────────────

object CardPowerResolver {

    // ─────────────────────────────────────────
    // ESTADO DEL PODER ACTIVO
    // GameTableScreen observa la sala y llama esto
    // para saber qué overlay mostrar.
    // ─────────────────────────────────────────

    data class EstadoPoder(
        val hayPoderActivo: Boolean,
        val tipoPoder: TipoPoder,
        val jugadorPoderId: String,          // ← nombre consistente en todo el archivo
        val esMiPoder: Boolean,
        val estaEspiando: Boolean,
        val cartaEspiandoId: String,
        val cartasSeleccionables: Set<String>
    )

    fun calcularEstadoPoder(
        jugadorLocalId: String,
        sala: Sala
    ): EstadoPoder {
        val poderActivo = sala.cartaPoderActiva

        if (poderActivo == null || poderActivo.jugadorId.isEmpty()) {
            return EstadoPoder(
                hayPoderActivo = false,
                tipoPoder = TipoPoder.NINGUNO,
                jugadorPoderId = "",
                esMiPoder = false,
                estaEspiando = false,
                cartaEspiandoId = "",
                cartasSeleccionables = emptySet()
            )
        }

        // TipoPoder viene de Firebase como String — convertir de forma segura
        val tipoPoder = parsearTipoPoder(poderActivo.tipoPoder.name)
        val esMiPoder = poderActivo.jugadorId == jugadorLocalId
        val estaEspiando = poderActivo.cartaEspiandoId.isNotEmpty()

        val seleccionables = calcularCartasSeleccionables(
            tipoPoder = tipoPoder,
            estaEspiando = estaEspiando,
            sala = sala
        )

        return EstadoPoder(
            hayPoderActivo = true,
            tipoPoder = tipoPoder,
            jugadorPoderId = poderActivo.jugadorId,
            esMiPoder = esMiPoder,
            estaEspiando = estaEspiando,
            cartaEspiandoId = poderActivo.cartaEspiandoId,
            cartasSeleccionables = seleccionables
        )
    }

    // ─────────────────────────────────────────
    // CARTAS SELECCIONABLES SEGÚN PODER
    // ─────────────────────────────────────────

    private fun calcularCartasSeleccionables(
        tipoPoder: TipoPoder,
        estaEspiando: Boolean,
        sala: Sala
    ): Set<String> {
        // Si ya está espiando una carta, no puede seleccionar más
        if (estaEspiando) return emptySet()

        return when (tipoPoder) {
            TipoPoder.ESPIAR,
            TipoPoder.CAMBIAR_VIENDO,
            TipoPoder.CAMBIAR_SIN_VER -> {
                sala.jugadores.values
                    .flatMap { it.cartas }
                    .map { it.id }
                    .toSet()
            }
            TipoPoder.DESCARTE_FREE_SELECCION -> {
                // Solo las cartas propias del jugador que activó el descarte free
                sala.jugadores[sala.cartaPoderActiva?.jugadorId]
                    ?.cartas
                    ?.map { it.id }
                    ?.toSet() ?: emptySet()
            }
            TipoPoder.NINGUNO -> emptySet()
        }
    }

    // ─────────────────────────────────────────
    // DESCARTE ESPONTÁNEO VÁLIDO
    // Un jugador puede descartar en cualquier momento
    // si su carta coincide con la cima del descarte.
    // ─────────────────────────────────────────

    fun puedeDescartarEspontaneo(
        carta: Carta,
        sala: Sala
    ): Boolean {
        val cima = sala.mazoDescarte.lastOrNull() ?: return false
        return carta.valor == cima.valor
    }

    // ─────────────────────────────────────────
    // DETECTAR ADELANTADO
    // Cuando hay un poder activo de espía y alguien descarta,
    // el jugador en turno debe ser notificado.
    // ─────────────────────────────────────────

    data class SituacionAdelantado(
        val hayAdelantado: Boolean,
        val adelantadoId: String,
        val jugadorPerjudicadoId: String
    )

    fun detectarAdelantado(
        jugadorQueDescartaId: String,
        sala: Sala
    ): SituacionAdelantado {
        val poderActivo = sala.cartaPoderActiva
            ?: return SituacionAdelantado(false, "", "")

        val tipoPoder = parsearTipoPoder(poderActivo.tipoPoder.name)

        // Solo aplica si hay poder espía activo
        val hayPoderEspia = tipoPoder == TipoPoder.ESPIAR ||
                tipoPoder == TipoPoder.CAMBIAR_VIENDO

        // La regla NO aplica si el jugador ya está espiando una carta concreta
        val yaEspiando = poderActivo.cartaEspiandoId.isNotEmpty()

        if (!hayPoderEspia || yaEspiando) {
            return SituacionAdelantado(false, "", "")
        }

        val jugadorDelPoder = poderActivo.jugadorId
        val esAdelantado = jugadorQueDescartaId != jugadorDelPoder

        return SituacionAdelantado(
            hayAdelantado = esAdelantado,
            adelantadoId = if (esAdelantado) jugadorQueDescartaId else "",
            jugadorPerjudicadoId = if (esAdelantado) jugadorDelPoder else ""
        )
    }

    // ─────────────────────────────────────────
    // VALIDAR COMODÍN ROBABLE DEL DESCARTE
    // El comodín del descarte solo puede ser robado
    // si perteneció al jugador inmediato anterior.
    // ─────────────────────────────────────────

    fun esComodinRobableDelDescarte(
        cartaDescarte: Carta,
        jugadorAnteriorId: String,
        propietarioOriginalId: String
    ): Boolean {
        return cartaDescarte.valor == "JKR" &&
                propietarioOriginalId == jugadorAnteriorId
    }

    // ─────────────────────────────────────────
    // HELPER: parsear TipoPoder desde String
    // Firebase serializa los enums como String.
    // Esta función evita crashes por valores inesperados.
    // ─────────────────────────────────────────

    private fun parsearTipoPoder(valor: String): TipoPoder {
        return try {
            TipoPoder.valueOf(valor)
        } catch (e: IllegalArgumentException) {
            TipoPoder.NINGUNO
        }
    }
}
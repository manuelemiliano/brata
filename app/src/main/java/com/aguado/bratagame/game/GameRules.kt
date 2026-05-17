package com.aguado.bratagame.game

import com.aguado.bratagame.Carta
import com.aguado.bratagame.Sala
import com.aguado.bratagame.TipoPoder
import com.aguado.bratagame.esSlotVacio

// ─────────────────────────────────────────────
// ACCIONES DISPONIBLES EN EL PANEL DE MANO
// El componente HandPanel recibe una lista de estas
// y renderiza exactamente esos botones, sin lógica propia.
// ─────────────────────────────────────────────

enum class AccionMano {
    CAMBIAR,
    DESCARTAR,
    ACTIVAR_PODER,
    DESCARTAR_FREE,
    SELECCIONAR_COMODIN,
    REGRESAR,
    TOMAR,

    // Regla del adelantado durante carta espía pendiente.
    ROBAR_DESCARTE
}

// ─────────────────────────────────────────────
// GAME RULES
// Funciones puras: reciben datos, devuelven datos.
// No conocen Firebase, no conocen Compose.
// ─────────────────────────────────────────────

object GameRules {

    // ── Número de barajas según jugadores ──────

    fun numeroDeBarajas(totalJugadores: Int): Int =
        if (totalJugadores >= 5) 2 else 1

    // ── Tipo de poder según valor de carta ─────

    fun obtenerPoder(carta: Carta): TipoPoder {
        return when (carta.valor.uppercase()) {
            "2", "3", "4", "K" -> TipoPoder.NINGUNO
            "5", "6", "7", "8", "9", "10" -> TipoPoder.ESPIAR
            "A" -> TipoPoder.CAMBIAR_VIENDO
            "J", "Q" -> TipoPoder.CAMBIAR_SIN_VER
            "JKR" -> TipoPoder.NINGUNO // El comodín tiene su propia regla
            else -> TipoPoder.NINGUNO
        }
    }

    // ── Botones disponibles en HandPanel ───────
    // Recibe la carta en mano y el contexto de la mesa,
    // devuelve exactamente los botones que debe mostrar HandPanel.

    fun accionesDisponibles(
        cartaEnMano: Carta,
        ultimaCartaDescarte: Carta?,
        segundaCartaDescarte: Carta?,   // la que está debajo de la última
        esComodinPropio: Boolean        // true si se robó del descarte siendo comodín
    ): List<AccionMano> {

        // Caso especial: COMODÍN robado del pozo normal
        if (cartaEnMano.valor == "JKR" && !esComodinPropio) {
            return listOf(AccionMano.SELECCIONAR_COMODIN)
        }

        // Caso especial: COMODÍN robado del descarte (comodín propio)
        if (cartaEnMano.valor == "JKR" && esComodinPropio) {
            return listOf(AccionMano.SELECCIONAR_COMODIN)
        }

        val acciones = mutableListOf<AccionMano>()

        // Siempre disponibles para cartas normales
        acciones.add(AccionMano.DESCARTAR)
        acciones.add(AccionMano.CAMBIAR)

        // Agregar poder si la carta lo tiene
        val poder = obtenerPoder(cartaEnMano)
        if (poder != TipoPoder.NINGUNO) {
            acciones.add(AccionMano.ACTIVAR_PODER)
        }

        // ── Regla de descarte free ──────────────

        // Caso 1: La carta robada es igual a la última del descarte
        if (ultimaCartaDescarte != null &&
            cartaEnMano.valor == ultimaCartaDescarte.valor) {
            acciones.add(AccionMano.DESCARTAR_FREE)
        }

        // Caso 2: Hay dos cartas consecutivas en el descarte
        // y la carta robada es consecutiva ascendente o descendente
        if (ultimaCartaDescarte != null && segundaCartaDescarte != null) {
            val valorUltima = valorNumerico(ultimaCartaDescarte.valor)
            val valorSegunda = valorNumerico(segundaCartaDescarte.valor)
            val valorRobada = valorNumerico(cartaEnMano.valor)

            val descarteSonConsecutivos =
                valorUltima != null && valorSegunda != null &&
                        (valorUltima - valorSegunda == 1 || valorSegunda - valorUltima == 1)

            val robadaEsConsecutiva =
                valorRobada != null && valorUltima != null &&
                        (valorRobada - valorUltima == 1 || valorUltima - valorRobada == 1)

            if (descarteSonConsecutivos && robadaEsConsecutiva &&
                !acciones.contains(AccionMano.DESCARTAR_FREE)) {
                acciones.add(AccionMano.DESCARTAR_FREE)
            }
        }

        return acciones
    }

    // ── Acciones durante espía (ESPIAR / CAMBIAR_VIENDO) ──

    fun accionesDuranteEspia(tipoPoder: TipoPoder): List<AccionMano> {
        return when (tipoPoder) {
            TipoPoder.ESPIAR -> listOf(AccionMano.REGRESAR)
            TipoPoder.CAMBIAR_VIENDO -> listOf(AccionMano.REGRESAR, AccionMano.CAMBIAR)
            else -> emptyList()
        }
    }

    // ── Robo desde la cima del descarte ─
    // La cima solo es robable si quien la puso en la pila fue el jugador inmediato anterior
    // (orden de [Sala.jugadores]) y la descartó desde sus 4 cartas de juego en mesa.

    fun jugadorInmediatoAnterior(jugadorId: String, sala: Sala): String? {
        val jugadores = sala.jugadores.keys.toList()
        if (jugadores.isEmpty()) return null
        val idx = jugadores.indexOf(jugadorId)
        if (idx < 0) return null
        val idxAnt = (idx - 1 + jugadores.size) % jugadores.size
        return jugadores[idxAnt]
    }

    fun cimaDelDescartePuedeRobarla(jugadorRobadorId: String, cima: Carta, sala: Sala): Boolean {
        val anterior = jugadorInmediatoAnterior(jugadorRobadorId, sala) ?: return false
        if (cima.descartadaPorJugadorId.isEmpty()) return false
        return cima.descartadaPorJugadorId == anterior && cima.descartadaDesdeJuegoMesa
    }

    // ── Puntuación de una mano ─────────────────

    fun calcularPuntuacion(cartas: List<Carta>): Int {
        val cartasReales = cartas.filterNot { it.esSlotVacio() }

        // Regla de palos: 4 cartas del mismo palo → 0 puntos
        if (cartasReales.size >= 4 && cartasReales.map { it.palo }.toSet().size == 1) return 0

        // Regla de números: los 4 iguales → 0 puntos
        if (cartasReales.size >= 4 && cartasReales.map { it.valor }.toSet().size == 1) return 0

        // Regla de reyes rojos: los dos reyes rojos juntos valen 0 entre ellos
        val reyesRojos = cartasReales.filter {
            it.valor == "K" && (it.palo == "corazones" || it.palo == "diamantes")
        }
        val cartasSinReyesRojos = if (reyesRojos.size >= 2) {
            cartasReales.filter { it !in reyesRojos }
        } else {
            cartasReales
        }

        return cartasSinReyesRojos.sumOf { valorPuntuacion(it) }
    }

    // ── Valor numérico para regla de consecutivos ──

    private fun valorNumerico(valor: String): Int? {
        return when (valor.uppercase()) {
            "A" -> 1
            "2" -> 2; "3" -> 3; "4" -> 4; "5" -> 5
            "6" -> 6; "7" -> 7; "8" -> 8; "9" -> 9; "10" -> 10
            "J" -> 11; "Q" -> 12; "K" -> 13
            else -> null // JKR y casos desconocidos
        }
    }

    // ── Valor de puntuación de cada carta ─────

    private fun valorPuntuacion(carta: Carta): Int {
        return when (carta.valor.uppercase()) {
            "A" -> 20
            "2" -> 0
            "J" -> 11
            "Q" -> 12
            "K" -> if (carta.palo == "treboles" || carta.palo == "picas") 13 else 1
            // El comodín sin definir vale 20 puntos (penalización máxima).
            // Si fue definido antes del conteo, su valor ya fue reemplazado
            // en Firebase y no llega como "JKR".
            "JKR" -> 20
            else -> carta.valor.toIntOrNull() ?: 0 // 3-10 valen su número
        }
    }
}
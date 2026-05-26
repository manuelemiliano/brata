package com.aguado.bratagame.bot

import com.aguado.bratagame.Carta
import com.aguado.bratagame.Sala
import com.aguado.bratagame.esSlotVacio
import com.aguado.bratagame.mesaNormalizadaACuatroCasillas

// ─────────────────────────────────────────────
// BOT EVENT DETECTOR
//
// Compara dos estados consecutivos de Sala (anterior y nuevo) y emite
// eventos discretos que la MemoriaBot procesa.
//
// El BotOrchestrator llama a este detector en cada cambio de Sala,
// recoge los eventos y los aplica una vez por bot a su memoria.
//
// Diseño:
//   - Pura, sin acceso a Firebase ni Compose.
//   - Idempotente: si recibe la misma sala dos veces, devuelve lista vacía.
//   - Independiente del bot: detecta TODOS los eventos relevantes para
//     CUALQUIER bot. Cada bot luego filtra los que le aplican.
//
// Cobertura Fase 2A (lo que detecta):
//   - INICIO_PARTIDA           → reparto inicial (incluye memorización de
//                                las 2 cartas alejadas del bot)
//   - CARTA_AGREGADA_A_DESCARTE → cualquier descarte (de mano, de mesa, etc.)
//   - JUGADOR_ROBO_POZO         → jugador X tiene ahora cartaEnMano y no la tenía
//   - JUGADOR_ROBO_DESCARTE     → idem pero la cima del descarte bajó
//   - JUGADOR_DESCARTO_DE_MESA  → posición de mesa cambió a vacía
//                                 y subió el descarte
//   - JUGADOR_DESCARTO_DE_MANO  → cartaEnMano desapareció y subió el descarte
//   - JUGADOR_CAMBIO_MESA       → posición pasó de carta X a carta Y
//                                 (carta diferente, no vacía)
//
// Fase 2B agregará detección de eventos de poderes (espía, cambio viendo,
// cambio sin ver, comodín definido, etc.). Por ahora esos casos se cubren
// indirectamente vía los eventos básicos de descarte y cambio de mesa.
// ─────────────────────────────────────────────

object BotEventDetector {

    fun detectarEventos(
        salaAnterior: Sala?,
        salaNueva: Sala
    ): List<EventoBot> {
        // Caso 1: no había sala anterior → es el inicio o primer load
        if (salaAnterior == null) {
            return detectarInicioPartida(salaNueva)
        }

        // Caso 2: cambió la partida (revancha) → tratarlo como inicio
        if (salaAnterior.partidaId != salaNueva.partidaId &&
            salaNueva.partidaId.isNotBlank()
        ) {
            return detectarInicioPartida(salaNueva)
        }

        // Caso 3: misma partida en curso → detectar diferencias
        return detectarCambiosEnPartida(salaAnterior, salaNueva)
    }

    // ─────────────────────────────────────────
    // INICIO DE PARTIDA
    // Cada bot debe aprender sus 2 cartas alejadas (posiciones 2 y 3).
    // ─────────────────────────────────────────

    private fun detectarInicioPartida(sala: Sala): List<EventoBot> {
        if (!sala.estaEnJuego) return emptyList()

        val eventos = mutableListOf<EventoBot>()

        sala.jugadores.values
            .filter { it.esBot }
            .forEach { bot ->
                val mesa = bot.cartas.mesaNormalizadaACuatroCasillas()
                val cartasIniciales = mutableMapOf<Int, Carta>()

                // Posiciones 2 y 3 = fila alejada (visibles durante el contador)
                listOf(2, 3).forEach { pos ->
                    val carta = mesa.getOrNull(pos)
                    if (carta != null && !carta.esSlotVacio()) {
                        cartasIniciales[pos] = carta
                    }
                }

                if (cartasIniciales.isNotEmpty()) {
                    eventos.add(
                        EventoBot.InicioPartida(
                            botId = bot.id,
                            cartasAlejadas = cartasIniciales
                        )
                    )
                }
            }

        return eventos
    }

    // ─────────────────────────────────────────
    // CAMBIOS DURANTE LA PARTIDA
    // ─────────────────────────────────────────

    private fun detectarCambiosEnPartida(
        salaAnterior: Sala,
        salaNueva: Sala
    ): List<EventoBot> {
        val eventos = mutableListOf<EventoBot>()

        // ── Detección de cambios en mazoDescarte ──
        val descarteAnterior = salaAnterior.mazoDescarte
        val descarteNuevo = salaNueva.mazoDescarte

        val cartasAgregadasAlDescarte = detectarCartasAgregadasAlDescarte(
            descarteAnterior = descarteAnterior,
            descarteNuevo = descarteNuevo
        )

        cartasAgregadasAlDescarte.forEach { carta ->
            eventos.add(
                EventoBot.CartaAgregadaADescarte(
                    cartaDescartada = carta,
                    descartadaPorJugadorId = carta.descartadaPorJugadorId,
                    desdeJuegoMesa = carta.descartadaDesdeJuegoMesa
                )
            )
        }

        // ── Detección de espionaje en curso ──
        // Cuando un jugador (cualquiera) activa un poder y luego espía una carta,
        // sala.cartaPoderActiva.cartaEspiandoId pasa de vacío a un id concreto.
        // Si el espía es un bot Y la carta es propia del bot O ajena visible
        // para el bot por el mecanismo de espía, el bot APRENDE su valor.
        val espionajeIniciado = detectarEspionajeIniciado(
            salaAnterior = salaAnterior,
            salaNueva = salaNueva
        )
        if (espionajeIniciado != null) {
            eventos.add(espionajeIniciado)
        }

        // ── Detección de cambios por jugador ──
        salaNueva.jugadores.forEach { (jugadorId, jugadorNuevo) ->
            val jugadorAnterior = salaAnterior.jugadores[jugadorId] ?: return@forEach

            // Cambio en cartaEnMano (de null a algo): jugador robó
            val tieneCartaEnManoAhora = jugadorNuevo.cartaEnMano != null
            val tenaCartaEnManoAntes = jugadorAnterior.cartaEnMano != null

            if (!tenaCartaEnManoAntes && tieneCartaEnManoAhora) {
                val carta = jugadorNuevo.cartaEnMano!!

                if (carta.origenRobo == "POZO") {
                    eventos.add(
                        EventoBot.JugadorRoboDelPozo(
                            jugadorId = jugadorId,
                            cartaRobada = carta
                        )
                    )
                } else if (carta.origenRobo == "DESCARTE") {
                    eventos.add(
                        EventoBot.JugadorRoboDelDescarte(
                            jugadorId = jugadorId,
                            cartaRobada = carta
                        )
                    )
                }
            }

            // Cambios en mesa: comparar las 4 posiciones
            val mesaAnterior = jugadorAnterior.cartas.mesaNormalizadaACuatroCasillas()
            val mesaNueva = jugadorNuevo.cartas.mesaNormalizadaACuatroCasillas()

            for (pos in 0..3) {
                val cartaAnt = mesaAnterior[pos]
                val cartaNvo = mesaNueva[pos]

                val antVacia = cartaAnt.esSlotVacio()
                val nvoVacio = cartaNvo.esSlotVacio()

                when {
                    !antVacia && nvoVacio -> {
                        eventos.add(
                            EventoBot.PosicionVaciada(
                                jugadorId = jugadorId,
                                posicion = pos,
                                cartaId = cartaAnt.id
                            )
                        )
                    }

                    antVacia && !nvoVacio -> {
                        eventos.add(
                            EventoBot.PosicionLlenada(
                                jugadorId = jugadorId,
                                posicion = pos,
                                cartaNueva = cartaNvo
                            )
                        )
                    }

                    !antVacia && !nvoVacio && cartaAnt.id != cartaNvo.id -> {
                        eventos.add(
                            EventoBot.PosicionReemplazada(
                                jugadorId = jugadorId,
                                posicion = pos,
                                cartaAnteriorId = cartaAnt.id,
                                cartaNueva = cartaNvo
                            )
                        )
                    }
                }
            }
        }

        return eventos
    }

    /**
     * Detecta si en esta transición de sala alguien (humano o bot) empezó a
     * espiar una carta. Si el ejecutor es un bot, este evento le permitirá
     * aprender el valor de esa carta.
     *
     * Política para bots:
     *   - Si la carta espiada es propia del bot → registrar en cartasPropias.
     *   - Si la carta espiada es ajena y la espió el bot → registrar en cartasRivales.
     *   - Si la carta espiada es ajena y la espió un humano → no aprendemos
     *     nada (el humano vio la carta, no el bot).
     */
    private fun detectarEspionajeIniciado(
        salaAnterior: Sala,
        salaNueva: Sala
    ): EventoBot? {
        val poderAnterior = salaAnterior.cartaPoderActiva
        val poderNuevo = salaNueva.cartaPoderActiva ?: return null

        // Si no hay carta espiada nueva, nada que detectar
        if (poderNuevo.cartaEspiandoId.isBlank()) return null

        // Si la carta espiada es la misma que antes, no es un evento nuevo
        if (poderAnterior?.cartaEspiandoId == poderNuevo.cartaEspiandoId) return null

        // Buscar la carta espiada en la sala nueva para extraer valor/palo/propietario
        var propietarioId: String? = null
        var posicion = -1
        var cartaEspiada: Carta? = null

        salaNueva.jugadores.forEach { (jugadorId, jugador) ->
            val mesa = jugador.cartas.mesaNormalizadaACuatroCasillas()
            mesa.forEachIndexed { idx, carta ->
                if (!carta.esSlotVacio() && carta.id == poderNuevo.cartaEspiandoId) {
                    propietarioId = jugadorId
                    posicion = idx
                    cartaEspiada = carta
                }
            }
        }

        val propietario = propietarioId ?: return null
        val carta = cartaEspiada ?: return null

        return EventoBot.JugadorEspio(
            espiaId = poderNuevo.jugadorId,
            propietarioId = propietario,
            posicion = posicion,
            cartaEspiada = carta
        )
    }

    // ─────────────────────────────────────────
    // HELPER: Detectar cartas agregadas al descarte
    //
    // El descarte puede crecer en 1 (caso común), 2 (descarte free
    // o caso espía), o más. Comparamos ids para identificar las nuevas.
    // ─────────────────────────────────────────

    private fun detectarCartasAgregadasAlDescarte(
        descarteAnterior: List<Carta>,
        descarteNuevo: List<Carta>
    ): List<Carta> {
        if (descarteNuevo.size <= descarteAnterior.size) return emptyList()

        val idsAnteriores = descarteAnterior.map { it.id }.toSet()
        return descarteNuevo.filter { it.id !in idsAnteriores }
    }
}

// ─────────────────────────────────────────────
// EVENTOS DEL BOT (sealed class)
//
// Cada evento es una "noticia" sobre algo que pasó en el juego.
// La MemoriaBot decide qué hacer con cada uno (algunos se ignoran
// según el bot que los recibe).
// ─────────────────────────────────────────────

sealed class EventoBot {

    /** Reparto inicial: el bot conoce sus 2 cartas alejadas */
    data class InicioPartida(
        val botId: String,
        val cartasAlejadas: Map<Int, Carta>   // posición → carta
    ) : EventoBot()

    /** Una carta nueva apareció en la cima (o cerca de la cima) del descarte */
    data class CartaAgregadaADescarte(
        val cartaDescartada: Carta,
        val descartadaPorJugadorId: String,
        val desdeJuegoMesa: Boolean
    ) : EventoBot()

    /** Un jugador robó del pozo (ahora tiene cartaEnMano) */
    data class JugadorRoboDelPozo(
        val jugadorId: String,
        val cartaRobada: Carta
    ) : EventoBot()

    /** Un jugador robó del descarte (ahora tiene cartaEnMano) */
    data class JugadorRoboDelDescarte(
        val jugadorId: String,
        val cartaRobada: Carta
    ) : EventoBot()

    /** Una posición de mesa pasó de ocupada a vacía */
    data class PosicionVaciada(
        val jugadorId: String,
        val posicion: Int,
        val cartaId: String
    ) : EventoBot()

    /** Una posición de mesa pasó de vacía a ocupada */
    data class PosicionLlenada(
        val jugadorId: String,
        val posicion: Int,
        val cartaNueva: Carta
    ) : EventoBot()

    /** Una posición de mesa cambió de una carta a otra (mismo slot, distinto id) */
    data class PosicionReemplazada(
        val jugadorId: String,
        val posicion: Int,
        val cartaAnteriorId: String,
        val cartaNueva: Carta
    ) : EventoBot()

    /**
     * Un jugador (espiaId) espió una carta (cartaEspiada) que pertenece a
     * otro jugador (propietarioId, que puede coincidir con espiaId si es
     * autoespionaje). Si el espía es un bot, este evento le permite aprender
     * el valor de esa carta legítimamente.
     */
    data class JugadorEspio(
        val espiaId: String,
        val propietarioId: String,
        val posicion: Int,
        val cartaEspiada: Carta
    ) : EventoBot()
}

package com.aguado.bratagame.bot

import com.aguado.bratagame.Carta

// ─────────────────────────────────────────────
// BOT MEMORY
//
// Lógica pura para actualizar la MemoriaBot a partir de un EventoBot.
// Es la única manera en que la memoria del bot crece o cambia.
//
// Reglas que respeta:
//   - El bot solo "conoce" cartas que aprendió legítimamente:
//       * Sus 2 cartas alejadas al inicio (memorización oficial).
//       * Su carta en mano cuando roba (la mira al robarla).
//       * Cartas vistas vía espía o cambio viendo (Fase 2B).
//       * Cartas que pasaron por descarte (todas son públicas).
//   - El bot "olvida" una posición cuando esa carta deja la posición
//     (por descarte, intercambio o ser sustituida).
//   - El descarte histórico es público: todos los bots lo conocen.
//
// Cada función pública recibe una memoria y devuelve la nueva memoria.
// Sin side-effects, sin acceso a Firebase. La persistencia la maneja
// el BotOrchestrator vía BotFirebaseRepository.
// ─────────────────────────────────────────────

object BotMemory {

    // ─────────────────────────────────────────
    // APLICAR EVENTO
    // Entry point principal. El orquestador llama esto por cada evento
    // emitido por BotEventDetector, una vez por bot.
    //
    // Parámetros:
    //   - memoriaPrevia: estado actual de la memoria del bot.
    //   - botId: id del bot dueño de esta memoria.
    //   - evento: el evento a procesar.
    //
    // Retorna: nueva memoria (puede ser igual si el evento no aplica).
    // ─────────────────────────────────────────

    fun aplicarEvento(
        memoriaPrevia: MemoriaBot,
        botId: String,
        evento: EventoBot
    ): MemoriaBot {
        val ahora = System.currentTimeMillis()

        return when (evento) {

            is EventoBot.InicioPartida -> {
                if (evento.botId != botId) return memoriaPrevia
                aplicarInicioPartida(memoriaPrevia, evento, ahora)
            }

            is EventoBot.CartaAgregadaADescarte -> {
                aplicarCartaAgregadaADescarte(memoriaPrevia, evento, ahora)
            }

            is EventoBot.JugadorRoboDelPozo -> {
                if (evento.jugadorId == botId) {
                    aplicarMiRoboDelPozo(memoriaPrevia, evento, ahora)
                } else {
                    memoriaPrevia
                }
            }

            is EventoBot.JugadorRoboDelDescarte -> {
                aplicarJugadorRoboDelDescarte(memoriaPrevia, evento, botId, ahora)
            }

            is EventoBot.PosicionVaciada -> {
                aplicarPosicionVaciada(memoriaPrevia, evento, botId)
            }

            is EventoBot.PosicionLlenada -> {
                aplicarPosicionLlenada(memoriaPrevia, evento, botId, ahora)
            }

            is EventoBot.PosicionReemplazada -> {
                aplicarPosicionReemplazada(memoriaPrevia, evento, botId, ahora)
            }

            is EventoBot.JugadorEspio -> {
                aplicarJugadorEspio(memoriaPrevia, evento, botId, ahora)
            }
        }
    }

    // ─────────────────────────────────────────
    // HANDLERS POR EVENTO
    // ─────────────────────────────────────────

    private fun aplicarInicioPartida(
        memoria: MemoriaBot,
        evento: EventoBot.InicioPartida,
        ahora: Long
    ): MemoriaBot {
        // Reset completo: nueva partida = memoria limpia + cartas alejadas
        val cartasIniciales = evento.cartasAlejadas.mapKeys { it.key.toString() }
            .mapValues { (_, carta) ->
                CartaConocida(
                    valor = carta.valor,
                    palo = carta.palo,
                    timestamp = ahora,
                    fuente = "inicial"
                )
            }

        return MemoriaBot(
            cartasPropiasConocidas = cartasIniciales,
            cartasRivalesConocidas = emptyMap(),
            descarteHistoricoIds = emptyList(),
            conteoValoresVistos = emptyMap(),
            ultimaActualizacionTs = ahora
        )
    }

    private fun aplicarCartaAgregadaADescarte(
        memoria: MemoriaBot,
        evento: EventoBot.CartaAgregadaADescarte,
        ahora: Long
    ): MemoriaBot {
        val carta = evento.cartaDescartada

        // El descarte es público; todos los bots ven valor y palo.
        val nuevoHistorico = (memoria.descarteHistoricoIds + carta.id)
            .takeLast(MAX_DESCARTE_HISTORICO_LEN)

        val nuevoConteo = memoria.conteoValoresVistos.toMutableMap()
        nuevoConteo[carta.valor] = (nuevoConteo[carta.valor] ?: 0) + 1

        return memoria.copy(
            descarteHistoricoIds = nuevoHistorico,
            conteoValoresVistos = nuevoConteo,
            ultimaActualizacionTs = ahora
        )
    }

    private fun aplicarMiRoboDelPozo(
        memoria: MemoriaBot,
        evento: EventoBot.JugadorRoboDelPozo,
        ahora: Long
    ): MemoriaBot {
        // Cuando YO robo del pozo, conozco mi carta en mano.
        // Esa carta no está en ninguna de mis 4 posiciones todavía,
        // así que no la guardamos en cartasPropiasConocidas.
        // El BotBrain accede a la carta directamente vía vista.cartaEnMano.
        return memoria.copy(ultimaActualizacionTs = ahora)
    }

    private fun aplicarJugadorRoboDelDescarte(
        memoria: MemoriaBot,
        evento: EventoBot.JugadorRoboDelDescarte,
        botId: String,
        ahora: Long
    ): MemoriaBot {
        // Cualquiera que robe del descarte está tomando una carta visible
        // por todos. Si después la incorpora a su mesa (vía PosicionLlenada
        // o PosicionReemplazada), esos eventos se encargarán de actualizar
        // la memoria sobre cartas ajenas.
        //
        // Aquí no hacemos nada especial; solo actualizamos timestamp.
        return memoria.copy(ultimaActualizacionTs = ahora)
    }

    private fun aplicarPosicionVaciada(
        memoria: MemoriaBot,
        evento: EventoBot.PosicionVaciada,
        botId: String
    ): MemoriaBot {
        return if (evento.jugadorId == botId) {
            // Mi propia posición se vació → olvido lo que sabía de ella
            val nuevasPropias = memoria.cartasPropiasConocidas.toMutableMap()
            nuevasPropias.remove(evento.posicion.toString())

            memoria.copy(
                cartasPropiasConocidas = nuevasPropias,
                ultimaActualizacionTs = System.currentTimeMillis()
            )
        } else {
            // Posición ajena se vació → olvido lo que sabía de ese rival en esa pos
            val clave = ClaveCartaRival.construir(evento.jugadorId, evento.posicion)
            val nuevasRivales = memoria.cartasRivalesConocidas.toMutableMap()
            nuevasRivales.remove(clave)

            memoria.copy(
                cartasRivalesConocidas = nuevasRivales,
                ultimaActualizacionTs = System.currentTimeMillis()
            )
        }
    }

    private fun aplicarPosicionLlenada(
        memoria: MemoriaBot,
        evento: EventoBot.PosicionLlenada,
        botId: String,
        ahora: Long
    ): MemoriaBot {
        // Una posición vacía se llenó con una carta nueva.
        // ¿Sabemos algo del valor de esa carta?
        //
        // Caso 1: la carta vino del descarte (donde era visible).
        //         En ese caso, la cartaNueva tiene un valor/palo legítimo
        //         que el bot pudo ver y memorizar.
        //
        // Caso 2: la carta vino del pozo (donde era oculta).
        //         El bot no debería conocer su valor.
        //
        // Heurística pragmática: si la carta tiene id distinto a todas las
        // cartas que estaban en mesa (que no veíamos), pudo haber venido del
        // pozo o del descarte. Para no hacer trampa, sólo memorizamos si el
        // id coincide con algún id que pasó por el descarte (lo cual significa
        // que el bot pudo haberla visto y memorizado).
        //
        // En Fase 2A simplificamos: si la posición se llenó en MI mesa con
        // una carta cuyo origen es "POZO", probablemente fue el "TOMAR" (cuando
        // el bot estaba sin cartas y robó). Si fue de DESCARTE, la conozco.
        //
        // Para no asumir indebidamente, en Fase 2A NO memorizamos cartas que
        // llegan por PosicionLlenada de mesa ajena. Solo recordamos las propias
        // si origenRobo indica DESCARTE (el bot la vio).
        if (evento.jugadorId != botId) {
            return memoria.copy(ultimaActualizacionTs = ahora)
        }

        if (evento.cartaNueva.origenRobo == "DESCARTE") {
            val conocida = CartaConocida(
                valor = evento.cartaNueva.valor,
                palo = evento.cartaNueva.palo,
                timestamp = ahora,
                fuente = "robada_descarte"
            )

            val nuevasPropias = memoria.cartasPropiasConocidas.toMutableMap()
            nuevasPropias[evento.posicion.toString()] = conocida

            return memoria.copy(
                cartasPropiasConocidas = nuevasPropias,
                ultimaActualizacionTs = ahora
            )
        }

        return memoria.copy(ultimaActualizacionTs = ahora)
    }

    private fun aplicarPosicionReemplazada(
        memoria: MemoriaBot,
        evento: EventoBot.PosicionReemplazada,
        botId: String,
        ahora: Long
    ): MemoriaBot {
        return if (evento.jugadorId == botId) {
            // En mi propia mesa, una carta fue reemplazada por otra.
            // Caso típico: yo robé del pozo y cambié con una mía.
            // La carta nueva la conozco (la tenía en mano).
            val nuevasPropias = memoria.cartasPropiasConocidas.toMutableMap()

            // La nueva carta la conozco solo si vino con valor (cartaEnMano)
            if (evento.cartaNueva.valor.isNotBlank()) {
                nuevasPropias[evento.posicion.toString()] = CartaConocida(
                    valor = evento.cartaNueva.valor,
                    palo = evento.cartaNueva.palo,
                    timestamp = ahora,
                    fuente = "cambio_propio"
                )
            } else {
                nuevasPropias.remove(evento.posicion.toString())
            }

            memoria.copy(
                cartasPropiasConocidas = nuevasPropias,
                ultimaActualizacionTs = ahora
            )
        } else {
            // En mesa ajena, una carta fue reemplazada. No sabemos qué entró.
            // Olvidamos lo que sabíamos de esa posición.
            val clave = ClaveCartaRival.construir(evento.jugadorId, evento.posicion)
            val nuevasRivales = memoria.cartasRivalesConocidas.toMutableMap()
            nuevasRivales.remove(clave)

            memoria.copy(
                cartasRivalesConocidas = nuevasRivales,
                ultimaActualizacionTs = ahora
            )
        }
    }

    /**
     * Cuando un jugador espía una carta:
     *   - Si el espía es el bot Y la carta espiada es propia del bot →
     *     registrar en cartasPropiasConocidas (aprendió su propia carta).
     *   - Si el espía es el bot Y la carta es de un rival →
     *     registrar en cartasRivalesConocidas (aprendió carta ajena).
     *   - Si el espía NO es el bot → el bot no aprende nada del valor
     *     (el otro jugador la vio, no nosotros).
     */
    private fun aplicarJugadorEspio(
        memoria: MemoriaBot,
        evento: EventoBot.JugadorEspio,
        botId: String,
        ahora: Long
    ): MemoriaBot {
        if (evento.espiaId != botId) return memoria

        val carta = evento.cartaEspiada

        // Solo aprendemos si la carta tiene valor real
        if (carta.valor.isBlank()) return memoria

        val conocida = CartaConocida(
            valor = carta.valor,
            palo = carta.palo,
            timestamp = ahora,
            fuente = "espiada"
        )

        return if (evento.propietarioId == botId) {
            // Espié una carta propia
            val nuevasPropias = memoria.cartasPropiasConocidas.toMutableMap()
            nuevasPropias[evento.posicion.toString()] = conocida
            memoria.copy(
                cartasPropiasConocidas = nuevasPropias,
                ultimaActualizacionTs = ahora
            )
        } else {
            // Espié una carta ajena
            val clave = ClaveCartaRival.construir(evento.propietarioId, evento.posicion)
            val nuevasRivales = memoria.cartasRivalesConocidas.toMutableMap()
            nuevasRivales[clave] = conocida
            memoria.copy(
                cartasRivalesConocidas = nuevasRivales,
                ultimaActualizacionTs = ahora
            )
        }
    }

    // ─────────────────────────────────────────
    // HELPERS PÚBLICOS DE CONSULTA
    // Los usa el BotBrain para tomar decisiones.
    // ─────────────────────────────────────────

    fun conoceMiPosicion(memoria: MemoriaBot, posicion: Int): Boolean {
        return memoria.cartasPropiasConocidas[posicion.toString()] != null
    }

    fun obtenerMiPosicionConocida(memoria: MemoriaBot, posicion: Int): CartaConocida? {
        return memoria.cartasPropiasConocidas[posicion.toString()]
    }

    fun obtenerCartaRivalConocida(
        memoria: MemoriaBot,
        rivalId: String,
        posicion: Int
    ): CartaConocida? {
        val clave = ClaveCartaRival.construir(rivalId, posicion)
        return memoria.cartasRivalesConocidas[clave]
    }

    fun todasLasMisPosicionesConocidas(
        memoria: MemoriaBot,
        misPosicionesOcupadas: Set<Int>
    ): Boolean {
        if (misPosicionesOcupadas.isEmpty()) return true
        return misPosicionesOcupadas.all { conoceMiPosicion(memoria, it) }
    }

    // ─────────────────────────────────────────
    // RECONCILIACIÓN DEFENSIVA
    //
    // Si el detector se perdió eventos o hubo desincronización,
    // esta función poda entradas de memoria que apunten a cartas
    // que ya no están donde el bot cree.
    //
    // El BotOrchestrator la llama antes de cada decisión importante.
    // ─────────────────────────────────────────

    fun reconciliar(
        memoria: MemoriaBot,
        vista: VistaParcialSala
    ): MemoriaBot {
        // Mis propias posiciones: si una entrada apunta a una posición
        // ahora vacía (o con carta de id distinto), purgamos.
        val nuevasPropias = memoria.cartasPropiasConocidas.toMutableMap()
        val keysAEliminarPropias = mutableListOf<String>()

        nuevasPropias.keys.forEach { posStr ->
            val pos = posStr.toIntOrNull() ?: return@forEach
            val estado = vista.miEstadoPosiciones[pos]
            if (estado == null || !estado.ocupada) {
                keysAEliminarPropias.add(posStr)
            }
        }
        keysAEliminarPropias.forEach { nuevasPropias.remove(it) }

        // Rivales: si una clave compuesta apunta a un rival/posición que ya
        // no tiene carta en mesa, purgamos.
        val nuevasRivales = memoria.cartasRivalesConocidas.toMutableMap()
        val keysAEliminarRivales = mutableListOf<String>()

        nuevasRivales.keys.forEach { claveCompuesta ->
            val rivalId = ClaveCartaRival.extraerRivalId(claveCompuesta) ?: run {
                // Clave mal formada → eliminar
                keysAEliminarRivales.add(claveCompuesta)
                return@forEach
            }
            val pos = ClaveCartaRival.extraerPosicion(claveCompuesta) ?: run {
                keysAEliminarRivales.add(claveCompuesta)
                return@forEach
            }

            val posicionesOcupadasRival = vista.posicionesOcupadasPorJugador[rivalId] ?: emptySet()
            if (pos !in posicionesOcupadasRival) {
                keysAEliminarRivales.add(claveCompuesta)
            }
        }
        keysAEliminarRivales.forEach { nuevasRivales.remove(it) }

        return memoria.copy(
            cartasPropiasConocidas = nuevasPropias,
            cartasRivalesConocidas = nuevasRivales
        )
    }

    // ─────────────────────────────────────────
    // CONSTANTES
    // ─────────────────────────────────────────

    /** Máximo tamaño del histórico de descarte que guardamos por bot.
     *  Brata se juega con 1 o 2 barajas (~54 o ~108 cartas), así que 120
     *  cubre cómodamente cualquier ronda. */
    private const val MAX_DESCARTE_HISTORICO_LEN = 120
}

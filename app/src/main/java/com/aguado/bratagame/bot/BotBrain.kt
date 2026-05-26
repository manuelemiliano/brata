package com.aguado.bratagame.bot

import com.aguado.bratagame.Carta
import com.aguado.bratagame.TipoPoder

// ─────────────────────────────────────────────
// BOT BRAIN (Fase 2B)
//
// Cerebro de decisión del bot. Función pura: recibe (memoria, vista, …)
// y devuelve una DecisionBot.
//
// REGLA DURA: este archivo NUNCA accede a Sala directamente.
// Solo lee VistaParcialSala (vista) y MemoriaBot (memoria).
// Esta es la frontera que protege el "fair play".
//
// Cobertura Fase 2B (acumulada sobre 2A):
//   - Inicio de turno: cantar BRATA, robar pozo o descarte.
//   - Post-robo: descartar, cambiar por hueco vacío, cambiar por peor conocida.
//   - ACTIVAR poderes: ESPIAR, CAMBIAR_VIENDO, CAMBIAR_SIN_VER, comodín.
//   - DESCARTE FREE: activarlo cuando esté disponible.
//   - Durante poder propio: elegir qué espiar, confirmar swaps, regresar.
//   - Sin reacciones fuera de turno (VOY, descarte espontáneo) → Fase 3/4.
// ─────────────────────────────────────────────

object BotBrain {

    // ─────────────────────────────────────────
    // ENTRY POINT
    // ─────────────────────────────────────────

    fun decidir(
        memoria: MemoriaBot,
        vista: VistaParcialSala
    ): DecisionBot {

        // ── Caso 1: NO es mi turno ──
        // Fase 2B: aún no reaccionamos fuera de turno (sin VOY, sin espontáneo)
        if (!vista.esMiTurno()) {
            return DecisionBot.NoOp
        }

        // ── Caso 2: hay poder activo propio ──
        val poder = vista.poderActivo
        if (poder != null && poder.jugadorId == vista.miId) {
            return decidirDuranteMiPoder(memoria, vista, poder)
        }

        // ── Caso 3: tengo carta en mano ──
        if (vista.cartaEnMano != null) {
            return decidirConCartaEnMano(memoria, vista)
        }

        // ── Caso 4: inicio normal de turno ──
        return decidirInicioDeTurno(memoria, vista)
    }

    // ─────────────────────────────────────────
    // INICIO DE TURNO
    // ─────────────────────────────────────────

    private fun decidirInicioDeTurno(
        memoria: MemoriaBot,
        vista: VistaParcialSala
    ): DecisionBot {

        // Si BRATA fue activada por otro y es mi turno, decidir si paso o juego
        if (vista.brataActivada && vista.brataJugadorId != vista.miId) {
            return decidirUltimaRonda(memoria, vista)
        }

        // 1. ¿Cantar BRATA?
        if (deberiaCantarBrata(memoria, vista)) {
            return DecisionBot.CantarBrata
        }

        // 2. ¿Robar del descarte?
        val cima = vista.ultimaCartaDescarte
        if (cima != null && cimaEsRobableLegalmente(cima, vista)) {
            if (deberiaRobarDelDescarte(cima, memoria, vista)) {
                return DecisionBot.RobarDelDescarte
            }
        }

        // 3. Por defecto, robar del pozo
        return DecisionBot.RobarDelPozo
    }

    private fun deberiaCantarBrata(
        memoria: MemoriaBot,
        vista: VistaParcialSala
    ): Boolean {
        val estimado = estimarManoPropia(memoria, vista)
        val umbral = umbralBrata(vista.cantidadJugadoresActivos)

        if (estimado <= umbral) return true

        // Señal adicional: si algún rival ya tiene mano corta (descartó mucho)
        // y mi estimado está dentro de 2 puntos del umbral, anticipo
        if (rivalCortoConocido(vista) && estimado <= umbral + 2) {
            return true
        }

        return false
    }

    private fun umbralBrata(cantidadActivos: Int): Int = when (cantidadActivos) {
        2 -> 5
        3 -> 6
        4 -> 6
        5 -> 7
        6 -> 8
        else -> 6
    }

    private fun rivalCortoConocido(vista: VistaParcialSala): Boolean {
        return vista.rivalesActivos().any { rivalId ->
            (vista.cuentaCartasPorJugador[rivalId] ?: 4) <= 2
        }
    }

    private fun cimaEsRobableLegalmente(
        cima: Carta,
        vista: VistaParcialSala
    ): Boolean {
        if (cima.descartadaPorJugadorId.isBlank()) return false
        if (!cima.descartadaDesdeJuegoMesa) return false
        val anterior = vista.jugadorInmediatoAnterior() ?: return false
        return cima.descartadaPorJugadorId == anterior
    }

    private fun deberiaRobarDelDescarte(
        cima: Carta,
        memoria: MemoriaBot,
        vista: VistaParcialSala
    ): Boolean {
        val valorCima = valorPuntos(cima)

        // Comodín legalmente robable: siempre tomar
        if (cima.valor == "JKR") return true

        // Cartas muy bajas: siempre vale la pena
        if (valorCima <= 4) return true

        // Cartas altas: nunca
        if (valorCima >= 10) return false

        // Cartas medias (5-9): solo si tengo una posición conocida peor
        val peorConocida = peorCartaPropiaConocida(memoria) ?: return false
        return valorPuntos(peorConocida.valor, peorConocida.palo) > valorCima
    }

    // ─────────────────────────────────────────
    // CON CARTA EN MANO
    // ─────────────────────────────────────────

    private fun decidirConCartaEnMano(
        memoria: MemoriaBot,
        vista: VistaParcialSala
    ): DecisionBot {
        val cartaEnMano = vista.cartaEnMano ?: return DecisionBot.NoOp

        // Caso especial: comodín → siempre hay que SELECCIONAR_COMODIN
        if (cartaEnMano.valor == "JKR") {
            val (valor, palo) = elegirValorComodin(memoria)
            return DecisionBot.SeleccionarComodin(valor = valor, palo = palo)
        }

        val valorMano = valorPuntos(cartaEnMano)

        // ── 1. Descarte free disponible (jugada gratuita) ──
        if (descarteFreeDisponible(cartaEnMano, vista)) {
            return DecisionBot.ActivarDescarteFree
        }

        // ── 2. ¿Activar poder de la carta? ──
        val tipoPoder = obtenerPoder(cartaEnMano)
        if (tipoPoder != TipoPoder.NINGUNO && deberiaActivarPoder(tipoPoder, memoria, vista)) {
            return DecisionBot.ActivarPoder(tipoPoder.name)
        }

        // ── 3. Caso A: hueco vacío → cambiar para llenar ──
        val huecoVacio = primeraPosicionVaciaPropia(vista)
        if (huecoVacio != null) {
            return DecisionBot.CambiarPorPosicion(huecoVacio)
        }

        // ── 4. Caso B: tengo peor conocida y la mano la mejora ──
        val peor = peorCartaPropiaConocida(memoria)
        if (peor != null) {
            val valorPeor = valorPuntos(peor.valor, peor.palo)
            val posicionPeor = posicionDePeorConocida(memoria) ?: return DecisionBot.Descartar

            if (valorMano < valorPeor) {
                return DecisionBot.CambiarPorPosicion(posicionPeor)
            }
            return DecisionBot.Descartar
        }

        // ── 5. Caso C: no conozco ninguna mía ──
        if (valorMano <= 4) {
            val posicionDesconocida = primeraPosicionPropiaDesconocida(memoria, vista)
            if (posicionDesconocida != null) {
                return DecisionBot.CambiarPorPosicion(posicionDesconocida)
            }
        }

        return DecisionBot.Descartar
    }

    // ─────────────────────────────────────────
    // ACTIVAR PODER: ¿debería?
    //
    // Reglas validadas en iteración anterior:
    //   - ESPIAR: activar si tengo posición propia desconocida (ganancia
    //     informativa garantizada), o si conozco rivales con desconocidas.
    //   - CAMBIAR_VIENDO (A): activar solo si mi peor estimada vale ≥8.
    //     Sin esa info, descartar el As (20 pts) es mejor que arriesgar.
    //   - CAMBIAR_SIN_VER (J, Q): activar solo si tengo posición conocida ≥10.
    //     Sin posición conocida-alta, jugar a ciegas con J/Q no mejora nada.
    //   - Validación adicional: el motor de juego (GameRules) ya filtra
    //     poderes no ejecutables (sin cartas ajenas en mesa, etc.).
    //     El bot replica esa lógica acá para no intentar activar inútilmente.
    // ─────────────────────────────────────────

    private fun deberiaActivarPoder(
        tipoPoder: TipoPoder,
        memoria: MemoriaBot,
        vista: VistaParcialSala
    ): Boolean {
        return when (tipoPoder) {
            TipoPoder.ESPIAR -> {
                if (tengoPosicionPropiaDesconocida(memoria, vista)) return true
                if (tengoRivalConPosicionEnMesa(vista)) return true
                false
            }

            TipoPoder.CAMBIAR_VIENDO -> {
                // GameRules exige: ≥1 carta ajena Y ≥2 cartas en mesa total
                if (!tengoRivalConPosicionEnMesa(vista)) return false
                if (totalCartasEnMesa(vista) < 2) return false

                // Heurística: solo activar si mi peor estimada vale ≥8
                val miPeorEstimada = peorPosicionPropiaEstimada(memoria, vista)
                miPeorEstimada >= 8
            }

            TipoPoder.CAMBIAR_SIN_VER -> {
                // GameRules exige: ≥2 cartas en mesa total
                if (totalCartasEnMesa(vista) < 2) return false

                // Heurística: solo activar si tengo conocida ≥10
                tengoPosicionConocidaConValor(memoria, minimo = 10)
            }

            TipoPoder.DESCARTE_FREE_SELECCION,
            TipoPoder.NINGUNO -> false
        }
    }

    private fun obtenerPoder(carta: Carta): TipoPoder {
        return when (carta.valor.uppercase()) {
            "2", "3", "4", "K" -> TipoPoder.NINGUNO
            "5", "6", "7", "8", "9", "10" -> TipoPoder.ESPIAR
            "A" -> TipoPoder.CAMBIAR_VIENDO
            "J", "Q" -> TipoPoder.CAMBIAR_SIN_VER
            "JKR" -> TipoPoder.NINGUNO
            else -> TipoPoder.NINGUNO
        }
    }

    // ─────────────────────────────────────────
    // DESCARTE FREE: ¿disponible?
    //
    // Replica la lógica de GameRules.accionesDisponibles para la rama
    // DESCARTAR_FREE, sin importar GameRules (para mantener pureza).
    //
    // Reglas:
    //   - Solo aplica si la carta fue robada del POZO (no del descarte).
    //   - Caso 1: la carta robada coincide en valor con la cima del descarte.
    //   - Caso 2: hay dos cartas consecutivas en el descarte y la robada
    //     es consecutiva ascendente o descendente.
    // ─────────────────────────────────────────

    private fun descarteFreeDisponible(
        cartaEnMano: Carta,
        vista: VistaParcialSala
    ): Boolean {
        if (cartaEnMano.origenRobo != "POZO") return false

        val ultima = vista.ultimaCartaDescarte ?: return false
        val segunda = vista.penultimaCartaDescarte

        // Caso 1: coincide en valor con la cima
        if (cartaEnMano.valor == ultima.valor) return true

        // Caso 2: secuencia consecutiva
        if (segunda != null) {
            val vUltima = valorNumerico(ultima.valor)
            val vSegunda = valorNumerico(segunda.valor)
            val vRobada = valorNumerico(cartaEnMano.valor)

            if (vUltima != null && vSegunda != null && vRobada != null) {
                val descarteConsecutivo = kotlin.math.abs(vUltima - vSegunda) == 1
                val robadaConsecutiva = kotlin.math.abs(vRobada - vUltima) == 1
                if (descarteConsecutivo && robadaConsecutiva) return true
            }
        }

        return false
    }

    private fun valorNumerico(valor: String): Int? {
        return when (valor.uppercase()) {
            "A" -> 1
            "J" -> 11
            "Q" -> 12
            "K" -> 13
            "JKR" -> null
            else -> valor.toIntOrNull()
        }
    }

    // ─────────────────────────────────────────
    // ÚLTIMA RONDA (otro jugador cantó BRATA)
    // ─────────────────────────────────────────

    private fun decidirUltimaRonda(
        memoria: MemoriaBot,
        vista: VistaParcialSala
    ): DecisionBot {
        val miEstimado = estimarManoPropia(memoria, vista)

        // Estimo que el cantor tiene mano ≤ umbral - 1
        val cantorEstimado = umbralBrata(vista.cantidadJugadoresActivos) - 1

        // Si creo que ya gano, pasar es seguro
        if (miEstimado <= cantorEstimado) {
            return DecisionBot.PasarTurnoBrata
        }

        // Si estoy perdiendo, vale la pena jugar el turno por si puedo bajar
        return DecisionBot.RobarDelPozo
    }

    // ─────────────────────────────────────────
    // DURANTE MI PODER ACTIVO
    //
    // Se invoca cuando vista.poderActivo.jugadorId == miId.
    // Dispatcha según el tipo de poder y la fase (espiando o no).
    // ─────────────────────────────────────────

    private fun decidirDuranteMiPoder(
        memoria: MemoriaBot,
        vista: VistaParcialSala,
        poder: com.aguado.bratagame.CartaPoderActiva
    ): DecisionBot {
        val tipoPoder = parsearTipoPoder(poder.tipoPoder.name)
        val estaEspiando = poder.cartaEspiandoId.isNotEmpty()

        return when (tipoPoder) {
            TipoPoder.ESPIAR -> decidirDuranteEspiar(memoria, vista, poder, estaEspiando)
            TipoPoder.CAMBIAR_VIENDO -> decidirDuranteCambiarViendo(memoria, vista, poder, estaEspiando)
            TipoPoder.CAMBIAR_SIN_VER -> decidirDuranteCambiarSinVer(memoria, vista)
            TipoPoder.DESCARTE_FREE_SELECCION -> decidirDuranteDescarteFree(memoria, vista)
            TipoPoder.NINGUNO -> DecisionBot.NoOp
        }
    }

    private fun parsearTipoPoder(nombre: String): TipoPoder {
        return try {
            TipoPoder.valueOf(nombre)
        } catch (e: IllegalArgumentException) {
            TipoPoder.NINGUNO
        }
    }

    // ── ESPIAR (5-10) ─────────────────────────

    private fun decidirDuranteEspiar(
        memoria: MemoriaBot,
        vista: VistaParcialSala,
        poder: com.aguado.bratagame.CartaPoderActiva,
        estaEspiando: Boolean
    ): DecisionBot {
        if (!estaEspiando) {
            // Fase 1: elegir qué carta espiar
            val objetivo = elegirCartaParaEspiar(memoria, vista)
            return if (objetivo != null) {
                DecisionBot.EspiarCarta(cartaId = objetivo.cartaId)
            } else {
                // No hay nada que espiar (no debería pasar, pero defensive)
                DecisionBot.RegresarCartaEspiada
            }
        }

        // Fase 2: ya espié. Casos:
        //  - Si la carta espiada coincide en valor con la activadora Y es propia,
        //    puedo descartarla (regla del As mejorada).
        //  - Si no, regresar.
        val valorActivadora = poder.valorCartaActivadora
        val cartaEspiada = buscarCartaEspiadaEnVista(memoria, vista, poder.cartaEspiandoId)

        if (cartaEspiada != null &&
            cartaEspiada.esPropia &&
            cartaEspiada.valor == valorActivadora &&
            valorActivadora.isNotBlank()
        ) {
            return DecisionBot.DescartarCartaEspiada
        }

        return DecisionBot.RegresarCartaEspiada
    }

    /**
     * Política de elección de carta para ESPIAR:
     *   1. Si tengo posición propia ocupada y desconocida → espiar la mía
     *      (ganancia informativa para mí, sin riesgo).
     *   2. Si no, espiar carta ajena desconocida (saber qué tiene el rival).
     *   3. Si todo lo razonable lo conozco, espiar cualquier carta ajena.
     */
    private fun elegirCartaParaEspiar(
        memoria: MemoriaBot,
        vista: VistaParcialSala
    ): CartaEspiable? {
        // 1. Mi propia desconocida
        for (pos in listOf(2, 3, 0, 1)) {
            val estado = vista.miEstadoPosiciones[pos] ?: continue
            if (!estado.ocupada) continue
            if (BotMemory.conoceMiPosicion(memoria, pos)) continue
            return CartaEspiable(cartaId = estado.cartaId, esPropia = true)
        }

        // 2. Carta ajena desconocida
        for (rivalId in vista.rivalesActivos()) {
            val posicionesOcupadas = vista.posicionesOcupadasPorJugador[rivalId] ?: continue
            for (pos in posicionesOcupadas) {
                if (BotMemory.obtenerCartaRivalConocida(memoria, rivalId, pos) != null) continue
                val cartaId = obtenerIdCartaRival(vista, rivalId, pos) ?: continue
                return CartaEspiable(cartaId = cartaId, esPropia = false)
            }
        }

        // 3. Cualquier carta ajena
        for (rivalId in vista.rivalesActivos()) {
            val posicionesOcupadas = vista.posicionesOcupadasPorJugador[rivalId] ?: continue
            val primera = posicionesOcupadas.firstOrNull() ?: continue
            val cartaId = obtenerIdCartaRival(vista, rivalId, primera) ?: continue
            return CartaEspiable(cartaId = cartaId, esPropia = false)
        }

        return null
    }

    // ── CAMBIAR_VIENDO (As) ───────────────────

    private fun decidirDuranteCambiarViendo(
        memoria: MemoriaBot,
        vista: VistaParcialSala,
        poder: com.aguado.bratagame.CartaPoderActiva,
        estaEspiando: Boolean
    ): DecisionBot {
        if (!estaEspiando) {
            // Fase 1: elegir una carta ajena para espiar
            val objetivo = elegirCartaRivalParaCambiarViendo(memoria, vista)
            return if (objetivo != null) {
                DecisionBot.EspiarCartaCambioViendo(cartaId = objetivo.cartaId)
            } else {
                // Sin objetivo ajeno (no debería pasar porque deberiaActivarPoder
                // ya validó), regresar
                DecisionBot.RegresarCartaEspiada
            }
        }

        // Fase 2: ya espié una carta ajena. Decidir si intercambio.
        val cartaEspiada = buscarCartaEspiadaEnVista(memoria, vista, poder.cartaEspiandoId)
            ?: return DecisionBot.RegresarCartaEspiada

        val valorEspiada = valorPuntos(cartaEspiada.valor, cartaEspiada.palo)
        val miPeor = peorCartaPropiaConocida(memoria)

        if (miPeor != null) {
            val valorMiPeor = valorPuntos(miPeor.valor, miPeor.palo)
            val posMiPeor = posicionDePeorConocida(memoria)

            // Mejora estricta: si la espiada vale menos que mi peor, intercambiar
            if (valorEspiada < valorMiPeor && posMiPeor != null) {
                val miCartaId = vista.miEstadoPosiciones[posMiPeor]?.cartaId
                if (miCartaId != null) {
                    return DecisionBot.ConfirmarSwapViendo(
                        jugadorAId = cartaEspiada.propietarioId,
                        cartaAId = cartaEspiada.cartaId,
                        jugadorBId = vista.miId,
                        cartaBId = miCartaId
                    )
                }
            }
        } else {
            // No conozco ninguna propia. Si la espiada vale POCO (≤4), me conviene
            // intercambiar contra una posición propia desconocida (apuesta razonable).
            if (valorEspiada <= 4) {
                val posDesconocida = primeraPosicionPropiaDesconocida(memoria, vista)
                if (posDesconocida != null) {
                    val miCartaId = vista.miEstadoPosiciones[posDesconocida]?.cartaId
                    if (miCartaId != null) {
                        return DecisionBot.ConfirmarSwapViendo(
                            jugadorAId = cartaEspiada.propietarioId,
                            cartaAId = cartaEspiada.cartaId,
                            jugadorBId = vista.miId,
                            cartaBId = miCartaId
                        )
                    }
                }
            }
        }

        // No mejora: regresar
        return DecisionBot.RegresarCartaEspiada
    }

    /**
     * Política de elección para CAMBIAR_VIENDO fase 1:
     *   Buscar carta ajena que vale la pena espiar. Prioridad:
     *   1. Rival con posición desconocida (info parcial sobre él).
     *   2. Rival con posición conocida-alta (sé que vale mucho).
     */
    private fun elegirCartaRivalParaCambiarViendo(
        memoria: MemoriaBot,
        vista: VistaParcialSala
    ): CartaEspiable? {
        // 1. Rival con desconocida
        for (rivalId in vista.rivalesActivos()) {
            val posicionesOcupadas = vista.posicionesOcupadasPorJugador[rivalId] ?: continue
            for (pos in posicionesOcupadas) {
                if (BotMemory.obtenerCartaRivalConocida(memoria, rivalId, pos) != null) continue
                val cartaId = obtenerIdCartaRival(vista, rivalId, pos) ?: continue
                return CartaEspiable(cartaId = cartaId, esPropia = false)
            }
        }

        // 2. Rival con conocida-alta
        for (rivalId in vista.rivalesActivos()) {
            val posicionesOcupadas = vista.posicionesOcupadasPorJugador[rivalId] ?: continue
            for (pos in posicionesOcupadas) {
                val conocida = BotMemory.obtenerCartaRivalConocida(memoria, rivalId, pos) ?: continue
                if (valorPuntos(conocida.valor, conocida.palo) >= 10) {
                    val cartaId = obtenerIdCartaRival(vista, rivalId, pos) ?: continue
                    return CartaEspiable(cartaId = cartaId, esPropia = false)
                }
            }
        }

        // 3. Fallback: primera ajena
        for (rivalId in vista.rivalesActivos()) {
            val posicionesOcupadas = vista.posicionesOcupadasPorJugador[rivalId] ?: continue
            val primera = posicionesOcupadas.firstOrNull() ?: continue
            val cartaId = obtenerIdCartaRival(vista, rivalId, primera) ?: continue
            return CartaEspiable(cartaId = cartaId, esPropia = false)
        }

        return null
    }

    // ── CAMBIAR_SIN_VER (J, Q) ────────────────

    private fun decidirDuranteCambiarSinVer(
        memoria: MemoriaBot,
        vista: VistaParcialSala
    ): DecisionBot {
        // Plan: intercambiar mi peor conocida (≥10) por una posición desconocida
        // ajena, o por una propia desconocida si no hay ajena disponible.

        val miPeor = peorCartaPropiaConocida(memoria) ?: return DecisionBot.NoOp
        val valorMiPeor = valorPuntos(miPeor.valor, miPeor.palo)
        if (valorMiPeor < 10) return DecisionBot.NoOp

        val posMiPeor = posicionDePeorConocida(memoria) ?: return DecisionBot.NoOp
        val miCartaId = vista.miEstadoPosiciones[posMiPeor]?.cartaId ?: return DecisionBot.NoOp

        // 1. Buscar posición desconocida en rivales
        for (rivalId in vista.rivalesActivos()) {
            val posicionesOcupadas = vista.posicionesOcupadasPorJugador[rivalId] ?: continue
            for (pos in posicionesOcupadas) {
                if (BotMemory.obtenerCartaRivalConocida(memoria, rivalId, pos) != null) continue
                val cartaId = obtenerIdCartaRival(vista, rivalId, pos) ?: continue
                return DecisionBot.ConfirmarSwapSinVer(
                    jugadorAId = vista.miId,
                    cartaAId = miCartaId,
                    jugadorBId = rivalId,
                    cartaBId = cartaId
                )
            }
        }

        // 2. Si no hay desconocida ajena, buscar una posición propia desconocida
        val miPosDesconocida = primeraPosicionPropiaDesconocida(memoria, vista)
        if (miPosDesconocida != null && miPosDesconocida != posMiPeor) {
            val cartaIdDestino = vista.miEstadoPosiciones[miPosDesconocida]?.cartaId
            if (cartaIdDestino != null) {
                return DecisionBot.ConfirmarSwapSinVer(
                    jugadorAId = vista.miId,
                    cartaAId = miCartaId,
                    jugadorBId = vista.miId,
                    cartaBId = cartaIdDestino
                )
            }
        }

        // Sin opción razonable
        return DecisionBot.NoOp
    }

    // ── DESCARTE_FREE_SELECCION ───────────────

    private fun decidirDuranteDescarteFree(
        memoria: MemoriaBot,
        vista: VistaParcialSala
    ): DecisionBot {
        // Política: descartar la peor carta propia conocida si la tengo;
        // si no, descartar la posición desconocida más reciente.

        val posPeor = posicionDePeorConocida(memoria)
        if (posPeor != null) {
            return DecisionBot.ConfirmarDescarteFree(posPeor)
        }

        val posDesconocida = primeraPosicionPropiaDesconocida(memoria, vista)
        if (posDesconocida != null) {
            return DecisionBot.ConfirmarDescarteFree(posDesconocida)
        }

        // No tengo cartas en mesa (raro pero defensive). Regresar para no atascar.
        return DecisionBot.RegresarCartaEspiada
    }

    // ─────────────────────────────────────────
    // ESTRUCTURAS Y HELPERS AUXILIARES PARA PODERES
    // ─────────────────────────────────────────

    private data class CartaEspiable(
        val cartaId: String,
        val esPropia: Boolean
    )

    private data class CartaEspiadaResolved(
        val cartaId: String,
        val valor: String,
        val palo: String,
        val propietarioId: String,
        val esPropia: Boolean
    )

    /**
     * Busca la carta espiada actualmente activa, reconstruyendo su valor/palo/dueño.
     *
     * Importante: solo conocemos el VALOR si veníamos espiándola legítimamente.
     * Para una carta espiada propia, leemos la memoria.
     * Para una carta espiada ajena, también la memoria DEBE haberla aprendido
     * vía el evento "bot espió carta ajena" (lo añadiremos en BotMemory).
     *
     * Si el valor no está en memoria, devolvemos null (el bot regresará por
     * defecto en vez de tomar decisión a ciegas).
     */
    private fun buscarCartaEspiadaEnVista(
        memoria: MemoriaBot,
        vista: VistaParcialSala,
        cartaEspiadaId: String
    ): CartaEspiadaResolved? {
        if (cartaEspiadaId.isBlank()) return null

        // ¿Es mía?
        vista.miEstadoPosiciones.forEach { (pos, estado) ->
            if (estado.ocupada && estado.cartaId == cartaEspiadaId) {
                val conocida = BotMemory.obtenerMiPosicionConocida(memoria, pos) ?: return null
                return CartaEspiadaResolved(
                    cartaId = cartaEspiadaId,
                    valor = conocida.valor,
                    palo = conocida.palo,
                    propietarioId = vista.miId,
                    esPropia = true
                )
            }
        }

        // ¿Es de un rival?
        for (rivalId in vista.rivalesActivos()) {
            val posicionesOcupadas = vista.posicionesOcupadasPorJugador[rivalId] ?: continue
            for (pos in posicionesOcupadas) {
                val cartaIdRival = obtenerIdCartaRival(vista, rivalId, pos)
                if (cartaIdRival == cartaEspiadaId) {
                    val conocida = BotMemory.obtenerCartaRivalConocida(memoria, rivalId, pos)
                        ?: return null
                    return CartaEspiadaResolved(
                        cartaId = cartaEspiadaId,
                        valor = conocida.valor,
                        palo = conocida.palo,
                        propietarioId = rivalId,
                        esPropia = false
                    )
                }
            }
        }

        return null
    }

    /**
     * VistaParcialSala no expone los ids de cartas ajenas por defecto (solo
     * cuenta posiciones ocupadas). Pero los hace falta para acciones como
     * EspiarCarta(cartaId).
     *
     * Trabajo: necesitamos que el orquestador pase también los ids de las
     * cartas ajenas en la vista. Esto se hace en VistaParcialBuilder.
     * En Fase 2B vamos a extender posicionesOcupadasPorJugador con un mapa
     * de cartaId por posición, o usar otra estructura.
     *
     * Por ahora, hasta que extendamos VistaParcialSala, esta función
     * devolverá null si no encuentra el id. El BotBrain devuelve NoOp en
     * ese caso y el orquestador no ejecuta.
     *
     * NOTA IMPORTANTE: esto se resuelve extendiendo VistaParcialSala con
     * un mapa de cartIds por posición ajena. Lo hago como parte de Fase 2B.
     */
    private fun obtenerIdCartaRival(
        vista: VistaParcialSala,
        rivalId: String,
        posicion: Int
    ): String? {
        return vista.cartasIdsRivales[rivalId]?.get(posicion)
    }

    private fun tengoPosicionPropiaDesconocida(
        memoria: MemoriaBot,
        vista: VistaParcialSala
    ): Boolean {
        for (pos in 0..3) {
            val estado = vista.miEstadoPosiciones[pos] ?: continue
            if (estado.ocupada && !BotMemory.conoceMiPosicion(memoria, pos)) {
                return true
            }
        }
        return false
    }

    private fun tengoRivalConPosicionEnMesa(vista: VistaParcialSala): Boolean {
        return vista.rivalesActivos().any { rivalId ->
            (vista.cuentaCartasPorJugador[rivalId] ?: 0) > 0
        }
    }

    private fun totalCartasEnMesa(vista: VistaParcialSala): Int {
        return vista.jugadoresOrden.sumOf { id ->
            vista.cuentaCartasPorJugador[id] ?: 0
        }
    }

    private fun tengoPosicionConocidaConValor(
        memoria: MemoriaBot,
        minimo: Int
    ): Boolean {
        return memoria.cartasPropiasConocidas.values.any { c ->
            valorPuntos(c.valor, c.palo) >= minimo
        }
    }

    private fun peorPosicionPropiaEstimada(
        memoria: MemoriaBot,
        vista: VistaParcialSala
    ): Int {
        var maxValor = 0
        for (pos in 0..3) {
            val estado = vista.miEstadoPosiciones[pos] ?: continue
            if (!estado.ocupada) continue
            val v = BotMemory.obtenerMiPosicionConocida(memoria, pos)?.let {
                valorPuntos(it.valor, it.palo)
            } ?: VALOR_ESPERADO_DESCONOCIDA
            if (v > maxValor) maxValor = v
        }
        return maxValor
    }

    // ─────────────────────────────────────────
    // COMODÍN: elegir valor a declarar
    //
    // Estrategia simple (Fase 2A):
    //   1. Si tengo K negro conocido en mesa → declarar K (cadenar propio)
    //   2. Si no, declarar el valor más común en mi descarte histórico
    //   3. Fallback seguro: K picas (vale 13, alto)
    // ─────────────────────────────────────────

    private fun elegirValorComodin(memoria: MemoriaBot): Pair<String, String> {
        // Estrategia 1: cadenar K si tengo K negro conocido
        memoria.cartasPropiasConocidas.values.forEach { c ->
            if (c.valor == "K" && (c.palo == "picas" || c.palo == "treboles")) {
                return Pair("K", c.palo)
            }
        }

        // Estrategia 2: valor más común en descarte (max del conteo)
        if (memoria.conteoValoresVistos.isNotEmpty()) {
            val valorMasComun = memoria.conteoValoresVistos
                .maxByOrNull { it.value }
                ?.key
            if (valorMasComun != null && valorMasComun != "JKR") {
                return Pair(valorMasComun, "corazones")
            }
        }

        // Estrategia 3: fallback K picas
        return Pair("K", "picas")
    }

    // ─────────────────────────────────────────
    // ESTIMACIÓN DE MANO PROPIA
    //
    // Cada posición ocupada se estima:
    //   - Si conocida → valor real.
    //   - Si desconocida → valor esperado conservador (6 puntos).
    //
    // Posiciones vacías = 0.
    // ─────────────────────────────────────────

    private fun estimarManoPropia(
        memoria: MemoriaBot,
        vista: VistaParcialSala
    ): Int {
        var total = 0
        for (pos in 0..3) {
            val estado = vista.miEstadoPosiciones[pos] ?: continue
            if (!estado.ocupada) continue

            val conocida = BotMemory.obtenerMiPosicionConocida(memoria, pos)
            total += if (conocida != null) {
                valorPuntos(conocida.valor, conocida.palo)
            } else {
                VALOR_ESPERADO_DESCONOCIDA
            }
        }
        return total
    }

    // ─────────────────────────────────────────
    // HELPERS DE POSICIONES
    // ─────────────────────────────────────────

    private fun peorCartaPropiaConocida(memoria: MemoriaBot): CartaConocida? {
        return memoria.cartasPropiasConocidas.values
            .maxByOrNull { valorPuntos(it.valor, it.palo) }
    }

    private fun posicionDePeorConocida(memoria: MemoriaBot): Int? {
        val peor = peorCartaPropiaConocida(memoria) ?: return null
        return memoria.cartasPropiasConocidas
            .entries
            .firstOrNull { it.value == peor }
            ?.key
            ?.toIntOrNull()
    }

    private fun primeraPosicionVaciaPropia(vista: VistaParcialSala): Int? {
        // Orden visual: filas alejadas primero (2, 3), luego cercanas (0, 1)
        listOf(2, 3, 0, 1).forEach { pos ->
            val estado = vista.miEstadoPosiciones[pos]
            if (estado != null && !estado.ocupada) return pos
        }
        return null
    }

    private fun primeraPosicionPropiaDesconocida(
        memoria: MemoriaBot,
        vista: VistaParcialSala
    ): Int? {
        // Posición ocupada cuyo valor no conocemos
        listOf(2, 3, 0, 1).forEach { pos ->
            val estado = vista.miEstadoPosiciones[pos] ?: return@forEach
            if (!estado.ocupada) return@forEach
            if (!BotMemory.conoceMiPosicion(memoria, pos)) return pos
        }
        return null
    }

    // ─────────────────────────────────────────
    // VALOR EN PUNTOS DE UNA CARTA
    //
    // Replica HandEvaluator.valorPuntuacion sin importarlo,
    // para mantener este archivo libre de dependencias del módulo de juego.
    // ─────────────────────────────────────────

    private fun valorPuntos(carta: Carta): Int = valorPuntos(carta.valor, carta.palo)

    private fun valorPuntos(valor: String, palo: String): Int {
        return when (valor.uppercase()) {
            "A" -> 20
            "2" -> 0
            "J" -> 11
            "Q" -> 12
            "K" -> if (palo == "treboles" || palo == "picas") 13 else 1
            "JKR" -> 20
            else -> valor.toIntOrNull() ?: 0
        }
    }

    // ─────────────────────────────────────────
    // CONSTANTES DE BALANCE
    // ─────────────────────────────────────────

    /** Valor esperado conservador de una posición desconocida.
     *  Promedio aproximado de una baraja considerando todos los valores. */
    private const val VALOR_ESPERADO_DESCONOCIDA = 6
}

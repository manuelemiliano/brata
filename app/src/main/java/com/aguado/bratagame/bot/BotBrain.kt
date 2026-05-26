package com.aguado.bratagame.bot

import com.aguado.bratagame.Carta

// ─────────────────────────────────────────────
// BOT BRAIN (Fase 2A)
//
// Cerebro de decisión del bot. Función pura: recibe (memoria, vista, …)
// y devuelve una DecisionBot.
//
// REGLA DURA: este archivo NUNCA accede a Sala directamente.
// Solo lee VistaParcialSala (vista) y MemoriaBot (memoria).
// Esta es la frontera que protege el "fair play".
//
// Cobertura Fase 2A:
//   - Inicio de turno: cantar BRATA, robar pozo o descarte.
//   - Post-robo: descartar, cambiar por hueco vacío, cambiar por peor conocida.
//   - Cuando aparece carta con poder en mano: SIEMPRE descartar (sin activar).
//     Fase 2B activará poderes.
//   - Sin reacciones fuera de turno (VOY, descarte espontáneo).
//
// Para qué SÍ tiene rama en Fase 2A pero está deshabilitada:
//   - El comodín "JKR" no se activa (siempre se selecciona descartar
//     declarando un valor). Esto es necesario porque el juego REQUIERE
//     definir el valor del comodín, no permite descartarlo directamente.
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
        // Fase 2A: no reaccionamos fuera de turno (sin VOY, sin espontáneo)
        if (!vista.esMiTurno()) {
            return DecisionBot.NoOp
        }

        // ── Caso 2: hay poder activo propio ──
        // Fase 2A: no debería ocurrir porque el bot nunca activa poderes,
        // pero por seguridad, si por algún motivo hay uno, regresamos.
        val poder = vista.poderActivo
        if (poder != null && poder.jugadorId == vista.miId) {
            return DecisionBot.RegresarCartaEspiada
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

        // Fase 2A: si la carta tiene poder, NO lo activamos.
        // Caemos al flujo normal de descartar/cambiar.
        // Fase 2B agregará la rama "activar poder".

        val valorMano = valorPuntos(cartaEnMano)

        // Caso A: tengo hueco vacío → cambiar para llenar (apuesta gratuita)
        val huecoVacio = primeraPosicionVaciaPropia(vista)
        if (huecoVacio != null) {
            return DecisionBot.CambiarPorPosicion(huecoVacio)
        }

        // Caso B: tengo peor conocida y la mano la mejora
        val peor = peorCartaPropiaConocida(memoria)
        if (peor != null) {
            val valorPeor = valorPuntos(peor.valor, peor.palo)
            val posicionPeor = posicionDePeorConocida(memoria) ?: return DecisionBot.Descartar

            if (valorMano < valorPeor) {
                return DecisionBot.CambiarPorPosicion(posicionPeor)
            }
            return DecisionBot.Descartar
        }

        // Caso C: no conozco ninguna mía
        // Si la mano es baja, vale apostar; si no, descartar
        if (valorMano <= 4) {
            val posicionDesconocida = primeraPosicionPropiaDesconocida(memoria, vista)
            if (posicionDesconocida != null) {
                return DecisionBot.CambiarPorPosicion(posicionDesconocida)
            }
        }

        return DecisionBot.Descartar
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

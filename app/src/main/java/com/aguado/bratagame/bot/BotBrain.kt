package com.aguado.bratagame.bot

import com.aguado.bratagame.AdelantadoPendiente
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

        // ── Caso 0 (PRIORIDAD ABSOLUTA): ──
        // Soy el perjudicado por un adelantado. Debo responder antes de cualquier
        // otra acción. Esto pasa cuando yo había activado ESPIAR o CAMBIAR_VIENDO
        // y un rival descartó espontáneamente antes de que yo terminara mi poder.
        val adelantado = vista.adelantadoPendiente
        if (adelantado != null &&
            adelantado.activo &&
            adelantado.jugadorPerjudicadoId == vista.miId
        ) {
            return decidirRespuestaAdelantado(memoria, vista, adelantado)
        }

        // ── Caso 1: NO es mi turno ──
        // Fase 3A: el bot puede actuar fuera de turno sólo para descarte
        // espontáneo. VOY y otras reacciones llegan en fases posteriores.
        if (!vista.esMiTurno()) {
            return decidirFueraDeTurno(memoria, vista)
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
    // FUERA DE TURNO (Fase 3A)
    //
    // El bot reacciona al estado de la mesa fuera de su turno.
    // Por ahora solo descarte espontáneo. VOY y respuesta a adelantado
    // llegan en Fases 3B/3C.
    // ─────────────────────────────────────────

    private fun decidirFueraDeTurno(
        memoria: MemoriaBot,
        vista: VistaParcialSala
    ): DecisionBot {
        // Si estoy descalificado o no estoy en sala, no actúo
        if (vista.miId in vista.jugadoresDescalificados) return DecisionBot.NoOp

        // ── PRIORIDAD: VOY pendiente ──
        // Si hay un VOY activo, manejarlo es prioritario porque la ventana
        // expira en pocos segundos. Distintos sub-casos según fase.
        val voy = vista.voyPendiente
        if (voy != null && voy.activo) {
            return decidirVoy(memoria, vista, voy)
        }

        // No interfiero con un poder ajeno en curso (regla 5.4 - paciencia).
        // Adelantarme generaría castigo (regla del adelantado).
        if (vista.poderActivo != null) return DecisionBot.NoOp

        // Adelantado pendiente sin resolver: esperar (caso del bot perjudicado
        // se maneja en Caso 0 del entry point, antes de llegar aquí).
        if (vista.adelantadoPendiente?.activo == true) return DecisionBot.NoOp

        return decidirDescarteEspontaneoFueraDeTurno(memoria, vista)
    }

    // ─────────────────────────────────────────
    // VOY (manual §6) — Fase 3C
    //
    // Tres sub-fases del VOY:
    //
    // 1. FASE "VENTANA" (≤2s): un jugador pidió robar pozo/descarte. Los demás
    //    pueden reclamar VOY si detectan que ese jugador tenía una carta del
    //    valor objetivo en mesa que olvidó descartar.
    //
    // 2. FASE "SELECCIONANDO_OBJETIVO": el reclamante elige qué carta del
    //    robador (o de otro) "sacar" como prueba del VOY.
    //
    // 3. FASE "SELECCIONANDO_ENTREGA": tras VOY exitoso, el reclamante entrega
    //    una carta propia al jugador que perdió la carta.
    //
    // Cuándo NO reclamamos:
    //   - Yo soy el jugadorRobandoId (no puedo reclamar mi propio VOY).
    //   - Yo ya soy el reclamante (en fase posterior debo actuar, no reclamar).
    //   - No conozco con certeza ninguna carta ajena cuyo valor coincida.
    //   - El objetivo es comodín no declarado (no aplica acá: valorObjetivo
    //     siempre es un valor declarado, no "JKR" en bruto).
    //
    // Riesgo: si me equivoco al seleccionar objetivo, sufro castigo (carta
    // extra del pozo). Por eso solo reclamamos con certeza alta.
    // ─────────────────────────────────────────

    private fun decidirVoy(
        memoria: MemoriaBot,
        vista: VistaParcialSala,
        voy: com.aguado.bratagame.VoyPendiente
    ): DecisionBot {
        return when (voy.fase) {
            "VENTANA" -> decidirVoyFaseVentana(memoria, vista, voy)
            "SELECCIONANDO_OBJETIVO" -> decidirVoyFaseObjetivo(memoria, vista, voy)
            "SELECCIONANDO_ENTREGA" -> decidirVoyFaseEntrega(memoria, vista, voy)
            else -> DecisionBot.NoOp
        }
    }

    private fun decidirVoyFaseVentana(
        memoria: MemoriaBot,
        vista: VistaParcialSala,
        voy: com.aguado.bratagame.VoyPendiente
    ): DecisionBot {
        // Ya hay un reclamante → no compito
        if (voy.reclamadoPorJugadorId.isNotBlank()) return DecisionBot.NoOp

        // Verificar que tengo carta para entregar (validación previa de GameActions)
        val tengoCartaParaEntregar = (0..3).any { pos ->
            vista.miEstadoPosiciones[pos]?.ocupada == true
        }
        if (!tengoCartaParaEntregar) return DecisionBot.NoOp

        // Valor objetivo: la cima del descarte al momento del intento de robo
        val valorObjetivo = voy.valorObjetivo
        if (valorObjetivo.isBlank() || valorObjetivo == "JKR") return DecisionBot.NoOp

        // ¿Quién puede ser el "olvidadizo" que la reclamación va a denunciar?
        // - Si yo soy el robador: cualquier OTRO jugador que tenga carta del
        //   valor objetivo en mesa.
        // - Si yo NO soy el robador: el robador es el sospechoso prioritario
        //   (era él quien debió descartar antes de robar). Pero también vale
        //   sobre cualquier otro rival que tenga ese valor.
        //
        // En ambos casos, busco entre TODOS los rivales activos (que no sea yo)
        // si conozco con certeza una carta del valor objetivo en su mesa.
        // Priorizo al robador si soy un observador (más probabilidad de ser
        // su olvido real); si yo soy el robador, voy directo a cualquier otro.

        val candidatos: List<String> = if (voy.jugadorRobandoId == vista.miId) {
            // Soy el robador: busco en cualquier otro rival
            vista.rivalesActivos()
        } else {
            // No soy el robador: priorizo al robador, luego al resto
            val otrosRivales = vista.rivalesActivos().filter { it != voy.jugadorRobandoId }
            listOf(voy.jugadorRobandoId) + otrosRivales
        }

        val hayCoincidenciaConocida = candidatos.any { rivalId ->
            (0..3).any { pos ->
                val conocida = BotMemory.obtenerCartaRivalConocida(memoria, rivalId, pos)
                conocida != null &&
                        conocida.valor == valorObjetivo &&
                        conocida.valor != "JKR"
            }
        }

        // Sin certeza → no reclamo (evitar castigo)
        if (!hayCoincidenciaConocida) return DecisionBot.NoOp

        return DecisionBot.ReclamarVoy
    }

    private fun decidirVoyFaseObjetivo(
        memoria: MemoriaBot,
        vista: VistaParcialSala,
        voy: com.aguado.bratagame.VoyPendiente
    ): DecisionBot {
        // Solo el reclamante actúa en esta fase
        if (voy.reclamadoPorJugadorId != vista.miId) return DecisionBot.NoOp

        val valorObjetivo = voy.valorObjetivo
        if (valorObjetivo.isBlank()) return DecisionBot.NoOp

        // Buscar la carta concreta a "sacar" como prueba del VOY.
        // GameActions valida que objetivo != yo, así que NUNCA apunto a mí mismo.
        //
        // Prioridad 1: el robador, si NO soy yo (era el sospechoso natural).
        // Prioridad 2: cualquier otro rival activo con carta conocida del valor.
        val rivalRobadorId = voy.jugadorRobandoId

        if (rivalRobadorId != vista.miId) {
            val posicionEnRobador = (0..3).firstOrNull { pos ->
                val conocida = BotMemory.obtenerCartaRivalConocida(memoria, rivalRobadorId, pos)
                conocida != null && conocida.valor == valorObjetivo && conocida.valor != "JKR"
            }
            if (posicionEnRobador != null) {
                return DecisionBot.SeleccionarObjetivoVoy(
                    rivalId = rivalRobadorId,
                    posicion = posicionEnRobador
                )
            }
        }

        // Prioridad 2: cualquier rival activo que NO sea yo (ni el robador ya intentado)
        val rivalesPosibles = vista.rivalesActivos().filter {
            it != vista.miId && it != rivalRobadorId
        }
        rivalesPosibles.forEach { rivalId ->
            val pos = (0..3).firstOrNull { pos ->
                val conocida = BotMemory.obtenerCartaRivalConocida(memoria, rivalId, pos)
                conocida != null && conocida.valor == valorObjetivo && conocida.valor != "JKR"
            }
            if (pos != null) {
                return DecisionBot.SeleccionarObjetivoVoy(rivalId = rivalId, posicion = pos)
            }
        }

        // Sin objetivo concreto: situación rara (reclamé sin saber). Abortar.
        return DecisionBot.NoOp
    }

    private fun decidirVoyFaseEntrega(
        memoria: MemoriaBot,
        vista: VistaParcialSala,
        voy: com.aguado.bratagame.VoyPendiente
    ): DecisionBot {
        // Solo el reclamante entrega
        if (voy.reclamadoPorJugadorId != vista.miId) return DecisionBot.NoOp

        // Estrategia: entregar la peor conocida (mayor valor en puntos) entre
        // las posiciones ocupadas. Si no tengo conocidas, fallback a posición
        // alejada ocupada (igual que decidirRespuestaAdelantado).
        val posicionesOcupadas = (0..3).filter { pos ->
            vista.miEstadoPosiciones[pos]?.ocupada == true
        }

        if (posicionesOcupadas.isEmpty()) {
            // No debería ocurrir (validación previa exige tener carta).
            // Fallback defensivo: posición 0; GameActions lo rechazará si está vacía.
            return DecisionBot.EntregarCartaVoy(posicion = 0)
        }

        val mejorCandidata = posicionesOcupadas
            .mapNotNull { pos ->
                val conocida = BotMemory.obtenerMiPosicionConocida(memoria, pos)
                if (conocida != null) {
                    pos to valorPuntos(conocida.valor, conocida.palo)
                } else {
                    null
                }
            }
            .maxByOrNull { (_, valor) -> valor }

        if (mejorCandidata != null) {
            return DecisionBot.EntregarCartaVoy(posicion = mejorCandidata.first)
        }

        // Fallback: posición más alejada
        val ordenPreferencia = listOf(2, 3, 0, 1)
        val posicionAlejada = ordenPreferencia.firstOrNull { it in posicionesOcupadas }
            ?: posicionesOcupadas.first()

        return DecisionBot.EntregarCartaVoy(posicion = posicionAlejada)
    }


    // ─────────────────────────────────────────
    // DESCARTE ESPONTÁNEO (manual §5.3, §5.5, §7.1)
    //
    // Condiciones para que el bot dispare descarte espontáneo:
    //   1. Existe carta en cima del descarte.
    //   2. El bot tiene una posición propia CONOCIDA con valor coincidente.
    //   3. El valor de esa carta es >= UMBRAL_DESCARTE_ESPONTANEO_MIN (3).
    //   4. La carta no es comodín (regla 7.1: no se puede descartar JKR oculto).
    //
    // Si encuentra varias coincidencias, prioriza la de MAYOR valor (descartar
    // la más cara primero).
    // ─────────────────────────────────────────

    private fun decidirDescarteEspontaneoFueraDeTurno(
        memoria: MemoriaBot,
        vista: VistaParcialSala
    ): DecisionBot {
        val cima = vista.ultimaCartaDescarte ?: return DecisionBot.NoOp

        // Regla 7.1: no se puede descartar comodín espontáneamente.
        // Pero SÍ podemos descartar contra una cima que es comodín, porque la
        // cima ya tiene un valor declarado por su dueño. Nuestra protección
        // es no descartar JKR PROPIO, no ignorar JKR como cima.
        val valorCima = cima.valor

        // Buscar carta propia conocida que coincida en valor con la cima
        val candidatas = memoria.cartasPropiasConocidas
            .filterValues { conocida ->
                conocida.valor == valorCima &&
                        conocida.valor != "JKR" &&   // no descartar comodín propio
                        valorPuntos(conocida.valor, conocida.palo) >= UMBRAL_DESCARTE_ESPONTANEO_MIN
            }

        if (candidatas.isEmpty()) return DecisionBot.NoOp

        // Verificar que la posición sigue ocupada en la vista (defensivo)
        val candidatasValidas = candidatas.filter { (posStr, _) ->
            val pos = posStr.toIntOrNull() ?: return@filter false
            vista.miEstadoPosiciones[pos]?.ocupada == true
        }

        if (candidatasValidas.isEmpty()) return DecisionBot.NoOp

        // Elegir la de mayor valor (descartar la más cara primero)
        val (posStrElegida, _) = candidatasValidas.maxBy { (_, conocida) ->
            valorPuntos(conocida.valor, conocida.palo)
        }

        val posicionElegida = posStrElegida.toIntOrNull() ?: return DecisionBot.NoOp

        return DecisionBot.DescarteEspontaneo(posicion = posicionElegida)
    }

    // ─────────────────────────────────────────
    // RESPUESTA AL ADELANTADO (manual §5.4, lado perjudicado)
    //
    // El bot había activado ESPIAR o CAMBIAR_VIENDO. Antes de que terminara
    // de elegir/espiar, un rival descartó espontáneamente una carta del valor
    // de su poder. Debe ceder una carta propia al adelantado.
    //
    // Estrategia de selección:
    //   1. Mi peor posición CONOCIDA (mayor valor en puntos) → minimiza pérdida.
    //   2. Si no tengo conocidas, posición más alejada ocupada (2 > 3 > 0 > 1).
    //      Razón: las alejadas tienden a ser viejas (memorizadas al inicio o
    //      en intercambios pasados) y, en promedio, tienen valor esperado de 6.
    //      Las cercanas suelen ser más recientes (cartas que sustituí), que
    //      tienden a tener valor controlado por mí. Cedo lo más viejo.
    //   3. Si no hay ocupadas (caso patológico, no debería ocurrir), elijo 0.
    //
    // Validaciones: nunca elegir slot vacío. GameActions también lo rechazará,
    // pero es mejor evitar el error.
    // ─────────────────────────────────────────

    private fun decidirRespuestaAdelantado(
        memoria: MemoriaBot,
        vista: VistaParcialSala,
        @Suppress("UNUSED_PARAMETER") adelantado: AdelantadoPendiente
    ): DecisionBot {
        // Conjunto de posiciones propias ocupadas
        val posicionesOcupadas = (0..3).filter { pos ->
            vista.miEstadoPosiciones[pos]?.ocupada == true
        }

        if (posicionesOcupadas.isEmpty()) {
            // Caso patológico: no debería ocurrir porque para haber activado
            // un poder, el bot tenía al menos una carta en mano (que ya está
            // descartada por el flujo de adelantado). Defendemos devolviendo
            // posición 0 — GameActions lo rechazará si está vacía y abortará
            // el flujo limpiamente.
            return DecisionBot.ResolverAdelantado(posicionAEntregar = 0)
        }

        // Estrategia 1: peor posición conocida (entre las ocupadas)
        val mejorCandidata = posicionesOcupadas
            .mapNotNull { pos ->
                val conocida = BotMemory.obtenerMiPosicionConocida(memoria, pos)
                if (conocida != null) {
                    pos to valorPuntos(conocida.valor, conocida.palo)
                } else {
                    null
                }
            }
            .maxByOrNull { (_, valor) -> valor }

        if (mejorCandidata != null) {
            return DecisionBot.ResolverAdelantado(posicionAEntregar = mejorCandidata.first)
        }

        // Estrategia 2: posición más alejada ocupada (2 > 3 > 0 > 1)
        val ordenPreferencia = listOf(2, 3, 0, 1)
        val posicionAlejada = ordenPreferencia.firstOrNull { it in posicionesOcupadas }
            ?: posicionesOcupadas.first()

        return DecisionBot.ResolverAdelantado(posicionAEntregar = posicionAlejada)
    }

    // ─────────────────────────────────────────
    // INICIO DE TURNO
    // ─────────────────────────────────────────

    private fun decidirInicioDeTurno(
        memoria: MemoriaBot,
        vista: VistaParcialSala
    ): DecisionBot {

        // Caso defensivo: si soy el cantor y por algún motivo me dieron turno,
        // intento pasar. TurnManager normalmente nos salta, pero protección extra.
        if (vista.brataActivada && vista.brataJugadorId == vista.miId) {
            return DecisionBot.PasarTurnoBrata
        }

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
        // ── Modo agresivo de última ronda (Fase 4) ──
        // Cuando hay BRATA activada y existe víctima óptima distinta de mí,
        // los umbrales de activación bajan: el costo de no atacar es que la
        // víctima sigue ganando.
        val enUltimaRonda = esUltimaRonda(vista)
        val victimaOptima = if (enUltimaRonda) encontrarVictimaOptima(memoria, vista) else null
        val hayObjetivoOfensivo = victimaOptima != null && victimaOptima != vista.miId

        return when (tipoPoder) {
            TipoPoder.ESPIAR -> {
                // En última ronda con objetivo claro: espiar la víctima si
                // tiene desconocidas (la info habilita un CAMBIAR_VIENDO
                // posterior con otra carta, o sirve para predecir su mano).
                if (hayObjetivoOfensivo) {
                    val tieneDesconocidas = victimaTieneDesconocidas(memoria, vista, victimaOptima!!)
                    if (tieneDesconocidas) return true
                    // Si ya conozco todo de la víctima, ESPIAR no aporta nada.
                    // Mantengo la heurística defensiva normal:
                }
                if (tengoPosicionPropiaDesconocida(memoria, vista)) return true
                if (tengoRivalConPosicionEnMesa(vista)) return true
                false
            }

            TipoPoder.CAMBIAR_VIENDO -> {
                // GameRules exige: ≥1 carta ajena Y ≥2 cartas en mesa total
                if (!tengoRivalConPosicionEnMesa(vista)) return false
                if (totalCartasEnMesa(vista) < 2) return false

                // En última ronda: activar siempre que haya víctima atacable.
                // El A vale 20 puntos descartado, así que casi siempre vale
                // más usarlo como ofensiva.
                if (hayObjetivoOfensivo) return true

                // Fuera de última ronda: solo activar si mi peor estimada vale ≥8
                val miPeorEstimada = peorPosicionPropiaEstimada(memoria, vista)
                miPeorEstimada >= 8
            }

            TipoPoder.CAMBIAR_SIN_VER -> {
                // GameRules exige: ≥2 cartas en mesa total
                if (totalCartasEnMesa(vista) < 2) return false

                // En última ronda con víctima: activar si tengo algo "malo"
                // que pueda sacrificar (carta propia ≥8 conocida).
                // Sin algo malo conocido, J/Q a ciegas puede empeorar mi mano.
                if (hayObjetivoOfensivo) {
                    return tengoPosicionConocidaConValor(memoria, minimo = 8)
                }

                // Fuera de última ronda: solo activar si tengo conocida ≥10
                tengoPosicionConocidaConValor(memoria, minimo = 10)
            }

            TipoPoder.DESCARTE_FREE_SELECCION,
            TipoPoder.NINGUNO -> false
        }
    }

    /**
     * ¿La víctima óptima tiene al menos una posición ocupada cuyo valor el
     * bot no conoce todavía? Si todas son conocidas, espiarla es desperdicio.
     */
    private fun victimaTieneDesconocidas(
        memoria: MemoriaBot,
        vista: VistaParcialSala,
        victimaId: String
    ): Boolean {
        val ocupadas = vista.posicionesOcupadasPorJugador[victimaId] ?: return false
        return ocupadas.any { pos ->
            BotMemory.obtenerCartaRivalConocida(memoria, victimaId, pos) == null
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
     *
     * Fuera de última ronda:
     *   1. Mi posición propia ocupada y desconocida (ganancia informativa
     *      sin riesgo).
     *   2. Carta ajena desconocida.
     *   3. Cualquier carta ajena.
     *
     * En última ronda (Fase 4):
     *   1. Víctima óptima: una posición DESCONOCIDA suya (preparo CAMBIAR_VIENDO
     *      futuro o aprendo su mano para predicción).
     *   2. Resto: política normal.
     */
    private fun elegirCartaParaEspiar(
        memoria: MemoriaBot,
        vista: VistaParcialSala
    ): CartaEspiable? {
        // Última ronda: priorizar a la víctima óptima
        if (esUltimaRonda(vista)) {
            val victima = encontrarVictimaOptima(memoria, vista)
            if (victima != null && victima != vista.miId) {
                val ocupadas = vista.posicionesOcupadasPorJugador[victima] ?: emptySet()
                // 1a. Una desconocida de la víctima
                val posDesconocida = ocupadas.firstOrNull { pos ->
                    BotMemory.obtenerCartaRivalConocida(memoria, victima, pos) == null
                }
                if (posDesconocida != null) {
                    val cartaId = obtenerIdCartaRival(vista, victima, posDesconocida)
                    if (cartaId != null) {
                        return CartaEspiable(cartaId = cartaId, esPropia = false)
                    }
                }
                // 1b. Cualquier carta de la víctima (todas conocidas → revalido)
                val pos = ocupadas.firstOrNull()
                if (pos != null) {
                    val cartaId = obtenerIdCartaRival(vista, victima, pos)
                    if (cartaId != null) {
                        return CartaEspiable(cartaId = cartaId, esPropia = false)
                    }
                }
            }
        }

        // Política normal (también fallback cuando víctima no aplica)
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
     *
     * Fuera de última ronda:
     *   1. Rival con posición desconocida.
     *   2. Rival con posición conocida-alta (≥10).
     *   3. Fallback: primera ajena.
     *
     * En última ronda (Fase 4):
     *   1. Víctima óptima: posición desconocida → conocida-alta → cualquier.
     *   2. Resto: política normal.
     */
    private fun elegirCartaRivalParaCambiarViendo(
        memoria: MemoriaBot,
        vista: VistaParcialSala
    ): CartaEspiable? {
        // Última ronda: priorizar víctima óptima
        if (esUltimaRonda(vista)) {
            val victima = encontrarVictimaOptima(memoria, vista)
            if (victima != null && victima != vista.miId) {
                val candidata = elegirEspiableEnRival(memoria, vista, victima)
                if (candidata != null) return candidata
            }
        }

        // Política normal
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

    /**
     * Elige una carta espiable en la mesa de un rival específico.
     * Orden: desconocida → conocida-alta (≥10) → primera ocupada.
     * Helper para Fase 4 cuando ya tenemos un objetivo específico.
     */
    private fun elegirEspiableEnRival(
        memoria: MemoriaBot,
        vista: VistaParcialSala,
        rivalId: String
    ): CartaEspiable? {
        val ocupadas = vista.posicionesOcupadasPorJugador[rivalId] ?: return null

        // 1. Posición desconocida
        for (pos in ocupadas) {
            if (BotMemory.obtenerCartaRivalConocida(memoria, rivalId, pos) != null) continue
            val cartaId = obtenerIdCartaRival(vista, rivalId, pos) ?: continue
            return CartaEspiable(cartaId = cartaId, esPropia = false)
        }

        // 2. Posición conocida-alta
        for (pos in ocupadas) {
            val conocida = BotMemory.obtenerCartaRivalConocida(memoria, rivalId, pos) ?: continue
            if (valorPuntos(conocida.valor, conocida.palo) >= 10) {
                val cartaId = obtenerIdCartaRival(vista, rivalId, pos) ?: continue
                return CartaEspiable(cartaId = cartaId, esPropia = false)
            }
        }

        // 3. Cualquier ocupada
        val pos = ocupadas.firstOrNull() ?: return null
        val cartaId = obtenerIdCartaRival(vista, rivalId, pos) ?: return null
        return CartaEspiable(cartaId = cartaId, esPropia = false)
    }

    // ── CAMBIAR_SIN_VER (J, Q) ────────────────

    private fun decidirDuranteCambiarSinVer(
        memoria: MemoriaBot,
        vista: VistaParcialSala
    ): DecisionBot {
        // Umbral para considerar "vale la pena sacrificar mi peor":
        //   - Última ronda: ≥8 (más agresivo).
        //   - Resto: ≥10 (más conservador).
        val umbralSacrificio = if (esUltimaRonda(vista)) 8 else 10

        val miPeor = peorCartaPropiaConocida(memoria) ?: return DecisionBot.NoOp
        val valorMiPeor = valorPuntos(miPeor.valor, miPeor.palo)
        if (valorMiPeor < umbralSacrificio) return DecisionBot.NoOp

        val posMiPeor = posicionDePeorConocida(memoria) ?: return DecisionBot.NoOp
        val miCartaId = vista.miEstadoPosiciones[posMiPeor]?.cartaId ?: return DecisionBot.NoOp

        // Última ronda: priorizar víctima óptima
        if (esUltimaRonda(vista)) {
            val victima = encontrarVictimaOptima(memoria, vista)
            if (victima != null && victima != vista.miId) {
                val swapVictima = elegirObjetivoSinVerEnRival(memoria, vista, victima)
                if (swapVictima != null) {
                    return DecisionBot.ConfirmarSwapSinVer(
                        jugadorAId = vista.miId,
                        cartaAId = miCartaId,
                        jugadorBId = victima,
                        cartaBId = swapVictima
                    )
                }
            }
        }

        // Política normal: buscar posición desconocida en cualquier rival
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

        // Si no hay desconocida ajena, buscar una posición propia desconocida
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

    /**
     * Elige una carta-objetivo para CAMBIAR_SIN_VER en la mesa del rival
     * indicado: prefiere desconocida → cualquier ocupada. Devuelve el id de la
     * carta o null.
     */
    private fun elegirObjetivoSinVerEnRival(
        memoria: MemoriaBot,
        vista: VistaParcialSala,
        rivalId: String
    ): String? {
        val ocupadas = vista.posicionesOcupadasPorJugador[rivalId] ?: return null

        // 1. Posición desconocida
        for (pos in ocupadas) {
            if (BotMemory.obtenerCartaRivalConocida(memoria, rivalId, pos) != null) continue
            val cartaId = obtenerIdCartaRival(vista, rivalId, pos) ?: continue
            return cartaId
        }

        // 2. Cualquier ocupada como fallback
        val pos = ocupadas.firstOrNull() ?: return null
        return obtenerIdCartaRival(vista, rivalId, pos)
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
    // VÍCTIMA ÓPTIMA (Fase 4)
    //
    // Estima la puntuación total de cada rival activo y devuelve el id del
    // rival con MENOR puntuación estimada (= el que va ganando = mejor víctima
    // para sabotaje).
    //
    // Estimación por rival:
    //   - Posiciones conocidas → valor real en puntos.
    //   - Posiciones desconocidas ocupadas → VALOR_ESPERADO_DESCONOCIDA (6).
    //   - Posiciones vacías → 0.
    //
    // Criterios de desempate:
    //   1. Si hay cantor entre los candidatos empatados, ganar el cantor
    //      (presunción de mano baja).
    //   2. Si no, el de menos cartas en mesa.
    //   3. Si sigue empate, el primero en orden de turno.
    //
    // Casos especiales:
    //   - Si la víctima óptima soy yo (voy ganando), devuelve null. En última
    //      ronda, esto significa "no ataques a nadie".
    //   - Si no hay rivales activos, devuelve null.
    // ─────────────────────────────────────────

    private fun encontrarVictimaOptima(
        memoria: MemoriaBot,
        vista: VistaParcialSala
    ): String? {
        // Candidatos: yo + todos los rivales activos (incluyendo cantor si aplica)
        val candidatos = vista.jugadoresOrden.filter { id ->
            id !in vista.jugadoresDescalificados
        }
        if (candidatos.size < 2) return null

        // Estimar puntuación por candidato
        val puntuaciones = candidatos.associateWith { id ->
            estimarManoDeJugador(memoria, vista, id)
        }

        val mejorPuntuacion = puntuaciones.values.min()
        val empatados = puntuaciones.filterValues { it == mejorPuntuacion }.keys

        // Si soy yo el único o el principal candidato → no ataco a nadie
        if (vista.miId in empatados && empatados.size == 1) return null

        // Si voy empatado con otro → preferir atacar al otro (yo no me ataco)
        val empatadosSinMi = empatados.filter { it != vista.miId }
        if (empatadosSinMi.isEmpty()) return null

        // Desempate 1: si el cantor está entre los empatados, atacarlo
        if (vista.brataActivada && vista.brataJugadorId in empatadosSinMi) {
            return vista.brataJugadorId
        }

        // Desempate 2: el de menos cartas en mesa
        val porCartas = empatadosSinMi.minByOrNull {
            vista.cuentaCartasPorJugador[it] ?: 4
        }
        if (porCartas != null) return porCartas

        // Desempate 3: primer empatado en orden de turno
        return empatadosSinMi.first()
    }

    /**
     * Estima la suma de puntos de la mano de un jugador específico, usando
     * la memoria del bot para posiciones conocidas y VALOR_ESPERADO_DESCONOCIDA
     * para las desconocidas/ocupadas.
     */
    private fun estimarManoDeJugador(
        memoria: MemoriaBot,
        vista: VistaParcialSala,
        jugadorId: String
    ): Int {
        if (jugadorId == vista.miId) {
            // Para mí mismo, uso estimarManoPropia (que ya cubre las propias)
            return estimarManoPropia(memoria, vista)
        }

        val posicionesOcupadas = vista.posicionesOcupadasPorJugador[jugadorId] ?: emptySet()
        var total = 0
        posicionesOcupadas.forEach { pos ->
            val conocida = BotMemory.obtenerCartaRivalConocida(memoria, jugadorId, pos)
            total += if (conocida != null) {
                valorPuntos(conocida.valor, conocida.palo)
            } else {
                VALOR_ESPERADO_DESCONOCIDA
            }
        }
        return total
    }

    /**
     * ¿Estamos en última ronda (brata activada por alguien)?
     * Conveniencia para chequeos breves.
     */
    private fun esUltimaRonda(vista: VistaParcialSala): Boolean {
        return vista.brataActivada
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

    /** Valor mínimo para que el bot descarte espontáneamente una carta propia
     *  conocida que coincide con la cima del descarte. Por debajo de este
     *  umbral, descartarla no aporta suficiente puntaje como para gastar el
     *  "uso" de la cima y arriesgar errores. Un 2 vale 0 pts (no se descarta).
     *  Un 3 vale 3 pts (sí, vale la pena). */
    private const val UMBRAL_DESCARTE_ESPONTANEO_MIN = 3
}

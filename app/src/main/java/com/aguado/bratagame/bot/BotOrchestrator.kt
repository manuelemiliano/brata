package com.aguado.bratagame.bot

import android.util.Log
import com.aguado.bratagame.FirebaseManager
import com.aguado.bratagame.Sala
import com.aguado.bratagame.game.GameActions
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

// ─────────────────────────────────────────────
// BOT ORCHESTRATOR (Fase 2A — versión thread-safe)
//
// Solo el HOST instancia y arranca esta clase.
//
// Cambios clave respecto a la versión anterior:
//   - ultimaSalaVista ahora es MutableStateFlow<Sala?> para garantizar
//     visibilidad cross-thread y permitir esperas reactivas.
//   - esperarCondicion ya no hace polling de variable mutable; usa el flow.
//   - Mejor logging para diagnosticar atascos.
//   - Liberación de lock en detener() como defensa adicional.
// ─────────────────────────────────────────────

class BotOrchestrator(
    private val salaId: String,
    private val hostId: String
) {

    companion object {
        private const val TAG = "BotOrchestrator"
        private const val MARGEN_POST_ANIMACION_MS = 500L
        private const val TIMEOUT_TURNO_MS = 15_000L
        private const val TIMEOUT_ESPERA_CONDICION_MS = 5_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var listenerSala: ValueEventListener? = null

    private val memoriasPorBot = mutableMapOf<String, MemoriaBot>()

    // StateFlow para que las esperas reactivas funcionen correctamente
    // y para garantizar visibilidad entre threads.
    private val salaFlow = MutableStateFlow<Sala?>(null)

    private var jobTurnoActual: Job? = null

    // ─────────────────────────────────────────
    // CICLO DE VIDA
    // ─────────────────────────────────────────

    fun iniciar() {
        Log.d(TAG, "Iniciando orquestador para sala $salaId con host $hostId")

        BotFirebaseRepository.observarMemoriasBots(salaId) { mapa ->
            mapa.forEach { (botId, mem) ->
                if (memoriasPorBot[botId] == null) {
                    memoriasPorBot[botId] = mem
                }
            }
        }

        listenerSala = FirebaseManager.observarSala(salaId) { sala ->
            if (sala == null) return@observarSala
            onSalaActualizada(sala)
        }
    }

    fun detener() {
        Log.d(TAG, "Deteniendo orquestador")
        listenerSala?.let { FirebaseManager.dejarDeObservarSala(salaId, it) }
        listenerSala = null
        jobTurnoActual?.cancel()
        BotFirebaseRepository.liberarLock(salaId)
        scope.cancel()
    }

    // ─────────────────────────────────────────
    // PIPELINE DE ACTUALIZACIÓN
    // ─────────────────────────────────────────

    private fun onSalaActualizada(salaNueva: Sala) {
        Log.d(TAG, "onSalaActualizada: turno=${salaNueva.turnoActualId} | enJuego=${salaNueva.estaEnJuego} | descarteN=${salaNueva.mazoDescarte.size}")

        val salaAnterior = salaFlow.value

        // 1. Detectar eventos comparando con la sala anterior
        val eventos = BotEventDetector.detectarEventos(
            salaAnterior = salaAnterior,
            salaNueva = salaNueva
        )

        // 2. Aplicar eventos a cada bot
        val botsEnSala = salaNueva.jugadores.values.filter { it.esBot }

        botsEnSala.forEach { bot ->
            var memoria = memoriasPorBot[bot.id] ?: MemoriaBot()

            eventos.forEach { evento ->
                memoria = BotMemory.aplicarEvento(
                    memoriaPrevia = memoria,
                    botId = bot.id,
                    evento = evento
                )
            }

            val vistaParaReconciliar = VistaParcialBuilder.construir(salaNueva, bot.id)
            memoria = BotMemory.reconciliar(memoria, vistaParaReconciliar)

            if (memoria != memoriasPorBot[bot.id]) {
                memoriasPorBot[bot.id] = memoria
                BotFirebaseRepository.guardarMemoriaBot(
                    salaId = salaId,
                    botId = bot.id,
                    memoria = memoria
                )
            }
        }

        // 3. PUBLICAR en el flow (despierta a quien espere reactivamente)
        salaFlow.value = salaNueva

        // 4. Evaluar si algún bot debe actuar
        evaluarTurnoDeBot(salaNueva)
    }

    // ─────────────────────────────────────────
    // EVALUACIÓN DE TURNO Y DISPATCH
    // ─────────────────────────────────────────

    private fun evaluarTurnoDeBot(sala: Sala) {
        if (!sala.estaEnJuego) {
            Log.v(TAG, "evaluarTurnoDeBot: descartado (no en juego)")
            return
        }
        if (sala.turnoActualId.isBlank()) {
            Log.v(TAG, "evaluarTurnoDeBot: descartado (turnoActualId vacío)")
            return
        }

        val jugadorEnTurno = sala.jugadores[sala.turnoActualId]
        if (jugadorEnTurno == null) {
            Log.w(TAG, "evaluarTurnoDeBot: turnoActualId=${sala.turnoActualId} pero no existe en jugadores")
            return
        }

        if (!jugadorEnTurno.esBot) {
            Log.v(TAG, "evaluarTurnoDeBot: turno de humano ${jugadorEnTurno.id}, ignoro")
            return
        }
        if (jugadorEnTurno.descalificado) {
            Log.d(TAG, "evaluarTurnoDeBot: bot ${jugadorEnTurno.id} descalificado, ignoro")
            return
        }

        if (sala.cartaPoderActiva != null) {
            Log.d(TAG, "evaluarTurnoDeBot: hay poder activo, espero")
            return
        }
        if (sala.voyPendiente?.activo == true) {
            Log.d(TAG, "evaluarTurnoDeBot: hay VOY pendiente, espero")
            return
        }
        if (sala.adelantadoPendiente?.activo == true) {
            Log.d(TAG, "evaluarTurnoDeBot: hay adelantado pendiente, espero")
            return
        }

        // ──────────────────────────────────────────
        // REGLA CRÍTICA:
        // Si ya hay un job activo, NO lanzamos otro y NO cancelamos el actual.
        // El job en curso lee salaFlow.value en cada iteración de su bucle,
        // así que verá el nuevo estado por sí mismo.
        //
        // Antes este check usaba un "token" que cambiaba con cada cambio de
        // estado (mazoDescarte.size, cartaEnMano, etc.), lo que provocaba
        // que el orquestador cancelara su propio job en medio de la secuencia
        // (autosabotaje).
        //
        // Ahora la regla es simple: un solo job de bot a la vez.
        // ──────────────────────────────────────────
        if (jobTurnoActual?.isActive == true) {
            Log.v(TAG, "evaluarTurnoDeBot: job de bot ya activo, no relanzo")
            return
        }

        val tieneCartaEnMano = jugadorEnTurno.cartaEnMano != null
        Log.d(TAG, "Detectado turno de bot ${jugadorEnTurno.id} | cartaEnMano=$tieneCartaEnMano")

        jobTurnoActual = scope.launch {
            try {
                ejecutarTurnoDeBot(jugadorEnTurno.id)
            } catch (e: Exception) {
                // CancellationException es normal cuando el orquestador se detiene;
                // no la consideramos un error.
                if (e is kotlinx.coroutines.CancellationException) {
                    Log.d(TAG, "Job del bot ${jugadorEnTurno.id} cancelado normalmente")
                } else {
                    Log.e(TAG, "Excepción en turno de bot ${jugadorEnTurno.id}", e)
                    BotFirebaseRepository.liberarLock(salaId)
                }
            }
        }
    }

    private suspend fun ejecutarTurnoDeBot(botId: String) {
        val lockOk = tomarLockSuspend(botId)
        if (!lockOk) {
            Log.d(TAG, "Lock no obtenido para bot $botId")
            return
        }

        try {
            ejecutarSecuenciaDecisiones(botId)
        } finally {
            BotFirebaseRepository.liberarLock(salaId)
        }
    }

    // ─────────────────────────────────────────
    // EJECUCIÓN SECUENCIAL DE DECISIONES
    // ─────────────────────────────────────────

    private suspend fun ejecutarSecuenciaDecisiones(botId: String) {
        val inicioTurno = System.currentTimeMillis()
        var iteraciones = 0
        val maxIteraciones = 5

        while (iteraciones < maxIteraciones) {
            iteraciones++

            if (System.currentTimeMillis() - inicioTurno > TIMEOUT_TURNO_MS) {
                Log.w(TAG, "Timeout de turno para bot $botId tras $iteraciones iteraciones")
                return
            }

            val sala = salaFlow.value ?: return
            val jugador = sala.jugadores[botId] ?: return

            if (sala.turnoActualId != botId) {
                Log.d(TAG, "Bot $botId: ya no es mi turno (turno=${sala.turnoActualId}), salgo")
                return
            }
            if (jugador.descalificado) return

            if (sala.cartaPoderActiva != null ||
                sala.voyPendiente?.activo == true ||
                sala.adelantadoPendiente?.activo == true
            ) {
                delay(300)
                continue
            }

            val memoria = memoriasPorBot[botId] ?: MemoriaBot()
            val vista = VistaParcialBuilder.construir(sala, botId)
            val decision = BotBrain.decidir(memoria, vista)

            Log.d(TAG, "Bot $botId | iter=$iteraciones | decisión: ${decision::class.simpleName}")

            if (decision is DecisionBot.NoOp) {
                Log.d(TAG, "Bot $botId: NoOp, fin de secuencia")
                return
            }

            // Delay humanizador
            val categoria = decision.categoriaDelay()
            val delayMs = Random.nextLong(
                categoria.rangoMs.first.toLong(),
                categoria.rangoMs.last.toLong() + 1
            )
            delay(delayMs + MARGEN_POST_ANIMACION_MS)

            val salaParaActuar = salaFlow.value ?: return
            if (salaParaActuar.turnoActualId != botId) {
                Log.d(TAG, "Bot $botId: turno cambió durante delay, salgo")
                return
            }

            val esperaPostAccion = ejecutarDecision(
                botId = botId,
                decision = decision,
                sala = salaParaActuar
            )

            if (esperaPostAccion != null) {
                Log.d(TAG, "Bot $botId: esperando confirmación de Firebase")
                val confirmado = esperarCondicionReactiva(
                    timeoutMs = TIMEOUT_ESPERA_CONDICION_MS,
                    condicion = esperaPostAccion
                )

                if (!confirmado) {
                    Log.w(TAG, "Bot $botId: timeout esperando confirmación, abandono turno")
                    return
                }

                Log.d(TAG, "Bot $botId: condición confirmada, continúo secuencia")
            } else {
                Log.d(TAG, "Bot $botId: acción terminó turno (no requiere espera)")
                return
            }
        }

        Log.w(TAG, "Bot $botId alcanzó maxIteraciones=$maxIteraciones")
    }

    private fun ejecutarDecision(
        botId: String,
        decision: DecisionBot,
        sala: Sala
    ): ((Sala) -> Boolean)? {
        Log.d(TAG, "Bot $botId ejecuta: ${decision::class.simpleName}")

        val esperarCartaEnMano: (Sala) -> Boolean = { s ->
            s.jugadores[botId]?.cartaEnMano != null
        }

        val resultado: ((Sala) -> Boolean)? = when (decision) {
            is DecisionBot.CantarBrata -> {
                GameActions.presionarBrata(salaId, botId, sala)
                null
            }

            is DecisionBot.RobarDelPozo -> {
                GameActions.robarDelPozo(salaId, botId, sala) { msg ->
                    Log.w(TAG, "Bot $botId no pudo robar pozo: $msg")
                }
                esperarCartaEnMano
            }

            is DecisionBot.RobarDelDescarte -> {
                GameActions.robarDelDescarte(salaId, botId, sala) { msg ->
                    Log.w(TAG, "Bot $botId no pudo robar descarte: $msg")
                }
                esperarCartaEnMano
            }

            is DecisionBot.Descartar -> {
                val cartaEnMano = sala.jugadores[botId]?.cartaEnMano
                if (cartaEnMano == null) {
                    Log.w(TAG, "Bot $botId quiso descartar pero no tiene carta en mano")
                    null
                } else {
                    GameActions.descartarCartaEnMano(salaId, botId, sala, cartaEnMano) { msg ->
                        Log.w(TAG, "Bot $botId no pudo descartar: $msg")
                    }
                    null
                }
            }

            is DecisionBot.CambiarPorPosicion -> {
                val cartaEnMano = sala.jugadores[botId]?.cartaEnMano
                if (cartaEnMano == null) {
                    Log.w(TAG, "Bot $botId quiso cambiar pero no tiene carta en mano")
                    null
                } else {
                    GameActions.cambiarCartaEnManoPorPropia(
                        salaId = salaId,
                        jugadorId = botId,
                        sala = sala,
                        cartaEnMano = cartaEnMano,
                        posicionDestino = decision.posicion
                    ) { msg ->
                        Log.w(TAG, "Bot $botId no pudo cambiar: $msg")
                    }
                    null
                }
            }

            is DecisionBot.SeleccionarComodin -> {
                val cartaEnMano = sala.jugadores[botId]?.cartaEnMano
                if (cartaEnMano == null) {
                    Log.w(TAG, "Bot $botId quiso definir comodín pero no tiene carta en mano")
                    null
                } else {
                    GameActions.seleccionarValorComodin(
                        salaId = salaId,
                        jugadorId = botId,
                        sala = sala,
                        comodin = cartaEnMano,
                        valorElegido = decision.valor,
                        paloElegido = decision.palo
                    )
                    null
                }
            }

            is DecisionBot.PasarTurnoBrata -> {
                GameActions.pasarTurnoBrata(salaId, botId, sala) { msg ->
                    Log.w(TAG, "Bot $botId no pudo pasar: $msg")
                }
                null
            }

            is DecisionBot.RegresarCartaEspiada -> {
                GameActions.regresarCartaEspiada(salaId, botId, sala)
                null
            }

            // ── Decisiones de fases futuras ──
            is DecisionBot.ActivarPoder,
            is DecisionBot.ActivarDescarteFree,
            is DecisionBot.RobarDescarteAdelantado,
            is DecisionBot.EspiarCarta,
            is DecisionBot.EspiarCartaCambioViendo,
            is DecisionBot.DescartarCartaEspiada,
            is DecisionBot.ConfirmarSwapViendo,
            is DecisionBot.ConfirmarSwapSinVer,
            is DecisionBot.ConfirmarDescarteFree,
            is DecisionBot.ReclamarVoy,
            is DecisionBot.SeleccionarObjetivoVoy,
            is DecisionBot.EntregarCartaVoy,
            is DecisionBot.DescarteEspontaneo -> {
                Log.w(TAG, "Bot $botId devolvió decisión de fase futura: $decision")
                null
            }

            is DecisionBot.NoOp -> null
        }

        return resultado
    }

    // ─────────────────────────────────────────
    // HELPERS DE SUSPEND
    // ─────────────────────────────────────────

    private suspend fun tomarLockSuspend(botId: String): Boolean {
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            BotFirebaseRepository.intentarTomarLock(
                salaId = salaId,
                botId = botId,
                hostId = hostId
            ) { exito ->
                if (cont.isActive) {
                    cont.resumeWith(Result.success(exito))
                }
            }
        }
    }

    /**
     * Espera reactiva: suspende hasta que la sala publicada en el flow cumpla
     * la condición, o hasta que pase el timeout.
     *
     * Usa el StateFlow para reaccionar a cambios reales en vez de hacer
     * polling de una variable mutable. Esto soluciona el problema de
     * visibilidad cross-thread que tenía la versión anterior.
     */
    private suspend fun esperarCondicionReactiva(
        timeoutMs: Long,
        condicion: (Sala) -> Boolean
    ): Boolean {
        return withTimeoutOrNull(timeoutMs) {
            val salaActual = salaFlow.value
            if (salaActual != null && condicion(salaActual)) {
                return@withTimeoutOrNull true
            }

            salaFlow
                .filterNotNull()
                .first { condicion(it) }

            true
        } ?: false
    }
}

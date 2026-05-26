package com.aguado.bratagame.bot

import android.util.Log
import com.aguado.bratagame.CartaEnMesa
import com.aguado.bratagame.FirebaseManager
import com.aguado.bratagame.Sala
import com.aguado.bratagame.esSlotVacio
import com.aguado.bratagame.game.GameActions
import com.aguado.bratagame.mesaNormalizadaACuatroCasillas
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

        // Duración aproximada de la animación visual de swap completa.
        // CardSwapAnimConfig: 3000ms de rebote en casilla + ~1600ms de cruce
        // = ~4600ms. Agregamos margen para garantizar que la animación se vea
        // completa antes que el bot confirme (que limpia swapAnimando).
        // Si el bot confirma antes, la animación se corta visualmente.
        private const val DURACION_ANIMACION_SWAP_MS = 4_800L

        // Tiempo que el bot mantiene visible una carta espiada antes de
        // decidir si la regresa o descarta. Alineado con duracionMs de
        // EspiaAnimando en GameActions.espiarCarta (3000 ms).
        private const val DURACION_VISUALIZACION_ESPIA_MS = 3_000L

        // Duración total de animación de descarte free.
        // DescarteFreeAnimando: 650ms viaje + 450ms rebote + 350ms margen
        // que usa el LaunchedEffect del cliente humano.
        private const val DURACION_ANIMACION_DESCARTE_FREE_MS = 1_500L

        // Duración total de animación de cambio propio (mano ↔ propia).
        // cambioPropioAnimando: 2000ms salto + 750ms viaje + 1000ms margen
        // (igual al usado por el LaunchedEffect humano).
        private const val DURACION_ANIMACION_CAMBIO_PROPIO_MS = 3_800L
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

        if (sala.cartaPoderActiva != null &&
            sala.cartaPoderActiva?.jugadorId != jugadorEnTurno.id
        ) {
            Log.d(TAG, "evaluarTurnoDeBot: hay poder activo de otro, espero")
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

            if (sala.voyPendiente?.activo == true ||
                sala.adelantadoPendiente?.activo == true
            ) {
                delay(300)
                continue
            }

            // Si hay poder activo y NO es mío, esperar
            val poder = sala.cartaPoderActiva
            if (poder != null && poder.jugadorId != botId) {
                delay(300)
                continue
            }

            // Si hay poder activo Y es mío, el cerebro decidirá durante el poder
            // (decidirDuranteMiPoder en BotBrain). No retornamos acá.

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

    /**
     * Ejecuta la DecisionBot. Devuelve una función de "espera" si se necesita
     * confirmar el cambio antes de seguir, o null si el turno terminó.
     *
     * Es suspend para poder hacer secuencias como "iniciar animación →
     * esperar 2.5s → confirmar" dentro del flujo principal del turno,
     * manteniendo el lock tomado y la coherencia del bucle.
     */
    private suspend fun ejecutarDecision(
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
                    // Construir la CartaEnMesa de la posición destino para
                    // alimentar a iniciarAnimacionCambioPropio.
                    val miJugador = sala.jugadores[botId]
                    val miMesa = miJugador?.cartas?.mesaNormalizadaACuatroCasillas()
                    val cartaDestino = miMesa?.getOrNull(decision.posicion)

                    if (cartaDestino == null) {
                        Log.w(TAG, "Bot $botId no encontró su carta destino en posición ${decision.posicion}")
                        null
                    } else if (cartaDestino.esSlotVacio()) {
                        // Slot vacío: no hay carta visible para animar. Usamos el
                        // camino instantáneo (igual que tomarCartaComoNuevaDeJuego).
                        // No queda animación colgada porque cambiarCartaEnManoPorPropia
                        // no escribe cambioPropioAnimando.
                        GameActions.cambiarCartaEnManoPorPropia(
                            salaId = salaId,
                            jugadorId = botId,
                            sala = sala,
                            cartaEnMano = cartaEnMano,
                            posicionDestino = decision.posicion
                        ) { msg ->
                            Log.w(TAG, "Bot $botId no pudo cambiar (slot vacío): $msg")
                        }
                        null
                    } else {
                        val cartaEnMesaDestino = CartaEnMesa(
                            carta = cartaDestino,
                            posicion = decision.posicion,
                            propietarioId = botId
                        )

                        // 1. Iniciar animación visible
                        GameActions.iniciarAnimacionCambioPropio(
                            salaId = salaId,
                            ejecutorId = botId,
                            cartaSeleccionada = cartaEnMesaDestino,
                            cartaEnMano = cartaEnMano,
                            onError = { msg ->
                                Log.w(TAG, "Bot $botId no pudo iniciar animación cambio propio: $msg")
                            }
                        )

                        // 2. Esperar duración total: salto (2000ms) + viaje (750ms) + margen
                        delay(DURACION_ANIMACION_CAMBIO_PROPIO_MS)

                        // 3. Releer estado y confirmar
                        val salaFresca = salaFlow.value
                        val animFresca = salaFresca?.cambioPropioAnimando

                        if (salaFresca != null &&
                            animFresca != null &&
                            animFresca.ejecutorId == botId
                        ) {
                            val cartaEnManoFresca = salaFresca.jugadores[botId]?.cartaEnMano
                            if (cartaEnManoFresca != null && cartaEnManoFresca.id == animFresca.cartaEnManoId) {
                                GameActions.confirmarCambioPropioAnimado(
                                    salaId = salaId,
                                    jugadorId = botId,
                                    sala = salaFresca,
                                    posicionDestino = animFresca.posicion,
                                    cartaEnMano = cartaEnManoFresca,
                                    animacionId = animFresca.id
                                ) { msg ->
                                    Log.w(TAG, "Bot $botId no pudo confirmar cambio propio: $msg")
                                }
                            } else {
                                Log.w(TAG, "Bot $botId: estado inconsistente al confirmar cambio propio")
                            }
                        } else {
                            Log.w(TAG, "Bot $botId: cambioPropioAnimando ya no activo al confirmar")
                        }
                        null
                    }
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
                // Si estoy regresando una carta tras un ESPIAR, espero a que
                // la animación visual del espía termine (es de 3 segundos
                // por defecto). Si no había espía activo (caso defensivo),
                // el delay es inofensivo.
                if (sala.cartaPoderActiva?.cartaEspiandoId?.isNotBlank() == true) {
                    delay(DURACION_VISUALIZACION_ESPIA_MS)
                }
                // Releer sala fresca por si el estado cambió durante el delay
                val salaFresca = salaFlow.value ?: sala
                GameActions.regresarCartaEspiada(salaId, botId, salaFresca)

                // CRÍTICO: limpiar la animación visual del espía.
                // El LaunchedEffect del cliente solo la limpia si jugadorLocal
                // es el ejecutor; cuando ejecuta el bot, nadie la limpia y
                // queda colgada en Firebase para siempre.
                GameActions.limpiarAnimacionEspia(salaId)
                null
            }

            // ── Activar poder según tipo ──
            is DecisionBot.ActivarPoder -> {
                val cartaEnMano = sala.jugadores[botId]?.cartaEnMano
                if (cartaEnMano == null) {
                    Log.w(TAG, "Bot $botId quiso activar poder pero no tiene carta en mano")
                    null
                } else {
                    when (decision.tipoPoder) {
                        com.aguado.bratagame.TipoPoder.ESPIAR.name -> {
                            GameActions.activarPoderEspiar(salaId, botId, sala, cartaEnMano)
                        }
                        com.aguado.bratagame.TipoPoder.CAMBIAR_VIENDO.name -> {
                            GameActions.activarPoderCambiarViendo(salaId, botId, sala, cartaEnMano)
                        }
                        com.aguado.bratagame.TipoPoder.CAMBIAR_SIN_VER.name -> {
                            GameActions.activarPoderCambiarSinVer(salaId, botId, sala, cartaEnMano)
                        }
                        else -> {
                            Log.w(TAG, "Bot $botId pidió poder desconocido: ${decision.tipoPoder}")
                        }
                    }
                    // Esperar a que cartaPoderActiva quede registrada en Firebase
                    val cond: (Sala) -> Boolean = { s ->
                        s.cartaPoderActiva != null && s.cartaPoderActiva?.jugadorId == botId
                    }
                    cond
                }
            }

            is DecisionBot.ActivarDescarteFree -> {
                val cartaEnMano = sala.jugadores[botId]?.cartaEnMano
                if (cartaEnMano == null) {
                    Log.w(TAG, "Bot $botId quiso activar descarte free pero no tiene carta en mano")
                    null
                } else {
                    GameActions.activarDescarteFree(salaId, botId, sala, cartaEnMano)
                    // Esperar a que cartaPoderActiva sea DESCARTE_FREE_SELECCION
                    val cond: (Sala) -> Boolean = { s ->
                        val pa = s.cartaPoderActiva
                        pa != null && pa.jugadorId == botId &&
                                pa.tipoPoder == com.aguado.bratagame.TipoPoder.DESCARTE_FREE_SELECCION
                    }
                    cond
                }
            }

            // ── Durante poder propio: seleccionar carta a espiar ──
            is DecisionBot.EspiarCarta -> {
                GameActions.espiarCarta(salaId, botId, sala, decision.cartaId) { msg ->
                    Log.w(TAG, "Bot $botId no pudo espiar: $msg")
                }
                // Esperar a que cartaEspiandoId tenga el id correcto
                val cond: (Sala) -> Boolean = { s ->
                    s.cartaPoderActiva?.cartaEspiandoId == decision.cartaId
                }
                cond
            }

            is DecisionBot.EspiarCartaCambioViendo -> {
                GameActions.espiarCartaCambioViendo(salaId, botId, sala, decision.cartaId) { msg ->
                    Log.w(TAG, "Bot $botId no pudo espiar (cambio viendo): $msg")
                }
                val cond: (Sala) -> Boolean = { s ->
                    s.cartaPoderActiva?.cartaEspiandoId == decision.cartaId
                }
                cond
            }

            // ── Durante poder propio: descartar carta espiada ──
            is DecisionBot.DescartarCartaEspiada -> {
                // El bot solo descarta la carta espiada cuando es PROPIA del bot
                // y coincide con el valor activador (regla del As mejorada).
                // Por simplicidad, delegamos a descartarCartaEspiadaPropia, que
                // GameActions ya valida internamente.
                val cartaEspiadaId = sala.cartaPoderActiva?.cartaEspiandoId
                if (cartaEspiadaId.isNullOrBlank()) {
                    Log.w(TAG, "Bot $botId quiso descartar espiada pero no hay cartaEspiandoId")
                    null
                } else {
                    // Esperar a que termine la animación visible del espía
                    delay(DURACION_VISUALIZACION_ESPIA_MS)
                    val salaFresca = salaFlow.value ?: sala

                    // Buscar la carta concreta en la mesa del bot
                    val miJugador = salaFresca.jugadores[botId]
                    val cartaEspiada = miJugador?.cartas
                        ?.firstOrNull { it.id == cartaEspiadaId }

                    if (cartaEspiada != null) {
                        GameActions.descartarCartaEspiadaPropia(
                            salaId = salaId,
                            jugadorId = botId,
                            sala = salaFresca,
                            cartaEspiada = cartaEspiada
                        ) { msg ->
                            Log.w(TAG, "Bot $botId no pudo descartar espiada: $msg")
                        }
                    } else {
                        // Fallback: regresar
                        GameActions.regresarCartaEspiada(salaId, botId, salaFresca)
                    }

                    // Limpiar animación visual del espía (responsabilidad del bot
                    // ya que el LaunchedEffect del cliente no la limpia para él).
                    GameActions.limpiarAnimacionEspia(salaId)
                    null
                }
            }

            // ── Durante poder propio: confirmar swap CAMBIAR_VIENDO ──
            is DecisionBot.ConfirmarSwapViendo -> {
                // Patrón:
                //   1. Iniciar animación visible (escribir swapAnimando).
                //   2. Esperar duración de animación.
                //   3. Confirmar el cambio leyendo el estado FRESCO de Firebase
                //      (porque la animación puede haber cambiado swapAnimando, pero
                //      cartaPoderActiva sigue activo hasta que confirmemos).
                //
                // Mantenemos esto SUSPEND dentro del flujo del turno para que
                // el lock siga tomado y nadie más pueda intervenir.
                val cartaA = GameActions.resolverCartaEnMesaPorId(
                    sala = sala,
                    propietarioId = decision.jugadorAId,
                    cartaId = decision.cartaAId
                )
                val cartaB = GameActions.resolverCartaEnMesaPorId(
                    sala = sala,
                    propietarioId = decision.jugadorBId,
                    cartaId = decision.cartaBId
                )

                if (cartaA != null && cartaB != null) {
                    GameActions.iniciarAnimacionSwap(
                        salaId = salaId,
                        ejecutorId = botId,
                        cartaA = cartaA,
                        cartaB = cartaB,
                        mostrarCartaA = true,
                        mostrarCartaB = false
                    )

                    // Esperar la duración de la animación
                    delay(DURACION_ANIMACION_SWAP_MS)

                    // Releer el estado de la sala — el poder DEBE seguir activo
                    val salaFresca = salaFlow.value
                    val poderFresco = salaFresca?.cartaPoderActiva

                    if (salaFresca != null &&
                        poderFresco != null &&
                        poderFresco.jugadorId == botId &&
                        poderFresco.tipoPoder == com.aguado.bratagame.TipoPoder.CAMBIAR_VIENDO
                    ) {
                        GameActions.confirmarCambioViendo(
                            salaId = salaId,
                            jugadorId = botId,
                            sala = salaFresca,
                            jugadorAId = decision.jugadorAId,
                            cartaAId = decision.cartaAId,
                            jugadorBId = decision.jugadorBId,
                            cartaBId = decision.cartaBId
                        ) { msg ->
                            Log.w(TAG, "Bot $botId no pudo confirmar cambio viendo: $msg")
                        }
                    } else {
                        Log.w(
                            TAG,
                            "Bot $botId: poder ya no activo al confirmar viendo (poder=${poderFresco?.tipoPoder}). Limpiando animación."
                        )
                        GameActions.limpiarAnimacionSwap(salaId)
                    }
                    // CAMBIAR_VIENDO implica que el bot espió antes; limpiar
                    // animación residual del espía si quedó colgada.
                    GameActions.limpiarAnimacionEspia(salaId)
                } else {
                    Log.w(TAG, "Bot $botId: no encontré las cartas para swap viendo")
                    GameActions.regresarCartaEspiada(salaId, botId, sala)
                    GameActions.limpiarAnimacionEspia(salaId)
                }
                null
            }

            is DecisionBot.ConfirmarSwapSinVer -> {
                val cartaA = GameActions.resolverCartaEnMesaPorId(
                    sala = sala,
                    propietarioId = decision.jugadorAId,
                    cartaId = decision.cartaAId
                )
                val cartaB = GameActions.resolverCartaEnMesaPorId(
                    sala = sala,
                    propietarioId = decision.jugadorBId,
                    cartaId = decision.cartaBId
                )

                if (cartaA != null && cartaB != null) {
                    GameActions.iniciarAnimacionSwap(
                        salaId = salaId,
                        ejecutorId = botId,
                        cartaA = cartaA,
                        cartaB = cartaB,
                        mostrarCartaA = false,
                        mostrarCartaB = false
                    )

                    delay(DURACION_ANIMACION_SWAP_MS)

                    val salaFresca = salaFlow.value
                    val poderFresco = salaFresca?.cartaPoderActiva

                    if (salaFresca != null &&
                        poderFresco != null &&
                        poderFresco.jugadorId == botId &&
                        poderFresco.tipoPoder == com.aguado.bratagame.TipoPoder.CAMBIAR_SIN_VER
                    ) {
                        GameActions.confirmarCambioSinVer(
                            salaId = salaId,
                            jugadorId = botId,
                            sala = salaFresca,
                            jugadorAId = decision.jugadorAId,
                            cartaAId = decision.cartaAId,
                            jugadorBId = decision.jugadorBId,
                            cartaBId = decision.cartaBId
                        ) { msg ->
                            Log.w(TAG, "Bot $botId no pudo confirmar cambio sin ver: $msg")
                        }
                    } else {
                        Log.w(
                            TAG,
                            "Bot $botId: poder ya no activo al confirmar sin ver (poder=${poderFresco?.tipoPoder}). Limpiando animación."
                        )
                        GameActions.limpiarAnimacionSwap(salaId)
                    }
                } else {
                    Log.w(TAG, "Bot $botId: no encontré las cartas para swap sin ver")
                    GameActions.regresarCartaEspiada(salaId, botId, sala)
                }
                null
            }

            // ── Durante poder propio: confirmar descarte free ──
            is DecisionBot.ConfirmarDescarteFree -> {
                GameActions.confirmarDescarteFree(
                    salaId = salaId,
                    jugadorId = botId,
                    sala = sala,
                    posicionDescartada = decision.posicion
                ) { msg ->
                    Log.w(TAG, "Bot $botId no pudo confirmar descarte free: $msg")
                }

                // Esperar a que la animación de descarte free termine y limpiarla.
                // El LaunchedEffect del cliente solo la limpia si jugadorLocal
                // es el ejecutor; cuando ejecuta el bot, nadie la limpia.
                // Duración: ~650ms viaje + ~450ms rebote + margen = 1450ms.
                delay(DURACION_ANIMACION_DESCARTE_FREE_MS)
                GameActions.limpiarAnimacionDescarteFree(salaId)
                null
            }

            // ── Decisiones de fases futuras (3 / 4) ──
            is DecisionBot.RobarDescarteAdelantado,
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

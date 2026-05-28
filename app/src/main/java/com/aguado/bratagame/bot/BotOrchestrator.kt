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

        // Duración total de animación de descarte espontáneo.
        // DescarteEspontaneoAnimando: 1200ms viaje + 450ms rebote + 350ms margen.
        private const val DURACION_ANIMACION_DESCARTE_ESPONTANEO_MS = 2_000L

        // ─────────────────────────────────────────
        // DEBUG DE ANIMACIONES (Fase de diagnóstico)
        //
        // Cuando está activo, el orquestador emite logs detallados con prefijo
        // [ANIM-DBG] que permiten reconstruir el ciclo de vida de cada
        // animación. Filtrá Logcat por "ANIM-DBG" para ver solo esto.
        //
        // Apagar (setear a false) cuando los bugs estén resueltos.
        // ─────────────────────────────────────────
        private const val DEBUG_ANIMATIONS = true

        // Umbrales del watchdog:
        // - Más allá de UMBRAL_COLGADA_MS, advertir (anim sigue activa).
        // - Más allá de UMBRAL_ZOMBIE_MS, error (no debería estar viva).
        // - Si tras iniciar, no aparece en UMBRAL_NO_PROPAGADA_MS, error.
        private const val UMBRAL_COLGADA_MS = 8_000L
        private const val UMBRAL_ZOMBIE_MS = 20_000L
        private const val UMBRAL_NO_PROPAGADA_MS = 2_000L

        // Tag específico para filtrar fácilmente en Logcat.
        private const val ANIM_TAG = "BotOrchestrator-ANIM"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var listenerSala: ValueEventListener? = null

    private val memoriasPorBot = mutableMapOf<String, MemoriaBot>()

    // StateFlow para que las esperas reactivas funcionen correctamente
    // y para garantizar visibilidad entre threads.
    private val salaFlow = MutableStateFlow<Sala?>(null)

    private var jobTurnoActual: Job? = null
    // ID del bot que está actualmente ejecutando su turno. Necesario porque
    // jobTurnoActual puede seguir vivo (en su delay post-acción) DESPUÉS de
    // que el turno cambió en Firebase. Mientras este job esté activo,
    // evaluarReaccionesFueraDeTurno no debe lanzar otra acción para ese bot.
    private var botIdEnTurnoActivo: String = ""

    // Jobs de reacción fuera de turno, uno por bot (descarte espontáneo y
    // similares de Fase 3+). Se mantienen separados del jobTurnoActual para
    // que un bot pueda reaccionar fuera de turno aunque otro bot esté
    // ejecutando su turno propio.
    private val jobsReaccionPorBot = mutableMapOf<String, Job>()

    // Job programado para cerrar la ventana VOY cuando un bot es robador.
    // Solo puede haber UNA ventana VOY activa a la vez por sala, así que un
    // solo job alcanza. Se cancela y reprograma si cambia el id de la ventana.
    private var jobCierreVentanaVoy: Job? = null
    private var ventanaVoyActualId: String = ""

    // ─────────────────────────────────────────
    // INSTRUMENTACIÓN DE ANIMACIONES (DEBUG)
    //
    // Tracking de cuándo aparece/desaparece cada animación en salaFlow para
    // diagnosticar bugs visuales como: animación colgada, animación que no
    // se ve, animación pisada por otra.
    //
    // Cada entrada guarda el animId activo y un timestamp de cuándo lo vimos
    // aparecer por primera vez. Si el animId cambia, registramos pisado.
    // Si no aparece, es señal de que Firebase no propagó.
    //
    // Esto vive solo en RAM del host. Si DEBUG_ANIMATIONS está apagado,
    // no se popula ni se loguea nada.
    // ─────────────────────────────────────────

    private val animacionesVistas = mutableMapOf<String, AnimacionVista>()

    // Tracking de "qué animaciones está esperando el bot que aparezcan".
    // Cuando un bot llama a GameActions.iniciar... lo registramos acá, y si
    // pasa más de UMBRAL_NO_PROPAGADA_MS sin verla en salaFlow, advertimos.
    private val animacionesEsperadas = mutableMapOf<String, AnimacionEsperada>()

    private data class AnimacionVista(
        val tipo: String,
        val animId: String,
        val ejecutorId: String,
        val timestampPrimeraVista: Long
    )

    private data class AnimacionEsperada(
        val tipo: String,
        val botId: String,
        val timestampSolicitud: Long
    )

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
        jobsReaccionPorBot.values.forEach { it.cancel() }
        jobsReaccionPorBot.clear()
        jobCierreVentanaVoy?.cancel()
        jobCierreVentanaVoy = null
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

        // 4. Evaluar si algún bot debe actuar EN su turno
        evaluarTurnoDeBot(salaNueva)

        // 5. Evaluar si algún bot debe reaccionar FUERA de turno
        //    (Fase 3A: descarte espontáneo)
        evaluarReaccionesFueraDeTurno(salaNueva)

        // 6. Si el bot tiene una ventana VOY abierta y nadie está
        //    encargado de cerrarla, programar el cierre (Fase 3C).
        programarCierreVentanaVoyDeBot(salaNueva)

        // 7. DEBUG: evaluar estado de animaciones para detectar bugs visuales
        watchdogAnimaciones(salaNueva)
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
        // Si hay adelantado pendiente y soy el perjudicado, debo procesarlo
        // (es mi responsabilidad responder). Si soy "otro" o sólo observo,
        // no actúo.
        if (sala.adelantadoPendiente?.activo == true) {
            val esPerjudicado = sala.adelantadoPendiente?.jugadorPerjudicadoId == jugadorEnTurno.id
            if (!esPerjudicado) {
                Log.d(TAG, "evaluarTurnoDeBot: hay adelantado pendiente y no soy perjudicado, espero")
                return
            }
            // Soy perjudicado → continuo y dejo que el bucle decida la respuesta.
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

        botIdEnTurnoActivo = jugadorEnTurno.id
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
            } finally {
                // Liberar el "lock lógico" para que el evaluador de reacciones
                // pueda lanzar nuevas acciones para este bot.
                if (botIdEnTurnoActivo == jugadorEnTurno.id) {
                    botIdEnTurnoActivo = ""
                }
                // Anular el job actual explícitamente. Aunque Kotlin lo
                // marcará completed cuando este finally retorne, anularlo
                // ahora deja el guard de evaluarTurnoDeBot pasable de
                // inmediato cuando la nueva coroutine corra.
                jobTurnoActual = null
                // FIX CONGELAMIENTO v3: Tras liberar el job, reevaluar.
                //
                // Aprendizajes de v1 y v2:
                //   v1: reevaluar in-line dentro del finally → falla porque
                //       jobTurnoActual.isActive sigue true.
                //   v2: scope.launch { yield(); reevaluar } → yield() no
                //       garantiza que el job exterior termine. Además
                //       leíamos salaFlow.value antes de que llegue la
                //       actualización de Firebase con el nuevo turno, así
                //       que el evaluador se relanzaba al mismo bot.
                //   v3 (esta): scope.launch { delay(80); leer FRESCO }.
                //       El delay da tiempo a:
                //         - El job exterior termine y se marque completado.
                //         - Que llegue la actualización de Firebase con
                //           el nuevo turno (típicamente <100ms en LAN).
                //       Y leemos salaFlow.value DENTRO del launch, no
                //       capturado del momento del finally.
                scope.launch {
                    delay(80L)
                    val ultima = salaFlow.value ?: return@launch
                    Log.v(TAG, "Job de turno terminado, reevaluando (turno=${ultima.turnoActualId})")
                    evaluarTurnoDeBot(ultima)
                    evaluarReaccionesFueraDeTurno(ultima)
                }
            }
        }
    }

    // ─────────────────────────────────────────
    // WATCHDOG DE ANIMACIONES (DEBUG)
    //
    // Después de cada actualización de Sala, evalúa el estado de las 5
    // animaciones del juego y registra eventos de aparición/desaparición,
    // edad de animaciones colgadas, y detección de pisado (anim id cambió).
    //
    // Si DEBUG_ANIMATIONS está apagado, retorna inmediatamente.
    // ─────────────────────────────────────────

    private fun watchdogAnimaciones(sala: Sala) {
        if (!DEBUG_ANIMATIONS) return

        val ahora = System.currentTimeMillis()

        // Evaluar cada animación por separado
        evaluarAnimacion(
            tipo = "cambioPropioAnimando",
            animId = sala.cambioPropioAnimando?.id,
            ejecutorId = sala.cambioPropioAnimando?.ejecutorId,
            timestampInicio = sala.cambioPropioAnimando?.timestampInicio,
            ahora = ahora,
            detallesExtra = sala.cambioPropioAnimando?.let {
                "jugadorId=${it.jugadorId} cartaId=${it.cartaId} pos=${it.posicion} cartaEnManoId=${it.cartaEnManoId}"
            }
        )

        evaluarAnimacion(
            tipo = "swapAnimando",
            // SwapAnimando no tiene campo id; construimos uno único combinando
            // ejecutorId + cartaAId + cartaBId + timestamp para detectar pisado.
            animId = sala.swapAnimando?.let {
                "${it.ejecutorId}|${it.cartaAId}|${it.cartaBId}|${it.timestampInicio}"
            },
            ejecutorId = sala.swapAnimando?.ejecutorId,
            timestampInicio = sala.swapAnimando?.timestampInicio,
            ahora = ahora,
            detallesExtra = sala.swapAnimando?.let {
                "jugadorA=${it.jugadorAId} cartaA=${it.cartaAId} jugadorB=${it.jugadorBId} cartaB=${it.cartaBId} mostrarA=${it.mostrarCartaA}"
            }
        )

        evaluarAnimacion(
            tipo = "espiaAnimando",
            animId = sala.espiaAnimando?.id,
            ejecutorId = sala.espiaAnimando?.ejecutorId,
            timestampInicio = sala.espiaAnimando?.timestampInicio,
            ahora = ahora,
            detallesExtra = sala.espiaAnimando?.let {
                "propietario=${it.propietarioId} pos=${it.posicion} cartaId=${it.cartaId}"
            }
        )

        evaluarAnimacion(
            tipo = "descarteFreeAnimando",
            animId = sala.descarteFreeAnimando?.id,
            ejecutorId = sala.descarteFreeAnimando?.ejecutorId,
            timestampInicio = sala.descarteFreeAnimando?.timestampInicio,
            ahora = ahora,
            detallesExtra = sala.descarteFreeAnimando?.let {
                "cartaId=${it.cartaId} valor=${it.valor}${it.palo.take(1)}"
            }
        )

        evaluarAnimacion(
            tipo = "descarteEspontaneoAnimando",
            animId = sala.descarteEspontaneoAnimando?.id,
            ejecutorId = sala.descarteEspontaneoAnimando?.ejecutorId,
            timestampInicio = sala.descarteEspontaneoAnimando?.timestampInicio,
            ahora = ahora,
            detallesExtra = sala.descarteEspontaneoAnimando?.let {
                "cartaId=${it.cartaId} valor=${it.valor}${it.palo.take(1)}"
            }
        )

        // Detectar "esperadas pero no propagadas":
        // bot llamó a iniciar... y todavía no aparece en salaFlow.
        val esperadasIter = animacionesEsperadas.entries.iterator()
        while (esperadasIter.hasNext()) {
            val (clave, esperada) = esperadasIter.next()
            val edad = ahora - esperada.timestampSolicitud
            if (edad > UMBRAL_NO_PROPAGADA_MS) {
                Log.e(
                    ANIM_TAG,
                    "❌ NO-PROPAGADA tipo=${esperada.tipo} botId=${esperada.botId} " +
                            "edadDesdeIniciar=${edad}ms (Firebase nunca emitió o se perdió)"
                )
                esperadasIter.remove()
            }
        }
    }

    /**
     * Evalúa un slot de animación: detecta aparición, desaparición, pisado
     * (cambio de id), y emite warnings si la animación está vieja.
     */
    private fun evaluarAnimacion(
        tipo: String,
        animId: String?,
        ejecutorId: String?,
        timestampInicio: Long?,
        ahora: Long,
        detallesExtra: String?
    ) {
        val previa = animacionesVistas[tipo]
        val animIdNoVacio = animId?.takeIf { it.isNotBlank() }

        when {
            // Caso 1: no había animación, ahora tampoco
            previa == null && animIdNoVacio == null -> return

            // Caso 2: apareció una animación nueva (no había antes)
            previa == null && animIdNoVacio != null -> {
                animacionesVistas[tipo] = AnimacionVista(
                    tipo = tipo,
                    animId = animIdNoVacio,
                    ejecutorId = ejecutorId ?: "",
                    timestampPrimeraVista = ahora
                )
                Log.d(
                    ANIM_TAG,
                    "📥 APARECIÓ $tipo animId=$animIdNoVacio ejecutor=$ejecutorId " +
                            (detallesExtra ?: "")
                )

                // Si estábamos esperándola, sacarla del registro de esperadas
                val esperada = animacionesEsperadas.remove("$tipo|$ejecutorId")
                if (esperada != null) {
                    val latencia = ahora - esperada.timestampSolicitud
                    Log.d(
                        ANIM_TAG,
                        "  ↳ propagación: ${latencia}ms desde iniciar() hasta verla en flow"
                    )
                }
            }

            // Caso 3: había animación y desapareció
            previa != null && animIdNoVacio == null -> {
                val duro = ahora - previa.timestampPrimeraVista
                animacionesVistas.remove(tipo)
                Log.d(
                    ANIM_TAG,
                    "📤 DESAPARECIÓ $tipo animId=${previa.animId} ejecutor=${previa.ejecutorId} duró=${duro}ms"
                )
            }

            // Caso 4: había animación y sigue (mismo id) → posible cuelgue
            previa != null && animIdNoVacio == previa.animId -> {
                val edad = ahora - previa.timestampPrimeraVista
                when {
                    edad > UMBRAL_ZOMBIE_MS -> {
                        Log.e(
                            ANIM_TAG,
                            "❌ ZOMBIE $tipo animId=${previa.animId} ejecutor=${previa.ejecutorId} " +
                                    "edad=${edad}ms (¡debió limpiarse hace mucho!)"
                        )
                    }
                    edad > UMBRAL_COLGADA_MS -> {
                        Log.w(
                            ANIM_TAG,
                            "⚠️ COLGADA $tipo animId=${previa.animId} ejecutor=${previa.ejecutorId} edad=${edad}ms"
                        )
                    }
                    // Edad razonable: no loguear (sería ruido)
                }
            }

            // Caso 5: había animación y ahora hay otra distinta (pisado)
            previa != null && animIdNoVacio != null && animIdNoVacio != previa.animId -> {
                val duroPrevia = ahora - previa.timestampPrimeraVista
                Log.w(
                    ANIM_TAG,
                    "⚠️ PISADA $tipo: animId previa=${previa.animId} (ejecutor=${previa.ejecutorId}, duró=${duroPrevia}ms) " +
                            "reemplazada por animId=$animIdNoVacio (ejecutor=$ejecutorId)"
                )
                animacionesVistas[tipo] = AnimacionVista(
                    tipo = tipo,
                    animId = animIdNoVacio,
                    ejecutorId = ejecutorId ?: "",
                    timestampPrimeraVista = ahora
                )
            }
        }
    }

    /**
     * Registra que un bot acaba de llamar a una función `iniciar...` y
     * espera que la animación aparezca en salaFlow pronto.
     */
    private fun registrarSolicitudAnimacion(tipo: String, botId: String) {
        if (!DEBUG_ANIMATIONS) return
        val clave = "$tipo|$botId"
        val ahora = System.currentTimeMillis()
        animacionesEsperadas[clave] = AnimacionEsperada(
            tipo = tipo,
            botId = botId,
            timestampSolicitud = ahora
        )
        Log.d(ANIM_TAG, "🚀 BOT-SOLICITA $tipo botId=$botId t=$ahora")
    }

    /**
     * Loguea un evento del flujo del bot (esperar, releer, confirmar, limpiar).
     */
    private fun logFlujoBot(evento: String, tipo: String, botId: String, detalles: String = "") {
        if (!DEBUG_ANIMATIONS) return
        Log.d(ANIM_TAG, "🤖 $evento $tipo botId=$botId $detalles".trim())
    }


    //
    // El cliente humano en GameTableScreen tiene un LaunchedEffect que cierra
    // la ventana VOY cuando expira y él es el robador. Pero ese efecto sólo
    // dispara cuando voy.jugadorRobandoId == jugadorLocal.id (un humano).
    //
    // Si el bot abre la ventana (porque pidió robar pozo/descarte), nadie en
    // el lado humano la va a cerrar. El bot debe encargarse desde el lado
    // del host (que orquesta al bot).
    //
    // Sólo el HOST programa este cierre. Si hay dos hosts (no debería),
    // ambos podrían intentar cerrar; el segundo recibe error idempotente.
    // ─────────────────────────────────────────

    private fun programarCierreVentanaVoyDeBot(sala: Sala) {
        val voy = sala.voyPendiente

        // Si no hay ventana o no está en fase VENTANA, cancelar cualquier job pendiente
        if (voy == null || !voy.activo || voy.fase != "VENTANA") {
            if (jobCierreVentanaVoy?.isActive == true) {
                jobCierreVentanaVoy?.cancel()
            }
            ventanaVoyActualId = ""
            return
        }

        // El cierre sólo lo programa el robador. Si el robador no es un bot,
        // el cliente humano lo manejará por su lado.
        val robador = sala.jugadores[voy.jugadorRobandoId]
        if (robador == null || !robador.esBot) return

        // Si ya tenemos un job para esta misma ventana, no reprogramar
        if (voy.id == ventanaVoyActualId && jobCierreVentanaVoy?.isActive == true) return

        // Nueva ventana: cancelar la vieja y programar la nueva
        jobCierreVentanaVoy?.cancel()
        ventanaVoyActualId = voy.id

        val transcurrido = System.currentTimeMillis() - voy.timestampInicio
        val restanteMs = (voy.duracionMs - transcurrido).coerceAtLeast(0L) + 150L

        Log.d(TAG, "Programando cierre de ventana VOY del bot ${voy.jugadorRobandoId} en ${restanteMs}ms")

        jobCierreVentanaVoy = scope.launch {
            try {
                delay(restanteMs)

                // Re-leer el estado de la ventana antes de resolver
                val salaActual = salaFlow.value ?: return@launch
                val voyActual = salaActual.voyPendiente ?: return@launch

                // Solo resolver si sigue siendo la misma ventana y sigue en fase VENTANA
                if (voyActual.id != voy.id) return@launch
                if (!voyActual.activo) return@launch
                if (voyActual.fase != "VENTANA") return@launch

                Log.d(TAG, "Cerrando ventana VOY sin reclamo para bot ${voy.jugadorRobandoId}")

                GameActions.resolverVoySinReclamo(
                    salaId = salaId,
                    jugadorId = voy.jugadorRobandoId,
                    sala = salaActual,
                    voyId = voy.id
                ) { msg ->
                    Log.w(TAG, "No se pudo resolver VOY sin reclamo: $msg")
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    Log.e(TAG, "Excepción cerrando ventana VOY del bot", e)
                }
            }
        }
    }


    // Recorre todos los bots que NO están en turno y verifica si alguno
    // puede hacer descarte espontáneo. Si encuentra uno, lanza un job
    // separado para esa reacción.
    //
    // Para evitar tormentas de jobs cuando hay varios bots elegibles,
    // mantenemos un mapa de jobs de reacción por bot. Si un bot ya tiene
    // un job de reacción activo, no se le lanza otro.
    // ─────────────────────────────────────────

    private fun evaluarReaccionesFueraDeTurno(sala: Sala) {
        if (!sala.estaEnJuego) return
        if (sala.cartaPoderActiva != null) return        // regla del adelantado
        // Nota: Si hay voyPendiente activo, los bots SÍ deben evaluar (para
        // reclamar VOY, seleccionar objetivo o entregar). BotBrain decide.
        if (sala.adelantadoPendiente?.activo == true) return

        val botsEnSala = sala.jugadores.values.filter { it.esBot && !it.descalificado }

        botsEnSala.forEach { bot ->
            // No evaluamos al bot que está en turno (ya lo hace evaluarTurnoDeBot)
            if (sala.turnoActualId == bot.id) return@forEach

            // FIX RACE: Si el job de turno del bot SIGUE activo (en su delay
            // post-animación tras ConfirmarDescarteFree, CambiarPorPosicion, etc.),
            // NO lanzar una reacción paralela. Eso causaría dos escrituras
            // simultáneas en Firebase pisándose mutuamente.
            // Ver bug log 17:05:28-31: descarteFreeAnimando + descarteEspontaneoAnimando
            // del mismo bot causaron carta "colgada" en el descarte.
            if (botIdEnTurnoActivo == bot.id && jobTurnoActual?.isActive == true) {
                Log.v(TAG, "evaluarReaccionesFueraDeTurno: bot ${bot.id} tiene turno activo aún, no relanzo")
                return@forEach
            }

            // Si ya hay un job de reacción activo para este bot, no lanzar otro
            val jobExistente = jobsReaccionPorBot[bot.id]
            if (jobExistente?.isActive == true) return@forEach

            // Consultar al cerebro qué haría este bot ahora
            val memoria = memoriasPorBot[bot.id] ?: return@forEach
            val vista = VistaParcialBuilder.construir(sala, bot.id)
            val decision = BotBrain.decidir(memoria, vista)

            // Solo arrancamos un job si hay decisión real
            if (decision is DecisionBot.NoOp) return@forEach

            Log.d(TAG, "Bot ${bot.id} reacciona fuera de turno: ${decision::class.simpleName}")

            val nuevoJob = scope.launch {
                try {
                    ejecutarReaccionFueraDeTurno(bot.id, decision)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) {
                        Log.d(TAG, "Job de reacción del bot ${bot.id} cancelado")
                    } else {
                        Log.e(TAG, "Excepción en reacción del bot ${bot.id}", e)
                    }
                } finally {
                    // FIX CONGELAMIENTO v3: ver explicación en finally de
                    // jobTurnoActual. delay(80) da tiempo al job exterior a
                    // completarse y a Firebase a propagar el nuevo estado.
                    scope.launch {
                        delay(80L)
                        val ultima = salaFlow.value ?: return@launch
                        Log.v(TAG, "Job de reacción terminado, reevaluando (turno=${ultima.turnoActualId})")
                        evaluarTurnoDeBot(ultima)
                        evaluarReaccionesFueraDeTurno(ultima)
                    }
                }
            }
            jobsReaccionPorBot[bot.id] = nuevoJob
        }
    }

    /**
     * Ejecuta una decisión de reacción fuera de turno con su delay humanizador.
     * Re-valida la decisión justo antes de actuar para evitar disparar contra
     * estado que ya cambió (otro jugador descartó la suya, animación se inició,
     * etc.).
     */
    private suspend fun ejecutarReaccionFueraDeTurno(botId: String, decision: DecisionBot) {
        // Delay humanizador para evitar "bot perfecto" e impactos en race conditions
        val categoria = decision.categoriaDelay()
        val delayMs = Random.nextLong(
            categoria.rangoMs.first.toLong(),
            categoria.rangoMs.last.toLong() + 1
        )
        delay(delayMs)

        // Re-validar: el estado pudo haber cambiado durante el delay
        val sala = salaFlow.value ?: return
        val memoria = memoriasPorBot[botId] ?: return
        val vista = VistaParcialBuilder.construir(sala, botId)
        val decisionFresca = BotBrain.decidir(memoria, vista)

        // Si la decisión cambió de clase (otro jugador descartó primero, fase
        // VOY avanzó, etc.), abortamos. El próximo ciclo de evaluación
        // detectará la nueva oportunidad si aplica.
        if (decisionFresca::class != decision::class) {
            Log.d(TAG, "Bot $botId: decisión fuera de turno ya no aplica (${decisionFresca::class.simpleName})")
            return
        }

        // Para descarte espontáneo, además validar que la posición no cambió
        if (decision is DecisionBot.DescarteEspontaneo &&
            decisionFresca is DecisionBot.DescarteEspontaneo &&
            decision.posicion != decisionFresca.posicion
        ) {
            Log.d(TAG, "Bot $botId: posición del descarte espontáneo cambió, abortar")
            return
        }

        // FIX PISADA-ENTRE-BOTS: Si va a hacer descarte espontáneo pero ya hay
        // una animación de descarte espontáneo activa de OTRO ejecutor, abortar.
        // Con muchos bots, varios pueden coincidir en el mismo valor de cima y
        // dispararían descartes simultáneos que se pisan en Firebase. El primero
        // que llegó gana; los demás esperan a la próxima oportunidad.
        if (decision is DecisionBot.DescarteEspontaneo) {
            val animActiva = sala.descarteEspontaneoAnimando
            if (animActiva != null && animActiva.ejecutorId.isNotBlank() && animActiva.ejecutorId != botId) {
                Log.d(TAG, "Bot $botId: ya hay descarte espontáneo de ${animActiva.ejecutorId} en curso, cedo")
                return
            }
        }

        // Para acciones VOY con datos posicionales, usar SIEMPRE la decisión
        // fresca: lo que el cerebro decida AHORA con la sala actual.
        // Esto evita seleccionar/entregar una posición que ya cambió durante
        // el delay (por ej., el rival objetivo movió cartas).
        val decisionAEjecutar = when (decisionFresca) {
            is DecisionBot.SeleccionarObjetivoVoy,
            is DecisionBot.EntregarCartaVoy -> decisionFresca
            else -> decision
        }

        Log.d(TAG, "Bot $botId ejecuta reacción: ${decisionAEjecutar::class.simpleName}")

        when (decisionAEjecutar) {
            is DecisionBot.DescarteEspontaneo -> {
                val cimaPre = sala.mazoDescarte.lastOrNull()
                val sizePre = sala.mazoDescarte.size

                registrarSolicitudAnimacion("descarteEspontaneoAnimando", botId)
                logFlujoBot(
                    "INICIA",
                    "descarteEspontaneoAnimando",
                    botId,
                    "posicion=${decisionAEjecutar.posicion} cimaPre=${cimaPre?.valor}${cimaPre?.palo?.take(1)} sizePre=$sizePre"
                )
                GameActions.descartarEspontaneo(
                    salaId = salaId,
                    jugadorId = botId,
                    sala = sala,
                    posicionCarta = decisionAEjecutar.posicion
                ) { msg ->
                    Log.d(TAG, "Bot $botId: descarte espontáneo rechazado: $msg")
                    logFlujoBot("ERROR-EJECUCION", "descarteEspontaneoAnimando", botId, "msg=$msg")
                }

                // Log inmediato del post-descarte (antes del delay de animación)
                delay(150L)
                val salaPost = salaFlow.value
                val cimaPost = salaPost?.mazoDescarte?.lastOrNull()
                val sizePost = salaPost?.mazoDescarte?.size ?: -1
                val cartaNueva = if (sizePost > sizePre) cimaPost else null
                logFlujoBot(
                    "POST-DESCARTE", "descarteEspontaneoAnimando", botId,
                    "cimaPost=${cimaPost?.valor}${cimaPost?.palo?.take(1)} sizePost=$sizePost " +
                            "cartaNueva=${cartaNueva?.valor}${cartaNueva?.palo?.take(1)}"
                )

                // Esperar duración de animación y limpiar
                logFlujoBot(
                    "ESPERA",
                    "descarteEspontaneoAnimando",
                    botId,
                    "delayPlaneado=${DURACION_ANIMACION_DESCARTE_ESPONTANEO_MS - 150L}ms"
                )
                delay(DURACION_ANIMACION_DESCARTE_ESPONTANEO_MS - 150L)
                logFlujoBot("LIMPIA", "descarteEspontaneoAnimando", botId)
                GameActions.limpiarAnimacionDescarteEspontaneo(salaId)
            }

            // ── VOY Fase Ventana: reclamar ──
            is DecisionBot.ReclamarVoy -> {
                GameActions.reclamarVoy(
                    salaId = salaId,
                    jugadorId = botId,
                    sala = sala
                ) { exito, msg ->
                    if (exito) {
                        Log.d(TAG, "Bot $botId reclamó VOY exitosamente")
                    } else {
                        Log.d(TAG, "Bot $botId no pudo reclamar VOY: $msg")
                    }
                }
            }

            // ── VOY Fase Objetivo: seleccionar carta del rival ──
            is DecisionBot.SeleccionarObjetivoVoy -> {
                GameActions.seleccionarCartaObjetivoVoy(
                    salaId = salaId,
                    jugadorId = botId,
                    sala = sala,
                    propietarioObjetivoId = decisionAEjecutar.rivalId,
                    posicionObjetivo = decisionAEjecutar.posicion
                ) { msg ->
                    Log.w(TAG, "Bot $botId no pudo seleccionar objetivo VOY: $msg")
                }
            }

            // ── VOY Fase Entrega: entregar carta propia ──
            is DecisionBot.EntregarCartaVoy -> {
                val cimaPre = sala.mazoDescarte.lastOrNull()
                val sizePre = sala.mazoDescarte.size

                logFlujoBot(
                    "INICIA", "voyEntrega", botId,
                    "pos=${decisionAEjecutar.posicion} cimaPre=${cimaPre?.valor}${cimaPre?.palo?.take(1)} sizePre=$sizePre"
                )

                GameActions.entregarCartaPropiaVoy(
                    salaId = salaId,
                    jugadorId = botId,
                    sala = sala,
                    posicionCartaPropia = decisionAEjecutar.posicion
                ) { msg ->
                    Log.w(TAG, "Bot $botId no pudo entregar carta VOY: $msg")
                    logFlujoBot("ERROR-EJECUCION", "voyEntrega", botId, "msg=$msg")
                }

                // Esperar a que la jugada se propague y loguear estado posterior
                delay(400L)
                val salaPost = salaFlow.value
                val cimaPost = salaPost?.mazoDescarte?.lastOrNull()
                val sizePost = salaPost?.mazoDescarte?.size ?: -1
                val cartaNueva = if (sizePost > sizePre) cimaPost else null
                logFlujoBot(
                    "POST-DESCARTE", "voyEntrega", botId,
                    "cimaPost=${cimaPost?.valor}${cimaPost?.palo?.take(1)} sizePost=$sizePost " +
                            "cartaNueva=${cartaNueva?.valor}${cartaNueva?.palo?.take(1)}"
                )
            }

            else -> {
                Log.w(TAG, "Bot $botId: decisión fuera de turno no soportada: ${decisionAEjecutar::class.simpleName}")
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

            // VOY pendiente: aún esperamos (Fase 3C lo manejará)
            if (sala.voyPendiente?.activo == true) {
                delay(300)
                continue
            }

            // Adelantado pendiente: si soy perjudicado, debo responder
            // (BotBrain devolverá ResolverAdelantado). Si no, espero.
            val adelantadoActivo = sala.adelantadoPendiente?.activo == true
            if (adelantadoActivo) {
                val soyPerjudicado = sala.adelantadoPendiente?.jugadorPerjudicadoId == botId
                if (!soyPerjudicado) {
                    delay(300)
                    continue
                }
                // Soy perjudicado → no esperamos, dejamos que el cerebro decida
            }

            // Si hay poder activo y NO es mío, esperar
            // (caso patológico salvo race condition)
            val poder = sala.cartaPoderActiva
            if (poder != null && poder.jugadorId != botId && !adelantadoActivo) {
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

        // Espera para casos de robo: o llega cartaEnMano (robo exitoso, sin VOY o
        // VOY sin reclamo), o el turno ya no es del bot (alguien reclamó VOY
        // exitosamente y el robo fue cancelado/redirigido). Si el bot reclamó
        // su propio VOY, su turno queda preservado pero cartaEnMano no llega;
        // en ese caso el bucle detectará turno != miId tras el flujo VOY si
        // la jugada termina con avance, o lo detectará por timeout.
        val esperarFinRoboConVoy: (Sala) -> Boolean = { s ->
            s.jugadores[botId]?.cartaEnMano != null ||
                    s.voyPendiente == null ||
                    s.voyPendiente?.activo == false
        }

        val resultado: ((Sala) -> Boolean)? = when (decision) {
            is DecisionBot.CantarBrata -> {
                GameActions.presionarBrata(salaId, botId, sala)
                null
            }

            is DecisionBot.RobarDelPozo -> {
                GameActions.solicitarRoboDelPozoConVoy(salaId, botId, sala) { msg ->
                    Log.w(TAG, "Bot $botId no pudo solicitar robar pozo: $msg")
                }
                // Esperar a que la ventana VOY se resuelva. Si se resuelve sin
                // reclamo → llega cartaEnMano. Si alguien reclamó → voyPendiente
                // se desactiva y el bucle continúa.
                esperarFinRoboConVoy
            }

            is DecisionBot.RobarDelDescarte -> {
                GameActions.solicitarRoboDelDescarteConVoy(salaId, botId, sala) { msg ->
                    Log.w(TAG, "Bot $botId no pudo solicitar robar descarte: $msg")
                }
                esperarFinRoboConVoy
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

                        val cimaPreCP = sala.mazoDescarte.lastOrNull()
                        val sizePreCP = sala.mazoDescarte.size

                        // 1. Iniciar animación visible
                        registrarSolicitudAnimacion("cambioPropioAnimando", botId)
                        logFlujoBot(
                            "INICIA",
                            "cambioPropioAnimando",
                            botId,
                            "pos=${decision.posicion} cartaEnManoId=${cartaEnMano.id} " +
                                    "cartaDestino=${cartaDestino.valor}${cartaDestino.palo.take(1)} " +
                                    "cimaPre=${cimaPreCP?.valor}${cimaPreCP?.palo?.take(1)} sizePre=$sizePreCP"
                        )
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
                        logFlujoBot(
                            "ESPERA",
                            "cambioPropioAnimando",
                            botId,
                            "delayPlaneado=${DURACION_ANIMACION_CAMBIO_PROPIO_MS}ms"
                        )
                        delay(DURACION_ANIMACION_CAMBIO_PROPIO_MS)

                        // 3. Releer estado y confirmar
                        val salaFresca = salaFlow.value
                        val animFresca = salaFresca?.cambioPropioAnimando

                        logFlujoBot(
                            "RELEE",
                            "cambioPropioAnimando",
                            botId,
                            "flagPresente=${animFresca != null} animId=${animFresca?.id ?: "<null>"} ejecutor=${animFresca?.ejecutorId ?: "<null>"}"
                        )

                        if (salaFresca != null &&
                            animFresca != null &&
                            animFresca.ejecutorId == botId
                        ) {
                            val cartaEnManoFresca = salaFresca.jugadores[botId]?.cartaEnMano
                            if (cartaEnManoFresca != null && cartaEnManoFresca.id == animFresca.cartaEnManoId) {
                                logFlujoBot(
                                    "CONFIRMA",
                                    "cambioPropioAnimando",
                                    botId,
                                    "animId=${animFresca.id}"
                                )
                                GameActions.confirmarCambioPropioAnimado(
                                    salaId = salaId,
                                    jugadorId = botId,
                                    sala = salaFresca,
                                    posicionDestino = animFresca.posicion,
                                    cartaEnMano = cartaEnManoFresca,
                                    animacionId = animFresca.id
                                ) { msg ->
                                    Log.w(TAG, "Bot $botId no pudo confirmar cambio propio: $msg")
                                    Log.e(ANIM_TAG, "❌ CONFIRMA-FALLA cambioPropioAnimando botId=$botId msg=$msg")
                                }

                                // Post-confirm: verificar estado del descarte
                                delay(200L)
                                val salaPostCP = salaFlow.value
                                val cimaPostCP = salaPostCP?.mazoDescarte?.lastOrNull()
                                val sizePostCP = salaPostCP?.mazoDescarte?.size ?: -1
                                val flagPostCP = salaPostCP?.cambioPropioAnimando
                                val cartaNuevaCP = if (sizePostCP > sizePreCP) cimaPostCP else null
                                logFlujoBot(
                                    "POST-DESCARTE", "cambioPropioAnimando", botId,
                                    "cimaPost=${cimaPostCP?.valor}${cimaPostCP?.palo?.take(1)} sizePost=$sizePostCP " +
                                            "cartaNueva=${cartaNuevaCP?.valor}${cartaNuevaCP?.palo?.take(1)} " +
                                            "flagLimpio=${flagPostCP == null}"
                                )
                            } else {
                                Log.w(TAG, "Bot $botId: estado inconsistente al confirmar cambio propio")
                                Log.e(
                                    ANIM_TAG,
                                    "❌ ESTADO-INCONSISTENTE cambioPropioAnimando botId=$botId " +
                                            "cartaEnManoFresca=${cartaEnManoFresca?.id ?: "<null>"} " +
                                            "esperabaCartaEnManoId=${animFresca.cartaEnManoId}"
                                )
                            }
                        } else {
                            Log.w(TAG, "Bot $botId: cambioPropioAnimando ya no activo al confirmar")
                            Log.e(
                                ANIM_TAG,
                                "❌ FLAG-NULO-AL-CONFIRMAR cambioPropioAnimando botId=$botId " +
                                        "salaFresca=${salaFresca != null} animFresca=${animFresca != null} " +
                                        "ejecutorEsperado=$botId ejecutorReal=${animFresca?.ejecutorId ?: "<null>"}"
                            )
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
                registrarSolicitudAnimacion("espiaAnimando", botId)
                logFlujoBot(
                    "INICIA",
                    "espiaAnimando",
                    botId,
                    "tipo=ESPIAR cartaId=${decision.cartaId}"
                )
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
                registrarSolicitudAnimacion("espiaAnimando", botId)
                logFlujoBot(
                    "INICIA",
                    "espiaAnimando",
                    botId,
                    "tipo=CAMBIAR_VIENDO cartaId=${decision.cartaId}"
                )
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
                        registrarSolicitudAnimacion("descarteEspontaneoAnimando", botId)
                        logFlujoBot(
                            "INICIA", "descarteEspontaneoAnimando(via descartarEspiada)", botId,
                            "cartaEspiadaId=$cartaEspiadaId"
                        )
                        GameActions.descartarCartaEspiadaPropia(
                            salaId = salaId,
                            jugadorId = botId,
                            sala = salaFresca,
                            cartaEspiada = cartaEspiada
                        ) { msg ->
                            Log.w(TAG, "Bot $botId no pudo descartar espiada: $msg")
                            logFlujoBot("ERROR-EJECUCION", "descarteEspontaneoAnimando(via descartarEspiada)", botId, "msg=$msg")
                        }

                        // Limpiar animación del espía (la carta levantada).
                        GameActions.limpiarAnimacionEspia(salaId)

                        // descartarCartaEspiadaPropia ESCRIBE descarteEspontaneoAnimando
                        // (reutiliza la animación de carta-de-mesa-al-pozo). El bot
                        // debe limpiarla tras la duración visible, igual que en
                        // descarte espontáneo y descarte free. Sin esto queda zombie.
                        delay(DURACION_ANIMACION_DESCARTE_ESPONTANEO_MS)
                        logFlujoBot("LIMPIA", "descarteEspontaneoAnimando(via descartarEspiada)", botId)
                        GameActions.limpiarAnimacionDescarteEspontaneo(salaId)
                    } else {
                        // Fallback: regresar
                        GameActions.regresarCartaEspiada(salaId, botId, salaFresca)
                        // Limpiar animación visual del espía.
                        GameActions.limpiarAnimacionEspia(salaId)
                    }

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
                    registrarSolicitudAnimacion("swapAnimando", botId)
                    logFlujoBot(
                        "INICIA",
                        "swapAnimando",
                        botId,
                        "tipoPoder=CAMBIAR_VIENDO cartaA=${cartaA.carta.id} cartaB=${cartaB.carta.id}"
                    )
                    GameActions.iniciarAnimacionSwap(
                        salaId = salaId,
                        ejecutorId = botId,
                        cartaA = cartaA,
                        cartaB = cartaB,
                        mostrarCartaA = true,
                        mostrarCartaB = false
                    )

                    // Esperar la duración de la animación
                    logFlujoBot(
                        "ESPERA",
                        "swapAnimando",
                        botId,
                        "delayPlaneado=${DURACION_ANIMACION_SWAP_MS}ms"
                    )
                    delay(DURACION_ANIMACION_SWAP_MS)

                    // Releer el estado de la sala — el poder DEBE seguir activo
                    val salaFresca = salaFlow.value
                    val poderFresco = salaFresca?.cartaPoderActiva

                    if (salaFresca != null &&
                        poderFresco != null &&
                        poderFresco.jugadorId == botId &&
                        poderFresco.tipoPoder == com.aguado.bratagame.TipoPoder.CAMBIAR_VIENDO
                    ) {
                        logFlujoBot(
                            "CONFIRMA",
                            "swapAnimando",
                            botId,
                            "tipoPoder=CAMBIAR_VIENDO cartaA=${decision.cartaAId} cartaB=${decision.cartaBId}"
                        )
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
                            logFlujoBot("ERROR-EJECUCION", "swapAnimando", botId, "msg=$msg tipoPoder=CAMBIAR_VIENDO")
                        }

                        // Post-confirm: verificar que la animación se limpió
                        delay(200L)
                        val salaPostSV = salaFlow.value
                        val flagPostSV = salaPostSV?.swapAnimando
                        logFlujoBot(
                            "POST-DESCARTE", "swapAnimando", botId,
                            "tipoPoder=CAMBIAR_VIENDO flagLimpio=${flagPostSV == null}"
                        )
                    } else {
                        Log.w(
                            TAG,
                            "Bot $botId: poder ya no activo al confirmar viendo (poder=${poderFresco?.tipoPoder}). Limpiando animación."
                        )
                        Log.e(
                            ANIM_TAG,
                            "❌ PODER-INACTIVO swapAnimando botId=$botId tipoPoder=CAMBIAR_VIENDO " +
                                    "poderFresco=${poderFresco?.tipoPoder ?: "<null>"}"
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
                    registrarSolicitudAnimacion("swapAnimando", botId)
                    logFlujoBot(
                        "INICIA",
                        "swapAnimando",
                        botId,
                        "tipoPoder=CAMBIAR_SIN_VER cartaA=${cartaA.carta.id} cartaB=${cartaB.carta.id}"
                    )
                    GameActions.iniciarAnimacionSwap(
                        salaId = salaId,
                        ejecutorId = botId,
                        cartaA = cartaA,
                        cartaB = cartaB,
                        mostrarCartaA = false,
                        mostrarCartaB = false
                    )

                    logFlujoBot(
                        "ESPERA",
                        "swapAnimando",
                        botId,
                        "delayPlaneado=${DURACION_ANIMACION_SWAP_MS}ms"
                    )
                    delay(DURACION_ANIMACION_SWAP_MS)

                    val salaFresca = salaFlow.value
                    val poderFresco = salaFresca?.cartaPoderActiva

                    if (salaFresca != null &&
                        poderFresco != null &&
                        poderFresco.jugadorId == botId &&
                        poderFresco.tipoPoder == com.aguado.bratagame.TipoPoder.CAMBIAR_SIN_VER
                    ) {
                        logFlujoBot(
                            "CONFIRMA",
                            "swapAnimando",
                            botId,
                            "tipoPoder=CAMBIAR_SIN_VER cartaA=${decision.cartaAId} cartaB=${decision.cartaBId}"
                        )
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
                            logFlujoBot("ERROR-EJECUCION", "swapAnimando", botId, "msg=$msg tipoPoder=CAMBIAR_SIN_VER")
                        }

                        // Post-confirm: verificar que la animación se limpió
                        delay(200L)
                        val salaPostSSV = salaFlow.value
                        val flagPostSSV = salaPostSSV?.swapAnimando
                        logFlujoBot(
                            "POST-DESCARTE", "swapAnimando", botId,
                            "tipoPoder=CAMBIAR_SIN_VER flagLimpio=${flagPostSSV == null}"
                        )
                    } else {
                        Log.w(
                            TAG,
                            "Bot $botId: poder ya no activo al confirmar sin ver (poder=${poderFresco?.tipoPoder}). Limpiando animación."
                        )
                        Log.e(
                            ANIM_TAG,
                            "❌ PODER-INACTIVO swapAnimando botId=$botId tipoPoder=CAMBIAR_SIN_VER " +
                                    "poderFresco=${poderFresco?.tipoPoder ?: "<null>"}"
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
            //
            // OJO: GameActions.confirmarDescarteFree escribe en Firebase el campo
            // `descarteEspontaneoAnimando` (NO `descarteFreeAnimando`), porque
            // visualmente reutiliza la misma animación de carta-de-mesa-al-pozo.
            // Por eso registramos, esperamos y limpiamos `descarteEspontaneoAnimando`.
            //
            // Bug histórico (resuelto): antes registrábamos/limpiábamos
            // `descarteFreeAnimando`, que NUNCA se escribía en Firebase. La
            // animación `descarteEspontaneoAnimando` real quedaba zombie por el
            // resto del juego, causando carta colgada en el pozo, z-index sobre
            // modales, etc.
            is DecisionBot.ConfirmarDescarteFree -> {
                val cimaPreDF = sala.mazoDescarte.lastOrNull()
                val sizePreDF = sala.mazoDescarte.size

                registrarSolicitudAnimacion("descarteEspontaneoAnimando", botId)
                logFlujoBot(
                    "INICIA",
                    "descarteEspontaneoAnimando(via descarteFree)",
                    botId,
                    "posicionDescartada=${decision.posicion} cimaPre=${cimaPreDF?.valor}${cimaPreDF?.palo?.take(1)} sizePre=$sizePreDF"
                )
                GameActions.confirmarDescarteFree(
                    salaId = salaId,
                    jugadorId = botId,
                    sala = sala,
                    posicionDescartada = decision.posicion
                ) { msg ->
                    Log.w(TAG, "Bot $botId no pudo confirmar descarte free: $msg")
                    logFlujoBot("ERROR-EJECUCION", "descarteEspontaneoAnimando(via descarteFree)", botId, "msg=$msg")
                }

                // Post-acción inmediata: ver qué quedó en el descarte
                delay(150L)
                val salaPostDF = salaFlow.value
                val cimaPostDF = salaPostDF?.mazoDescarte?.lastOrNull()
                val sizePostDF = salaPostDF?.mazoDescarte?.size ?: -1
                val cartaNuevaDF = if (sizePostDF > sizePreDF) cimaPostDF else null
                logFlujoBot(
                    "POST-DESCARTE", "descarteEspontaneoAnimando(via descarteFree)", botId,
                    "cimaPost=${cimaPostDF?.valor}${cimaPostDF?.palo?.take(1)} sizePost=$sizePostDF " +
                            "cartaNueva=${cartaNuevaDF?.valor}${cartaNuevaDF?.palo?.take(1)}"
                )

                logFlujoBot(
                    "ESPERA",
                    "descarteEspontaneoAnimando(via descarteFree)",
                    botId,
                    "delayPlaneado=${DURACION_ANIMACION_DESCARTE_ESPONTANEO_MS - 150L}ms"
                )
                delay(DURACION_ANIMACION_DESCARTE_ESPONTANEO_MS - 150L)
                logFlujoBot("LIMPIA", "descarteEspontaneoAnimando(via descarteFree)", botId)
                // IMPORTANTE: limpiar AMBOS campos:
                // - descarteFreeAnimando fue escrito por activarDescarteFree (paso 1).
                // - descarteEspontaneoAnimando fue escrito por confirmarDescarteFree (paso 2).
                // El cliente humano solo limpia si jugadorLocal == ejecutor;
                // como ejecutor es bot, debemos limpiar nosotros.
                GameActions.limpiarAnimacionDescarteEspontaneo(salaId)
                GameActions.limpiarAnimacionDescarteFree(salaId)
                null
            }

            // ── Respuesta al adelantado (Fase 3B) ──
            is DecisionBot.ResolverAdelantado -> {
                val cimaPre = sala.mazoDescarte.lastOrNull()
                val sizePre = sala.mazoDescarte.size

                logFlujoBot(
                    "INICIA", "resolverAdelantado", botId,
                    "pos=${decision.posicionAEntregar} cimaPre=${cimaPre?.valor}${cimaPre?.palo?.take(1)} sizePre=$sizePre"
                )

                GameActions.resolverRobarDescartePorAdelantado(
                    salaId = salaId,
                    jugadorId = botId,
                    sala = sala,
                    posicionCartaPropia = decision.posicionAEntregar
                ) { msg ->
                    Log.w(TAG, "Bot $botId no pudo resolver adelantado: $msg")
                    logFlujoBot("ERROR-EJECUCION", "resolverAdelantado", botId, "msg=$msg")
                }
                null
            }

            // ── Decisiones de fase futura (4: sabotaje) ──
            is DecisionBot.RobarDescarteAdelantado -> {
                Log.w(TAG, "Bot $botId devolvió decisión de fase futura: $decision")
                null
            }

            // Las acciones de VOY y descarte espontáneo se manejan por
            // ejecutarReaccionFueraDeTurno, NO por el bucle del turno propio.
            // Si llegan acá, es un bug en BotBrain (cambió el estado entre
            // evaluación y ejecución, o devolvió decisión fuera de contexto).
            is DecisionBot.DescarteEspontaneo,
            is DecisionBot.ReclamarVoy,
            is DecisionBot.SeleccionarObjetivoVoy,
            is DecisionBot.EntregarCartaVoy -> {
                Log.w(TAG, "Bot $botId: decisión fuera de turno llegó al bucle del turno propio (bug): ${decision::class.simpleName}")
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

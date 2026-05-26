package com.aguado.bratagame

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.aguado.bratagame.ui.screens.GameTableScreen
import com.aguado.bratagame.ui.screens.LobbyScreen
import com.aguado.bratagame.ui.screens.LoginScreen
import com.aguado.bratagame.ui.screens.ResultScreen
import java.util.UUID
import android.content.Context
import com.aguado.bratagame.game.TurnManager
import com.aguado.bratagame.bot.BotOrchestrator
import com.aguado.bratagame.bot.BotFirebaseRepository
import android.app.ActivityManager
import android.os.Build
import androidx.core.content.ContextCompat
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            setTaskDescription(
                ActivityManager.TaskDescription(
                    getString(R.string.app_name),
                    R.mipmap.ic_launcher,
                    ContextCompat.getColor(this, R.color.task_color)
                )
            )
        }

        val prefs = getSharedPreferences("brata_prefs", Context.MODE_PRIVATE)

        setContent {
            // ID persistente por dispositivo.
            // Permite reconectar a una partida si la app se cerró por error.
            val idUsuarioUnico = rememberSaveable {
                val existente = prefs.getString("jugador_id", null)

                if (existente != null) {
                    existente
                } else {
                    val nuevo = UUID.randomUUID().toString()
                    prefs.edit()
                        .putString("jugador_id", nuevo)
                        .apply()
                    nuevo
                }
            }

            var jugadorActual by remember { mutableStateOf<Jugador?>(null) }
            var idSalaActual by remember { mutableStateOf<String?>(null) }
            var salaActual by remember { mutableStateOf<Sala?>(null) }
            var pantalla by remember { mutableStateOf(Pantalla.LOGIN) }

            // Guarda la última partida que este dispositivo ya reconoció.
            // Si partidaId cambia, significa que empezó una revancha o nueva partida.
            var ultimaPartidaIdVista by rememberSaveable { mutableStateOf("") }

            LaunchedEffect(Unit) {
                val salaGuardada = prefs.getString("sala_id", null)
                val nombreGuardado = prefs.getString("jugador_nombre", null)

                if (salaGuardada != null && nombreGuardado != null) {
                    FirebaseManager.reconectarJugadorASala(
                        salaId = salaGuardada,
                        jugadorId = idUsuarioUnico
                    ) { exito ->
                        if (exito) {
                            jugadorActual = Jugador(
                                id = idUsuarioUnico,
                                nombre = nombreGuardado
                            )

                            idSalaActual = salaGuardada
                            pantalla = Pantalla.MESA
                        } else {
                            prefs.edit()
                                .remove("sala_id")
                                .remove("jugador_nombre")
                                .apply()

                            jugadorActual = null
                            idSalaActual = null
                            salaActual = null
                            pantalla = Pantalla.LOGIN
                        }
                    }
                }
            }

            // ── Listener central de Firebase ──────────
            DisposableEffect(idSalaActual) {
                val salaId = idSalaActual
                if (salaId == null) {
                    return@DisposableEffect onDispose { }
                }

                val listener = FirebaseManager.observarSala(salaId) { sala ->
                    salaActual = sala

                    if (sala != null) {

                        // Evento 1: La sala volvió al lobby desde resultados.
                        // Esto permite que todos los dispositivos regresen al lobby
                        // cuando un jugador presiona el botón LOBBY en resultados.
                        if (
                            pantalla == Pantalla.RESULTADO &&
                            !sala.estaEnJuego
                        ) {
                            pantalla = Pantalla.LOBBY
                        }

                        // Evento 2: Inicio de partida o revancha.
                        // Si inicia una nueva partida desde lobby, todos entran a mesa.
                        // Evento 2: Inicio de partida o revancha.
                        // Cuando cambia partidaId, todos deben entrar a MESA,
                        // incluso si estaban en RESULTADO.
                        val hayNuevaPartida =
                            sala.estaEnJuego &&
                                    sala.partidaId.isNotBlank() &&
                                    sala.partidaId != ultimaPartidaIdVista

                        if (
                            hayNuevaPartida &&
                            pantalla != Pantalla.LOGIN
                        ) {
                            ultimaPartidaIdVista = sala.partidaId
                            pantalla = Pantalla.MESA
                        }

                        // Evento 3: Fin de ronda
                        // Puede terminar por BRATA o porque solo queda un jugador activo.
                        if (
                            pantalla == Pantalla.MESA &&
                            sala.partidaId.isNotBlank() &&
                            sala.partidaId == ultimaPartidaIdVista &&
                            TurnManager.debeEvaluarFinal(sala)
                        ) {
                            pantalla = Pantalla.RESULTADO
                        }

                        // Evento 4: Jugador expulsado o anfitrión cerró la sala
                        if (!sala.jugadores.containsKey(jugadorActual?.id) &&
                            pantalla != Pantalla.LOGIN
                        ) {
                            jugadorActual = null
                            idSalaActual = null
                            salaActual = null
                            pantalla = Pantalla.LOGIN
                        }

                    } else {
                        // Sala eliminada completamente de Firebase
                        if (pantalla != Pantalla.LOGIN) {
                            jugadorActual = null
                            idSalaActual = null
                            salaActual = null
                            pantalla = Pantalla.LOGIN
                        }
                    }
                }

                onDispose { FirebaseManager.dejarDeObservarSala(salaId, listener) }
            }

            when (pantalla) {

                // ── 1. LOGIN ──────────────────────────────
                Pantalla.LOGIN -> {
                    LoginScreen { nombreLogin, idSala ->
                        val esCreacion = idSala.startsWith("CREAR_SALA:")
                        val jugador = Jugador(
                            id = idUsuarioUnico,
                            nombre = nombreLogin,
                            esAnfitrion = esCreacion
                        )
                        jugadorActual = jugador

                        if (esCreacion) {
                            val nombreMesa = idSala.removePrefix("CREAR_SALA:")
                            FirebaseManager.crearSala(nombreMesa, jugador) { idGenerado ->
                                if (idGenerado != null) {
                                    prefs.edit()
                                        .putString("sala_id", idGenerado)
                                        .putString("jugador_nombre", jugador.nombre)
                                        .apply()

                                    idSalaActual = idGenerado
                                    pantalla = Pantalla.LOBBY
                                }
                            }
                        } else {
                            FirebaseManager.unirseASala(idSala, jugador) { exito ->
                                if (exito) {
                                    prefs.edit()
                                        .putString("sala_id", idSala)
                                        .putString("jugador_nombre", jugador.nombre)
                                        .apply()

                                    idSalaActual = idSala
                                    pantalla = Pantalla.LOBBY
                                } else {
                                    prefs.edit()
                                        .remove("sala_id")
                                        .remove("jugador_nombre")
                                        .apply()

                                    jugadorActual = null
                                    idSalaActual = null
                                    salaActual = null
                                    pantalla = Pantalla.LOGIN
                                }
                            }
                        }
                    }
                }

                // ── 2. LOBBY ──────────────────────────────
                Pantalla.LOBBY -> {
                    val jugador = jugadorActual
                    val salaId = idSalaActual
                    if (jugador != null && salaId != null) {
                        LobbyScreen(
                            jugadorLocal = jugador,
                            idSalaInicial = salaId,
                            onSalirAlLogin = {
                                FirebaseManager.salirDeSala(salaId, jugador.id, jugador.esAnfitrion)

                                prefs.edit()
                                    .remove("sala_id")
                                    .remove("jugador_nombre")
                                    .apply()

                                // Si soy host y me voy, los nodos hermanos del bot también deben limpiarse
                                if (jugador.esAnfitrion) {
                                    BotFirebaseRepository.limpiarTodoElEstadoDeBots(salaId)
                                }

                                jugadorActual = null
                                idSalaActual = null
                                salaActual = null
                                pantalla = Pantalla.LOGIN
                            },
                            onIniciarJuego = {
                                // El listener del Evento 1 maneja la navegación
                                // Este callback queda como fallback local
                                pantalla = Pantalla.MESA
                            }
                        )
                    }
                }

                // ── 3. MESA DE JUEGO ──────────────────────
                Pantalla.MESA -> {
                    val jugador = jugadorActual
                    val salaId = idSalaActual
                    if (jugador != null && salaId != null) {

                        // ──────────────────────────────────────────
                        // BOT ORCHESTRATOR
                        // Solo el host instancia el orquestador. Si el host se va,
                        // el siguiente anfitrión (promovido por FirebaseManager)
                        // detecta el lock vencido (15 s) y retoma la ejecución de bots.
                        // ──────────────────────────────────────────
                        DisposableEffect(salaId, jugador.id, jugador.esAnfitrion) {
                            val orquestador = if (jugador.esAnfitrion) {
                                BotOrchestrator(
                                    salaId = salaId,
                                    hostId = jugador.id
                                ).also { it.iniciar() }
                            } else {
                                null
                            }

                            onDispose {
                                orquestador?.detener()
                            }
                        }

                        GameTableScreen(
                            jugadorLocal = jugador,
                            idSala = salaId,
                            onSalir = {
                                FirebaseManager.salirDeSala(salaId, jugador.id, jugador.esAnfitrion)

                                prefs.edit()
                                    .remove("sala_id")
                                    .remove("jugador_nombre")
                                    .apply()

                                // Si soy host y me voy, los nodos hermanos del bot también deben limpiarse
                                if (jugador.esAnfitrion) {
                                    BotFirebaseRepository.limpiarTodoElEstadoDeBots(salaId)
                                }

                                jugadorActual = null
                                idSalaActual = null
                                salaActual = null
                                pantalla = Pantalla.LOGIN
                            }
                        )
                    }
                }

                // ── 4. RESULTADO ──────────────────────────
                Pantalla.RESULTADO -> {
                    val jugador = jugadorActual
                    val sala = salaActual
                    if (jugador != null && sala != null) {
                        ResultScreen(
                            sala = sala,
                            jugadorLocalId = jugador.id,
                            esAnfitrion = jugador.esAnfitrion,
                            onRevancha = {
                                if (jugador.esAnfitrion) {
                                    val salaId = idSalaActual ?: return@ResultScreen

                                    FirebaseManager.iniciarPartida(
                                        salaId = salaId,
                                        jugadores = sala.jugadores.values.toList()
                                    )

                                    // No forzamos pantalla aquí.
                                    // El listener detectará partidaId nuevo y moverá a TODOS a MESA.
                                }
                            },
                            onIrAlLobby = {
                                val salaId = idSalaActual

                                if (salaId != null) {
                                    FirebaseManager.volverSalaAlLobby(
                                        salaId = salaId,
                                        sala = sala
                                    ) { exito ->
                                        if (exito) {
                                            pantalla = Pantalla.LOBBY
                                        }
                                    }
                                }
                            },
                            onIrAlInicio = {
                                // Si soy host y me voy, los nodos hermanos del bot también deben limpiarse
                                val salaId = idSalaActual
                                if (salaId != null) {
                                    FirebaseManager.salirDeSala(salaId, jugador.id, jugador.esAnfitrion)

                                    if (jugador.esAnfitrion) {
                                        BotFirebaseRepository.limpiarTodoElEstadoDeBots(salaId)
                                    }
                                }

                                prefs.edit()
                                    .remove("sala_id")
                                    .remove("jugador_nombre")
                                    .apply()

                                jugadorActual = null
                                idSalaActual = null
                                salaActual = null
                                pantalla = Pantalla.LOGIN
                            }
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────
// PANTALLAS
// ─────────────────────────────────────────────

enum class Pantalla {
    LOGIN,
    LOBBY,
    MESA,
    RESULTADO
}
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
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // ID único por dispositivo — evita duplicados al reconectar
            val idUsuarioUnico = rememberSaveable {
                val prefs = getSharedPreferences("brata_prefs", Context.MODE_PRIVATE)
                val existente = prefs.getString("jugador_id", null)

                if (existente != null) {
                    existente
                } else {
                    val nuevo = UUID.randomUUID().toString()
                    prefs.edit().putString("jugador_id", nuevo).apply()
                    nuevo
                }
            }

            var jugadorActual by remember { mutableStateOf<Jugador?>(null) }
            var idSalaActual by remember { mutableStateOf<String?>(null) }
            var salaActual by remember { mutableStateOf<Sala?>(null) }
            var pantalla by remember { mutableStateOf(Pantalla.LOGIN) }

            // ── Listener central de Firebase ──────────
            // Detecta tres eventos y navega en todos los dispositivos:
            //   1. Inicio de partida / revancha → MESA
            //   2. Fin de ronda (Brata completó vuelta) → RESULTADO
            //   3. Jugador expulsado o sala eliminada → LOGIN
            DisposableEffect(idSalaActual) {
                val salaId = idSalaActual
                if (salaId == null) {
                    return@DisposableEffect onDispose { }
                }

                val listener = FirebaseManager.observarSala(salaId) { sala ->
                    salaActual = sala

                    if (sala != null) {

                        // Evento 1: Inicio de partida o revancha
                        if (sala.estaEnJuego &&
                            (pantalla == Pantalla.LOBBY || pantalla == Pantalla.RESULTADO)
                        ) {
                            pantalla = Pantalla.MESA
                        }

                        // Evento 2: Fin de ronda
                        if (pantalla == Pantalla.MESA &&
                            sala.brataActivada &&
                            sala.turnoActualId == sala.brataJugadorId
                        ) {
                            pantalla = Pantalla.RESULTADO
                        }

                        // Evento 3: Jugador expulsado o anfitrión cerró la sala
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
                                    idSalaActual = idGenerado
                                    pantalla = Pantalla.LOBBY
                                }
                            }
                        } else {
                            FirebaseManager.unirseASala(idSala, jugador) { exito ->
                                if (exito) {
                                    idSalaActual = idSala
                                    pantalla = Pantalla.LOBBY
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
                        GameTableScreen(
                            jugadorLocal = jugador,
                            idSala = salaId,
                            onSalir = {
                                FirebaseManager.salirDeSala(salaId, jugador.id, jugador.esAnfitrion)
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
                            onRevancha = {
                                // Solo el anfitrión llama iniciarPartida
                                // Los demás navegan via Evento 1 del listener
                                if (jugador.esAnfitrion) {
                                    val salaId = idSalaActual ?: return@ResultScreen
                                    FirebaseManager.iniciarPartida(
                                        salaId = salaId,
                                        jugadores = sala.jugadores.values.toList()
                                    )
                                }
                                // El anfitrión navega localmente, los demás via listener
                                pantalla = Pantalla.MESA
                            },
                            onIrAlLobby = {
                                pantalla = Pantalla.LOBBY
                            },
                            onIrAlInicio = {
                                val salaId = idSalaActual
                                if (salaId != null) {
                                    FirebaseManager.salirDeSala(salaId, jugador.id, jugador.esAnfitrion)
                                }
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
package com.aguado.bratagame.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.aguado.bratagame.FirebaseManager
import com.aguado.bratagame.Jugador
import com.aguado.bratagame.Sala
import com.aguado.bratagame.ui.theme.DarkCasinoGreen
import com.aguado.bratagame.ui.theme.CasinoGold

@Composable
fun LobbyScreen(
    jugadorLocal: Jugador,
    idSalaInicial: String,
    onSalirAlLogin: () -> Unit,
    onIniciarJuego: () -> Unit
) {
    var datosSala by remember { mutableStateOf<Sala?>(null) }
    LaunchedEffect(Unit) {
        FirebaseManager.unirseASala(idSalaInicial, jugadorLocal) { }
    }

    // Escucha en tiempo real (Sincronización automática)
    DisposableEffect(idSalaInicial) {
        val listener = FirebaseManager.observarSala(idSalaInicial) { sala ->
            if (sala != null) {
                datosSala = sala

                // --- LÓGICA DE EXPULSIÓN ---
                // Si la sala existe pero MI ID ya no está en la lista de jugadores, me sacaron.
                val yoSigoEnLaSala = sala.jugadores.containsKey(jugadorLocal.id)
                if (!yoSigoEnLaSala) {
                    onSalirAlLogin()
                }

                if (sala.estaEnJuego) {
                    onIniciarJuego()
                }
            } else {
                // Si la sala se eliminó por completo
                onSalirAlLogin()
            }
        }
        onDispose { FirebaseManager.dejarDeObservarSala(idSalaInicial, listener) }
    }

    Box(modifier = Modifier.fillMaxSize().background(DarkCasinoGreen)) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "LOBBY: ${datosSala?.nombreSala ?: ""}",
                    color = CasinoGold,
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onSalirAlLogin) {
                    Icon(Icons.Default.ExitToApp, "Salir", tint = Color.Red)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            LazyColumn(modifier = Modifier.weight(1f)) {
                val jugadores = datosSala?.jugadores?.values?.toList() ?: emptyList()
                items(jugadores) { jugador ->
                    val esYo = jugador.id == jugadorLocal.id
                    val soyHost = datosSala?.jugadores?.get(jugadorLocal.id)?.esAnfitrion ?: false

                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (jugador.estaListo) Color(0xFF2E7D32) else Color.DarkGray
                        )
                    ) {
                        // AQUÍ CORREGIMOS LA ALTURA CON Alignment.CenterVertically
                        Row(
                            modifier = Modifier
                                .padding(horizontal = 16.dp, vertical = 8.dp) // Padding uniforme
                                .fillMaxWidth()
                                .height(48.dp), // Altura fija para que todas las tarjetas sean iguales
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (esYo) "${jugador.nombre} (Tú)" else jugador.nombre,
                                color = Color.White,
                                modifier = Modifier.weight(1f), // Toma el espacio sobrante sin empujar
                                maxLines = 1,
                                style = MaterialTheme.typography.bodyLarge
                            )

                            // Contenedor de iconos con ancho fijo para que no "salte" el diseño
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.wrapContentWidth()
                            ) {
                                if (jugador.esAnfitrion) {
                                    Text(
                                        text = "👑",
                                        modifier = Modifier.padding(horizontal = 8.dp)
                                    )
                                }

                                if (soyHost && !esYo) {
                                    // Usamos una Box con tamaño fijo para el botón de eliminar
                                    Box(
                                        modifier = Modifier.size(40.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        IconButton(
                                            onClick = {
                                                FirebaseManager.salirDeSala(idSalaInicial, jugador.id, false)
                                            },
                                            modifier = Modifier.size(24.dp) // Botón más pequeño
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = "Expulsar",
                                                tint = Color.Red,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        }
                                    }
                                } else {
                                    // Espaciador invisible para mantener el mismo ancho si no hay botón
                                    Spacer(modifier = Modifier.size(40.dp))
                                }
                            }
                        }
                    }
                }
            }

            val yo = datosSala?.jugadores?.get(jugadorLocal.id)
            Button(
                onClick = {
                    FirebaseManager.cambiarEstadoListo(idSalaInicial, jugadorLocal.id, !(yo?.estaListo ?: false))
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if(yo?.estaListo == true) Color.Red else CasinoGold
                )
            ) {
                Text(if(yo?.estaListo == true) "CANCELAR" else "ESTOY LISTO", color = Color.Black)
            }

            // BOTÓN COMENZAR (Solo para el Host)
            if (yo?.esAnfitrion == true) {
                val todosListos = datosSala?.jugadores?.values?.all { it.estaListo } ?: false
                Button(
                    onClick = {
                        val jugadoresConectados = datosSala?.jugadores?.values?.toList() ?: emptyList()
                        FirebaseManager.iniciarPartida(idSalaInicial, jugadoresConectados)
                        // iniciarPartida ya setea estaEnJuego = true internamente, no necesitas marcarJuegoIniciado
                    },
                    enabled = todosListos && (datosSala?.jugadores?.size ?: 0) >= 2,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White)
                ) {
                    Text("COMENZAR JUEGO", color = Color.Black)
                }
            }
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, backgroundColor = 0xFF0D3311)
@Composable
fun LobbyPreview() {
    // Creamos datos falsos solo para ver cómo se ve el diseño
    val jugadorFalso = Jugador(id = "1", nombre = "Manuel (Vista Previa)", esAnfitrion = true)

    // Llamamos a la pantalla real con funciones vacías {}
    LobbyScreen(
        jugadorLocal = jugadorFalso,
        idSalaInicial = "Mesa-Prueba",
        onSalirAlLogin = {},
        onIniciarJuego = {}
    )
}

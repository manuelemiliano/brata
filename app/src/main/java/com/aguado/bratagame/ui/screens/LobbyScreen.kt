package com.aguado.bratagame.ui.screens

import androidx.compose.foundation.Image
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.Offset
import com.aguado.bratagame.FirebaseManager
import com.aguado.bratagame.Jugador
import com.aguado.bratagame.Sala
import com.aguado.bratagame.R
import com.aguado.bratagame.ui.theme.CasinoGold
import com.aguado.bratagame.ui.theme.DarkCasinoGreen
@Composable
fun LobbyScreen(
    jugadorLocal: Jugador,
    idSalaInicial: String,
    onSalirAlLogin: () -> Unit,
    onIniciarJuego: () -> Unit
) {
    var datosSala by remember { mutableStateOf<Sala?>(null) }
    LaunchedEffect(Unit) {
        FirebaseManager.unirseASala(idSalaInicial, jugadorLocal) { exito ->
            if (!exito) {
                onSalirAlLogin()
            }
        }
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

    Box(modifier = Modifier.fillMaxSize()) {

        Image(
            painter = painterResource(id = R.drawable.login_lobby_bg),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        // Capa oscura opcional para mejorar contraste
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.35f))
        )

        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(175.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                BannerNombreSala(
                    nombreSala = datosSala?.nombreSala ?: idSalaInicial,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

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
            val textoBotonListo = if (yo?.estaListo == true) {
                "CANCELAR"
            } else {
                "ESTOY LISTO"
            }

            Button(
                onClick = {
                    FirebaseManager.cambiarEstadoListo(
                        idSalaInicial,
                        jugadorLocal.id,
                        !(yo?.estaListo ?: false)
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF456B03),
                    contentColor = Color.White,
                    disabledContainerColor = Color(0xFF456B03).copy(alpha = 0.45f),
                    disabledContentColor = Color.White.copy(alpha = 0.75f)
                )
            ) {
                Text(
                    text = textoBotonListo,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }

            Button(
                onClick = onSalirAlLogin,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF123515),
                    contentColor = Color.White
                ),
                shape = MaterialTheme.shapes.medium
            ) {
                Icon(
                    imageVector = Icons.Default.ExitToApp,
                    contentDescription = "Salir",
                    tint = Color.White
                )

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = "SALIR",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }

            // BOTÓN COMENZAR (Solo para el Host)
            if (yo?.esAnfitrion == true) {
                val todosListos = datosSala?.jugadores?.values?.all { it.estaListo } ?: false

                Button(
                    onClick = {
                        val jugadoresConectados = datosSala?.jugadores?.values?.toList() ?: emptyList()
                        FirebaseManager.iniciarPartida(idSalaInicial, jugadoresConectados)
                    },
                    enabled = todosListos && (datosSala?.jugadores?.size ?: 0) >= 2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color.Black
                    )
                ) {
                    Text(
                        text = "COMENZAR JUEGO",
                        color = Color.Black,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}


@Composable
private fun BannerNombreSala(
    nombreSala: String,
    modifier: Modifier = Modifier
) {
    val nombreMostrado = nombreSala.ifBlank { "MESA" }
    val fontSizeSala = tamanoFuenteSala(nombreMostrado)

    Box(
        modifier = modifier
            .height(190.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Image(
            painter = painterResource(id = R.drawable.lobby_sala_frame),
            contentDescription = "Marco del nombre de la sala",
            modifier = Modifier
                .fillMaxWidth()
                .height(178.dp),
            contentScale = ContentScale.Fit
        )

        // Capa inferior para profundidad muy sutil
        Text(
            text = nombreMostrado,
            modifier = Modifier
                .fillMaxWidth(0.68f)
                .align(Alignment.TopCenter)
                .offset(y = 104.dp),
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            color = Color(0xFF0E2410).copy(alpha = 0.88f),
            fontSize = fontSizeSala,
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.35.sp,
            style = TextStyle(
                shadow = Shadow(
                    color = Color.White.copy(alpha = 0.4f),
                    offset = Offset(0.8f, 0.5f),
                    blurRadius = 0.8f
                )
            )
        )

        // Capa principal del texto
        Text(
            text = nombreMostrado,
            modifier = Modifier
                .fillMaxWidth(0.68f)
                .align(Alignment.TopCenter)
                .offset(y = 103.dp),
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            color = Color(0xFF093D14),
            fontSize = fontSizeSala,
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.35.sp,
            style = TextStyle(
                shadow = Shadow(
                    color = Color.White.copy(alpha = 0.28f),
                    offset = Offset(-0.35f, -0.35f),
                    blurRadius = 0.5f
                )
            )
        )
    }
}

private fun tamanoFuenteSala(nombreSala: String) = when {
    nombreSala.length <= 10 -> 28.sp
    nombreSala.length <= 16 -> 25.sp
    nombreSala.length <= 22 -> 22.sp
    nombreSala.length <= 28 -> 18.sp
    else -> 15.sp
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

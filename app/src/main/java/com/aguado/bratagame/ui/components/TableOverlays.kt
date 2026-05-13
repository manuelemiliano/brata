package com.aguado.bratagame.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aguado.bratagame.ui.theme.CasinoGold
import kotlinx.coroutines.delay

// ─────────────────────────────────────────────
// CONTADOR REGRESIVO DE 15 SEGUNDOS
//
// Se muestra al inicio de la partida para que
// todos los jugadores memoricen sus cartas 2 y 3.
// Todos los clientes lo ven simultáneamente porque
// se calcula desde el mismo timestampInicioContador
// almacenado en Firebase.
//
// Parámetros:
//   timestampInicio → Unix ms guardado en Sala
//   onTiempoAgotado → callback cuando llega a 0
// ─────────────────────────────────────────────

@Composable
fun ContadorMemorizacion(
    timestampInicio: Long,
    duracionSegundos: Int = 15,
    onTiempoAgotado: () -> Unit
) {
    var segundosRestantes by remember { mutableStateOf(duracionSegundos) }
    var visible by remember { mutableStateOf(true) }

    LaunchedEffect(timestampInicio) {
        while (true) {
            val ahora = System.currentTimeMillis()
            val transcurridos = ((ahora - timestampInicio) / 1000).toInt()
            val restantes = (duracionSegundos - transcurridos).coerceAtLeast(0)
            segundosRestantes = restantes

            if (restantes == 0) {
                delay(500) // pequeña pausa antes de desaparecer
                visible = false
                onTiempoAgotado()
                break
            }
            delay(200) // actualizar cada 200ms para mayor precisión
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + scaleIn(),
        exit = fadeOut() + scaleOut()
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            // Fondo semitransparente
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f))
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "¡MEMORIZA TUS CARTAS!",
                    color = CasinoGold,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Círculo con el número
                val colorCirculo = when {
                    segundosRestantes <= 5 -> Color(0xFFB71C1C) // rojo urgente
                    segundosRestantes <= 10 -> Color(0xFFFF8F00) // naranja
                    else -> Color(0xFF1B5E20)                   // verde casino
                }

                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .background(colorCirculo, CircleShape)
                        .border(3.dp, CasinoGold, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "$segundosRestantes",
                        color = Color.White,
                        fontSize = 42.sp,
                        fontWeight = FontWeight.Black
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Las cartas del centro se voltearán",
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

// ─────────────────────────────────────────────
// INDICADOR DE TURNO
// Banner superior que muestra a quién le toca.
// ─────────────────────────────────────────────

@Composable
fun IndicadorTurno(
    nombreJugador: String,
    esMiTurno: Boolean,
    brataActivada: Boolean,
    modifier: Modifier = Modifier
) {
    val texto = when {
        brataActivada && esMiTurno -> "⚠️ ¡ÚLTIMA RONDA! Es tu turno"
        brataActivada -> "⚠️ ÚLTIMA RONDA — Turno de $nombreJugador"
        esMiTurno -> "✦ Es tu turno"
        else -> "Turno de $nombreJugador"
    }

    val colorFondo = when {
        brataActivada -> Color(0xFFB71C1C)
        esMiTurno -> Color(0xFF1B5E20)
        else -> Color.Black.copy(alpha = 0.65f)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(colorFondo, RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = texto,
            color = if (esMiTurno) CasinoGold else Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
    }
}

// ─────────────────────────────────────────────
// ALERTA DE ADELANTADO
// Aparece sobre la mesa cuando alguien se adelanta
// durante un poder espía activo.
// ─────────────────────────────────────────────

@Composable
fun AlertaAdelantado(
    nombreAdelantado: String,
    onRobarJuego: () -> Unit,
    onPerdonar: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // Fondo oscuro
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.70f))
        )

        Card(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .wrapContentHeight(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A))
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "⚡ ¡ADELANTADO!",
                    color = Color(0xFFFF8F00),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "$nombreAdelantado se adelantó y descartó mientras usabas tu poder.",
                    color = Color.White,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Botón: robar juego del adelantado
                Button(
                    onClick = onRobarJuego,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB71C1C))
                ) {
                    Text(
                        "ROBAR SU JUEGO",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Botón: perdonar
                OutlinedButton(
                    onClick = onPerdonar,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) {
                    Text("PERDONAR Y CONTINUAR")
                }
            }
        }
    }
}

// ─────────────────────────────────────────────
// SELECTOR DE VALOR DEL COMODÍN
// Modal con scroll para elegir el valor que tomará el comodín.
// ─────────────────────────────────────────────

@Composable
fun SelectorComodin(
    onSeleccionar: (valor: String, palo: String) -> Unit,
    onDismiss: () -> Unit
) {
    val palos = listOf("corazones", "picas", "diamantes", "treboles")
    val valores = listOf("A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K")

    var valorSeleccionado by remember { mutableStateOf<String?>(null) }
    var paloSeleccionado by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1A1A1A),
        title = {
            Text(
                "★ Define el valor del COMODÍN",
                color = Color(0xFF6200EE),
                fontWeight = FontWeight.ExtraBold
            )
        },
        text = {
            Column {
                Text("Valor:", color = Color.White, fontSize = 13.sp)
                Spacer(modifier = Modifier.height(8.dp))

                // Grid de valores
                valores.chunked(4).forEach { fila ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        fila.forEach { valor ->
                            val seleccionado = valorSeleccionado == valor
                            OutlinedButton(
                                onClick = { valorSeleccionado = valor },
                                modifier = Modifier.size(56.dp, 40.dp),
                                contentPadding = PaddingValues(0.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = if (seleccionado) CasinoGold else Color.Transparent,
                                    contentColor = if (seleccionado) Color.Black else Color.White
                                )
                            ) {
                                Text(valor, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text("Palo:", color = Color.White, fontSize = 13.sp)
                Spacer(modifier = Modifier.height(8.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    palos.forEach { palo ->
                        val seleccionado = paloSeleccionado == palo
                        val (simbolo, color) = when (palo) {
                            "corazones" -> "♥" to Color.Red
                            "picas" -> "♠" to Color.White
                            "diamantes" -> "♦" to Color.Red
                            "treboles" -> "♣" to Color.White
                            else -> "" to Color.White
                        }
                        OutlinedButton(
                            onClick = { paloSeleccionado = palo },
                            modifier = Modifier.size(56.dp, 40.dp),
                            contentPadding = PaddingValues(0.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (seleccionado) color.copy(alpha = 0.2f)
                                else Color.Transparent,
                                contentColor = color
                            )
                        ) {
                            Text(simbolo, fontSize = 20.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val v = valorSeleccionado
                    val p = paloSeleccionado
                    if (v != null && p != null) onSeleccionar(v, p)
                },
                enabled = valorSeleccionado != null && paloSeleccionado != null,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6200EE))
            ) {
                Text("CONFIRMAR", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCELAR", color = Color.White)
            }
        }
    )
}
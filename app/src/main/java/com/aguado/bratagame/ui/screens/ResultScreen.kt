package com.aguado.bratagame.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.aguado.bratagame.Carta
import com.aguado.bratagame.Jugador
import com.aguado.bratagame.Sala
import com.aguado.bratagame.esSlotVacio
import com.aguado.bratagame.game.HandEvaluator
import com.aguado.bratagame.game.HandEvaluator.ReglaEspecial
import com.aguado.bratagame.ui.components.CartaVisual
import com.aguado.bratagame.ui.components.mappingPalo
import com.aguado.bratagame.ui.theme.CasinoGold
import com.aguado.bratagame.ui.theme.DarkCasinoGreen

@Composable
fun ResultScreen(
    sala: Sala,
    jugadorLocalId: String,
    onRevancha: () -> Unit,
    onIrAlLobby: () -> Unit,
    onIrAlInicio: () -> Unit
) {
    val resultado = remember(sala) { HandEvaluator.evaluarRonda(sala) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkCasinoGreen)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            EncabezadoResultado(
                resultado = resultado,
                jugadorLocalId = jugadorLocalId
            )

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                items(resultado.jugadores) { resultadoJugador ->
                    FilaJugadorResultado(
                        resultadoJugador = resultadoJugador,
                        esLocal = resultadoJugador.jugador.id == jugadorLocalId
                    )
                }
            }

            BotonesResultado(
                onRevancha = onRevancha,
                onIrAlLobby = onIrAlLobby,
                onIrAlInicio = onIrAlInicio
            )
        }
    }
}

// ─────────────────────────────────────────────
// ENCABEZADO
// ─────────────────────────────────────────────

@Composable
private fun EncabezadoResultado(
    resultado: HandEvaluator.ResultadoRonda,
    jugadorLocalId: String
) {
    val (titulo, subtitulo, colorFondo) = when {
        resultado.hayEmpate -> {
            val nombres = resultado.empatados.joinToString(" y ") { it.jugador.nombre }
            Triple(
                "¡EMPATE!",
                "$nombres · ${resultado.empatados.first().puntuacion} pts",
                Color(0xFF1565C0)
            )
        }
        resultado.ganador.jugador.id == jugadorLocalId -> Triple(
            "🏆 ¡GANASTE!",
            "${resultado.ganador.jugador.nombre} · ${resultado.ganador.puntuacion} pts",
            Color(0xFF1B5E20)
        )
        else -> Triple(
            "Ganó ${resultado.ganador.jugador.nombre}",
            "${resultado.ganador.puntuacion} pts",
            Color(0xFF4A0000)
        )
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(colorFondo)
            .padding(vertical = 20.dp, horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = titulo,
                color = CasinoGold,
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitulo,
                color = Color.White,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

// ─────────────────────────────────────────────
// FILA DE JUGADOR
// Cartas abiertas en horizontal + puntuación por carta + total
// Orden visual: [pos2][pos3][pos0][pos1]
// (las cartas alejadas primero, las próximas al final)
// ─────────────────────────────────────────────

@Composable
private fun FilaJugadorResultado(
    resultadoJugador: HandEvaluator.ResultadoJugador,
    esLocal: Boolean
) {
    val jugador = resultadoJugador.jugador

    val colorCard = when {
        resultadoJugador.esGanador -> Color(0xFF1B5E20)
        esLocal -> Color(0xFF1A1A2E)
        else -> Color(0xFF1A1A1A)
    }
    val colorBorde = when {
        resultadoJugador.esGanador -> CasinoGold
        else -> Color.White.copy(alpha = 0.15f)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = colorCard),
        border = androidx.compose.foundation.BorderStroke(
            width = if (resultadoJugador.esGanador) 2.dp else 1.dp,
            color = colorBorde
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            // ── Nombre ────────────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (resultadoJugador.esGanador) Text("🏆 ", fontSize = 16.sp)
                Text(
                    text = if (esLocal) "${jugador.nombre} (Tú)" else jugador.nombre,
                    color = if (resultadoJugador.esGanador) CasinoGold else Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // ── Cartas + puntuación individual ────
            // Orden: pos2, pos3 (alejadas), pos0, pos1 (próximas)
            val cartasOrdenadas = listOf(
                jugador.cartas.getOrNull(2),
                jugador.cartas.getOrNull(3),
                jugador.cartas.getOrNull(0),
                jugador.cartas.getOrNull(1)
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
                modifier = Modifier.fillMaxWidth(),
            ) {
                cartasOrdenadas.forEach { carta ->
                    val esHueco = carta == null || carta.esSlotVacio()
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f)
                    ) {
                        CartaVisual(
                            abierta = !esHueco,
                            valor = carta?.valor ?: "",
                            palo = mappingPalo(carta?.palo),
                            modifier = Modifier.size(56.dp, 78.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = when {
                                esHueco -> "—"
                                else -> "${HandEvaluator.valorPuntuacion(carta!!)}"
                            },
                            color = Color.White.copy(alpha = 0.75f),
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // ── Regla especial (si aplica) ────────
            if (resultadoJugador.reglaAplicada != ReglaEspecial.NINGUNA) {
                Text(
                    text = HandEvaluator.etiquetaRegla(resultadoJugador.reglaAplicada),
                    color = Color(0xFFFF8F00),
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.15f), thickness = 1.dp)
            Spacer(modifier = Modifier.height(8.dp))

            // ── Total ─────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "TOTAL",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${resultadoJugador.puntuacion} pts",
                    color = if (resultadoJugador.esGanador) CasinoGold else Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }
    }
}

// ─────────────────────────────────────────────
// BOTONES DE ACCIÓN
// ─────────────────────────────────────────────

@Composable
private fun BotonesResultado(
    onRevancha: () -> Unit,
    onIrAlLobby: () -> Unit,
    onIrAlInicio: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.4f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = onRevancha,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            colors = ButtonDefaults.buttonColors(containerColor = CasinoGold),
            shape = RoundedCornerShape(10.dp)
        ) {
            Text(
                "⚔ REVANCHA",
                color = Color.Black,
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onIrAlLobby,
                modifier = Modifier.weight(1f).height(46.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("LOBBY", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }

            OutlinedButton(
                onClick = onIrAlInicio,
                modifier = Modifier.weight(1f).height(46.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("INICIO", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ─────────────────────────────────────────────
// PREVIEW
// ─────────────────────────────────────────────

@androidx.compose.ui.tooling.preview.Preview(
    showBackground = true,
    widthDp = 360,
    heightDp = 760,
    name = "Result Screen"
)
@Composable
fun ResultScreenPreview() {
    val cartasNormales = listOf(
        Carta(valor = "7", palo = "corazones"),
        Carta(valor = "K", palo = "picas"),
        Carta(valor = "A", palo = "treboles"),
        Carta(valor = "4", palo = "diamantes")
    )
    val cartasGanador = listOf(
        Carta(valor = "2", palo = "corazones"),
        Carta(valor = "2", palo = "picas"),
        Carta(valor = "2", palo = "treboles"),
        Carta(valor = "2", palo = "diamantes")
    )
    val yo = Jugador(id = "1", nombre = "Manolo", cartas = cartasNormales)
    val op1 = Jugador(id = "2", nombre = "Ramon", cartas = cartasGanador)
    val op2 = Jugador(id = "3", nombre = "Elena", cartas = cartasNormales)

    ResultScreen(
        sala = Sala(
            id = "test",
            jugadores = mapOf("1" to yo, "2" to op1, "3" to op2),
            turnoActualId = "1"
        ),
        jugadorLocalId = "1",
        onRevancha = {},
        onIrAlLobby = {},
        onIrAlInicio = {}
    )
}

@androidx.compose.ui.tooling.preview.Preview(
    showBackground = true,
    widthDp = 360,
    heightDp = 760,
    name = "Result Screen - Ganas"
)
@Composable
fun ResultScreenGanasPreview() {
    val cartasGanador = listOf(
        Carta(valor = "2", palo = "corazones"),
        Carta(valor = "2", palo = "picas"),
        Carta(valor = "2", palo = "treboles"),
        Carta(valor = "2", palo = "diamantes")
    )
    val cartasNormales = listOf(
        Carta(valor = "7", palo = "corazones"),
        Carta(valor = "K", palo = "picas"),
        Carta(valor = "A", palo = "treboles"),
        Carta(valor = "4", palo = "diamantes")
    )
    val yo = Jugador(id = "1", nombre = "Manolo", cartas = cartasGanador)
    val op1 = Jugador(id = "2", nombre = "Ramon", cartas = cartasNormales)
    val op2 = Jugador(id = "3", nombre = "Elena", cartas = cartasNormales)

    ResultScreen(
        sala = Sala(
            id = "test",
            jugadores = mapOf("1" to yo, "2" to op1, "3" to op2),
            turnoActualId = "1"
        ),
        jugadorLocalId = "1",
        onRevancha = {},
        onIrAlLobby = {},
        onIrAlInicio = {}
    )
}

@androidx.compose.ui.tooling.preview.Preview(
    showBackground = true,
    widthDp = 360,
    heightDp = 760,
    name = "Result Screen - Empate"
)
@Composable
fun ResultScreenEmpatePreview() {
    val cartasEmpate = listOf(
        Carta(valor = "2", palo = "corazones"),
        Carta(valor = "3", palo = "picas"),
        Carta(valor = "4", palo = "treboles"),
        Carta(valor = "5", palo = "diamantes")
    )
    val cartasNormales = listOf(
        Carta(valor = "7", palo = "corazones"),
        Carta(valor = "K", palo = "picas"),
        Carta(valor = "A", palo = "treboles"),
        Carta(valor = "4", palo = "diamantes")
    )
    val yo = Jugador(id = "1", nombre = "Manolo", cartas = cartasEmpate)
    val op1 = Jugador(id = "2", nombre = "Ramon", cartas = cartasEmpate)
    val op2 = Jugador(id = "3", nombre = "Elena", cartas = cartasNormales)

    ResultScreen(
        sala = Sala(
            id = "test",
            jugadores = mapOf("1" to yo, "2" to op1, "3" to op2),
            turnoActualId = "1"
        ),
        jugadorLocalId = "1",
        onRevancha = {},
        onIrAlLobby = {},
        onIrAlInicio = {}
    )
}
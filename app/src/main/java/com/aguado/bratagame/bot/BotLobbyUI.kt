package com.aguado.bratagame.bot

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aguado.bratagame.Sala
import com.aguado.bratagame.ui.theme.CasinoGold

// ─────────────────────────────────────────────
// BOT LOBBY UI
//
// Composable que se inserta en LobbyScreen entre la lista
// de jugadores y el botón "ESTOY LISTO".
//
// Solo se renderiza si el jugador local es anfitrión.
//
// Diseño coherente con la paleta existente (CasinoGold + fondo verde casino).
// ─────────────────────────────────────────────

@Composable
fun ConfiguracionBotsCard(
    salaActual: Sala,
    salaId: String,
    soyHost: Boolean,
    onError: (String) -> Unit = {}
) {
    if (!soyHost) return

    val cantidadBots = BotLobbyActions.cantidadBotsEnSala(salaActual)
    val cantidadHumanos = BotLobbyActions.cantidadHumanosEnSala(salaActual)
    val puedeAgregar = BotLobbyActions.puedeAgregarMasBots(salaActual)
    val puedeQuitar = BotLobbyActions.puedeQuitarBots(salaActual)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .background(
                color = Color(0xFF0E2410).copy(alpha = 0.85f),
                shape = RoundedCornerShape(12.dp)
            )
            .border(
                width = 1.dp,
                color = CasinoGold.copy(alpha = 0.6f),
                shape = RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "BOTS",
                    color = CasinoGold,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Black
                )

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = textoEstado(cantidadHumanos, cantidadBots),
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 11.sp
                )
            }

            // Botón menos
            BotonContador(
                etiqueta = "−",
                habilitado = puedeQuitar,
                onClick = {
                    BotLobbyActions.quitarUltimoBot(
                        salaId = salaId,
                        sala = salaActual
                    ) { exito, mensaje ->
                        if (!exito) onError(mensaje)
                    }
                }
            )

            Spacer(modifier = Modifier.width(12.dp))

            Box(
                modifier = Modifier
                    .size(width = 44.dp, height = 36.dp)
                    .background(
                        color = Color.Black.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(8.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "$cantidadBots",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Botón más
            BotonContador(
                etiqueta = "+",
                habilitado = puedeAgregar,
                onClick = {
                    BotLobbyActions.agregarBot(
                        salaId = salaId,
                        sala = salaActual
                    ) { exito, mensaje ->
                        if (!exito) onError(mensaje)
                    }
                }
            )
        }
    }
}

@Composable
private fun BotonContador(
    etiqueta: String,
    habilitado: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = habilitado,
        modifier = Modifier.size(width = 44.dp, height = 36.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = CasinoGold,
            contentColor = Color.Black,
            disabledContainerColor = CasinoGold.copy(alpha = 0.30f),
            disabledContentColor = Color.Black.copy(alpha = 0.40f)
        )
    ) {
        Text(
            text = etiqueta,
            fontSize = 18.sp,
            fontWeight = FontWeight.Black
        )
    }
}

private fun textoEstado(humanos: Int, bots: Int): String {
    val total = humanos + bots
    val sufijoTotal = if (total == 1) "jugador en mesa" else "jugadores en mesa"
    return "$humanos humanos · $bots bots · $total $sufijoTotal"
}

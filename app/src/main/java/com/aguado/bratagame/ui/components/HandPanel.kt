package com.aguado.bratagame.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aguado.bratagame.Carta
import com.aguado.bratagame.TipoPoder
import com.aguado.bratagame.game.AccionMano
import com.aguado.bratagame.game.GameRules
import com.aguado.bratagame.ui.theme.CasinoGold

// ─────────────────────────────────────────────
// HAND PANEL
//
// Panel que aparece desde abajo cuando el jugador
// tiene una carta en mano (robó del pozo o descarte).
//
// Solo el jugador local lo ve.
// Los demás ven la animación CartaEspiandoOverlay si aplica.
//
// Parámetros:
//   cartaEnMano       → la carta recién robada
//   cartasDelJugador  → las 4 cartas en su cuadrado 2x2
//   accionesDisponibles → calculadas por GameRules, no por este componente
//   onAccion          → callback con la acción elegida y posición destino (si aplica)
// ─────────────────────────────────────────────

@Composable
fun HandPanel(
    cartaEnMano: Carta,
    accionesDisponibles: List<AccionMano>,
    onAccion: (accion: AccionMano, posicionDestino: Int?) -> Unit
) {
    AnimatedVisibility(
        visible = true,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it })
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = Color(0xE6102A10),
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
                )
                .padding(16.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {

                val esModoTomar = accionesDisponibles.size == 1 &&
                        accionesDisponibles.first() == AccionMano.TOMAR

                val poder = GameRules.obtenerPoder(cartaEnMano)

                if (!esModoTomar && poder != TipoPoder.NINGUNO) {
                    Text(
                        text = etiquetaPoder(poder),
                        color = CasinoGold,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }

                if (esModoTomar) {
                    Text(
                        text = "Nueva carta de juego",
                        color = CasinoGold,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }

                CartaVisual(
                    abierta = true,
                    valor = cartaEnMano.valor,
                    palo = mappingPalo(cartaEnMano.palo),
                    modifier = Modifier.size(70.dp, 98.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))

                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    accionesDisponibles.forEach { accion ->
                        BotonAccion(
                            accion = accion,
                            onClick = {
                                onAccion(accion, null)
                            }
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────
// BOTÓN DE ACCIÓN INDIVIDUAL
// Colores y etiquetas definidos aquí, lógica en HandPanel/GameActions
// ─────────────────────────────────────────────

@Composable
private fun BotonAccion(accion: AccionMano, onClick: () -> Unit) {
    val (etiqueta, colorFondo, colorTexto) = when (accion) {
        AccionMano.CAMBIAR -> Triple("CAMBIAR", Color(0xFF1565C0), Color.White)
        AccionMano.DESCARTAR -> Triple("DESCARTAR", Color(0xFFB71C1C), Color.White)
        AccionMano.ACTIVAR_PODER -> Triple("ACTIVAR PODER", Color(0xFF6A0DAD), Color.White)
        AccionMano.DESCARTAR_FREE -> Triple("DESCARTE FREE ✦", Color(0xFFFF8F00), Color.Black)
        AccionMano.SELECCIONAR_COMODIN -> Triple("DEFINIR COMODÍN ★", Color(0xFF6200EE), Color.White)
        AccionMano.REGRESAR -> Triple("REGRESAR", Color.DarkGray, Color.White)
        AccionMano.TOMAR -> Triple("TOMAR", Color(0xFF456B03), Color.White)

        AccionMano.ROBAR_DESCARTE -> Triple("ROBAR DESCARTE", Color(0xFF8A6D1D), Color.White)
    }

    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = colorFondo),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
    ) {
        Text(etiqueta, color = colorTexto, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

// ─────────────────────────────────────────────
// CUADRADO 2x2 SELECCIONABLE
// Muestra las 4 cartas del jugador para elegir con cuál intercambiar.
// La carta seleccionada se resalta con un borde dorado.
// ─────────────────────────────────────────────


// ─────────────────────────────────────────────
// HELPERS
// ─────────────────────────────────────────────

private fun etiquetaPoder(poder: TipoPoder): String = when (poder) {
    TipoPoder.ESPIAR -> "✦ PODER: ESPIAR"
    TipoPoder.CAMBIAR_VIENDO -> "✦ PODER: CAMBIAR VIENDO"
    TipoPoder.CAMBIAR_SIN_VER -> "✦ PODER: CAMBIAR SIN VER"
    TipoPoder.DESCARTE_FREE_SELECCION -> ""
    TipoPoder.NINGUNO -> ""
}
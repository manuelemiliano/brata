package com.aguado.bratagame.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import com.aguado.bratagame.CartaEnMesa
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
    cartasDelJugador: List<CartaEnMesa>,
    accionesDisponibles: List<AccionMano>,
    onAccion: (accion: AccionMano, posicionDestino: Int?) -> Unit
) {
    // null = ninguna seleccionada aún, Int = índice 0-3 de la carta elegida para intercambiar
    var cartaSeleccionadaIndex by remember { mutableStateOf<Int?>(null) }

    // Fase del panel: SELECCIONANDO_INTERCAMBIO muestra el cuadrado de cartas propias
    var modoSeleccionIntercambio by remember { mutableStateOf(false) }

    AnimatedVisibility(
        visible = true,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it })
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = Color(0xE6102A10), // verde oscuro semitransparente
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
                )
                .padding(16.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {

                // ── Indicador de poder ─────────────────
                val poder = GameRules.obtenerPoder(cartaEnMano)
                if (poder != TipoPoder.NINGUNO) {
                    Text(
                        text = etiquetaPoder(poder),
                        color = CasinoGold,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }

                // ── Carta en mano (siempre visible, grande) ──
                CartaVisual(
                    abierta = true,
                    valor = cartaEnMano.valor,
                    palo = mappingPalo(cartaEnMano.palo),
                    modifier = Modifier.size(70.dp, 98.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))

                // ── Modo: seleccionar carta para intercambiar ──
                if (modoSeleccionIntercambio) {
                    Text(
                        text = "¿Con cuál carta intercambias?",
                        color = Color.White,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    // Cuadrado 2x2 de cartas propias seleccionables
                    CuadradoCartasSeleccionable(
                        cartas = cartasDelJugador,
                        seleccionada = cartaSeleccionadaIndex,
                        onSeleccionar = { cartaSeleccionadaIndex = it }
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Cancelar selección
                        OutlinedButton(
                            onClick = {
                                modoSeleccionIntercambio = false
                                cartaSeleccionadaIndex = null
                            },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                        ) {
                            Text("CANCELAR")
                        }

                        // Confirmar intercambio
                        Button(
                            onClick = {
                                cartaSeleccionadaIndex?.let { pos ->
                                    onAccion(AccionMano.CAMBIAR, pos)
                                }
                            },
                            enabled = cartaSeleccionadaIndex != null,
                            colors = ButtonDefaults.buttonColors(containerColor = CasinoGold)
                        ) {
                            Text("CONFIRMAR", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }

                } else {
                    // ── Botones de acción principales ─────────
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        accionesDisponibles.forEach { accion ->
                            BotonAccion(
                                accion = accion,
                                onClick = {
                                    when (accion) {
                                        AccionMano.CAMBIAR -> {
                                            // Entrar al modo de selección de carta
                                            modoSeleccionIntercambio = true
                                        }
                                        else -> onAccion(accion, null)
                                    }
                                }
                            )
                        }
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

@Composable
private fun CuadradoCartasSeleccionable(
    cartas: List<CartaEnMesa>,
    seleccionada: Int?,
    onSeleccionar: (Int) -> Unit
) {
    // Respeta el layout 2x2:
    //   [2][3]  ← posiciones alejadas (fila superior)
    //   [0][1]  ← posiciones próximas (fila inferior)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Fila superior: posiciones 2 y 3
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(2, 3).forEach { pos ->
                val cartaEnMesa = cartas.find { it.posicion == pos }
                CartaSeleccionable(
                    cartaEnMesa = cartaEnMesa,
                    estaSeleccionada = seleccionada == pos,
                    onClick = { onSeleccionar(pos) }
                )
            }
        }
        // Fila inferior: posiciones 0 y 1
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(0, 1).forEach { pos ->
                val cartaEnMesa = cartas.find { it.posicion == pos }
                CartaSeleccionable(
                    cartaEnMesa = cartaEnMesa,
                    estaSeleccionada = seleccionada == pos,
                    onClick = { onSeleccionar(pos) }
                )
            }
        }
    }
}

@Composable
private fun CartaSeleccionable(
    cartaEnMesa: CartaEnMesa?,
    estaSeleccionada: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clickable { onClick() }
            .border(
                width = if (estaSeleccionada) 2.dp else 0.dp,
                color = if (estaSeleccionada) CasinoGold else Color.Transparent,
                shape = RoundedCornerShape(4.dp)
            )
    ) {
        // Las cartas propias en mano siempre se muestran abiertas al jugador local
        CartaVisual(
            abierta = true,
            valor = cartaEnMesa?.carta?.valor ?: "",
            palo = mappingPalo(cartaEnMesa?.carta?.palo)
        )
    }
}

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
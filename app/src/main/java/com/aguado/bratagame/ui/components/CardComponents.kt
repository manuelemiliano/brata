package com.aguado.bratagame.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aguado.bratagame.R

enum class Palo(val simbolo: String, val color: Color) {
    CORAZONES("♥", Color.Red),
    PICAS("♠", Color.Black),
    DIAMANTES("♦", Color.Red),
    TREBOLES("♣", Color.Black),
    COMODIN("★", Color(0xFF6200EE)),
    NINGUNO("", Color.Black)
}

fun mappingPalo(paloString: String?): Palo {
    val cleanPalo = paloString?.trim()?.lowercase() ?: ""
    return when (cleanPalo) {
        "corazones" -> Palo.CORAZONES
        "picas"     -> Palo.PICAS
        "diamantes" -> Palo.DIAMANTES
        "treboles"  -> Palo.TREBOLES
        "comodin"   -> Palo.COMODIN
        else        -> Palo.NINGUNO
    }
}

@Composable
fun CartaVisual(
    abierta: Boolean,
    valor: String = "",
    palo: Palo = Palo.NINGUNO,
    modifier: Modifier = Modifier
) {
    val esJoker = palo == Palo.COMODIN

    Surface(
        modifier = modifier
            .size(50.dp, 70.dp)
            .padding(2.dp),
        shape = RoundedCornerShape(4.dp),
        color = if (abierta) Color.White else Color.Transparent,
        shadowElevation = 4.dp
    ) {
        if (!abierta) {
            Image(
                painter = painterResource(id = R.drawable.card_bg),
                contentDescription = "Dorso",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds
            )
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                if (esJoker) {
                    Image(
                        painter = painterResource(id = R.drawable.joker),
                        contentDescription = "Joker",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else if (valor.isNotEmpty()) {
                    Text(
                        text = valor,
                        color = palo.color,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(top = 2.dp, start = 4.dp)
                    )
                    Text(
                        text = palo.simbolo,
                        color = palo.color,
                        fontSize = 24.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }
        }
    }
}

@Composable
fun CuadradoCartas(
    esLocal: Boolean,
    cartas: List<Pair<String, Palo>>
) {
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        // FILA SUPERIOR: Ahora son las visibles para el jugador local
        Row {
            val c1 = cartas.getOrNull(0) ?: ("" to Palo.NINGUNO)
            val c2 = cartas.getOrNull(1) ?: ("" to Palo.NINGUNO)
            CartaVisual(abierta = esLocal, valor = c1.first, palo = c1.second)
            CartaVisual(abierta = esLocal, valor = c2.first, palo = c2.second)
        }
        // FILA INFERIOR: Ahora permanecen cerradas
        Row {
            val c3 = cartas.getOrNull(2) ?: ("" to Palo.NINGUNO)
            val c4 = cartas.getOrNull(3) ?: ("" to Palo.NINGUNO)
            CartaVisual(abierta = false, valor = c3.first, palo = c3.second)
            CartaVisual(abierta = false, valor = c4.first, palo = c4.second)
        }
    }
}

fun obtenerConfiguracionMazos(totalOponentes: Int): Triple<Alignment, Boolean, Float> {
    return when (totalOponentes) {
        1 -> Triple(Alignment.Center, true, 0f)
        2 -> Triple(BiasAlignment(0f, 0.2f), true, 0f)
        3 -> Triple(Alignment.Center, false, 0f)
        4 -> Triple(BiasAlignment(0f, -0.15f), true, 0f)
        5 -> Triple(Alignment.Center, false, 0f)
        else -> Triple(Alignment.Center, false, 0f)
    }
}

@Composable
fun MazosCentrales(esHorizontal: Boolean, cartaSuperiorDescarte: Pair<String, Palo>?) {
    val layoutModifier = Modifier.wrapContentSize()
    val contenido = @Composable {
        CartaVisual(abierta = false)
        if (cartaSuperiorDescarte == null) {
            Box(
                modifier = Modifier
                    .size(50.dp, 70.dp)
                    .background(Color.Black.copy(0.1f), RoundedCornerShape(4.dp))
                    .border(2.dp, Color.White.copy(0.4f), RoundedCornerShape(4.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("BRATA", color = Color.White.copy(0.3f), fontSize = 8.sp, fontWeight = FontWeight.Bold)
            }
        } else {
            CartaVisual(abierta = true, valor = cartaSuperiorDescarte.first, palo = cartaSuperiorDescarte.second)
        }
    }

    if (esHorizontal) {
        Row(layoutModifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) { contenido() }
    } else {
        Column(layoutModifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) { contenido() }
    }
}
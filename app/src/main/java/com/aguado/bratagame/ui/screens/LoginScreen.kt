package com.aguado.bratagame.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aguado.bratagame.FirebaseManager
import com.aguado.bratagame.Jugador
import com.aguado.bratagame.Sala
import com.aguado.bratagame.ui.theme.CasinoGold
import com.aguado.bratagame.R
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.sp

@Composable
fun LoginScreen(onEntrarASala: (String, String) -> Unit) {
    var nombreUsuario by remember { mutableStateOf("") }
    var mostrarModalCrear by remember { mutableStateOf(false) }
    var mostrarAyudaReglas by remember { mutableStateOf(false) }
    var salasDisponibles by remember { mutableStateOf<List<Sala>>(emptyList()) }

    LaunchedEffect(Unit) {
        FirebaseManager.obtenerSalasActivas { salasDisponibles = it }
    }

    Box(modifier = Modifier.fillMaxSize()) {

        Image(
            painter = painterResource(id = R.drawable.login_lobby_bg),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        // Capa oscura opcional para que los textos se lean mejor
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.35f))
        )

        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp, bottom = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.brata_casino_logo),
                    contentDescription = "Brata Casino",
                    modifier = Modifier
                        .fillMaxWidth(2f)
                        .height(130.dp),
                    contentScale = ContentScale.Fit
                )
            }

            OutlinedTextField(
                value = nombreUsuario,
                onValueChange = { nombreUsuario = it },
                label = { Text("Tu Apodo", color = Color.White) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = CasinoGold
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = { mostrarModalCrear = true },
                // VALIDACIÓN: Solo se activa si el nombre no está vacío
                enabled = nombreUsuario.trim().isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF456B03),
                    contentColor = Color.White,
                    disabledContainerColor = Color(0xFF456B03).copy(alpha = 0.45f),
                    disabledContentColor = Color.White.copy(alpha = 0.75f)
                )
            ) {
                Text(
                    text = "CREAR NUEVA MESA",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }

            Text("Mesas Abiertas:", color = Color.White, modifier = Modifier.padding(vertical = 12.dp))

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(salasDisponibles) { sala ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.1f))
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(sala.nombreSala, color = Color.White, fontWeight = FontWeight.Bold)
                                Text("${sala.jugadores.size}/6 Jugadores", color = CasinoGold, style = MaterialTheme.typography.bodySmall)
                            }

                            Button(
                                onClick = { onEntrarASala(nombreUsuario, sala.id) },
                                // VALIDACIÓN: El invitado también debe tener nombre para unirse
                                enabled = nombreUsuario.trim().isNotBlank()
                            ) {
                                Text("UNIRSE")
                            }
                        }
                    }
                }
            }
        }
        FloatingActionButton(
            onClick = { mostrarAyudaReglas = true },
            containerColor = Color(0xFF456B03),
            contentColor = Color.White,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
        ) {
            Text(
                text = "?",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Black
            )
        }
    }

    if (mostrarModalCrear) {
        var nombreMesa by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { mostrarModalCrear = false },
            title = { Text("Nombre de la Mesa") },
            text = {
                OutlinedTextField(
                    value = nombreMesa,
                    onValueChange = { nombreMesa = it },
                    placeholder = { Text("Ej: Mesa de Ale") }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onEntrarASala(nombreUsuario, "CREAR_SALA:$nombreMesa")
                        mostrarModalCrear = false
                    },
                    enabled = nombreMesa.isNotBlank()
                ) { Text("CONTINUAR") }
            }
        )
    }
    if (mostrarAyudaReglas) {
        ModalReglasBrata(
            onDismiss = { mostrarAyudaReglas = false }
        )
    }
}

@Composable
private fun ModalReglasBrata(
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1A1A1A),
        titleContentColor = CasinoGold,
        textContentColor = Color.White,
        title = {
            Text(
                text = "¿Cómo se juega Brata?",
                color = CasinoGold,
                fontWeight = FontWeight.ExtraBold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                SeccionReglasBrata(
                    titulo = "1. Objetivo",
                    contenido = """
                        El objetivo de Brata es terminar la ronda con la menor cantidad de puntos posible.

                        Cada jugador tiene 4 cartas ocultas en su zona de juego. Durante la partida debes recordar tus cartas, cambiar las que te perjudiquen, aprovechar poderes y descartar cuando tengas oportunidad.

                        Gana quien termine con la puntuación más baja al final de la ronda.
                    """.trimIndent()
                )

                SeccionReglasBrata(
                    titulo = "2. Reparto",
                    contenido = """
                        Pueden jugar de 2 a 6 jugadores.

                        Cada jugador recibe 4 cartas boca abajo, acomodadas en un cuadro de 2 por 2.

                        Con hasta 4 jugadores se usa una baraja. Con 5 o 6 jugadores se usan dos barajas. Cada baraja incluye sus comodines.

                        Las cartas que no se reparten forman el pozo de robo. Al inicio no hay cartas en el pozo de descarte.
                    """.trimIndent()
                )

                SeccionReglasBrata(
                    titulo = "3. Inicio del juego",
                    contenido = """
                        Al iniciar la partida, cada jugador puede memorizar únicamente sus 2 cartas más alejadas.

                        Después de ese tiempo inicial, las cartas vuelven a quedar ocultas.

                        El primer turno corresponde al anfitrión de la mesa y después el turno avanza en orden entre los jugadores.
                    """.trimIndent()
                )

                SeccionReglasBrata(
                    titulo = "4. Desarrollo",
                    contenido = """
                        En tu turno puedes robar una carta del pozo. En algunos casos también puedes tomar la carta superior del descarte, siempre que la regla del juego lo permita.

                        Cuando tienes una carta en mano, puedes hacer una de estas acciones:

                        • Descartarla directamente.
                        • Cambiarla por una de tus cartas ocultas.
                        • Activar su poder, si la carta tiene poder.
                        • Definir el valor del comodín, si robaste un Joker.

                        Poderes principales:

                        • 5, 6, 7, 8, 9 y 10: Espiar. Puedes mirar una carta del juego.
                        • A: Cambiar viendo. Puedes ver una carta y decidir si la cambias por otra.
                        • J y Q: Cambiar sin ver. Puedes intercambiar dos cartas sin revelar su valor.
                        • Joker: eliges el valor que representará al jugarlo.

                        También existe el descarte rápido: si tienes en tu juego una carta con el mismo valor que la cima del descarte, puedes descartarla. Si te equivocas, recibes una carta de castigo.

                        Cuando los descartes rápidos siguen el orden de turno, se considera descarte encadenado y esos turnos se consumen. Si un jugador rompe la cadena, los siguientes conservan su turno normal.
                    """.trimIndent()
                )

                SeccionReglasBrata(
                    titulo = "5. Puntuación",
                    contenido = """
                        Al final de la ronda se revelan las cartas de cada jugador y se suman sus puntos.

                        Valores principales:

                        • A vale 20 puntos.
                        • 2 vale 0 puntos.
                        • 3 al 10 valen su número.
                        • J vale 11 puntos.
                        • Q vale 12 puntos.
                        • K negro vale 13 puntos.
                        • K rojo vale 1 punto.
                        • Joker sin definir vale 20 puntos.

                        Reglas especiales:

                        • Cuatro cartas del mismo palo valen 0 puntos.
                        • Cuatro cartas del mismo número valen 0 puntos.
                        • Dos reyes rojos juntos no cuentan puntos entre ellos.

                        La ronda termina cuando un jugador canta Brata. Después de eso, los demás jugadores tienen una última oportunidad antes de contar puntos.
                    """.trimIndent()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = "ENTENDIDO",
                    color = CasinoGold,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    )
}

@Composable
private fun SeccionReglasBrata(
    titulo: String,
    contenido: String
) {
    Text(
        text = titulo,
        color = CasinoGold,
        fontSize = 16.sp,
        fontWeight = FontWeight.ExtraBold,
        modifier = Modifier.padding(top = 12.dp, bottom = 6.dp)
    )

    Text(
        text = contenido,
        color = Color.White,
        fontSize = 13.sp,
        lineHeight = 18.sp
    )
}

// --- VISTA PREVIA ---
@Preview(showBackground = true)
@Composable
fun LoginPreview() {
    LoginScreen(onEntrarASala = { _, _ -> })
}
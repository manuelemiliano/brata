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
import com.aguado.bratagame.ui.theme.DarkCasinoGreen
import com.aguado.bratagame.ui.theme.CasinoGold
import com.aguado.bratagame.R

@Composable
fun LoginScreen(onEntrarASala: (String, String) -> Unit) {
    var nombreUsuario by remember { mutableStateOf("") }
    var mostrarModalCrear by remember { mutableStateOf(false) }
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
}

// --- VISTA PREVIA ---
@Preview(showBackground = true)
@Composable
fun LoginPreview() {
    LoginScreen(onEntrarASala = { _, _ -> })
}
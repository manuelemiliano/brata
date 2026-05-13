package com.aguado.bratagame.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aguado.bratagame.Carta
import com.aguado.bratagame.CartaEnMesa
import com.aguado.bratagame.FirebaseManager
import com.aguado.bratagame.Jugador
import com.aguado.bratagame.R
import com.aguado.bratagame.Sala
import com.aguado.bratagame.TipoPoder
import com.aguado.bratagame.game.AccionMano
import com.aguado.bratagame.game.CardPowerResolver
import com.aguado.bratagame.game.GameActions
import com.aguado.bratagame.game.GameRules
import com.aguado.bratagame.game.TurnManager
import com.aguado.bratagame.ui.components.*
import com.aguado.bratagame.ui.theme.CasinoGold

@Composable
fun GameTableScreen(
    jugadorLocal: Jugador,
    idSala: String,
    onSalir: () -> Unit,
    salaPreview: Sala? = null
) {
    var datosSala by remember { mutableStateOf(salaPreview) }
    val isPreview = LocalInspectionMode.current
    var mostrarSelectorComodin by remember { mutableStateOf(false) }
    var adelantadoId by remember { mutableStateOf<String?>(null) }
    var seleccionSinVer by remember { mutableStateOf<List<CartaEnMesa>>(emptyList()) }

    // Estado para el selector de carta propia durante descarte del espiado
    var mostrarSelectorCartaPropia by remember { mutableStateOf(false) }
    var cartaEspiadaParaDescartar by remember { mutableStateOf<Carta?>(null) }
    var propietarioEspiadoId by remember { mutableStateOf("") }

    DisposableEffect(idSala) {
        if (isPreview || salaPreview != null) {
            onDispose { }
        } else {
            val listener = FirebaseManager.observarSala(idSala) { sala ->
                datosSala = sala
            }
            onDispose { FirebaseManager.dejarDeObservarSala(idSala, listener) }
        }
    }

    val salaActual = datosSala ?: return

    val yo = salaActual.jugadores[jugadorLocal.id] ?: jugadorLocal
    val esObservador = !salaActual.jugadores.containsKey(jugadorLocal.id)
    val ordenJugadores = TurnManager.ordenDesdeJugadorLocal(jugadorLocal.id, salaActual)
    val oponentes = ordenJugadores.drop(1).mapNotNull { id -> salaActual.jugadores[id] }
    val estadoTurno = TurnManager.calcularEstadoTurno(jugadorLocal.id, salaActual)
    val estadoPoder = CardPowerResolver.calcularEstadoPoder(jugadorLocal.id, salaActual)
    val cimaDscarte = salaActual.mazoDescarte.lastOrNull()
    val cimaPar = cimaDscarte?.let { it.valor to mappingPalo(it.palo) }
    val mostrarContador = salaActual.timestampInicioContador > 0L

    // Carta espiada actual (si la hay)
    val cartaEspiada = if (estadoPoder.estaEspiando) {
        salaActual.jugadores.values
            .flatMap { it.cartas }
            .firstOrNull { it.id == estadoPoder.cartaEspiandoId }
    } else null

    val propietarioCartaEspiada = if (estadoPoder.estaEspiando) {
        salaActual.jugadores.entries
            .firstOrNull { entry -> entry.value.cartas.any { it.id == estadoPoder.cartaEspiandoId } }
            ?.key ?: ""
    } else ""

    // Determinar si el jugador local puede descartar la carta espiada
    // (solo si la carta espiada tiene el mismo valor que la carta que activó el poder)
    val puedeDescartarEspiada = estadoPoder.esMiPoder &&
            estadoPoder.estaEspiando &&
            cartaEspiada != null &&
            cartaEspiada.valor == salaActual.cartaPoderActiva?.valorCartaActivadora

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0D3311))) {

        Image(
            painter = painterResource(id = R.drawable.brata_bg),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        // 1. Oponentes
        oponentes.forEachIndexed { index, oponente ->
            val alignment = calcularAlineacion(index, oponentes.size)
            val rotacion = calcularRotacion(index, oponentes.size)
            val esTurnoOponente = salaActual.turnoActualId == oponente.id
            Box(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = alignment
            ) {
                AreaJugador(
                    jugador = oponente,
                    esLocal = false,
                    esObservador = esObservador,
                    rotacion = rotacion,
                    esTurnoActual = esTurnoOponente,
                    estadoPoder = estadoPoder,
                    onCartaTocada = { cartaEnMesa ->
                        manejarCartaTocada(
                            cartaEnMesa = cartaEnMesa,
                            estadoPoder = estadoPoder,
                            salaActual = salaActual,
                            jugadorLocalId = jugadorLocal.id,
                            seleccionSinVer = seleccionSinVer,
                            onSeleccionSinVer = { seleccionSinVer = it },
                            idSala = idSala
                        )
                    }
                )
            }
        }

        // 2. Jugador local
        Box(
            modifier = Modifier.fillMaxSize().padding(bottom = 40.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            AreaJugador(
                jugador = yo,
                esLocal = true,
                esObservador = false,
                rotacion = 0f,
                esTurnoActual = estadoTurno.esMiTurno,
                estadoPoder = estadoPoder,
                onBrataClick = {
                    if (estadoTurno.puedePresionarBrata) {
                        GameActions.presionarBrata(idSala, jugadorLocal.id, salaActual)
                    }
                },
                onCartaTocada = { cartaEnMesa ->
                    manejarCartaTocada(
                        cartaEnMesa = cartaEnMesa,
                        estadoPoder = estadoPoder,
                        salaActual = salaActual,
                        jugadorLocalId = jugadorLocal.id,
                        seleccionSinVer = seleccionSinVer,
                        onSeleccionSinVer = { seleccionSinVer = it },
                        idSala = idSala
                    )
                }
            )
        }

        // 3. Mazos centrales
        val (mazoAlign, esHoriz, _) = obtenerConfiguracionMazos(oponentes.size)
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = mazoAlign) {
            MazosCentralesInteractivos(
                esHorizontal = esHoriz,
                cartaSuperiorDescarte = cimaPar,
                puedeRobarDelPozo = estadoTurno.puedeRobar,
                puedeRobarDelDescarte = estadoTurno.puedeRobar && cimaDscarte != null,
                onRobarPozo = { GameActions.robarDelPozo(idSala, jugadorLocal.id, salaActual) },
                onRobarDescarte = { GameActions.robarDelDescarte(idSala, jugadorLocal.id, salaActual) }
            )
        }

        // 4. Indicador de turno
        // Durante espía muestra "Nombre: espiando" en todos los dispositivos
        val textoIndicador = when {
            estadoPoder.hayPoderActivo && estadoPoder.estaEspiando -> {
                val nombreEspia = salaActual.jugadores[estadoPoder.jugadorPoderId]?.nombre ?: ""
                "$nombreEspia: espiando"
            }
            else -> estadoTurno.jugadorEnTurnoNombre
        }
        IndicadorTurno(
            nombreJugador = textoIndicador,
            esMiTurno = estadoTurno.esMiTurno && !estadoPoder.estaEspiando,
            brataActivada = estadoTurno.brataActivada,
            modifier = Modifier.align(Alignment.TopCenter)
        )

        // 5. HandPanel normal — carta robada del pozo
        // Solo si el jugador tiene carta en mano Y NO está en modo espía
        val cartaEnMano = yo.cartaEnMano
        if (cartaEnMano != null && !esObservador && !estadoPoder.estaEspiando) {
            val ultimaDescarte = salaActual.mazoDescarte.lastOrNull()
            val segundaDescarte = salaActual.mazoDescarte.getOrNull(salaActual.mazoDescarte.size - 2)
            val esComodinPropio = cartaEnMano.valor == "JKR" &&
                    salaActual.mazoDescarte.lastOrNull()?.valor == "JKR"

            val acciones = GameRules.accionesDisponibles(
                cartaEnMano = cartaEnMano,
                ultimaCartaDescarte = ultimaDescarte,
                segundaCartaDescarte = segundaDescarte,
                esComodinPropio = esComodinPropio
            )

            val cartasEnMesa = yo.cartas.mapIndexed { index, carta ->
                CartaEnMesa(carta = carta, posicion = index, propietarioId = jugadorLocal.id)
            }

            Box(
                modifier = Modifier.fillMaxSize().padding(bottom = 160.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                HandPanel(
                    cartaEnMano = cartaEnMano,
                    cartasDelJugador = cartasEnMesa,
                    accionesDisponibles = acciones,
                    onAccion = { accion, posicionDestino ->
                        manejarAccionMano(
                            accion = accion,
                            posicionDestino = posicionDestino,
                            cartaEnMano = cartaEnMano,
                            jugadorLocalId = jugadorLocal.id,
                            salaActual = salaActual,
                            idSala = idSala,
                            onMostrarSelectorComodin = { mostrarSelectorComodin = true }
                        )
                    }
                )
            }
        }

        // 6. HandPanel del espía — muestra la carta espiada con sus botones
        // Solo visible para el jugador que está espiando
        if (estadoPoder.esMiPoder && estadoPoder.estaEspiando && cartaEspiada != null) {
            val accionesEspia = mutableListOf<AccionMano>().apply {
                add(AccionMano.REGRESAR)
                if (puedeDescartarEspiada) add(AccionMano.DESCARTAR)
            }

            val cartasEnMesa = yo.cartas.mapIndexed { index, carta ->
                CartaEnMesa(carta = carta, posicion = index, propietarioId = jugadorLocal.id)
            }

            Box(
                modifier = Modifier.fillMaxSize().padding(bottom = 160.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                HandPanel(
                    cartaEnMano = cartaEspiada,
                    cartasDelJugador = cartasEnMesa,
                    accionesDisponibles = accionesEspia,
                    onAccion = { accion, _ ->
                        when (accion) {
                            AccionMano.REGRESAR -> {
                                GameActions.regresarCartaEspiada(idSala, jugadorLocal.id, salaActual)
                            }
                            AccionMano.DESCARTAR -> {
                                // Guardar contexto y mostrar selector de carta propia
                                cartaEspiadaParaDescartar = cartaEspiada
                                propietarioEspiadoId = propietarioCartaEspiada
                                mostrarSelectorCartaPropia = true
                            }
                            else -> Unit
                        }
                    }
                )
            }
        }

        // 7. Overlay de carta bailando
        // Solo visible para jugadores que NO son el espía
        // El espía ve la carta en su HandPanel, no en la mesa
        if (estadoPoder.hayPoderActivo &&
            estadoPoder.estaEspiando &&
            cartaEspiada != null &&
            !estadoPoder.esMiPoder
        ) {
            val cartaEnMesaEspiada = CartaEnMesa(
                carta = cartaEspiada,
                posicion = 0,
                propietarioId = propietarioCartaEspiada
            )
            CartaEspiandoOverlay(carta = cartaEnMesaEspiada)
        }

        // 8. Alerta de adelantado
        val adelantadoActual = adelantadoId
        if (adelantadoActual != null) {
            val nombreAdelantado = salaActual.jugadores[adelantadoActual]?.nombre ?: ""
            AlertaAdelantado(
                nombreAdelantado = nombreAdelantado,
                onRobarJuego = {
                    GameActions.robarJuegoDelAdelantado(
                        salaId = idSala,
                        jugadorId = jugadorLocal.id,
                        adelantadoId = adelantadoActual,
                        sala = salaActual,
                        posicionADescartar = 0
                    )
                    adelantadoId = null
                },
                onPerdonar = {
                    GameActions.perdonarAdelantado(idSala)
                    adelantadoId = null
                }
            )
        }

        // 9. Contador de memorización
        if (mostrarContador) {
            ContadorMemorizacion(
                timestampInicio = salaActual.timestampInicioContador,
                onTiempoAgotado = { }
            )
        }

        // 10. Botón salir
        IconButton(
            onClick = onSalir,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 48.dp, end = 16.dp)
        ) {
            Text("X", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }
    }

    // Selector de carta propia para dar al espiado tras descartar su carta
    if (mostrarSelectorCartaPropia) {
        val cartaEspiadaCtx = cartaEspiadaParaDescartar
        if (cartaEspiadaCtx != null) {
            SelectorCartaPropiaParaEspiado(
                cartasJugador = yo.cartas,
                onSeleccionar = { posicion ->
                    GameActions.descartarCartaEspiada(
                        salaId = idSala,
                        jugadorId = jugadorLocal.id,
                        sala = salaActual,
                        cartaEspiada = cartaEspiadaCtx,
                        propietarioEspiadoId = propietarioEspiadoId,
                        posicionCartaPropia = posicion
                    )
                    mostrarSelectorCartaPropia = false
                    cartaEspiadaParaDescartar = null
                    propietarioEspiadoId = ""
                },
                onDismiss = {
                    mostrarSelectorCartaPropia = false
                }
            )
        }
    }

    // Selector de comodin
    if (mostrarSelectorComodin) {
        val cartaEnMano = yo.cartaEnMano
        if (cartaEnMano != null) {
            SelectorComodin(
                onSeleccionar = { valor, palo ->
                    GameActions.seleccionarValorComodin(
                        salaId = idSala,
                        jugadorId = jugadorLocal.id,
                        sala = salaActual,
                        comodin = cartaEnMano,
                        valorElegido = valor,
                        paloElegido = palo
                    )
                    mostrarSelectorComodin = false
                },
                onDismiss = { mostrarSelectorComodin = false }
            )
        }
    }
}

// ─────────────────────────────────────────────
// SELECTOR DE CARTA PROPIA PARA DAR AL ESPIADO
// Modal que aparece cuando el jugador descarta
// la carta espiada y debe elegir cuál propia dar
// ─────────────────────────────────────────────

@Composable
private fun SelectorCartaPropiaParaEspiado(
    cartasJugador: List<Carta>,
    onSeleccionar: (posicion: Int) -> Unit,
    onDismiss: () -> Unit
) {
    var seleccionada by remember { mutableStateOf<Int?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1A1A1A),
        title = {
            Text(
                "Elige una carta para dar al espiado",
                color = CasinoGold,
                fontWeight = FontWeight.ExtraBold
            )
        },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "La carta descartada va al pozo. Esta carta la recibirá el jugador espiado.",
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(12.dp))

                // Cuadrado 2x2 seleccionable
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(listOf(2, 3), listOf(0, 1)).forEach { fila ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            fila.forEach { pos ->
                                val carta = cartasJugador.getOrNull(pos)
                                val estaSeleccionada = seleccionada == pos
                                Box(
                                    modifier = Modifier
                                        .clickable { seleccionada = pos }
                                        .then(
                                            if (estaSeleccionada)
                                                Modifier.background(
                                                    CasinoGold.copy(alpha = 0.3f),
                                                    RoundedCornerShape(4.dp)
                                                )
                                            else Modifier
                                        )
                                ) {
                                    CartaVisual(
                                        abierta = true,
                                        valor = carta?.valor ?: "",
                                        palo = mappingPalo(carta?.palo)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { seleccionada?.let { onSeleccionar(it) } },
                enabled = seleccionada != null,
                colors = ButtonDefaults.buttonColors(containerColor = CasinoGold)
            ) {
                Text("CONFIRMAR", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCELAR", color = Color.White)
            }
        }
    )
}

// ─────────────────────────────────────────────
// AREA DE JUGADOR
// ─────────────────────────────────────────────

@Composable
fun AreaJugador(
    jugador: Jugador,
    esLocal: Boolean,
    esObservador: Boolean,
    rotacion: Float,
    esTurnoActual: Boolean,
    estadoPoder: CardPowerResolver.EstadoPoder,
    onBrataClick: () -> Unit = {},
    onCartaTocada: (CartaEnMesa) -> Unit = {}
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.rotate(rotacion)
    ) {
        if (!esLocal) {
            Text(
                text = jugador.nombre,
                color = if (esTurnoActual) CasinoGold else Color.White,
                fontSize = 12.sp,
                fontWeight = if (esTurnoActual) FontWeight.ExtraBold else FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (esLocal) Spacer(modifier = Modifier.width(82.dp))

            CuadradoCartasInteractivo(
                jugador = jugador,
                esLocal = esLocal,
                esObservador = esObservador,
                estadoPoder = estadoPoder,
                onCartaTocada = onCartaTocada
            )

            if (esLocal) {
                Spacer(modifier = Modifier.width(12.dp))
                Button(
                    onClick = onBrataClick,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF456B03),
                        disabledContainerColor = Color(0xFF2A3D02)
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.size(70.dp, 36.dp),
                    contentPadding = PaddingValues(0.dp),
                    enabled = esTurnoActual
                ) {
                    Text("BRATA", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Black)
                }
            }
        }

        if (esLocal) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "${jugador.nombre} (TU)",
                color = CasinoGold,
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }
    }
}

// ─────────────────────────────────────────────
// CUADRADO DE CARTAS INTERACTIVO
// [2][3] ALEJADAS - las ve el jugador local
// [0][1] PROXIMAS - siempre cerradas
// ─────────────────────────────────────────────

@Composable
fun CuadradoCartasInteractivo(
    jugador: Jugador,
    esLocal: Boolean,
    esObservador: Boolean,
    estadoPoder: CardPowerResolver.EstadoPoder,
    onCartaTocada: (CartaEnMesa) -> Unit
) {
    val cartas = jugador.cartas
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        listOf(listOf(2, 3), listOf(0, 1)).forEachIndexed { filaIndex, posiciones ->
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                posiciones.forEach { pos ->
                    val carta = cartas.getOrNull(pos)
                    val cartaEnMesa = CartaEnMesa(
                        carta = carta ?: Carta(),
                        posicion = pos,
                        propietarioId = jugador.id
                    )
                    val esSeleccionable = estadoPoder.cartasSeleccionables.contains(carta?.id)
                    // Fila superior (index 0) = posiciones 2,3 = ALEJADAS = las ve el jugador local
                    val abierta = esObservador || (esLocal && filaIndex == 0)

                    CartaVisualInteractiva(
                        carta = carta,
                        abierta = abierta,
                        esSeleccionable = esSeleccionable,
                        onClick = { if (esSeleccionable) onCartaTocada(cartaEnMesa) }
                    )
                }
            }
        }
    }
}

@Composable
private fun CartaVisualInteractiva(
    carta: Carta?,
    abierta: Boolean,
    esSeleccionable: Boolean,
    onClick: () -> Unit
) {
    Box(modifier = Modifier.then(if (esSeleccionable) Modifier.clickable { onClick() } else Modifier)) {
        CartaVisual(
            abierta = abierta,
            valor = carta?.valor ?: "",
            palo = mappingPalo(carta?.palo)
        )
        if (esSeleccionable) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(CasinoGold.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
            )
        }
    }
}

// ─────────────────────────────────────────────
// MAZOS CENTRALES INTERACTIVOS
// ─────────────────────────────────────────────

@Composable
fun MazosCentralesInteractivos(
    esHorizontal: Boolean,
    cartaSuperiorDescarte: Pair<String, Palo>?,
    puedeRobarDelPozo: Boolean,
    puedeRobarDelDescarte: Boolean,
    onRobarPozo: () -> Unit,
    onRobarDescarte: () -> Unit
) {
    val contenido = @Composable {
        Box(modifier = Modifier.then(if (puedeRobarDelPozo) Modifier.clickable { onRobarPozo() } else Modifier)) {
            CartaVisual(abierta = false)
            if (puedeRobarDelPozo) {
                Box(modifier = Modifier.matchParentSize().background(CasinoGold.copy(alpha = 0.25f), RoundedCornerShape(4.dp)))
            }
        }

        if (cartaSuperiorDescarte == null) {
            Box(
                modifier = Modifier.size(50.dp, 70.dp).background(Color.Black.copy(0.1f), RoundedCornerShape(4.dp)).padding(2.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("BRATA", color = Color.White.copy(0.3f), fontSize = 8.sp, fontWeight = FontWeight.Bold)
            }
        } else {
            Box(modifier = Modifier.then(if (puedeRobarDelDescarte) Modifier.clickable { onRobarDescarte() } else Modifier)) {
                CartaVisual(abierta = true, valor = cartaSuperiorDescarte.first, palo = cartaSuperiorDescarte.second)
                if (puedeRobarDelDescarte) {
                    Box(modifier = Modifier.matchParentSize().background(CasinoGold.copy(alpha = 0.25f), RoundedCornerShape(4.dp)))
                }
            }
        }
    }

    if (esHorizontal) {
        Row(Modifier.wrapContentSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) { contenido() }
    } else {
        Column(Modifier.wrapContentSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) { contenido() }
    }
}

// ─────────────────────────────────────────────
// MANEJADORES DE ACCIONES
// ─────────────────────────────────────────────

private fun manejarAccionMano(
    accion: AccionMano,
    posicionDestino: Int?,
    cartaEnMano: Carta,
    jugadorLocalId: String,
    salaActual: Sala,
    idSala: String,
    onMostrarSelectorComodin: () -> Unit
) {
    when (accion) {
        AccionMano.DESCARTAR -> GameActions.descartarCartaEnMano(idSala, jugadorLocalId, salaActual, cartaEnMano)
        AccionMano.CAMBIAR -> posicionDestino?.let {
            GameActions.cambiarCartaEnManoPorPropia(idSala, jugadorLocalId, salaActual, cartaEnMano, it)
        }
        AccionMano.ACTIVAR_PODER -> {
            val poder = GameRules.obtenerPoder(cartaEnMano)
            when (poder) {
                TipoPoder.ESPIAR -> GameActions.activarPoderEspiar(idSala, jugadorLocalId, salaActual, cartaEnMano)
                TipoPoder.CAMBIAR_VIENDO -> GameActions.activarPoderCambiarViendo(idSala, jugadorLocalId, salaActual, cartaEnMano)
                TipoPoder.CAMBIAR_SIN_VER -> GameActions.activarPoderCambiarSinVer(idSala, jugadorLocalId, salaActual, cartaEnMano)
                TipoPoder.DESCARTE_FREE_SELECCION -> Unit
                TipoPoder.NINGUNO -> Unit
            }
        }
        AccionMano.DESCARTAR_FREE -> GameActions.activarDescarteFree(idSala, jugadorLocalId, salaActual, cartaEnMano)
        AccionMano.SELECCIONAR_COMODIN -> onMostrarSelectorComodin()
        AccionMano.REGRESAR -> GameActions.regresarCartaEspiada(idSala, jugadorLocalId, salaActual)
    }
}

private fun manejarCartaTocada(
    cartaEnMesa: CartaEnMesa,
    estadoPoder: CardPowerResolver.EstadoPoder,
    salaActual: Sala,
    jugadorLocalId: String,
    seleccionSinVer: List<CartaEnMesa>,
    onSeleccionSinVer: (List<CartaEnMesa>) -> Unit,
    idSala: String
) {
    if (!estadoPoder.hayPoderActivo || !estadoPoder.esMiPoder) return

    when (estadoPoder.tipoPoder) {
        TipoPoder.ESPIAR -> {
            GameActions.espiarCarta(idSala, jugadorLocalId, cartaEnMesa.carta.id)
        }
        TipoPoder.CAMBIAR_VIENDO -> {
            if (!estadoPoder.estaEspiando) {
                GameActions.espiarCarta(idSala, jugadorLocalId, cartaEnMesa.carta.id)
            }
        }
        TipoPoder.CAMBIAR_SIN_VER -> {
            val nuevaSeleccion = seleccionSinVer + cartaEnMesa
            if (nuevaSeleccion.size == 2) {
                GameActions.confirmarCambioSinVer(idSala, jugadorLocalId, salaActual, nuevaSeleccion[0], nuevaSeleccion[1])
                onSeleccionSinVer(emptyList())
            } else {
                onSeleccionSinVer(nuevaSeleccion)
            }
        }
        TipoPoder.DESCARTE_FREE_SELECCION -> {
            GameActions.confirmarDescarteFree(idSala, jugadorLocalId, salaActual, cartaEnMesa.posicion)
        }
        TipoPoder.NINGUNO -> Unit
    }
}

// ─────────────────────────────────────────────
// POSICIONAMIENTO
// ─────────────────────────────────────────────

fun calcularAlineacion(index: Int, total: Int): Alignment {
    return when (total) {
        1 -> Alignment.TopCenter
        2 -> when (index) {
            0 -> BiasAlignment(-1f, -0.40f)
            else -> BiasAlignment(1f, -0.40f)
        }
        3 -> when (index) {
            0 -> Alignment.CenterStart
            1 -> Alignment.TopCenter
            else -> Alignment.CenterEnd
        }
        4 -> when (index) {
            0 -> BiasAlignment(-1f, 0.30f)
            1 -> BiasAlignment(-1f, -0.70f)
            2 -> BiasAlignment(1f, -0.70f)
            else -> BiasAlignment(1f, 0.30f)
        }
        5 -> when (index) {
            0 -> BiasAlignment(-1f, 0.30f)
            1 -> BiasAlignment(-1f, -0.30f)
            2 -> Alignment.TopCenter
            3 -> BiasAlignment(1f, -0.30f)
            else -> BiasAlignment(1f, 0.30f)
        }
        else -> Alignment.TopCenter
    }
}

fun calcularRotacion(index: Int, total: Int): Float {
    return when (total) {
        1 -> 180f
        2 -> if (index == 0) 90f else 270f
        3 -> when (index) { 0 -> 90f; 1 -> 180f; else -> 270f }
        4 -> when (index) { 0 -> 90f; 1 -> 90f; 2 -> 270f; else -> 270f }
        5 -> when (index) { 0 -> 90f; 1 -> 90f; 2 -> 180f; 3 -> 270f; else -> 270f }
        else -> 0f
    }
}

// ─────────────────────────────────────────────
// PREVIEWS
// ─────────────────────────────────────────────

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, widthDp = 360, heightDp = 760)
@Composable
fun GameTablePreview() {
    val cartasPrueba = listOf(
        Carta(valor = "7", palo = "corazones"),
        Carta(valor = "K", palo = "picas"),
        Carta(valor = "A", palo = "treboles"),
        Carta(valor = "4", palo = "diamantes")
    )
    val yo = Jugador(id = "1", nombre = "Manolo", cartas = cartasPrueba)
    val op1 = Jugador(id = "2", nombre = "Ramon", cartas = cartasPrueba)
    val op2 = Jugador(id = "3", nombre = "Elena", cartas = cartasPrueba)
    GameTableScreen(
        jugadorLocal = yo,
        idSala = "test",
        onSalir = {},
        salaPreview = Sala(id = "test", jugadores = mapOf("1" to yo, "2" to op1, "3" to op2), turnoActualId = "1")
    )
}

// Cartas de prueba reutilizables
private val cartasPrueba = listOf(
    Carta(valor = "7", palo = "corazones"),
    Carta(valor = "K", palo = "picas"),
    Carta(valor = "A", palo = "treboles"),
    Carta(valor = "4", palo = "diamantes")
)

private fun jugador(id: String, nombre: String, esAnfitrion: Boolean = false) =
    Jugador(id = id, nombre = nombre, cartas = cartasPrueba, esAnfitrion = esAnfitrion)

private fun salaConJugadores(vararg jugadores: Jugador) = Sala(
    id = "preview",
    nombreSala = "Mesa Preview",
    jugadores = jugadores.associateBy { it.id },
    turnoActualId = jugadores.first().id
)

// ── 2 JUGADORES (yo + 1 oponente) ─────────────
// Oponente: TopCenter rotado 180°

@Preview(showBackground = true, widthDp = 360, heightDp = 760, name = "Mesa 2 jugadores")
@Composable
fun Preview2Jugadores() {
    val yo = jugador("1", "Tú", esAnfitrion = true)
    val op1 = jugador("2", "Ramon")
    GameTableScreen(
        jugadorLocal = yo,
        idSala = "preview",
        onSalir = {},
        salaPreview = salaConJugadores(yo, op1)
    )
}

// ── 3 JUGADORES (yo + 2 oponentes) ────────────
// Op1: CenterStart rotado 90°
// Op2: CenterEnd rotado 270°

@Preview(showBackground = true, widthDp = 360, heightDp = 760, name = "Mesa 3 jugadores")
@Composable
fun Preview3Jugadores() {
    val yo = jugador("1", "Tú", esAnfitrion = true)
    val op1 = jugador("2", "Ramon")
    val op2 = jugador("3", "Elena")
    GameTableScreen(
        jugadorLocal = yo,
        idSala = "preview",
        onSalir = {},
        salaPreview = salaConJugadores(yo, op1, op2)
    )
}

// ── 4 JUGADORES (yo + 3 oponentes) ────────────
// Op1: BiasAlignment(-0.6, -1) rotado 135°
// Op2: TopCenter rotado 180°
// Op3: BiasAlignment(+0.6, -1) rotado 225°

@Preview(showBackground = true, widthDp = 360, heightDp = 760, name = "Mesa 4 jugadores")
@Composable
fun Preview4Jugadores() {
    val yo = jugador("1", "Tú", esAnfitrion = true)
    val op1 = jugador("2", "Ramon")
    val op2 = jugador("3", "Elena")
    val op3 = jugador("4", "Pedro")
    GameTableScreen(
        jugadorLocal = yo,
        idSala = "preview",
        onSalir = {},
        salaPreview = salaConJugadores(yo, op1, op2, op3)
    )
}

// ── 5 JUGADORES (yo + 4 oponentes) ────────────
// Op1: CenterStart rotado 90°
// Op2: BiasAlignment(-0.3, -1) rotado 150°
// Op3: BiasAlignment(+0.3, -1) rotado 210°
// Op4: CenterEnd rotado 270°

@Preview(showBackground = true, widthDp = 360, heightDp = 760, name = "Mesa 5 jugadores")
@Composable
fun Preview5Jugadores() {
    val yo = jugador("1", "Tú", esAnfitrion = true)
    val op1 = jugador("2", "Ramon")
    val op2 = jugador("3", "Elena")
    val op3 = jugador("4", "Pedro")
    val op4 = jugador("5", "Sofia")
    GameTableScreen(
        jugadorLocal = yo,
        idSala = "preview",
        onSalir = {},
        salaPreview = salaConJugadores(yo, op1, op2, op3, op4)
    )
}

// ── 6 JUGADORES (yo + 5 oponentes) ────────────
// Op1: CenterStart rotado 90°
// Op2: BiasAlignment(-0.6, -1) rotado 135°
// Op3: TopCenter rotado 180°
// Op4: BiasAlignment(+0.6, -1) rotado 225°
// Op5: CenterEnd rotado 270°

@Preview(showBackground = true, widthDp = 360, heightDp = 760, name = "Mesa 6 jugadores")
@Composable
fun Preview6Jugadores() {
    val yo = jugador("1", "Tú", esAnfitrion = true)
    val op1 = jugador("2", "Ramon")
    val op2 = jugador("3", "Elena")
    val op3 = jugador("4", "Pedro")
    val op4 = jugador("5", "Sofia")
    val op5 = jugador("6", "Carlos")
    GameTableScreen(
        jugadorLocal = yo,
        idSala = "preview",
        onSalir = {},
        salaPreview = salaConJugadores(yo, op1, op2, op3, op4, op5)
    )
}
package com.aguado.bratagame.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
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
import com.aguado.bratagame.esSlotVacio
import com.aguado.bratagame.mesaNormalizadaACuatroCasillas
import com.aguado.bratagame.game.AccionMano
import com.aguado.bratagame.game.CardPowerResolver
import com.aguado.bratagame.game.GameActions
import com.aguado.bratagame.game.GameRules
import com.aguado.bratagame.game.TurnManager
import com.aguado.bratagame.ui.components.*
import com.aguado.bratagame.ui.theme.CasinoGold
import android.widget.Toast
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInRoot
import kotlinx.coroutines.delay
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExitToApp

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
    var seleccionCambioPropioActiva by remember { mutableStateOf(false) }

    var seleccionSinVer by remember {
        mutableStateOf<List<CartaEnMesa>>(emptyList())
    }

    var mostrarSelectorCartaPropia by remember { mutableStateOf(false) }
    var cartaEspiadaParaDescartar by remember { mutableStateOf<Carta?>(null) }
    var propietarioEspiadoId by remember { mutableStateOf("") }

    var cartasAlejadasVisibles by remember { mutableStateOf(true) }

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
    val context = LocalContext.current

    val cambioPropioFirebase = salaActual.cambioPropioAnimando

    LaunchedEffect(cambioPropioFirebase?.id) {
        val anim = cambioPropioFirebase ?: return@LaunchedEffect

        if (anim.ejecutorId != jugadorLocal.id) return@LaunchedEffect

        val duracionTotal = anim.duracionSaltoMs + anim.duracionViajeMs

        // Margen extra para que el otro dispositivo alcance a recibir
        // cambioPropioAnimando y reproducir la animación antes de que se limpie.
        delay(duracionTotal + 1_000L)

        val salaFinal = datosSala ?: return@LaunchedEffect
        val yoFinal = salaFinal.jugadores[jugadorLocal.id] ?: return@LaunchedEffect
        val cartaEnManoFinal = yoFinal.cartaEnMano ?: return@LaunchedEffect

        if (cartaEnManoFinal.id != anim.cartaEnManoId) return@LaunchedEffect

        GameActions.confirmarCambioPropioAnimado(
            salaId = idSala,
            jugadorId = jugadorLocal.id,
            sala = salaFinal,
            posicionDestino = anim.posicion,
            cartaEnMano = cartaEnManoFinal,
            animacionId = anim.id
        ) { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    var espontaneoEnCurso by remember { mutableStateOf<CartaEnMesa?>(null) }
    val puedeClicEspontaneoMesa = CardPowerResolver.puedeIniciarDescarteEspontaneoDesdeMesa(
        sala = salaActual,
        estadoPoder = estadoPoder,
        esObservador = esObservador
    )

    LaunchedEffect(espontaneoEnCurso) {
        val ce = espontaneoEnCurso ?: return@LaunchedEffect
        try {
            delay(3000)
            val salaFin = datosSala
            if (salaFin == null) return@LaunchedEffect
            val yoFin = salaFin.jugadores[jugadorLocal.id] ?: return@LaunchedEffect
            val mesa = yoFin.cartas.mesaNormalizadaACuatroCasillas()
            val cartaSlot = mesa.getOrNull(ce.posicion)
            if (cartaSlot == null || cartaSlot.id != ce.carta.id) return@LaunchedEffect
            val cima = salaFin.mazoDescarte.lastOrNull() ?: return@LaunchedEffect
            val coincide = !cartaSlot.esSlotVacio() && cartaSlot.valor == cima.valor
            if (coincide) {
                GameActions.descartarEspontaneo(
                    salaId = idSala,
                    jugadorId = jugadorLocal.id,
                    sala = salaFin,
                    posicionCarta = ce.posicion
                ) { msg -> Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
            } else {
                GameActions.castigoDescarteEspontaneoIncorrecto(
                    salaId = idSala,
                    jugadorId = jugadorLocal.id,
                    sala = salaFin
                ) { msg -> Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
            }
        } finally {
            espontaneoEnCurso = null
        }
    }
    val cimaPar = cimaDscarte?.let { it.valor to mappingPalo(it.palo) }
    val mostrarContador = salaActual.timestampInicioContador > 0L

    // Carta espiada actual
    val cartaEspiada = if (estadoPoder.estaEspiando) {
        salaActual.jugadores.values
            .flatMap { it.cartas }
            .filterNot { it.esSlotVacio() }
            .firstOrNull { it.id == estadoPoder.cartaEspiandoId }
    } else null

    val propietarioCartaEspiada = if (estadoPoder.estaEspiando) {
        salaActual.jugadores.entries
            .firstOrNull { entry ->
                entry.value.cartas.filterNot { it.esSlotVacio() }
                    .any { it.id == estadoPoder.cartaEspiandoId }
            }
            ?.key ?: ""
    } else ""

    // As — puede descartar la espiada si su valor es igual al activador
    val puedeDescartarEspiada = estadoPoder.esMiPoder &&
            estadoPoder.estaEspiando &&
            cartaEspiada != null &&
            cartaEspiada.valor == salaActual.cartaPoderActiva?.valorCartaActivadora

    // Durante CAMBIAR_VIENDO, tras espiar la primera carta, todas las cartas de
    // la mesa siguen seleccionables como objetivo del intercambio.
    // (CardPowerResolver las apaga cuando estaEspiando=true; aquí re-activamos
    // para el caso específico de CAMBIAR_VIENDO en segunda fase.)
    val esCambiarViendoEspiando = estadoPoder.esMiPoder &&
            estadoPoder.estaEspiando &&
            estadoPoder.tipoPoder == TipoPoder.CAMBIAR_VIENDO

    val estadoPoderEfectivo = if (esCambiarViendoEspiando) {
        val todasLasCartasIds = salaActual.jugadores.values
            .flatMap { it.cartas }
            .filterNot { it.esSlotVacio() }
            .map { it.id }
            .toSet()
        estadoPoder.copy(cartasSeleccionables = todasLasCartasIds)
    } else {
        estadoPoder
    }

    val mesaLayout = remember { MesaCardPositionHolder() }

    var centroDescarteEnRaiz by remember {
        mutableStateOf<Offset?>(null)
    }

    val cartaSwapA = salaActual.swapAnimando?.let { swap ->
        GameActions.resolverCartaEnMesaPorId(
            sala = salaActual,
            propietarioId = swap.jugadorAId,
            cartaId = swap.cartaAId
        )
    }

    val cartaSwapB = salaActual.swapAnimando?.let { swap ->
        GameActions.resolverCartaEnMesaPorId(
            sala = salaActual,
            propietarioId = swap.jugadorBId,
            cartaId = swap.cartaBId
        )
    }

    LaunchedEffect(cartaSwapA?.propietarioId, cartaSwapA?.posicion, cartaSwapB?.propietarioId, cartaSwapB?.posicion) {
        if (cartaSwapA != null && cartaSwapB != null) {
            mesaLayout.freezeCentersForSwap(
                MesaCardKey(cartaSwapA.propietarioId, cartaSwapA.posicion),
                MesaCardKey(cartaSwapB.propietarioId, cartaSwapB.posicion)
            )
        }
    }

    val cartaCambioPropioAnimando = salaActual.cambioPropioAnimando

    val casillasOcultasSwap: Set<Pair<String, Int>> =
        listOfNotNull(
            cartaSwapA?.let { it.propietarioId to it.posicion },
            cartaSwapB?.let { it.propietarioId to it.posicion },
            cartaCambioPropioAnimando?.let { it.jugadorId to it.posicion }
        ).toSet()

    ProvideMesaCardLayout(mesaLayout) {
        Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0D3311))) {

            Image(
                painter = painterResource(id = R.drawable.brata_bg),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )

            // 1. Oponentes
            oponentes.forEachIndexed { index, oponente ->
                val posicionMesa = calcularPosicionJugadorMesa(
                    index = index,
                    totalOponentes = oponentes.size
                )

                val esTurnoOponente = salaActual.turnoActualId == oponente.id

                Box(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = posicionMesa.alignment
                ) {
                    AreaJugador(
                        jugador = oponente,
                        jugadorLocalIdActual = jugadorLocal.id,
                        esLocal = false,
                        esObservador = esObservador,
                        rotacion = posicionMesa.rotacion,
                        esTurnoActual = esTurnoOponente,
                        estadoPoder = estadoPoderEfectivo,
                        cartaEspiadaId = estadoPoder.cartaEspiandoId,
                        cartasAlejadasVisibles = false,
                        casillasOcultasAnimacion = casillasOcultasSwap,

                        onCancelarCambioViendo = {
                            GameActions.regresarCartaEspiada(
                                salaId = idSala,
                                jugadorId = jugadorLocal.id,
                                sala = salaActual
                            )
                        },

                        onCartaTocada = { cartaEnMesa ->
                            // Fix 2: Congelar centro de carta A en el momento de la PRIMERA selección,
                            // antes de que casillasOcultasSwap la oculte con un Box vacío.
                            val esPrimerSeleccionSinVer =
                                estadoPoder.tipoPoder == TipoPoder.CAMBIAR_SIN_VER &&
                                        seleccionSinVer.isEmpty()

                            if (esPrimerSeleccionSinVer) {
                                mesaLayout.freezeCentersForSwap(
                                    MesaCardKey(cartaEnMesa.propietarioId, cartaEnMesa.posicion),
                                    MesaCardKey(cartaEnMesa.propietarioId, cartaEnMesa.posicion)
                                )
                            }

                            manejarCartaTocada(
                                cartaEnMesa = cartaEnMesa,
                                estadoPoder = estadoPoder,
                                salaActual = salaActual,
                                jugadorLocalId = jugadorLocal.id,
                                seleccionSinVer = seleccionSinVer,
                                onSeleccionSinVer = { seleccionSinVer = it },
                                onSwapSinVerPendiente = { cartaA, cartaB ->
                                    GameActions.iniciarAnimacionSwap(
                                        salaId = idSala,
                                        ejecutorId = jugadorLocal.id,
                                        cartaA = cartaA,
                                        cartaB = cartaB,
                                        mostrarCartaA = false,
                                        mostrarCartaB = false,
                                        onListo = {}
                                    )
                                },
                                onSwapViendo = { espiada, destino ->
                                    GameActions.iniciarAnimacionSwap(
                                        salaId = idSala,
                                        ejecutorId = jugadorLocal.id,
                                        cartaA = espiada,
                                        cartaB = destino,
                                        mostrarCartaA = true,
                                        mostrarCartaB = false,
                                        onListo = {}
                                    )
                                },
                                cartaEspiadaEnMesa = estadoPoder.cartaEspiandoId
                                    .takeIf { it.isNotEmpty() }
                                    ?.let { cartaId ->
                                        salaActual.jugadores.entries.firstNotNullOfOrNull { entry ->
                                            GameActions.resolverCartaEnMesaPorId(
                                                sala = salaActual,
                                                propietarioId = entry.key,
                                                cartaId = cartaId
                                            )
                                        }
                                    },
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
                    jugadorLocalIdActual = jugadorLocal.id,
                    esLocal = true,
                    esObservador = false,
                    rotacion = 0f,
                    esTurnoActual = estadoTurno.esMiTurno,
                    estadoPoder = estadoPoderEfectivo,
                    cartaEspiadaId = estadoPoder.cartaEspiandoId,
                    cartasAlejadasVisibles = cartasAlejadasVisibles,
                    casillasOcultasAnimacion = casillasOcultasSwap,
                    idCartaAnimacionDescarteEspontaneo = espontaneoEnCurso?.carta?.id,
                    habilitarDescarteEspontaneo = puedeClicEspontaneoMesa,
                    valorCimaDescarteParaPista = cimaDscarte?.valor,

                    habilitarSeleccionCambioPropio = seleccionCambioPropioActiva,
                    onSeleccionCambioPropio = { cartaSeleccionada ->
                        val cartaEnManoActual = yo.cartaEnMano

                        if (cartaEnManoActual != null) {
                            seleccionCambioPropioActiva = false

                            GameActions.iniciarAnimacionCambioPropio(
                                salaId = idSala,
                                ejecutorId = jugadorLocal.id,
                                cartaSeleccionada = cartaSeleccionada,
                                cartaEnMano = cartaEnManoActual,
                                onError = { msg ->
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                }
                            )
                        } else {
                            seleccionCambioPropioActiva = false

                            Toast.makeText(
                                context,
                                "No hay carta en mano para cambiar",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },

                    onCancelarCambioViendo = {
                        GameActions.regresarCartaEspiada(
                            salaId = idSala,
                            jugadorId = jugadorLocal.id,
                            sala = salaActual
                        )
                    },

                    onDescarteEspontaneo = { ce ->
                        if (espontaneoEnCurso == null) espontaneoEnCurso = ce
                    },
                    onBrataClick = {
                        if (estadoTurno.puedePresionarBrata) {
                            GameActions.presionarBrata(idSala, jugadorLocal.id, salaActual)
                        }
                    },
                    onCartaTocada = { cartaEnMesa ->
                        // Fix 2: Congelar centro de carta A en el momento de la PRIMERA selección,
                        // antes de que casillasOcultasSwap la oculte con un Box vacío.
                        val esPrimerSeleccionSinVer =
                            estadoPoder.tipoPoder == TipoPoder.CAMBIAR_SIN_VER &&
                                    seleccionSinVer.isEmpty()

                        if (esPrimerSeleccionSinVer) {
                            mesaLayout.freezeCentersForSwap(
                                MesaCardKey(cartaEnMesa.propietarioId, cartaEnMesa.posicion),
                                MesaCardKey(cartaEnMesa.propietarioId, cartaEnMesa.posicion)
                            )
                        }

                        manejarCartaTocada(
                            cartaEnMesa = cartaEnMesa,
                            estadoPoder = estadoPoder,
                            salaActual = salaActual,
                            jugadorLocalId = jugadorLocal.id,
                            seleccionSinVer = seleccionSinVer,
                            onSeleccionSinVer = { seleccionSinVer = it },
                            onSwapSinVerPendiente = { cartaA, cartaB ->
                                GameActions.iniciarAnimacionSwap(
                                    salaId = idSala,
                                    ejecutorId = jugadorLocal.id,
                                    cartaA = cartaA,
                                    cartaB = cartaB,
                                    mostrarCartaA = false,
                                    mostrarCartaB = false,
                                    onListo = {}
                                )
                            },
                            onSwapViendo = { espiada, destino ->
                                GameActions.iniciarAnimacionSwap(
                                    salaId = idSala,
                                    ejecutorId = jugadorLocal.id,
                                    cartaA = espiada,
                                    cartaB = destino,
                                    mostrarCartaA = true,
                                    mostrarCartaB = false,
                                    onListo = {}
                                )
                            },
                            cartaEspiadaEnMesa = estadoPoder.cartaEspiandoId
                                .takeIf { it.isNotEmpty() }
                                ?.let { cartaId ->
                                    salaActual.jugadores.entries.firstNotNullOfOrNull { entry ->
                                        GameActions.resolverCartaEnMesaPorId(
                                            sala = salaActual,
                                            propietarioId = entry.key,
                                            cartaId = cartaId
                                        )
                                    }
                                },
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
                    puedeRobarDelDescarte = estadoTurno.puedeRobarDelDescarte,
                    onRobarPozo = { GameActions.robarDelPozo(idSala, jugadorLocal.id, salaActual) },
                    onRobarDescarte = { GameActions.robarDelDescarte(idSala, jugadorLocal.id, salaActual) },
                    onCentroDescarteMedido = { centro ->
                        centroDescarteEnRaiz = centro
                    }
                )
            }

            // 4. Indicador de turno

            val nombreTurno = estadoTurno.jugadorEnTurnoNombre

            val detalleJugada = construirDetalleJugadaBanner(
                sala = salaActual,
                jugadorLocalId = jugadorLocal.id
            )

            IndicadorTurno(
                nombreJugador = nombreTurno,
                esMiTurno = estadoTurno.esMiTurno && !estadoPoder.estaEspiando,
                brataActivada = estadoTurno.brataActivada,
                detalleJugada = detalleJugada,
                modifier = Modifier.align(Alignment.TopCenter)
            )


            val cartaEnMano = yo.cartaEnMano

            LaunchedEffect(cartaEnMano?.id) {
                if (cartaEnMano == null) {
                    seleccionCambioPropioActiva = false
                }
            }

            if (
                cartaEnMano != null &&
                !esObservador &&
                !estadoPoder.estaEspiando &&
                !seleccionCambioPropioActiva &&
                salaActual.cambioPropioAnimando == null
            ) {
                val ultimaDescarte = salaActual.mazoDescarte.lastOrNull()
                val segundaDescarte =
                    salaActual.mazoDescarte.getOrNull(salaActual.mazoDescarte.size - 2)
                val esComodinPropio = cartaEnMano.valor == "JKR" &&
                        cartaEnMano.comodinRobadoDelDescarteValido

                val acciones = GameRules.accionesDisponibles(
                    cartaEnMano = cartaEnMano,
                    ultimaCartaDescarte = ultimaDescarte,
                    segundaCartaDescarte = segundaDescarte,
                    esComodinPropio = esComodinPropio
                )


                Box(
                    modifier = Modifier.fillMaxSize().padding(bottom = 160.dp),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    HandPanel(
                        cartaEnMano = cartaEnMano,
                        accionesDisponibles = acciones,
                        onAccion = { accion, posicionDestino ->
                            manejarAccionMano(
                                accion = accion,
                                posicionDestino = posicionDestino,
                                cartaEnMano = cartaEnMano,
                                jugadorLocalId = jugadorLocal.id,
                                salaActual = salaActual,
                                idSala = idSala,
                                onMostrarSelectorComodin = {
                                    GameActions.marcarJugadaActual(
                                        salaId = idSala,
                                        jugadorId = jugadorLocal.id,
                                        tipo = "COMODIN",
                                        subaccion = "Eligiendo valor"
                                    )

                                    mostrarSelectorComodin = true
                                },
                                onIniciarSeleccionCambioPropio = {
                                    seleccionCambioPropioActiva = true
                                }
                            )
                        }
                    )

                }
            }

            if (
                seleccionCambioPropioActiva &&
                cartaEnMano != null &&
                !esObservador &&
                salaActual.cambioPropioAnimando == null
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 40.dp),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(end = 14.dp)
                        ) {
                            Text(
                                text = "Carta en mano",
                                color = CasinoGold,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            CartaVisual(
                                abierta = true,
                                valor = cartaEnMano.valor,
                                palo = mappingPalo(cartaEnMano.palo),
                                modifier = Modifier.size(70.dp, 98.dp)
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            TextButton(
                                onClick = {
                                    seleccionCambioPropioActiva = false
                                }
                            ) {
                                Text(
                                    text = "CANCELAR",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(170.dp))
                    }
                }
            }

            // 6. Panel del espiá (ESPIAR) — solo REGRESAR / DESCARTAR
            // CAMBIAR_VIENDO tiene su propio flujo inline (sección 6b).
            val esEspiarPuro = estadoPoder.esMiPoder &&
                    estadoPoder.estaEspiando &&
                    cartaEspiada != null &&
                    estadoPoder.tipoPoder == TipoPoder.ESPIAR

            if (esEspiarPuro) {
                val accionesEspia = mutableListOf<AccionMano>().apply {
                    add(AccionMano.REGRESAR)
                    if (puedeDescartarEspiada) add(AccionMano.DESCARTAR)
                }
                Box(
                    modifier = Modifier.fillMaxSize().padding(bottom = 160.dp),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    HandPanel(
                        cartaEnMano = cartaEspiada!!,
                        accionesDisponibles = accionesEspia,
                        onAccion = { accion, _ ->
                            when (accion) {
                                AccionMano.REGRESAR ->
                                    GameActions.regresarCartaEspiada(
                                        idSala,
                                        jugadorLocal.id,
                                        salaActual
                                    )

                                AccionMano.DESCARTAR -> {
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

            // 6b. CAMBIAR VIENDO — flujo 100% sobre la mesa
            // Sin panel auxiliar: la carta espiada se revela in-place al espía
            // (CartaEspiandoOverlay con abierta=true) y todas las cartas de la
            // mesa quedan resaltadas con borde azul como objetivos seleccionables.
            // El jugador toca cualquier carta azul → animación + swap.

            // 7. Alerta de adelantado
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

            // 8. Contador de memorización
            // FIX 2: al terminar voltea las cartas alejadas
            if (mostrarContador) {
                ContadorMemorizacion(
                    timestampInicio = salaActual.timestampInicioContador,
                    onTiempoAgotado = {
                        cartasAlejadasVisibles = false
                    }
                )
            }

            // Animación Firebase-driven: se activa en TODOS los dispositivos.
            // Firebase guarda identidad universal; cada dispositivo resuelve posición local.
            salaActual.swapAnimando?.let { swap ->
                val cartaAEnMesa = GameActions.resolverCartaEnMesaPorId(
                    sala = salaActual,
                    propietarioId = swap.jugadorAId,
                    cartaId = swap.cartaAId
                )

                val cartaBEnMesa = GameActions.resolverCartaEnMesaPorId(
                    sala = salaActual,
                    propietarioId = swap.jugadorBId,
                    cartaId = swap.cartaBId
                )

                if (cartaAEnMesa != null && cartaBEnMesa != null) {
                    CardSwapAnimation(
                        cartaA = cartaAEnMesa,
                        cartaB = cartaBEnMesa,
                        mostrarValorA = swap.mostrarCartaA,
                        mostrarValorB = swap.mostrarCartaB,
                        onAnimacionCompleta = { _, _ ->
                            // Solo el dispositivo del jugador que ejecutó el poder confirma.
                            // Los demás dispositivos solo reproducen la animación.
                            if (swap.ejecutorId == jugadorLocal.id) {
                                val tipoPoderActivo = salaActual.cartaPoderActiva?.tipoPoder

                                when (tipoPoderActivo) {
                                    TipoPoder.CAMBIAR_VIENDO -> {
                                        GameActions.confirmarCambioViendo(
                                            salaId = idSala,
                                            jugadorId = jugadorLocal.id,
                                            sala = salaActual,
                                            jugadorAId = swap.jugadorAId,
                                            cartaAId = swap.cartaAId,
                                            jugadorBId = swap.jugadorBId,
                                            cartaBId = swap.cartaBId
                                        ) { msg ->
                                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                        }
                                    }

                                    TipoPoder.CAMBIAR_SIN_VER -> {
                                        GameActions.confirmarCambioSinVer(
                                            salaId = idSala,
                                            jugadorId = jugadorLocal.id,
                                            sala = salaActual,
                                            jugadorAId = swap.jugadorAId,
                                            cartaAId = swap.cartaAId,
                                            jugadorBId = swap.jugadorBId,
                                            cartaBId = swap.cartaBId
                                        ) { msg ->
                                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                        }
                                    }

                                    else -> Unit
                                }

                                seleccionSinVer = emptyList()
                                GameActions.limpiarAnimacionSwap(idSala)
                            }
                        }
                    )
                }
            }
            // Animación de cambio por carta propia Firebase-driven: visible en todos los dispositivos.
            val animCambioPropio = salaActual.cambioPropioAnimando
            val destinoDescarte = centroDescarteEnRaiz

            if (
                animCambioPropio != null &&
                destinoDescarte != null
            ) {
                val cartaAnimada = GameActions.resolverCartaEnMesaPorId(
                    sala = salaActual,
                    propietarioId = animCambioPropio.jugadorId,
                    cartaId = animCambioPropio.cartaId
                )

                if (cartaAnimada != null) {
                    CartaCambioPropioADescarteAnimation(
                        animationId = animCambioPropio.id,
                        cartaEnMesa = cartaAnimada,
                        destinoCentroEnRaiz = destinoDescarte,
                        duracionSaltoMs = animCambioPropio.duracionSaltoMs,
                        duracionViajeMs = animCambioPropio.duracionViajeMs
                    )
                }
            }
            // 9. Botón salir
            IconButton(
                onClick = onSalir,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 48.dp, end = 16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ExitToApp,
                    contentDescription = "Salir",
                    tint = Color(0xFF456B03)
                )
            }
        }

        // Modal: selector de carta propia para dar al espiado
        if (mostrarSelectorCartaPropia) {
            val ctx = cartaEspiadaParaDescartar
            if (ctx != null) {
                SelectorCartaPropiaParaEspiado(
                    cartasJugador = yo.cartas,
                    onSeleccionar = { posicion ->
                        GameActions.descartarCartaEspiada(
                            salaId = idSala,
                            jugadorId = jugadorLocal.id,
                            sala = salaActual,
                            cartaEspiada = ctx,
                            propietarioEspiadoId = propietarioEspiadoId,
                            posicionCartaPropia = posicion
                        )
                        mostrarSelectorCartaPropia = false
                        cartaEspiadaParaDescartar = null
                        propietarioEspiadoId = ""
                    },
                    onDismiss = { mostrarSelectorCartaPropia = false }
                )
            }
        }

        // Modal: selector de comodín
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
}

// ─────────────────────────────────────────────
// SELECTOR DE CARTA PROPIA PARA DAR AL ESPIADO
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
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(listOf(2, 3), listOf(0, 1)).forEach { fila ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            fila.forEach { pos ->
                                val carta = cartasJugador.getOrNull(pos)
                                val esHueco = carta == null || carta.esSlotVacio()
                                val estaSeleccionada = seleccionada == pos
                                Box(
                                    modifier = Modifier
                                        .then(
                                            if (!esHueco) Modifier.clickable { seleccionada = pos }
                                            else Modifier
                                        )
                                        .then(
                                            if (estaSeleccionada)
                                                Modifier.background(
                                                    CasinoGold.copy(alpha = 0.3f),
                                                    RoundedCornerShape(4.dp)
                                                )
                                            else Modifier
                                        )
                                ) {
                                    // FIX 3: se muestran abiertas aquí porque el jugador
                                    // necesita elegir conscientemente qué carta entregar
                                    CartaVisual(
                                        abierta = !esHueco,
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
    jugadorLocalIdActual: String,
    esLocal: Boolean,
    esObservador: Boolean,
    rotacion: Float,
    esTurnoActual: Boolean,
    estadoPoder: CardPowerResolver.EstadoPoder,
    cartaEspiadaId: String,
    cartasAlejadasVisibles: Boolean,
    casillasOcultasAnimacion: Set<Pair<String, Int>> = emptySet(),
    idCartaAnimacionDescarteEspontaneo: String? = null,
    habilitarDescarteEspontaneo: Boolean = false,
    valorCimaDescarteParaPista: String? = null,

    habilitarSeleccionCambioPropio: Boolean = false,
    onSeleccionCambioPropio: ((CartaEnMesa) -> Unit)? = null,

    onCancelarCambioViendo: (() -> Unit)? = null,

    onDescarteEspontaneo: ((CartaEnMesa) -> Unit)? = null,
    onBrataClick: () -> Unit = {},
    onCartaTocada: (CartaEnMesa) -> Unit = {}
) {
    // Notificar la rotación del área al holder para que CardSwapAnimation
    // pueda orientar correctamente las cartas durante el intercambio.
    val posicionesHolder = LocalMesaCardPositions.current
    LaunchedEffect(posicionesHolder, jugador.id, rotacion) {
        posicionesHolder?.updateRotation(jugador.id, rotacion)
    }

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
                jugadorLocalIdActual = jugadorLocalIdActual,
                esLocal = esLocal,
                esObservador = esObservador,
                estadoPoder = estadoPoder,
                cartaEspiadaId = cartaEspiadaId,
                cartasAlejadasVisibles = cartasAlejadasVisibles,
                casillasOcultasAnimacion = casillasOcultasAnimacion,
                idCartaAnimacionDescarteEspontaneo = idCartaAnimacionDescarteEspontaneo,
                habilitarDescarteEspontaneo = habilitarDescarteEspontaneo,
                valorCimaDescarteParaPista = valorCimaDescarteParaPista,

                habilitarSeleccionCambioPropio = habilitarSeleccionCambioPropio,
                onSeleccionCambioPropio = onSeleccionCambioPropio,

                onCancelarCambioViendo = onCancelarCambioViendo,

                onDescarteEspontaneo = onDescarteEspontaneo,
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
//
// FIX 1: La carta espiada baila exactamente en su posición en el
//         cuadrado del jugador espiado. Su valor se revela in-place
//         SOLO al jugador que activó el poder (estadoPoder.esMiPoder).
//         Los demás la ven boca abajo brincando.
//
// FIX 2: cartasAlejadasVisibles controla si [2][3] se ven abiertas
//         true = durante contador, false = después
//
// FIX 3: CAMBIAR SIN VER — las cartas siempre cerradas
//         El jugador elige sin ver los valores
// ─────────────────────────────────────────────

@Composable
fun CuadradoCartasInteractivo(
    jugador: Jugador,
    jugadorLocalIdActual: String,
    esLocal: Boolean,
    esObservador: Boolean,
    estadoPoder: CardPowerResolver.EstadoPoder,
    cartaEspiadaId: String,
    cartasAlejadasVisibles: Boolean,
    casillasOcultasAnimacion: Set<Pair<String, Int>> = emptySet(),
    idCartaAnimacionDescarteEspontaneo: String? = null,
    habilitarDescarteEspontaneo: Boolean = false,
    valorCimaDescarteParaPista: String? = null,

    habilitarSeleccionCambioPropio: Boolean = false,
    onSeleccionCambioPropio: ((CartaEnMesa) -> Unit)? = null,

    onCancelarCambioViendo: (() -> Unit)? = null,

    onDescarteEspontaneo: ((CartaEnMesa) -> Unit)? = null,
    onCartaTocada: (CartaEnMesa) -> Unit = {}
) {
    val cartas = jugador.cartas
    val posicionesHolder = LocalMesaCardPositions.current

// FIX 3: durante CAMBIAR_SIN_VER nadie ve sus valores
    val esCambiarSinVer = estadoPoder.tipoPoder == TipoPoder.CAMBIAR_SIN_VER

    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        listOf(listOf(2, 3), listOf(0, 1)).forEachIndexed { filaIndex, posiciones ->
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                posiciones.forEach { pos ->
                    val carta = cartas.getOrNull(pos)
                    val key = MesaCardKey(jugador.id, pos)

                    val layoutMod = Modifier
                        .onGloballyPositioned { coords ->
                            val bounds = coords.boundsInRoot()
                            val centroVisualReal = bounds.center

                            posicionesHolder?.updateCenter(
                                key = key,
                                center = centroVisualReal
                            )
                        }

                    DisposableEffect(key, posicionesHolder) {
                        onDispose { posicionesHolder?.remove(key) }
                    }

                    val ocultarPorAnimacion =
                        casillasOcultasAnimacion.contains(jugador.id to pos)

                    if (carta == null || carta.esSlotVacio()) {
                        Box(
                            modifier = Modifier
                                .size(50.dp, 70.dp)
                                .then(layoutMod)
                        ) {
                            Spacer(modifier = Modifier.fillMaxSize())
                        }
                        return@forEach
                    }

                    val cartaEnMesa = CartaEnMesa(
                        carta = carta,
                        posicion = pos,
                        propietarioId = jugador.id
                    )

                    val esSeleccionable = estadoPoder.cartasSeleccionables.contains(carta.id)

                    val esSeleccionCambioPropio =
                        esLocal &&
                                habilitarSeleccionCambioPropio &&
                                onSeleccionCambioPropio != null

                    // FIX 1: detectar si esta carta específica está siendo espiada
                    val estaEspiada = carta.id == cartaEspiadaId && cartaEspiadaId.isNotEmpty()

                    // Lógica de visibilidad:
                    // - Cambio por carta propia: siempre cerrada
                    // - Observador: siempre abierta
                    // - CAMBIAR_SIN_VER: siempre cerrada
                    // - Fila alejada: abierta solo durante contador
                    // - Fila próxima: siempre cerrada
                    val abierta = when {
                        esSeleccionCambioPropio -> false
                        esCambiarSinVer -> false
                        esObservador -> true
                        esLocal && filaIndex == 0 -> cartasAlejadasVisibles
                        else -> false
                    }

                    if (ocultarPorAnimacion) {
                        Box(
                            modifier = Modifier
                                .size(50.dp, 70.dp)
                                .then(layoutMod)
                        ) {
                            Spacer(Modifier.fillMaxSize())
                        }

                    } else if (estaEspiada && !esSeleccionCambioPropio) {
                        val soyJugadorQueActivoElPoder =
                            estadoPoder.jugadorPoderId == jugadorLocalIdActual

                        val revelarCartaEspiada =
                            soyJugadorQueActivoElPoder &&
                                    estadoPoder.tipoPoder == TipoPoder.CAMBIAR_VIENDO

                        val puedeCancelarCambioViendo =
                            revelarCartaEspiada &&
                                    onCancelarCambioViendo != null

                        Box(
                            modifier = layoutMod.then(
                                if (puedeCancelarCambioViendo) {
                                    Modifier.clickable {
                                        onCancelarCambioViendo?.invoke()
                                    }
                                } else {
                                    Modifier
                                }
                            )
                        ) {
                            CartaEspiandoOverlay(
                                carta = cartaEnMesa,
                                revelarValor = revelarCartaEspiada,
                                modifier = Modifier.size(50.dp, 70.dp)
                            )
                        }

                    } else {
                        val animandoEspontaneo =
                            carta.id == idCartaAnimacionDescarteEspontaneo

                        val clicEspontaneo =
                            esLocal &&
                                    habilitarDescarteEspontaneo &&
                                    onDescarteEspontaneo != null &&
                                    !esSeleccionable &&
                                    !esSeleccionCambioPropio

                        if (esSeleccionCambioPropio) {
                            Box(
                                modifier = layoutMod
                                    .clickable {
                                        onSeleccionCambioPropio?.invoke(cartaEnMesa)
                                    }
                            ) {
                                CartaVisual(
                                    abierta = false,
                                    valor = "",
                                    palo = mappingPalo(carta.palo)
                                )

                                Box(
                                    modifier = Modifier
                                        .matchParentSize()
                                        .border(
                                            width = 3.dp,
                                            color = Color(0xFF2196F3),
                                            shape = RoundedCornerShape(4.dp)
                                        )
                                )
                            }
                        } else {
                            Box(modifier = layoutMod) {
                                CartaVisualInteractiva(
                                    carta = carta,
                                    abierta = abierta,
                                    esSeleccionable = esSeleccionable,
                                    animandoDescarteEspontaneo = animandoEspontaneo,

                                    onClickPoder = {
                                        onCartaTocada(cartaEnMesa)
                                    },

                                    onClickDescarteEspontaneo = if (clicEspontaneo) {
                                        {
                                            val cb = onDescarteEspontaneo
                                            if (cb != null) cb(cartaEnMesa)
                                        }
                                    } else null
                                )
                            }
                        }
                    }
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
    animandoDescarteEspontaneo: Boolean = false,
    onClickPoder: () -> Unit,
    onClickDescarteEspontaneo: (() -> Unit)? = null
) {
    val clickable = esSeleccionable || onClickDescarteEspontaneo != null

    Box(
        modifier = Modifier
            .descarteEspontaneoBounce(animandoDescarteEspontaneo)
            .then(
                if (clickable) {
                    Modifier.clickable {
                        when {
                            esSeleccionable -> onClickPoder()
                            onClickDescarteEspontaneo != null -> onClickDescarteEspontaneo.invoke()
                        }
                    }
                } else {
                    Modifier
                }
            )
    ) {
        CartaVisual(
            abierta = abierta,
            valor = carta?.valor ?: "",
            palo = mappingPalo(carta?.palo)
        )

        // Borde azul solo para acciones reales de selección:
        // poderes o cambio por carta propia.
        // No se usa para revelar coincidencias del descarte espontáneo.
        if (esSeleccionable) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .border(
                        width = 3.dp,
                        color = Color(0xFF2196F3),
                        shape = RoundedCornerShape(4.dp)
                    )
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
    onRobarDescarte: () -> Unit,
    onCentroDescarteMedido: (Offset) -> Unit = {}
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
                modifier = Modifier
                    .size(50.dp, 70.dp)
                    .onGloballyPositioned { coords ->
                        onCentroDescarteMedido(coords.boundsInRoot().center)
                    }
                    .background(Color.Black.copy(0.1f), RoundedCornerShape(4.dp))
                    .padding(2.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "BRATA",
                    color = Color.White.copy(0.3f),
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .onGloballyPositioned { coords ->
                        onCentroDescarteMedido(coords.boundsInRoot().center)
                    }
                    .then(
                        if (puedeRobarDelDescarte) {
                            Modifier.clickable { onRobarDescarte() }
                        } else {
                            Modifier
                        }
                    )
            ) {
                CartaVisual(
                    abierta = true,
                    valor = cartaSuperiorDescarte.first,
                    palo = cartaSuperiorDescarte.second
                )

                if (puedeRobarDelDescarte) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(
                                CasinoGold.copy(alpha = 0.25f),
                                RoundedCornerShape(4.dp)
                            )
                    )
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
    onMostrarSelectorComodin: () -> Unit,
    onIniciarSeleccionCambioPropio: () -> Unit
) {
    when (accion) {
        AccionMano.DESCARTAR -> {
            GameActions.descartarCartaEnMano(
                salaId = idSala,
                jugadorId = jugadorLocalId,
                sala = salaActual,
                cartaEnMano = cartaEnMano
            )
        }

        AccionMano.CAMBIAR -> {
            if (posicionDestino == null) {
                GameActions.marcarJugadaActual(
                    salaId = idSala,
                    jugadorId = jugadorLocalId,
                    tipo = "CAMBIO_CARTA_PROPIA",
                    subaccion = "Seleccionando carta propia"
                )

                onIniciarSeleccionCambioPropio()
            } else {
                GameActions.cambiarCartaEnManoPorPropia(
                    salaId = idSala,
                    jugadorId = jugadorLocalId,
                    sala = salaActual,
                    cartaEnMano = cartaEnMano,
                    posicionDestino = posicionDestino
                )
            }
        }

        AccionMano.ACTIVAR_PODER -> {
            val poder = GameRules.obtenerPoder(cartaEnMano)
            when (poder) {
                TipoPoder.ESPIAR ->
                    GameActions.activarPoderEspiar(idSala, jugadorLocalId, salaActual, cartaEnMano)

                TipoPoder.CAMBIAR_VIENDO ->
                    GameActions.activarPoderCambiarViendo(idSala, jugadorLocalId, salaActual, cartaEnMano)

                TipoPoder.CAMBIAR_SIN_VER ->
                    GameActions.activarPoderCambiarSinVer(idSala, jugadorLocalId, salaActual, cartaEnMano)

                TipoPoder.DESCARTE_FREE_SELECCION -> Unit
                TipoPoder.NINGUNO -> Unit
            }
        }

        AccionMano.DESCARTAR_FREE -> {
            GameActions.activarDescarteFree(idSala, jugadorLocalId, salaActual, cartaEnMano)
        }

        AccionMano.SELECCIONAR_COMODIN -> onMostrarSelectorComodin()

        AccionMano.REGRESAR -> {
            GameActions.regresarCartaEspiada(idSala, jugadorLocalId, salaActual)
        }
    }
}

private fun construirDetalleJugadaBanner(
    sala: Sala,
    jugadorLocalId: String
): String {
    val jugada = sala.jugadaActual ?: return ""

    if (jugada.jugadorId.isBlank()) return ""

    val nombreJugador = sala.jugadores[jugada.jugadorId]?.nombre ?: "Jugador"
    val prefijo = if (jugada.jugadorId == jugadorLocalId) {
        "Estás"
    } else {
        "$nombreJugador está"
    }

    return when (jugada.tipo) {
        "CAMBIO_CARTA_PROPIA" -> {
            "$prefijo cambiando por carta propia"
        }

        "CAMBIAR_VIENDO" -> {
            when {
                jugada.subaccion.contains("Viendo", ignoreCase = true) ->
                    "$prefijo usando Cambiar viendo · ${jugada.subaccion}"

                else ->
                    "$prefijo usando Cambiar viendo · ${jugada.subaccion.ifBlank { "Seleccionando carta" }}"
            }
        }

        "CAMBIAR_SIN_VER" -> {
            "$prefijo usando Cambiar sin ver · ${jugada.subaccion.ifBlank { "Seleccionando cartas" }}"
        }

        "COMODIN" -> {
            "$prefijo usando comodín · ${jugada.subaccion.ifBlank { "Eligiendo acción" }}"
        }

        "DESCARTE_FREE" -> {
            "$prefijo haciendo descarte free · ${jugada.subaccion.ifBlank { "Seleccionando carta" }}"
        }

        "ESPIAR" -> {
            "$prefijo espiando · ${jugada.subaccion.ifBlank { "Viendo una carta" }}"
        }

        else -> ""
    }
}

private fun manejarCartaTocada(
    cartaEnMesa: CartaEnMesa,
    estadoPoder: CardPowerResolver.EstadoPoder,
    salaActual: Sala,
    jugadorLocalId: String,
    seleccionSinVer: List<CartaEnMesa>,
    onSeleccionSinVer: (List<CartaEnMesa>) -> Unit,
    onSwapSinVerPendiente: (CartaEnMesa, CartaEnMesa) -> Unit,
    // CAMBIAR_VIENDO: callback cuando el jugador elige la carta destino en la mesa
    onSwapViendo: ((cartaEspiada: CartaEnMesa, cartaDestino: CartaEnMesa) -> Unit)? = null,
    // Carta actualmente espiada (necesaria para el swap viendo)
    cartaEspiadaEnMesa: CartaEnMesa? = null,
    idSala: String
){
    if (!estadoPoder.hayPoderActivo || !estadoPoder.esMiPoder) return

    when (estadoPoder.tipoPoder) {
        TipoPoder.ESPIAR -> {
            GameActions.espiarCarta(idSala, jugadorLocalId, cartaEnMesa.carta.id)
        }
        TipoPoder.CAMBIAR_VIENDO -> {
            if (!estadoPoder.estaEspiando) {
                // Primera fase: espiar la carta elegida
                GameActions.espiarCartaCambioViendo(
                    salaId = idSala,
                    jugadorId = jugadorLocalId,
                    cartaId = cartaEnMesa.carta.id
                )
            } else {
                // Segunda fase (modo selección inline activo):
                // el jugador tocó una carta verde → disparar animación + swap
                val espiada = cartaEspiadaEnMesa
                if (espiada != null && onSwapViendo != null) {
                    onSwapViendo(espiada, cartaEnMesa)
                }
            }
        }
        TipoPoder.CAMBIAR_SIN_VER -> {
            val nuevaSeleccion = seleccionSinVer + cartaEnMesa

            if (nuevaSeleccion.size == 2) {
                onSwapSinVerPendiente(
                    nuevaSeleccion[0],
                    nuevaSeleccion[1]
                )
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

data class PosicionJugadorMesa(
    val alignment: Alignment,
    val rotacion: Float
)

fun calcularPosicionJugadorMesa(index: Int, totalOponentes: Int): PosicionJugadorMesa {
    return when (totalOponentes) {

        // 2 jugadores total:
        // Oponente único arriba.
        1 -> PosicionJugadorMesa(
            alignment = Alignment.TopCenter,
            rotacion = 180f
        )

        // 3 jugadores total:
        // Un oponente a la izquierda y otro a la derecha.
        2 -> when (index) {
            0 -> PosicionJugadorMesa(
                alignment = BiasAlignment(-1f, -0.40f),
                rotacion = 90f
            )
            else -> PosicionJugadorMesa(
                alignment = BiasAlignment(1f, -0.40f),
                rotacion = 270f
            )
        }

        // 4 jugadores total:
        // Izquierda, arriba, derecha.
        3 -> when (index) {
            0 -> PosicionJugadorMesa(
                alignment = Alignment.CenterStart,
                rotacion = 90f
            )
            1 -> PosicionJugadorMesa(
                alignment = Alignment.TopCenter,
                rotacion = 180f
            )
            else -> PosicionJugadorMesa(
                alignment = Alignment.CenterEnd,
                rotacion = 270f
            )
        }

        // 5 jugadores total:
        // Dos del lado izquierdo y dos del lado derecho.
        4 -> when (index) {
            0 -> PosicionJugadorMesa(
                alignment = BiasAlignment(-1f, 0.30f),
                rotacion = 90f
            )
            1 -> PosicionJugadorMesa(
                alignment = BiasAlignment(-1f, -0.70f),
                rotacion = 90f
            )
            2 -> PosicionJugadorMesa(
                alignment = BiasAlignment(1f, -0.70f),
                rotacion = 270f
            )
            else -> PosicionJugadorMesa(
                alignment = BiasAlignment(1f, 0.30f),
                rotacion = 270f
            )
        }

        // 6 jugadores total:
        // Dos izquierda, uno arriba, dos derecha.
        5 -> when (index) {
            0 -> PosicionJugadorMesa(
                alignment = BiasAlignment(-1f, 0.30f),
                rotacion = 90f
            )
            1 -> PosicionJugadorMesa(
                alignment = BiasAlignment(-1f, -0.30f),
                rotacion = 90f
            )
            2 -> PosicionJugadorMesa(
                alignment = Alignment.TopCenter,
                rotacion = 180f
            )
            3 -> PosicionJugadorMesa(
                alignment = BiasAlignment(1f, -0.30f),
                rotacion = 270f
            )
            else -> PosicionJugadorMesa(
                alignment = BiasAlignment(1f, 0.30f),
                rotacion = 270f
            )
        }

        else -> PosicionJugadorMesa(
            alignment = Alignment.TopCenter,
            rotacion = 180f
        )
    }
}

@Composable
private fun CartaCambioPropioADescarteAnimation(
    animationId: String,
    cartaEnMesa: CartaEnMesa,
    destinoCentroEnRaiz: Offset,
    duracionSaltoMs: Long,
    duracionViajeMs: Long
) {
    val holder = LocalMesaCardPositions.current
    val density = LocalDensity.current

    val mediaCartaPx = remember(density) {
        with(density) {
            Offset(
                x = 25.dp.toPx(),
                y = 35.dp.toPx()
            )
        }
    }

    val key = remember(cartaEnMesa.propietarioId, cartaEnMesa.posicion) {
        MesaCardKey(cartaEnMesa.propietarioId, cartaEnMesa.posicion)
    }

    var overlayOrigenEnRaiz by remember {
        mutableStateOf<Offset?>(null)
    }

    val centroOrigen = holder?.frozenCenterOf(key) ?: holder?.centerOf(key)

    var elapsedLocal by remember(animationId) {
        mutableStateOf(0L)
    }

    LaunchedEffect(animationId) {
        val inicioLocal = System.currentTimeMillis()
        val total = duracionSaltoMs + duracionViajeMs

        while (elapsedLocal < total) {
            elapsedLocal = System.currentTimeMillis() - inicioLocal
            delay(16L)
        }

        elapsedLocal = total
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { coords ->
                overlayOrigenEnRaiz = coords.positionInRoot()
            }
    ) {
        val origen = centroOrigen
        val overlay = overlayOrigenEnRaiz

        if (origen != null && overlay != null) {
            val elapsed = elapsedLocal.coerceAtLeast(0L)

            val startX = origen.x - mediaCartaPx.x - overlay.x
            val startY = origen.y - mediaCartaPx.y - overlay.y

            val dx = destinoCentroEnRaiz.x - origen.x
            val dy = destinoCentroEnRaiz.y - origen.y

            val saltoProgress =
                (elapsed.toFloat() / duracionSaltoMs.toFloat()).coerceIn(0f, 1f)

            val viajeProgress =
                ((elapsed - duracionSaltoMs).toFloat() / duracionViajeMs.toFloat()).coerceIn(0f, 1f)

            val saltoY = if (elapsed < duracionSaltoMs) {
                -22f * kotlin.math.sin(saltoProgress * Math.PI.toFloat() * 6f)
            } else {
                0f
            }

            val easingViaje = FastOutSlowInEasing.transform(viajeProgress)

            Box(
                modifier = Modifier.graphicsLayer {
                    translationX = startX + dx * easingViaje
                    translationY = startY + dy * easingViaje + saltoY
                    scaleX = 1f - (0.08f * easingViaje)
                    scaleY = 1f - (0.08f * easingViaje)
                    alpha = 1f
                    shadowElevation = 16f
                }
            ) {
                CartaVisual(
                    abierta = false,
                    valor = "",
                    palo = mappingPalo(cartaEnMesa.carta.palo)
                )
            }
        }
    }
}
/**
 * Rota el punto [punto] alrededor de [pivote] por [grados].
 * Usado para corregir positionInRoot() que ignora Modifier.rotate().
 */


// ─────────────────────────────────────────────
// PREVIEW
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
        salaPreview = Sala(
            id = "test",
            jugadores = mapOf("1" to yo, "2" to op1, "3" to op2),
            turnoActualId = "1"
        )
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
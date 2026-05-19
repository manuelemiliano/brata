package com.aguado.bratagame.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.ui.text.style.TextAlign
import com.aguado.bratagame.DescarteEspontaneoAnimando
import com.aguado.bratagame.DescarteFreeAnimando
import com.aguado.bratagame.EspiaAnimando
import com.aguado.bratagame.VentanaFinalRonda
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import com.aguado.bratagame.EntregaCartaEspiadoAnimando
import com.aguado.bratagame.HistorialJugada
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Divider

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

    var mostrarSelectorCartaPropia by remember { mutableStateOf(false) }
    var cartaEspiadaParaDescartar by remember { mutableStateOf<Carta?>(null) }
    var propietarioEspiadoId by remember { mutableStateOf("") }

    var seleccionCartaParaEspiadoActiva by remember { mutableStateOf(false) }

    var seleccionCartaParaAdelantadoActiva by remember { mutableStateOf(false) }

    var cartasSeleccionadasVisualmente by remember {
        mutableStateOf<Set<MesaCardKey>>(emptySet())
    }

    var seleccionSinVer by remember { mutableStateOf<List<CartaEnMesa>>(emptyList()) }
    var accionPoderEnProceso by remember {
        mutableStateOf(false)
    }

    var seleccionVoyObjetivoActiva by remember { mutableStateOf(false) }
    var seleccionVoyEntregaActiva by remember { mutableStateOf(false) }

    var mostrarHistorialJugadas by remember { mutableStateOf(false) }

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

    val historialJugadasOrdenado = salaActual.historialJugadas.values
        .sortedBy { it.timestamp }

    val ventanaFinalRonda = salaActual.ventanaFinalRonda

    val ventanaFinalActiva =
        salaActual.brataActivada &&
                ventanaFinalRonda != null &&
                ventanaFinalRonda.activa &&
                !ventanaFinalRonda.finalizada

    val anfitrionId = salaActual.jugadores.values
        .firstOrNull { it.esAnfitrion }
        ?.id

    val responsableVentanaFinalId =
        anfitrionId ?: salaActual.brataJugadorId

    val soyResponsableVentanaFinal =
        jugadorLocal.id == responsableVentanaFinalId

    val espiaAnimandoFirebase = salaActual.espiaAnimando

    val yo = salaActual.jugadores[jugadorLocal.id] ?: jugadorLocal

    val voyPendienteLocal = salaActual.voyPendiente?.takeIf { it.activo }

    val soyJugadorQueReclamoVoy =
        voyPendienteLocal?.reclamadoPorJugadorId == jugadorLocal.id

    val puedoPresionarVoy =
        voyPendienteLocal != null &&
                voyPendienteLocal.fase == "VENTANA" &&
                !yo.descalificado &&
                yo.cartas.mesaNormalizadaACuatroCasillas().any { !it.esSlotVacio() }

    val deboSeleccionarObjetivoVoy =
        voyPendienteLocal != null &&
                soyJugadorQueReclamoVoy &&
                voyPendienteLocal.fase == "SELECCIONANDO_OBJETIVO"

    val deboSeleccionarEntregaVoy =
        voyPendienteLocal != null &&
                soyJugadorQueReclamoVoy &&
                voyPendienteLocal.fase == "SELECCIONANDO_ENTREGA"

    val adelantadoPendienteLocal = salaActual.adelantadoPendiente
        ?.takeIf {
            it.activo &&
                    it.jugadorPerjudicadoId == jugadorLocal.id
        }
    val jugadorLocalDescalificado = yo.descalificado
    val esObservador = !salaActual.jugadores.containsKey(jugadorLocal.id)

    val jugadorLocalSinCartas = yo.cartas
        .mesaNormalizadaACuatroCasillas()
        .all { it.esSlotVacio() }

    val ordenJugadores = TurnManager.ordenDesdeJugadorLocal(jugadorLocal.id, salaActual)
    val oponentes = ordenJugadores.drop(1).mapNotNull { id -> salaActual.jugadores[id] }
    val estadoTurno = TurnManager.calcularEstadoTurno(jugadorLocal.id, salaActual)
    val estadoPoder = CardPowerResolver.calcularEstadoPoder(jugadorLocal.id, salaActual)

    val hayAnimacionCambioActiva =
        salaActual.swapAnimando != null ||
                salaActual.cambioPropioAnimando != null

    val hayPoderBloqueante =
        estadoPoder.hayPoderActivo ||
                seleccionCambioPropioActiva ||
                seleccionCartaParaEspiadoActiva ||
                seleccionCartaParaAdelantadoActiva ||
                seleccionVoyObjetivoActiva ||
                seleccionVoyEntregaActiva ||
                accionPoderEnProceso ||
                hayAnimacionCambioActiva

    val bloquearInteraccionMesa =
        accionPoderEnProceso ||
                hayAnimacionCambioActiva

    val cimaDscarte = salaActual.mazoDescarte.lastOrNull()
    val context = LocalContext.current

    val descarteEspontaneoFirebase = salaActual.descarteEspontaneoAnimando
    val descarteFreeFirebase = salaActual.descarteFreeAnimando
    val entregaCartaEspiadoFirebase = salaActual.entregaCartaEspiadoAnimando

    LaunchedEffect(
        estadoPoder.hayPoderActivo,
        salaActual.swapAnimando,
        salaActual.cambioPropioAnimando,
        salaActual.cartaPoderActiva
    ) {
        val poderYaNoActivo = !estadoPoder.hayPoderActivo
        val sinAnimacionCambio =
            salaActual.swapAnimando == null &&
                    salaActual.cambioPropioAnimando == null

        if (poderYaNoActivo && sinAnimacionCambio) {
            accionPoderEnProceso = false
        }
    }

    LaunchedEffect(
        salaActual.brataActivada,
        salaActual.turnoActualId,
        salaActual.brataJugadorId,
        ventanaFinalRonda?.id,
        ventanaFinalRonda?.finalizada
    ) {
        val debeAbrirVentanaFinal =
            salaActual.brataActivada &&
                    salaActual.brataJugadorId.isNotBlank() &&
                    salaActual.turnoActualId == salaActual.brataJugadorId &&
                    ventanaFinalRonda?.finalizada != true &&
                    ventanaFinalRonda?.activa != true

        if (debeAbrirVentanaFinal && soyResponsableVentanaFinal) {
            GameActions.iniciarVentanaFinalRonda(
                salaId = idSala,
                sala = salaActual,
                duracionMs = 5000L
            )
        }
    }

    LaunchedEffect(entregaCartaEspiadoFirebase?.id) {
        val anim = entregaCartaEspiadoFirebase ?: return@LaunchedEffect

        if (anim.ejecutorId != jugadorLocal.id) return@LaunchedEffect

        delay(anim.duracionMs + 350L)

        GameActions.limpiarAnimacionEntregaCartaEspiado(idSala)
    }

    LaunchedEffect(ventanaFinalRonda?.id) {
        val ventana = ventanaFinalRonda ?: return@LaunchedEffect

        if (!ventana.activa || ventana.finalizada) return@LaunchedEffect
        if (!soyResponsableVentanaFinal) return@LaunchedEffect

        val transcurrido = System.currentTimeMillis() - ventana.timestampInicio
        val restante = (ventana.duracionMs - transcurrido).coerceAtLeast(0L)

        delay(restante + 120L)

        GameActions.finalizarVentanaFinalRonda(
            salaId = idSala,
            ventanaId = ventana.id
        )
    }

    LaunchedEffect(espiaAnimandoFirebase?.id) {
        val anim = espiaAnimandoFirebase ?: return@LaunchedEffect

        if (anim.ejecutorId != jugadorLocal.id) return@LaunchedEffect

        val transcurrido = System.currentTimeMillis() - anim.timestampInicio
        val restante = (anim.duracionMs - transcurrido).coerceAtLeast(0L)

        delay(restante + 150L)

        GameActions.limpiarAnimacionEspia(idSala)
    }

    LaunchedEffect(descarteFreeFirebase?.id) {
        val anim = descarteFreeFirebase ?: return@LaunchedEffect

        if (anim.ejecutorId != jugadorLocal.id) return@LaunchedEffect

        delay(anim.duracionViajeMs + anim.duracionReboteMs + 350L)

        GameActions.limpiarAnimacionDescarteFree(idSala)
    }

    LaunchedEffect(descarteEspontaneoFirebase?.id) {
        val anim = descarteEspontaneoFirebase ?: return@LaunchedEffect

        if (anim.ejecutorId != jugadorLocal.id) return@LaunchedEffect

        delay(anim.duracionViajeMs + anim.duracionReboteMs + 350L)

        GameActions.limpiarAnimacionDescarteEspontaneo(idSala)
    }

    LaunchedEffect(voyPendienteLocal?.id, voyPendienteLocal?.fase) {
        val voy = voyPendienteLocal ?: return@LaunchedEffect

        if (
            voy.jugadorRobandoId == jugadorLocal.id &&
            voy.fase == "VENTANA"
        ) {
            val transcurrido = System.currentTimeMillis() - voy.timestampInicio
            val restante = (voy.duracionMs - transcurrido).coerceAtLeast(0L)

            delay(restante + 120L)

            val salaFin = datosSala ?: return@LaunchedEffect

            GameActions.resolverVoySinReclamo(
                salaId = idSala,
                jugadorId = jugadorLocal.id,
                sala = salaFin,
                voyId = voy.id
            ) { msg ->
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(deboSeleccionarObjetivoVoy, deboSeleccionarEntregaVoy) {
        seleccionVoyObjetivoActiva = deboSeleccionarObjetivoVoy
        seleccionVoyEntregaActiva = deboSeleccionarEntregaVoy
    }

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
    val puedeClicEspontaneoMesa =
        !jugadorLocalDescalificado &&
                salaActual.voyPendiente?.activo != true &&
                CardPowerResolver.puedeIniciarDescarteEspontaneoDesdeMesa(
                    sala = salaActual,
                    estadoPoder = estadoPoder,
                    esObservador = esObservador
                )

    LaunchedEffect(
        espontaneoEnCurso?.propietarioId,
        espontaneoEnCurso?.posicion,
        espontaneoEnCurso?.carta?.id
    ) {
        val ce = espontaneoEnCurso ?: return@LaunchedEffect

        // La validación ya NO se hace aquí.
        // Este efecto solo conserva un feedback visual breve
        // para evitar dobles clics inmediatos.
        delay(700)

        if (
            espontaneoEnCurso?.propietarioId == ce.propietarioId &&
            espontaneoEnCurso?.posicion == ce.posicion &&
            espontaneoEnCurso?.carta?.id == ce.carta.id
        ) {
            espontaneoEnCurso = null
        }
    }
    val ultimasDosDescarte = salaActual.mazoDescarte
        .takeLast(2)
        .map { it.valor to mappingPalo(it.palo) }
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

    var centroCartaEnManoEnRaiz by remember {
        mutableStateOf<Offset?>(null)
    }

    var centroPenultimaDescarteEnRaiz by remember {
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

    LaunchedEffect(
        seleccionCambioPropioActiva,
        seleccionCartaParaEspiadoActiva,
        seleccionCartaParaAdelantadoActiva,
        seleccionVoyObjetivoActiva,
        seleccionVoyEntregaActiva,
        estadoPoder.hayPoderActivo,
        salaActual.turnoActualId
    ) {
        val haySeleccionActiva =
            seleccionCambioPropioActiva ||
                    seleccionCartaParaEspiadoActiva ||
                    seleccionCartaParaAdelantadoActiva ||
                    seleccionVoyObjetivoActiva ||
                    seleccionVoyEntregaActiva ||
                    estadoPoder.hayPoderActivo

        if (!haySeleccionActiva) {
            cartasSeleccionadasVisualmente = emptySet()
        }
    }

    val cartaCambioPropioAnimando = salaActual.cambioPropioAnimando

    val casillasOcultasSwap: Set<Pair<String, Int>> =
        listOfNotNull(
            cartaSwapA?.let { it.propietarioId to it.posicion },
            cartaSwapB?.let { it.propietarioId to it.posicion },
            cartaCambioPropioAnimando?.let { it.jugadorId to it.posicion },
            entregaCartaEspiadoFirebase?.let { it.origenJugadorId to it.origenPosicion },
            entregaCartaEspiadoFirebase?.let { it.destinoJugadorId to it.destinoPosicion }
        ).toSet()


    fun marcarCartaSeleccionadaVisualmente(carta: CartaEnMesa) {
        val key = MesaCardKey(
            jugadorId = carta.propietarioId,
            posicion = carta.posicion
        )

        cartasSeleccionadasVisualmente = cartasSeleccionadasVisualmente + key
    }

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
                        bloquearInteraccionMesa = bloquearInteraccionMesa,
                        espiaAnimando = espiaAnimandoFirebase,
                        onHistorialClick = {
                            mostrarHistorialJugadas = true
                        },
                        cartasSeleccionadasVisualmente = cartasSeleccionadasVisualmente,
                        esTurnoActual = esTurnoOponente,
                        esJugadorQuePresionoBrata = salaActual.brataActivada &&
                                salaActual.brataJugadorId == oponente.id,
                        estadoPoder = estadoPoderEfectivo,
                        cartaEspiadaId = estadoPoder.cartaEspiandoId,
                        cartasAlejadasVisibles = false,
                        casillasOcultasAnimacion = casillasOcultasSwap,
                        onCartaSeleccionadaVisualmente = { carta ->
                            marcarCartaSeleccionadaVisualmente(carta)
                        },

                        habilitarSeleccionVoyObjetivo =
                            seleccionVoyObjetivoActiva &&
                                    voyPendienteLocal?.reclamadoPorJugadorId == jugadorLocal.id,

                        onSeleccionVoyObjetivo = { cartaObjetivo ->
                            GameActions.seleccionarCartaObjetivoVoy(
                                salaId = idSala,
                                jugadorId = jugadorLocal.id,
                                sala = salaActual,
                                propietarioObjetivoId = cartaObjetivo.propietarioId,
                                posicionObjetivo = cartaObjetivo.posicion
                            ) { msg ->
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            }
                            cartasSeleccionadasVisualmente = emptySet()
                        },

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
                                    accionPoderEnProceso = true

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
                                    accionPoderEnProceso = true

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
                                idSala = idSala,
                                bloquearInteraccionMesa = bloquearInteraccionMesa
                            )
                        }
                    )
                }
            }

            val nombreTurno = estadoTurno.jugadorEnTurnoNombre

            val detalleJugada = construirDetalleJugadaBanner(
                sala = salaActual,
                jugadorLocalId = jugadorLocal.id
            )

            val mostrarBrataLocal =
                !jugadorLocalDescalificado &&
                        estadoTurno.puedePresionarBrata &&
                        yo.cartaEnMano == null &&
                        !seleccionCambioPropioActiva &&
                        !seleccionCartaParaEspiadoActiva &&
                        !seleccionCartaParaAdelantadoActiva &&
                        salaActual.cambioPropioAnimando == null &&
                        !estadoPoder.hayPoderActivo

            val mostrarPasoLocal =
                !jugadorLocalDescalificado &&
                        salaActual.brataActivada &&
                        salaActual.brataJugadorId != jugadorLocal.id &&
                        salaActual.turnoActualId == jugadorLocal.id &&
                        yo.cartaEnMano == null &&
                        !seleccionCambioPropioActiva &&
                        !seleccionCartaParaEspiadoActiva &&
                        !seleccionCartaParaAdelantadoActiva &&
                        !seleccionVoyObjetivoActiva &&
                        !seleccionVoyEntregaActiva &&
                        salaActual.voyPendiente?.activo != true &&
                        salaActual.cambioPropioAnimando == null &&
                        !estadoPoder.hayPoderActivo

            val mostrarVoyLocal =
                puedoPresionarVoy &&
                        !seleccionCambioPropioActiva &&
                        !seleccionCartaParaEspiadoActiva &&
                        !seleccionCartaParaAdelantadoActiva &&
                        !seleccionVoyObjetivoActiva &&
                        !seleccionVoyEntregaActiva &&
                        yo.cartaEnMano == null &&
                        !estadoPoder.hayPoderActivo &&
                        salaActual.cambioPropioAnimando == null

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
                    bloquearInteraccionMesa = bloquearInteraccionMesa,
                    onHistorialClick = {
                        mostrarHistorialJugadas = true
                    },
                    cartasSeleccionadasVisualmente = cartasSeleccionadasVisualmente,
                    esTurnoActual = estadoTurno.esMiTurno,
                    esJugadorQuePresionoBrata = salaActual.brataActivada &&
                            salaActual.brataJugadorId == yo.id,
                    mostrarBotonBrata = mostrarBrataLocal,
                    espiaAnimando = espiaAnimandoFirebase,
                    onCartaSeleccionadaVisualmente = { carta ->
                        marcarCartaSeleccionadaVisualmente(carta)
                    },
                    mostrarBotonPaso = mostrarPasoLocal,
                    mostrarBotonVoy = mostrarVoyLocal,
                    onVoyClick = {
                        GameActions.marcarJugadaActual(
                            salaId = idSala,
                            jugadorId = jugadorLocal.id,
                            tipo = "VOY",
                            subaccion = "oprimió VOY · seleccionando una carta"
                        )

                        GameActions.reclamarVoy(
                            salaId = idSala,
                            jugadorId = jugadorLocal.id,
                            sala = salaActual
                        ) { ok, msg ->
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

                            if (ok) {
                                seleccionVoyObjetivoActiva = true
                                seleccionVoyEntregaActiva = false
                            }
                        }
                    },
                    onPasoClick = {
                        GameActions.pasarTurnoBrata(
                            salaId = idSala,
                            jugadorId = jugadorLocal.id,
                            sala = salaActual
                        ) { msg ->
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }
                    },
                    textoInformativoMesa = detalleJugada.ifBlank {
                        if (estadoTurno.brataActivada) {
                            "Última ronda · Turno de ${estadoTurno.jugadorEnTurnoNombre}"
                        } else {
                            "Turno de ${estadoTurno.jugadorEnTurnoNombre}"
                        }
                    },
                    habilitarSeleccionCartaParaAdelantado = seleccionCartaParaAdelantadoActiva,
                    onSeleccionCartaParaAdelantado = { cartaSeleccionada ->
                        GameActions.resolverRobarDescartePorAdelantado(
                            salaId = idSala,
                            jugadorId = jugadorLocal.id,
                            sala = salaActual,
                            posicionCartaPropia = cartaSeleccionada.posicion
                        ) { msg ->
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }

                        seleccionCartaParaAdelantadoActiva = false
                        cartasSeleccionadasVisualmente = emptySet()
                    },

                    habilitarSeleccionVoyEntrega = seleccionVoyEntregaActiva,
                    onSeleccionVoyEntrega = { cartaPropia ->
                        GameActions.entregarCartaPropiaVoy(
                            salaId = idSala,
                            jugadorId = jugadorLocal.id,
                            sala = salaActual,
                            posicionCartaPropia = cartaPropia.posicion
                        ) { msg ->
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }

                        seleccionVoyEntregaActiva = false
                        cartasSeleccionadasVisualmente = emptySet()
                        seleccionVoyObjetivoActiva = false
                    },
                    brataActivada = estadoTurno.brataActivada,
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

                        cartasSeleccionadasVisualmente = emptySet()
                    },

                    habilitarSeleccionCartaParaEspiado = seleccionCartaParaEspiadoActiva,
                    onSeleccionCartaParaEspiado = { cartaSeleccionada ->
                        val cartaEspiadaActual = cartaEspiadaParaDescartar

                        if (cartaEspiadaActual != null && propietarioEspiadoId.isNotBlank()) {
                            if (propietarioEspiadoId == jugadorLocal.id) {
                                GameActions.descartarCartaEspiadaPropia(
                                    salaId = idSala,
                                    jugadorId = jugadorLocal.id,
                                    sala = salaActual,
                                    cartaEspiada = cartaEspiadaActual
                                ) { msg ->
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                val posicionEspiada = salaActual.jugadores[propietarioEspiadoId]
                                    ?.cartas
                                    ?.mesaNormalizadaACuatroCasillas()
                                    ?.indexOfFirst { it.id == cartaEspiadaActual.id }
                                    ?: -1

                                if (posicionEspiada >= 0) {
                                    mesaLayout.freezeCentersForSwap(
                                        MesaCardKey(cartaSeleccionada.propietarioId, cartaSeleccionada.posicion),
                                        MesaCardKey(propietarioEspiadoId, posicionEspiada)
                                    )
                                }
                                GameActions.descartarCartaEspiada(
                                    salaId = idSala,
                                    jugadorId = jugadorLocal.id,
                                    sala = salaActual,
                                    cartaEspiada = cartaEspiadaActual,
                                    propietarioEspiadoId = propietarioEspiadoId,
                                    posicionCartaPropia = cartaSeleccionada.posicion
                                ) { msg ->
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                }
                            }
                        }

                        seleccionCartaParaEspiadoActiva = false
                        cartasSeleccionadasVisualmente = emptySet()
                        cartaEspiadaParaDescartar = null
                        propietarioEspiadoId = ""
                    },

                    onCancelarCambioViendo = {
                        GameActions.regresarCartaEspiada(
                            salaId = idSala,
                            jugadorId = jugadorLocal.id,
                            sala = salaActual
                        )
                    },

                    onDescarteEspontaneo = onDescarte@{ ce ->
                        if (espontaneoEnCurso != null) return@onDescarte

                        // Congelamos el centro del slot antes de que Firebase quite la carta.
                        mesaLayout.freezeCentersForSwap(
                            MesaCardKey(ce.propietarioId, ce.posicion),
                            MesaCardKey(ce.propietarioId, ce.posicion)
                        )

                        val salaClick = datosSala ?: return@onDescarte
                        val jugadorClick = salaClick.jugadores[jugadorLocal.id] ?: return@onDescarte

                        val mesaClick = jugadorClick.cartas.mesaNormalizadaACuatroCasillas()
                        val cartaSlot = mesaClick.getOrNull(ce.posicion) ?: return@onDescarte

                        // Si la carta ya cambió de lugar o ya no es la misma, no hacemos nada.
                        if (cartaSlot.id != ce.carta.id || cartaSlot.esSlotVacio()) {
                            return@onDescarte
                        }

                        val cimaClick = salaClick.mazoDescarte.lastOrNull()

                        if (cimaClick == null) {
                            Toast.makeText(
                                context,
                                "No hay carta en el descarte",
                                Toast.LENGTH_SHORT
                            ).show()
                            return@onDescarte
                        }

                        // Feedback visual breve. La regla se resuelve inmediatamente.
                        espontaneoEnCurso = ce

                        val coincideEnElMomentoDelClick = cartaSlot.valor == cimaClick.valor

                        if (coincideEnElMomentoDelClick) {
                            GameActions.descartarEspontaneo(
                                salaId = idSala,
                                jugadorId = jugadorLocal.id,
                                sala = salaClick,
                                posicionCarta = ce.posicion
                            ) { msg ->
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            GameActions.castigoDescarteEspontaneoIncorrecto(
                                salaId = idSala,
                                jugadorId = jugadorLocal.id,
                                sala = salaClick
                            ) { msg ->
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            }
                        }
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

                        val esSeleccionDescarteFree =
                            estadoPoder.tipoPoder == TipoPoder.DESCARTE_FREE_SELECCION

                        if (esPrimerSeleccionSinVer || esSeleccionDescarteFree) {
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
                                accionPoderEnProceso = true

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
                                accionPoderEnProceso = true

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
                            idSala = idSala,
                            bloquearInteraccionMesa = bloquearInteraccionMesa
                        )
                    }
                )
            }

            // 3. Mazos centrales
            val (mazoAlign, esHoriz, _) = obtenerConfiguracionMazos(oponentes.size)
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = mazoAlign) {
                MazosCentralesInteractivos(
                    esHorizontal = esHoriz,
                    cartasDescarteVisibles = ultimasDosDescarte,

                    puedeRobarDelPozo = !jugadorLocalDescalificado &&
                            estadoTurno.puedeRobar &&
                            voyPendienteLocal == null &&
                            !ventanaFinalActiva &&
                            !hayPoderBloqueante,

                    puedeRobarDelDescarte = !jugadorLocalDescalificado &&
                            estadoTurno.puedeRobarDelDescarte &&
                            voyPendienteLocal == null &&
                            !ventanaFinalActiva &&
                            !hayPoderBloqueante,

                    onRobarPozo = {
                        GameActions.solicitarRoboDelPozoConVoy(
                            salaId = idSala,
                            jugadorId = jugadorLocal.id,
                            sala = salaActual
                        ) { msg ->
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }
                    },
                    onRobarDescarte = {
                        GameActions.solicitarRoboDelDescarteConVoy(
                            salaId = idSala,
                            jugadorId = jugadorLocal.id,
                            sala = salaActual
                        ) { msg ->
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }
                    },
                    onCentroDescarteMedido = { centro ->
                        centroDescarteEnRaiz = centro
                    },
                    onCentroPenultimaDescarteMedido = { centro ->
                        centroPenultimaDescarteEnRaiz = centro
                    }
                )
            }

            // El indicador de turno ahora se muestra junto al área del jugador local,
            // en el espacio alterno al botón BRATA.


            val cartaEnMano = yo.cartaEnMano

            LaunchedEffect(cartaEnMano?.id) {
                if (cartaEnMano == null) {
                    seleccionCambioPropioActiva = false
                }
            }

            if (
                cartaEnMano != null &&
                !ventanaFinalActiva &&
                !jugadorLocalDescalificado &&
                !esObservador &&
                !estadoPoder.estaEspiando &&
                !seleccionCambioPropioActiva &&
                !seleccionCartaParaEspiadoActiva &&
                !seleccionCartaParaAdelantadoActiva &&
                !seleccionVoyObjetivoActiva &&
                !seleccionVoyEntregaActiva &&
                voyPendienteLocal == null &&
                salaActual.cambioPropioAnimando == null
            ) {
                val ultimaDescarte = salaActual.mazoDescarte.lastOrNull()
                val segundaDescarte =
                    salaActual.mazoDescarte.getOrNull(salaActual.mazoDescarte.size - 2)
                val esComodinPropio = cartaEnMano.valor == "JKR" &&
                        cartaEnMano.comodinRobadoDelDescarteValido

                val accionesBase = if (jugadorLocalSinCartas) {
                    listOf(AccionMano.TOMAR)
                } else {
                    GameRules.accionesDisponibles(
                        cartaEnMano = cartaEnMano,
                        ultimaCartaDescarte = ultimaDescarte,
                        segundaCartaDescarte = segundaDescarte,
                        esComodinPropio = esComodinPropio,
                        permitirDescarteFree = cartaEnMano.origenRobo == "POZO"
                    )
                }

                val acciones = if (adelantadoPendienteLocal != null) {
                    listOf(AccionMano.ROBAR_DESCARTE) + accionesBase
                } else {
                    accionesBase
                }


                Box(
                    modifier = Modifier.fillMaxSize().padding(bottom = 160.dp),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    HandPanel(
                        cartaEnMano = cartaEnMano,
                        accionesDisponibles = acciones,
                        onCentroCartaEnManoMedido = { centro ->
                            centroCartaEnManoEnRaiz = centro
                        },
                        onAccion = { accion, posicionDestino ->
                            manejarAccionMano(
                                accion = accion,
                                posicionDestino = posicionDestino,
                                cartaEnMano = cartaEnMano,
                                jugadorLocalId = jugadorLocal.id,
                                salaActual = salaActual,
                                idSala = idSala,
                                onIniciarSeleccionAdelantado = {
                                    seleccionCambioPropioActiva = false
                                    seleccionCartaParaEspiadoActiva = false
                                    seleccionCartaParaAdelantadoActiva = true
                                },
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
                !esObservador
            ) {
                Box(
                    modifier = Modifier.fillMaxSize()
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .offset(x = (-118).dp, y = (-72).dp)
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
                }
            }


            if (
                adelantadoPendienteLocal != null &&
                cartaEnMano == null &&
                !seleccionCartaParaAdelantadoActiva &&
                !esObservador
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 110.dp),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Button(
                        onClick = {
                            GameActions.marcarJugadaActual(
                                salaId = idSala,
                                jugadorId = jugadorLocal.id,
                                tipo = "ADELANTADO_ESPIA",
                                subaccion = "Seleccionando carta propia"
                            )

                            seleccionCambioPropioActiva = false
                            seleccionCartaParaEspiadoActiva = false
                            seleccionCartaParaAdelantadoActiva = true
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF8A6D1D),
                            contentColor = Color.White
                        )
                    ) {
                        Text(
                            text = "ROBAR DESCARTE",
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // 6. Panel del espiá (ESPIAR) — solo REGRESAR / DESCARTAR
            // CAMBIAR_VIENDO tiene su propio flujo inline (sección 6b).
            val esEspiarPuro = estadoPoder.esMiPoder &&
                    estadoPoder.estaEspiando &&
                    cartaEspiada != null &&
                    estadoPoder.tipoPoder == TipoPoder.ESPIAR

            if (
                esEspiarPuro &&
                !seleccionCartaParaEspiadoActiva &&
                !seleccionCartaParaAdelantadoActiva
            ) {
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
                                    val cartaActual = cartaEspiada

                                    if (cartaActual == null) {
                                        Toast.makeText(
                                            context,
                                            "No hay carta espiada para descartar",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        return@HandPanel
                                    }

                                    if (propietarioCartaEspiada == jugadorLocal.id) {
                                        // Caso especial:
                                        // el jugador espió una carta propia igual a la carta espía.
                                        // No debe seleccionar otra carta; se descarta directamente esa carta.
                                        GameActions.descartarCartaEspiadaPropia(
                                            salaId = idSala,
                                            jugadorId = jugadorLocal.id,
                                            sala = salaActual,
                                            cartaEspiada = cartaActual
                                        ) { msg ->
                                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                        }

                                        seleccionCartaParaEspiadoActiva = false
                                        cartaEspiadaParaDescartar = null
                                        propietarioEspiadoId = ""
                                    } else {
                                        // Caso normal:
                                        // el jugador espió una carta de otro jugador.
                                        // Debe entregar una carta propia para ocupar el hueco del espiado.
                                        cartaEspiadaParaDescartar = cartaActual
                                        propietarioEspiadoId = propietarioCartaEspiada

                                        GameActions.marcarJugadaActual(
                                            salaId = idSala,
                                            jugadorId = jugadorLocal.id,
                                            tipo = "ENTREGAR_CARTA_ESPIADO",
                                            subaccion = "Seleccionando carta propia"
                                        )

                                        seleccionCartaParaEspiadoActiva = true
                                    }
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
                        mostrarValorA = swap.mostrarCartaA && swap.ejecutorId == jugadorLocal.id,
                        mostrarValorB = swap.mostrarCartaB && swap.ejecutorId == jugadorLocal.id,
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
                                accionPoderEnProceso = false
                                cartasSeleccionadasVisualmente = emptySet()
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

            val animDescarteEspontaneo = salaActual.descarteEspontaneoAnimando

            if (animDescarteEspontaneo != null) {
                val indiceActual = salaActual.mazoDescarte.indexOfFirst {
                    it.id == animDescarteEspontaneo.cartaId
                }

                val lastIndex = salaActual.mazoDescarte.lastIndex

                val destinoVisual = when {
                    indiceActual == lastIndex && centroDescarteEnRaiz != null -> {
                        centroDescarteEnRaiz
                    }

                    indiceActual == lastIndex - 1 && centroPenultimaDescarteEnRaiz != null -> {
                        centroPenultimaDescarteEnRaiz
                    }

                    else -> {
                        centroDescarteEnRaiz
                    }
                }

                val estaVisibleEnDescarte =
                    indiceActual == lastIndex || indiceActual == lastIndex - 1

                if (destinoVisual != null) {
                    CartaDescarteEspontaneoHaciaPozoAnimation(
                        animacion = animDescarteEspontaneo,
                        destinoCentroEnRaiz = destinoVisual,
                        visibleEnPozo = estaVisibleEnDescarte
                    )
                }
            }

            val animEntregaEspiado = salaActual.entregaCartaEspiadoAnimando

            if (animEntregaEspiado != null) {
                CartaEntregaEspiadoAnimation(
                    animacion = animEntregaEspiado
                )
            }

            val animDescarteFree = salaActual.descarteFreeAnimando

            if (animDescarteFree != null) {
                val indiceActual = salaActual.mazoDescarte.indexOfFirst {
                    it.id == animDescarteFree.cartaId
                }

                val lastIndex = salaActual.mazoDescarte.lastIndex

                val destinoVisual = when {
                    indiceActual == lastIndex && centroDescarteEnRaiz != null -> {
                        centroDescarteEnRaiz
                    }

                    indiceActual == lastIndex - 1 && centroPenultimaDescarteEnRaiz != null -> {
                        centroPenultimaDescarteEnRaiz
                    }

                    else -> {
                        centroDescarteEnRaiz
                    }
                }

                val estaVisibleEnDescarte =
                    indiceActual == lastIndex || indiceActual == lastIndex - 1

                val origen = centroCartaEnManoEnRaiz

                if (destinoVisual != null && origen != null) {
                    CartaDescarteFreeHaciaPozoAnimation(
                        animacion = animDescarteFree,
                        origenCentroEnRaiz = origen,
                        destinoCentroEnRaiz = destinoVisual,
                        visibleEnPozo = estaVisibleEnDescarte
                    )
                }
            }

            if (ventanaFinalActiva && ventanaFinalRonda != null) {
                IndicadorVentanaFinalRonda(
                    ventana = ventanaFinalRonda,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(top = 48.dp, start = 16.dp)
                )
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

            if (mostrarHistorialJugadas) {
                HistorialJugadasModal(
                    historial = historialJugadasOrdenado,
                    onCerrar = {
                        mostrarHistorialJugadas = false
                    }
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


@Composable
private fun PanelEsperaRoboVoy(
    texto: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .width(150.dp)
            .height(120.dp)
            .background(
                color = Color(0xFF123515).copy(alpha = 0.96f),
                shape = RoundedCornerShape(14.dp)
            )
            .border(
                width = 1.dp,
                color = Color(0xFF456B03),
                shape = RoundedCornerShape(14.dp)
            )
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(34.dp),
                color = CasinoGold,
                strokeWidth = 3.dp
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = texto,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Oportunidad VOY",
                color = CasinoGold,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun CartaEntregaEspiadoAnimation(
    animacion: EntregaCartaEspiadoAnimando
) {
    val holder = LocalMesaCardPositions.current
    val density = LocalDensity.current

    val keyOrigen = remember(animacion.origenJugadorId, animacion.origenPosicion) {
        MesaCardKey(animacion.origenJugadorId, animacion.origenPosicion)
    }

    val keyDestino = remember(animacion.destinoJugadorId, animacion.destinoPosicion) {
        MesaCardKey(animacion.destinoJugadorId, animacion.destinoPosicion)
    }

    val mediaCartaPx = remember(density) {
        with(density) {
            Offset(
                x = 25.dp.toPx(),
                y = 35.dp.toPx()
            )
        }
    }

    var overlayOrigenEnRaiz by remember {
        mutableStateOf<Offset?>(null)
    }

    var elapsedLocal by remember(animacion.id) {
        mutableStateOf(0L)
    }

    LaunchedEffect(animacion.id) {
        val inicioLocal = System.currentTimeMillis()

        while (elapsedLocal < animacion.duracionMs) {
            elapsedLocal = System.currentTimeMillis() - inicioLocal
            delay(16L)
        }

        elapsedLocal = animacion.duracionMs
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { coords ->
                overlayOrigenEnRaiz = coords.positionInRoot()
            }
    ) {
        val origen = holder?.frozenCenterOf(keyOrigen) ?: holder?.centerOf(keyOrigen)
        val destino = holder?.frozenCenterOf(keyDestino) ?: holder?.centerOf(keyDestino)
        val overlay = overlayOrigenEnRaiz

        if (origen != null && destino != null && overlay != null) {
            val progress =
                (elapsedLocal.toFloat() / animacion.duracionMs.toFloat())
                    .coerceIn(0f, 1f)

            val easing = FastOutSlowInEasing.transform(progress)

            val startX = origen.x - mediaCartaPx.x - overlay.x
            val startY = origen.y - mediaCartaPx.y - overlay.y

            val dx = destino.x - origen.x
            val dy = destino.y - origen.y

            val rotacionOrigen = holder?.rotationOf(animacion.origenJugadorId) ?: 0f
            val rotacionDestino = holder?.rotationOf(animacion.destinoJugadorId) ?: 0f
            val diferenciaRotacion =
                ((rotacionDestino - rotacionOrigen + 540f) % 360f) - 180f

            Box(
                modifier = Modifier.graphicsLayer {
                    translationX = startX + dx * easing
                    translationY = startY + dy * easing

                    rotationZ = rotacionOrigen + diferenciaRotacion * easing

                    scaleX = 1f
                    scaleY = 1f
                    alpha = 1f
                    shadowElevation = 18f
                }
            ) {
                CartaVisual(
                    abierta = true,
                    valor = animacion.valor,
                    palo = mappingPalo(animacion.palo),
                    modifier = Modifier.size(50.dp, 70.dp)
                )
            }
        }
    }
}

@Composable
private fun IndicadorErroresDescarte(
    errores: Int,
    descalificado: Boolean,
    presionoBrata: Boolean,
    modifier: Modifier = Modifier
) {
    val erroresSeguros = errores.coerceIn(0, 3)

    Column(
        modifier = modifier.height(92.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        repeat(3) { index ->
            val activo = index < erroresSeguros

            Box(
                modifier = Modifier
                    .size(14.dp)
                    .background(
                        color = when {
                            descalificado -> Color(0xFFB71C1C)
                            activo -> Color(0xFFD32F2F)
                            else -> Color.Black.copy(alpha = 0.35f)
                        },
                        shape = CircleShape
                    )
                    .border(
                        width = 1.dp,
                        color = when {
                            descalificado -> Color(0xFFFFCDD2)
                            activo -> Color(0xFFFFCDD2)
                            else -> Color.White.copy(alpha = 0.30f)
                        },
                        shape = CircleShape
                    )
            )
        }

        Box(
            modifier = Modifier
                .size(15.dp)
                .background(
                    color = if (presionoBrata) {
                        CasinoGold
                    } else {
                        Color.Black.copy(alpha = 0.20f)
                    },
                    shape = CircleShape
                )
                .border(
                    width = 1.dp,
                    color = if (presionoBrata) {
                        Color.White.copy(alpha = 0.85f)
                    } else {
                        Color.White.copy(alpha = 0.20f)
                    },
                    shape = CircleShape
                )
        )
    }
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
    bloquearInteraccionMesa: Boolean = false,
    onHistorialClick: () -> Unit = {},
    espiaAnimando: EspiaAnimando? = null,
    cartasSeleccionadasVisualmente: Set<MesaCardKey> = emptySet(),
    onCartaSeleccionadaVisualmente: (CartaEnMesa) -> Unit = {},
    esTurnoActual: Boolean,
    esJugadorQuePresionoBrata: Boolean = false,
    mostrarBotonBrata: Boolean = false,
    mostrarBotonPaso: Boolean = false,
    mostrarBotonVoy: Boolean = false,
    textoInformativoMesa: String = "",
    brataActivada: Boolean = false,
    estadoPoder: CardPowerResolver.EstadoPoder,
    cartaEspiadaId: String,
    cartasAlejadasVisibles: Boolean,
    casillasOcultasAnimacion: Set<Pair<String, Int>> = emptySet(),
    idCartaAnimacionDescarteEspontaneo: String? = null,
    habilitarDescarteEspontaneo: Boolean = false,
    valorCimaDescarteParaPista: String? = null,

    habilitarSeleccionCambioPropio: Boolean = false,
    onSeleccionCambioPropio: ((CartaEnMesa) -> Unit)? = null,

    habilitarSeleccionCartaParaEspiado: Boolean = false,
    onSeleccionCartaParaEspiado: ((CartaEnMesa) -> Unit)? = null,
    habilitarSeleccionCartaParaAdelantado: Boolean = false,
    onSeleccionCartaParaAdelantado: ((CartaEnMesa) -> Unit)? = null,

    habilitarSeleccionVoyObjetivo: Boolean = false,
    onSeleccionVoyObjetivo: ((CartaEnMesa) -> Unit)? = null,

    habilitarSeleccionVoyEntrega: Boolean = false,
    onSeleccionVoyEntrega: ((CartaEnMesa) -> Unit)? = null,

    onCancelarCambioViendo: (() -> Unit)? = null,

    onDescarteEspontaneo: ((CartaEnMesa) -> Unit)? = null,
    onBrataClick: () -> Unit = {},
    onPasoClick: () -> Unit = {},
    onVoyClick: () -> Unit = {},
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

        Box(
            modifier = if (esLocal) {
                Modifier
                    .width(270.dp)
                    .height(230.dp)
            } else {
                Modifier.wrapContentSize()
            }
        ) {
            if (esLocal) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 6.dp)
                ) {
                    CuadradoCartasInteractivo(
                        jugador = jugador,
                        jugadorLocalIdActual = jugadorLocalIdActual,
                        esLocal = esLocal,
                        esObservador = esObservador,
                        bloquearInteraccionMesa = bloquearInteraccionMesa,
                        estadoPoder = estadoPoder,
                        cartaEspiadaId = cartaEspiadaId,
                        cartasAlejadasVisibles = cartasAlejadasVisibles,
                        casillasOcultasAnimacion = casillasOcultasAnimacion,
                        idCartaAnimacionDescarteEspontaneo = idCartaAnimacionDescarteEspontaneo,
                        habilitarDescarteEspontaneo = habilitarDescarteEspontaneo,
                        valorCimaDescarteParaPista = valorCimaDescarteParaPista,
                        cartasSeleccionadasVisualmente = cartasSeleccionadasVisualmente,
                        onCartaSeleccionadaVisualmente = onCartaSeleccionadaVisualmente,
                        espiaAnimando = espiaAnimando,

                        habilitarSeleccionCambioPropio = habilitarSeleccionCambioPropio,
                        onSeleccionCambioPropio = onSeleccionCambioPropio,

                        habilitarSeleccionCartaParaEspiado = habilitarSeleccionCartaParaEspiado,
                        onSeleccionCartaParaEspiado = onSeleccionCartaParaEspiado,

                        habilitarSeleccionCartaParaAdelantado = habilitarSeleccionCartaParaAdelantado,
                        onSeleccionCartaParaAdelantado = onSeleccionCartaParaAdelantado,

                        habilitarSeleccionVoyObjetivo = habilitarSeleccionVoyObjetivo,
                        onSeleccionVoyObjetivo = onSeleccionVoyObjetivo,

                        habilitarSeleccionVoyEntrega = habilitarSeleccionVoyEntrega,
                        onSeleccionVoyEntrega = onSeleccionVoyEntrega,

                        onCancelarCambioViendo = onCancelarCambioViendo,

                        onDescarteEspontaneo = onDescarteEspontaneo,
                        onCartaTocada = onCartaTocada
                    )
                    IndicadorErroresDescarte(
                        errores = jugador.erroresDescarte,
                        descalificado = jugador.descalificado,
                        presionoBrata = esJugadorQuePresionoBrata,
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .offset(x = (-26).dp)
                    )
                }

                when {
                    mostrarBotonVoy -> {
                        Button(
                            onClick = onVoyClick,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF8A6D1D)
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .offset(x = 36.dp)
                                .size(width = 92.dp, height = 44.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text(
                                "VOY",
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }

                    mostrarBotonBrata -> {
                        Button(
                            onClick = onBrataClick,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF456B03)
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .offset(x = 36.dp)
                                .size(width = 92.dp, height = 44.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text(
                                "BRATA",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }

                    mostrarBotonPaso -> {
                        Button(
                            onClick = onPasoClick,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF456B03)
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .offset(x = 36.dp)
                                .size(width = 92.dp, height = 44.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text(
                                "PASO",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }

                    textoInformativoMesa.isNotBlank() -> {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth(0.95f)
                                .padding(bottom = 0.dp)
                                .heightIn(min = 58.dp, max = 78.dp)
                                .background(
                                    color = if (brataActivada) {
                                        Color(0xFF4E5F05)
                                    } else {
                                        Color(0xFF123515).copy(alpha = 0.94f)
                                    },
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .border(
                                    width = 1.dp,
                                    color = Color(0xFF456B03),
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = textoInformativoMesa,
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )

                                Spacer(modifier = Modifier.width(6.dp))

                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .background(
                                            color = Color.Black.copy(alpha = 0.35f),
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                        .border(
                                            width = 1.dp,
                                            color = Color(0xFF2196F3),
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                        .clickable {
                                            onHistorialClick()
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "≡",
                                        color = Color.White,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Black
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                Box(
                    modifier = Modifier.wrapContentSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CuadradoCartasInteractivo(
                        jugador = jugador,
                        jugadorLocalIdActual = jugadorLocalIdActual,
                        esLocal = esLocal,
                        esObservador = esObservador,
                        bloquearInteraccionMesa = bloquearInteraccionMesa,
                        estadoPoder = estadoPoder,
                        cartaEspiadaId = cartaEspiadaId,
                        cartasAlejadasVisibles = cartasAlejadasVisibles,
                        casillasOcultasAnimacion = casillasOcultasAnimacion,
                        idCartaAnimacionDescarteEspontaneo = idCartaAnimacionDescarteEspontaneo,
                        habilitarDescarteEspontaneo = habilitarDescarteEspontaneo,
                        valorCimaDescarteParaPista = valorCimaDescarteParaPista,
                        cartasSeleccionadasVisualmente = cartasSeleccionadasVisualmente,
                        onCartaSeleccionadaVisualmente = onCartaSeleccionadaVisualmente,
                        espiaAnimando = espiaAnimando,

                        habilitarSeleccionCambioPropio = habilitarSeleccionCambioPropio,
                        onSeleccionCambioPropio = onSeleccionCambioPropio,

                        habilitarSeleccionCartaParaEspiado = habilitarSeleccionCartaParaEspiado,
                        onSeleccionCartaParaEspiado = onSeleccionCartaParaEspiado,

                        habilitarSeleccionCartaParaAdelantado = habilitarSeleccionCartaParaAdelantado,
                        onSeleccionCartaParaAdelantado = onSeleccionCartaParaAdelantado,

                        habilitarSeleccionVoyObjetivo = habilitarSeleccionVoyObjetivo,
                        onSeleccionVoyObjetivo = onSeleccionVoyObjetivo,

                        habilitarSeleccionVoyEntrega = habilitarSeleccionVoyEntrega,
                        onSeleccionVoyEntrega = onSeleccionVoyEntrega,

                        onCancelarCambioViendo = onCancelarCambioViendo,

                        onDescarteEspontaneo = onDescarteEspontaneo,
                        onCartaTocada = onCartaTocada
                    )

                    IndicadorErroresDescarte(
                        errores = jugador.erroresDescarte,
                        descalificado = jugador.descalificado,
                        presionoBrata = esJugadorQuePresionoBrata,
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .offset(x = (-26).dp)
                    )
                }
            }
        }

        if (esLocal) {

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
    bloquearInteraccionMesa: Boolean = false,
    espiaAnimando: EspiaAnimando? = null,
    cartasSeleccionadasVisualmente: Set<MesaCardKey> = emptySet(),
    onCartaSeleccionadaVisualmente: (CartaEnMesa) -> Unit = {},
    estadoPoder: CardPowerResolver.EstadoPoder,
    cartaEspiadaId: String,
    cartasAlejadasVisibles: Boolean,
    casillasOcultasAnimacion: Set<Pair<String, Int>> = emptySet(),
    idCartaAnimacionDescarteEspontaneo: String? = null,
    habilitarDescarteEspontaneo: Boolean = false,
    valorCimaDescarteParaPista: String? = null,

    habilitarSeleccionCambioPropio: Boolean = false,
    onSeleccionCambioPropio: ((CartaEnMesa) -> Unit)? = null,

    habilitarSeleccionCartaParaAdelantado: Boolean = false,
    onSeleccionCartaParaAdelantado: ((CartaEnMesa) -> Unit)? = null,

    habilitarSeleccionCartaParaEspiado: Boolean = false,
    onSeleccionCartaParaEspiado: ((CartaEnMesa) -> Unit)? = null,

    habilitarSeleccionVoyObjetivo: Boolean = false,
    onSeleccionVoyObjetivo: ((CartaEnMesa) -> Unit)? = null,

    habilitarSeleccionVoyEntrega: Boolean = false,
    onSeleccionVoyEntrega: ((CartaEnMesa) -> Unit)? = null,

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

                    val keyVisual = MesaCardKey(
                        jugadorId = cartaEnMesa.propietarioId,
                        posicion = cartaEnMesa.posicion
                    )

                    val estaSeleccionadaVisualmente =
                        cartasSeleccionadasVisualmente.contains(keyVisual)

                    val puedeInteractuarConEstaCarta =
                        !bloquearInteraccionMesa

                    val esSeleccionable =
                        puedeInteractuarConEstaCarta &&
                                estadoPoder.cartasSeleccionables.contains(carta.id)

                    val esSeleccionCambioPropio =
                        puedeInteractuarConEstaCarta &&
                                esLocal &&
                                habilitarSeleccionCambioPropio &&
                                onSeleccionCambioPropio != null

                    val esSeleccionCartaParaEspiado =
                        puedeInteractuarConEstaCarta &&
                                esLocal &&
                                habilitarSeleccionCartaParaEspiado &&
                                onSeleccionCartaParaEspiado != null

                    val esSeleccionCartaParaAdelantado =
                        puedeInteractuarConEstaCarta &&
                                esLocal &&
                                habilitarSeleccionCartaParaAdelantado &&
                                onSeleccionCartaParaAdelantado != null

                    val esSeleccionVoyObjetivo =
                        puedeInteractuarConEstaCarta &&
                                habilitarSeleccionVoyObjetivo &&
                                onSeleccionVoyObjetivo != null

                    val esSeleccionVoyEntrega =
                        puedeInteractuarConEstaCarta &&
                                esLocal &&
                                habilitarSeleccionVoyEntrega &&
                                onSeleccionVoyEntrega != null

                    // FIX 1: detectar si esta carta específica está siendo espiada
                    val estaEspiada = carta.id == cartaEspiadaId && cartaEspiadaId.isNotEmpty()

                    val estaEnAnimacionEspiaPersistente =
                        espiaAnimando != null &&
                                espiaAnimando.propietarioId == jugador.id &&
                                espiaAnimando.posicion == pos &&
                                espiaAnimando.cartaId == carta.id

                    // Lógica de visibilidad:
                    // - Cambio por carta propia: siempre cerrada
                    // - Observador: siempre abierta
                    // - CAMBIAR_SIN_VER: siempre cerrada
                    // - Fila alejada: abierta solo durante contador
                    // - Fila próxima: siempre cerrada
                    val abierta = when {
                        esSeleccionCambioPropio -> false
                        esSeleccionCartaParaEspiado -> false
                        esSeleccionCartaParaAdelantado -> false
                        esSeleccionVoyObjetivo -> false
                        esSeleccionVoyEntrega -> false
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

                    } else if (
                        estaEspiada &&
                        !esSeleccionCambioPropio &&
                        !esSeleccionCartaParaEspiado &&
                        !esSeleccionCartaParaAdelantado &&
                        !esSeleccionVoyObjetivo &&
                        !esSeleccionVoyEntrega
                    ) {
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

                    } else if (
                        estaEnAnimacionEspiaPersistente &&
                        !esSeleccionCambioPropio &&
                        !esSeleccionCartaParaEspiado &&
                        !esSeleccionCartaParaAdelantado &&
                        !esSeleccionVoyObjetivo &&
                        !esSeleccionVoyEntrega
                    ) {
                        Box(
                            modifier = layoutMod
                        ) {
                            CartaEspiandoOverlay(
                                carta = cartaEnMesa,
                                revelarValor = false,
                                modifier = Modifier.size(50.dp, 70.dp)
                            )
                        }

                    } else {
                        val animandoEspontaneo =
                            carta.id == idCartaAnimacionDescarteEspontaneo

                        val clicEspontaneo =
                            !bloquearInteraccionMesa &&
                                    esLocal &&
                                    habilitarDescarteEspontaneo &&
                                    onDescarteEspontaneo != null &&
                                    !esSeleccionable &&
                                    !esSeleccionCambioPropio &&
                                    !esSeleccionCartaParaEspiado &&
                                    !esSeleccionCartaParaAdelantado &&
                                    !esSeleccionVoyObjetivo &&
                                    !esSeleccionVoyEntrega

                        if (
                            esSeleccionCambioPropio ||
                            esSeleccionCartaParaEspiado ||
                            esSeleccionCartaParaAdelantado ||
                            esSeleccionVoyObjetivo ||
                            esSeleccionVoyEntrega
                        ) {
                            Box(
                                modifier = layoutMod.clickable {
                                    onCartaSeleccionadaVisualmente(cartaEnMesa)

                                    when {
                                        esSeleccionCambioPropio ->
                                            onSeleccionCambioPropio?.invoke(cartaEnMesa)

                                        esSeleccionCartaParaEspiado ->
                                            onSeleccionCartaParaEspiado?.invoke(cartaEnMesa)

                                        esSeleccionCartaParaAdelantado ->
                                            onSeleccionCartaParaAdelantado?.invoke(cartaEnMesa)

                                        esSeleccionVoyObjetivo ->
                                            onSeleccionVoyObjetivo?.invoke(cartaEnMesa)

                                        esSeleccionVoyEntrega ->
                                            onSeleccionVoyEntrega?.invoke(cartaEnMesa)
                                    }
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
                                            color = if (estaSeleccionadaVisualmente) {
                                                Color(0xFFFF0000)
                                            } else {
                                                Color(0xFF2196F3)
                                            },
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
                                    onClickPoder = { onCartaTocada(cartaEnMesa) },
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
    cartasDescarteVisibles: List<Pair<String, Palo>>,
    puedeRobarDelPozo: Boolean,
    puedeRobarDelDescarte: Boolean,
    onRobarPozo: () -> Unit,
    onRobarDescarte: () -> Unit,
    onCentroDescarteMedido: (Offset) -> Unit = {},
    onCentroPenultimaDescarteMedido: (Offset) -> Unit = {}
) {
    val contenido = @Composable {
        Box(
            modifier = Modifier.then(
                if (puedeRobarDelPozo) {
                    Modifier.clickable { onRobarPozo() }
                } else {
                    Modifier
                }
            )
        ) {
            CartaVisual(abierta = false)

            if (puedeRobarDelPozo) {
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

        Box(
            modifier = Modifier
                .width(76.dp)
                .height(70.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (cartasDescarteVisibles.isEmpty()) {
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
                val penultima = cartasDescarteVisibles.getOrNull(0)
                val ultima = cartasDescarteVisibles.lastOrNull()

                if (penultima != null && cartasDescarteVisibles.size > 1) {
                    Box(
                        modifier = Modifier
                            .offset(x = 0.dp)
                            .onGloballyPositioned { coords ->
                                onCentroPenultimaDescarteMedido(coords.boundsInRoot().center)
                            }
                    ) {
                        CartaVisual(
                            abierta = true,
                            valor = penultima.first,
                            palo = penultima.second
                        )

                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .background(
                                    Color.Black.copy(alpha = 0.10f),
                                    RoundedCornerShape(4.dp)
                                )
                        )
                    }
                }

                if (ultima != null) {
                    Box(
                        modifier = Modifier
                            .offset(x = 26.dp)
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
                            valor = ultima.first,
                            palo = ultima.second
                        )

                        if (puedeRobarDelDescarte) {
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
    onIniciarSeleccionCambioPropio: () -> Unit,
    onIniciarSeleccionAdelantado: () -> Unit
) {
    when (accion) {
        AccionMano.ROBAR_DESCARTE -> {
            GameActions.marcarJugadaActual(
                salaId = idSala,
                jugadorId = jugadorLocalId,
                tipo = "ADELANTADO_ESPIA",
                subaccion = "Seleccionando carta propia"
            )

            onIniciarSeleccionAdelantado()
        }

        AccionMano.TOMAR -> {
            GameActions.tomarCartaComoNuevaDeJuego(
                salaId = idSala,
                jugadorId = jugadorLocalId,
                sala = salaActual,
                cartaEnMano = cartaEnMano
            )
        }

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

        "PASO_BRATA" -> {
            "$prefijo pasando su turno final"
        }

        "VOY" -> {
            if (jugada.jugadorId == jugadorLocalId) {
                "Oprimiste VOY · ${jugada.subaccion
                    .replace("oprimió VOY ·", "")
                    .trim()
                    .ifBlank { "seleccionando una carta" }}"
            } else {
                "$nombreJugador oprimió VOY · ${jugada.subaccion
                    .replace("oprimió VOY ·", "")
                    .trim()
                    .ifBlank { "seleccionando una carta" }}"
            }
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
    idSala: String,
    bloquearInteraccionMesa: Boolean = false,
){
    if (bloquearInteraccionMesa) return
    if (!estadoPoder.hayPoderActivo || !estadoPoder.esMiPoder) return

    when (estadoPoder.tipoPoder) {
        TipoPoder.ESPIAR -> {
            GameActions.espiarCarta(
                salaId = idSala,
                jugadorId = jugadorLocalId,
                sala = salaActual,
                cartaId = cartaEnMesa.carta.id
            )
        }
        TipoPoder.CAMBIAR_VIENDO -> {
            if (!estadoPoder.estaEspiando) {
                // Primera fase: espiar la carta elegida
                GameActions.espiarCartaCambioViendo(
                    salaId = idSala,
                    jugadorId = jugadorLocalId,
                    sala = salaActual,
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
            val yaSeleccionada =
                seleccionSinVer.any {
                    it.propietarioId == cartaEnMesa.propietarioId &&
                            it.posicion == cartaEnMesa.posicion
                }

            if (yaSeleccionada) return

            if (seleccionSinVer.size >= 2) return

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
private fun HistorialJugadasModal(
    historial: List<HistorialJugada>,
    onCerrar: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.78f))
            .clickable { onCerrar() }
            .padding(18.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.82f)
                .background(
                    color = Color(0xFF102313).copy(alpha = 0.92f),
                    shape = RoundedCornerShape(18.dp)
                )
                .border(
                    width = 1.dp,
                    color = Color(0xFF2196F3),
                    shape = RoundedCornerShape(18.dp)
                )
                .padding(14.dp)
                .clickable(enabled = false) {},
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Historial de jugadas",
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.weight(1f)
                )

                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(
                            color = Color.Black.copy(alpha = 0.35f),
                            shape = RoundedCornerShape(8.dp)
                        )
                        .border(
                            width = 1.dp,
                            color = Color(0xFF2196F3),
                            shape = RoundedCornerShape(8.dp)
                        )
                        .clickable { onCerrar() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "×",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (historial.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Aún no hay movimientos registrados.",
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    reverseLayout = false
                ) {
                    items(historial) { item ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                        ) {
                            Text(
                                text = item.mensaje,
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )

                            Text(
                                text = formatoHoraHistorial(item.timestamp),
                                color = Color.White.copy(alpha = 0.45f),
                                fontSize = 10.sp
                            )
                        }

                        Divider(
                            color = Color.White.copy(alpha = 0.10f),
                            thickness = 1.dp
                        )
                    }
                }
            }
        }
    }
}

private fun formatoHoraHistorial(timestamp: Long): String {
    if (timestamp <= 0L) return ""

    val formato = java.text.SimpleDateFormat(
        "HH:mm:ss",
        java.util.Locale.getDefault()
    )

    return formato.format(java.util.Date(timestamp))
}
@Composable
private fun CartaDescarteFreeHaciaPozoAnimation(
    animacion: DescarteFreeAnimando,
    origenCentroEnRaiz: Offset,
    destinoCentroEnRaiz: Offset,
    visibleEnPozo: Boolean
) {
    val density = LocalDensity.current

    val mediaCartaPx = remember(density) {
        with(density) {
            Offset(
                x = 25.dp.toPx(),
                y = 35.dp.toPx()
            )
        }
    }

    var overlayOrigenEnRaiz by remember {
        mutableStateOf<Offset?>(null)
    }

    var elapsedLocal by remember(animacion.id) {
        mutableStateOf(0L)
    }

    LaunchedEffect(animacion.id) {
        val inicioLocal = System.currentTimeMillis()
        val total = animacion.duracionViajeMs + animacion.duracionReboteMs

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
        val overlay = overlayOrigenEnRaiz

        if (overlay != null) {
            val elapsed = elapsedLocal.coerceAtLeast(0L)

            val viajeProgress =
                (elapsed.toFloat() / animacion.duracionViajeMs.toFloat())
                    .coerceIn(0f, 1f)

            val reboteProgress =
                ((elapsed - animacion.duracionViajeMs).toFloat() / animacion.duracionReboteMs.toFloat())
                    .coerceIn(0f, 1f)

            val easingViaje = FastOutSlowInEasing.transform(viajeProgress)

            val startX = origenCentroEnRaiz.x - mediaCartaPx.x - overlay.x
            val startY = origenCentroEnRaiz.y - mediaCartaPx.y - overlay.y

            val dx = destinoCentroEnRaiz.x - origenCentroEnRaiz.x
            val dy = destinoCentroEnRaiz.y - origenCentroEnRaiz.y

            val rebote = if (reboteProgress > 0f && visibleEnPozo) {
                -10f * kotlin.math.sin(reboteProgress * Math.PI.toFloat() * 4f) *
                        (1f - reboteProgress)
            } else {
                0f
            }

            val alphaActual = when {
                visibleEnPozo -> 1f
                reboteProgress > 0f -> 1f - reboteProgress
                else -> 1f
            }

            Box(
                modifier = Modifier.graphicsLayer {
                    translationX = startX + dx * easingViaje
                    translationY = startY + dy * easingViaje + rebote

                    scaleX = 1f - (0.08f * easingViaje)
                    scaleY = 1f - (0.08f * easingViaje)

                    rotationZ = 0f
                    alpha = alphaActual.coerceIn(0f, 1f)
                    shadowElevation = 18f
                }
            ) {
                CartaVisual(
                    abierta = true,
                    valor = animacion.valor,
                    palo = mappingPalo(animacion.palo),
                    modifier = Modifier.size(50.dp, 70.dp)
                )
            }
        }
    }
}

@Composable
private fun CartaDescarteEspontaneoHaciaPozoAnimation(
    animacion: DescarteEspontaneoAnimando,
    destinoCentroEnRaiz: Offset,
    visibleEnPozo: Boolean
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

    val key = remember(animacion.jugadorId, animacion.posicion) {
        MesaCardKey(animacion.jugadorId, animacion.posicion)
    }

    var overlayOrigenEnRaiz by remember {
        mutableStateOf<Offset?>(null)
    }

    val centroOrigen = holder?.frozenCenterOf(key) ?: holder?.centerOf(key)

    var elapsedLocal by remember(animacion.id) {
        mutableStateOf(0L)
    }

    LaunchedEffect(animacion.id) {
        val inicioLocal = System.currentTimeMillis()
        val total = animacion.duracionViajeMs + animacion.duracionReboteMs

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

            val viajeProgress =
                (elapsed.toFloat() / animacion.duracionViajeMs.toFloat())
                    .coerceIn(0f, 1f)

            val reboteProgress =
                ((elapsed - animacion.duracionViajeMs).toFloat() / animacion.duracionReboteMs.toFloat())
                    .coerceIn(0f, 1f)

            val easingViaje = FastOutSlowInEasing.transform(viajeProgress)

            val startX = origen.x - mediaCartaPx.x - overlay.x
            val startY = origen.y - mediaCartaPx.y - overlay.y

            val dx = destinoCentroEnRaiz.x - origen.x
            val dy = destinoCentroEnRaiz.y - origen.y

            val rebote = if (reboteProgress > 0f && visibleEnPozo) {
                -10f * kotlin.math.sin(reboteProgress * Math.PI.toFloat() * 4f) *
                        (1f - reboteProgress)
            } else {
                0f
            }

            val rotacionOrigen = holder?.rotationOf(animacion.jugadorId) ?: 0f
            val rotacionDestino = 0f
            val diferenciaRotacion =
                ((rotacionDestino - rotacionOrigen + 540f) % 360f) - 180f
            val rotacionActual =
                rotacionOrigen + diferenciaRotacion * easingViaje

            val alphaActual = when {
                visibleEnPozo -> 1f
                reboteProgress > 0f -> 1f - reboteProgress
                else -> 1f
            }

            Box(
                modifier = Modifier.graphicsLayer {
                    translationX = startX + dx * easingViaje
                    translationY = startY + dy * easingViaje + rebote

                    scaleX = 1f - (0.08f * easingViaje)
                    scaleY = 1f - (0.08f * easingViaje)

                    rotationZ = rotacionActual
                    alpha = alphaActual.coerceIn(0f, 1f)
                    shadowElevation = 18f
                }
            ) {
                CartaVisual(
                    abierta = true,
                    valor = animacion.valor,
                    palo = mappingPalo(animacion.palo),
                    modifier = Modifier.size(50.dp, 70.dp)
                )
            }
        }
    }
}


@Composable
private fun IndicadorVentanaFinalRonda(
    ventana: VentanaFinalRonda,
    modifier: Modifier = Modifier
) {
    var ahora by remember(ventana.id) {
        mutableStateOf(System.currentTimeMillis())
    }

    LaunchedEffect(ventana.id) {
        while (true) {
            ahora = System.currentTimeMillis()

            val transcurrido = ahora - ventana.timestampInicio
            if (transcurrido >= ventana.duracionMs) {
                break
            }

            delay(32L)
        }
    }

    val transcurrido = ahora - ventana.timestampInicio
    val restanteMs = (ventana.duracionMs - transcurrido).coerceAtLeast(0L)

    val progreso = if (ventana.duracionMs <= 0L) {
        0f
    } else {
        (restanteMs.toFloat() / ventana.duracionMs.toFloat())
            .coerceIn(0f, 1f)
    }

    Box(
        modifier = modifier.size(46.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier.fillMaxSize()
        ) {
            val strokeWidth = 4.dp.toPx()

            drawArc(
                color = Color(0xFF52BD45).copy(alpha = 0.25f),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(
                    width = strokeWidth,
                    cap = StrokeCap.Round
                )
            )

            drawArc(
                color = Color(0xFF52BD45),
                startAngle = -90f,
                sweepAngle = 360f * progreso,
                useCenter = false,
                style = Stroke(
                    width = strokeWidth,
                    cap = StrokeCap.Round
                )
            )
        }
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
                ((elapsed - duracionSaltoMs).toFloat() / duracionViajeMs.toFloat())
                    .coerceIn(0f, 1f)

            val easingViaje = FastOutSlowInEasing.transform(viajeProgress)

            /*
 * La carta conserva la orientación del jugador al inicio.
 * Usamos esa rotación también para decidir el eje del salto.
 */
            val rotacionOrigen = holder?.rotationOf(cartaEnMesa.propietarioId) ?: 0f
            val rotacionNormalizada = ((rotacionOrigen % 360f) + 360f) % 360f

            val esJugadorLateral =
                rotacionNormalizada in 45f..135f ||
                        rotacionNormalizada in 225f..315f

            val impulsoSalto = if (elapsed < duracionSaltoMs) {
                22f * kotlin.math.sin(
                    saltoProgress * Math.PI.toFloat() * 6f
                )
            } else {
                0f
            }

            /*
             * Jugadores inferior/superior:
             * salto vertical en pantalla.
             *
             * Jugadores laterales:
             * salto horizontal en pantalla, sobre el eje largo de la carta.
             *
             * Esto hace que para quien observa la mesa, las cartas laterales
             * parezcan saltar arriba-abajo respecto al jugador virtual.
             */
            val saltoXGlobal = if (esJugadorLateral) {
                if (rotacionNormalizada in 45f..135f) {
                    impulsoSalto
                } else {
                    -impulsoSalto
                }
            } else {
                0f
            }

            val saltoYGlobal = if (esJugadorLateral) {
                0f
            } else {
                -impulsoSalto
            }

            /*
             * Durante el viaje al descarte, la carta gira hasta quedar
             * orientada como el mazo.
             */
            val rotacionDestino = 0f

            val diferenciaRotacion =
                ((rotacionDestino - rotacionOrigen + 540f) % 360f) - 180f

            val rotacionActual =
                rotacionOrigen + diferenciaRotacion * easingViaje

            Box(
                modifier = Modifier.graphicsLayer {
                    translationX = startX + dx * easingViaje + saltoXGlobal
                    translationY = startY + dy * easingViaje + saltoYGlobal

                    scaleX = 1f - (0.08f * easingViaje)
                    scaleY = 1f - (0.08f * easingViaje)

                    rotationZ = rotacionActual

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
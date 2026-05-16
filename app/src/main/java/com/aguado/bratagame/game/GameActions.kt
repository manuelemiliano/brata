package com.aguado.bratagame.game

import com.aguado.bratagame.Carta
import com.aguado.bratagame.CartaEnMesa
import com.aguado.bratagame.FirebaseManager
import com.aguado.bratagame.MesaSlots
import com.aguado.bratagame.Sala
import com.aguado.bratagame.TipoPoder
import com.aguado.bratagame.esSlotVacio
import com.aguado.bratagame.mesaNormalizadaACuatroCasillas
import com.aguado.bratagame.primeraCasillaVaciaOrdenVisual
import com.google.firebase.database.FirebaseDatabase
import java.util.UUID

// ─────────────────────────────────────────────
// GAME ACTIONS
//
// Cada función representa una acción de un jugador
// durante su turno. Valida con GameRules y escribe
// en Firebase de forma atómica usando updateChildren.
//
// Principio: una acción = una escritura atómica.
// Todos los clientes reciben el nuevo estado via
// el listener de observarSala que ya existe.
// ─────────────────────────────────────────────

object GameActions {

    private val salasRef by lazy {
        FirebaseDatabase.getInstance().getReference("salas")
    }

    // ─────────────────────────────────────────
    // JUGADA ACTUAL PARA BANNER SUPERIOR
    // Estado informativo compartido por Firebase.
    // ─────────────────────────────────────────

    fun marcarJugadaActual(
        salaId: String,
        jugadorId: String,
        tipo: String,
        subaccion: String = ""
    ) {
        val jugada = mapOf(
            "jugadorId" to jugadorId,
            "tipo" to tipo,
            "subaccion" to subaccion,
            "timestamp" to System.currentTimeMillis()
        )

        salasRef.child(salaId)
            .child("jugadaActual")
            .setValue(jugada)
    }

    fun limpiarJugadaActual(salaId: String) {
        salasRef.child(salaId)
            .child("jugadaActual")
            .setValue(mapOf<String, Any>())
    }

    // ─────────────────────────────────────────
    // ROBAR DEL POZO
    // El jugador toma la carta superior del mazoRobar.
    // La carta pasa a estado EN_MANO en Firebase
    // (se registra en cartaEnMano del jugador).
    // ─────────────────────────────────────────

    fun robarDelPozo(
        salaId: String,
        jugadorId: String,
        sala: Sala,
        onError: (String) -> Unit = {}
    ) {
        val mazo = sala.mazoRobar.toMutableList()
        if (mazo.isEmpty()) {
            onError("El pozo de robo está vacío")
            return
        }

        val cartaRobada = mazo.removeAt(0).limpiarMetaDescarteRobo()
        val updates = mutableMapOf<String, Any>()

        // Quitar carta del pozo
        updates["mazoRobar"] = mazo

        // Registrar carta en mano del jugador
        updates["jugadores/$jugadorId/cartaEnMano"] = cartaRobada

        salasRef.child(salaId).updateChildren(updates)
    }

    // ─────────────────────────────────────────
    // ROBAR DEL DESCARTE
    // Solo posible si la carta perteneció al jugador
    // anterior y estaba en su juego (validado por GameRules).
    // ─────────────────────────────────────────

    fun robarDelDescarte(
        salaId: String,
        jugadorId: String,
        sala: Sala,
        onError: (String) -> Unit = {}
    ) {
        if (sala.turnoActualId != jugadorId) {
            onError("No es tu turno")
            return
        }
        if (sala.jugadores[jugadorId]?.cartaEnMano != null) {
            onError("Ya tienes una carta en mano")
            return
        }

        val descarte = sala.mazoDescarte.toMutableList()
        if (descarte.isEmpty()) {
            onError("El pozo de descarte está vacío")
            return
        }

        val cima = descarte.last()
        if (!GameRules.cimaDelDescartePuedeRobarla(jugadorId, cima, sala)) {
            onError("No puedes robar esta carta del descarte")
            return
        }

        val cartaRobada = descarte.removeAt(descarte.lastIndex)
        val paraMano = cartaRobada.paraManoTrasRobarDelDescarte()
        val updates = mutableMapOf<String, Any>()

        updates["mazoDescarte"] = descarte
        updates["jugadores/$jugadorId/cartaEnMano"] = paraMano

        salasRef.child(salaId).updateChildren(updates)
    }

    // ─────────────────────────────────────────
    // DESCARTAR CARTA EN MANO
    // La carta en mano pasa al tope del mazoDescarte.
    // Termina el turno del jugador.
    // ─────────────────────────────────────────

    fun descartarCartaEnMano(
        salaId: String,
        jugadorId: String,
        sala: Sala,
        cartaEnMano: Carta,
        onError: (String) -> Unit = {}
    ) {
        val descarte = sala.mazoDescarte.toMutableList()
        descarte.add(
            cartaEnMano.copy(
                descartadaPorJugadorId = jugadorId,
                descartadaDesdeJuegoMesa = false,
                comodinRobadoDelDescarteValido = false
            )
        )

        val updates = mutableMapOf<String, Any>()
        updates["mazoDescarte"] = descarte
        updates["jugadores/$jugadorId/cartaEnMano"] = mapOf<String, Any>()
        updates["jugadaActual"] = mapOf<String, Any>()
        updates["turnoActualId"] = siguienteTurno(jugadorId, sala)

        salasRef.child(salaId).updateChildren(updates)
    }

    // ─────────────────────────────────────────
    // CAMBIAR CARTA EN MANO POR UNA PROPIA
    // La carta en mano ocupa la posición elegida.
    // La carta desplazada pasa al descarte.
    // Termina el turno.
    // ─────────────────────────────────────────


    fun cambiarCartaEnManoPorPropia(
        salaId: String,
        jugadorId: String,
        sala: Sala,
        cartaEnMano: Carta,
        posicionDestino: Int,           // 0-3 en el cuadrado 2x2
        onError: (String) -> Unit = {}
    ) {
        val jugador = sala.jugadores[jugadorId] ?: run {
            onError("Jugador no encontrado")
            return
        }

        val cartasActuales = jugador.cartas.mesaNormalizadaACuatroCasillas()
        if (posicionDestino !in 0..3) {
            onError("Posición inválida")
            return
        }

        val cartaDesplazada = cartasActuales[posicionDestino]
        cartasActuales[posicionDestino] = cartaEnMano

        val descarte = sala.mazoDescarte.toMutableList()
        if (!cartaDesplazada.esSlotVacio()) {
            descarte.add(
                cartaDesplazada.copy(
                    descartadaPorJugadorId = jugadorId,
                    descartadaDesdeJuegoMesa = true,
                    comodinRobadoDelDescarteValido = false
                )
            )
        }

        val updates = mutableMapOf<String, Any>()
        updates["jugadores/$jugadorId/cartas"] = cartasActuales
        updates["jugadores/$jugadorId/cartaEnMano"] = mapOf<String, Any>()
        updates["mazoDescarte"] = descarte
        updates["jugadaActual"] = mapOf<String, Any>()
        updates["turnoActualId"] = siguienteTurno(jugadorId, sala)

        salasRef.child(salaId).updateChildren(updates)
    }

    // ─────────────────────────────────────────
    // DESCARTE FREE
    // La carta en mano va al descarte en estado activo.
    // El jugador elige una carta de su juego para descartar.
    // Termina el turno al confirmar.
    // ─────────────────────────────────────────

    fun activarDescarteFree(
        salaId: String,
        jugadorId: String,
        sala: Sala,
        cartaEnMano: Carta,
        onError: (String) -> Unit = {}
    ) {
        val descarte = sala.mazoDescarte.toMutableList()
        descarte.add(
            cartaEnMano.copy(
                descartadaPorJugadorId = jugadorId,
                descartadaDesdeJuegoMesa = false,
                comodinRobadoDelDescarteValido = false
            )
        )

        val updates = mutableMapOf<String, Any>()
        updates["mazoDescarte"] = descarte
        updates["jugadores/$jugadorId/cartaEnMano"] = mapOf<String, Any>()

        // Activar estado: el jugador debe elegir qué carta de su juego descartar
        updates["cartaPoderActiva"] = mapOf(
            "jugadorId" to jugadorId,
            "tipoPoder" to TipoPoder.DESCARTE_FREE_SELECCION.name,
            "cartaEspiandoId" to ""
        )

        updates["jugadaActual"] = mapOf(
            "jugadorId" to jugadorId,
            "tipo" to "DESCARTE_FREE",
            "subaccion" to "Seleccionando carta propia para descartar",
            "timestamp" to System.currentTimeMillis()
        )

        salasRef.child(salaId).updateChildren(updates)
    }

    // Confirmar la carta elegida en descarte free
    fun confirmarDescarteFree(
        salaId: String,
        jugadorId: String,
        sala: Sala,
        posicionDescartada: Int,
        onError: (String) -> Unit = {}
    ) {
        val jugador = sala.jugadores[jugadorId] ?: run {
            onError("Jugador no encontrado")
            return
        }

        val cartasActuales = jugador.cartas.mesaNormalizadaACuatroCasillas()
        if (posicionDescartada !in 0..3) {
            onError("Posición inválida")
            return
        }

        val cartaDescartada = cartasActuales[posicionDescartada]
        if (cartaDescartada.esSlotVacio()) {
            onError("Casilla vacía")
            return
        }

        cartasActuales[posicionDescartada] = MesaSlots.VACIA
        val descarte = sala.mazoDescarte.toMutableList()
        descarte.add(
            cartaDescartada.copy(
                descartadaPorJugadorId = jugadorId,
                descartadaDesdeJuegoMesa = true,
                comodinRobadoDelDescarteValido = false
            )
        )

        val updates = mutableMapOf<String, Any>()
        updates["jugadores/$jugadorId/cartas"] = cartasActuales
        updates["mazoDescarte"] = descarte
        updates["cartaPoderActiva"] = mapOf<String, Any>()
        updates["jugadaActual"] = mapOf<String, Any>()
        updates["turnoActualId"] = siguienteTurno(jugadorId, sala)

        salasRef.child(salaId).updateChildren(updates)
    }

    // ─────────────────────────────────────────
    // ACTIVAR PODER: ESPIAR (cartas 5-10)
    // La carta va al descarte en estado ACTIVA_DESCARTE.
    // El jugador puede hacer clic en cualquier carta para espiarla.
    // ─────────────────────────────────────────

    fun activarPoderEspiar(
        salaId: String,
        jugadorId: String,
        sala: Sala,
        cartaEnMano: Carta,
        onError: (String) -> Unit = {}
    ) {
        val descarte = sala.mazoDescarte.toMutableList()
        descarte.add(
            cartaEnMano.copy(
                descartadaPorJugadorId = jugadorId,
                descartadaDesdeJuegoMesa = false,
                comodinRobadoDelDescarteValido = false
            )
        )

        val updates = mutableMapOf<String, Any>()
        updates["mazoDescarte"] = descarte
        updates["jugadores/$jugadorId/cartaEnMano"] = mapOf<String, Any>()
        updates["cartaPoderActiva"] = mapOf(
            "jugadorId" to jugadorId,
            "tipoPoder" to TipoPoder.ESPIAR.name,
            "cartaEspiandoId" to "",
            "valorCartaActivadora" to cartaEnMano.valor
        )

        salasRef.child(salaId).updateChildren(updates)
    }

    // El jugador hace clic sobre una carta para espiarla
    fun espiarCarta(
        salaId: String,
        jugadorId: String,
        cartaId: String
    ) {
        val updates = mutableMapOf<String, Any>()

        updates["cartaPoderActiva/cartaEspiandoId"] = cartaId
        updates["jugadaActual"] = mapOf(
            "jugadorId" to jugadorId,
            "tipo" to "ESPIAR",
            "subaccion" to "Viendo una carta",
            "timestamp" to System.currentTimeMillis()
        )

        salasRef.child(salaId).updateChildren(updates)
    }

    fun espiarCartaCambioViendo(
        salaId: String,
        jugadorId: String,
        cartaId: String
    ) {
        val updates = mutableMapOf<String, Any>()

        updates["cartaPoderActiva/cartaEspiandoId"] = cartaId
        updates["jugadaActual"] = mapOf(
            "jugadorId" to jugadorId,
            "tipo" to "CAMBIAR_VIENDO",
            "subaccion" to "Viendo carta; puede cambiarla o regresarla",
            "timestamp" to System.currentTimeMillis()
        )

        salasRef.child(salaId).updateChildren(updates)
    }

    // El jugador termina de espiar: regresa la carta a su lugar
    fun regresarCartaEspiada(
        salaId: String,
        jugadorId: String,
        sala: Sala
    ) {
        val updates = mutableMapOf<String, Any>()
        updates["cartaPoderActiva"] = mapOf<String, Any>()
        updates["jugadaActual"] = mapOf<String, Any>()
        updates["turnoActualId"] = siguienteTurno(jugadorId, sala)

        salasRef.child(salaId).updateChildren(updates)
    }

    // Descarta la carta espiada (mismo valor que la activadora)
    // y el jugador elige una carta propia para dar al espiado
    fun descartarCartaEspiada(
        salaId: String,
        jugadorId: String,
        sala: Sala,
        cartaEspiada: Carta,           // la carta que estaba espiando
        propietarioEspiadoId: String,  // dueño de la carta espiada
        posicionCartaPropia: Int,       // carta propia que le dará al espiado
        onError: (String) -> Unit = {}
    ) {
        val jugador = sala.jugadores[jugadorId] ?: run { onError("Jugador no encontrado"); return }
        val espiado = sala.jugadores[propietarioEspiadoId] ?: run { onError("Espiado no encontrado"); return }

        val cartasJugador = jugador.cartas.mesaNormalizadaACuatroCasillas()
        if (posicionCartaPropia !in 0..3) {
            onError("Posición inválida")
            return
        }
        val cartaParaDar = cartasJugador[posicionCartaPropia]
        if (cartaParaDar.esSlotVacio()) {
            onError("Casilla vacía")
            return
        }
        cartasJugador[posicionCartaPropia] = MesaSlots.VACIA

        val cartasEspiado = espiado.cartas.mesaNormalizadaACuatroCasillas()
        val idxEspiada = cartasEspiado.indexOfFirst { it.id == cartaEspiada.id }
        if (idxEspiada < 0) {
            onError("Carta espiada no encontrada en mesa")
            return
        }
        cartasEspiado[idxEspiada] = cartaParaDar

        val descarte = sala.mazoDescarte.toMutableList()
        descarte.add(
            cartaEspiada.copy(
                descartadaPorJugadorId = jugadorId,
                descartadaDesdeJuegoMesa = false,
                comodinRobadoDelDescarteValido = false
            )
        )

        val updates = mutableMapOf<String, Any>()
        updates["jugadores/$jugadorId/cartas"] = cartasJugador
        updates["jugadores/$propietarioEspiadoId/cartas"] = cartasEspiado
        updates["mazoDescarte"] = descarte
        updates["cartaPoderActiva"] = mapOf<String, Any>()
        updates["turnoActualId"] = siguienteTurno(jugadorId, sala)

        salasRef.child(salaId).updateChildren(updates)
    }

    // ─────────────────────────────────────────
    // ACTIVAR PODER: CAMBIAR VIENDO (As)
    // Igual que ESPIAR pero al tener la carta en mano
    // el jugador puede cambiarla por cualquier carta de cualquier juego.
    // ─────────────────────────────────────────

    fun activarPoderCambiarViendo(
        salaId: String,
        jugadorId: String,
        sala: Sala,
        cartaEnMano: Carta,
        onError: (String) -> Unit = {}
    ) {
        val descarte = sala.mazoDescarte.toMutableList()
        descarte.add(
            cartaEnMano.copy(
                descartadaPorJugadorId = jugadorId,
                descartadaDesdeJuegoMesa = false,
                comodinRobadoDelDescarteValido = false
            )
        )

        val updates = mutableMapOf<String, Any>()
        updates["mazoDescarte"] = descarte
        updates["jugadores/$jugadorId/cartaEnMano"] = mapOf<String, Any>()
        updates["cartaPoderActiva"] = mapOf(
            "jugadorId" to jugadorId,
            "tipoPoder" to TipoPoder.CAMBIAR_VIENDO.name,
            "cartaEspiandoId" to ""
        )

        updates["jugadaActual"] = mapOf(
            "jugadorId" to jugadorId,
            "tipo" to "CAMBIAR_VIENDO",
            "subaccion" to "Seleccionando carta para ver",
            "timestamp" to System.currentTimeMillis()
        )

        salasRef.child(salaId).updateChildren(updates)
    }

    // Confirmar cambio tras espiar con el As
    // cartaEspiada = la que está en mano virtual del jugador
    // cartaDestino = la que el jugador elige para intercambiar
    fun confirmarCambioViendo(
        salaId: String,
        jugadorId: String,
        sala: Sala,
        jugadorAId: String,
        cartaAId: String,
        jugadorBId: String,
        cartaBId: String,
        onError: (String) -> Unit = {}
    ) {
        val updates = construirUpdatesIntercambioPorId(
            sala = sala,
            jugadorAId = jugadorAId,
            cartaAId = cartaAId,
            jugadorBId = jugadorBId,
            cartaBId = cartaBId,
            onError = onError
        ) ?: return

        updates["cartaPoderActiva"] = mapOf<String, Any>()
        updates["jugadaActual"] = mapOf<String, Any>()
        updates["turnoActualId"] = siguienteTurno(jugadorId, sala)

        salasRef.child(salaId).updateChildren(updates)
    }

    // ─────────────────────────────────────────
    // ACTIVAR PODER: CAMBIAR SIN VER (J, Q)
    // La carta va al descarte en estado ACTIVA_DESCARTE.
    // El jugador elige dos cartas de cualquier juego para intercambiarlas.
    // ─────────────────────────────────────────

    fun activarPoderCambiarSinVer(
        salaId: String,
        jugadorId: String,
        sala: Sala,
        cartaEnMano: Carta,
        onError: (String) -> Unit = {}
    ) {
        val descarte = sala.mazoDescarte.toMutableList()
        descarte.add(
            cartaEnMano.copy(
                descartadaPorJugadorId = jugadorId,
                descartadaDesdeJuegoMesa = false,
                comodinRobadoDelDescarteValido = false
            )
        )

        val updates = mutableMapOf<String, Any>()
        updates["mazoDescarte"] = descarte
        updates["jugadores/$jugadorId/cartaEnMano"] = mapOf<String, Any>()
        updates["cartaPoderActiva"] = mapOf(
            "jugadorId" to jugadorId,
            "tipoPoder" to TipoPoder.CAMBIAR_SIN_VER.name,
            "cartaEspiandoId" to ""
        )

        updates["jugadaActual"] = mapOf(
            "jugadorId" to jugadorId,
            "tipo" to "CAMBIAR_SIN_VER",
            "subaccion" to "Seleccionando dos cartas",
            "timestamp" to System.currentTimeMillis()
        )

        salasRef.child(salaId).updateChildren(updates)
    }

    // Confirmar intercambio sin ver: dos cartas elegidas de cualquier juego
    fun confirmarCambioSinVer(
        salaId: String,
        jugadorId: String,
        sala: Sala,
        jugadorAId: String,
        cartaAId: String,
        jugadorBId: String,
        cartaBId: String,
        onError: (String) -> Unit = {}
    ) {
        val updates = construirUpdatesIntercambioPorId(
            sala = sala,
            jugadorAId = jugadorAId,
            cartaAId = cartaAId,
            jugadorBId = jugadorBId,
            cartaBId = cartaBId,
            onError = onError
        ) ?: return

        updates["cartaPoderActiva"] = mapOf<String, Any>()
        updates["jugadaActual"] = mapOf<String, Any>()
        updates["turnoActualId"] = siguienteTurno(jugadorId, sala)

        salasRef.child(salaId).updateChildren(updates)
    }

    fun resolverCartaEnMesaPorId(
        sala: Sala,
        propietarioId: String,
        cartaId: String
    ): CartaEnMesa? {
        val cartas = sala.jugadores[propietarioId]
            ?.cartas
            ?.mesaNormalizadaACuatroCasillas()
            ?: return null

        val posicion = cartas.indexOfFirst { it.id == cartaId && !it.esSlotVacio() }

        if (posicion < 0) return null

        return CartaEnMesa(
            carta = cartas[posicion],
            posicion = posicion,
            propietarioId = propietarioId
        )
    }

    // ─────────────────────────────────────────
    // SELECCIONAR VALOR DEL COMODÍN
    // El comodín cambia su valor y palo al elegido.
    // Termina el turno. Todos pueden descartar ese valor.
    // ─────────────────────────────────────────

    fun seleccionarValorComodin(
        salaId: String,
        jugadorId: String,
        sala: Sala,
        comodin: Carta,
        valorElegido: String,
        paloElegido: String,
        onError: (String) -> Unit = {}
    ) {
        val cartaTransformada = comodin.copy(
            valor = valorElegido,
            palo = paloElegido,
            descartadaPorJugadorId = jugadorId,
            descartadaDesdeJuegoMesa = false,
            comodinRobadoDelDescarteValido = false
        )

        val descarte = sala.mazoDescarte.toMutableList()
        descarte.add(cartaTransformada)

        val updates = mutableMapOf<String, Any>()
        updates["mazoDescarte"] = descarte
        updates["jugadores/$jugadorId/cartaEnMano"] = mapOf<String, Any>()
        updates["turnoActualId"] = siguienteTurno(jugadorId, sala)

        salasRef.child(salaId).updateChildren(updates)
    }



    // ─────────────────────────────────────────
    // DESCARTE ESPONTÁNEO
    // Un jugador descarta una carta de su juego si es
    // igual a la carta en la cima del descarte.
    // Puede ocurrir en cualquier momento (no solo en su turno).
    // ─────────────────────────────────────────

    fun descartarEspontaneo(
        salaId: String,
        jugadorId: String,
        sala: Sala,
        posicionCarta: Int,
        onError: (String) -> Unit = {}
    ) {
        val jugador = sala.jugadores[jugadorId] ?: run {
            onError("Jugador no encontrado")
            return
        }

        val ultimaDescarte = sala.mazoDescarte.lastOrNull() ?: run {
            onError("No hay carta en el descarte")
            return
        }

        val cartaADescartar = jugador.cartas.mesaNormalizadaACuatroCasillas().getOrNull(posicionCarta) ?: run {
            onError("Posición inválida")
            return
        }
        if (cartaADescartar.esSlotVacio()) {
            onError("Casilla vacía")
            return
        }

        // Validar que coincide con la cima del descarte
        if (cartaADescartar.valor != ultimaDescarte.valor) {
            onError("La carta no coincide con la cima del descarte")
            return
        }

        val cartasActualizadas = jugador.cartas.mesaNormalizadaACuatroCasillas()
        cartasActualizadas[posicionCarta] = MesaSlots.VACIA

        val descarte = sala.mazoDescarte.toMutableList()
        descarte.add(
            cartaADescartar.copy(
                descartadaPorJugadorId = jugadorId,
                descartadaDesdeJuegoMesa = true,
                comodinRobadoDelDescarteValido = false
            )
        )

        val updates = mutableMapOf<String, Any>()
        updates["jugadores/$jugadorId/cartas"] = cartasActualizadas
        updates["mazoDescarte"] = descarte

        salasRef.child(salaId).updateChildren(updates)
    }

    // ─────────────────────────────────────────
    // CASTIGO: descarte espontáneo incorrecto
    // La carta en mesa no se mueve; se roba una carta del pozo y se coloca
    // en la primera casilla vacía siguiendo el orden visual de la mesa.
    // ─────────────────────────────────────────

    fun castigoDescarteEspontaneoIncorrecto(
        salaId: String,
        jugadorId: String,
        sala: Sala,
        onError: (String) -> Unit = {}
    ) {
        val jugador = sala.jugadores[jugadorId] ?: run {
            onError("Jugador no encontrado")
            return
        }

        val mesa = jugador.cartas.mesaNormalizadaACuatroCasillas()
        val hueco = primeraCasillaVaciaOrdenVisual(mesa) ?: run {
            onError("No hay casilla libre para la carta de castigo")
            return
        }

        val pozo = sala.mazoRobar.toMutableList()
        if (pozo.isEmpty()) {
            onError("El pozo de robo está vacío")
            return
        }

        val castigo = pozo.removeAt(0).limpiarMetaDescarteRobo().copy(abierta = false)
        mesa[hueco] = castigo

        val updates = mutableMapOf<String, Any>()
        updates["mazoRobar"] = pozo
        updates["jugadores/$jugadorId/cartas"] = mesa

        salasRef.child(salaId).updateChildren(updates)
    }

    // ─────────────────────────────────────────
    // BRATA
    // El jugador presiona BRATA en su turno.
    // Todos tienen una última vuelta.
    // Al volver al jugador que presionó, se evalúan las manos.
    // ─────────────────────────────────────────

    fun presionarBrata(
        salaId: String,
        jugadorId: String,
        sala: Sala
    ) {
        val updates = mutableMapOf<String, Any>()
        updates["brataActivada"] = true
        updates["brataJugadorId"] = jugadorId
        updates["turnoActualId"] = siguienteTurno(jugadorId, sala)

        salasRef.child(salaId).updateChildren(updates)
    }

    // ─────────────────────────────────────────
    // REGLA DEL ADELANTADO
    // Cuando un jugador se adelanta durante un poder espía.
    // ─────────────────────────────────────────

    fun robarJuegoDelAdelantado(
        salaId: String,
        jugadorId: String,        // el del turno (damnificado)
        adelantadoId: String,     // el que se adelantó
        sala: Sala,
        posicionADescartar: Int,  // carta del damnificado que quiere descartar al adelantado
        onError: (String) -> Unit = {}
    ) {
        val jugador = sala.jugadores[jugadorId] ?: run { onError("Jugador no encontrado"); return }
        val cartasJugador = jugador.cartas.mesaNormalizadaACuatroCasillas()
        val cartaADescartar = cartasJugador.getOrNull(posicionADescartar) ?: run {
            onError("Posición inválida"); return
        }
        if (cartaADescartar.esSlotVacio()) {
            onError("Casilla vacía"); return
        }

        cartasJugador[posicionADescartar] = MesaSlots.VACIA

        val adelantado = sala.jugadores[adelantadoId] ?: run { onError("Adelantado no encontrado"); return }
        val cartasAdelantado = adelantado.cartas.mesaNormalizadaACuatroCasillas()
        val hueco = (0..3).firstOrNull { cartasAdelantado[it].esSlotVacio() }
        if (hueco == null) {
            onError("El juego del adelantado no tiene casilla libre")
            return
        }
        cartasAdelantado[hueco] = cartaADescartar

        val updates = mutableMapOf<String, Any>()
        updates["jugadores/$jugadorId/cartas"] = cartasJugador
        updates["jugadores/$adelantadoId/cartas"] = cartasAdelantado
        updates["cartaPoderActiva"] = mapOf<String, Any>() // limpiar poder activo

        salasRef.child(salaId).updateChildren(updates)
    }

    fun perdonarAdelantado(salaId: String) {
        // Solo limpia el flag de adelantado; el jugador continúa su turno
        salasRef.child(salaId).child("adelantadoId").removeValue()
    }

    // ─────────────────────────────────────────
    // RECICLAR DESCARTE → POZO
    // Cuando el pozo se agota: el descarte (menos la carta superior)
    // se mezcla y pasa a ser el nuevo pozo.
    // ─────────────────────────────────────────

    fun reciclarDescarte(
        salaId: String,
        sala: Sala,
        onError: (String) -> Unit = {}
    ) {
        val descarte = sala.mazoDescarte.toMutableList()
        if (descarte.size < 2) {
            onError("No hay suficientes cartas para reciclar")
            return
        }

        // La carta superior se queda en el descarte
        val cartaSuperior = descarte.removeAt(descarte.lastIndex).limpiarMetaDescarteRobo()

        // El resto se mezcla y pasa al pozo
        val nuevoPozoMezclado = descarte.map { it.limpiarMetaDescarteRobo() }.shuffled()

        val updates = mutableMapOf<String, Any>()
        updates["mazoRobar"] = nuevoPozoMezclado
        updates["mazoDescarte"] = listOf(cartaSuperior)

        salasRef.child(salaId).updateChildren(updates)
    }

    // ─────────────────────────────────────────
    // HELPERS PRIVADOS
    // ─────────────────────────────────────────

    // Calcula el ID del siguiente jugador en sentido horario
    private fun siguienteTurno(jugadorActualId: String, sala: Sala): String {
        val jugadores = sala.jugadores.keys.toList()
        if (jugadores.isEmpty()) return ""
        val indexActual = jugadores.indexOf(jugadorActualId)
        val indexSiguiente = (indexActual + 1) % jugadores.size
        return jugadores[indexSiguiente]
    }

    // Construye el mapa de actualizaciones para un intercambio de dos cartas
    // entre cualquier combinación de jugadores
    private fun construirUpdatesIntercambioPorId(
        sala: Sala,
        jugadorAId: String,
        cartaAId: String,
        jugadorBId: String,
        cartaBId: String,
        onError: (String) -> Unit = {}
    ): MutableMap<String, Any>? {
        val cartaA = resolverCartaEnMesaPorId(
            sala = sala,
            propietarioId = jugadorAId,
            cartaId = cartaAId
        ) ?: run {
            onError("Carta A no encontrada para el intercambio")
            return null
        }

        val cartaB = resolverCartaEnMesaPorId(
            sala = sala,
            propietarioId = jugadorBId,
            cartaId = cartaBId
        ) ?: run {
            onError("Carta B no encontrada para el intercambio")
            return null
        }

        if (cartaA.carta.id == cartaB.carta.id) {
            onError("No puedes intercambiar una carta consigo misma")
            return null
        }

        val updates = mutableMapOf<String, Any>()

        if (jugadorAId == jugadorBId) {
            val cartas = sala.jugadores[jugadorAId]
                ?.cartas
                ?.mesaNormalizadaACuatroCasillas()
                ?: run {
                    onError("Jugador no encontrado")
                    return null
                }

            cartas[cartaA.posicion] = cartaB.carta
            cartas[cartaB.posicion] = cartaA.carta

            updates["jugadores/$jugadorAId/cartas"] = cartas
        } else {
            val cartasA = sala.jugadores[jugadorAId]
                ?.cartas
                ?.mesaNormalizadaACuatroCasillas()
                ?: run {
                    onError("Jugador A no encontrado")
                    return null
                }

            val cartasB = sala.jugadores[jugadorBId]
                ?.cartas
                ?.mesaNormalizadaACuatroCasillas()
                ?: run {
                    onError("Jugador B no encontrado")
                    return null
                }

            cartasA[cartaA.posicion] = cartaB.carta
            cartasB[cartaB.posicion] = cartaA.carta

            updates["jugadores/$jugadorAId/cartas"] = cartasA
            updates["jugadores/$jugadorBId/cartas"] = cartasB
        }

        return updates
    }

    fun iniciarAnimacionSwap(
        salaId: String,
        ejecutorId: String,
        cartaA: CartaEnMesa,
        cartaB: CartaEnMesa,
        mostrarCartaA: Boolean = false,
        mostrarCartaB: Boolean = false,
        onListo: () -> Unit = {}
    ) {
        val updates = mutableMapOf<String, Any?>()

        updates["swapAnimando/ejecutorId"] = ejecutorId

        updates["swapAnimando/jugadorAId"] = cartaA.propietarioId
        updates["swapAnimando/cartaAId"] = cartaA.carta.id
        updates["swapAnimando/valorA"] = cartaA.carta.valor

        updates["swapAnimando/jugadorBId"] = cartaB.propietarioId
        updates["swapAnimando/cartaBId"] = cartaB.carta.id
        updates["swapAnimando/valorB"] = cartaB.carta.valor

        updates["swapAnimando/timestampInicio"] = System.currentTimeMillis()
        updates["swapAnimando/mostrarCartaA"] = mostrarCartaA
        updates["swapAnimando/mostrarCartaB"] = mostrarCartaB

        salasRef.child(salaId).updateChildren(updates)
            .addOnSuccessListener { onListo() }
    }

    fun limpiarAnimacionSwap(salaId: String) {
        salasRef.child(salaId).child("swapAnimando").removeValue()
    }

    fun confirmarCambioPropioAnimado(
        salaId: String,
        jugadorId: String,
        sala: Sala,
        posicionDestino: Int,
        cartaEnMano: Carta,
        animacionId: String,
        onError: (String) -> Unit = {}
    ) {
        val jugador = sala.jugadores[jugadorId] ?: run {
            onError("Jugador no encontrado")
            return
        }

        if (posicionDestino !in 0..3) {
            onError("Posición inválida")
            return
        }

        val animacionActual = sala.cambioPropioAnimando
        if (animacionActual == null || animacionActual.id != animacionId) {
            onError("La animación de cambio ya no está activa")
            return
        }

        val cartasActuales = jugador.cartas.mesaNormalizadaACuatroCasillas()
        val cartaDesplazada = cartasActuales[posicionDestino]

        cartasActuales[posicionDestino] = cartaEnMano

        val descarte = sala.mazoDescarte.toMutableList()

        if (!cartaDesplazada.esSlotVacio()) {
            descarte.add(
                cartaDesplazada.copy(
                    descartadaPorJugadorId = jugadorId,
                    descartadaDesdeJuegoMesa = true,
                    comodinRobadoDelDescarteValido = false
                )
            )
        }

        val updates = mutableMapOf<String, Any?>()

        updates["jugadores/$jugadorId/cartas"] = cartasActuales
        updates["jugadores/$jugadorId/cartaEnMano"] = mapOf<String, Any>()
        updates["mazoDescarte"] = descarte
        updates["turnoActualId"] = siguienteTurno(jugadorId, sala)
        updates["cambioPropioAnimando"] = null
        updates["jugadaActual"] = mapOf<String, Any>()

        salasRef.child(salaId).updateChildren(updates)
    }

    fun iniciarAnimacionCambioPropio(
        salaId: String,
        ejecutorId: String,
        cartaSeleccionada: CartaEnMesa,
        cartaEnMano: Carta,
        onListo: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        if (cartaSeleccionada.propietarioId != ejecutorId) {
            onError("Solo puedes cambiar una carta de tu propio juego")
            return
        }

        if (cartaSeleccionada.posicion !in 0..3) {
            onError("Posición inválida")
            return
        }

        val animId = java.util.UUID.randomUUID().toString()
        val ahora = System.currentTimeMillis()

        val updates = mutableMapOf<String, Any?>()

        updates["cambioPropioAnimando/id"] = animId
        updates["cambioPropioAnimando/ejecutorId"] = ejecutorId
        updates["cambioPropioAnimando/jugadorId"] = cartaSeleccionada.propietarioId
        updates["cambioPropioAnimando/cartaId"] = cartaSeleccionada.carta.id
        updates["cambioPropioAnimando/posicion"] = cartaSeleccionada.posicion
        updates["cambioPropioAnimando/cartaEnManoId"] = cartaEnMano.id
        updates["cambioPropioAnimando/timestampInicio"] = ahora
        updates["cambioPropioAnimando/duracionSaltoMs"] = 2000L
        updates["cambioPropioAnimando/duracionViajeMs"] = 750L

        updates["jugadaActual"] = mapOf(
            "jugadorId" to ejecutorId,
            "tipo" to "CAMBIO_CARTA_PROPIA",
            "subaccion" to "Descartando carta propia",
            "timestamp" to ahora
        )

        salasRef.child(salaId).updateChildren(updates)
            .addOnSuccessListener { onListo() }
            .addOnFailureListener { error ->
                onError(error.message ?: "No se pudo iniciar la animación")
            }
    }
}

private fun Carta.limpiarMetaDescarteRobo(): Carta =
    copy(
        descartadaPorJugadorId = "",
        descartadaDesdeJuegoMesa = false,
        comodinRobadoDelDescarteValido = false
    )

private fun Carta.paraManoTrasRobarDelDescarte(): Carta =
    copy(
        descartadaPorJugadorId = "",
        descartadaDesdeJuegoMesa = false,
        comodinRobadoDelDescarteValido = valor == "JKR"
    )
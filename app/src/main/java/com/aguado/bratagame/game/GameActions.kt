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
import com.aguado.bratagame.CadenaDescarte
import java.util.UUID
import com.aguado.bratagame.AdelantadoPendiente
import com.aguado.bratagame.CartaPoderActiva
import com.aguado.bratagame.Jugador
import com.aguado.bratagame.VoyPendiente
import com.google.firebase.database.Transaction
import com.google.firebase.database.MutableData
import com.google.firebase.database.DatabaseError
import com.aguado.bratagame.DescarteEspontaneoAnimando

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

    private fun jugadorPuedeActuar(
        sala: Sala,
        jugadorId: String,
        onError: (String) -> Unit
    ): Boolean {
        val jugador = sala.jugadores[jugadorId] ?: run {
            onError("Jugador no encontrado")
            return false
        }

        if (jugador.descalificado) {
            onError("Jugador descalificado")
            return false
        }

        return true
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

    fun limpiarAnimacionDescarteEspontaneo(
        salaId: String
    ) {
        val updates = mutableMapOf<String, Any?>()
        updates["descarteEspontaneoAnimando"] = mapOf<String, Any>()

        salasRef.child(salaId).updateChildren(updates)
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
        if (!jugadorPuedeActuar(sala, jugadorId, onError)) return

        val mazo = sala.mazoRobar.toMutableList()
        if (mazo.isEmpty()) {
            onError("El pozo de robo está vacío")
            return
        }

        val cartaRobada = mazo.removeAt(0).paraManoTrasRobarDelPozo()
        val updates = mutableMapOf<String, Any?>()

        // Quitar carta del pozo
        updates["mazoRobar"] = mazo

        // Registrar carta en mano del jugador
        updates["jugadores/$jugadorId/cartaEnMano"] = cartaRobada

        romperCadenaSiJugadorEsperado(
            updates = updates,
            sala = sala,
            jugadorId = jugadorId
        )

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
        if (!jugadorPuedeActuar(sala, jugadorId, onError)) return

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
        val updates = mutableMapOf<String, Any?>()

        updates["mazoDescarte"] = descarte
        updates["jugadores/$jugadorId/cartaEnMano"] = paraMano

        romperCadenaSiJugadorEsperado(
            updates = updates,
            sala = sala,
            jugadorId = jugadorId
        )

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
        if (!jugadorPuedeActuar(sala, jugadorId, onError)) return

        val descarte = sala.mazoDescarte.toMutableList()
        descarte.add(
            cartaEnMano.copy(
                descartadaPorJugadorId = jugadorId,
                descartadaDesdeJuegoMesa = false,
                comodinRobadoDelDescarteValido = false,
                origenRobo = ""
            )
        )

        val updates = mutableMapOf<String, Any?>()
        updates["mazoDescarte"] = descarte
        updates["jugadores/$jugadorId/cartaEnMano"] = mapOf<String, Any>()
        updates["jugadaActual"] = mapOf<String, Any>()

        aplicarAvancePorDescarteEncadenado(
            updates = updates,
            sala = sala,
            jugadorId = jugadorId,
            valorDescartado = cartaEnMano.valor
        )

        limpiarAdelantadoPendienteSiPerteneceA(
            updates = updates,
            sala = sala,
            jugadorId = jugadorId
        )

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
        cartasActuales[posicionDestino] = cartaEnMano.limpiarMetaDescarteRobo()

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
        updates["jugadaActual"] = mapOf<String, Any>()

        if (!cartaDesplazada.esSlotVacio()) {
            aplicarAvancePorDescarteEncadenado(
                updates = updates,
                sala = sala,
                jugadorId = jugadorId,
                valorDescartado = cartaDesplazada.valor
            )
        } else {
            updates["turnoActualId"] = siguienteTurno(jugadorId, sala)
            updates["cadenaDescarte"] = mapOf<String, Any>()
        }

        limpiarAdelantadoPendienteSiPerteneceA(
            updates = updates,
            sala = sala,
            jugadorId = jugadorId
        )

        salasRef.child(salaId).updateChildren(updates)
    }

    fun tomarCartaComoNuevaDeJuego(
        salaId: String,
        jugadorId: String,
        sala: Sala,
        cartaEnMano: Carta,
        onError: (String) -> Unit = {}
    ) {
        val jugador = sala.jugadores[jugadorId] ?: run {
            onError("Jugador no encontrado")
            return
        }

        val mesa = jugador.cartas.mesaNormalizadaACuatroCasillas()

        val jugadorSinCartas = mesa.all { it.esSlotVacio() }

        if (!jugadorSinCartas) {
            onError("Esta acción solo aplica cuando el jugador no tiene cartas")
            return
        }

        val posicionDestino = primeraCasillaVaciaOrdenVisual(mesa) ?: run {
            onError("No hay espacio disponible para tomar la carta")
            return
        }

        mesa[posicionDestino] = cartaEnMano
            .limpiarMetaDescarteRobo()
            .copy(abierta = false)

        val updates = mutableMapOf<String, Any?>()

        updates["jugadores/$jugadorId/cartas"] = mesa
        updates["jugadores/$jugadorId/cartaEnMano"] = mapOf<String, Any>()
        updates["jugadaActual"] = mapOf<String, Any>()

        // No es descarte, no activa poder y no encadena.
        // Solo coloca la carta en el juego y termina el turno.
        updates["turnoActualId"] = siguienteTurno(jugadorId, sala)

        // Si había una cadena esperando a este jugador y decidió robar/tomar,
        // esa cadena se rompe.
        updates["cadenaDescarte"] = mapOf<String, Any>()

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
                comodinRobadoDelDescarteValido = false,
                origenRobo = ""
            )
        )

        val updates = mutableMapOf<String, Any?>()
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

        val updates = mutableMapOf<String, Any?>()
        updates["jugadores/$jugadorId/cartas"] = cartasActuales
        updates["mazoDescarte"] = descarte
        updates["cartaPoderActiva"] = mapOf<String, Any>()
        updates["jugadaActual"] = mapOf<String, Any>()

        aplicarAvancePorDescarteEncadenado(
            updates = updates,
            sala = sala,
            jugadorId = jugadorId,
            valorDescartado = cartaDescartada.valor
        )

        limpiarAdelantadoPendienteSiPerteneceA(
            updates = updates,
            sala = sala,
            jugadorId = jugadorId
        )

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
                comodinRobadoDelDescarteValido = false,
                origenRobo = ""
            )
        )

        val updates = mutableMapOf<String, Any?>()
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
        val updates = mutableMapOf<String, Any?>()

        updates["cartaPoderActiva/cartaEspiandoId"] = cartaId
        updates["adelantadoPendiente"] = mapOf<String, Any>()
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
        val updates = mutableMapOf<String, Any?>()

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
        val updates = mutableMapOf<String, Any?>()
        updates["cartaPoderActiva"] = mapOf<String, Any>()
        updates["jugadaActual"] = mapOf<String, Any>()

        aplicarAvancePorCimaActualDelDescarte(
            updates = updates,
            sala = sala,
            jugadorId = jugadorId
        )

        limpiarAdelantadoPendienteSiPerteneceA(
            updates = updates,
            sala = sala,
            jugadorId = jugadorId
        )

        salasRef.child(salaId).updateChildren(updates)
    }

    fun descartarCartaEspiadaPropia(
        salaId: String,
        jugadorId: String,
        sala: Sala,
        cartaEspiada: Carta,
        onError: (String) -> Unit = {}
    ) {
        val jugador = sala.jugadores[jugadorId] ?: run {
            onError("Jugador no encontrado")
            return
        }

        val cartasJugador = jugador.cartas.mesaNormalizadaACuatroCasillas()
        val posicionEspiada = cartasJugador.indexOfFirst { it.id == cartaEspiada.id }

        if (posicionEspiada < 0) {
            onError("Carta espiada no encontrada en tu juego")
            return
        }

        val cartaADescartar = cartasJugador[posicionEspiada]

        if (cartaADescartar.esSlotVacio()) {
            onError("La carta espiada ya no está disponible")
            return
        }

        cartasJugador[posicionEspiada] = MesaSlots.VACIA

        val descarte = sala.mazoDescarte.toMutableList()
        descarte.add(
            cartaADescartar.copy(
                descartadaPorJugadorId = jugadorId,
                descartadaDesdeJuegoMesa = true,
                comodinRobadoDelDescarteValido = false
            )
        )

        val updates = mutableMapOf<String, Any?>()
        updates["jugadores/$jugadorId/cartas"] = cartasJugador
        updates["mazoDescarte"] = descarte
        updates["cartaPoderActiva"] = mapOf<String, Any>()
        updates["jugadaActual"] = mapOf<String, Any>()

        aplicarAvancePorDescarteEncadenado(
            updates = updates,
            sala = sala,
            jugadorId = jugadorId,
            valorDescartado = cartaADescartar.valor
        )

        salasRef.child(salaId).updateChildren(updates)
    }

    // Descarta la carta espiada (mismo valor que la activadora)
    // y el jugador elige una carta propia para dar al espiado
    fun descartarCartaEspiada(
        salaId: String,
        jugadorId: String,
        sala: Sala,
        cartaEspiada: Carta,
        propietarioEspiadoId: String,
        posicionCartaPropia: Int,
        onError: (String) -> Unit = {}
    ) {
        if (propietarioEspiadoId == jugadorId) {
            descartarCartaEspiadaPropia(
                salaId = salaId,
                jugadorId = jugadorId,
                sala = sala,
                cartaEspiada = cartaEspiada,
                onError = onError
            )
            return
        }

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

        val updates = mutableMapOf<String, Any?>()
        updates["jugadores/$jugadorId/cartas"] = cartasJugador
        updates["jugadores/$propietarioEspiadoId/cartas"] = cartasEspiado
        updates["mazoDescarte"] = descarte
        updates["cartaPoderActiva"] = mapOf<String, Any>()
        updates["jugadaActual"] = mapOf<String, Any>()

        aplicarAvancePorDescarteEncadenado(
            updates = updates,
            sala = sala,
            jugadorId = jugadorId,
            valorDescartado = cartaEspiada.valor
        )

        limpiarAdelantadoPendienteSiPerteneceA(
            updates = updates,
            sala = sala,
            jugadorId = jugadorId
        )

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
                comodinRobadoDelDescarteValido = false,
                origenRobo = ""
            )
        )

        val updates = mutableMapOf<String, Any?>()
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

        aplicarAvancePorCimaActualDelDescarte(
            updates = updates,
            sala = sala,
            jugadorId = jugadorId
        )

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
                comodinRobadoDelDescarteValido = false,
                origenRobo = ""
            )
        )

        val updates = mutableMapOf<String, Any?>()
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

        aplicarAvancePorCimaActualDelDescarte(
            updates = updates,
            sala = sala,
            jugadorId = jugadorId
        )

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
        val valorFinal = valorElegido.uppercase()

        val paloFinal = when {
            valorFinal == "JKR" -> {
                "comodin"
            }

            paloElegido.isNotBlank() -> {
                paloElegido
            }

            else -> {
                "corazones"
            }
        }

        val cartaTransformada = comodin.copy(
            valor = valorFinal,
            palo = paloFinal,
            descartadaPorJugadorId = jugadorId,
            descartadaDesdeJuegoMesa = false,
            comodinRobadoDelDescarteValido = false
        )

        val descarte = sala.mazoDescarte.toMutableList()
        descarte.add(cartaTransformada)

        val updates = mutableMapOf<String, Any?>()
        updates["mazoDescarte"] = descarte
        updates["jugadores/$jugadorId/cartaEnMano"] = mapOf<String, Any>()

        aplicarAvancePorDescarteEncadenado(
            updates = updates,
            sala = sala,
            jugadorId = jugadorId,
            valorDescartado = cartaTransformada.valor
        )

        limpiarAdelantadoPendienteSiPerteneceA(
            updates = updates,
            sala = sala,
            jugadorId = jugadorId
        )

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
        if (!jugadorPuedeActuar(sala, jugadorId, onError)) return

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

// Este es el índice real donde entra la carta.
// Si después otro jugador descarta encima, esta carta queda debajo,
// pero conserva su lugar correcto.
        val indiceDescarteInsertado = descarte.size

        val cartaDescartada = cartaADescartar.copy(
            descartadaPorJugadorId = jugadorId,
            descartadaDesdeJuegoMesa = true,
            comodinRobadoDelDescarteValido = false
        )

        descarte.add(cartaDescartada)

        val animId = java.util.UUID.randomUUID().toString()
        val ahora = System.currentTimeMillis()

        val updates = mutableMapOf<String, Any?>()

        updates["jugadores/$jugadorId/cartas"] = cartasActualizadas
        updates["mazoDescarte"] = descarte

        updates["descarteEspontaneoAnimando"] = DescarteEspontaneoAnimando(
            id = animId,
            ejecutorId = jugadorId,
            jugadorId = jugadorId,
            posicion = posicionCarta,
            cartaId = cartaADescartar.id,
            valor = cartaADescartar.valor,
            palo = cartaADescartar.palo,
            indiceDescarte = indiceDescarteInsertado,
            timestampInicio = ahora,
            duracionViajeMs = 650L,
            duracionReboteMs = 450L
        )

        aplicarDescarteEspontaneoConPoliticaDeCadena(
            updates = updates,
            sala = sala,
            jugadorId = jugadorId,
            valorDescartado = cartaADescartar.valor
        )

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

        if (jugador.descalificado) {
            onError("El jugador ya está descalificado")
            return
        }

        val mesa = jugador.cartas.mesaNormalizadaACuatroCasillas()
        val hueco = primeraCasillaVaciaOrdenVisual(mesa)

        val updates = mutableMapOf<String, Any?>()

        if (hueco != null) {
            val pozo = sala.mazoRobar.toMutableList()

            if (pozo.isEmpty()) {
                onError("El pozo de robo está vacío")
                return
            }

            val castigo = pozo
                .removeAt(0)
                .limpiarMetaDescarteRobo()
                .copy(abierta = false)

            mesa[hueco] = castigo

            // Si el castigo sí pudo aplicarse en un slot vacío,
            // NO se suma error.
            updates["mazoRobar"] = pozo
            updates["jugadores/$jugadorId/cartas"] = mesa
        } else {
            // No hay espacio dentro de las 4 cartas principales.
            // No se roba carta. Ahora sí cuenta como error.
            val nuevosErrores = (jugador.erroresDescarte + 1).coerceAtMost(3)
            val quedaDescalificado = nuevosErrores >= 3

            updates["jugadores/$jugadorId/erroresDescarte"] = nuevosErrores
            updates["jugadores/$jugadorId/descalificado"] = quedaDescalificado

            if (quedaDescalificado && sala.turnoActualId == jugadorId) {
                updates["turnoActualId"] = siguienteTurno(jugadorId, sala)
            }

            onError(
                if (quedaDescalificado) {
                    "Tercer error: jugador descalificado"
                } else {
                    "Error registrado: $nuevosErrores de 3"
                }
            )
        }

        salasRef.child(salaId).updateChildren(updates)
    }

    // ─────────────────────────────────────────
    // BRATA
    // El jugador presiona BRATA en su turno.
    // Todos tienen una última vuelta.
    // Al volver al jugador que presionó, se evalúan las manos.
    // ─────────────────────────────────────────


    fun pasarTurnoBrata(
        salaId: String,
        jugadorId: String,
        sala: Sala,
        onError: (String) -> Unit = {}
    ) {
        if (!sala.brataActivada) {
            onError("BRATA no está activada")
            return
        }

        if (sala.turnoActualId != jugadorId) {
            onError("No es tu turno")
            return
        }

        if (sala.brataJugadorId == jugadorId) {
            onError("El jugador que presionó BRATA no puede pasar")
            return
        }

        val jugador = sala.jugadores[jugadorId] ?: run {
            onError("Jugador no encontrado")
            return
        }

        if (jugador.descalificado) {
            onError("Jugador descalificado")
            return
        }

        if (jugador.cartaEnMano != null) {
            onError("No puedes pasar con una carta en mano")
            return
        }

        val updates = mutableMapOf<String, Any?>()

        updates["jugadaActual"] = mapOf(
            "jugadorId" to jugadorId,
            "tipo" to "PASO_BRATA",
            "subaccion" to "Pasó su turno final",
            "timestamp" to System.currentTimeMillis()
        )

        updates["turnoActualId"] = siguienteTurno(jugadorId, sala)

        salasRef.child(salaId).updateChildren(updates)
    }

    fun presionarBrata(
        salaId: String,
        jugadorId: String,
        sala: Sala
    ) {
        val jugador = sala.jugadores[jugadorId] ?: return
        if (jugador.descalificado) return

        val updates = mutableMapOf<String, Any?>()
        updates["brataActivada"] = true
        updates["brataJugadorId"] = jugadorId
        updates["turnoActualId"] = siguienteTurno(jugadorId, sala)

        romperCadenaSiJugadorEsperado(
            updates = updates,
            sala = sala,
            jugadorId = jugadorId
        )

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

        val updates = mutableMapOf<String, Any?>()
        updates["jugadores/$jugadorId/cartas"] = cartasJugador
        updates["jugadores/$adelantadoId/cartas"] = cartasAdelantado
        updates["cartaPoderActiva"] = mapOf<String, Any>() // limpiar poder activo

        salasRef.child(salaId).updateChildren(updates)
    }

    fun resolverRobarDescartePorAdelantado(
        salaId: String,
        jugadorId: String,
        sala: Sala,
        posicionCartaPropia: Int,
        onError: (String) -> Unit = {}
    ) {
        val adelanto = sala.adelantadoPendiente ?: run {
            onError("No hay adelantado pendiente")
            return
        }

        if (!adelanto.activo || adelanto.jugadorPerjudicadoId != jugadorId) {
            onError("No puedes aplicar esta acción")
            return
        }

        val jugadorPerjudicado = sala.jugadores[jugadorId] ?: run {
            onError("Jugador no encontrado")
            return
        }

        val jugadorAdelantado = sala.jugadores[adelanto.jugadorAdelantadoId] ?: run {
            onError("Jugador adelantado no encontrado")
            return
        }

        if (posicionCartaPropia !in 0..3) {
            onError("Posición inválida")
            return
        }

        if (adelanto.posicionAdelantada !in 0..3) {
            onError("Posición del adelantado inválida")
            return
        }

        val cartasPerjudicado = jugadorPerjudicado.cartas.mesaNormalizadaACuatroCasillas()
        val cartaParaEntregar = cartasPerjudicado[posicionCartaPropia]

        if (cartaParaEntregar.esSlotVacio()) {
            onError("No puedes entregar una casilla vacía")
            return
        }

        val cartasAdelantado = jugadorAdelantado.cartas.mesaNormalizadaACuatroCasillas()

        // El espacio del adelantado debería estar vacío porque de ahí salió la carta descartada.
        // Si por alguna razón no está vacío, evitamos pisar una carta existente.
        if (!cartasAdelantado[adelanto.posicionAdelantada].esSlotVacio()) {
            onError("El espacio del adelantado ya no está vacío")
            return
        }

        cartasPerjudicado[posicionCartaPropia] = MesaSlots.VACIA
        cartasAdelantado[adelanto.posicionAdelantada] = cartaParaEntregar.copy(abierta = false)

        val descarte = sala.mazoDescarte.toMutableList()

        // Si la carta espía todavía estaba en mano, se manda ahora al descarte.
        if (!adelanto.espiaYaEnDescarte) {
            val cartaEspia = jugadorPerjudicado.cartaEnMano ?: run {
                onError("La carta espía ya no está en mano")
                return
            }

            if (cartaEspia.id != adelanto.cartaEspiaPendienteId) {
                onError("La carta espía pendiente no coincide")
                return
            }

            descarte.add(
                cartaEspia.copy(
                    descartadaPorJugadorId = jugadorId,
                    descartadaDesdeJuegoMesa = false,
                    comodinRobadoDelDescarteValido = false
                )
            )
        }

        val updates = mutableMapOf<String, Any?>()

        updates["jugadores/$jugadorId/cartas"] = cartasPerjudicado
        updates["jugadores/$jugadorId/cartaEnMano"] = mapOf<String, Any>()
        updates["jugadores/${adelanto.jugadorAdelantadoId}/cartas"] = cartasAdelantado

        updates["mazoDescarte"] = descarte
        updates["cartaPoderActiva"] = mapOf<String, Any>()
        updates["adelantadoPendiente"] = mapOf<String, Any>()
        updates["jugadaActual"] = mapOf<String, Any>()
        updates["turnoActualId"] = siguienteTurno(jugadorId, sala)

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

        val updates = mutableMapOf<String, Any?>()
        updates["mazoRobar"] = nuevoPozoMezclado
        updates["mazoDescarte"] = listOf(cartaSuperior)

        salasRef.child(salaId).updateChildren(updates)
    }

    fun solicitarRoboDelPozoConVoy(
        salaId: String,
        jugadorId: String,
        sala: Sala,
        onError: (String) -> Unit = {}
    ) {
        val jugador = sala.jugadores[jugadorId] ?: run {
            onError("Jugador no encontrado")
            return
        }

        if (jugador.descalificado) {
            onError("Jugador descalificado")
            return
        }

        if (sala.turnoActualId != jugadorId) {
            onError("No es tu turno")
            return
        }

        if (jugador.cartaEnMano != null) {
            onError("Ya tienes una carta en mano")
            return
        }

        if (sala.voyPendiente?.activo == true) {
            onError("Hay una regla VOY pendiente")
            return
        }

        val cimaDescarte = sala.mazoDescarte.lastOrNull()

        // Si no hay descarte, no hay valor objetivo para VOY.
        // Roba normal.
        if (cimaDescarte == null) {
            robarDelPozo(
                salaId = salaId,
                jugadorId = jugadorId,
                sala = sala,
                onError = onError
            )
            return
        }

        val voy = VoyPendiente(
            activo = true,
            id = "voy_${System.currentTimeMillis()}_${jugadorId}",
            jugadorRobandoId = jugadorId,
            tipoRobo = VOY_TIPO_ROBO_POZO,
            valorObjetivo = cimaDescarte.valor,
            cartaDescarteObjetivoId = cimaDescarte.id,
            timestampInicio = System.currentTimeMillis(),
            duracionMs = VOY_DURACION_MS,
            reclamadoPorJugadorId = "",
            fase = VOY_FASE_VENTANA
        )

        val updates = mutableMapOf<String, Any?>()
        updates["voyPendiente"] = voy
        updates["jugadaActual"] = mapOf(
            "jugadorId" to jugadorId,
            "tipo" to "VOY",
            "subaccion" to "Intentando robar · oportunidad VOY",
            "timestamp" to System.currentTimeMillis()
        )

        salasRef.child(salaId).updateChildren(updates)
    }

    fun solicitarRoboDelDescarteConVoy(
        salaId: String,
        jugadorId: String,
        sala: Sala,
        onError: (String) -> Unit = {}
    ) {
        val jugador = sala.jugadores[jugadorId] ?: run {
            onError("Jugador no encontrado")
            return
        }

        if (jugador.descalificado) {
            onError("Jugador descalificado")
            return
        }

        if (sala.turnoActualId != jugadorId) {
            onError("No es tu turno")
            return
        }

        if (jugador.cartaEnMano != null) {
            onError("Ya tienes una carta en mano")
            return
        }

        if (sala.voyPendiente?.activo == true) {
            onError("Hay una regla VOY pendiente")
            return
        }

        val cimaDescarte = sala.mazoDescarte.lastOrNull() ?: run {
            onError("No hay carta en el descarte")
            return
        }

        val voy = VoyPendiente(
            activo = true,
            id = "voy_${System.currentTimeMillis()}_${jugadorId}",
            jugadorRobandoId = jugadorId,
            tipoRobo = VOY_TIPO_ROBO_DESCARTE,
            valorObjetivo = cimaDescarte.valor,
            cartaDescarteObjetivoId = cimaDescarte.id,
            timestampInicio = System.currentTimeMillis(),
            duracionMs = VOY_DURACION_MS,
            reclamadoPorJugadorId = "",
            fase = VOY_FASE_VENTANA
        )

        val updates = mutableMapOf<String, Any?>()
        updates["voyPendiente"] = voy
        updates["jugadaActual"] = mapOf(
            "jugadorId" to jugadorId,
            "tipo" to "VOY",
            "subaccion" to "Intentando robar descarte · oportunidad VOY",
            "timestamp" to System.currentTimeMillis()
        )

        salasRef.child(salaId).updateChildren(updates)
    }

    fun resolverVoySinReclamo(
        salaId: String,
        jugadorId: String,
        sala: Sala,
        voyId: String,
        onError: (String) -> Unit = {}
    ) {
        val voy = sala.voyPendiente ?: return

        if (!voy.activo) return
        if (voy.id != voyId) return
        if (voy.jugadorRobandoId != jugadorId) return
        if (voy.fase != VOY_FASE_VENTANA) return
        if (voy.reclamadoPorJugadorId.isNotBlank()) return

        val ahora = System.currentTimeMillis()
        val vencida = ahora - voy.timestampInicio >= voy.duracionMs

        if (!vencida) return

        val updates = mutableMapOf<String, Any?>()
        val mazoRobar = sala.mazoRobar.toMutableList()
        val mazoDescarte = sala.mazoDescarte.toMutableList()

        val roboOk = agregarRoboOriginalVoyAUpdates(
            updates = updates,
            sala = sala,
            voy = voy,
            mazoRobarMutable = mazoRobar,
            mazoDescarteMutable = mazoDescarte,
            onError = onError
        )

        if (!roboOk) return

        updates["voyPendiente"] = mapOf<String, Any>()
        updates["jugadaActual"] = mapOf<String, Any>()

        salasRef.child(salaId).updateChildren(updates)
    }

    fun reclamarVoy(
        salaId: String,
        jugadorId: String,
        sala: Sala,
        onResultado: (Boolean, String) -> Unit = { _, _ -> }
    ) {
        val jugador = sala.jugadores[jugadorId]

        if (jugador == null) {
            onResultado(false, "Jugador no encontrado")
            return
        }

        if (jugador.descalificado) {
            onResultado(false, "Jugador descalificado")
            return
        }

        val voyActual = sala.voyPendiente

        if (voyActual == null || !voyActual.activo) {
            onResultado(false, "No hay VOY pendiente")
            return
        }

        if (voyActual.jugadorRobandoId == jugadorId) {
            onResultado(false, "El jugador que va a robar no puede decir VOY")
            return
        }

        if (!jugadorTieneCartaParaEntregar(jugador)) {
            onResultado(false, "No tienes carta para entregar si aciertas")
            return
        }

        val voyRef = salasRef.child(salaId).child("voyPendiente")

        voyRef.runTransaction(object : Transaction.Handler {
            override fun doTransaction(currentData: MutableData): Transaction.Result {
                val voy = currentData.getValue(VoyPendiente::class.java)
                    ?: return Transaction.abort()

                if (!voy.activo) return Transaction.abort()
                if (voy.fase != VOY_FASE_VENTANA) return Transaction.abort()
                if (voy.reclamadoPorJugadorId.isNotBlank()) return Transaction.abort()
                if (voy.jugadorRobandoId == jugadorId) return Transaction.abort()

                val ahora = System.currentTimeMillis()
                if (ahora - voy.timestampInicio > voy.duracionMs) {
                    return Transaction.abort()
                }

                currentData.value = voy.copy(
                    reclamadoPorJugadorId = jugadorId,
                    fase = VOY_FASE_SELECCIONANDO_OBJETIVO
                )

                return Transaction.success(currentData)
            }

            override fun onComplete(
                error: DatabaseError?,
                committed: Boolean,
                currentData: com.google.firebase.database.DataSnapshot?
            ) {
                if (committed) {
                    salasRef.child(salaId).child("jugadaActual").setValue(
                        mapOf(
                            "jugadorId" to jugadorId,
                            "tipo" to "VOY",
                            "subaccion" to "oprimió VOY · seleccionando una carta",
                            "timestamp" to System.currentTimeMillis()
                        )
                    )

                    onResultado(true, "VOY reclamado")
                } else {
                    onResultado(false, "Otro jugador ganó VOY primero")
                }
            }
        })
    }

    fun seleccionarCartaObjetivoVoy(
        salaId: String,
        jugadorId: String,
        sala: Sala,
        propietarioObjetivoId: String,
        posicionObjetivo: Int,
        onError: (String) -> Unit = {}
    ) {
        val voy = sala.voyPendiente ?: run {
            onError("No hay VOY pendiente")
            return
        }

        if (!voy.activo || voy.fase != VOY_FASE_SELECCIONANDO_OBJETIVO) {
            onError("VOY no está esperando carta objetivo")
            return
        }

        if (voy.reclamadoPorJugadorId != jugadorId) {
            onError("No eres quien reclamó VOY")
            return
        }

        if (propietarioObjetivoId == jugadorId) {
            onError("Debes seleccionar una carta de otro jugador")
            return
        }

        if (posicionObjetivo !in 0..3) {
            onError("Posición inválida")
            return
        }

        val jugadorObjetivo = sala.jugadores[propietarioObjetivoId] ?: run {
            onError("Jugador objetivo no encontrado")
            return
        }

        val cartasObjetivo = jugadorObjetivo.cartas.mesaNormalizadaACuatroCasillas()
        val cartaSeleccionada = cartasObjetivo.getOrNull(posicionObjetivo) ?: run {
            onError("Carta no encontrada")
            return
        }

        if (cartaSeleccionada.esSlotVacio()) {
            onError("No puedes seleccionar una casilla vacía")
            return
        }

        val updates = mutableMapOf<String, Any?>()
        val mazoRobarMutable = sala.mazoRobar.toMutableList()
        val mazoDescarteMutable = sala.mazoDescarte.toMutableList()

        val coincide = cartaSeleccionada.valor == voy.valorObjetivo

        if (!coincide) {
            aplicarCastigoOErrorEnUpdates(
                updates = updates,
                sala = sala,
                jugadorId = jugadorId,
                mazoRobarMutable = mazoRobarMutable,
                onError = onError
            )

            val roboOk = agregarRoboOriginalVoyAUpdates(
                updates = updates,
                sala = sala,
                voy = voy,
                mazoRobarMutable = mazoRobarMutable,
                mazoDescarteMutable = mazoDescarteMutable,
                onError = onError
            )

            if (!roboOk) return

            updates["voyPendiente"] = mapOf<String, Any>()
            updates["jugadaActual"] = mapOf<String, Any>()

            salasRef.child(salaId).updateChildren(updates)
            return
        }

        // VOY correcto:
        // la carta objetivo se descarta, pero no encadena y no altera turno.
        cartasObjetivo[posicionObjetivo] = MesaSlots.VACIA

        val descarte = sala.mazoDescarte.toMutableList()
        descarte.add(
            cartaSeleccionada.copy(
                descartadaPorJugadorId = jugadorId,
                descartadaDesdeJuegoMesa = true,
                comodinRobadoDelDescarteValido = false
            )
        )

        updates["jugadores/$propietarioObjetivoId/cartas"] = cartasObjetivo
        updates["mazoDescarte"] = descarte

        updates["voyPendiente"] = voy.copy(
            fase = VOY_FASE_SELECCIONANDO_ENTREGA,
            jugadorObjetivoId = propietarioObjetivoId,
            posicionObjetivo = posicionObjetivo,
            cartaObjetivoId = cartaSeleccionada.id
        )

        updates["jugadaActual"] = mapOf(
            "jugadorId" to jugadorId,
            "tipo" to "VOY",
            "subaccion" to "seleccionando una carta propia para entregar",
            "timestamp" to System.currentTimeMillis()
        )

        salasRef.child(salaId).updateChildren(updates)
    }

    fun entregarCartaPropiaVoy(
        salaId: String,
        jugadorId: String,
        sala: Sala,
        posicionCartaPropia: Int,
        onError: (String) -> Unit = {}
    ) {
        val voy = sala.voyPendiente ?: run {
            onError("No hay VOY pendiente")
            return
        }

        if (!voy.activo || voy.fase != VOY_FASE_SELECCIONANDO_ENTREGA) {
            onError("VOY no está esperando carta propia")
            return
        }

        if (voy.reclamadoPorJugadorId != jugadorId) {
            onError("No eres quien debe entregar carta")
            return
        }

        if (voy.jugadorObjetivoId.isBlank() || voy.posicionObjetivo !in 0..3) {
            onError("Datos de VOY incompletos")
            return
        }

        if (posicionCartaPropia !in 0..3) {
            onError("Posición propia inválida")
            return
        }

        val jugadorVoy = sala.jugadores[jugadorId] ?: run {
            onError("Jugador VOY no encontrado")
            return
        }

        val jugadorObjetivo = sala.jugadores[voy.jugadorObjetivoId] ?: run {
            onError("Jugador objetivo no encontrado")
            return
        }

        val cartasVoy = jugadorVoy.cartas.mesaNormalizadaACuatroCasillas()
        val cartaAEntregar = cartasVoy[posicionCartaPropia]

        if (cartaAEntregar.esSlotVacio()) {
            onError("No puedes entregar una casilla vacía")
            return
        }

        val cartasObjetivo = jugadorObjetivo.cartas.mesaNormalizadaACuatroCasillas()

        if (!cartasObjetivo[voy.posicionObjetivo].esSlotVacio()) {
            onError("El espacio objetivo ya no está vacío")
            return
        }

        cartasVoy[posicionCartaPropia] = MesaSlots.VACIA
        cartasObjetivo[voy.posicionObjetivo] = cartaAEntregar
            .limpiarMetaDescarteRobo()
            .copy(abierta = false)

        val updates = mutableMapOf<String, Any?>()
        val mazoRobarMutable = sala.mazoRobar.toMutableList()
        val mazoDescarteMutable = sala.mazoDescarte.toMutableList()

        val roboOk = agregarRoboOriginalVoyAUpdates(
            updates = updates,
            sala = sala,
            voy = voy,
            mazoRobarMutable = mazoRobarMutable,
            mazoDescarteMutable = mazoDescarteMutable,
            onError = onError
        )

        if (!roboOk) return

        updates["jugadores/$jugadorId/cartas"] = cartasVoy
        updates["jugadores/${voy.jugadorObjetivoId}/cartas"] = cartasObjetivo

        updates["voyPendiente"] = mapOf<String, Any>()
        updates["jugadaActual"] = mapOf<String, Any>()

        salasRef.child(salaId).updateChildren(updates)
    }

    // ─────────────────────────────────────────
    // HELPERS PRIVADOS
    // ─────────────────────────────────────────


    private const val VOY_FASE_VENTANA = "VENTANA"
    private const val VOY_FASE_SELECCIONANDO_OBJETIVO = "SELECCIONANDO_OBJETIVO"
    private const val VOY_FASE_SELECCIONANDO_ENTREGA = "SELECCIONANDO_ENTREGA"
    private const val VOY_DURACION_MS = 2000L

    private const val VOY_TIPO_ROBO_POZO = "POZO"
    private const val VOY_TIPO_ROBO_DESCARTE = "DESCARTE"

    private fun jugadorTieneCartaParaEntregar(jugador: Jugador): Boolean {
        return jugador.cartas
            .mesaNormalizadaACuatroCasillas()
            .any { !it.esSlotVacio() }
    }

    private fun agregarRoboDelPozoAUpdates(
        updates: MutableMap<String, Any?>,
        sala: Sala,
        jugadorRobandoId: String,
        mazoRobarMutable: MutableList<Carta>,
        onError: (String) -> Unit = {}
    ): Boolean {
        val jugador = sala.jugadores[jugadorRobandoId] ?: run {
            onError("Jugador que iba a robar no encontrado")
            return false
        }

        if (jugador.descalificado) {
            onError("El jugador que iba a robar está descalificado")
            return false
        }

        if (jugador.cartaEnMano != null) {
            onError("El jugador ya tiene una carta en mano")
            return false
        }

        if (mazoRobarMutable.isEmpty()) {
            onError("El pozo de robo está vacío")
            return false
        }

        val cartaRobada = mazoRobarMutable
            .removeAt(0)
            .paraManoTrasRobarDelPozo()

        updates["mazoRobar"] = mazoRobarMutable
        updates["jugadores/$jugadorRobandoId/cartaEnMano"] = cartaRobada

        romperCadenaSiJugadorEsperado(
            updates = updates,
            sala = sala,
            jugadorId = jugadorRobandoId
        )

        return true
    }

    private fun agregarRoboDelDescarteAUpdates(
        updates: MutableMap<String, Any?>,
        sala: Sala,
        jugadorRobandoId: String,
        mazoDescarteMutable: MutableList<Carta>,
        onError: (String) -> Unit = {}
    ): Boolean {
        val jugador = sala.jugadores[jugadorRobandoId] ?: run {
            onError("Jugador que iba a robar no encontrado")
            return false
        }

        if (jugador.descalificado) {
            onError("El jugador que iba a robar está descalificado")
            return false
        }

        if (jugador.cartaEnMano != null) {
            onError("El jugador ya tiene una carta en mano")
            return false
        }

        if (mazoDescarteMutable.isEmpty()) {
            onError("El pozo de descarte está vacío")
            return false
        }

        val cartaRobada = mazoDescarteMutable
            .removeAt(mazoDescarteMutable.lastIndex)
            .paraManoTrasRobarDelDescarte()

        updates["mazoDescarte"] = mazoDescarteMutable
        updates["jugadores/$jugadorRobandoId/cartaEnMano"] = cartaRobada

        romperCadenaSiJugadorEsperado(
            updates = updates,
            sala = sala,
            jugadorId = jugadorRobandoId
        )

        return true
    }

    private fun agregarRoboOriginalVoyAUpdates(
        updates: MutableMap<String, Any?>,
        sala: Sala,
        voy: VoyPendiente,
        mazoRobarMutable: MutableList<Carta>,
        mazoDescarteMutable: MutableList<Carta>,
        onError: (String) -> Unit = {}
    ): Boolean {
        return when (voy.tipoRobo) {
            VOY_TIPO_ROBO_DESCARTE -> {
                agregarRoboDelDescarteAUpdates(
                    updates = updates,
                    sala = sala,
                    jugadorRobandoId = voy.jugadorRobandoId,
                    mazoDescarteMutable = mazoDescarteMutable,
                    onError = onError
                )
            }

            else -> {
                agregarRoboDelPozoAUpdates(
                    updates = updates,
                    sala = sala,
                    jugadorRobandoId = voy.jugadorRobandoId,
                    mazoRobarMutable = mazoRobarMutable,
                    onError = onError
                )
            }
        }
    }

    private fun aplicarCastigoOErrorEnUpdates(
        updates: MutableMap<String, Any?>,
        sala: Sala,
        jugadorId: String,
        mazoRobarMutable: MutableList<Carta>,
        onError: (String) -> Unit = {}
    ) {
        val jugador = sala.jugadores[jugadorId] ?: run {
            onError("Jugador no encontrado para castigo")
            return
        }

        val mesa = jugador.cartas.mesaNormalizadaACuatroCasillas()
        val hueco = primeraCasillaVaciaOrdenVisual(mesa)

        if (hueco != null && mazoRobarMutable.isNotEmpty()) {
            val castigo = mazoRobarMutable
                .removeAt(0)
                .limpiarMetaDescarteRobo()
                .copy(abierta = false)

            mesa[hueco] = castigo

            updates["jugadores/$jugadorId/cartas"] = mesa
            updates["mazoRobar"] = mazoRobarMutable

            onError("VOY incorrecto: recibe carta de castigo")
            return
        }

        val nuevosErrores = (jugador.erroresDescarte + 1).coerceAtMost(3)
        val quedaDescalificado = nuevosErrores >= 3

        updates["jugadores/$jugadorId/erroresDescarte"] = nuevosErrores
        updates["jugadores/$jugadorId/descalificado"] = quedaDescalificado

        onError(
            if (quedaDescalificado) {
                "VOY incorrecto: tercer error, jugador descalificado"
            } else {
                "VOY incorrecto: error $nuevosErrores de 3"
            }
        )
    }

    private fun esValorCartaEspia(valor: String): Boolean {
        return valor in listOf("5", "6", "7", "8", "9", "10")
    }

    private fun detectarAdelantadoDuranteEspiaPendiente(
        sala: Sala,
        jugadorQueDescartaId: String,
        cartaDescartada: Carta,
        posicionDescartada: Int
    ): AdelantadoPendiente? {
        val jugadorPerjudicadoId = sala.turnoActualId

        if (jugadorPerjudicadoId.isBlank()) return null
        if (jugadorPerjudicadoId == jugadorQueDescartaId) return null
        if (sala.adelantadoPendiente?.activo == true) return null

        val jugadorPerjudicado = sala.jugadores[jugadorPerjudicadoId] ?: return null

        // Caso A:
        // El jugador en turno todavía tiene la carta espía en mano.
        val cartaEnMano = jugadorPerjudicado.cartaEnMano
        if (cartaEnMano != null && esValorCartaEspia(cartaEnMano.valor)) {
            return AdelantadoPendiente(
                activo = true,
                jugadorPerjudicadoId = jugadorPerjudicadoId,
                jugadorAdelantadoId = jugadorQueDescartaId,
                cartaAdelantadaId = cartaDescartada.id,
                valorAdelantado = cartaDescartada.valor,
                paloAdelantado = cartaDescartada.palo,
                posicionAdelantada = posicionDescartada,
                cartaEspiaPendienteId = cartaEnMano.id,
                valorCartaEspia = cartaEnMano.valor,
                paloCartaEspia = cartaEnMano.palo,
                espiaYaEnDescarte = false
            )
        }

        // Caso B:
        // El jugador ya activó ESPIAR, la carta espía ya está en descarte,
        // pero todavía no eligió carta para espiar.
        val poder = sala.cartaPoderActiva
        val espiaActivaSinCartaSeleccionada =
            poder != null &&
                    poder.jugadorId == jugadorPerjudicadoId &&
                    poder.tipoPoder == TipoPoder.ESPIAR &&
                    poder.cartaEspiandoId.isBlank()

        if (espiaActivaSinCartaSeleccionada) {
            return AdelantadoPendiente(
                activo = true,
                jugadorPerjudicadoId = jugadorPerjudicadoId,
                jugadorAdelantadoId = jugadorQueDescartaId,
                cartaAdelantadaId = cartaDescartada.id,
                valorAdelantado = cartaDescartada.valor,
                paloAdelantado = cartaDescartada.palo,
                posicionAdelantada = posicionDescartada,
                cartaEspiaPendienteId = "",
                valorCartaEspia = poder.valorCartaActivadora,
                paloCartaEspia = "",
                espiaYaEnDescarte = true
            )
        }

        return null
    }

    private fun limpiarAdelantadoPendienteSiPerteneceA(
        updates: MutableMap<String, Any?>,
        sala: Sala,
        jugadorId: String
    ) {
        val adelanto = sala.adelantadoPendiente

        if (
            adelanto != null &&
            adelanto.activo &&
            adelanto.jugadorPerjudicadoId == jugadorId
        ) {
            updates["adelantadoPendiente"] = mapOf<String, Any>()
        }
    }

    private data class ResultadoCadena(
        val siguienteTurnoId: String,
        val cadenaActualizada: CadenaDescarte?
    )

    private fun aplicarDescarteEspontaneoConPoliticaDeCadena(
        updates: MutableMap<String, Any?>,
        sala: Sala,
        jugadorId: String,
        valorDescartado: String
    ) {
        val cadenaActual = sala.cadenaDescarte
            ?.takeIf { it.activa && it.valorBase == valorDescartado }

        val esJugadorEnTurno = sala.turnoActualId == jugadorId
        val esJugadorEsperado = cadenaActual?.turnoEsperadoId == jugadorId

        // Caso 1:
        // No existe cadena activa.
        // Si descarta el jugador en turno, inicia cadena.
        // Si descarta alguien fuera de turno, se permite el descarte,
        // pero NO cambia el turno.
        if (cadenaActual == null) {
            if (esJugadorEnTurno) {
                aplicarAvancePorDescarteEncadenado(
                    updates = updates,
                    sala = sala,
                    jugadorId = jugadorId,
                    valorDescartado = valorDescartado
                )
            }

            return
        }

        // Caso 2:
        // Hay cadena activa del mismo valor.
        // Registramos que este jugador ya descartó,
        // aunque se haya adelantado.
        val cadenaConJugadorRegistrado = cadenaActual.copy(
            jugadoresQueDescartaron = cadenaActual.jugadoresQueDescartaron +
                    (jugadorId to true)
        )

        // Caso 3:
        // Si quien descartó era el jugador esperado,
        // ahora sí se consumen todos los turnos que ya estén
        // registrados en orden natural.
        if (esJugadorEsperado || esJugadorEnTurno) {
            val resultado = resolverAvanceCadenaDesde(
                sala = sala,
                cadena = cadenaConJugadorRegistrado,
                jugadorDesdeId = cadenaConJugadorRegistrado.turnoEsperadoId
            )

            updates["turnoActualId"] = resultado.siguienteTurnoId

            if (resultado.cadenaActualizada == null) {
                updates["cadenaDescarte"] = mapOf<String, Any>()
            } else {
                updates["cadenaDescarte"] = resultado.cadenaActualizada
            }

            return
        }

        // Caso 4:
        // Jugador fuera de turno se adelantó correctamente.
        // Su descarte queda registrado, pero el turno NO cambia.
        updates["cadenaDescarte"] = cadenaConJugadorRegistrado
    }

    private fun aplicarAvancePorDescarteEncadenado(
        updates: MutableMap<String, Any?>,
        sala: Sala,
        jugadorId: String,
        valorDescartado: String
    ) {
        val resultado = calcularAvancePorDescarteEncadenado(
            sala = sala,
            jugadorId = jugadorId,
            valorDescartado = valorDescartado
        )

        updates["turnoActualId"] = resultado.siguienteTurnoId

        if (resultado.cadenaActualizada == null) {
            updates["cadenaDescarte"] = mapOf<String, Any>()
        } else {
            updates["cadenaDescarte"] = resultado.cadenaActualizada
        }
    }

    private fun calcularAvancePorDescarteEncadenado(
        sala: Sala,
        jugadorId: String,
        valorDescartado: String
    ): ResultadoCadena {
        val cadenaExistente = sala.cadenaDescarte
            ?.takeIf { it.activa && it.valorBase == valorDescartado }

        val jugadoresQueDescartaron =
            (cadenaExistente?.jugadoresQueDescartaron ?: emptyMap()) +
                    (jugadorId to true)

        val cadenaBase = if (cadenaExistente != null) {
            cadenaExistente.copy(
                jugadoresQueDescartaron = jugadoresQueDescartaron
            )
        } else {
            val siguiente = siguienteTurno(jugadorId, sala)

            CadenaDescarte(
                activa = true,
                valorBase = valorDescartado,
                jugadorOrigenId = jugadorId,
                turnoEsperadoId = siguiente,
                jugadoresQueDescartaron = jugadoresQueDescartaron
            )
        }

        val jugadorDesde = cadenaBase.turnoEsperadoId.ifBlank {
            siguienteTurno(jugadorId, sala)
        }

        return resolverAvanceCadenaDesde(
            sala = sala,
            cadena = cadenaBase,
            jugadorDesdeId = jugadorDesde
        )
    }

    private fun resolverAvanceCadenaDesde(
        sala: Sala,
        cadena: CadenaDescarte,
        jugadorDesdeId: String
    ): ResultadoCadena {
        var jugadorEvaluadoId = jugadorDesdeId
        val totalJugadores = sala.jugadores.size.coerceAtLeast(1)
        var saltos = 0

        while (saltos < totalJugadores) {
            val yaDescarto =
                cadena.jugadoresQueDescartaron[jugadorEvaluadoId] == true

            if (!yaDescarto) {
                return ResultadoCadena(
                    siguienteTurnoId = jugadorEvaluadoId,
                    cadenaActualizada = cadena.copy(
                        turnoEsperadoId = jugadorEvaluadoId
                    )
                )
            }

            jugadorEvaluadoId = siguienteTurno(jugadorEvaluadoId, sala)
            saltos++
        }

        // Todos los jugadores del ciclo ya descartaron ese valor.
        // La cadena termina y el turno continúa con el siguiente calculado.
        return ResultadoCadena(
            siguienteTurnoId = jugadorEvaluadoId,
            cadenaActualizada = null
        )
    }

    private fun romperCadenaSiJugadorEsperado(
        updates: MutableMap<String, Any?>,
        sala: Sala,
        jugadorId: String
    ) {
        val cadena = sala.cadenaDescarte

        if (
            cadena != null &&
            cadena.activa &&
            cadena.turnoEsperadoId == jugadorId
        ) {
            updates["cadenaDescarte"] = mapOf<String, Any>()
        }
    }

    private fun aplicarAvancePorCimaActualDelDescarte(
        updates: MutableMap<String, Any?>,
        sala: Sala,
        jugadorId: String
    ) {
        val cima = sala.mazoDescarte.lastOrNull()

        if (cima != null) {
            aplicarAvancePorDescarteEncadenado(
                updates = updates,
                sala = sala,
                jugadorId = jugadorId,
                valorDescartado = cima.valor
            )
        } else {
            updates["turnoActualId"] = siguienteTurno(jugadorId, sala)
            updates["cadenaDescarte"] = mapOf<String, Any>()
        }
    }

    // Calcula el ID del siguiente jugador en sentido horario
    private fun siguienteTurno(jugadorActualId: String, sala: Sala): String {
        val jugadores = sala.jugadores.keys.toList()
        if (jugadores.isEmpty()) return ""

        val indexActual = jugadores.indexOf(jugadorActualId)
        if (indexActual < 0) {
            return jugadores.firstOrNull { id ->
                sala.jugadores[id]?.descalificado != true
            } ?: jugadores.first()
        }

        var offset = 1

        while (offset <= jugadores.size) {
            val indexSiguiente = (indexActual + offset) % jugadores.size
            val candidatoId = jugadores[indexSiguiente]
            val candidato = sala.jugadores[candidatoId]

            if (candidato?.descalificado != true) {
                return candidatoId
            }

            offset++
        }

        return jugadorActualId
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
    ): MutableMap<String, Any?>? {
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

        val updates = mutableMapOf<String, Any?>()

        if (jugadorAId == jugadorBId) {
            val cartas = sala.jugadores[jugadorAId]
                ?.cartas
                ?.mesaNormalizadaACuatroCasillas()
                ?: run {
                    onError("Jugador no encontrado")
                    return null
                }

            val cartaTemporal = cartas[cartaA.posicion]
            cartas[cartaA.posicion] = cartas[cartaB.posicion]
            cartas[cartaB.posicion] = cartaTemporal

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
        updates["cambioPropioAnimando"] = null
        updates["jugadaActual"] = mapOf<String, Any>()

        if (!cartaDesplazada.esSlotVacio()) {
            aplicarAvancePorDescarteEncadenado(
                updates = updates,
                sala = sala,
                jugadorId = jugadorId,
                valorDescartado = cartaDesplazada.valor
            )
        } else {
            updates["turnoActualId"] = siguienteTurno(jugadorId, sala)
            updates["cadenaDescarte"] = mapOf<String, Any>()
        }

        limpiarAdelantadoPendienteSiPerteneceA(
            updates = updates,
            sala = sala,
            jugadorId = jugadorId
        )

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
        comodinRobadoDelDescarteValido = false,
        origenRobo = ""
    )

private fun Carta.paraManoTrasRobarDelPozo(): Carta =
    limpiarMetaDescarteRobo().copy(
        abierta = true,
        origenRobo = "POZO"
    )

private fun Carta.paraManoTrasRobarDelDescarte(): Carta =
    copy(
        descartadaPorJugadorId = "",
        descartadaDesdeJuegoMesa = false,
        comodinRobadoDelDescarteValido = valor == "JKR",
        abierta = true,
        origenRobo = "DESCARTE"
    )

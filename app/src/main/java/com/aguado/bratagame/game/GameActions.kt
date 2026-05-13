package com.aguado.bratagame.game

import com.aguado.bratagame.Carta
import com.aguado.bratagame.CartaEnMesa
import com.aguado.bratagame.CartaPoderActiva
import com.aguado.bratagame.Jugador
import com.aguado.bratagame.Sala
import com.aguado.bratagame.TipoPoder
import com.google.firebase.database.FirebaseDatabase

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

        val cartaRobada = mazo.removeAt(0)
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
        val descarte = sala.mazoDescarte.toMutableList()
        if (descarte.isEmpty()) {
            onError("El pozo de descarte está vacío")
            return
        }

        val cartaRobada = descarte.removeAt(descarte.lastIndex)
        val updates = mutableMapOf<String, Any>()

        updates["mazoDescarte"] = descarte
        updates["jugadores/$jugadorId/cartaEnMano"] = cartaRobada

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
        descarte.add(cartaEnMano)

        val updates = mutableMapOf<String, Any>()
        updates["mazoDescarte"] = descarte
        updates["jugadores/$jugadorId/cartaEnMano"] = mapOf<String, Any>() // limpiar mano
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

        val cartasActuales = jugador.cartas.toMutableList()
        if (posicionDestino !in 0..3 || posicionDestino >= cartasActuales.size) {
            onError("Posición inválida")
            return
        }

        val cartaDesplazada = cartasActuales[posicionDestino]
        cartasActuales[posicionDestino] = cartaEnMano

        val descarte = sala.mazoDescarte.toMutableList()
        descarte.add(cartaDesplazada)

        val updates = mutableMapOf<String, Any>()
        updates["jugadores/$jugadorId/cartas"] = cartasActuales
        updates["jugadores/$jugadorId/cartaEnMano"] = mapOf<String, Any>()
        updates["mazoDescarte"] = descarte
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
        descarte.add(cartaEnMano)

        val updates = mutableMapOf<String, Any>()
        updates["mazoDescarte"] = descarte
        updates["jugadores/$jugadorId/cartaEnMano"] = mapOf<String, Any>()

        // Activar estado: el jugador debe elegir qué carta de su juego descartar
        updates["cartaPoderActiva"] = mapOf(
            "jugadorId" to jugadorId,
            "tipoPoder" to TipoPoder.DESCARTE_FREE_SELECCION.name,
            "cartaEspiandoId" to ""
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

        val cartasActuales = jugador.cartas.toMutableList()
        if (posicionDescartada !in 0..3 || posicionDescartada >= cartasActuales.size) {
            onError("Posición inválida")
            return
        }

        val cartaDescartada = cartasActuales.removeAt(posicionDescartada)
        val descarte = sala.mazoDescarte.toMutableList()
        descarte.add(cartaDescartada)

        val updates = mutableMapOf<String, Any>()
        updates["jugadores/$jugadorId/cartas"] = cartasActuales
        updates["mazoDescarte"] = descarte
        updates["cartaPoderActiva"] = mapOf<String, Any>()
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
        descarte.add(cartaEnMano)

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
        salasRef.child(salaId).child("cartaPoderActiva")
            .child("cartaEspiandoId").setValue(cartaId)
    }

    // El jugador termina de espiar: regresa la carta a su lugar
    fun regresarCartaEspiada(
        salaId: String,
        jugadorId: String,
        sala: Sala
    ) {
        val updates = mutableMapOf<String, Any>()
        updates["cartaPoderActiva"] = mapOf<String, Any>()
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

        val cartasJugador = jugador.cartas.toMutableList()
        if (posicionCartaPropia !in 0 until cartasJugador.size) { onError("Posición inválida"); return }

        // Carta propia que le dará al espiado
        val cartaParaDar = cartasJugador.removeAt(posicionCartaPropia)

        // Carta espiada sale del juego del espiado y va al descarte
        val cartasEspiado = espiado.cartas.toMutableList()
        cartasEspiado.removeAll { it.id == cartaEspiada.id }

        // El espiado recibe la carta propia del espía
        cartasEspiado.add(cartaParaDar)

        val descarte = sala.mazoDescarte.toMutableList()
        descarte.add(cartaEspiada)

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
        descarte.add(cartaEnMano)

        val updates = mutableMapOf<String, Any>()
        updates["mazoDescarte"] = descarte
        updates["jugadores/$jugadorId/cartaEnMano"] = mapOf<String, Any>()
        updates["cartaPoderActiva"] = mapOf(
            "jugadorId" to jugadorId,
            "tipoPoder" to TipoPoder.CAMBIAR_VIENDO.name,
            "cartaEspiandoId" to ""
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
        cartaEspiada: CartaEnMesa,
        cartaDestino: CartaEnMesa,
        onError: (String) -> Unit = {}
    ) {
        // Intercambiar propietarios y posiciones
        val nuevaEspiada = cartaEspiada.copy(
            propietarioId = cartaDestino.propietarioId,
            posicion = cartaDestino.posicion
        )
        val nuevaDestino = cartaDestino.copy(
            propietarioId = cartaEspiada.propietarioId,
            posicion = cartaEspiada.posicion
        )

        val updates = construirUpdatesIntercambio(sala, nuevaEspiada, nuevaDestino)
        updates["cartaPoderActiva"] = mapOf<String, Any>()
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
        descarte.add(cartaEnMano)

        val updates = mutableMapOf<String, Any>()
        updates["mazoDescarte"] = descarte
        updates["jugadores/$jugadorId/cartaEnMano"] = mapOf<String, Any>()
        updates["cartaPoderActiva"] = mapOf(
            "jugadorId" to jugadorId,
            "tipoPoder" to TipoPoder.CAMBIAR_SIN_VER.name,
            "cartaEspiandoId" to ""
        )

        salasRef.child(salaId).updateChildren(updates)
    }

    // Confirmar intercambio sin ver: dos cartas elegidas de cualquier juego
    fun confirmarCambioSinVer(
        salaId: String,
        jugadorId: String,
        sala: Sala,
        cartaA: CartaEnMesa,
        cartaB: CartaEnMesa,
        onError: (String) -> Unit = {}
    ) {
        val nuevaA = cartaA.copy(propietarioId = cartaB.propietarioId, posicion = cartaB.posicion)
        val nuevaB = cartaB.copy(propietarioId = cartaA.propietarioId, posicion = cartaA.posicion)

        val updates = construirUpdatesIntercambio(sala, nuevaA, nuevaB)
        updates["cartaPoderActiva"] = mapOf<String, Any>()
        updates["turnoActualId"] = siguienteTurno(jugadorId, sala)

        salasRef.child(salaId).updateChildren(updates)
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
            palo = paloElegido
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

        val cartaADescartar = jugador.cartas.getOrNull(posicionCarta) ?: run {
            onError("Posición inválida")
            return
        }

        // Validar que coincide con la cima del descarte
        if (cartaADescartar.valor != ultimaDescarte.valor) {
            onError("La carta no coincide con la cima del descarte")
            return
        }

        val cartasActualizadas = jugador.cartas.toMutableList()
        cartasActualizadas.removeAt(posicionCarta)

        val descarte = sala.mazoDescarte.toMutableList()
        descarte.add(cartaADescartar)

        val updates = mutableMapOf<String, Any>()
        updates["jugadores/$jugadorId/cartas"] = cartasActualizadas
        updates["mazoDescarte"] = descarte

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
        val cartaADescartar = jugador.cartas.getOrNull(posicionADescartar) ?: run {
            onError("Posición inválida"); return
        }

        val cartasJugador = jugador.cartas.toMutableList()
        cartasJugador.removeAt(posicionADescartar)

        val adelantado = sala.jugadores[adelantadoId] ?: run { onError("Adelantado no encontrado"); return }
        val cartasAdelantado = adelantado.cartas.toMutableList()
        cartasAdelantado.add(cartaADescartar)

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
        val cartaSuperior = descarte.removeAt(descarte.lastIndex)

        // El resto se mezcla y pasa al pozo
        val nuevoPozoMezclado = descarte.shuffled()

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
    private fun construirUpdatesIntercambio(
        sala: Sala,
        cartaA: CartaEnMesa, // ya con propietario/posicion nuevos
        cartaB: CartaEnMesa
    ): MutableMap<String, Any> {
        val updates = mutableMapOf<String, Any>()

        // Actualizar cartas del propietario original de A (ahora recibe B)
        val jugadorDeA = sala.jugadores[cartaB.propietarioId]
        if (jugadorDeA != null) {
            val cartasA = jugadorDeA.cartas.toMutableList()
            val indexA = cartasA.indexOfFirst { it.id == cartaB.carta.id }
            if (indexA >= 0) cartasA[indexA] = cartaA.carta
            updates["jugadores/${cartaB.propietarioId}/cartas"] = cartasA
        }

        // Actualizar cartas del propietario original de B (ahora recibe A)
        val jugadorDeB = sala.jugadores[cartaA.propietarioId]
        if (jugadorDeB != null) {
            val cartasB = jugadorDeB.cartas.toMutableList()
            val indexB = cartasB.indexOfFirst { it.id == cartaA.carta.id }
            if (indexB >= 0) cartasB[indexB] = cartaB.carta
            updates["jugadores/${cartaA.propietarioId}/cartas"] = cartasB
        }

        return updates
    }
}
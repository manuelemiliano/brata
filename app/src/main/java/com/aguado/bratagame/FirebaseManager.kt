package com.aguado.bratagame

import com.aguado.bratagame.game.TurnManager
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import com.aguado.bratagame.EntregaCartaEspiadoAnimando
import com.aguado.bratagame.EstadoParticipante
import com.aguado.bratagame.ParticipantePartida

object FirebaseManager {

    // lazy para evitar inicializar Firebase durante Previews del IDE
    private val database: FirebaseDatabase by lazy { Firebase.database }
    private val salasRef by lazy { database.getReference("salas") }
    private val connectedRef by lazy { database.getReference(".info/connected") }

    private val serverTimeOffsetRef by lazy {
        database.getReference(".info/serverTimeOffset")
    }

    @Volatile
    private var serverTimeOffsetMs: Long = 0L

    init {
        serverTimeOffsetRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                serverTimeOffsetMs = snapshot.getValue(Long::class.java) ?: 0L
            }

            override fun onCancelled(error: DatabaseError) = Unit
        })
    }

    fun horaServidorAproximada(): Long {
        return System.currentTimeMillis() + serverTimeOffsetMs
    }

    fun offsetServidorMs(): Long {
        return serverTimeOffsetMs
    }

    // ─────────────────────────────────────────
    // INICIAR PARTIDA
    //
    // Responsabilidades en una sola escritura atómica:
    //   1. Generar el mazo correcto (1 o 2 barajas según jugadores)
    //   2. Repartir 4 cartas a cada jugador
    //   3. Dejar el resto en mazoRobar
    //   4. Escribir el timestamp para el contador de 15 segundos
    //   5. Asignar el primer turno al anfitrión
    //   6. Marcar estaEnJuego = true (dispara navegación en todos los clientes)
    // ─────────────────────────────────────────

    fun iniciarPartida(salaId: String, jugadores: List<Jugador>) {
        val listaPalos = listOf("corazones", "picas", "diamantes", "treboles")
        val valores = listOf("A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K")

        // 1. Determinar número de barajas según jugadores
        val numBarajas = if (jugadores.size >= 5) 2 else 1
        val mazoCompleto = mutableListOf<Carta>()

        repeat(numBarajas) { baraja ->
            // 52 cartas estándar por baraja
            for (palo in listaPalos) {
                for (v in valores) {
                    mazoCompleto.add(
                        Carta(
                            id = "${v}_${palo}_$baraja",
                            valor = v,
                            palo = palo,
                            abierta = false
                        )
                    )
                }
            }
            // 2 comodines por baraja
            repeat(2) { index ->
                mazoCompleto.add(
                    Carta(
                        id = "joker_${baraja}_$index",
                        valor = "JKR",
                        palo = "comodin",
                        abierta = false
                    )
                )
            }
        }

        mazoCompleto.shuffle()

        val actualizaciones = mutableMapOf<String, Any?>()
        val nuevaPartidaId = "partida_${System.currentTimeMillis()}"
        var punteroMazo = 0

        // 2. Repartir 4 cartas a cada jugador
        jugadores.forEach { jugador ->
            val cartasAsignadas = mazoCompleto
                .subList(punteroMazo, punteroMazo + 4)
                .toList()

            actualizaciones["jugadores/${jugador.id}/cartas"] = cartasAsignadas
            actualizaciones["jugadores/${jugador.id}/cartaEnMano"] = mapOf<String, Any>()
            actualizaciones["jugadores/${jugador.id}/estaListo"] = false
            actualizaciones["jugadores/${jugador.id}/erroresDescarte"] = 0
            actualizaciones["jugadores/${jugador.id}/descalificado"] = false

            actualizaciones["participantes/${jugador.id}"] = ParticipantePartida(
                id = jugador.id,
                nombre = jugador.nombre,
                estado = EstadoParticipante.ACTIVO,
                partidaId = nuevaPartidaId,
                conectado = true,
                ultimaConexion = System.currentTimeMillis()
            )

            // Conservamos presencia si ya la estás usando.
            actualizaciones["jugadores/${jugador.id}/conectado"] = true
            actualizaciones["jugadores/${jugador.id}/ultimaConexion"] = System.currentTimeMillis()

            punteroMazo += 4
        }

        // 3. El resto del mazo va al pozo de robo
        val mazoRobar = mazoCompleto.subList(punteroMazo, mazoCompleto.size).toList()
        actualizaciones["mazoRobar"] = mazoRobar

        // 4. Descarte vacío al inicio
        actualizaciones["mazoDescarte"] = emptyList<Carta>()

        // 5. Timestamp para el contador de 15 segundos
        // Todos los clientes calculan el tiempo restante desde este valor
        actualizaciones["timestampInicioContador"] = ServerValue.TIMESTAMP

        // 6. Primer turno: el anfitrión
        val anfitrion = jugadores.firstOrNull { it.esAnfitrion } ?: jugadores.first()
        actualizaciones["turnoActualId"] = anfitrion.id

        // 7. Limpiar estado global por si es una revancha
        actualizaciones["brataActivada"] = false
        actualizaciones["brataJugadorId"] = ""
        actualizaciones["cartaPoderActiva"] = mapOf<String, Any>()
        actualizaciones["jugadaActual"] = mapOf<String, Any>()
        actualizaciones["historialJugadas"] = mapOf<String, Any>()
        actualizaciones["observadores"] = null
        actualizaciones["swapAnimando"] = mapOf<String, Any>()
        actualizaciones["cambioPropioAnimando"] = null
        actualizaciones["descarteEspontaneoAnimando"] = mapOf<String, Any>()
        actualizaciones["descarteFreeAnimando"] = mapOf<String, Any>()
        actualizaciones["espiaAnimando"] = mapOf<String, Any>()
        actualizaciones["entregaCartaEspiadoAnimando"] = mapOf<String, Any>()
        actualizaciones["cadenaDescarte"] = mapOf<String, Any>()
        actualizaciones["adelantadoPendiente"] = mapOf<String, Any>()
        actualizaciones["voyPendiente"] = mapOf<String, Any>()
        actualizaciones["ventanaFinalRonda"] = mapOf<String, Any>()



        // 8. Identificador de partida.
        // Sirve para que todos los clientes detecten revancha aunque estén en RESULTADO.
        actualizaciones["partidaId"] = nuevaPartidaId

        // 9. Marcar juego iniciado — dispara navegación en todos los clientes
        actualizaciones["estaEnJuego"] = true

        salasRef.child(salaId).updateChildren(actualizaciones)
    }

    // ─────────────────────────────────────────
    // LOBBY
    // ─────────────────────────────────────────

    fun volverSalaAlLobby(
        salaId: String,
        sala: Sala,
        onComplete: (Boolean) -> Unit = {}
    ) {
        val actualizaciones = mutableMapOf<String, Any?>()

        // La sala vuelve a estar disponible como lobby.
        actualizaciones["estaEnJuego"] = false
        actualizaciones["estaActiva"] = true
        actualizaciones["jugadoresExpulsados"] = mapOf<String, Any>()
        actualizaciones["participantes"] = mapOf<String, Any>()
        actualizaciones["observadores"] = null

        // Estados generales de partida.
        actualizaciones["turnoActualId"] = ""
        actualizaciones["partidaId"] = ""
        actualizaciones["mazoRobar"] = emptyList<Carta>()
        actualizaciones["mazoDescarte"] = emptyList<Carta>()
        actualizaciones["timestampInicioContador"] = 0L

        // Estados de BRATA.
        actualizaciones["brataActivada"] = false
        actualizaciones["brataJugadorId"] = ""
        actualizaciones["ventanaFinalRonda"] = mapOf<String, Any>()

        // Estados de poderes / animaciones / jugadas.
        actualizaciones["cartaPoderActiva"] = mapOf<String, Any>()
        actualizaciones["jugadaActual"] = mapOf<String, Any>()
        actualizaciones["historialJugadas"] = mapOf<String, Any>()
        actualizaciones["swapAnimando"] = mapOf<String, Any>()
        actualizaciones["cambioPropioAnimando"] = null
        actualizaciones["descarteEspontaneoAnimando"] = mapOf<String, Any>()
        actualizaciones["descarteFreeAnimando"] = mapOf<String, Any>()
        actualizaciones["espiaAnimando"] = mapOf<String, Any>()
        actualizaciones["entregaCartaEspiadoAnimando"] = mapOf<String, Any>()
        actualizaciones["cadenaDescarte"] = mapOf<String, Any>()
        actualizaciones["adelantadoPendiente"] = mapOf<String, Any>()
        actualizaciones["voyPendiente"] = mapOf<String, Any>()

        val anfitrionActual = sala.jugadores.values
            .firstOrNull { it.esAnfitrion }

        val nuevoAnfitrion = anfitrionActual
            ?: sala.jugadores.values
                .sortedBy { it.nombre.lowercase() }
                .firstOrNull()

        // Reiniciar jugadores, pero conservarlos en la sala.
        sala.jugadores.values.forEach { jugador ->
            actualizaciones["jugadores/${jugador.id}/cartas"] = emptyList<Carta>()
            actualizaciones["jugadores/${jugador.id}/cartaEnMano"] = mapOf<String, Any>()
            actualizaciones["jugadores/${jugador.id}/estaListo"] = false
            actualizaciones["jugadores/${jugador.id}/erroresDescarte"] = 0
            actualizaciones["jugadores/${jugador.id}/descalificado"] = false

            // Garantiza que siempre exista un anfitrión al volver al lobby.
            actualizaciones["jugadores/${jugador.id}/esAnfitrion"] =
                jugador.id == nuevoAnfitrion?.id

            actualizaciones["jugadores/${jugador.id}/conectado"] = jugador.conectado
            actualizaciones["jugadores/${jugador.id}/ultimaConexion"] = System.currentTimeMillis()
        }

        salasRef.child(salaId)
            .updateChildren(actualizaciones)
            .addOnCompleteListener { task ->
                onComplete(task.isSuccessful)
            }
    }

    fun cambiarEstadoListo(salaId: String, jugadorId: String, estaListo: Boolean) {
        salasRef.child(salaId)
            .child("jugadores")
            .child(jugadorId)
            .child("estaListo")
            .setValue(estaListo)
    }

    fun eliminarSala(salaId: String) {
        salasRef.child(salaId).removeValue()
    }

    fun registrarPresenciaJugador(
        salaId: String,
        jugadorId: String
    ) {
        if (salaId.isBlank() || jugadorId.isBlank()) return

        val salaRef = salasRef.child(salaId)
        val jugadorRef = salaRef.child("jugadores").child(jugadorId)

        connectedRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val conectado = snapshot.getValue(Boolean::class.java) ?: false
                if (!conectado) return

                salaRef.get().addOnSuccessListener { salaSnap ->
                    val salaExiste = salaSnap.exists()
                    val salaTieneNombre = salaSnap.child("nombreSala").exists()
                    val salaTieneEstado = salaSnap.child("estaActiva").exists() ||
                            salaSnap.child("estaEnJuego").exists()
                    val jugadorExiste = salaSnap.child("jugadores").child(jugadorId).exists()

                    // Blindaje clave:
                    // si la sala no existe o es un nodo incompleto,
                    // NO escribimos presencia porque eso crea salas fantasma.
                    if (!salaExiste || !salaTieneNombre || !salaTieneEstado || !jugadorExiste) {
                        return@addOnSuccessListener
                    }

                    val ahora = System.currentTimeMillis()

                    val desconexionJugador = mapOf<String, Any>(
                        "conectado" to false,
                        "ultimaConexion" to ahora
                    )

                    val desconexionParticipante = mapOf<String, Any>(
                        "estado" to EstadoParticipante.DESCONECTADO.name,
                        "conectado" to false,
                        "ultimaConexion" to ahora
                    )

                    jugadorRef.onDisconnect().updateChildren(desconexionJugador)

                    salasRef.child(salaId)
                        .child("participantes")
                        .child(jugadorId)
                        .onDisconnect()
                        .updateChildren(desconexionParticipante)

                    jugadorRef.updateChildren(
                        mapOf(
                            "conectado" to true,
                            "ultimaConexion" to ahora
                        )
                    )

                    salasRef.child(salaId)
                        .child("participantes")
                        .child(jugadorId)
                        .updateChildren(
                            mapOf(
                                "estado" to EstadoParticipante.ACTIVO.name,
                                "conectado" to true,
                                "ultimaConexion" to ahora
                            )
                        )
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        })
    }

    fun limpiarSalaSiQuedaVacia(salaId: String) {
        val salaRef = salasRef.child(salaId)

        salaRef.child("jugadores")
            .get()
            .addOnSuccessListener { snapshot ->
                if (!snapshot.exists() || snapshot.childrenCount == 0L) {
                    salaRef.removeValue()
                }
            }
    }
    // Ya no se usa directamente — iniciarPartida lo reemplaza.
    // Se mantiene por compatibilidad con LobbyScreen hasta actualizar ese archivo.
    fun marcarJuegoIniciado(salaId: String) {
        salasRef.child(salaId).child("estaEnJuego").setValue(true)
    }

    // ─────────────────────────────────────────
    // SALAS
    // ─────────────────────────────────────────

    fun obtenerSalasActivas(onUpdate: (List<Sala>) -> Unit): ValueEventListener {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val updates = mutableMapOf<String, Any?>()

                val salasVisibles = snapshot.children.mapNotNull { salaSnap ->
                    val salaKey = salaSnap.key ?: return@mapNotNull null
                    val sala = salaSnap.getValue(Sala::class.java)

                    val tieneNombreSala = salaSnap.child("nombreSala").exists()
                    val tieneEstadoSala = salaSnap.child("estaActiva").exists() ||
                            salaSnap.child("estaEnJuego").exists()

                    val jugadoresSnap = salaSnap.child("jugadores")
                    val jugadores = jugadoresSnap.children.toList()

                    val esNodoFantasma =
                        !tieneNombreSala ||
                                !tieneEstadoSala ||
                                jugadores.isEmpty() ||
                                jugadores.all { jugadorSnap ->
                                    !jugadorSnap.child("nombre").exists()
                                }

                    if (esNodoFantasma) {
                        updates[salaKey] = null
                        return@mapNotNull null
                    }

                    if (sala == null) {
                        updates[salaKey] = null
                        return@mapNotNull null
                    }

                    val jugadoresValidos = sala.jugadores.values.filter { jugador ->
                        jugador.id.isNotBlank() && jugador.nombre.isNotBlank()
                    }

                    // Los bots cuentan como "conectados" siempre porque su presencia es lógica,
                    // no física. Sin esto, una sala donde solo hay bots se borraría como fantasma.
                    val hayJugadoresConectados = jugadoresValidos.any { it.conectado || it.esBot }

                    when {
                        sala.estaActiva && !sala.estaEnJuego && !hayJugadoresConectados -> {
                            updates[salaKey] = null
                            null
                        }

                        sala.estaActiva && !sala.estaEnJuego && hayJugadoresConectados -> {
                            sala
                        }

                        else -> null
                    }
                }

                if (updates.isNotEmpty()) {
                    salasRef.updateChildren(updates)
                }

                onUpdate(salasVisibles)
            }

            override fun onCancelled(error: DatabaseError) {}
        }

        salasRef.addValueEventListener(listener)
        return listener
    }

    fun salirDeSala(salaId: String, jugadorId: String, esAnfitrion: Boolean) {
        val salaRef = salasRef.child(salaId)
        val jugadorRef = salaRef.child("jugadores").child(jugadorId)

        jugadorRef.onDisconnect().cancel()

        jugadorRef.removeValue().addOnCompleteListener {
            salaRef.child("jugadores").get().addOnSuccessListener { snapshot ->
                when {
                    !snapshot.exists() || snapshot.childrenCount == 0L -> {
                        salaRef.removeValue()
                    }

                    esAnfitrion -> {
                        salaRef.removeValue()
                    }
                }
            }
        }
    }

    fun crearSala(nombreSala: String, anfitrion: Jugador, onComplete: (String?) -> Unit) {
        val salaId = salasRef.push().key ?: return
        val anfitrionConectado = anfitrion.copy(
            conectado = true,
            ultimaConexion = System.currentTimeMillis()
        )

        val nuevaSala = Sala(
            id = salaId,
            nombreSala = nombreSala,
            jugadores = mapOf(anfitrion.id to anfitrionConectado),
            participantes = mapOf(
                anfitrion.id to participanteActivo(anfitrionConectado)
            ),
            estaEnJuego = false,
            estaActiva = true
        )

        salasRef.child(salaId).setValue(nuevaSala).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                registrarPresenciaJugador(
                    salaId = salaId,
                    jugadorId = anfitrion.id
                )
                onComplete(salaId)
            } else {
                onComplete(null)
            }
        }
    }

    fun reconectarJugadorASala(
        salaId: String,
        jugadorId: String,
        onComplete: (Boolean) -> Unit
    ) {
        val salaRef = salasRef.child(salaId)

        salaRef.get()
            .addOnSuccessListener { snapshot ->
                val sala = snapshot.getValue(Sala::class.java)

                if (sala == null) {
                    onComplete(false)
                    return@addOnSuccessListener
                }

                val participante = sala.participantes[jugadorId]

                val fueExpulsado =
                    sala.jugadoresExpulsados[jugadorId] == true ||
                            participante?.estado == EstadoParticipante.EXPULSADO ||
                            participante?.estado == EstadoParticipante.ABANDONO

                if (fueExpulsado) {
                    onComplete(false)
                    return@addOnSuccessListener
                }

                if (!sala.jugadores.containsKey(jugadorId)) {
                    onComplete(false)
                    return@addOnSuccessListener
                }

                val updates = mapOf<String, Any?>(
                    "jugadores/$jugadorId/conectado" to true,
                    "jugadores/$jugadorId/ultimaConexion" to System.currentTimeMillis(),
                    "participantes/$jugadorId/estado" to EstadoParticipante.ACTIVO.name,
                    "participantes/$jugadorId/conectado" to true,
                    "participantes/$jugadorId/ultimaConexion" to System.currentTimeMillis()
                )

                salaRef.updateChildren(updates).addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        registrarPresenciaJugador(salaId, jugadorId)
                    }

                    onComplete(task.isSuccessful)
                }
            }
            .addOnFailureListener {
                onComplete(false)
            }
    }

    fun unirseASala(
        salaId: String,
        jugador: Jugador,
        onComplete: (Boolean) -> Unit
    ) {
        val salaRef = salasRef.child(salaId)

        salaRef.get()
            .addOnSuccessListener { snapshot ->
                val sala = snapshot.getValue(Sala::class.java)

                if (sala == null) {
                    onComplete(false)
                    return@addOnSuccessListener
                }

                val participante = sala.participantes[jugador.id]
                val fueExpulsado =
                    sala.jugadoresExpulsados[jugador.id] == true ||
                            participante?.estado == EstadoParticipante.EXPULSADO ||
                            participante?.estado == EstadoParticipante.ABANDONO

                if (fueExpulsado) {
                    onComplete(false)
                    return@addOnSuccessListener
                }

                val yaExisteComoJugador =
                    sala.jugadores.containsKey(jugador.id)

                /*
                 * Candado principal:
                 * si la partida ya empezó, nadie nuevo entra.
                 * Solo puede volver alguien que ya existe en jugadores
                 * y cuyo participante siga activo o desconectado.
                 */
                if (sala.estaEnJuego) {
                    val puedeReconectarDurantePartida =
                        yaExisteComoJugador &&
                                participante != null &&
                                (
                                        participante.estado == EstadoParticipante.ACTIVO ||
                                                participante.estado == EstadoParticipante.DESCONECTADO
                                        )

                    if (!puedeReconectarDurantePartida) {
                        onComplete(false)
                        return@addOnSuccessListener
                    }
                }

                if (!yaExisteComoJugador && sala.jugadores.size >= 6) {
                    onComplete(false)
                    return@addOnSuccessListener
                }

                val jugadorConectado = jugador.copy(
                    conectado = true,
                    ultimaConexion = System.currentTimeMillis()
                )

                val updates = mutableMapOf<String, Any?>()

                updates["jugadores/${jugador.id}"] = jugadorConectado
                updates["participantes/${jugador.id}"] = ParticipantePartida(
                    id = jugador.id,
                    nombre = jugador.nombre,
                    estado = EstadoParticipante.ACTIVO,
                    partidaId = sala.partidaId,
                    conectado = true,
                    ultimaConexion = System.currentTimeMillis()
                )

                salaRef.updateChildren(updates).addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        registrarPresenciaJugador(
                            salaId = salaId,
                            jugadorId = jugador.id
                        )
                    }

                    onComplete(task.isSuccessful)
                }
            }
            .addOnFailureListener {
                onComplete(false)
            }
    }

    // ─────────────────────────────────────────
    // OBSERVAR SALA EN TIEMPO REAL
    // ─────────────────────────────────────────

    fun observarSala(salaId: String, onUpdate: (Sala?) -> Unit): ValueEventListener {
        val salaRef = salasRef.child(salaId)

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) {
                    onUpdate(null)
                    return
                }

                val updates = mutableMapOf<String, Any?>()

                val jugadoresSnap = snapshot.child("jugadores")
                val expulsadosSnap = snapshot.child("jugadoresExpulsados")

                jugadoresSnap.children.forEach { jugadorSnap ->
                    val jugadorKey = jugadorSnap.key ?: return@forEach

                    val idCampo = jugadorSnap
                        .child("id")
                        .getValue(String::class.java)
                        .orEmpty()

                    val nombreCampo = jugadorSnap
                        .child("nombre")
                        .getValue(String::class.java)
                        .orEmpty()

                    val fueExpulsado =
                        expulsadosSnap
                            .child(jugadorKey)
                            .getValue(Boolean::class.java) == true

                    val esNodoFantasma =
                        idCampo.isBlank() ||
                                nombreCampo.isBlank() ||
                                idCampo != jugadorKey

                    if (esNodoFantasma || fueExpulsado) {
                        updates["jugadores/$jugadorKey"] = null
                    }
                }

                /*
                 * Si encontramos nodos inválidos, primero los limpiamos
                 * y NO enviamos esa sala a la UI.
                 *
                 * Esto evita que GameTableScreen pinte por un instante
                 * al jugador fantasma.
                 */
                if (updates.isNotEmpty()) {
                    salaRef.updateChildren(updates)
                    return
                }

                val sala = snapshot.getValue(Sala::class.java)
                onUpdate(sala)
            }

            override fun onCancelled(error: DatabaseError) {}
        }

        salaRef.addValueEventListener(listener)
        return listener
    }

    fun dejarDeObservarSala(salaId: String, listener: ValueEventListener) {
        salasRef.child(salaId).removeEventListener(listener)
    }

    private fun participanteActivo(
        jugador: Jugador,
        partidaId: String = ""
    ): ParticipantePartida {
        return ParticipantePartida(
            id = jugador.id,
            nombre = jugador.nombre,
            estado = EstadoParticipante.ACTIVO,
            partidaId = partidaId,
            conectado = true,
            ultimaConexion = System.currentTimeMillis()
        )
    }

}
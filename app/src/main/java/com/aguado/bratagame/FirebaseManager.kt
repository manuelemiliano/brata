package com.aguado.bratagame

import com.aguado.bratagame.game.TurnManager
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import com.aguado.bratagame.EntregaCartaEspiadoAnimando

object FirebaseManager {

    // lazy para evitar inicializar Firebase durante Previews del IDE
    private val database: FirebaseDatabase by lazy { Firebase.database }
    private val salasRef by lazy { database.getReference("salas") }
    private val connectedRef by lazy { database.getReference(".info/connected") }

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
        actualizaciones["timestampInicioContador"] = System.currentTimeMillis()

        // 6. Primer turno: el anfitrión
        val anfitrion = jugadores.firstOrNull { it.esAnfitrion } ?: jugadores.first()
        actualizaciones["turnoActualId"] = anfitrion.id

        // 7. Limpiar estado global por si es una revancha
        actualizaciones["brataActivada"] = false
        actualizaciones["brataJugadorId"] = ""
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
        actualizaciones["ventanaFinalRonda"] = mapOf<String, Any>()



        // 8. Identificador de partida.
        // Sirve para que todos los clientes detecten revancha aunque estén en RESULTADO.
        actualizaciones["partidaId"] = "partida_${System.currentTimeMillis()}"

        // 9. Marcar juego iniciado — dispara navegación en todos los clientes
        actualizaciones["estaEnJuego"] = true

        salasRef.child(salaId).updateChildren(actualizaciones)
    }

    // ─────────────────────────────────────────
    // OBSERVADORES
    // Los observadores se unen pero NO como jugadores activos.
    // Ven la sala completa con todas las cartas abiertas (modo dios).
    // ─────────────────────────────────────────

    fun unirseComoObservador(salaId: String, observador: Observador, onComplete: (Boolean) -> Unit) {
        salasRef.child(salaId)
            .child("observadores")
            .child(observador.id)
            .setValue(observador)
            .addOnCompleteListener { onComplete(it.isSuccessful) }
    }

    fun salirComoObservador(salaId: String, observadorId: String) {
        salasRef.child(salaId)
            .child("observadores")
            .child(observadorId)
            .removeValue()
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

        // Reiniciar jugadores, pero conservarlos en la sala.
        sala.jugadores.values.forEach { jugador ->
            actualizaciones["jugadores/${jugador.id}/cartas"] = emptyList<Carta>()
            actualizaciones["jugadores/${jugador.id}/cartaEnMano"] = mapOf<String, Any>()
            actualizaciones["jugadores/${jugador.id}/estaListo"] = false
            actualizaciones["jugadores/${jugador.id}/erroresDescarte"] = 0
            actualizaciones["jugadores/${jugador.id}/descalificado"] = false

            // Conservamos presencia.
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

                    val desconexion = mapOf<String, Any>(
                        "conectado" to false,
                        "ultimaConexion" to System.currentTimeMillis()
                    )

                    jugadorRef.onDisconnect().updateChildren(desconexion)

                    jugadorRef.updateChildren(
                        mapOf(
                            "conectado" to true,
                            "ultimaConexion" to System.currentTimeMillis()
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

                    val hayJugadoresConectados = jugadoresValidos.any { it.conectado }

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
        val jugadorRef = salasRef
            .child(salaId)
            .child("jugadores")
            .child(jugadorId)

        jugadorRef.get().addOnSuccessListener { snapshot ->
            if (!snapshot.exists()) {
                onComplete(false)
                return@addOnSuccessListener
            }

            jugadorRef.updateChildren(
                mapOf(
                    "conectado" to true,
                    "ultimaConexion" to System.currentTimeMillis()
                )
            ).addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    registrarPresenciaJugador(salaId, jugadorId)
                }

                onComplete(task.isSuccessful)
            }
        }.addOnFailureListener {
            onComplete(false)
        }
    }

    fun unirseASala(salaId: String, jugador: Jugador, onComplete: (Boolean) -> Unit) {
        val salaRef = salasRef.child(salaId)

        salaRef.get()
            .addOnSuccessListener { snapshot ->
                val sala = snapshot.getValue(Sala::class.java)

                if (sala == null) {
                    onComplete(false)
                    return@addOnSuccessListener
                }

                val fueExpulsado =
                    sala.jugadoresExpulsados[jugador.id] == true

                if (fueExpulsado && sala.estaEnJuego) {
                    onComplete(false)
                    return@addOnSuccessListener
                }

                val jugadorConectado = jugador.copy(
                    conectado = true,
                    ultimaConexion = System.currentTimeMillis()
                )

                salaRef
                    .child("jugadores")
                    .child(jugador.id)
                    .setValue(jugadorConectado)
                    .addOnCompleteListener { task ->
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
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val sala = snapshot.getValue(Sala::class.java)
                onUpdate(sala)
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        salasRef.child(salaId).addValueEventListener(listener)
        return listener
    }

    fun dejarDeObservarSala(salaId: String, listener: ValueEventListener) {
        salasRef.child(salaId).removeEventListener(listener)
    }

}
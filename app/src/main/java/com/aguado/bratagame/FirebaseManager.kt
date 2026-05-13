package com.aguado.bratagame

import com.aguado.bratagame.game.TurnManager
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase

object FirebaseManager {

    // lazy para evitar inicializar Firebase durante Previews del IDE
    private val database: FirebaseDatabase by lazy { Firebase.database }
    private val salasRef by lazy { database.getReference("salas") }

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

        val actualizaciones = mutableMapOf<String, Any>()
        var punteroMazo = 0

        // 2. Repartir 4 cartas a cada jugador
        jugadores.forEach { jugador ->
            val cartasAsignadas = mazoCompleto.subList(punteroMazo, punteroMazo + 4).toList()
            actualizaciones["jugadores/${jugador.id}/cartas"] = cartasAsignadas
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

        // 7. Limpiar estado de brata por si es una revancha
        actualizaciones["brataActivada"] = false
        actualizaciones["brataJugadorId"] = ""
        actualizaciones["cartaPoderActiva"] = mapOf<String, Any>()

        // 8. Marcar juego iniciado — dispara navegación en todos los clientes
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
                val lista = snapshot.children.mapNotNull { it.getValue(Sala::class.java) }
                // Solo mostrar salas activas que NO estén en juego aún
                onUpdate(lista.filter { it.estaActiva && !it.estaEnJuego })
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        salasRef.addValueEventListener(listener)
        return listener
    }

    fun salirDeSala(salaId: String, jugadorId: String, esAnfitrion: Boolean) {
        val salaRef = salasRef.child(salaId)

        salaRef.child("jugadores").child(jugadorId).removeValue().addOnCompleteListener {
            salaRef.child("jugadores").get().addOnSuccessListener { snapshot ->
                when {
                    // Sala vacía → eliminar
                    !snapshot.exists() || snapshot.childrenCount == 0L -> {
                        salaRef.removeValue()
                    }
                    // Anfitrión se va con gente dentro → cerrar sala
                    esAnfitrion -> {
                        salaRef.removeValue()
                    }
                    // Jugador normal se va → la sala continúa
                }
            }
        }
    }

    fun crearSala(nombreSala: String, anfitrion: Jugador, onComplete: (String?) -> Unit) {
        val salaId = salasRef.push().key ?: return
        val nuevaSala = Sala(
            id = salaId,
            nombreSala = nombreSala,
            jugadores = mapOf(anfitrion.id to anfitrion),
            estaEnJuego = false,
            estaActiva = true
        )
        salasRef.child(salaId).setValue(nuevaSala).addOnCompleteListener {
            onComplete(if (it.isSuccessful) salaId else null)
        }
    }

    fun unirseASala(salaId: String, jugador: Jugador, onComplete: (Boolean) -> Unit) {
        salasRef.child(salaId)
            .child("jugadores")
            .child(jugador.id)
            .setValue(jugador)
            .addOnCompleteListener { onComplete(it.isSuccessful) }
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
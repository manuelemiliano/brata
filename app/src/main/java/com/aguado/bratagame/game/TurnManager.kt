package com.aguado.bratagame.game

import com.aguado.bratagame.Sala
import com.google.firebase.database.FirebaseDatabase

// ─────────────────────────────────────────────
// TURN MANAGER
//
// Responsabilidades:
//   - Determinar a quién le toca
//   - Detectar si el ciclo de Brata terminó
//   - Iniciar la evaluación final cuando corresponde
//   - Proveer helpers de estado de turno a la UI
//
// NO escribe cartas ni ejecuta acciones — eso es GameActions.
// ─────────────────────────────────────────────

object TurnManager {

    private val salasRef by lazy {
        FirebaseDatabase.getInstance().getReference("salas")
    }

    // ─────────────────────────────────────────
    // ESTADO DEL TURNO
    // Lo usa GameTableScreen para saber qué mostrar.
    // ─────────────────────────────────────────

    data class EstadoTurno(
        val esMiTurno: Boolean,
        val jugadorEnTurnoNombre: String,
        val brataActivada: Boolean,
        val esFinalDeRonda: Boolean,
        val puedePresionarBrata: Boolean,
        val puedeRobar: Boolean
    )

    fun calcularEstadoTurno(
        jugadorLocalId: String,
        sala: Sala
    ): EstadoTurno {
        val esMiTurno = sala.turnoActualId == jugadorLocalId
        val jugadorEnTurno = sala.jugadores[sala.turnoActualId]
        val brataActivada = sala.brataActivada

        // El ciclo de Brata terminó cuando el turno vuelve al jugador que la presionó
        val esFinalDeRonda = brataActivada && sala.turnoActualId == sala.brataJugadorId

        // Solo puede presionar BRATA el jugador en turno, antes de robar,
        // y solo si la Brata no fue activada aún
        val puedePresionarBrata = esMiTurno && !brataActivada &&
                (sala.jugadores[jugadorLocalId]?.cartaEnMano == null)

        val puedeRobar = esMiTurno &&
                (sala.jugadores[jugadorLocalId]?.cartaEnMano == null)

        return EstadoTurno(
            esMiTurno = esMiTurno,
            jugadorEnTurnoNombre = jugadorEnTurno?.nombre ?: "",
            brataActivada = brataActivada,
            esFinalDeRonda = esFinalDeRonda,
            puedePresionarBrata = puedePresionarBrata,
            puedeRobar = puedeRobar
        )
    }

    // ─────────────────────────────────────────
    // INICIAR PRIMER TURNO
    // Lo llama FirebaseManager al iniciar la partida.
    // El primer turno es del anfitrión.
    // ─────────────────────────────────────────

    fun iniciarPrimerTurno(salaId: String, sala: Sala) {
        val anfitrion = sala.jugadores.values.firstOrNull { it.esAnfitrion }
            ?: sala.jugadores.values.firstOrNull()
            ?: return

        salasRef.child(salaId).child("turnoActualId").setValue(anfitrion.id)
    }

    // ─────────────────────────────────────────
    // AVANZAR TURNO
    // Llama GameActions al final de cada acción.
    // Aquí lo exponemos por si TurnManager necesita
    // avanzar independientemente (ej: adelantado perdonado).
    // ─────────────────────────────────────────

    fun avanzarTurno(salaId: String, jugadorActualId: String, sala: Sala) {
        val siguiente = siguienteTurno(jugadorActualId, sala)
        salasRef.child(salaId).child("turnoActualId").setValue(siguiente)
    }

    // ─────────────────────────────────────────
    // DETECTAR FIN DE RONDA (Brata completada)
    // GameTableScreen llama esto en cada update de sala.
    // Si regresa true, debe navegar a la pantalla de resultados.
    // ─────────────────────────────────────────

    fun debeEvaluarFinal(sala: Sala): Boolean {
        return sala.brataActivada && sala.turnoActualId == sala.brataJugadorId
    }

    // ─────────────────────────────────────────
    // ORDEN DE JUGADORES DESDE LA PERSPECTIVA LOCAL
    // Devuelve los IDs en orden horario empezando
    // por el jugador local (él es el índice 0).
    // GameTableScreen usa esto para posicionar los jugadores.
    // ─────────────────────────────────────────

    fun ordenDesdeJugadorLocal(
        jugadorLocalId: String,
        sala: Sala
    ): List<String> {
        val ids = sala.jugadores.keys.toList()
        val indexLocal = ids.indexOf(jugadorLocalId)
        if (indexLocal < 0) return ids

        // Rotar la lista para que el local quede primero
        return ids.drop(indexLocal) + ids.take(indexLocal)
    }

    // ─────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────

    private fun siguienteTurno(jugadorActualId: String, sala: Sala): String {
        val jugadores = sala.jugadores.keys.toList()
        if (jugadores.isEmpty()) return ""
        val indexActual = jugadores.indexOf(jugadorActualId)
        val indexSiguiente = (indexActual + 1) % jugadores.size
        return jugadores[indexSiguiente]
    }
}
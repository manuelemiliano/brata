package com.aguado.bratagame.bot

import com.aguado.bratagame.FirebaseManager
import com.aguado.bratagame.Jugador
import com.aguado.bratagame.Sala
import com.google.firebase.database.FirebaseDatabase

// ─────────────────────────────────────────────
// BOT LOBBY ACTIONS
//
// Helpers de alto nivel que la UI del lobby invoca
// para agregar o quitar bots.
//
// Estos métodos:
//   1. Crean/eliminan al bot como Jugador dentro de Sala (vía FirebaseManager o
//      escritura directa cuando hace falta el flag esBot).
//   2. Actualizan ConfigBots en su nodo hermano.
//   3. Limpian la memoria del bot al eliminarlo.
//
// El bot creado tiene:
//   - id = "bot_${UUID}" (BotIdentidad.generarIdBot)
//   - esBot = true
//   - estaListo = true (para no bloquear el botón "COMENZAR JUEGO")
//   - conectado = true (no tiene presencia real, pero debe contar como presente)
//   - esAnfitrion = false
// ─────────────────────────────────────────────

object BotLobbyActions {

    private val salasRef by lazy {
        FirebaseDatabase.getInstance().getReference("salas")
    }

    private const val MAX_JUGADORES_TOTAL = 6
    private const val MAX_BOTS = 5

    // ─────────────────────────────────────────
    // AGREGAR BOT
    //
    // Reglas:
    //   - No exceder 6 jugadores totales.
    //   - No exceder 5 bots.
    //   - Solo el host puede invocar (la UI ya lo valida).
    //   - La partida no puede haber iniciado.
    // ─────────────────────────────────────────

    fun agregarBot(
        salaId: String,
        sala: Sala,
        onComplete: (exito: Boolean, mensaje: String) -> Unit = { _, _ -> }
    ) {
        if (sala.estaEnJuego) {
            onComplete(false, "La partida ya inició; no se pueden agregar bots")
            return
        }

        val jugadoresActuales = sala.jugadores.values.toList()
        val totalActual = jugadoresActuales.size

        if (totalActual >= MAX_JUGADORES_TOTAL) {
            onComplete(false, "La mesa está llena")
            return
        }

        val cantidadBotsActual = jugadoresActuales.count { it.esBot }

        if (cantidadBotsActual >= MAX_BOTS) {
            onComplete(false, "Máximo $MAX_BOTS bots por mesa")
            return
        }

        val nuevoBotId = BotIdentidad.generarIdBot()
        val nombreBot = elegirNombreBotDisponible(jugadoresActuales)

        val nuevoBot = Jugador(
            id = nuevoBotId,
            nombre = nombreBot,
            estaListo = true,
            esAnfitrion = false,
            cartas = emptyList(),
            cartaEnMano = null,
            conectado = true,
            ultimaConexion = System.currentTimeMillis(),
            erroresDescarte = 0,
            descalificado = false,
            esBot = true
        )

        val updates = mutableMapOf<String, Any?>()
        updates["jugadores/$nuevoBotId"] = nuevoBot

        salasRef.child(salaId).updateChildren(updates)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    // Sincronizar nodo hermano de configuración
                    BotFirebaseRepository.establecerCantidadBots(
                        salaId = salaId,
                        cantidad = cantidadBotsActual + 1
                    )

                    onComplete(true, "Bot agregado")
                } else {
                    onComplete(false, "No se pudo agregar el bot")
                }
            }
    }

    // ─────────────────────────────────────────
    // QUITAR ÚLTIMO BOT
    //
    // Política: el botón − del lobby quita siempre el bot
    // agregado más recientemente. Sencillo y predecible.
    //
    // Si el host quisiera quitar un bot específico en el futuro,
    // se podría exponer un quitarBotPorId(salaId, botId).
    // ─────────────────────────────────────────

    fun quitarUltimoBot(
        salaId: String,
        sala: Sala,
        onComplete: (exito: Boolean, mensaje: String) -> Unit = { _, _ -> }
    ) {
        if (sala.estaEnJuego) {
            onComplete(false, "La partida ya inició; no se pueden quitar bots")
            return
        }

        // El "último bot" es el que tenga el ultimaConexion más reciente.
        // Como todos los bots se crean con System.currentTimeMillis(),
        // esto da el orden de agregación.
        val ultimoBot = sala.jugadores.values
            .filter { it.esBot }
            .maxByOrNull { it.ultimaConexion }

        if (ultimoBot == null) {
            onComplete(false, "No hay bots para quitar")
            return
        }

        eliminarBot(
            salaId = salaId,
            botId = ultimoBot.id,
            cantidadRestante = sala.jugadores.values.count { it.esBot } - 1,
            onComplete = onComplete
        )
    }

    fun quitarBotPorId(
        salaId: String,
        sala: Sala,
        botId: String,
        onComplete: (exito: Boolean, mensaje: String) -> Unit = { _, _ -> }
    ) {
        if (sala.estaEnJuego) {
            onComplete(false, "La partida ya inició; no se pueden quitar bots")
            return
        }

        val bot = sala.jugadores[botId]

        if (bot == null || !bot.esBot) {
            onComplete(false, "No es un bot válido")
            return
        }

        eliminarBot(
            salaId = salaId,
            botId = botId,
            cantidadRestante = sala.jugadores.values.count { it.esBot } - 1,
            onComplete = onComplete
        )
    }

    private fun eliminarBot(
        salaId: String,
        botId: String,
        cantidadRestante: Int,
        onComplete: (exito: Boolean, mensaje: String) -> Unit
    ) {
        val updates = mutableMapOf<String, Any?>()
        updates["jugadores/$botId"] = null

        salasRef.child(salaId).updateChildren(updates)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    // Sincronizar configuración y limpiar memoria del bot eliminado
                    BotFirebaseRepository.establecerCantidadBots(
                        salaId = salaId,
                        cantidad = cantidadRestante.coerceAtLeast(0)
                    )

                    BotFirebaseRepository.eliminarMemoriaBot(
                        salaId = salaId,
                        botId = botId
                    )

                    onComplete(true, "Bot eliminado")
                } else {
                    onComplete(false, "No se pudo eliminar el bot")
                }
            }
    }

    // ─────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────

    private fun elegirNombreBotDisponible(jugadoresActuales: List<Jugador>): String {
        val nombresUsados = jugadoresActuales
            .filter { it.esBot }
            .map { it.nombre }
            .toSet()

        return BotIdentidad.NOMBRES_BOTS.firstOrNull { it !in nombresUsados }
            ?: "Bot ${jugadoresActuales.count { it.esBot } + 1}"
    }

    // ─────────────────────────────────────────
    // CONTADORES PARA UI
    // ─────────────────────────────────────────

    fun cantidadBotsEnSala(sala: Sala): Int =
        sala.jugadores.values.count { it.esBot }

    fun cantidadHumanosEnSala(sala: Sala): Int =
        sala.jugadores.values.count { !it.esBot }

    fun puedeAgregarMasBots(sala: Sala): Boolean {
        if (sala.estaEnJuego) return false
        val total = sala.jugadores.size
        val bots = cantidadBotsEnSala(sala)
        return total < MAX_JUGADORES_TOTAL && bots < MAX_BOTS
    }

    fun puedeQuitarBots(sala: Sala): Boolean {
        if (sala.estaEnJuego) return false
        return cantidadBotsEnSala(sala) > 0
    }
}

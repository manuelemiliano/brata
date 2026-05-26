package com.aguado.bratagame.bot

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.MutableData
import com.google.firebase.database.Transaction
import com.google.firebase.database.ValueEventListener

// ─────────────────────────────────────────────
// BOT FIREBASE REPOSITORY
//
// Único punto de acceso a los nodos hermanos del bot:
//   /configuracion_bots/{salaId}
//   /memorias_bots/{salaId}/{botId}
//   /locks_bot/{salaId}
//
// El nodo /salas/{salaId} (Sala) NO se toca desde aquí.
// Las escrituras al estado de juego (cartas, turnos, etc.)
// siguen pasando por GameActions, igual que un humano.
//
// Esto preserva la arquitectura de impacto mínimo acordada:
// si en el futuro se elimina el feature de bots, basta con
// borrar este package y los nodos hermanos. Sala queda intacta.
// ─────────────────────────────────────────────

object BotFirebaseRepository {

    private val database by lazy { FirebaseDatabase.getInstance() }

    private val configBotsRef by lazy { database.getReference("configuracion_bots") }
    private val memoriasBotsRef by lazy { database.getReference("memorias_bots") }
    private val locksBotRef by lazy { database.getReference("locks_bot") }

    // ── Tiempo máximo que un lock se considera vigente ──
    // Si un host muere mientras un bot juega, el siguiente host
    // verá un lock viejo y lo liberará para no bloquear el juego.
    private const val LOCK_BOT_TIMEOUT_MS = 15_000L

    // ─────────────────────────────────────────
    // CONFIGURACIÓN DE BOTS
    // ─────────────────────────────────────────

    fun obtenerConfigBots(
        salaId: String,
        onResult: (ConfigBots) -> Unit
    ) {
        configBotsRef.child(salaId).get()
            .addOnSuccessListener { snapshot ->
                val config = snapshot.getValue(ConfigBots::class.java) ?: ConfigBots()
                onResult(config)
            }
            .addOnFailureListener {
                onResult(ConfigBots())
            }
    }

    fun observarConfigBots(
        salaId: String,
        onUpdate: (ConfigBots) -> Unit
    ): ValueEventListener {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val config = snapshot.getValue(ConfigBots::class.java) ?: ConfigBots()
                onUpdate(config)
            }

            override fun onCancelled(error: DatabaseError) {}
        }

        configBotsRef.child(salaId).addValueEventListener(listener)
        return listener
    }

    fun dejarDeObservarConfigBots(
        salaId: String,
        listener: ValueEventListener
    ) {
        configBotsRef.child(salaId).removeEventListener(listener)
    }

    fun establecerCantidadBots(
        salaId: String,
        cantidad: Int,
        onComplete: (Boolean) -> Unit = {}
    ) {
        val configActualizada = ConfigBots(cantidad = cantidad.coerceIn(0, 5))

        configBotsRef.child(salaId)
            .setValue(configActualizada)
            .addOnCompleteListener { task -> onComplete(task.isSuccessful) }
    }

    // ─────────────────────────────────────────
    // MEMORIA DE BOTS
    // ─────────────────────────────────────────

    fun obtenerMemoriaBot(
        salaId: String,
        botId: String,
        onResult: (MemoriaBot) -> Unit
    ) {
        memoriasBotsRef.child(salaId).child(botId).get()
            .addOnSuccessListener { snapshot ->
                val memoria = try {
                    snapshot.getValue(MemoriaBot::class.java) ?: MemoriaBot()
                } catch (e: Exception) {
                    // Datos viejos en Firebase con formato incompatible.
                    // Devolvemos memoria vacía para que el bot arranque limpio.
                    android.util.Log.w(
                        "BotFirebaseRepository",
                        "Memoria del bot $botId no se pudo deserializar, se usa vacía: ${e.message}"
                    )
                    MemoriaBot()
                }
                onResult(memoria)
            }
            .addOnFailureListener {
                onResult(MemoriaBot())
            }
    }

    fun guardarMemoriaBot(
        salaId: String,
        botId: String,
        memoria: MemoriaBot,
        onComplete: (Boolean) -> Unit = {}
    ) {
        memoriasBotsRef.child(salaId).child(botId)
            .setValue(memoria)
            .addOnCompleteListener { task -> onComplete(task.isSuccessful) }
    }

    fun observarMemoriasBots(
        salaId: String,
        onUpdate: (Map<String, MemoriaBot>) -> Unit
    ): ValueEventListener {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val mapa = mutableMapOf<String, MemoriaBot>()

                snapshot.children.forEach { hijo ->
                    val botId = hijo.key ?: return@forEach
                    val memoria = try {
                        hijo.getValue(MemoriaBot::class.java) ?: MemoriaBot()
                    } catch (e: Exception) {
                        // Datos viejos en Firebase con formato incompatible.
                        // Loguear y seguir con memoria vacía para que la app no crashee.
                        android.util.Log.w(
                            "BotFirebaseRepository",
                            "Memoria del bot $botId no se pudo deserializar: ${e.message}"
                        )
                        MemoriaBot()
                    }
                    mapa[botId] = memoria
                }

                onUpdate(mapa)
            }

            override fun onCancelled(error: DatabaseError) {}
        }

        memoriasBotsRef.child(salaId).addValueEventListener(listener)
        return listener
    }

    fun dejarDeObservarMemoriasBots(
        salaId: String,
        listener: ValueEventListener
    ) {
        memoriasBotsRef.child(salaId).removeEventListener(listener)
    }

    fun eliminarMemoriaBot(
        salaId: String,
        botId: String
    ) {
        memoriasBotsRef.child(salaId).child(botId).removeValue()
    }

    fun eliminarTodasLasMemorias(salaId: String) {
        memoriasBotsRef.child(salaId).removeValue()
    }

    // ─────────────────────────────────────────
    // LOCKS DE TURNO DE BOT
    //
    // Antes de ejecutar un movimiento de bot, el host toma el lock
    // vía transacción. Si dos hosts compiten, solo uno gana.
    //
    // Si encuentra un lock vencido (más de LOCK_BOT_TIMEOUT_MS), lo
    // sobrescribe (auto-liberación de locks abandonados).
    // ─────────────────────────────────────────

    fun intentarTomarLock(
        salaId: String,
        botId: String,
        hostId: String,
        onResult: (exito: Boolean) -> Unit
    ) {
        val lockRef = locksBotRef.child(salaId)
        val ahora = System.currentTimeMillis()

        lockRef.runTransaction(object : Transaction.Handler {
            override fun doTransaction(currentData: MutableData): Transaction.Result {
                val lockActual = currentData.getValue(LockBotTurno::class.java)

                val lockEstaLibre = lockActual == null ||
                        lockActual.botId.isBlank() ||
                        ahora - lockActual.timestamp > LOCK_BOT_TIMEOUT_MS

                if (!lockEstaLibre) {
                    return Transaction.abort()
                }

                currentData.value = LockBotTurno(
                    botId = botId,
                    hostId = hostId,
                    timestamp = ahora
                )

                return Transaction.success(currentData)
            }

            override fun onComplete(
                error: DatabaseError?,
                committed: Boolean,
                currentData: DataSnapshot?
            ) {
                onResult(committed)
            }
        })
    }

    fun liberarLock(salaId: String) {
        locksBotRef.child(salaId).removeValue()
    }

    // ─────────────────────────────────────────
    // LIMPIEZA TOTAL DEL ESTADO DE BOTS
    // Útil al cerrar la sala o al volver al lobby.
    // ─────────────────────────────────────────

    fun limpiarTodoElEstadoDeBots(salaId: String) {
        configBotsRef.child(salaId).removeValue()
        memoriasBotsRef.child(salaId).removeValue()
        locksBotRef.child(salaId).removeValue()
    }
}

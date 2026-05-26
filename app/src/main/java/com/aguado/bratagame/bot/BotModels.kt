package com.aguado.bratagame.bot

// ─────────────────────────────────────────────
// BOT MODELS
//
// Modelos de datos del subsistema de bots.
// Todo lo del bot vive en el package com.aguado.bratagame.bot
// y se persiste en nodos hermanos de Firebase (no dentro de Sala),
// excepto el flag esBot que sí vive en Jugador para que el bot
// aparezca en mesa como un jugador normal.
//
// Nodos Firebase relacionados (definidos por BotFirebaseRepository):
//   /configuracion_bots/{salaId}    → ConfigBots
//   /memorias_bots/{salaId}/{botId} → MemoriaBot
//   /locks_bot/{salaId}             → LockBotTurno
// ─────────────────────────────────────────────

// ─────────────────────────────────────────────
// CONFIG BOTS
// Configuración a nivel sala. La pone el host en el lobby.
// En Fase 1 solo guarda cantidad; en fases futuras podría
// guardar nivel u otros parámetros.
// ─────────────────────────────────────────────

data class ConfigBots(
    var cantidad: Int = 0
)

// ─────────────────────────────────────────────
// CARTA CONOCIDA
// Una carta que el bot conoce con certeza, junto con
// el momento y la fuente por la que la aprendió.
//
// La fuente sirve para auditar y para que en fases futuras
// el bot pueda "olvidar" cartas viejas si se quisiera realismo extra.
// ─────────────────────────────────────────────

data class CartaConocida(
    var valor: String = "",
    var palo: String = "",
    var timestamp: Long = 0L,
    var fuente: String = ""   // "inicial" | "espiada" | "descarte_propio" | "cambio_viendo" | "descarte_ajeno_visto"
)

// ─────────────────────────────────────────────
// MEMORIA BOT
// La única fuente de información del bot.
// El BotBrain NUNCA accede a sala.jugadores[*].cartas[*].valor
// salvo a través de esta estructura.
//
// Notas de serialización Firebase:
//   - Firebase Realtime Database tiene problemas con Maps anidados y con
//     algunas inferencias de tipos cuando una colección es vacía. Para
//     evitarlos, todas las estructuras de esta clase son planas.
//   - cartasPropiasConocidas: Map<posicion-como-string, CartaConocida>.
//     Las keys son "0".."3".
//   - cartasRivalesConocidas: Map<clave-compuesta, CartaConocida>.
//     Las keys son del formato "{rivalId}__{posicion}" para evitar mapas
//     anidados (que Firebase a veces serializa como listas).
//     Usar las funciones helper de abajo para construir/parsear claves.
// ─────────────────────────────────────────────

data class MemoriaBot(
    var cartasPropiasConocidas: Map<String, CartaConocida> = emptyMap(),
    var cartasRivalesConocidas: Map<String, CartaConocida> = emptyMap(),
    var descarteHistoricoIds: List<String> = emptyList(),
    var conteoValoresVistos: Map<String, Int> = emptyMap(),
    var ultimaActualizacionTs: Long = 0L
)

// ─────────────────────────────────────────────
// HELPERS DE CLAVES PARA cartasRivalesConocidas
// Las claves tienen el formato "{rivalId}__{posicion}".
// Cualquier acceso a este mapa debe pasar por estas funciones para
// garantizar consistencia.
// ─────────────────────────────────────────────

object ClaveCartaRival {
    private const val SEPARADOR = "__"

    fun construir(rivalId: String, posicion: Int): String =
        "$rivalId$SEPARADOR$posicion"

    fun extraerRivalId(clave: String): String? {
        val partes = clave.split(SEPARADOR)
        return if (partes.size == 2) partes[0] else null
    }

    fun extraerPosicion(clave: String): Int? {
        val partes = clave.split(SEPARADOR)
        return if (partes.size == 2) partes[1].toIntOrNull() else null
    }

    fun coincideRival(clave: String, rivalId: String): Boolean {
        return extraerRivalId(clave) == rivalId
    }
}

// ─────────────────────────────────────────────
// LOCK BOT TURNO
// Lock simple para evitar que dos hosts (durante traspaso)
// ejecuten el mismo turno de bot por duplicado.
//
// Se toma vía transacción de Firebase (ver BotFirebaseRepository).
// El timestamp permite a un nuevo host detectar y liberar locks
// abandonados si el host anterior cayó.
// ─────────────────────────────────────────────

data class LockBotTurno(
    var botId: String = "",
    var hostId: String = "",
    var timestamp: Long = 0L
)

// ─────────────────────────────────────────────
// DECISIÓN BOT (sealed class)
//
// Resultado del BotBrain. Cada subclase representa una acción
// concreta que el BotOrchestrator traducirá a una llamada a
// GameActions (o, en el caso de NoOp, a no hacer nada).
//
// En Fase 1 solo se usan RobarDelPozo y Descartar (acción trivial).
// Las demás subclases ya están declaradas para que las fases
// siguientes no tengan que tocar este archivo.
// ─────────────────────────────────────────────

sealed class DecisionBot {

    // ── Inicio de turno ──
    object CantarBrata : DecisionBot()
    object RobarDelPozo : DecisionBot()
    object RobarDelDescarte : DecisionBot()
    object PasarTurnoBrata : DecisionBot()

    // ── Carta en mano (acciones post-robo) ──
    object Descartar : DecisionBot()
    data class CambiarPorPosicion(val posicion: Int) : DecisionBot()
    data class ActivarPoder(val tipoPoder: String) : DecisionBot()
    object ActivarDescarteFree : DecisionBot()
    data class SeleccionarComodin(val valor: String, val palo: String) : DecisionBot()
    data class RobarDescarteAdelantado(val posicionPropiaACeder: Int) : DecisionBot()

    // ── Durante poder propio ──
    data class EspiarCarta(val cartaId: String) : DecisionBot()
    data class EspiarCartaCambioViendo(val cartaId: String) : DecisionBot()
    object DescartarCartaEspiada : DecisionBot()
    object RegresarCartaEspiada : DecisionBot()
    data class ConfirmarSwapViendo(
        val jugadorAId: String,
        val cartaAId: String,
        val jugadorBId: String,
        val cartaBId: String
    ) : DecisionBot()
    data class ConfirmarSwapSinVer(
        val jugadorAId: String,
        val cartaAId: String,
        val jugadorBId: String,
        val cartaBId: String
    ) : DecisionBot()
    data class ConfirmarDescarteFree(val posicion: Int) : DecisionBot()

    // ── Fuera de turno ──
    object ReclamarVoy : DecisionBot()
    data class SeleccionarObjetivoVoy(val rivalId: String, val posicion: Int) : DecisionBot()
    data class EntregarCartaVoy(val posicion: Int) : DecisionBot()
    data class DescarteEspontaneo(val posicion: Int) : DecisionBot()

    // ── Sin acción ──
    object NoOp : DecisionBot()
}

// ─────────────────────────────────────────────
// CATEGORÍA DE DELAY
// Cada DecisionBot pertenece a una categoría que define
// el rango de delay humanizador antes de ejecutarla.
//
// Tabla:
//   PENSAMIENTO_PROFUNDO  → 2000–3000 ms  (cantar BRATA)
//   DECISION_TURNO        → 1200–2500 ms  (robar, pasar)
//   ACCION_POST_ROBO      → 1500–2500 ms  (descartar, cambiar, activar poder, comodín, descarte free)
//   SELECCION_DURANTE_PODER → 1000–1800 ms (espiar, swap, regresar, elegir en descarte free)
//   REACCION_RAPIDA       → 800–1500 ms   (reclamar VOY)
//   SIN_DELAY             → 0             (NoOp)
// ─────────────────────────────────────────────

enum class CategoriaDelayBot(val rangoMs: IntRange) {
    PENSAMIENTO_PROFUNDO(2000..3000),
    DECISION_TURNO(1200..2500),
    ACCION_POST_ROBO(1500..2500),
    SELECCION_DURANTE_PODER(1000..1800),
    REACCION_RAPIDA(800..1500),
    SIN_DELAY(0..0)
}

fun DecisionBot.categoriaDelay(): CategoriaDelayBot = when (this) {
    is DecisionBot.CantarBrata -> CategoriaDelayBot.PENSAMIENTO_PROFUNDO

    is DecisionBot.RobarDelPozo,
    is DecisionBot.RobarDelDescarte,
    is DecisionBot.PasarTurnoBrata -> CategoriaDelayBot.DECISION_TURNO

    is DecisionBot.Descartar,
    is DecisionBot.CambiarPorPosicion,
    is DecisionBot.ActivarPoder,
    is DecisionBot.ActivarDescarteFree,
    is DecisionBot.SeleccionarComodin,
    is DecisionBot.RobarDescarteAdelantado,
    is DecisionBot.DescarteEspontaneo -> CategoriaDelayBot.ACCION_POST_ROBO

    is DecisionBot.EspiarCarta,
    is DecisionBot.EspiarCartaCambioViendo,
    is DecisionBot.DescartarCartaEspiada,
    is DecisionBot.RegresarCartaEspiada,
    is DecisionBot.ConfirmarSwapViendo,
    is DecisionBot.ConfirmarSwapSinVer,
    is DecisionBot.ConfirmarDescarteFree,
    is DecisionBot.SeleccionarObjetivoVoy,
    is DecisionBot.EntregarCartaVoy -> CategoriaDelayBot.SELECCION_DURANTE_PODER

    is DecisionBot.ReclamarVoy -> CategoriaDelayBot.REACCION_RAPIDA

    is DecisionBot.NoOp -> CategoriaDelayBot.SIN_DELAY
}

// ─────────────────────────────────────────────
// HELPER PARA IDENTIFICAR BOTS
// Convención de id: "bot_${UUID}".
// El campo esBot del Jugador es la fuente principal de verdad,
// pero este helper sirve como fallback defensivo.
// ─────────────────────────────────────────────

object BotIdentidad {
    const val PREFIJO_ID = "bot_"

    fun esIdDeBot(id: String): Boolean = id.startsWith(PREFIJO_ID)

    fun generarIdBot(): String = "$PREFIJO_ID${java.util.UUID.randomUUID()}"

    // Nombres autogenerados de bots (validados con el usuario).
    val NOMBRES_BOTS = listOf(
        "Bot Alfa",
        "Bot Beta",
        "Bot Gamma",
        "Bot Delta",
        "Bot Epsilon"
    )

    fun nombreParaNuevoBot(cantidadBotsActual: Int): String {
        val indice = cantidadBotsActual.coerceIn(0, NOMBRES_BOTS.size - 1)
        return NOMBRES_BOTS[indice]
    }
}

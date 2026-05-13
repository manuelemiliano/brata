package com.aguado.bratagame

import com.google.firebase.database.PropertyName
import java.util.UUID

// ─────────────────────────────────────────────
// CARTA BASE
// ─────────────────────────────────────────────

data class Carta(
    var id: String = UUID.randomUUID().toString(),
    var valor: String = "",
    var palo: String = "",
    @get:PropertyName("abierta")
    @set:PropertyName("abierta")
    var abierta: Boolean = false
)

// ─────────────────────────────────────────────
// ESTADO VISUAL DE UNA CARTA EN LA MESA
// ─────────────────────────────────────────────

enum class EstadoCarta {
    CERRADA,          // anverso oculto (estado normal)
    ABIERTA,          // visible permanentemente
    EN_MANO,          // el jugador la tiene en su HandPanel
    ACTIVA_DESCARTE   // carta con poder activo sobre el pozo de descarte
}

// ─────────────────────────────────────────────
// CARTA UBICADA EN LA MESA
// Combina la carta con su posición y propietario.
// posicion: 0-3 en el cuadrado 2x2
//   [2][3]  ← ALEJADAS del jugador (se ven 15s al inicio)
//   [0][1]  ← PRÓXIMAS al jugador
// ─────────────────────────────────────────────

data class CartaEnMesa(
    val carta: Carta = Carta(),
    val posicion: Int = 0,
    val propietarioId: String = ""
)

// ─────────────────────────────────────────────
// JUGADOR ACTIVO
// ─────────────────────────────────────────────

data class Jugador(
    var id: String = "",
    var nombre: String = "",
    var estaListo: Boolean = false,
    var esAnfitrion: Boolean = false,
    var cartas: List<Carta> = emptyList(),
    var cartaEnMano: Carta? = null
)

// ─────────────────────────────────────────────
// OBSERVADOR (modo dios: ve todas las cartas abiertas)
// ─────────────────────────────────────────────

data class Observador(
    var id: String = "",
    var nombre: String = ""
)

// ─────────────────────────────────────────────
// SALA
// ─────────────────────────────────────────────

data class Sala(
    var id: String = "",
    var nombreSala: String = "",

    // Jugadores activos (máximo 6)
    var jugadores: Map<String, Jugador> = emptyMap(),

    // Observadores (sin límite)
    var observadores: Map<String, Observador> = emptyMap(),

    // Estado de la partida
    var estaEnJuego: Boolean = false,
    var estaActiva: Boolean = true,

    // Turno actual: ID del jugador que tiene el turno
    var turnoActualId: String = "",

    // true cuando algún jugador presionó BRATA
    // todos tienen una última vuelta y luego se evalúa
    var brataActivada: Boolean = false,

    // ID del jugador que presionó BRATA
    var brataJugadorId: String = "",

    // Mazos
    // Con 5-6 jugadores se usan 2 barajas; GameRules lo determina al iniciar
    var mazoRobar: List<Carta> = emptyList(),
    var mazoDescarte: List<Carta> = emptyList(),

    // Contador regresivo de 15 segundos al inicio
    // Se almacena el timestamp Unix (ms) en que inició el countdown
    // Todos los clientes calculan el tiempo restante a partir de este valor
    var timestampInicioContador: Long = 0L,

    // Carta activa en el descarte con poder en juego
    // null si no hay poder activo en este momento
    var cartaPoderActiva: CartaPoderActiva? = null
)

// ─────────────────────────────────────────────
// PODER ACTIVO EN EL DESCARTE
// Registra qué jugador activó el poder, qué tipo es,
// y si ya está espiando una carta específica
// ─────────────────────────────────────────────

data class CartaPoderActiva(
    val jugadorId: String = "",
    val tipoPoder: TipoPoder = TipoPoder.NINGUNO,
    val cartaEspiandoId: String = "",
    val valorCartaActivadora: String = ""
)


// ─────────────────────────────────────────────
// TIPOS DE PODER
// ─────────────────────────────────────────────

enum class TipoPoder {
    NINGUNO,
    ESPIAR,
    CAMBIAR_VIENDO,
    CAMBIAR_SIN_VER,
    DESCARTE_FREE_SELECCION  // ← agregar
}
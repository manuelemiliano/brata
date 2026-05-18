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
    var abierta: Boolean = false,

    /** Quien colocó esta carta como nueva cima del descarte (última acción sobre la pila). */
    @get:PropertyName("descartadaPorJugadorId")
    @set:PropertyName("descartadaPorJugadorId")
    var descartadaPorJugadorId: String = "",

    /** true = salió de las 4 del juego en mesa; false = desde la mano u otra acción. */
    @get:PropertyName("descartadaDesdeJuegoMesa")
    @set:PropertyName("descartadaDesdeJuegoMesa")
    var descartadaDesdeJuegoMesa: Boolean = false,

    /** Solo comodín: se robó legalmente del descarte (regla del jugador anterior + desde mesa). */
    @get:PropertyName("comodinRoboDescarteOk")
    @set:PropertyName("comodinRoboDescarteOk")
    var comodinRobadoDelDescarteValido: Boolean = false,

    /**
     * Origen de la carta cuando está en mano.
     * "POZO" = viene del pozo oculto.
     * "DESCARTE" = viene del pozo de descarte.
     * "" = no está en mano o no aplica.
     */
    @get:PropertyName("origenRobo")
    @set:PropertyName("origenRobo")
    var origenRobo: String = ""
)

/** Casilla sin carta: conserva el hueco 0–3 para memoria espacial. */
object MesaSlots {
    val VACIA = Carta(
        id = "__EMPTY_SLOT__",
        valor = "",
        palo = "",
        abierta = false
    )
}

fun Carta.esSlotVacio(): Boolean =
    id == MesaSlots.VACIA.id

/** Garantiza exactamente 4 entradas (índices fijos de mesa). */
fun List<Carta>.mesaNormalizadaACuatroCasillas(): MutableList<Carta> {
    val m = toMutableList()
    while (m.size < 4) m.add(MesaSlots.VACIA)
    if (m.size > 4) return m.subList(0, 4).toMutableList()
    return m
}

/**
 * Orden visual del cuadrado 2×2 en pantalla: fila superior [2][3], inferior [0][1]
 * (izquierda a derecha, de arriba hacia abajo). Misma lectura que [CuadradoCartasInteractivo].
 */
val ORDEN_CASILLAS_MESA_VISUAL: List<Int> = listOf(2, 3, 0, 1)

/** Primera casilla vacía al rellenar en el mismo orden que el layout de la mesa. */
fun primeraCasillaVaciaOrdenVisual(cartas: List<Carta>): Int? {
    val m = cartas.mesaNormalizadaACuatroCasillas()
    for (i in ORDEN_CASILLAS_MESA_VISUAL) {
        if (m.getOrNull(i)?.esSlotVacio() == true) return i
    }
    return null
}

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
    var cartaEnMano: Carta? = null,

    // Presencia real del jugador.
    // Sirve para saber si el jugador está conectado actualmente
    // sin eliminarlo de la partida cuando cierra la app por error.
    var conectado: Boolean = true,
    var ultimaConexion: Long = 0L,

    // Errores por intento incorrecto de descarte cuando ya no hay
    // espacio disponible para recibir carta de castigo.
    var erroresDescarte: Int = 0,

    // Al llegar a 3 errores, el jugador queda fuera de la ronda.
    var descalificado: Boolean = false
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
    var cartaPoderActiva: CartaPoderActiva? = null,

    // Describe la jugada actual para que todos los jugadores sepan
    // qué acción está realizando el jugador en turno.
    var jugadaActual: JugadaActual? = null,

    // Animación de intercambio en curso (null = sin animación activa)
    var swapAnimando: SwapAnimando? = null,

    // Animación de cambio por carta propia hacia el descarte
    // Escrita por el jugador ejecutor; leída por todos los dispositivos.
    var cambioPropioAnimando: CambioPropioAnimando? = null,

    // Animación de descarte espontáneo hacia el pozo.
    // La carta ya fue jugada en Firebase; esto solo representa visualmente el movimiento.
    var descarteEspontaneoAnimando: DescarteEspontaneoAnimando? = null,

    // Animación de DESCARTE FREE.
    // La carta ya fue jugada en Firebase; esto solo representa visualmente el movimiento.
    var descarteFreeAnimando: DescarteFreeAnimando? = null,

    var espiaAnimando: EspiaAnimando? = null,

    var cadenaDescarte: CadenaDescarte? = null,

    var ventanaFinalRonda: VentanaFinalRonda? = null,

    var adelantadoPendiente: AdelantadoPendiente? = null,

    // Regla VOY: ventana temporal antes del robo.
    var voyPendiente: VoyPendiente? = null,

    var partidaId: String = ""
)

data class VentanaFinalRonda(
    val activa: Boolean = false,
    val finalizada: Boolean = false,
    val id: String = "",
    val timestampInicio: Long = 0L,
    val duracionMs: Long = 5000L
)

data class VoyPendiente(
    val activo: Boolean = false,
    val id: String = "",

    // Jugador que intentó robar y cuyo robo queda pausado.
    val jugadorRobandoId: String = "",

    // "POZO" o "DESCARTE"
    val tipoRobo: String = "POZO",

    // Valor de la cima del descarte al momento de intentar robar.
    val valorObjetivo: String = "",
    val cartaDescarteObjetivoId: String = "",

    val timestampInicio: Long = 0L,
    val duracionMs: Long = 2000L,
    val reclamadoPorJugadorId: String = "",
    val fase: String = "VENTANA",

    val jugadorObjetivoId: String = "",
    val posicionObjetivo: Int = -1,
    val cartaObjetivoId: String = ""
)

data class CadenaDescarte(
    val activa: Boolean = false,

    // Valor que se está encadenando: "K", "7", "A", etc.
    val valorBase: String = "",

    // Jugador que inició la cadena al colocar la carta en el descarte.
    val jugadorOrigenId: String = "",

    // Jugador cuyo turno debe responder la cadena.
    val turnoEsperadoId: String = "",

    // Jugadores que ya descartaron este valor durante la cadena,
    // aunque se hayan adelantado.
    val jugadoresQueDescartaron: Map<String, Boolean> = emptyMap()
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

data class JugadaActual(
    val jugadorId: String = "",
    val tipo: String = "",
    val subaccion: String = "",
    val timestamp: Long = 0L
)

data class AdelantadoPendiente(
    val activo: Boolean = false,

    // Jugador que tenía la carta espía pendiente.
    val jugadorPerjudicadoId: String = "",

    // Jugador que se adelantó descartando.
    val jugadorAdelantadoId: String = "",

    // Carta que el adelantado descartó.
    val cartaAdelantadaId: String = "",
    val valorAdelantado: String = "",
    val paloAdelantado: String = "",

    // Posición exacta que dejó vacía el adelantado.
    val posicionAdelantada: Int = -1,

    // Carta espía pendiente del jugador perjudicado.
    // Si ya había activado ESPIAR, puede estar ya en el descarte.
    val cartaEspiaPendienteId: String = "",
    val valorCartaEspia: String = "",
    val paloCartaEspia: String = "",
    val espiaYaEnDescarte: Boolean = false
)

// ─────────────────────────────────────────────
// TIPOS DE PODER
// ─────────────────────────────────────────────

enum class TipoPoder {
    NINGUNO,
    ESPIAR,
    CAMBIAR_VIENDO,
    CAMBIAR_SIN_VER,
    DESCARTE_FREE_SELECCION
}

// ─────────────────────────────────────────────
// ANIMACIÓN DE INTERCAMBIO (Firebase-driven)
// Escrita por el jugador ejecutor; leída por TODOS los dispositivos
// para que cada uno ejecute la animación con sus propias coordenadas locales.
// Se borra de Firebase cuando la animación termina.
// ─────────────────────────────────────────────

data class SwapAnimando(
    /** Jugador que activó el poder y que debe confirmar el intercambio real */
    val ejecutorId: String = "",

    /** Identidad universal de la primera carta */
    val jugadorAId: String = "",
    val cartaAId: String = "",
    val valorA: String = "",

    /** Identidad universal de la segunda carta */
    val jugadorBId: String = "",
    val cartaBId: String = "",
    val valorB: String = "",

    /** Timestamp Unix (ms) de inicio — permite sincronizar duración entre dispositivos */
    val timestampInicio: Long = 0L,

    /** true = CAMBIAR_VIENDO muestra carta A; false = CAMBIAR_SIN_VER mantiene ambas cerradas */
    val mostrarCartaA: Boolean = false,

    /** Reservado por si después quieres mostrar también carta B */
    val mostrarCartaB: Boolean = false
)

data class CambioPropioAnimando(
    val id: String = "",
    val ejecutorId: String = "",
    val jugadorId: String = "",
    val cartaId: String = "",
    val posicion: Int = -1,
    val cartaEnManoId: String = "",
    val timestampInicio: Long = 0L,
    val duracionSaltoMs: Long = 2000L,
    val duracionViajeMs: Long = 750L
)

data class DescarteEspontaneoAnimando(
    val id: String = "",
    val ejecutorId: String = "",

    // Carta que salió del juego de un jugador.
    val jugadorId: String = "",
    val posicion: Int = -1,
    val cartaId: String = "",
    val valor: String = "",
    val palo: String = "",

    // Lugar real donde entró en mazoDescarte al momento del clic.
    val indiceDescarte: Int = -1,

    // Sincronización visual.
    val timestampInicio: Long = 0L,
    val duracionViajeMs: Long = 650L,
    val duracionReboteMs: Long = 450L
)

data class EspiaAnimando(
    val id: String = "",
    val ejecutorId: String = "",

    // Carta que fue seleccionada para espiar.
    val propietarioId: String = "",
    val posicion: Int = -1,
    val cartaId: String = "",

    // Sincronización visual.
    val timestampInicio: Long = 0L,
    val duracionMs: Long = 3000L
)

data class DescarteFreeAnimando(
    val id: String = "",
    val ejecutorId: String = "",

    // Carta que estaba en mano y fue enviada al descarte.
    val cartaId: String = "",
    val valor: String = "",
    val palo: String = "",

    // Lugar real donde entró en mazoDescarte.
    val indiceDescarte: Int = -1,

    // Sincronización visual.
    val timestampInicio: Long = 0L,
    val duracionViajeMs: Long = 650L,
    val duracionReboteMs: Long = 450L
)
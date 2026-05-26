package com.aguado.bratagame.bot

import com.aguado.bratagame.AdelantadoPendiente
import com.aguado.bratagame.Carta
import com.aguado.bratagame.CartaPoderActiva
import com.aguado.bratagame.Sala
import com.aguado.bratagame.VentanaFinalRonda
import com.aguado.bratagame.VoyPendiente
import com.aguado.bratagame.esSlotVacio
import com.aguado.bratagame.mesaNormalizadaACuatroCasillas

// ─────────────────────────────────────────────
// VISTA PARCIAL DE SALA
//
// Esta es la ÚNICA interfaz que el BotBrain está autorizado a leer.
// Encapsula la información pública que un humano sentado en la mesa
// vería con sus propios ojos:
//
//   - Quién está en mesa y cuántas cartas tiene cada uno.
//   - Qué carta hay en la cima del descarte (y la penúltima).
//   - Su propia carta en mano (si robó).
//   - Estado de turno, brata, poderes activos, VOY pendiente.
//
// NO contiene los valores de las cartas en mesa, salvo las que el bot
// conoce por memoria legítima (mem.cartasPropiasConocidas y
// mem.cartasRivalesConocidas). Eso lo consulta el BotBrain por separado.
//
// Esta separación es la frontera que protege el "fair play":
// si alguien quiere darle más información al bot, tiene que cambiar
// explícitamente esta clase, lo cual hace visible la decisión.
// ─────────────────────────────────────────────

data class VistaParcialSala(

    // ── Identidad del bot que decide ──
    val miId: String,

    // ── Estado de mesa ──
    val jugadoresOrden: List<String>,                        // orden en sentido del turno
    val cuentaCartasPorJugador: Map<String, Int>,            // cuántas cartas no vacías tiene cada jugador
    val posicionesOcupadasPorJugador: Map<String, Set<Int>>, // qué slots tiene ocupados cada jugador

    // ── IDs de cartas ajenas por posición ──
    // El bot NUNCA accede al VALOR de estas cartas a través de esta vista
    // (eso vive solo en MemoriaBot). Pero sí necesita el ID para construir
    // acciones de poderes como EspiarCarta(cartaId), ConfirmarSwapViendo, etc.
    // Mapa: rivalId → (posición 0..3 → cartaId)
    val cartasIdsRivales: Map<String, Map<Int, String>>,

    // ── Mis 4 posiciones (algunas pueden estar vacías) ──
    val miEstadoPosiciones: Map<Int, EstadoPosicionPropia>,

    // ── Descarte ──
    val ultimaCartaDescarte: Carta?,
    val penultimaCartaDescarte: Carta?,
    val tamanoMazoDescarte: Int,
    val tamanoMazoRobar: Int,

    // ── Mi carta en mano (si robé) ──
    val cartaEnMano: Carta?,

    // ── Turno y flags globales ──
    val turnoActualId: String,
    val brataActivada: Boolean,
    val brataJugadorId: String,

    // ── Sub-estados temporales ──
    val poderActivo: CartaPoderActiva?,
    val voyPendiente: VoyPendiente?,
    val adelantadoPendiente: AdelantadoPendiente?,
    val ventanaFinalRonda: VentanaFinalRonda?,

    // ── Cantidad de jugadores activos (no descalificados, no expulsados) ──
    val cantidadJugadoresActivos: Int,

    // ── Descalificados (para el bot saber a quién no atacar/intercambiar) ──
    val jugadoresDescalificados: Set<String>
) {
    // Helpers que el BotBrain usa para preguntas comunes sin volver a calcular.

    fun esMiTurno(): Boolean = turnoActualId == miId

    fun rivalesActivos(): List<String> = jugadoresOrden.filter {
        it != miId && it !in jugadoresDescalificados
    }

    fun jugadorInmediatoAnterior(): String? {
        if (jugadoresOrden.isEmpty()) return null
        val idx = jugadoresOrden.indexOf(miId)
        if (idx < 0) return null
        val idxAnt = (idx - 1 + jugadoresOrden.size) % jugadoresOrden.size
        return jugadoresOrden[idxAnt]
    }
}

// ─────────────────────────────────────────────
// ESTADO DE UNA POSICIÓN PROPIA
// Sin valor de carta — eso vive en la MemoriaBot.
// ─────────────────────────────────────────────

data class EstadoPosicionPropia(
    val posicion: Int,          // 0..3
    val ocupada: Boolean,       // false = slot vacío
    val cartaId: String         // útil para acciones que requieren id
)

// ─────────────────────────────────────────────
// CONSTRUCTOR DE VISTA PARCIAL
//
// Transforma una Sala completa en una VistaParcialSala desde la
// perspectiva de un bot específico.
//
// Importante: este es el ÚNICO lugar donde se "convierte" Sala → vista.
// El BotBrain no recibe Sala nunca; siempre recibe el resultado de esta función.
// ─────────────────────────────────────────────

object VistaParcialBuilder {

    fun construir(sala: Sala, miId: String): VistaParcialSala {
        val ordenSinDescalificados = sala.jugadores.keys.toList()

        // Mapas pre-calculados de cuentas, posiciones ocupadas e ids de cartas
        val cuentaPorJugador = mutableMapOf<String, Int>()
        val posicionesPorJugador = mutableMapOf<String, Set<Int>>()
        val cartasIdsRivales = mutableMapOf<String, Map<Int, String>>()
        val descalificados = mutableSetOf<String>()

        sala.jugadores.forEach { (id, jugador) ->
            val mesa = jugador.cartas.mesaNormalizadaACuatroCasillas()
            val ocupadas = mesa.mapIndexedNotNull { idx, carta ->
                if (!carta.esSlotVacio()) idx else null
            }.toSet()

            cuentaPorJugador[id] = ocupadas.size
            posicionesPorJugador[id] = ocupadas

            // IDs de cartas (solo para rivales; las propias ya están en miEstadoPosiciones)
            if (id != miId) {
                val mapaIds = mutableMapOf<Int, String>()
                ocupadas.forEach { pos ->
                    mapaIds[pos] = mesa[pos].id
                }
                cartasIdsRivales[id] = mapaIds
            }

            if (jugador.descalificado) {
                descalificados.add(id)
            }
        }

        // Estado de mis 4 posiciones
        val miJugador = sala.jugadores[miId]
        val miEstado = if (miJugador != null) {
            val miMesa = miJugador.cartas.mesaNormalizadaACuatroCasillas()
            (0..3).associateWith { pos ->
                val carta = miMesa[pos]
                EstadoPosicionPropia(
                    posicion = pos,
                    ocupada = !carta.esSlotVacio(),
                    cartaId = carta.id
                )
            }
        } else {
            emptyMap()
        }

        val cantidadActivos = sala.jugadores.values.count { !it.descalificado }

        return VistaParcialSala(
            miId = miId,
            jugadoresOrden = ordenSinDescalificados,
            cuentaCartasPorJugador = cuentaPorJugador,
            posicionesOcupadasPorJugador = posicionesPorJugador,
            cartasIdsRivales = cartasIdsRivales,
            miEstadoPosiciones = miEstado,
            ultimaCartaDescarte = sala.mazoDescarte.lastOrNull(),
            penultimaCartaDescarte = sala.mazoDescarte.getOrNull(sala.mazoDescarte.size - 2),
            tamanoMazoDescarte = sala.mazoDescarte.size,
            tamanoMazoRobar = sala.mazoRobar.size,
            cartaEnMano = miJugador?.cartaEnMano,
            turnoActualId = sala.turnoActualId,
            brataActivada = sala.brataActivada,
            brataJugadorId = sala.brataJugadorId,
            poderActivo = sala.cartaPoderActiva,
            voyPendiente = sala.voyPendiente,
            adelantadoPendiente = sala.adelantadoPendiente,
            ventanaFinalRonda = sala.ventanaFinalRonda,
            cantidadJugadoresActivos = cantidadActivos,
            jugadoresDescalificados = descalificados
        )
    }
}

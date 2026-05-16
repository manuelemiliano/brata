package com.aguado.bratagame.game

import com.aguado.bratagame.Carta
import com.aguado.bratagame.Jugador
import com.aguado.bratagame.Sala
import com.aguado.bratagame.esSlotVacio

// ─────────────────────────────────────────────
// HAND EVALUATOR
//
// Responsabilidades:
//   - Calcular la puntuación final de cada jugador
//   - Aplicar todas las reglas especiales de conteo
//   - Determinar el ganador (menor puntaje gana)
//   - Producir el resultado completo para la UI
// ─────────────────────────────────────────────

object HandEvaluator {

    // ─────────────────────────────────────────
    // RESULTADO DE UNA MANO
    // ─────────────────────────────────────────

    data class ResultadoJugador(
        val jugador: Jugador,
        val puntuacion: Int,
        val reglaAplicada: ReglaEspecial,   // para mostrar en UI
        val esGanador: Boolean
    )

    enum class ReglaEspecial {
        NINGUNA,
        CUATRO_MISMO_PALO,    // regla de palos: todos iguales → 0
        CUATRO_MISMO_NUMERO,  // regla de números: los 4 iguales → 0
        DOS_REYES_ROJOS       // los dos reyes rojos no se cuentan
    }

    data class ResultadoRonda(
        val jugadores: List<ResultadoJugador>,
        val ganador: ResultadoJugador,
        val hayEmpate: Boolean,
        val empatados: List<ResultadoJugador>
    )

    // ─────────────────────────────────────────
    // EVALUACIÓN COMPLETA DE LA RONDA
    // ─────────────────────────────────────────

    fun evaluarRonda(sala: Sala): ResultadoRonda {
        val resultados = sala.jugadores.values.map { jugador ->
            evaluarMano(jugador)
        }.sortedBy { it.puntuacion }

        val menorPuntuacion = resultados.first().puntuacion
        val empatados = resultados.filter { it.puntuacion == menorPuntuacion }
        val hayEmpate = empatados.size > 1

        // En empate todos los empatados son "ganadores"
        val resultadosFinales = resultados.map { resultado ->
            resultado.copy(esGanador = resultado.puntuacion == menorPuntuacion)
        }

        return ResultadoRonda(
            jugadores = resultadosFinales,
            ganador = resultadosFinales.first(),
            hayEmpate = hayEmpate,
            empatados = resultadosFinales.filter { it.esGanador }
        )
    }

    // ─────────────────────────────────────────
    // EVALUACIÓN DE UN JUGADOR
    // ─────────────────────────────────────────

    fun evaluarMano(jugador: Jugador): ResultadoJugador {
        val cartas = jugador.cartas.filterNot { it.esSlotVacio() }

        // ── Regla de palos ─────────────────────
        // 4 o más cartas del mismo palo → 0 puntos
        if (cartas.size >= 4 && cartas.map { it.palo }.toSet().size == 1) {
            return ResultadoJugador(
                jugador = jugador,
                puntuacion = 0,
                reglaAplicada = ReglaEspecial.CUATRO_MISMO_PALO,
                esGanador = false // se actualiza en evaluarRonda
            )
        }

        // ── Regla de números ───────────────────
        // Los 4 del mismo valor → 0 puntos
        if (cartas.size >= 4 && cartas.map { it.valor }.toSet().size == 1) {
            return ResultadoJugador(
                jugador = jugador,
                puntuacion = 0,
                reglaAplicada = ReglaEspecial.CUATRO_MISMO_NUMERO,
                esGanador = false
            )
        }

        // ── Regla de reyes rojos ───────────────
        // Los dos reyes rojos juntos no se cuentan (valen 0 entre ellos)
        val reyesRojos = cartas.filter {
            it.valor == "K" && (it.palo == "corazones" || it.palo == "diamantes")
        }
        val reglaReyesAplica = reyesRojos.size >= 2
        val cartasAContar = if (reglaReyesAplica) {
            // Excluir exactamente dos reyes rojos del conteo
            val sinDosReyesRojos = cartas.toMutableList()
            var excluidos = 0
            val iterador = sinDosReyesRojos.iterator()
            while (iterador.hasNext() && excluidos < 2) {
                val carta = iterador.next()
                if (carta.valor == "K" &&
                    (carta.palo == "corazones" || carta.palo == "diamantes")) {
                    iterador.remove()
                    excluidos++
                }
            }
            sinDosReyesRojos
        } else {
            cartas
        }

        val puntuacion = cartasAContar.sumOf { valorPuntuacion(it) }

        return ResultadoJugador(
            jugador = jugador,
            puntuacion = puntuacion,
            reglaAplicada = if (reglaReyesAplica) ReglaEspecial.DOS_REYES_ROJOS
            else ReglaEspecial.NINGUNA,
            esGanador = false
        )
    }

    // ─────────────────────────────────────────
    // VALOR DE PUNTUACIÓN POR CARTA
    // ─────────────────────────────────────────

    fun valorPuntuacion(carta: Carta): Int {
        return when (carta.valor.uppercase()) {
            "A"   -> 20
            "2"   -> 0
            "J"   -> 11
            "Q"   -> 12
            "K"   -> if (carta.palo == "treboles" || carta.palo == "picas") 13 else 1
            // El comodín sin definir vale 20 puntos (penalización máxima).
            // Si el jugador lo definió antes de que termine la ronda,
            // su valor ya fue reemplazado en Firebase y no llega como "JKR".
            "JKR" -> 20
            else  -> carta.valor.toIntOrNull() ?: 0 // 3 al 10 valen su número
        }
    }

    // ─────────────────────────────────────────
    // PUNTUACIÓN MÁXIMA POSIBLE
    // Útil para mostrar en UI qué tan bien/mal está un jugador
    // con sus cartas visibles.
    // ─────────────────────────────────────────

    fun puntuacionMaximaVisible(cartas: List<Carta>): Int {
        // Solo cuenta las cartas con abierta = true
        val cartasVisibles = cartas.filter { it.abierta }
        return cartasVisibles.sumOf { valorPuntuacion(it) }
    }

    // ─────────────────────────────────────────
    // ETIQUETA DE REGLA PARA LA UI
    // ─────────────────────────────────────────

    fun etiquetaRegla(regla: ReglaEspecial): String = when (regla) {
        ReglaEspecial.NINGUNA -> ""
        ReglaEspecial.CUATRO_MISMO_PALO ->
            "✦ Regla de palos: todos del mismo palo → 0 pts"
        ReglaEspecial.CUATRO_MISMO_NUMERO ->
            "✦ Regla de números: cuatro iguales → 0 pts"
        ReglaEspecial.DOS_REYES_ROJOS ->
            "✦ Regla de reyes: los dos reyes rojos no cuentan"
    }
}
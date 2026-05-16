# Manual Oficial de Brata

> Documento de referencia del juego. Toda la lógica implementada en
> `app/src/main/java/com/aguado/bratagame/game/` debe respetarlo.

---

## 1. Objetivo del juego

Terminar la ronda con la **menor puntuación posible**. Los jugadores
memorizan cartas, usan poderes tácticos y aprovechan descartes rápidos
para reducir el valor y la cantidad de sus cartas antes de que alguien
detone el final con el canto de “¡Brata!”.

---

## 2. Preparación de la partida

### 2.1 Jugadores y barajas

- Baraja francesa de 54 cartas (incluye 2 comodines).
- **1 baraja**: hasta 4 jugadores.
- **2 barajas**: hasta 6 jugadores.

### 2.2 Reparto

- Se reparten **4 cartas** a cada jugador, boca abajo, en un cuadrado **2×2**.
- El resto forma el **pozo** en el centro.
- **No** hay pila de descarte al inicio.

### 2.3 Memorización inicial

- Cada jugador puede ver en secreto **únicamente las 2 cartas más
  alejadas** de él (fila superior del 2×2).
- Las memoriza y las devuelve boca abajo a su posición.

### 2.4 Inicio

- Se elige quién comienza.
- El turno avanza en **sentido horario**.

---

## 3. Cartas: valor y poder

| Carta     | Valor | Poder |
|-----------|------:|-------|
| As (A)    | 20    | **Cambiar viendo** — ve una carta ajena y la cambia por otra (suya o de otro) sin verla |
| 2         | 0     | — |
| 3, 4      | 3, 4  | — |
| 5–10      | su número | **Espiar** — ve en secreto cualquier carta del juego |
| J, Q      | 11, 12 | **Cambiar sin ver** — intercambia dos cartas cualesquiera sin verlas |
| K rojo    | 1     | — |
| K negro   | 13    | — |
| Joker     | variable | **Comodín** — toma el valor declarado al descartarlo |

---

## 4. Desarrollo del turno

En su turno, el jugador roba la carta superior del pozo, la mira en
secreto y elige **una** de estas opciones:

1. **Usar el poder de la carta**
   - La coloca boca arriba junto al pozo.
   - Ejecuta el poder.
   - Termina su turno.
2. **Intercambiarla**
   - Sustituye una de sus 4 cartas con la robada.
   - La carta reemplazada va al descarte.
3. **Descartarla**
   - La coloca directamente en la pila de descarte.

### 4.1 Robar del descarte (Regla de Oro)

- Solo se puede tomar la **carta superior** del descarte.
- Solo es robable si proviene de la **cuadrícula del jugador
  inmediatamente anterior**.
- Si el jugador anterior descartó una carta que venía del pozo, el
  descarte queda **bloqueado** para el siguiente.

---

## 5. Descarte rápido y control de turnos

### 5.1 Descarte al par

Si robas una carta **idéntica en valor** a la cima del descarte:

- La colocas en el descarte.
- Anuncias **“Descarte al par”**.
- Puedes tirar **otra carta propia** del mismo valor.

### 5.2 Descarte consecutivo

Si robas una carta que **completa una secuencia** (asc/desc) con las
dos últimas del descarte:

- Anuncias **“Descarte consecutivo”**.
- Puedes tirar otra carta propia del mismo valor que la robada.

### 5.3 Descarte espontáneo

En cualquier momento, si tienes una carta en tu mesa **idéntica** a la
cima del descarte, puedes descartarla directamente. Reduces tu cantidad
de cartas.

### 5.4 Regla de la paciencia (Adelantado)

- Si un jugador activa un poder de **espía o cambiar viendo**, los
  demás deben **esperar a que termine su acción** antes de hacer un
  descarte rápido.
- Si te adelantas (descartas antes de que el jugador haya empezado a
  espiar la carta), el jugador en turno:
  - Toma tu carta descartada.
  - Te entrega una carta de su juego a cambio.

### 5.5 Castigo por error

Si la carta no coincide al intentar descarte rápido (en tu turno o
fuera de él):
- La carta **regresa a tu cuadrícula** (estado original).
- Robas **1 carta extra de castigo** del pozo, boca abajo.

### 5.6 Encadenamiento de turnos

- **Turnos encadenados**: si los descartes rápidos siguen el orden
  natural (siguiente jugador inmediato), los turnos se **consumen**.
- **Turnos no encadenados**: si se rompe el orden (un jugador no
  inmediato descarta), el turno **regresa al siguiente jugador
  normal** tras el que estaba en turno.

---

## 6. Regla “Voy”

Si detectas que un rival olvidó descartar una carta que podía haber
descartado:
- Gritas **“¡Voy!”**.
- Tomas la carta correspondiente del rival.
- La descartas.
- Le das una de tus cartas a cambio.

Errar en “Voy” es una penalización (sección 8).

---

## 7. El Joker (Comodín)

- Al descartarlo, **declaras su valor**.
- Tras la declaración, todos pueden reaccionar con descarte rápido
  contra ese valor.

### 7.1 Pase del Joker

- **No** puedes descartar tu Joker de forma rápida si está oculto en
  tu cuadrícula.
- Para sacarlo de tu juego debes:
  - Moverlo con un poder (**J**, **Q** o **As**), o
  - **Descartarlo directamente** en tu turno (declarando su valor).

---

## 8. Penalizaciones generales

Actos que aplican castigo:
- Descarte rápido incorrecto.
- Fallar al cantar “¡Voy!”.
- Ver cartas de forma indebida.

**Castigo**:
- Restaurar el estado original (devolver cartas a su lugar).
- Tomar **1 carta extra** del pozo, boca abajo, en la primera casilla
  vacía.

---

## 9. Combos de 0 puntos

### 9.1 Par de reyes rojos
Dos reyes rojos en tu juego → **0 puntos entre ellos** (las otras
cartas siguen contando).

### 9.2 Juego monocolor (mismo palo)
4 o más cartas del **mismo palo** → **0 puntos** la mano completa. No
aplica con solo 3 cartas.

---

## 10. Final: el canto de “¡Brata!”

### 10.1 Anuncio
- Al **inicio de su turno** (antes de robar), un jugador puede cantar
  **“¡Brata!”**.
- Su turno termina inmediatamente.
- Su juego queda **inmovilizado**.

### 10.2 Vuelta final
- Los demás jugadores tienen **un último turno** cada uno.

### 10.3 Vulnerabilidad
Durante la vuelta final, los rivales pueden sabotear al cantor con los
poderes:
- **J** (cambiar sin ver)
- **Q** (cambiar sin ver)
- **A** (cambiar viendo)

### 10.4 Resolución
- Se revelan **todas** las cartas.
- Se suman los puntos aplicando los combos de la sección 9.
- **Gana** quien tenga la puntuación más baja.
- En empate, todos los empatados ganan la ronda.

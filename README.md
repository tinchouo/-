# TOD4 (Paper 1.21.4)

Los comandos de admin son solo `/tod4:<comando>` (escribiendo `/tod4:` en el chat se ven todos). Los jugadores usan `/misiones`.

Plugin de TrucOfDeath 4. Se compila solo: pestaña **Actions** → última ejecución con tilde verde → abajo de todo, **TOD4** (zip con `TOD4.jar`).

## Instalar (tanda 1)
1. Apagá el server.
2. Poné `TOD4.jar` en `plugins/`.
3. **Sacá** estos scripts de `plugins/Skript/scripts/` (el plugin los reemplaza): `revivir.sk`, `mensajes.sk`, `misiones.sk`, `momentotruco.sk`, `palo.sk`, `cambios_dia_1.sk` … `cambios_dia_4.sk`.
4. **Sacá** `MesaEncantamientos.jar` de `plugins/` (la mesa ahora es el cambio "Mesa de encantamientos" del día 4) y el datapack `hornos_extra` (y el de hornos del día 3 si lo tenías): ahora son cambios del menú.
5. **Sacá SimpleScore** (la sidebar ahora la maneja TOD4; si quedan los dos se pisan).
6. **Sacá** `tabulador.sk`: el tab (misión, alma, nombre y corazones de vida) ahora lo pone el plugin.
7. Prendé el server, poné el día con **`/tod4 dia <n>`** y configurá el ítem normal/premium y el bloque del altar con **`/tod4:altar`**.

## Misiones
- `/tod4:panel` → Diarias / Opcionales → **Crear nueva**: escribís `ID Nombre` (la ID pegada, ej. `3 Armadura de diamante`, `op3 Cazador`).
- En la misión: **➕ Agregar objetivo** (Conseguir, Fabricar/Obtener, Picar, Matar, Logro, Ir a, Evento). Se pueden agregar varios del mismo tipo.
- En el cofre de cada objetivo: **por separado** (la cantidad es la del stack; varios stacks se suman) o **en conjunto** (todos suman y la cantidad la pone el reloj).
- `/tod4:completar <jugador|todos> <id>` completa la misión entera; con `evento` al final, solo el evento.

## Comandos
`/tod4 help` muestra todo. Permiso: `tod4.admin` (ops).

## Archivos en plugins/TOD4/
- `config.yml` – sidebar, colores de mundos, sonidos de la ruleta, mensajes del tótem, números de los cambios.
- `mesa.yml` – reglas de la mesa de encantamientos.
- `datos.yml`, `avisos.yml`, `revivir.yml`, `cambios.yml` – datos guardados (no hace falta tocarlos).

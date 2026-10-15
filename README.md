# TOD4 (Paper 1.21.4)

Los comandos de admin son solo `/tod4:<comando>` (escribiendo `/tod4:` en el chat se ven todos). Los jugadores usan `/misiones`.

Plugin de TrucOfDeath 4. Se compila solo: pestaña **Actions** → última ejecución con tilde verde → abajo de todo, **TOD4** (zip con `TOD4.jar`).

## Instalar (tanda 1)
1. Apaga el server.
2. Pon `TOD4.jar` en `plugins/`.
3. **Saca** estos scripts de `plugins/Skript/scripts/` (el plugin los reemplaza): `revivir.sk`, `mensajes.sk`, `misiones.sk`, `momentotruco.sk`, `palo.sk`, `cambios_dia_1.sk` … `cambios_dia_4.sk`.
4. **Saca** `MesaEncantamientos.jar` de `plugins/` (la mesa ahora es el cambio "Mesa de encantamientos" del día 4) y el datapack `hornos_extra` (y el de hornos del día 3 si lo tenías): ahora son cambios del menú.
5. **Saca SimpleScore** (la sidebar ahora la maneja TOD4; si quedan los dos se pisan).
6. **Saca** `tabulador.sk`: el tab (misión, alma, nombre y corazones de vida) ahora lo pone el plugin.
7. Enciende el server, pon el día con **`/tod4 dia <n>`** y configura el ítem normal/premium y el bloque del altar con **`/tod4:altar`**.

## Misiones
- `/tod4:panel` → Diarias / Opcionales → **Crear nueva**: escribes `ID Nombre` (la ID pegada, ej. `3 Armadura de diamante`, `op3 Cazador`).
- En la misión: **➕ Agregar objetivo** (Conseguir, Fabricar/Obtener, Picar, Matar, Logro, Ir a, Evento). Se pueden agregar varios del mismo tipo.
- En el cofre de cada objetivo: **por separado** (la cantidad es la del stack; varios stacks se suman) o **en conjunto** (todos suman y la cantidad la pone el reloj).
- `/tod4:completar <jugador|todos> <id>` completa la misión entera; con `evento` al final, solo el evento.

## Comandos
`/tod4 help` muestra todo. Permiso: `tod4.admin` (ops).

## Archivos en plugins/TOD4/
- `config.yml` – sidebar, colores de mundos, sonidos de la ruleta, mensajes del tótem, números de los cambios.
- `mesa.yml` – reglas de la mesa de encantamientos.
- `datos.yml`, `avisos.yml`, `revivir.yml`, `cambios.yml` – datos guardados (no hace falta tocarlos).

## Cambios nuevos
- Día 1: el Warden aparece solo en las Ancient City (en lugar del bloqueo de manzanas).
- Día 2: las hojas no sueltan brotes ni manzanas; silverfish en las cuevas (debajo de Y 0).
- Día 5: el Wither solo se invoca con la cabeza de un jugador pegada en el pecho de la T. Se llama "Wither de <jugador>".
- Día 6: solo Caída de Pluma protege de la caída; las noches no se saltan (acostarte igual resetea el contador de phantoms); tocar bedrock da Wither III apenas la tocas y gasta la armadura.
- Siempre: para pasar la noche duerme un cuarto de los jugadores (mínimo 5), contando Overworld y Aether (`sueno:` en config.yml).
- Mesa (día 4): con el botón 40 las herramientas sacan mínimo 2 encantamientos y el principal al máximo; Reparación un poco más seguido; nuevo **Libro Indestructible** (se usa en el yunque). El `mesa.yml` viejo queda como `mesa_viejo.yml`.

## Lobotomía (video a pantalla completa)
1. Descomprimí `TOD4_pack_Oraxen.zip` adentro de `plugins/Oraxen/pack/` (se suma a la carpeta `assets/`) y recargá Oraxen. Trae el video, el shader de pantalla completa y los sonidos de lobotomía y del Howler.
2. Reemplazá `lobotomia.sk` por el nuevo (llama a `/tod4:video`).
3. Probar: `/lobotomia frame <jugador>`.

# TOD4 (Paper 1.21.4)

Plugin de TrucOfDeath 4. Se compila solo: pestaña **Actions** → última ejecución con tilde verde → abajo de todo, **TOD4** (zip con `TOD4.jar`).

## Instalar (tanda 1)
1. Apagá el server.
2. Poné `TOD4.jar` en `plugins/`.
3. **Sacá** estos scripts de `plugins/Skript/scripts/` (el plugin los reemplaza): `revivir.sk`, `mensajes.sk`, `cambios_dia_1.sk` … `cambios_dia_4.sk`.
4. **Sacá** `MesaEncantamientos.jar` de `plugins/` (la mesa ahora es el cambio "Mesa de encantamientos" del día 4) y el datapack `hornos_extra` (y el de hornos del día 3 si lo tenías): ahora son cambios del menú.
5. **Sacá SimpleScore** (la sidebar ahora la maneja TOD4; si quedan los dos se pisan).
6. Reemplazá `tabulador.sk` por el nuevo (lee el alma desde el plugin). Necesita el addon **skript-placeholders** en `plugins/`.
7. Prendé el server, poné el día con **`/tod4 dia <n>`** y configurá el ítem normal/premium y el bloque del altar con **`/altar`**.

## Comandos
`/tod4 help` muestra todo. Permiso: `tod4.admin` (ops).

## Archivos en plugins/TOD4/
- `config.yml` – sidebar, colores de mundos, sonidos de la ruleta, mensajes del tótem, números de los cambios.
- `mesa.yml` – reglas de la mesa de encantamientos.
- `datos.yml`, `avisos.yml`, `revivir.yml`, `cambios.yml` – datos guardados (no hace falta tocarlos).

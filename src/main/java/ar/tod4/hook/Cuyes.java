package ar.tod4.hook;

import org.bukkit.entity.Player;

/**
 * Los cuyes (script cuyes.sk) estan en supervivencia pero vuelan y no reciben danio: el plugin los trata como exentos
 * (no cuentan para los Momentos Truco, los clones ni los cambios de dificultad). Se lee su variable
 * {cuy::active::<uuid>} (true mientras tienen el modo cuy activo); el script no se toca.
 */
public final class Cuyes {
    private Cuyes() {}

    public static boolean activo(Player p) {
        Object o = SkriptVars.get("cuy::active::" + p.getUniqueId());
        return o != null && !Boolean.FALSE.equals(o);
    }
}

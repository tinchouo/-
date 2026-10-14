package ar.tod4.hook;

import java.lang.reflect.Method;

import org.bukkit.Bukkit;
import org.bukkit.event.Event;

/**
 * Lee variables globales de Skript (para los sistemas que todavia siguen en Skript, como las misiones
 * y el Momento Truco). Si Skript no esta, devuelve null.
 */
public final class SkriptVars {
    private SkriptVars() {}

    private static Method GET;
    private static boolean tried;

    public static Object get(String name) {
        try {
            if (!tried) {
                tried = true;
                if (Bukkit.getPluginManager().getPlugin("Skript") != null) {
                    Class<?> c = Class.forName("ch.njol.skript.variables.Variables");
                    GET = c.getMethod("getVariable", String.class, Event.class, boolean.class);
                }
            }
            if (GET == null) return null;
            return GET.invoke(null, name, null, false);
        } catch (Throwable t) {
            return null;
        }
    }

    public static boolean isSet(String name) { return get(name) != null; }

    public static boolean isTrue(String name) { return Boolean.TRUE.equals(get(name)); }
}

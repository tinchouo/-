package ar.tod4.core;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.kyori.adventure.text.Component;

/**
 * Cuando un cambio le hace dano a un jugador con un tipo de dano "neutro" (porque el juego anularia el real,
 * por ejemplo la lava con resistencia al fuego), aca se anota que lo mato, asi el mensaje de muerte lo dice bien.
 */
public final class Causas {
    private static final class C { final Component msg; final long hasta; C(Component m, long h) { msg = m; hasta = h; } }
    private static final Map<UUID, C> mapa = new HashMap<>();

    private Causas() {}

    /** msg: el mensaje de muerte completo (por ejemplo translatable death.attack.lava con el nombre). Vale 3 segundos. */
    public static void anotar(UUID u, Component msg) { mapa.put(u, new C(msg, System.currentTimeMillis() + 3000)); }

    /** El mensaje anotado si es reciente (y lo borra), o null. */
    public static Component sacar(UUID u) {
        C c = mapa.remove(u);
        return c != null && System.currentTimeMillis() <= c.hasta ? c.msg : null;
    }
}

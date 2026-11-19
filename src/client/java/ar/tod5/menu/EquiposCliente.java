package ar.tod5.menu;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Quien es admin y de que equipo es cada jugador, para dibujar el cartel de arriba de la cabeza.
 * Lo manda el plugin (canal tod5:equipos). No usa los equipos de Minecraft.
 */
public final class EquiposCliente {
	private EquiposCliente() {}
	/** equipo = null si no tiene; rgb = -1 si no tiene. */
	public record Info(String equipo, int rgb, boolean admin) {}
	private static Map<UUID, Info> todos = new HashMap<>();

	public static Info de(UUID u) { return todos.get(u); }
	public static void limpiar() { todos = new HashMap<>(); }

	public static void recibir(String json) {
		try {
			Map<UUID, Info> nuevo = new HashMap<>();
			JsonObject j = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("j");
			for (String k : j.keySet()) {
				JsonObject o = j.getAsJsonObject(k);
				nuevo.put(UUID.fromString(k), new Info(o.has("n") ? o.get("n").getAsString() : null, o.has("c") ? o.get("c").getAsInt() : -1, o.has("a") && o.get("a").getAsBoolean()));
			}
			todos = nuevo;
		} catch (Exception ignorado) { }
	}
}

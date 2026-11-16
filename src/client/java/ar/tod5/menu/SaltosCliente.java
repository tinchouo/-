package ar.tod5.menu;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orbes y pads (Geometry Dash), del lado del mod: el salto se hace aca, al instante.
 *  - pad: si tu caja toca el pad (el cuarto de abajo de su bloque), salis disparado
 *  - orbe: en el aire, adentro de su bloque, apretas espacio (recien apretado) y saltas; queda gastada hasta tocar el piso
 *    (si no es "se puede repetir")
 *  - 0,1 s de espera por cada orbe/pad
 *  - el salto reemplaza la velocidad para tu "arriba" (no se suma) y llega a la altura que tiene configurada
 * Se le avisa al server cada vez (para las particulas y para que no haya daño de caida).
 */
public final class SaltosCliente {
	private static final Logger LOG = LoggerFactory.getLogger("TOD5");
	private SaltosCliente() {}
	/** (x, y, z): el centro de la orbe, o el centro de la cara donde esta pegado el pad. n: para donde apunta el pad. */
	private record Salto(String id, boolean pad, boolean roja, double x, double y, double z, double altura, boolean repetir, Vec3 n) {
		AABB caja() {
			if (!pad) return new AABB(x - 0.5, y - 0.5, z - 0.5, x + 0.5, y + 0.5, z + 0.5);
			// un cuarto de bloque de alto, para afuera de la cara
			Vec3 a = new Vec3(x, y, z), b = a.add(n.scale(0.25));
			double ex = n.x == 0 ? 0.5 : 0, ey = n.y == 0 ? 0.5 : 0, ez = n.z == 0 ? 0.5 : 0;
			return new AABB(Math.min(a.x, b.x) - ex, Math.min(a.y, b.y) - ey, Math.min(a.z, b.z) - ez, Math.max(a.x, b.x) + ex, Math.max(a.y, b.y) + ey, Math.max(a.z, b.z) + ez);
		}
		Vec3 centro() { return new Vec3(x, y, z); }
	}
	private static Vec3 normal(String cara) {
		return switch (cara) {
			case "down" -> new Vec3(0, -1, 0); case "north" -> new Vec3(0, 0, -1); case "south" -> new Vec3(0, 0, 1);
			case "east" -> new Vec3(1, 0, 0); case "west" -> new Vec3(-1, 0, 0); default -> new Vec3(0, 1, 0);
		};
	}
	private static final List<Salto> SALTOS = new ArrayList<>();
	private static final Set<String> GASTADAS = new HashSet<>();
	private static final Map<String, Integer> ESPERA = new HashMap<>();
	private static boolean espacioAntes;
	/** Buffer: apretaste espacio en el aire (no es el del salto del piso) y lo seguis manteniendo: la proxima orbe que toques la usa. */
	private static boolean guardado;
	private static int vuelta;

	public static void recibir(String json) {
		SALTOS.clear();
		try {
			for (JsonElement e : JsonParser.parseString(json).getAsJsonArray()) {
				JsonObject j = e.getAsJsonObject();
				SALTOS.add(new Salto(j.get("id").getAsString(), j.get("tipo").getAsString().equals("pad"), j.get("color").getAsString().equals("roja"),
					j.get("x").getAsDouble(), j.get("y").getAsDouble(), j.get("z").getAsDouble(), j.get("altura").getAsDouble(),
					j.has("repetir") && j.get("repetir").getAsBoolean(), normal(j.has("cara") ? j.get("cara").getAsString() : "up")));
			}
		} catch (Exception e) { LOG.warn("TOD5 saltos: no entendi la lista ({})", e.getMessage()); }
		LOG.info("TOD5 saltos: {}", SALTOS.size());
	}
	public static void limpiar() { SALTOS.clear(); GASTADAS.clear(); ESPERA.clear(); }

	/** La velocidad para arriba que llega justo a esa altura (con la gravedad y el freno del aire de Minecraft). */
	static double velocidadPara(double altura) {
		double lo = 0, hi = 6;
		for (int k = 0; k < 40; k++) {
			double v0 = (lo + hi) / 2, v = v0, y = 0, max = 0;
			for (int t = 0; t < 400 && v > 0; t++) { y += v; max = Math.max(max, y); v = (v - 0.08) * 0.98; }
			if (max < altura) lo = v0; else hi = v0;
		}
		return (lo + hi) / 2;
	}

	public static void tick(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (p == null || SALTOS.isEmpty()) { espacioAntes = false; return; }
		ESPERA.replaceAll((k, v) -> v - 1);
		ESPERA.values().removeIf(v -> v <= 0);
		if (p.onGround()) GASTADAS.clear();
		// recien apretado: tambien cuenta un toque cortito entre dos ticks (el juego lo anota como "clic")
		boolean clic = false;
		while (mc.options.keyJump.consumeClick()) clic = true;
		boolean espacio = p.input.keyPresses.jump(), recien = clic || (espacio && !espacioAntes);
		espacioAntes = espacio;
		if (!espacio && !clic) guardado = false;                 // lo soltaste: se pierde el buffer
		else if (recien && !p.onGround()) guardado = true;       // apretado en el aire (no el del salto del piso)
		vuelta++;
		particulas(mc, p);
		if (p.getAbilities().flying || p.isSpectator() || p.isPassenger()) return;
		AABB yo = p.getBoundingBox();
		for (Salto s : SALTOS) {
			if (ESPERA.containsKey(s.id) || !yo.intersects(s.caja())) continue;
			if (!s.pad) {
				if (!guardado || p.onGround() || (!s.repetir && GASTADAS.contains(s.id))) continue;
				if (!s.repetir) GASTADAS.add(s.id);
				guardado = false;   // cada apretada sirve para una sola orbe
				saltar(p, s.altura, null);
				Efectos.orbe(s.centro(), s.roja);
			} else {
				saltar(p, s.altura, s.n);
				Efectos.pad(s.centro(), s.n, s.roja);
			}
			ESPERA.put(s.id, 2);
			if (ClientPlayNetworking.canSend(Red.SALTO)) ClientPlayNetworking.send(new Red.Texto(Red.SALTO, s.id));
			break;
		}
	}

	/** Las particulas de siempre: los pads echan para donde apuntan; las orbes (las que todavia podes usar) chupan para adentro. */
	private static void particulas(Minecraft mc, LocalPlayer p) {
		Vec3 yo = p.position();
		for (Salto s : SALTOS) {
			Vec3 c = s.centro();
			if (c.distanceToSqr(yo) > 32 * 32) continue;
			if (s.pad) Efectos.chispaPad(c, s.n, s.roja);
			else if ((s.repetir || !GASTADAS.contains(s.id)) && vuelta % 2 == 0) Efectos.chispaOrbe(c, s.roja);
		}
	}

	/** Reemplaza la velocidad hacia "arriba" por la del salto. Arriba: para donde apunta el pad, o (las orbes) tu arriba segun tu gravedad. */
	private static void saltar(LocalPlayer p, double altura, Vec3 hacia) {
		GravedadCliente.G g = GravedadCliente.de(p);
		Vec3 arriba = hacia != null ? hacia : new Vec3(-g.x, -g.y, -g.z);
		Vec3 v = p.getDeltaMovement();
		v = v.subtract(arriba.scale(v.dot(arriba))).add(arriba.scale(velocidadPara(altura)));
		p.setDeltaMovement(v);
		p.setOnGround(false);
		p.resetFallDistance();
	}
}

package ar.tod5.menu;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Plataformas que se mueven, del lado del mod: el server las dibuja (bloques de display) y aca se hacen solidas y te llevan.
 *  - el server dice cual es la pieza "raiz" (la de la esquina; esta en el centro de su bloque, a medio bloque de altura) y el tamaño
 *  - al empezar cada tick se anota donde esta cada plataforma: esa es la caja solida durante todo el tick (asi no se "escapa" mientras te moves)
 *  - si estabas parado arriba, te mueve lo mismo que se movio ella
 */
public final class PlataformasCliente {
	private static final Logger LOG = LoggerFactory.getLogger("TOD5");
	private PlataformasCliente() {}
	private record Plat(String id, UUID raiz, double ancho, double alto, double largo) {}
	private static final List<Plat> LISTA = new ArrayList<>();
	private static final Map<String, Vec3> AHORA = new HashMap<>();
	private static final Map<UUID, Entity> CACHE = new HashMap<>();
	private static String parado;

	public static void recibir(String json) {
		LISTA.clear();
		try {
			for (JsonElement e : JsonParser.parseString(json).getAsJsonArray()) {
				JsonObject j = e.getAsJsonObject();
				LISTA.add(new Plat(j.get("id").getAsString(), UUID.fromString(j.get("raiz").getAsString()), j.get("ancho").getAsDouble(), j.get("alto").getAsDouble(), j.get("largo").getAsDouble()));
			}
		} catch (Exception e) { LOG.warn("TOD5 plataformas: no entendi la lista ({})", e.getMessage()); }
		AHORA.keySet().retainAll(LISTA.stream().map(Plat::id).toList());
		CACHE.clear();
	}
	public static void limpiar() { LISTA.clear(); AHORA.clear(); CACHE.clear(); parado = null; }

	private static Entity raiz(Minecraft mc, Plat p) {
		Entity e = CACHE.get(p.raiz);
		if (e != null && !e.isRemoved()) return e;
		for (Entity x : mc.level.entitiesForRendering()) if (x.getUUID().equals(p.raiz)) { CACHE.put(p.raiz, x); return x; }
		return null;
	}
	private static AABB caja(Plat p, Vec3 pos) { return new AABB(pos.x, pos.y, pos.z, pos.x + p.ancho, pos.y + p.alto, pos.z + p.largo); }

	/** Al empezar el tick: anoto donde esta cada una y, si estaba parado en una, lo muevo con ella. */
	public static void inicioTick(Minecraft mc) {
		if (mc.level == null || mc.player == null || LISTA.isEmpty()) return;
		LocalPlayer yo = mc.player;
		for (Plat p : LISTA) {
			Entity e = raiz(mc, p);
			if (e == null) { AHORA.remove(p.id); continue; }
			Vec3 pos = e.position().subtract(0.5, 0.5, 0.5), antes = AHORA.put(p.id, pos);   // la pieza raiz esta en el centro de su bloque
			if (antes != null && p.id.equals(parado)) {
				Vec3 d = pos.subtract(antes);
				if (d.lengthSqr() > 1.0E-10 && d.lengthSqr() < 4.0) yo.setPos(yo.position().add(d));
			}
		}
	}
	/** Al terminar el tick: ¿quedo parado arriba de alguna? */
	public static void finTick(Minecraft mc) {
		parado = null;
		if (mc.player == null || LISTA.isEmpty()) return;
		AABB yo = mc.player.getBoundingBox();
		for (Plat p : LISTA) {
			Vec3 pos = AHORA.get(p.id);
			if (pos == null) continue;
			AABB c = caja(p, pos);
			boolean encima = yo.minY >= c.maxY - 0.02 && yo.minY <= c.maxY + 0.08 && yo.maxX > c.minX && yo.minX < c.maxX && yo.maxZ > c.minZ && yo.minZ < c.maxZ;
			if (encima) { parado = p.id; return; }
		}
	}
	/** Las cajas solidas de las plataformas que tocan esta zona (para el jugador propio). */
	public static void agregarFormas(AABB zona, List<VoxelShape> lista) {
		for (Plat p : LISTA) {
			Vec3 pos = AHORA.get(p.id);
			if (pos == null) continue;
			AABB c = caja(p, pos);
			if (c.intersects(zona)) lista.add(Shapes.create(c));
		}
	}
	public static boolean hay() { return !AHORA.isEmpty(); }
}

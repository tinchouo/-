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
 * Plataformas que se mueven, del lado del mod: aca se mueven de verdad.
 *  - el server dice de donde a donde va, en que tick del mundo empieza y cuanto tarda; cada compu calcula donde esta en cada tick
 *    (arranca y frena suave) y pone ahi las piezas que dibuja el server: asi se ve lisa y lo que ves es lo que pisas
 *  - la caja solida es la de ese tick, y si estabas parado arriba te mueve lo mismo que ella
 *  - si saltas (o te caes) de una que se mueve, te llevas su velocidad (momentum), una sola vez
 */
public final class PlataformasCliente {
	private static final Logger LOG = LoggerFactory.getLogger("TOD5");
	private PlataformasCliente() {}
	private record Plat(String id, double ancho, double alto, double largo, Vec3 a, Vec3 b, long inicio, int dur, boolean suave, int fade, List<UUID> piezas) {
		/** La esquina en ese tick (puede ser con decimales). */
		Vec3 donde(double tick) {
			double f = Math.max(0, Math.min(1, (tick - inicio) / dur));
			if (suave) f = f * f * (3 - 2 * f);
			return a.add(b.subtract(a).scale(f));
		}
		AABB caja(Vec3 pos) { return new AABB(pos.x, pos.y, pos.z, pos.x + ancho, pos.y + alto, pos.z + largo); }
	}
	private static final List<Plat> LISTA = new ArrayList<>();
	private static final Map<String, Vec3> AHORA = new HashMap<>();
	private static final Map<UUID, Entity> CACHE = new HashMap<>();
	private record Lugar(Plat p, double ox, double oz) {}
	private static final Map<UUID, Lugar> PIEZAS = new HashMap<>();
	private static String parado, paradoAntes;
	private static final Map<String, Vec3> ANTES = new HashMap<>();   // donde estaba cada una el tick anterior
	private static Vec3 velParado;                                    // la velocidad de la que tenia abajo (para el momentum)

	public static void recibir(String json) {
		LISTA.clear();
		try {
			for (JsonElement e : JsonParser.parseString(json).getAsJsonArray()) {
				JsonObject j = e.getAsJsonObject();
				List<UUID> pz = new ArrayList<>();
				for (JsonElement u : j.getAsJsonArray("piezas")) pz.add(UUID.fromString(u.getAsString()));
				LISTA.add(new Plat(j.get("id").getAsString(), j.get("ancho").getAsDouble(), j.get("alto").getAsDouble(), j.get("largo").getAsDouble(),
					new Vec3(j.get("ax").getAsDouble(), j.get("ay").getAsDouble(), j.get("az").getAsDouble()),
					new Vec3(j.get("bx").getAsDouble(), j.get("by").getAsDouble(), j.get("bz").getAsDouble()),
					j.get("inicio").getAsLong(), Math.max(1, j.get("dur").getAsInt()), !j.has("suave") || j.get("suave").getAsBoolean(),
					j.has("fade") ? j.get("fade").getAsInt() : 0, pz));
			}
		} catch (Exception e) { LOG.warn("TOD5 plataformas: no entendi la lista ({})", e.getMessage()); }
		AHORA.keySet().retainAll(LISTA.stream().map(Plat::id).toList());
		CACHE.clear();
		PIEZAS.clear();
		for (Plat p : LISTA) {
			int largo = (int) Math.round(p.largo);
			for (int n = 0; n < p.piezas.size(); n++) PIEZAS.put(p.piezas.get(n), new Lugar(p, n / largo + 0.5, n % largo + 0.5));
		}
	}
	/** Donde se dibuja esta pieza en este cuadro (null = no es de una plataforma). Sigue el horario, no lo que diga el server. */
	public static Vec3 dibujo(Entity e, float parcial) {
		if (PIEZAS.isEmpty()) return null;
		Lugar l = PIEZAS.get(e.getUUID());
		if (l == null || e.level() == null) return null;
		// Minecraft dibuja al jugador entre el tick anterior y el ultimo; el reloj del mundo ya avanzo uno despues de mover a todos: -2
		Vec3 pos = l.p.donde(e.level().getGameTime() - 2 + parcial);
		return pos.add(l.ox, 0.5, l.oz);
	}
	public static void limpiar() { LISTA.clear(); AHORA.clear(); ANTES.clear(); CACHE.clear(); PIEZAS.clear(); parado = paradoAntes = null; velParado = null; }

	private static Entity pieza(Minecraft mc, UUID u) {
		Entity e = CACHE.get(u);
		if (e != null && !e.isRemoved()) return e;
		for (Entity x : mc.level.entitiesForRendering()) if (x.getUUID().equals(u)) { CACHE.put(u, x); return x; }
		return null;
	}

	private static Vec3 arrastre;
	/** Al empezar el tick: donde esta cada una (la caja solida de este tick) y cuanto se movio la que tenia abajo. */
	public static void inicioTick(Minecraft mc) {
		arrastre = null;
		if (mc.level == null || mc.player == null || LISTA.isEmpty()) return;
		long tick = mc.level.getGameTime();
		for (Plat p : LISTA) {
			Vec3 pos = p.donde(tick), antes = AHORA.put(p.id, pos);
			ANTES.put(p.id, antes != null ? antes : p.donde(tick - 1));
			if (antes != null && p.id.equals(parado)) {
				Vec3 d = pos.subtract(antes);
				if (d.lengthSqr() > 1.0E-10 && d.lengthSqr() < 4.0) arrastre = d;
			}
		}
	}
	/**
	 * Lo muevo con la plataforma al principio de SU tick (despues de que Minecraft anoto donde estaba): asi el personaje y la camara
	 * se deslizan junto con ella en vez de dar un saltito cada tick.
	 */
	public static void arrastrar(LocalPlayer yo) {
		if (arrastre != null) { yo.setPos(yo.position().add(arrastre)); arrastre = null; }
		atrapar(yo);
	}
	/**
	 * Si venias cayendo y la plataforma (que sube, o vos que caes muy rapido) quedo "adentro" tuyo entre un tick y otro,
	 * te deja parado arriba en vez de que la atravieses: el tick anterior tus pies estaban arriba de ella.
	 */
	private static void atrapar(LocalPlayer yo) {
		if (LISTA.isEmpty() || yo.getAbilities().flying) return;
		AABB caja = yo.getBoundingBox();
		for (Plat p : LISTA) {
			Vec3 pos = AHORA.get(p.id), ant = ANTES.get(p.id);
			if (pos == null || ant == null) continue;
			AABB c = p.caja(pos);
			if (!caja.intersects(c) || caja.minY >= c.maxY) continue;
			double piesAntes = yo.yo, techoAntes = ant.y + p.alto;
			if (piesAntes < techoAntes - 0.1 || yo.getDeltaMovement().y > 0.2) continue;
			yo.setPos(yo.getX(), c.maxY, yo.getZ());
			Vec3 v = yo.getDeltaMovement();
			yo.setDeltaMovement(v.x, Math.max(0, pos.y - ant.y), v.z);
			yo.setOnGround(true);
			yo.resetFallDistance();
			parado = p.id;
			return;
		}
	}
	/** Al terminar el tick: pongo las piezas donde van (para que se dibujen lisas) y miro si quede parado arriba de alguna. */
	public static void finTick(Minecraft mc) {
		paradoAntes = parado;
		parado = null;
		if (mc.level == null || mc.player == null) return;
		LocalPlayer yo0 = mc.player;
		// momentum: estaba parado en una y ya no esta (salto, se cayo o la plataforma desaparecio): se lleva su velocidad
		if (LISTA.stream().noneMatch(p -> p.id.equals(paradoAntes)) && paradoAntes != null && velParado != null && !yo0.onGround()) {
			yo0.setDeltaMovement(yo0.getDeltaMovement().add(velParado));
			velParado = null; paradoAntes = null;
		}
		if (LISTA.isEmpty()) return;
		long tick = mc.level.getGameTime();
		for (Plat p : LISTA) {
			// Minecraft dibuja cada cosa entre donde estaba el tick anterior y donde esta ahora: le doy esos dos lugares exactos
			Vec3 ant = p.donde(tick - 1), act = p.donde(tick);
			for (int n = 0; n < p.piezas.size(); n++) {
				Entity e = pieza(mc, p.piezas.get(n));
				if (e == null) continue;
				int i = n / (int) Math.round(p.largo), k = n % (int) Math.round(p.largo);
				double ox = i + 0.5, oy = 0.5, oz = k + 0.5;
				e.setPos(act.x + ox, act.y + oy, act.z + oz);
				e.xo = e.xOld = ant.x + ox; e.yo = e.yOld = ant.y + oy; e.zo = e.zOld = ant.z + oz;
			}
		}
		LocalPlayer yo = mc.player;
		AABB caja = yo.getBoundingBox();
		for (Plat p : LISTA) {
			Vec3 pos = AHORA.get(p.id);
			if (pos == null) continue;
			AABB c = p.caja(pos);
			boolean encima = caja.minY >= c.maxY - 0.02 && caja.minY <= c.maxY + 0.08 && caja.maxX > c.minX && caja.minX < c.maxX && caja.maxZ > c.minZ && caja.minZ < c.maxZ;
			if (encima) {
				parado = p.id;
				Vec3 vel = p.donde(tick).subtract(p.donde(tick - 1));
				velParado = vel.lengthSqr() < 4.0 ? vel : null;
				break;
			}
		}
		// momentum: estaba parado en una y ya no (salto o se cayo): se lleva la velocidad que tenia la plataforma
		if (paradoAntes != null && parado == null && !yo.onGround() && velParado != null) {
			if (velParado.lengthSqr() > 1.0E-8) yo.setDeltaMovement(yo.getDeltaMovement().add(velParado));
			velParado = null;
		}
		desvanecer(mc, tick);
	}

	/** Modo desvanece: los ultimos ticks las piezas se van haciendo transparentes (16 pasos), todas juntas, en cada compu. */
	private static void desvanecer(Minecraft mc, long tick) {
		for (Plat p : LISTA) {
			if (p.fade <= 0) continue;
			long falta = p.inicio + p.dur - tick;
			int nivel = falta >= p.fade ? 15 : (int) Math.max(0, Math.min(15, Math.floor(15.999 * falta / p.fade)));
			for (UUID u : p.piezas) {
				if (!(pieza(mc, u) instanceof net.minecraft.world.entity.Display.ItemDisplay d)) continue;
				net.minecraft.world.item.ItemStack it = d.getItemStack();
				net.minecraft.resources.Identifier m = it.get(net.minecraft.core.component.DataComponents.ITEM_MODEL);
				if (m == null || !m.getPath().startsWith("plataforma_")) continue;
				String path = m.getPath(), base = path.substring(0, path.lastIndexOf('_') + 1), nuevo = base + nivel;
				if (nuevo.equals(path)) continue;
				net.minecraft.world.item.ItemStack copia = it.copy();
				copia.set(net.minecraft.core.component.DataComponents.ITEM_MODEL, net.minecraft.resources.Identifier.fromNamespaceAndPath("tod5", nuevo));
				d.setItemStack(copia);
			}
		}
	}
	/** Las cajas solidas de las plataformas que tocan esta zona (para el jugador propio). */
	public static void agregarFormas(AABB zona, List<VoxelShape> lista) {
		for (Plat p : LISTA) {
			Vec3 pos = AHORA.get(p.id);
			if (pos == null) continue;
			AABB c = p.caja(pos);
			if (c.intersects(zona)) lista.add(Shapes.create(c));
		}
	}
	public static boolean hay() { return !AHORA.isEmpty(); }
}

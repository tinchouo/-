package ar.tod5.menu;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Plataformas que se mueven, del lado del mod: aca se mueven y se ven de verdad.
 *  - el server manda una sola vez el horario (de donde a donde, cuanto tarda cada parte de la vuelta, desde que tick del mundo):
 *    con eso cada compu sabe, en cualquier tick, donde esta, si esta, si parpadea y cuan transparente es. No hace falta volver a mandar nada.
 *  - las piezas (que crea el server una sola vez) se dibujan donde dice el horario, cuadro por cuadro
 *  - la caja solida es la de ese tick; si estabas parado arriba te mueve con ella; si saltas o desaparece, te llevas su velocidad
 */
public final class PlataformasCliente {
	private static final Logger LOG = LoggerFactory.getLogger("TOD5");
	private PlataformasCliente() {}
	private record Plat(String id, double ancho, double alto, double largo, Vec3 a, Vec3 b, long cero, int salida, int dur, boolean suave,
			int fin, int ciclo, int parpadeo, int fade, int color, int[] piezas) {
		/** En que parte de la vuelta esta (ticks, con decimales). */
		double vuelta(double tick) { double c = (tick - cero) % ciclo; return c < 0 ? c + ciclo : c; }
		/** ¿Se puede pisar en ese tick? */
		boolean esta(double tick) { return tick >= cero && vuelta(tick) < fin; }
		/** La esquina en ese tick. */
		Vec3 donde(double tick) {
			if (tick < cero) return a;
			double f = Math.max(0, Math.min(1, (vuelta(tick) - salida) / dur));
			if (suave) f = f * f * (3 - 2 * f);
			return a.add(b.subtract(a).scale(f));
		}
		AABB caja(Vec3 pos) { return new AABB(pos.x, pos.y, pos.z, pos.x + ancho, pos.y + alto, pos.z + largo); }
		int largoI() { return (int) Math.round(largo); }
	}
	private static final List<Plat> LISTA = new ArrayList<>();
	private static final Map<String, Vec3> AHORA = new HashMap<>();   // donde esta cada una este tick (solo las que estan)
	private static final Map<String, Vec3> ANTES = new HashMap<>();   // y donde estaba el tick anterior
	private record Lugar(Plat p, double ox, double oz) {}
	private static final Map<Integer, Lugar> PIEZAS = new HashMap<>();
	private static final Map<Integer, String> PINTADA = new HashMap<>();   // como deje pintada cada pieza (para no repetir)
	private static String parado, paradoAntes;
	private static Vec3 velParado, arrastre;

	public static void recibir(String json) {
		LISTA.clear(); PIEZAS.clear(); PINTADA.clear();
		try {
			for (JsonElement e : JsonParser.parseString(json).getAsJsonArray()) {
				JsonObject j = e.getAsJsonObject();
				var arr = j.getAsJsonArray("piezas");
				int[] pz = new int[arr.size()];
				for (int i = 0; i < pz.length; i++) pz[i] = arr.get(i).getAsInt();
				Plat p = new Plat(j.get("id").getAsString(), j.get("ancho").getAsDouble(), j.get("alto").getAsDouble(), j.get("largo").getAsDouble(),
					new Vec3(j.get("ax").getAsDouble(), j.get("ay").getAsDouble(), j.get("az").getAsDouble()),
					new Vec3(j.get("bx").getAsDouble(), j.get("by").getAsDouble(), j.get("bz").getAsDouble()),
					j.get("cero").getAsLong(), j.get("salida").getAsInt(), Math.max(1, j.get("dur").getAsInt()), j.get("suave").getAsBoolean(),
					j.get("fin").getAsInt(), Math.max(1, j.get("ciclo").getAsInt()), j.get("parpadeo").getAsInt(), j.get("fade").getAsInt(),
					j.get("color").getAsInt(), pz);
				LISTA.add(p);
				int largo = p.largoI();
				for (int n = 0; n < pz.length; n++) PIEZAS.put(pz[n], new Lugar(p, n / largo + 0.5, n % largo + 0.5));
			}
		} catch (Exception e) { LOG.warn("TOD5 plataformas: no entendi la lista ({})", e.getMessage()); }
		AHORA.keySet().retainAll(LISTA.stream().map(Plat::id).toList());
	}
	public static void limpiar() { LISTA.clear(); AHORA.clear(); ANTES.clear(); PIEZAS.clear(); PINTADA.clear(); parado = paradoAntes = null; velParado = arrastre = null; }

	/** Donde se dibuja esta pieza en este cuadro (null = no es de una plataforma). Sigue el horario, no lo que diga el server. */
	public static Vec3 dibujo(Entity e, float parcial) {
		if (PIEZAS.isEmpty()) return null;
		Lugar l = PIEZAS.get(e.getId());
		if (l == null || e.level() == null) return null;
		// Minecraft dibuja al jugador entre el tick anterior y el ultimo; el reloj del mundo ya avanzo uno despues de mover a todos: -2
		Vec3 pos = l.p.donde(e.level().getGameTime() - 2 + parcial);
		return pos.add(l.ox, 0.5, l.oz);
	}

	/** Al empezar el tick: donde esta cada una (la caja solida de este tick) y cuanto se movio la que tenia abajo. */
	public static void inicioTick(Minecraft mc) {
		arrastre = null;
		if (mc.level == null || mc.player == null || LISTA.isEmpty()) return;
		long tick = mc.level.getGameTime();
		for (Plat p : LISTA) {
			if (!p.esta(tick)) { AHORA.remove(p.id); ANTES.remove(p.id); continue; }
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
	/** Si venias cayendo y la plataforma quedo "adentro" tuyo entre un tick y otro (el anterior tus pies estaban arriba): te deja parado arriba. */
	private static void atrapar(LocalPlayer yo) {
		if (AHORA.isEmpty() || yo.getAbilities().flying) return;
		AABB caja = yo.getBoundingBox();
		for (Plat p : LISTA) {
			Vec3 pos = AHORA.get(p.id), ant = ANTES.get(p.id);
			if (pos == null || ant == null) continue;
			AABB c = p.caja(pos);
			if (!caja.intersects(c) || caja.minY >= c.maxY) continue;
			if (yo.yo < ant.y + p.alto - 0.1 || yo.getDeltaMovement().y > 0.2) continue;
			yo.setPos(yo.getX(), c.maxY, yo.getZ());
			Vec3 v = yo.getDeltaMovement();
			yo.setDeltaMovement(v.x, Math.max(0, pos.y - ant.y), v.z);
			yo.setOnGround(true);
			yo.resetFallDistance();
			parado = p.id;
			return;
		}
	}

	/** Al terminar el tick: ¿quede parado arriba de alguna? momentum. Y el aspecto de las piezas (desvanecido, destellos). */
	public static void finTick(Minecraft mc) {
		paradoAntes = parado;
		parado = null;
		if (mc.level == null || mc.player == null || LISTA.isEmpty()) { velParado = null; return; }
		LocalPlayer yo = mc.player;
		long tick = mc.level.getGameTime();
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
		// momentum: estaba parado en una y ya no (salto, se cayo o desaparecio): se lleva la velocidad que tenia la plataforma
		if (paradoAntes != null && parado == null && !yo.onGround() && velParado != null) {
			if (velParado.lengthSqr() > 1.0E-8) yo.setDeltaMovement(yo.getDeltaMovement().add(velParado));
			velParado = null;
		}
		for (Plat p : LISTA) pintar(mc, p, tick);
	}

	/** Desvanecido (16 pasos, todas juntas) y destellos blancos: lo decide el horario, en cada compu. Solo toca las piezas que cambian. */
	private static void pintar(Minecraft mc, Plat p, long tick) {
		double c = p.vuelta(tick);
		int nivel = 15;
		if (p.fade > 0) {
			double falta = p.fin - c;
			if (falta < p.fade) nivel = (int) Math.max(0, Math.min(15, Math.floor(15.999 * falta / p.fade)));
		}
		boolean blanca = false;
		if (p.parpadeo >= 0 && c < p.fin) {
			double b = c - p.parpadeo;
			blanca = (b >= 1 && b < 4) || (b >= 11 && b < 14) || b >= 21;
		}
		String estado = nivel + (blanca ? "b" : "");
		for (int id : p.piezas) {
			if (estado.equals(PINTADA.get(id))) continue;
			if (!(mc.level.getEntity(id) instanceof Display.ItemDisplay d)) continue;
			ItemStack it = d.getItemStack();
			Identifier m = it.get(DataComponents.ITEM_MODEL);
			if (m == null || !m.getPath().startsWith("plataforma_")) continue;
			String path = m.getPath(), base = path.substring(0, path.lastIndexOf('_') + 1);
			ItemStack copia = it.copy();
			copia.set(DataComponents.ITEM_MODEL, Identifier.fromNamespaceAndPath("tod5", base + nivel));
			copia.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(List.of(), List.of(), List.of(), List.of(blanca ? 0xFFFFFF : p.color)));
			d.setItemStack(copia);
			PINTADA.put(id, estado);
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

package ar.tod5.menu;

import ar.tod5.menu.tablet.TabletDatos;
import ar.tod5.menu.tablet.TabletHud;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Util;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Misiones fijadas (hasta 5) a la derecha, a media altura: tarjetitas con el nombre, una barrita y los objetivos como iconos
 * ("Conseguir:" + los items en fila, "Matar:" + la cabeza del mob...). Lo conseguido se ve gris con un tilde del color de la mision.
 * Cuando avanzás, el icono del objetivo sale del centro de la pantalla con un brillo del color de la mision y tarda 3 s en llegar
 * a su tarjeta (o a la tablet chiquita si no esta fijada). Recien al llegar sube el numero (y la tarjeta hace "boing").
 * Todo es del mod: el server no hace nada extra.
 */
public final class MisionesFijadas {
	private MisionesFijadas() {}
	public static final int MAX = 5;
	private static final long VUELO = 3000;
	private static final Identifier BRILLO = Identifier.fromNamespaceAndPath("tod5", "textures/efectos/rareza_brillo.png");
	private static final float[] TONOS = { 1.25f, 1.4f, 1.55f, 1.7f, 1.85f };   // 5 variaciones del sonidito

	private static final Set<String> FIJADAS = new LinkedHashSet<>();
	private static List<TabletDatos.Mision> misiones = List.of();
	private static final Map<String, Integer> CONOCIDO = new HashMap<>(), MOSTRADO = new HashMap<>();
	private static final Map<String, long[]> POS = new HashMap<>();          // uid del objetivo -> donde esta su icono (x, y) este frame
	private static final Map<String, Long> REBOTE = new HashMap<>(), TERMINADA = new HashMap<>();
	private record Vuelo(String uid, String mision, int cantidad, ItemStack icono, int color, long inicio) {}
	private static final List<Vuelo> VUELOS = new ArrayList<>();
	private static boolean cargadas;

	// ================= fijar =================
	public static boolean fijada(String id) { cargar(); return FIJADAS.contains(id); }
	/** Fija o desfija. Devuelve un aviso si no se pudo (ya hay 5). */
	public static String alternar(String id) {
		cargar();
		if (!FIJADAS.remove(id)) {
			if (FIJADAS.size() >= MAX) return "Puedes fijar hasta " + MAX + " misiones.";
			FIJADAS.add(id);
		}
		guardar();
		return null;
	}
	private static Path archivo() { return FabricLoader.getInstance().getConfigDir().resolve("tod5_fijadas.txt"); }
	private static void cargar() {
		if (cargadas) return;
		cargadas = true;
		try { if (Files.exists(archivo())) for (String l : Files.readAllLines(archivo())) if (!l.isBlank()) FIJADAS.add(l.trim()); } catch (Exception ignorado) { }
	}
	private static void guardar() { try { Files.write(archivo(), FIJADAS); } catch (Exception ignorado) { } }

	// ================= el progreso que llega del server =================
	public static void actualizar(List<TabletDatos.Mision> nuevas) {
		long ahora = Util.getMillis();
		for (TabletDatos.Mision m : nuevas) {
			int color = m.diaria() ? 0xFF55FF55 : 0xFF55FFFF;
			int i = 0;
			for (TabletDatos.Objetivo o : m.objetivos()) {
				int nuevo = Math.min(o.hecho(), o.cantidad());
				Integer antes = CONOCIDO.put(o.uid(), nuevo);
				if (antes == null || nuevo < antes) { MOSTRADO.put(o.uid(), nuevo); continue; }   // lo primero que llega (o se reinicio): sin animacion
				int dif = nuevo - antes;
				if (dif <= 0) continue;
				if (Ajustes.rendimiento()) { MOSTRADO.merge(o.uid(), dif, Integer::sum); continue; }
				int vuelos = Math.min(dif, 4), resto = dif;
				for (int k = 0; k < vuelos; k++) {   // varios iconitos (hasta 4), uno atras del otro
					int c = k == vuelos - 1 ? resto : dif / vuelos;
					resto -= c;
					VUELOS.add(new Vuelo(o.uid(), m.id(), c, icono(o), color, ahora + (i++) * 180L));
				}
			}
		}
		misiones = nuevas;
	}
	/** ¿Todavia le falta llegar algun iconito a esta mision? (el aviso de "completada" espera) */
	public static boolean enVuelo(String misionId) {
		for (Vuelo v : VUELOS) if (v.mision().equals(misionId)) return true;
		return false;
	}
	private static int mostrado(TabletDatos.Objetivo o) { return Math.min(o.cantidad(), MOSTRADO.getOrDefault(o.uid(), Math.min(o.hecho(), o.cantidad()))); }

	/** El icono de un objetivo: el item; para matar, la cabeza del mob (o su huevo). */
	static ItemStack icono(TabletDatos.Objetivo o) {
		switch (o.tipo()) {
			case "matar" -> {
				if (o.item() != null && !o.item().isEmpty()) return o.item();
				String c = o.clave().replace("minecraft:", "").trim().toLowerCase();
				Item cab = switch (c) {
					case "zombie" -> Items.ZOMBIE_HEAD; case "skeleton" -> Items.SKELETON_SKULL; case "wither_skeleton" -> Items.WITHER_SKELETON_SKULL;
					case "creeper" -> Items.CREEPER_HEAD; case "piglin" -> Items.PIGLIN_HEAD; case "ender_dragon" -> Items.DRAGON_HEAD; default -> null;
				};
				if (cab != null) return new ItemStack(cab);
				Item huevo = BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(c + "_spawn_egg"));
				return huevo != null && huevo != Items.AIR ? new ItemStack(huevo) : new ItemStack(Items.IRON_SWORD);
			}
			case "ir" -> { return new ItemStack(Items.COMPASS); }
			case "evento" -> { return o.item() != null && !o.item().isEmpty() ? o.item() : new ItemStack(Items.NETHER_STAR); }
			default -> { return o.item() == null || o.item().isEmpty() ? new ItemStack(Items.BARRIER) : o.item().copyWithCount(1); }
		}
	}
	private static String verbo(String tipo) {
		return switch (tipo) { case "matar" -> "Matar:"; case "picar" -> "Picar:"; case "ir" -> "Ir a:"; case "evento" -> "Evento:"; default -> "Conseguir:"; };
	}

	// ================= llegadas (en el tick, aunque no se dibuje) =================
	public static void registrar() {
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(TOD5Client.MOD_ID, "misiones_fijadas"), (g, dt) -> dibujar(g));
		ClientTickEvents.END_CLIENT_TICK.register(mc -> llegadas(mc));
	}
	private static void llegadas(Minecraft mc) {
		long ahora = Util.getMillis();
		for (Iterator<Vuelo> it = VUELOS.iterator(); it.hasNext(); ) {
			Vuelo v = it.next();
			if (ahora - v.inicio() < VUELO) continue;
			it.remove();
			int antes = MOSTRADO.getOrDefault(v.uid(), 0);
			MOSTRADO.put(v.uid(), antes + v.cantidad());
			if (FIJADAS.contains(v.mision()) && POS.containsKey(v.uid())) REBOTE.put(v.mision(), ahora); else TabletHud.rebotar();
			float tono = TONOS[(int) (Math.random() * TONOS.length)];
			mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.AMETHYST_BLOCK_CHIME, tono, 0.35f));
			TabletDatos.Objetivo o = buscar(v.uid());
			if (o != null && antes < o.cantidad() && antes + v.cantidad() >= o.cantidad())   // objetivo terminado
				mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BELL.value(), 1.5f, 0.45f));
		}
		// las fijadas que ya se completaron (y llego todo) se van solas a los 5 s
		for (TabletDatos.Mision m : misiones) {
			if (!FIJADAS.contains(m.id())) continue;
			boolean toda = !m.objetivos().isEmpty() && m.objetivos().stream().allMatch(o -> mostrado(o) >= o.cantidad()) && !enVuelo(m.id());
			if (!toda) { TERMINADA.remove(m.id()); continue; }
			long t = TERMINADA.computeIfAbsent(m.id(), k -> ahora);
			if (ahora - t > 5000) { FIJADAS.remove(m.id()); TERMINADA.remove(m.id()); guardar(); }
		}
	}
	private static TabletDatos.Objetivo buscar(String uid) {
		for (TabletDatos.Mision m : misiones) for (TabletDatos.Objetivo o : m.objetivos()) if (o.uid().equals(uid)) return o;
		return null;
	}

	// ================= dibujar =================
	private static final int ANCHO = 118, CASILLA = 18;
	private static void dibujar(GuiGraphics g) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.options.hideGui || mc.getDebugOverlay().showDebugScreen()) return;
		if (mc.screen != null && !(mc.screen instanceof ChatScreen)) return;
		cargar();
		Font f = mc.font;
		long ahora = Util.getMillis();
		POS.clear();
		List<TabletDatos.Mision> l = new ArrayList<>();
		for (String id : FIJADAS) for (TabletDatos.Mision m : misiones) if (m.id().equals(id) && !m.estado().equals("vencida")) l.add(m);
		// alto de cada tarjeta
		int total = 0;
		List<List<List<TabletDatos.Objetivo>>> filas = new ArrayList<>();
		for (TabletDatos.Mision m : l) { List<List<TabletDatos.Objetivo>> fs = armarFilas(f, m); filas.add(fs); total += alto(fs) + 3; }
		int x = g.guiWidth() - ANCHO - 4, y = g.guiHeight() / 2 - total / 2;
		for (int k = 0; k < l.size(); k++) {
			TabletDatos.Mision m = l.get(k);
			int h = alto(filas.get(k));
			Long rb = REBOTE.get(m.id());
			float e = rb == null ? 1f : rebote((ahora - rb) / 350f);
			g.pose().pushMatrix();
			if (e != 1f) { g.pose().translate(x + ANCHO / 2f, y + h / 2f); g.pose().scale(e, e); g.pose().translate(-(x + ANCHO / 2f), -(y + h / 2f)); }
			tarjeta(g, f, m, filas.get(k), x, y, h, ahora);
			g.pose().popMatrix();
			y += h + 3;
		}
		// los iconitos volando
		for (Vuelo v : VUELOS) {
			float t = (ahora - v.inicio()) / (float) VUELO;
			if (t < 0) continue;
			t = Math.min(1f, t);
			float ease = t < 0.5f ? 2 * t * t : 1 - (float) Math.pow(-2 * t + 2, 2) / 2;
			float sx = g.guiWidth() / 2f, sy = g.guiHeight() / 2f;
			long[] dst = POS.get(v.uid());
			float ex = dst != null ? dst[0] + 8 : TabletHud.MARGEN + 8, ey = dst != null ? dst[1] + 8 : TabletHud.MARGEN + 10;
			float cx = (sx + ex) / 2, cy = Math.min(sy, ey) - 50;   // la curva
			float u = 1 - ease;
			float px = u * u * sx + 2 * u * ease * cx + ease * ease * ex, py = u * u * sy + 2 * u * ease * cy + ease * ease * ey;
			float aparece = Math.min(1f, t * 6f);
			int z = (int) (26 + 4 * Math.sin(t * Math.PI * 4));
			g.blit(RenderPipelines.GUI_TEXTURED, BRILLO, (int) px - z / 2, (int) py - z / 2, 0f, 0f, z, z, 32, 32, 32, 32, ((int) (210 * aparece) << 24) | (v.color() & 0xFFFFFF));
			g.renderItem(v.icono(), (int) px - 8, (int) py - 8);
		}
	}
	private static float rebote(float t) { return t < 0 || t >= 1 ? 1f : 1f + 0.08f * (float) Math.sin(t * Math.PI) * (1 - t); }

	/** Los objetivos agrupados por verbo (los seguidos con el mismo verbo van en la misma fila; si no entran, siguen abajo). */
	private static List<List<TabletDatos.Objetivo>> armarFilas(Font f, TabletDatos.Mision m) {
		List<List<TabletDatos.Objetivo>> filas = new ArrayList<>();
		List<TabletDatos.Objetivo> fila = null; String vb = null; int usado = 0;
		for (TabletDatos.Objetivo o : m.objetivos()) {
			String v = verbo(o.tipo());
			if (fila == null || !v.equals(vb) || usado + CASILLA > ANCHO - 8) {
				fila = new ArrayList<>(); filas.add(fila);
				usado = 5 + f.width(v) + 3;
				vb = v;
			}
			fila.add(o); usado += CASILLA;
		}
		return filas;
	}
	private static int alto(List<List<TabletDatos.Objetivo>> filas) { return 16 + filas.size() * CASILLA + 2; }

	private static void tarjeta(GuiGraphics g, Font f, TabletDatos.Mision m, List<List<TabletDatos.Objetivo>> filas, int x, int y, int h, long ahora) {
		int color = m.diaria() ? 0xFF55FF55 : 0xFF55FFFF;
		boolean terminada = TERMINADA.containsKey(m.id());
		g.fill(x, y, x + ANCHO, y + h, terminada ? 0xB0123A1C : 0xA00A0E0C);
		g.fill(x, y, x + 2, y + h, color);
		String nombre = m.diaria() ? "Día " + m.dia() : m.titulo();
		while (f.width(nombre) > ANCHO - 10 && nombre.length() > 2) nombre = nombre.substring(0, nombre.length() - 2) + "…";
		g.drawString(f, nombre, x + 6, y + 3, color, true);
		// la barrita (con lo que ya llego)
		int hecho = 0, tot = 0;
		for (TabletDatos.Objetivo o : m.objetivos()) { hecho += mostrado(o); tot += o.cantidad(); }
		int bw = ANCHO - 10;
		g.fill(x + 6, y + 12, x + 6 + bw, y + 13, 0xFF3A3A3A);
		g.fill(x + 6, y + 12, x + 6 + (int) (bw * (tot == 0 ? 0 : hecho / (float) tot)), y + 13, color);
		int fy = y + 16;
		String anterior = null;
		for (List<TabletDatos.Objetivo> fila : filas) {
			int fx = x + 6;
			String v = verbo(fila.get(0).tipo());
			if (!v.equals(anterior)) { g.drawString(f, v, fx, fy + 5, 0xFFE6E6E6, true); fx += f.width(v) + 3; }
			else fx += f.width(v) + 3;
			anterior = v;
			for (TabletDatos.Objetivo o : fila) {
				int ix = fx, iy = fy + 1;
				g.renderItem(icono(o), ix, iy);
				int mo = mostrado(o);
				if (mo >= o.cantidad()) {   // conseguido: gris, opaco y con el tilde del color de la mision
					g.fill(ix, iy, ix + 16, iy + 16, 0xA0202020);
					g.drawString(f, "✔", ix + 9, iy + 8, color, true);
				} else if (o.cantidad() > 1) {
					String n = mo + "/" + o.cantidad();
					g.pose().pushMatrix(); g.pose().translate(ix + 17 - f.width(n) * 0.75f, iy + 11); g.pose().scale(0.75f, 0.75f);
					g.drawString(f, n, 0, 0, 0xFFFFFFFF, true);
					g.pose().popMatrix();
				}
				POS.put(o.uid(), new long[] { ix, iy });
				fx += CASILLA;
			}
			fy += CASILLA;
		}
	}
}

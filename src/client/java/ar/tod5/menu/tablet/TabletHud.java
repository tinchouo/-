package ar.tod5.menu.tablet;

import ar.tod5.menu.TOD5Client;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;

/**
 * Arriba a la izquierda: la tablet chiquita + la tecla para abrirla.
 * Si hay cosas sin ver, un numerito rojo; y cuando llega algo nuevo, abajo aparece unos segundos (con el color de su tipo).
 */
public final class TabletHud {
	private static final Identifier TABLET = Identifier.fromNamespaceAndPath(TOD5Client.MOD_ID, "textures/gui/hud/tablet_mini.png");
	private static final Identifier PUNTO = Identifier.fromNamespaceAndPath(TOD5Client.MOD_ID, "textures/gui/hud/punto.png");
	private static final long DURA = 7000, FUNDE = 600;
	private static final int MARGEN = 4, ANCHO_AVISO = 170, MAX_AVISOS = 3;

	private record Aviso(String titulo, String texto, int color, long desde) {}
	private static final List<Aviso> avisos = new ArrayList<>();
	private static final Set<String> avisados = new HashSet<>();

	private TabletHud() {}

	public static void registrar() {
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(TOD5Client.MOD_ID, "tablet_hud"), (g, dt) -> dibujar(g));
	}

	/** Revisa si llego algo nuevo que todavia no se aviso. */
	private static void revisar(TabletDatos d, long ahora) {
		for (TabletDatos.Novedad n : d.novedades) {
			if (!n.nuevo() || !avisados.add("n:" + n.id())) continue;
			int c = Tablet.colorDe(n.tipo())[0];
			avisos.add(new Aviso(n.titulo() != null ? n.titulo() : Tablet.etiquetaDe(n.tipo()), n.texto(), c, ahora));
		}
		for (TabletDatos.Crafteo c : d.crafteos) {
			if (!c.nuevo() || !avisados.add("c:" + c.id())) continue;
			avisos.add(new Aviso("NUEVO CRAFTEO", c.nombre() != null ? c.nombre() : "", 0xFF55FFFF, ahora));
		}
		avisos.removeIf(a -> ahora - a.desde() > DURA);
	}

	private static String recortar(Font f, String s, int ancho) {
		if (f.width(s) <= ancho) return s;
		while (s.length() > 1 && f.width(s + "...") > ancho) s = s.substring(0, s.length() - 1);
		return s + "...";
	}

	private static int alfa(int argb, float a) { return ((int) (((argb >>> 24) & 255) * a) << 24) | (argb & 0xFFFFFF); }

	private static void dibujar(GuiGraphicsExtractor g) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.options.hideGui || mc.gui.screen() instanceof Tablet) return;
		Font f = mc.font;
		long ahora = Util.getMillis();
		TabletDatos d = TabletDatos.actual();
		revisar(d, ahora);

		// la tablet chiquita (16x20) y su tecla al costado
		int x = MARGEN, y = MARGEN;
		g.blit(RenderPipelines.GUI_TEXTURED, TABLET, x, y, 0, 0, 16, 20, 64, 80, 64, 80, 0xFFFFFFFF);
		String tecla = TeclaTablet.TECLA != null ? TeclaTablet.TECLA.getTranslatedKeyMessage().getString() : "J";
		int tw = Math.max(12, f.width(tecla) + 6), tx = x + 20, ty = y + 4;
		g.fill(tx, ty, tx + tw, ty + 12, 0xFF141618);                       // tecla: borde
		g.fill(tx + 1, ty + 1, tx + tw - 1, ty + 11, 0xFF3C4045);           // lado
		g.fill(tx + 1, ty + 1, tx + tw - 1, ty + 9, 0xFF5A5F66);            // cara de arriba
		g.text(f, tecla, tx + (tw - f.width(tecla) + 1) / 2, ty + 1, 0xFFE8E8E8, false);

		// numerito rojo con lo que no viste
		int nuevos = d.novedadesNuevas() + d.crafteosNuevos();
		if (nuevos > 0) {
			String n = nuevos > 9 ? "9+" : String.valueOf(nuevos);
			int bw = Math.max(10, f.width(n) + 4), bx = x + 16 - bw / 2 + 1, by = y - 3;
			g.blit(RenderPipelines.GUI_TEXTURED, PUNTO, bx, by, 0, 0, bw, 10, 32, 32, 32, 32, 0xFF4A0A0A);
			g.blit(RenderPipelines.GUI_TEXTURED, PUNTO, bx + 1, by + 1, 0, 0, bw - 2, 8, 32, 32, 32, 32, 0xFFE03232);
			g.text(f, n, bx + (bw - f.width(n)) / 2 + 1, by + 1, 0xFFFFFFFF, false);
		}

		// lo que llego recien, abajo de la tablet
		int ay = y + 24, mostrados = 0;
		for (Aviso a : avisos) {
			if (mostrados == MAX_AVISOS) break;
			long t = ahora - a.desde();
			float al = t > DURA - FUNDE ? (DURA - t) / (float) FUNDE : Math.min(1, t / 200f);
			if (al <= 0) continue;
			int alto = a.texto().isEmpty() ? 14 : 24;
			g.fill(x, ay, x + ANCHO_AVISO, ay + alto, alfa(0xC00A0E0C, al));
			g.fill(x + 3, ay + 3, x + 5, ay + alto - 3, alfa(a.color(), al));
			g.text(f, recortar(f, a.titulo(), ANCHO_AVISO - 12), x + 9, ay + 3, alfa(a.color(), al), true);
			if (!a.texto().isEmpty()) g.text(f, recortar(f, a.texto(), ANCHO_AVISO - 12), x + 9, ay + 13, alfa(0xFFBFC8C2, al), false);
			ay += alto + 2;
			mostrados++;
		}
		int resto = (int) avisos.stream().filter(a -> ahora - a.desde() < DURA).count() - mostrados;
		if (resto > 0) g.text(f, "y " + resto + " más en la tablet", x + 2, ay + 1, 0xFFAAAAAA, true);
	}
}

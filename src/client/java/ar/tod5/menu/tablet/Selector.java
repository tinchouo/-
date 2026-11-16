package ar.tod5.menu.tablet;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;

/**
 * Una ventanita encima de la tablet para elegir una cosa de una lista: con buscador, agrupada y con ruedita.
 * Se usa para vincular un anuncio (y despues para los equipos). Clic afuera o Esc: se cierra sin cambiar nada.
 */
final class Selector {
	/** Una fila: id ("" = ninguno), texto, lo de abajo en gris, color de la rayita y el grupo (encabezado). */
	record Opcion(String id, String texto, String sub, int color, String grupo) {}

	private final String titulo;
	private final List<Opcion> opciones;
	private final String elegido;
	private final Consumer<String> alElegir;
	private final EditorTexto buscar = new EditorTexto(false, 30);
	private final String vacio;
	private int scroll;
	private int lx, ly, lw, lh;

	Selector(String titulo, List<Opcion> opciones, String elegido, String vacio, Consumer<String> alElegir) {
		this.titulo = titulo; this.opciones = opciones; this.elegido = elegido == null ? "" : elegido; this.vacio = vacio; this.alElegir = alElegir;
		this.buscar.foco(true);
	}

	private static String llano(String s) {
		return Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
	}

	/** Dibuja la ventanita centrada en la pantalla de la tablet (sx, sy, sw, sh). Las zonas de clic las agrega a la tablet. */
	void dibujar(GuiGraphics g, Tablet t, int sx, int sy, int sw, int sh, int mx, int my) {
		Font f = Minecraft.getInstance().font;
		g.nextStratum();
		g.fill(sx, sy, sx + sw, sy + sh, 0xB0000000);   // oscurece lo de atras
		int w = Math.min(280, sw - 24), h = sh - 20, x = sx + (sw - w) / 2, y = sy + 10;
		g.fill(x - 1, y - 1, x + w + 1, y + h + 1, t.acento());
		g.fill(x, y, x + w, y + h, 0xFF0B100E);
		// encabezado
		t.titulo(g, f.plainSubstrByWidth(this.titulo, (int) ((w - 30) / 1.25f)), x + 6, y + 5);
		boolean sobreX = mx >= x + w - 16 && my >= y + 3 && mx < x + w - 3 && my < y + 16;
		g.drawString(f, "✖", x + w - 13, y + 6, sobreX ? 0xFFFF6060 : Tablet.GRIS_TEXTO, false);
		// buscador
		int by = y + 20;
		g.fill(x + 6, by, x + w - 6, by + 14, this.buscar.foco() ? t.acento() : 0xFF3A4840);
		g.fill(x + 7, by + 1, x + w - 7, by + 13, 0xFF070B09);
		this.buscar.dibujar(g, x + 10, by + 3, w - 20, 9, Tablet.TEXTO);
		if (this.buscar.plano().isEmpty()) g.drawString(f, "Buscar...", x + 10, by + 3, 0xFF4A5650, false);
		// la lista
		this.lx = x + 6; this.ly = by + 18; this.lw = w - 12; this.lh = y + h - 6 - this.ly;
		String q = llano(this.buscar.plano().trim());
		List<Opcion> vis = new ArrayList<>();
		for (Opcion o : this.opciones) if (q.isEmpty() || o.id().isEmpty() || llano(o.texto()).contains(q) || llano(o.grupo()).contains(q) || llano(o.sub()).contains(q)) vis.add(o);
		g.enableScissor(lx, ly, lx + lw, ly + lh);
		int yy = ly - this.scroll; String grupoAnt = null;
		List<Runnable> zonas = new ArrayList<>();
		for (Opcion o : vis) {
			if (o.grupo() != null && !o.grupo().isEmpty() && !o.grupo().equals(grupoAnt)) { g.drawString(f, o.grupo(), lx + 1, yy + 3, Tablet.GRIS_TEXTO, false); yy += 13; grupoAnt = o.grupo(); }
			int alto = o.sub().isEmpty() ? 14 : 22;
			boolean sel = o.id().equals(this.elegido);
			boolean sobre = mx >= lx && my >= Math.max(yy, ly) && mx < lx + lw && my < Math.min(yy + alto, ly + lh);
			g.fill(lx, yy, lx + lw, yy + alto, sel ? t.acento() : sobre ? 0xFF8A948E : Tablet.LINEA);
			g.fill(lx + 1, yy + 1, lx + lw - 1, yy + alto - 1, sel ? 0xFF2A2610 : sobre ? 0xFF1E2D24 : 0xFF141B18);
			if (o.color() != 0) g.fill(lx + 3, yy + 3, lx + 5, yy + alto - 3, o.color());
			g.drawString(f, Tablet.cortar(f, o.texto(), lw - 14 - (sel ? 12 : 0)), lx + 8, yy + 3, o.id().isEmpty() ? Tablet.GRIS_TEXTO : Tablet.TEXTO, false);
			if (sel) g.drawString(f, "✔", lx + lw - 11, yy + 3, t.acento(), false);
			if (!o.sub().isEmpty()) g.drawString(f, Tablet.cortar(f, o.sub(), lw - 14), lx + 8, yy + 12, Tablet.GRIS_TEXTO, false);
			if (yy + alto > ly && yy < ly + lh) {
				final int zy = Math.max(yy, ly), zh = Math.min(yy + alto, ly + lh) - zy;
				t.zona(lx, zy, lw, zh, () -> { t.cerrarSelector(); this.alElegir.accept(o.id()); });
			}
			yy += alto + 2;
		}
		g.disableScissor();
		this.scroll = Math.max(0, Math.min(this.scroll, yy + this.scroll - ly - lh));
		if (vis.stream().allMatch(o -> o.id().isEmpty())) {
			int ty = yy + 4;
			for (var r : f.split(net.minecraft.network.chat.Component.literal(q.isEmpty() ? this.vacio : "No hay nada con \"" + this.buscar.plano().trim() + "\"."), lw - 4)) {
				g.drawString(f, r, lx + 2, ty, 0xFFE0A040, false); ty += 10;
			}
		}
		// la cruz y clic afuera: cerrar (van al final: las filas tienen prioridad)
		t.zona(x + w - 16, y + 3, 13, 13, t::cerrarSelector);
		t.zona(x, y, w, h, () -> this.buscar.foco(true));   // adentro, pero no en una fila: no hace nada
		t.zona(sx - 2000, sy - 2000, sw + 4000, sh + 4000, t::cerrarSelector);
	}

	boolean tecla(KeyEvent e) { this.scroll = this.buscar.tecla(e) ? 0 : this.scroll; return true; }
	boolean caracter(CharacterEvent e) { if (this.buscar.caracter(e)) this.scroll = 0; return true; }
	void ruedita(double dy) { this.scroll = Math.max(0, this.scroll - (int) (dy * 14)); }
}

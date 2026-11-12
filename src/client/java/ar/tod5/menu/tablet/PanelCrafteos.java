package ar.tod5.menu.tablet;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

/**
 * Modo admin > Crafteos: a la izquierda todos los crafteos (por categoria), a la derecha el editor.
 *  - nombre, categoria (la escribis vos: se crea sola), dia, nota
 *  - donde se hace: mesa, horno, fundicion, ahumador, herreria, pociones (y si la mesa pide forma exacta o libre)
 *  - estado: oculto / sombra / activo (activo = todos lo pueden hacer y aparece en su tablet)
 *  - "Armar receta": abre una mesa en el server donde pones los items (desde tu inventario o el creativo)
 * Todo se guarda en el server (lo ven todos los editores).
 */
final class PanelCrafteos extends Panel {
	private static final String[] TIPOS = { "mesa", "horno", "fundicion", "ahumador", "herreria", "pociones" };
	private static final String[] ESTADOS = { "oculto", "sombra", "activo" };
	private static final int[] COLOR_ESTADO = { 0xFFE06060, 0xFF8A8AA0, 0xFF55FF55 };

	private final Tablet t;
	private String id;
	private int dia = 1; private String tipo = "mesa"; private boolean forma = true;
	private final EditorTexto nombre = new EditorTexto(false, 40), categoria = new EditorTexto(false, 24), nota = new EditorTexto(true, 300);
	private boolean sucio; private long graciaHasta;
	private String confirmar; private long confirmarHasta;
	private String aviso; private long avisoHasta;
	private int scroll; private int lx, ly, lw, lh;

	PanelCrafteos(Tablet t) { this.t = t; }

	private static int indice(String[] a, String s) { for (int i = 0; i < a.length; i++) if (a[i].equals(s)) return i; return 0; }
	private TabletDatos.Crafteo actual(TabletDatos d) { return this.id == null ? null : d.crafteos.stream().filter(c -> c.id().equals(this.id)).findFirst().orElse(null); }

	private void abrir(TabletDatos.Crafteo c) {
		guardarSiHaceFalta();
		this.id = c.id(); this.dia = Math.max(1, c.dia()); this.tipo = c.tipo(); this.forma = c.forma();
		this.nombre.ponerPlano(c.nombre() == null ? "" : c.nombre()); this.categoria.ponerPlano(c.categoria() == null ? "General" : c.categoria());
		this.nota.ponerPlano(c.nota() == null ? "" : c.nota());
		sinFoco(); this.sucio = false; this.confirmar = null;
	}
	private void nuevo(TabletDatos d) {
		guardarSiHaceFalta();
		this.id = "c" + Long.toString(System.currentTimeMillis(), 36);
		this.dia = Math.max(1, d.hoy); this.tipo = "mesa"; this.forma = true;
		this.nombre.ponerPlano("Crafteo nuevo"); this.categoria.ponerPlano("General"); this.nota.ponerPlano("");
		sinFoco(); this.nombre.foco(true);
		mandarGuardar();
	}
	private JsonObject json() {
		JsonObject c = new JsonObject();
		c.addProperty("id", this.id); c.addProperty("nombre", this.nombre.plano().trim()); c.addProperty("categoria", this.categoria.plano().trim());
		c.addProperty("nota", this.nota.plano()); c.addProperty("dia", this.dia); c.addProperty("tipo", this.tipo); c.addProperty("forma", this.forma);
		return c;
	}
	private void mandarGuardar() {
		if (this.id == null) return;
		JsonObject o = new JsonObject(); o.addProperty("a", "crafteo_guardar"); o.add("crafteo", json());
		ar.tod5.menu.Conexion.accion(o.toString());
		this.sucio = false; this.graciaHasta = Util.getMillis() + 4000;
	}
	private void accion(String a, String extraK, String extraV) {
		JsonObject o = new JsonObject(); o.addProperty("a", a);
		JsonArray ids = new JsonArray(); ids.add(this.id); o.add("ids", ids);
		if (extraK != null) o.addProperty(extraK, extraV);
		ar.tod5.menu.Conexion.accion(o.toString());
	}
	@Override void guardarSiHaceFalta() { if (this.sucio && this.id != null) mandarGuardar(); }
	private void avisar(String s) { this.aviso = s; this.avisoHasta = Util.getMillis() + 2500; }

	@Override
	void dibujar(GuiGraphics g, TabletDatos d, int x, int y, int cw, int ch, int mx, int my) {
		Font f = Minecraft.getInstance().font;
		if (this.confirmar != null && Util.getMillis() > this.confirmarHasta) this.confirmar = null;
		if (this.id != null && !this.sucio && actual(d) == null && Util.getMillis() > this.graciaHasta) this.id = null;
		if (!d.pluginAlMenos("0.21")) {
			t.titulo(g, "Crafteos", x, y);
			g.drawString(f, "El plugin del server no tiene el editor de crafteos.", x, y + 22, Tablet.AMARILLO_ADMIN, false);
			g.drawString(f, "Actualizalo: tod5-plugin-servidor 0.21 o más nuevo.", x, y + 34, Tablet.TEXTO, false);
			return;
		}

		// ===== lista, por categoria =====
		this.lw = 102; this.lx = x; this.ly = y + 16; this.lh = ch - 16;
		t.titulo(g, "Crafteos", x, y);
		t.boton(g, "+Nuevo", x + lw - f.width("+Nuevo") - 10, y - 1, false, mx, my, () -> nuevo(d));
		List<TabletDatos.Crafteo> orden = new ArrayList<>(d.crafteos);
		orden.sort(Comparator.comparing((TabletDatos.Crafteo c) -> c.categoria() == null ? "" : c.categoria().toLowerCase()).thenComparingInt(TabletDatos.Crafteo::dia));
		g.enableScissor(lx, ly, lx + lw, ly + lh);
		int yy = ly - this.scroll; String catAnt = null;
		for (TabletDatos.Crafteo c : orden) {
			String cat = c.categoria() == null ? "General" : c.categoria();
			if (!cat.equals(catAnt)) { g.drawString(f, f.plainSubstrByWidth(cat, lw - 2), lx + 1, yy + 2, Tablet.GRIS_TEXTO, false); yy += 12; catAnt = cat; }
			boolean sel = c.id().equals(this.id);
			int alto = 20, est = indice(ESTADOS, c.estado());
			g.fill(lx, yy, lx + lw, yy + alto, sel ? t.acento() : Tablet.LINEA);
			g.fill(lx + 1, yy + 1, lx + lw - 1, yy + alto - 1, sel ? 0xFF2A2610 : 0xFF141B18);
			g.renderItem(c.icono(), lx + 2, yy + 2);
			String n = c.nombre() == null ? c.id() : c.nombre();
			g.drawString(f, f.plainSubstrByWidth(n, lw - 26), lx + 21, yy + 3, Tablet.TEXTO, false);
			g.drawString(f, ESTADOS[est].substring(0, 1).toUpperCase() + ESTADOS[est].substring(1), lx + 21, yy + 11, COLOR_ESTADO[est], false);
			if (yy + alto > ly && yy < ly + lh) t.zona(lx, Math.max(yy, ly), lw, Math.min(yy + alto, ly + lh) - Math.max(yy, ly), () -> abrir(c));
			yy += alto + 2;
		}
		g.disableScissor();
		this.scroll = Math.max(0, Math.min(this.scroll, yy + this.scroll - ly - lh));
		if (d.crafteos.isEmpty()) { g.drawString(f, "Todavía no hay", lx + 1, ly + 4, Tablet.GRIS_TEXTO, false); g.drawString(f, "crafteos.", lx + 1, ly + 14, Tablet.GRIS_TEXTO, false); }
		g.fill(x + lw + 6, y - 2, x + lw + 7, y + ch, Tablet.LINEA);

		// ===== editor =====
		int ex = x + lw + 14, ew = cw - lw - 14;
		TabletDatos.Crafteo c = actual(d);
		if (this.id == null) {
			g.drawString(f, "Elegí un crafteo de la lista", ex, y + 20, Tablet.GRIS_TEXTO, false);
			g.drawString(f, "o creá uno con \"+Nuevo\".", ex, y + 31, Tablet.GRIS_TEXTO, false);
			return;
		}
		int ey = y;
		// nombre
		g.drawString(f, "Nombre", ex, ey + 3, Tablet.GRIS_TEXTO, false);
		texto(g, this.nombre, ex + 54, ey, ew - 54, 14, Tablet.TEXTO);
		ey += 18;
		// categoria y dia
		g.drawString(f, "Categoría", ex, ey + 3, Tablet.GRIS_TEXTO, false);
		int catW = ew - 54 - 78;
		texto(g, this.categoria, ex + 54, ey, catW, 14, Tablet.TEXTO);
		int dx = ex + 54 + catW + 6;
		g.drawString(f, "Día", dx, ey + 3, Tablet.GRIS_TEXTO, false);
		dx += f.width("Día") + 3;
		dx += t.boton(g, "<", dx, ey, false, mx, my, () -> { this.dia = Math.max(1, this.dia - 1); this.sucio = true; }) + 1;
		String ds = String.valueOf(this.dia); g.drawString(f, ds, dx + (16 - f.width(ds)) / 2, ey + 3, Tablet.TEXTO, false); dx += 16;
		t.boton(g, ">", dx, ey, false, mx, my, () -> { this.dia = Math.min(999, this.dia + 1); this.sucio = true; });
		ey += 18;
		// donde se hace
		g.drawString(f, "Dónde", ex, ey + 4, Tablet.GRIS_TEXTO, false);
		int tx = ex + 54;
		for (String tp : TIPOS) {
			boolean sel = tp.equals(this.tipo);
			g.fill(tx, ey, tx + 18, ey + 18, sel ? t.acento() : 0xFF3A4840);
			g.fill(tx + 1, ey + 1, tx + 17, ey + 17, sel ? 0xFF2A2610 : 0xFF1A211D);
			g.renderItem(VistaReceta.iconoTipo(tp), tx + 1, ey + 1);
			t.zona(tx, ey, 18, 18, () -> { if (!tp.equals(this.tipo)) { this.tipo = tp; this.sucio = true; } });
			if (mx >= tx && my >= ey && mx < tx + 18 && my < ey + 18) g.setTooltipForNextFrame(Component.literal(VistaReceta.nombreTipo(tp)), mx, my);
			tx += 20;
		}
		if (this.tipo.equals("mesa")) {
			String fs = this.forma ? "Forma exacta" : "En cualquier orden";
			t.boton(g, fs, tx + 4, ey + 2, !this.forma, mx, my, () -> { this.forma = !this.forma; this.sucio = true; });
		}
		ey += 22;
		// estado
		g.drawString(f, "Estado", ex, ey + 3, Tablet.GRIS_TEXTO, false);
		int sx = ex + 54, estAct = c == null ? 0 : indice(ESTADOS, c.estado());
		for (int i = 0; i < ESTADOS.length; i++) {
			final String es = ESTADOS[i];
			String txt = es.substring(0, 1).toUpperCase() + es.substring(1);
			int w = t.boton(g, txt, sx, ey, i == estAct, mx, my, () -> { guardarSiHaceFalta(); accion("crafteo_estado", "estado", es); avisar(es.equals("activo") ? "Activo: ya lo pueden hacer todos." : es.equals("sombra") ? "Sombra: ven la silueta." : "Oculto."); });
			if (mx >= sx && my >= ey && mx < sx + w && my < ey + 13) g.setTooltipForNextFrame(Component.literal(switch (es) {
				case "activo" -> "Todos lo pueden hacer y aparece en su tablet";
				case "sombra" -> "En la tablet de los jugadores se ve solo la silueta negra (sin nombre ni receta)";
				default -> "Nadie lo ve ni lo puede hacer";
			}), mx, my);
			sx += w + 3;
		}
		ey += 18;
		// la receta
		g.fill(ex, ey, ex + ew, ey + 1, Tablet.LINEA);
		ey += 5;
		if (c != null) {
			TabletDatos.Crafteo vista = new TabletDatos.Crafteo(c.id(), c.nombre(), c.categoria(), c.dia(), c.estado(), this.tipo, this.forma, c.icono(), c.resultado(), c.entrada(), c.nota());
			int alto = VistaReceta.dibujar(g, vista, ex + 2, ey + 4, mx, my);
			boolean vacia = c.resultado() == null || c.resultado().isEmpty();
			int bx = ex + 2 + VistaReceta.ancho() + 10;
			t.boton(g, "Armar receta", bx, ey + 4, vacia, mx, my, () -> {
				mandarGuardar(); accion("crafteo_receta", null, null);
				t.onClose();   // se abre la mesa en el server
			});
			if (vacia) g.drawString(f, "Todavía no tiene receta.", bx, ey + 22, 0xFFE0A040, false);
			else if (!this.tipo.equals(c.tipo())) g.drawString(f, "Cambiaste el lugar: armala de nuevo.", bx, ey + 22, 0xFFE0A040, false);
			ey += Math.max(alto + 8, 34);
		}
		// nota
		// nota (de ancho entero: los renglones se cortan igual que en el chat)
		g.drawString(f, "Nota", ex, ey, Tablet.GRIS_TEXTO, false);
		int by = y + ch - 14;
		int notaAlto = Math.max(14, by - 4 - (ey + 10));
		texto(g, this.nota, ex, ey + 10, ew, Math.min(notaAlto, 32), Tablet.TEXTO);

		// botones
		int bbx = ex;
		bbx += t.boton(g, "Guardar", bbx, by, this.sucio, mx, my, () -> { mandarGuardar(); avisar("Guardado."); }) + 3;
		bbx += t.boton(g, "Duplicar", bbx, by, false, mx, my, () -> { guardarSiHaceFalta(); accion("crafteo_duplicar", null, null); avisar("Copia creada."); }) + 3;
		boolean confBor = "borrar".equals(this.confirmar);
		t.boton(g, confBor ? "¿Borrar? Clic de nuevo" : "Borrar", bbx, by, confBor, mx, my, () -> {
			if (confBor) { accion("crafteo_borrar", null, null); this.id = null; this.sucio = false; this.confirmar = null; }
			else { this.confirmar = "borrar"; this.confirmarHasta = Util.getMillis() + 3000; }
		});
		if (this.aviso != null && Util.getMillis() < this.avisoHasta) g.drawString(f, this.aviso, ex + ew - f.width(this.aviso), by + 3, t.acento(), false);
	}

	private void texto(GuiGraphics g, EditorTexto e, int x, int y, int w, int h, int color) {
		g.fill(x, y, x + w, y + h, e.foco() ? t.acento() : 0xFF3A4840);
		g.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFF070B09);
		String antes = e.plano();
		e.dibujar(g, x + 4, y + 3, w - 8, h - 5, color);
		if (!antes.equals(e.plano())) this.sucio = true;
	}

	// ---------- teclado y mouse ----------
	private EditorTexto[] cajas() { return new EditorTexto[] { this.nombre, this.categoria, this.nota }; }
	@Override boolean escribiendo() { for (EditorTexto e : cajas()) if (e.foco()) return true; return false; }
	@Override boolean tecla(KeyEvent ev) {
		if (ev.isEscape() && escribiendo()) { sinFoco(); return true; }
		if (ev.isCycleFocus() && escribiendo()) {   // Tab: a la caja siguiente
			EditorTexto[] cs = cajas();
			for (int i = 0; i < cs.length; i++) if (cs[i].foco()) { cs[i].foco(false); cs[(i + 1) % cs.length].foco(true); return true; }
		}
		boolean r = false;
		for (EditorTexto e : cajas()) r |= e.tecla(ev);
		if (r) this.sucio = true;
		return r || escribiendo();
	}
	@Override boolean caracter(CharacterEvent ev) {
		boolean r = false;
		for (EditorTexto e : cajas()) r |= e.caracter(ev);
		if (r) this.sucio = true;
		return r;
	}
	@Override boolean clic(double mx, double my, boolean shift) {
		if (this.id == null) return false;
		for (EditorTexto e : cajas()) if (e.adentro(mx, my)) { for (EditorTexto o : cajas()) if (o != e) o.foco(false); e.clic(mx, my, shift); return true; }
		return false;
	}
	@Override void sinFoco() { for (EditorTexto e : cajas()) e.foco(false); }
	@Override void arrastrar(double mx, double my) { for (EditorTexto e : cajas()) e.arrastrar(mx, my); }
	@Override void soltar() { for (EditorTexto e : cajas()) e.soltar(); }
	@Override boolean ruedita(double mx, double my, double dy) {
		if (mx >= lx && my >= ly && mx < lx + lw && my < ly + lh) { this.scroll = Math.max(0, this.scroll - (int) (dy * 12)); return true; }
		return false;
	}
}

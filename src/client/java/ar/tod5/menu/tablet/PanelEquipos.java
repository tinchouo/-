package ar.tod5.menu.tablet;

import com.google.gson.JsonObject;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.util.Util;

/**
 * Modo admin > Equipos: el admin hace todo.
 *  - Equipos: crear, renombrar, color, borrar; meter gente, sacarla, moverla (meterla en otro la saca del suyo) y elegir el lider.
 *  - Whitelist: buscar, agregar y quitar jugadores; prenderla o apagarla.
 */
final class PanelEquipos extends Panel {
	private final Tablet t;
	private boolean verWhitelist;
	private String id;                        // equipo elegido
	private final EditorTexto nombre = new EditorTexto(false, 32), buscar = new EditorTexto(false, 32);
	private boolean sucio;
	private String confirmar; private long confirmarHasta;
	private String aviso; private long avisoHasta;
	private int lx, ly, lw, lh, scroll, wx, wy, ww, wh, scrollW;

	PanelEquipos(Tablet t) { this.t = t; }

	private static void mandar(String a, String equipo, String uuid, String nombre, String color) {
		JsonObject o = new JsonObject(); o.addProperty("a", a);
		if (equipo != null) o.addProperty("equipo", equipo);
		if (uuid != null) o.addProperty("uuid", uuid);
		if (nombre != null) o.addProperty("nombre", nombre);
		if (color != null) o.addProperty("color", color);
		ar.tod5.menu.Conexion.accion(o.toString());
	}
	private void avisar(String s) { this.aviso = s; this.avisoHasta = Util.getMillis() + 2500; }
	private boolean confirmado(String clave) {
		if (clave.equals(this.confirmar) && Util.getMillis() < this.confirmarHasta) { this.confirmar = null; return true; }
		this.confirmar = clave; this.confirmarHasta = Util.getMillis() + 3000; return false;
	}
	private boolean pidiendo(String clave) { return clave.equals(this.confirmar) && Util.getMillis() < this.confirmarHasta; }
	private static String llano(String s) { return Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT); }

	@Override void guardarSiHaceFalta() {
		if (this.sucio && this.id != null && !this.nombre.plano().isBlank()) mandar("eq_editar", this.id, null, this.nombre.plano().trim(), null);
		this.sucio = false;
	}
	private void elegir(TabletDatos.Equipo e) {
		guardarSiHaceFalta();
		this.id = e.id; this.nombre.ponerPlano(e.nombre); this.nombre.foco(false); this.sucio = false;
	}

	@Override
	void dibujar(GuiGraphics g, TabletDatos d, int x, int y, int cw, int ch, int mx, int my) {
		Font f = Minecraft.getInstance().font;
		if (!d.conEquipos) {
			t.titulo(g, "Equipos", x, y);
			g.drawString(f, "El plugin del server es viejo: actualízalo (0.33 o más).", x, y + 22, Tablet.AMARILLO_ADMIN, false);
			return;
		}
		// pestañitas arriba a la derecha
		int fx = x + cw;
		String tw = "Whitelist (" + d.jugadores.stream().filter(TabletDatos.Jugador::whitelist).count() + ")";
		fx -= f.width(tw) + 10; t.boton(g, tw, fx, y - 1, this.verWhitelist, mx, my, () -> { guardarSiHaceFalta(); this.verWhitelist = true; });
		fx -= f.width("Equipos") + 13; t.boton(g, "Equipos", fx, y - 1, !this.verWhitelist, mx, my, () -> this.verWhitelist = false);
		if (this.verWhitelist) whitelist(g, d, f, x, y, cw, ch, mx, my);
		else equipos(g, d, f, x, y, cw, ch, mx, my);
		if (this.aviso != null && Util.getMillis() < this.avisoHasta) g.drawString(f, this.aviso, x, y + ch - 10, t.acento(), false);
	}

	// ================= equipos =================
	private void equipos(GuiGraphics g, TabletDatos d, Font f, int x, int y, int cw, int ch, int mx, int my) {
		TabletDatos.Equipo eq = this.id == null ? null : d.equipoAdmin(this.id);
		if (eq == null && this.id != null && !this.sucio) this.id = null;
		this.lw = 110; this.lx = x; this.ly = y + 16; this.lh = ch - 16;
		t.titulo(g, "Equipos", x, y);
		String nv = "+Nuevo";
		t.boton(g, nv, t.alLadoDelTitulo("Equipos", nv, x, lw), y + 14 - 14, false, mx, my, () -> {
			mandar("eq_crear", null, null, "Equipo " + (d.equiposAdmin.size() + 1), d.coloresEquipo.isEmpty() ? "yellow" : d.coloresEquipo.get(d.equiposAdmin.size() % d.coloresEquipo.size()).id());
			avisar("Equipo creado.");
		});
		g.enableScissor(lx, ly, lx + lw, ly + lh);
		int yy = ly + 4 - this.scroll;
		for (TabletDatos.Equipo e : d.equiposAdmin) {
			boolean sel = e.id.equals(this.id);
			g.fill(lx, yy, lx + lw, yy + 20, sel ? t.acento() : Tablet.LINEA);
			g.fill(lx + 1, yy + 1, lx + lw - 1, yy + 19, sel ? 0xFF2A2610 : 0xFF141B18);
			g.fill(lx + 3, yy + 3, lx + 6, yy + 17, e.color);
			g.drawString(f, Tablet.cortar(f, e.nombre, lw - 34), lx + 10, yy + 3, Tablet.TEXTO, false);
			g.drawString(f, e.miembros.size() + (e.miembros.size() == 1 ? " jugador" : " jugadores"), lx + 10, yy + 11, Tablet.GRIS_TEXTO, false);
			if (yy + 20 > ly && yy < ly + lh) { final TabletDatos.Equipo ee = e; t.zona(lx, Math.max(yy, ly), lw, Math.min(yy + 20, ly + lh) - Math.max(yy, ly), () -> elegir(ee)); }
			yy += 22;
		}
		g.disableScissor();
		this.scroll = Math.max(0, Math.min(this.scroll, yy + this.scroll - ly - lh));
		if (d.equiposAdmin.isEmpty()) { g.drawString(f, "Todavía no hay", lx + 1, ly + 6, Tablet.GRIS_TEXTO, false); g.drawString(f, "equipos.", lx + 1, ly + 16, Tablet.GRIS_TEXTO, false); }
		g.fill(x + lw + 6, y + 14, x + lw + 7, y + ch, Tablet.LINEA);

		int ex = x + lw + 14, ew = cw - lw - 14, ey = y + 18;
		if (eq == null) {
			g.drawString(f, "Elige un equipo de la lista o crea", ex, ey + 2, Tablet.GRIS_TEXTO, false);
			g.drawString(f, "uno con \"+Nuevo\".", ex, ey + 13, Tablet.GRIS_TEXTO, false);
			return;
		}
		// nombre
		g.drawString(f, "Nombre", ex, ey + 3, Tablet.GRIS_TEXTO, false);
		int nx = ex + 42, nw = ew - 42 - 50;
		g.fill(nx, ey, nx + nw, ey + 14, this.nombre.foco() ? t.acento() : 0xFF3A4840);
		g.fill(nx + 1, ey + 1, nx + nw - 1, ey + 13, 0xFF070B09);
		String antes = this.nombre.plano();
		this.nombre.dibujar(g, nx + 4, ey + 3, nw - 8, 9, eq.color);
		if (!antes.equals(this.nombre.plano())) this.sucio = true;
		t.boton(g, "Guardar", nx + nw + 4, ey, this.sucio, mx, my, () -> { guardarSiHaceFalta(); avisar("Guardado."); });
		ey += 18;
		// color
		g.drawString(f, "Color", ex, ey + 2, Tablet.GRIS_TEXTO, false);
		int cx = ex + 42;
		for (TabletDatos.ColorEquipo c : d.coloresEquipo) {
			boolean sel = c.id().equals(eq.colorId);
			g.fill(cx, ey, cx + 11, ey + 11, sel ? 0xFFFFFFFF : 0xFF000000);
			g.fill(cx + 1, ey + 1, cx + 10, ey + 10, c.rgb());
			if (mx >= cx && my >= ey && mx < cx + 11 && my < ey + 11) g.setTooltipForNextFrame(f, net.minecraft.network.chat.Component.literal(c.nombre()), mx, my);
			final String cid = c.id();
			t.zona(cx, ey, 11, 11, () -> mandar("eq_editar", eq.id, null, null, cid));
			cx += 12;
			if (cx + 11 > ex + ew) { cx = ex + 42; ey += 12; }
		}
		ey += 16;
		// miembros
		g.drawString(f, "Miembros (" + eq.miembros.size() + ")", ex, ey, Tablet.GRIS_TEXTO, false);
		String ag = "+ Agregar jugador";
		t.boton(g, ag, ex + ew - f.width(ag) - 10, ey - 3, false, mx, my, () -> {
			List<Selector.Opcion> ops = new ArrayList<>();
			for (TabletDatos.Jugador j : d.jugadores) {
				if (j.equipo().equals(eq.id)) continue;
				TabletDatos.Equipo otro = d.equipoAdmin(j.equipo());
				String grupo = otro == null ? "Sin equipo" : "En otro equipo (lo cambia)";
				ops.add(new Selector.Opcion(j.uuid(), j.nombre(), otro == null ? (j.conectado() ? "Conectado" : "") : otro.nombre, otro == null ? 0 : otro.color, grupo));
			}
			ops.sort((a, b) -> a.grupo().compareTo(b.grupo()) != 0 ? b.grupo().compareTo(a.grupo()) : a.texto().compareToIgnoreCase(b.texto()));
			t.abrirSelector(new Selector("Agregar a " + eq.nombre, ops, "", "No hay jugadores para agregar (agrégalos a la whitelist).", u -> { if (!u.isEmpty()) mandar("eq_meter", eq.id, u, null, null); }));
		});
		ey += 12;
		int bottom = y + ch - 18;
		for (TabletDatos.Miembro m : eq.miembros) {
			if (ey + 16 > bottom) { g.drawString(f, "...", ex, ey, Tablet.GRIS_TEXTO, false); break; }
			g.fill(ex, ey, ex + ew, ey + 16, Tablet.LINEA);
			g.fill(ex + 1, ey + 1, ex + ew - 1, ey + 15, 0xFF141B18);
			int mxx = ex + 4;
			if (m.lider()) { g.drawString(f, "★", mxx, ey + 4, Tablet.AMARILLO, true); mxx += 10; }
			g.drawString(f, Tablet.cortar(f, m.nombre(), ew - 120), mxx, ey + 4, m.conectado() ? Tablet.TEXTO : Tablet.GRIS_TEXTO, false);
			int bx = ex + ew - 2;
			String sc = pidiendo("sacar:" + m.uuid()) ? "¿Seguro?" : "Sacar";
			bx -= f.width(sc) + 10;
			t.boton(g, sc, bx, ey + 1, pidiendo("sacar:" + m.uuid()), mx, my, () -> { if (confirmado("sacar:" + m.uuid())) mandar("eq_sacar", eq.id, m.uuid(), null, null); });
			if (!m.lider()) {
				bx -= f.width("Líder") + 13;
				t.boton(g, "Líder", bx, ey + 1, false, mx, my, () -> mandar("eq_lider", eq.id, m.uuid(), null, null));
			}
			ey += 17;
		}
		// borrar
		String bt = pidiendo("borrar") ? "¿Borrar el equipo?" : "Borrar equipo";
		t.boton(g, bt, ex + ew - f.width(bt) - 10, y + ch - 14, pidiendo("borrar"), mx, my, () -> {
			if (confirmado("borrar")) { mandar("eq_borrar", eq.id, null, null, null); this.id = null; this.sucio = false; }
		});
	}

	// ================= whitelist =================
	private void whitelist(GuiGraphics g, TabletDatos d, Font f, int x, int y, int cw, int ch, int mx, int my) {
		t.titulo(g, "Whitelist", x, y);
		int ey = y + 18;
		String act = "Whitelist: " + (d.whitelistActiva ? "activa" : "apagada");
		int aw = t.boton(g, act, x, ey, d.whitelistActiva, mx, my, () -> {
			if (confirmado("wl")) mandar("wl_activar", null, null, null, null);
			else avisar(d.whitelistActiva ? "Clic otra vez para apagarla (entra cualquiera)." : "Clic otra vez para activarla (solo entran los de la lista).");
		});
		g.drawString(f, d.whitelistActiva ? "Solo entran los de la lista." : "Entra cualquiera.", x + aw + 6, ey + 3, Tablet.GRIS_TEXTO, false);
		ey += 18;
		// buscar / agregar
		int bw = cw - 70;
		g.fill(x, ey, x + bw, ey + 14, this.buscar.foco() ? t.acento() : 0xFF3A4840);
		g.fill(x + 1, ey + 1, x + bw - 1, ey + 13, 0xFF070B09);
		this.buscar.dibujar(g, x + 4, ey + 3, bw - 8, 9, Tablet.TEXTO);
		if (this.buscar.plano().isEmpty() && !this.buscar.foco()) g.drawString(f, "Buscar o escribir un nombre para agregar...", x + 4, ey + 3, 0xFF4A5650, false);
		String q = this.buscar.plano().trim();
		t.boton(g, "Agregar", x + bw + 4, ey, false, mx, my, () -> {
			if (q.isEmpty()) { avisar("Escribe el nombre del jugador."); return; }
			mandar("wl_agregar", null, null, q, null); this.buscar.ponerPlano(""); avisar("Agregado: " + q);
		});
		ey += 18;
		// la lista
		this.wx = x; this.wy = ey; this.ww = cw; this.wh = y + ch - 12 - ey;
		String ql = llano(q);
		List<TabletDatos.Jugador> vis = new ArrayList<>();
		for (TabletDatos.Jugador j : d.jugadores) if (ql.isEmpty() || llano(j.nombre()).contains(ql)) vis.add(j);
		vis.sort((a, b) -> a.whitelist() != b.whitelist() ? (a.whitelist() ? -1 : 1) : a.nombre().compareToIgnoreCase(b.nombre()));
		g.enableScissor(wx, wy, wx + ww, wy + wh);
		int yy = wy - this.scrollW;
		for (TabletDatos.Jugador j : vis) {
			g.fill(wx, yy, wx + ww, yy + 16, Tablet.LINEA);
			g.fill(wx + 1, yy + 1, wx + ww - 1, yy + 15, 0xFF141B18);
			g.fill(wx + 3, yy + 5, wx + 7, yy + 9, j.conectado() ? Tablet.VERDE : 0xFF3A443F);
			g.drawString(f, Tablet.cortar(f, j.nombre(), 120), wx + 11, yy + 4, j.whitelist() ? Tablet.TEXTO : Tablet.GRIS_TEXTO, false);
			TabletDatos.Equipo e = d.equipoAdmin(j.equipo());
			if (e != null) g.drawString(f, Tablet.cortar(f, "<" + e.nombre + ">", 110), wx + 136, yy + 4, e.color, false);
			else if (!j.whitelist()) g.drawString(f, "(no está en la whitelist)", wx + 136, yy + 4, Tablet.GRIS_TEXTO, false);
			if (yy + 16 > wy && yy < wy + wh) {
				String bt = j.whitelist() ? (pidiendo("wl:" + j.uuid()) ? "¿Seguro?" : "Quitar") : "Agregar";
				int bx = wx + ww - f.width(bt) - 12;
				t.boton(g, bt, bx, yy + 1, pidiendo("wl:" + j.uuid()), mx, my, () -> {
					if (!j.whitelist()) mandar("wl_agregar", null, null, j.nombre(), null);
					else if (confirmado("wl:" + j.uuid())) mandar("wl_sacar", null, j.uuid(), null, null);
				});
			}
			yy += 17;
		}
		g.disableScissor();
		this.scrollW = Math.max(0, Math.min(this.scrollW, yy + this.scrollW - wy - wh));
		if (vis.isEmpty()) g.drawString(f, q.isEmpty() ? "La whitelist está vacía." : "Nadie se llama así. Toca \"Agregar\" para sumarlo.", wx + 2, wy + 4, Tablet.GRIS_TEXTO, false);
	}

	// ================= teclado y mouse =================
	private EditorTexto[] cajas() { return this.verWhitelist ? new EditorTexto[] { this.buscar } : new EditorTexto[] { this.nombre }; }
	@Override boolean escribiendo() { for (EditorTexto e : cajas()) if (e.foco()) return true; return false; }
	@Override boolean tecla(KeyEvent ev) {
		if (ev.isEscape() && escribiendo()) { sinFoco(); return true; }
		if (escribiendo() && (ev.key() == 257 || ev.key() == 335)) {   // Enter: guardar el nombre / agregar a la whitelist
			if (this.verWhitelist) { String q = this.buscar.plano().trim(); if (!q.isEmpty()) { mandar("wl_agregar", null, null, q, null); this.buscar.ponerPlano(""); avisar("Agregado: " + q); } }
			else { guardarSiHaceFalta(); avisar("Guardado."); }
			sinFoco(); return true;
		}
		boolean r = false; for (EditorTexto e : cajas()) r |= e.tecla(ev);
		if (r && !this.verWhitelist) this.sucio = true;
		if (r) this.scrollW = 0;
		return r || escribiendo();
	}
	@Override boolean caracter(CharacterEvent ev) {
		boolean r = false; for (EditorTexto e : cajas()) r |= e.caracter(ev);
		if (r && !this.verWhitelist) this.sucio = true;
		if (r) this.scrollW = 0;
		return r;
	}
	@Override boolean clic(double mx, double my, boolean shift) {
		for (EditorTexto e : cajas()) if (e.adentro(mx, my)) { e.clic(mx, my, shift); return true; }
		return false;
	}
	@Override void sinFoco() { this.nombre.foco(false); this.buscar.foco(false); }
	@Override void arrastrar(double mx, double my) { for (EditorTexto e : cajas()) e.arrastrar(mx, my); }
	@Override void soltar() { for (EditorTexto e : cajas()) e.soltar(); }
	@Override boolean ruedita(double mx, double my, double dy) {
		if (!this.verWhitelist && mx >= lx && my >= ly && mx < lx + lw && my < ly + lh) { this.scroll = Math.max(0, this.scroll - (int) (dy * 12)); return true; }
		if (this.verWhitelist && mx >= wx && my >= wy && mx < wx + ww && my < wy + wh) { this.scrollW = Math.max(0, this.scrollW - (int) (dy * 12)); return true; }
		return false;
	}
}

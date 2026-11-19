package ar.tod5.menu.tablet;

import com.google.gson.JsonObject;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Util;

/**
 * La pestaña Equipo de cada jugador:
 *  - a la izquierda los miembros (★ líder, punto verde = conectado); el líder invita y expulsa, cualquiera puede salir
 *  - a la derecha, el Chat del equipo (solo lo ven los del equipo) o las Notas
 *  - las notas llevan el autor, el día y la hora; se marcan como hechas o como que ya no sirven (y se pueden reactivar)
 */
final class PanelEquipo extends Panel {
	private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm");
	private static final int NEGRO = 0xFF000000, BLANCO = 0xFFFFFFFF, SLOT = 0xFF19201C, SLOT_OSCURO = 0xFF050706, SLOT_CLARO = 0xFF34403A, SOMBRA = 0xFF3A443F;
	private final Tablet t;
	private boolean verNotas;
	private final EditorTexto mensaje = new EditorTexto(false, 120);
	private final EditorTexto notaTitulo = new EditorTexto(false, 40), notaTexto = new EditorTexto(true, 200);
	private boolean escribiendoNota;
	private String confirmar; private long confirmarHasta;
	private int scrollChat, scrollNotas;
	private int bx, by, bw, bh;   // el cuadro de la derecha (para la ruedita)

	PanelEquipo(Tablet t) { this.t = t; }

	private static void mandar(String a, String clave, String valor) {
		JsonObject o = new JsonObject(); o.addProperty("a", a);
		if (clave != null) o.addProperty(clave, valor);
		ar.tod5.menu.Conexion.accion(o.toString());
	}
	private static void mandar(JsonObject o) { ar.tod5.menu.Conexion.accion(o.toString()); }
	private boolean confirmado(String clave) {
		if (clave.equals(this.confirmar) && Util.getMillis() < this.confirmarHasta) { this.confirmar = null; return true; }
		this.confirmar = clave; this.confirmarHasta = Util.getMillis() + 3000; return false;
	}
	private boolean pidiendo(String clave) { return clave.equals(this.confirmar) && Util.getMillis() < this.confirmarHasta; }
	private static void hundido(GuiGraphics g, int x, int y, int w, int h) {
		g.fill(x, y, x + w, y + h, SLOT);
		g.fill(x, y, x + w, y + 1, SLOT_OSCURO);
		g.fill(x, y, x + 1, y + h, SLOT_OSCURO);
		g.fill(x, y + h - 1, x + w, y + h, SLOT_CLARO);
		g.fill(x + w - 1, y, x + w, y + h, SLOT_CLARO);
	}
	private static void marco(GuiGraphics g, int x, int y, int w, int h, int color) {
		g.fill(x, y, x + w, y + 1, color); g.fill(x, y + h - 1, x + w, y + h, color);
		g.fill(x, y, x + 1, y + h, color); g.fill(x + w - 1, y, x + w, y + h, color);
	}
	private static String hora(long ms) { return HORA.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())); }

	@Override
	void dibujar(GuiGraphics g, TabletDatos d, int x, int y, int cw, int ch, int mx, int my) {
		Font f = Minecraft.getInstance().font;
		TabletDatos.Equipo eq = d.equipo;
		if (eq == null) return;
		// titulo: "Equipo <nombre>" con el nombre de su color
		g.pose().pushMatrix(); g.pose().translate(x, y); g.pose().scale(1.25f, 1.25f);
		g.drawString(f, "Equipo ", 0, 0, Tablet.TEXTO, false);
		g.drawString(f, eq.nombre, f.width("Equipo "), 0, eq.color, false);
		g.pose().popMatrix();
		// pestañitas arriba a la derecha
		int fx = x + cw;
		int nuevos = eq.mensajesNuevos();
		String tn = "Notas" + (eq.notas.stream().anyMatch(n -> n.estado().equals("activa")) ? " (" + eq.notas.stream().filter(n -> n.estado().equals("activa")).count() + ")" : "");
		String tc = "Chat" + (nuevos > 0 && this.verNotas ? " (" + nuevos + ")" : "");
		fx -= f.width(tn) + 10; t.boton(g, tn, fx, y - 1, this.verNotas, mx, my, () -> { this.verNotas = true; this.mensaje.foco(false); });
		fx -= f.width(tc) + 13; t.boton(g, tc, fx, y - 1, !this.verNotas, mx, my, () -> { this.verNotas = false; this.escribiendoNota = false; sinFoco(); });

		miembros(g, d, eq, f, x, y, mx, my);

		this.bx = x + 124; this.by = y + 17; this.bw = cw - 124; this.bh = ch - 17;
		if (this.verNotas) notas(g, eq, f, mx, my); else chat(g, eq, f, mx, my);
	}

	// ================= miembros =================
	private void miembros(GuiGraphics g, TabletDatos d, TabletDatos.Equipo eq, Font f, int x, int y, int mx, int my) {
		int lw = 118;
		g.drawString(f, eq.miembros.size() + (eq.miembros.size() == 1 ? " miembro" : " miembros"), x, y + 17, Tablet.GRIS_TEXTO, false);
		if (eq.soyLider) g.drawString(f, "Eres el líder", x, y + 27, Tablet.AMARILLO, false);
		int yy = y + 40;
		for (TabletDatos.Miembro m : eq.miembros) {
			hundido(g, x, yy, lw, 16);
			int nx = x + 5;
			if (m.lider()) { g.drawString(f, "★", nx, yy + 4, Tablet.AMARILLO, true); nx += 10; }
			boolean puedeEchar = eq.soyLider && !m.lider();
			g.drawString(f, Tablet.cortar(f, m.nombre(), lw - (puedeEchar ? 44 : 30) - (m.lider() ? 10 : 0)), nx, yy + 4, m.conectado() ? BLANCO : Tablet.GRIS_TEXTO, true);
			g.fill(x + lw - 9, yy + 5, x + lw - 3, yy + 11, NEGRO);
			g.fill(x + lw - 8, yy + 6, x + lw - 4, yy + 10, m.conectado() ? Tablet.VERDE : SOMBRA);
			if (puedeEchar) {
				String clave = "echar:" + m.nombre();
				boolean c = pidiendo(clave);
				String te = c ? "¿Seguro?" : "✕";
				int ex = x + lw - 14 - f.width(te) - 6;
				t.boton(g, te, ex, yy + 1, c, mx, my, () -> {
					if (confirmado(clave)) { JsonObject o = new JsonObject(); o.addProperty("a", "equipo_echar"); o.addProperty("nombre", m.nombre()); mandar(o); }
				});
			}
			yy += 18;
		}
		yy += 4;
		if (eq.soyLider) {
			yy += 16;
			t.boton(g, "+ Invitar", x, yy - 16, false, mx, my, () -> {
				List<Selector.Opcion> ops = new ArrayList<>();
				for (String n : eq.invitables) ops.add(new Selector.Opcion(n, n, "", 0, "Conectados"));
				t.abrirSelector(new Selector("Invitar al equipo", ops, "", "No hay nadie conectado para invitar.", n -> { if (!n.isEmpty()) mandarNombre("equipo_invitar", n); }));
			});
		}
		boolean conf = pidiendo("salir");
		t.boton(g, conf ? "¿Seguro? Salir" : "Salir del equipo", x, yy, conf, mx, my, () -> { if (confirmado("salir")) mandar("equipo_salir", null, null); });
	}
	private static void mandarNombre(String a, String n) { mandar(a, "nombre", n); }

	// ================= chat =================
	private void chat(GuiGraphics g, TabletDatos.Equipo eq, Font f, int mx, int my) {
		g.fill(bx, by, bx + bw, by + bh, 0xFF000000);
		marco(g, bx, by, bw, bh, SLOT_OSCURO);
		int caja = 16;
		List<FormattedCharSequence> renglones = new ArrayList<>();
		for (TabletDatos.Mensaje m : eq.chat) {
			Component linea = Component.literal(hora(m.t()) + " ").withStyle(s -> s.withColor(0xA0A0A0))
				.append(Component.literal(m.quien()).withStyle(s -> s.withColor(TextColor.fromRgb(eq.color & 0xFFFFFF))))
				.append(Component.literal(": ").withStyle(s -> s.withColor(0xA0A0A0)))
				.append(Component.literal(m.texto()).withStyle(s -> s.withColor(0xFFFFFF)));
			renglones.addAll(f.split(linea, bw - 12));
			Vistos.marcar("m:" + eq.id + ":" + m.t());   // lo estas viendo: ya esta leido
		}
		int visibles = (bh - caja - 10) / 10;
		int maxScroll = Math.max(0, renglones.size() - visibles);
		this.scrollChat = Math.max(0, Math.min(maxScroll, this.scrollChat));
		int hasta = renglones.size() - this.scrollChat, desde = Math.max(0, hasta - visibles);
		int ry = by + bh - caja - 6 - (hasta - desde) * 10;
		if (eq.chat.isEmpty()) g.drawString(f, "Todavía no hay mensajes. Escribe algo abajo.", bx + 6, by + 6, 0xFF707070, false);
		for (int i = desde; i < hasta; i++) { g.drawString(f, renglones.get(i), bx + 6, ry, BLANCO, true); ry += 10; }
		if (this.scrollChat > 0) g.drawString(f, "▼ más nuevos", bx + bw - f.width("▼ más nuevos") - 6, by + bh - caja - 14, 0xFF909090, false);
		// la caja para escribir
		int cy = by + bh - caja - 2;
		g.fill(bx + 3, cy, bx + bw - 3, cy + caja - 1, 0xFF000000);
		marco(g, bx + 3, cy, bw - 6, caja - 1, this.mensaje.foco() ? t.acento() : 0xFFA0A0A0);
		this.mensaje.dibujar(g, bx + 7, cy + 3, bw - 14, 9, BLANCO);
		if (this.mensaje.plano().isEmpty() && !this.mensaje.foco()) g.drawString(f, "Escribe al equipo... (máx. 120 letras)", bx + 7, cy + 4, 0xFF707070, false);
	}
	private void enviarMensaje() {
		String s = this.mensaje.plano().trim();
		if (s.isEmpty()) return;
		mandar("equipo_chat", "texto", s);
		this.mensaje.ponerPlano("");
		this.scrollChat = 0;
	}

	// ================= notas =================
	private void notas(GuiGraphics g, TabletDatos.Equipo eq, Font f, int mx, int my) {
		int y0 = by;
		if (this.escribiendoNota) {
			// formulario: titulo + texto + guardar/cancelar
			g.fill(bx, by, bx + bw, by + 78, 0xFF6B5A3A);
			g.fill(bx + 1, by + 1, bx + bw - 1, by + 77, 0xFFE9E1C4);
			g.drawString(f, "Título", bx + 6, by + 5, 0xFF6B5A3A, false);
			g.fill(bx + 5, by + 15, bx + bw - 5, by + 29, 0xFFFFFFFF);
			marco(g, bx + 5, by + 15, bw - 10, 14, this.notaTitulo.foco() ? 0xFF9E862F : 0xFF8A7A55);
			this.notaTitulo.dibujar(g, bx + 8, by + 18, bw - 16, 9, 0xFF3B2F1C);
			g.drawString(f, "Nota", bx + 6, by + 33, 0xFF6B5A3A, false);
			g.fill(bx + 5, by + 43, bx + bw - 5, by + 74, 0xFFFFFFFF);
			marco(g, bx + 5, by + 43, bw - 10, 31, this.notaTexto.foco() ? 0xFF9E862F : 0xFF8A7A55);
			this.notaTexto.dibujar(g, bx + 8, by + 46, bw - 16, 27, 0xFF3B2F1C);
			int by2 = by + 82;
			int w1 = t.boton(g, "Guardar nota", bx, by2, false, mx, my, this::guardarNota);
			t.boton(g, "Cancelar", bx + w1 + 4, by2, false, mx, my, () -> { this.escribiendoNota = false; sinFoco(); });
			return;
		}
		int bw1 = t.boton(g, "+ Nueva nota", bx, by, false, mx, my, () -> {
			this.escribiendoNota = true; this.notaTitulo.ponerPlano(""); this.notaTexto.ponerPlano(""); this.notaTitulo.foco(true); this.notaTexto.foco(false);
		});
		if (eq.notas.isEmpty()) g.drawString(f, "Todavía no hay notas. Usá \"+ Nueva nota\" para dejar algo al equipo.", bx, by + 20, Tablet.GRIS_TEXTO, false);
		int lt = by + 17, lh = bh - 17;
		// primero las activas, despues las hechas y las que ya no sirven (el orden de adentro es el mas nuevo arriba)
		List<TabletDatos.Nota> orden = new ArrayList<>();
		for (TabletDatos.Nota n : eq.notas) if (n.estado().equals("activa")) orden.add(n);
		for (TabletDatos.Nota n : eq.notas) if (!n.estado().equals("activa")) orden.add(n);
		int total = 0;
		for (TabletDatos.Nota n : orden) total += altoNota(f, n) + 4;
		this.scrollNotas = Math.max(0, Math.min(Math.max(0, total - lh), this.scrollNotas));
		g.enableScissor(bx, lt, bx + bw, lt + lh);
		int ny = lt - this.scrollNotas;
		for (TabletDatos.Nota n : orden) {
			int alto = altoNota(f, n);
			if (ny + alto > lt - 2 && ny < lt + lh) tarjeta(g, f, n, ny, alto, mx, my, lt, lh);
			ny += alto + 4;
		}
		g.disableScissor();
	}
	private int altoNota(Font f, TabletDatos.Nota n) {
		return 14 + f.split(Component.literal(n.texto()), bw - 14).size() * 10 + 12 + 16;
	}
	private void tarjeta(GuiGraphics g, Font f, TabletDatos.Nota n, int y, int alto, int mx, int my, int lt, int lh) {
		boolean activa = n.estado().equals("activa");
		g.fill(bx, y, bx + bw, y + alto, activa ? 0xFF6B5A3A : 0xFF4A4636);
		g.fill(bx + 1, y + 1, bx + bw - 1, y + alto - 1, activa ? 0xFFE9E1C4 : 0xFFB9B4A0);
		int tinta = activa ? 0xFF3B2F1C : 0xFF6C6858;
		Component tit = Component.literal(n.titulo()).withStyle(s -> s.withBold(true).withStrikethrough(!activa));
		g.drawString(f, tit, bx + 6, y + 5, tinta, false);
		if (!activa) {
			String et = n.estado().equals("hecha") ? "HECHA" : "YA NO SIRVE";
			g.drawString(f, et, bx + bw - f.width(et) - 6, y + 5, n.estado().equals("hecha") ? 0xFF2F7A3A : 0xFF8A4A3A, false);
		}
		int ry = y + 17;
		for (FormattedCharSequence r : f.split(Component.literal(n.texto()), bw - 14)) { g.drawString(f, r, bx + 6, ry, tinta, false); ry += 10; }
		g.drawString(f, n.autor() + " · día " + n.dia() + " · " + hora(n.t()), bx + 6, ry + 2, 0xFF6B5A3A, false);
		int by2 = ry + 14 - 2;
		boolean visible = y + alto > lt && y < lt + lh;
		if (!visible) return;
		int bxx = bx + 6;
		if (activa) {
			bxx += botonNota(g, f, "Hecha", bxx, by2, mx, my, lt, lh, () -> mandarEstado(n, "hecha")) + 4;
			botonNota(g, f, "Ya no sirve", bxx, by2, mx, my, lt, lh, () -> mandarEstado(n, "obsoleta"));
		} else {
			botonNota(g, f, "Volver a activar", bxx, by2, mx, my, lt, lh, () -> mandarEstado(n, "activa"));
		}
	}
	/** Boton chiquito de una nota (solo reacciona si esta dentro de la zona visible). */
	private int botonNota(GuiGraphics g, Font f, String txt, int x, int y, int mx, int my, int lt, int lh, Runnable r) {
		int w = f.width(txt) + 8, h = 11;
		boolean dentro = my >= lt && my < lt + lh;
		boolean encima = dentro && mx >= x && my >= y && mx < x + w && my < y + h;
		g.fill(x, y, x + w, y + h, encima ? 0xFF3B2F1C : 0xFF8A7A55);
		g.fill(x + 1, y + 1, x + w - 1, y + h - 1, encima ? 0xFF5A4A2C : 0xFFD8CFAE);
		g.drawString(f, txt, x + 4, y + 2, encima ? 0xFFFFFFFF : 0xFF3B2F1C, false);
		if (dentro && y + h > lt && y < lt + lh) t.zona(x, Math.max(y, lt), w, Math.min(h, lt + lh - Math.max(y, lt)), r);
		return w;
	}
	private static void mandarEstado(TabletDatos.Nota n, String estado) {
		JsonObject o = new JsonObject(); o.addProperty("a", "equipo_nota_estado"); o.addProperty("id", n.id()); o.addProperty("estado", estado);
		mandar(o);
	}
	private void guardarNota() {
		String ti = this.notaTitulo.plano().trim(), tx = this.notaTexto.plano().trim();
		if (ti.isEmpty() && tx.isEmpty()) return;
		JsonObject o = new JsonObject(); o.addProperty("a", "equipo_nota"); o.addProperty("titulo", ti); o.addProperty("texto", tx);
		mandar(o);
		this.escribiendoNota = false; sinFoco();
		this.scrollNotas = 0;
	}

	// ================= teclado y mouse =================
	private EditorTexto[] cajas() { return this.verNotas ? (this.escribiendoNota ? new EditorTexto[] { this.notaTitulo, this.notaTexto } : new EditorTexto[0]) : new EditorTexto[] { this.mensaje }; }
	@Override boolean escribiendo() { for (EditorTexto e : cajas()) if (e.foco()) return true; return false; }
	@Override boolean tecla(KeyEvent ev) {
		if (ev.isEscape() && escribiendo()) { sinFoco(); return true; }
		if (escribiendo() && (ev.key() == 257 || ev.key() == 335)) {   // Enter
			if (!this.verNotas) { enviarMensaje(); return true; }
			if (this.notaTitulo.foco()) { this.notaTitulo.foco(false); this.notaTexto.foco(true); return true; }
			// en el texto de la nota, Enter es un renglon nuevo (lo maneja el editor)
		}
		boolean r = false; for (EditorTexto e : cajas()) r |= e.tecla(ev);
		return r || escribiendo();   // mientras escribis, ninguna tecla cierra la tablet
	}
	@Override boolean caracter(CharacterEvent ev) { boolean r = false; for (EditorTexto e : cajas()) r |= e.caracter(ev); return r; }
	@Override boolean clic(double mx, double my, boolean shift) {
		EditorTexto[] cs = cajas();
		for (EditorTexto e : cs) if (e.adentro(mx, my)) { for (EditorTexto o : cs) if (o != e) o.foco(false); e.clic(mx, my, shift); return true; }
		return false;
	}
	@Override void sinFoco() { this.mensaje.foco(false); this.notaTitulo.foco(false); this.notaTexto.foco(false); }
	@Override void arrastrar(double mx, double my) { for (EditorTexto e : cajas()) e.arrastrar(mx, my); }
	@Override void soltar() { for (EditorTexto e : cajas()) e.soltar(); }
	@Override boolean ruedita(double mx, double my, double dy) {
		if (mx < bx || mx >= bx + bw || my < by || my >= by + bh) return false;
		if (this.verNotas) this.scrollNotas = Math.max(0, this.scrollNotas - (int) (dy * 14));
		else this.scrollChat = Math.max(0, this.scrollChat + (int) Math.signum(dy) * 3);
		return true;
	}
}

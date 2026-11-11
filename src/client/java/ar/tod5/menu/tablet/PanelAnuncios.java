package ar.tod5.menu.tablet;

import ar.tod5.menu.Ruleta;
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
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;

/**
 * Modo admin > Anuncios: a la izquierda los anuncios guardados (por dia, General y Momento truco),
 * a la derecha el editor y abajo como sale en el chat.
 * Todo se guarda en el server (lo ven todos los editores).
 */
final class PanelAnuncios {
	/** Tipos en el orden de la ruleta (el numero es el gajo). */
	static final String[] TIPOS = { "diaria", "aviso", "dungeon", "sancion", "cuy", "truco", "cambio", "mob" };
	static final String[] TITULOS = { "Misión diaria", "Aviso", "Nueva dungeon", "Sanción", "Momento cuy", "Momento truco", "Cambio de dificultad", "Nuevo mob" };
	private static final Identifier PIEDRAS = Identifier.fromNamespaceAndPath("tod5", "textures/font/piedras.png");
	private static final String[] SIMBOLOS = { "☠", "★", "✔", "✖", "❤", "⚠", "➜", "⚔", "✦", "●" };
	private static final int[] COLORES_TEXTO = { -1, 0xFFFFFF, 0xAAAAAA, 0x55FF55, 0x55FFFF, 0x4678FF, 0xB45AFF, 0xFF78C8, 0xFF3C3C, 0xFF9600, 0xFFE63C };

	private final Tablet t;
	private String id;                 // el que se esta editando (null = ninguno)
	private int tipo = 1, grupo = 1;
	private boolean ruleta = true, sucio, verSimbolos;
	private final EditorTexto titulo = new EditorTexto(false, 40);
	private final EditorTexto texto = new EditorTexto(true, 1000);
	private String confirmar;          // "enviar", "retirar" o "borrar": esperando el segundo clic
	private long confirmarHasta;
	private String aviso; private long avisoHasta, graciaHasta; private boolean avisoMal;
	private String retirando; private long retirandoHasta;   // esperando que el server confirme el retiro
	private int scroll;
	private int lx, ly, lw, lh;        // la lista (para la ruedita)
	private int simX, simY, simW, simH;  // el cartelito de simbolos (encima de todo)

	PanelAnuncios(Tablet t) { this.t = t; }

	static int gajo(String tipo) { for (int k = 0; k < TIPOS.length; k++) if (TIPOS[k].equals(tipo)) return k; return 1; }
	static int fuerte(int gajo) { return 0xFF000000 | Ruleta.COLORES[gajo]; }
	static int claro(int gajo) {
		int c = Ruleta.COLORES[gajo], r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
		r += (int) ((255 - r) * 0.45); g += (int) ((255 - g) * 0.45); b += (int) ((255 - b) * 0.45);
		return 0xFF000000 | (r << 16) | (g << 8) | b;
	}
	private static String nombreGrupo(int g) { return g == -1 ? "Momento truco" : g == 0 ? "General" : "Día " + g; }

	// ---------- abrir / guardar ----------
	private void abrir(TabletDatos.Anuncio a) {
		guardarSiHaceFalta();
		this.id = a.id(); this.tipo = gajo(a.tipo()); this.grupo = a.grupo(); this.ruleta = a.ruleta();
		this.titulo.ponerPlano(a.titulo()); this.texto.poner(a.texto());
		this.titulo.foco(false); this.texto.foco(false);
		this.sucio = false; this.confirmar = null;
	}
	private void nuevo(TabletDatos d) {
		guardarSiHaceFalta();
		this.id = "a" + Long.toString(System.currentTimeMillis(), 36) + (int) (Math.random() * 1000);
		this.tipo = 1; this.grupo = Math.max(1, d.hoy); this.ruleta = true;
		this.titulo.ponerPlano(TITULOS[1]); this.texto.poner(List.of());
		this.texto.foco(true); this.titulo.foco(false);
		this.sucio = true;
		mandar("anuncio_guardar");
	}
	private JsonObject anuncioJson() {
		JsonObject a = new JsonObject();
		a.addProperty("id", this.id);
		a.addProperty("tipo", TIPOS[this.tipo]);
		a.addProperty("titulo", this.titulo.plano().trim());
		a.addProperty("grupo", this.grupo);
		a.addProperty("ruleta", this.ruleta);
		a.add("texto", EditorTexto.json(this.texto.letras()));
		JsonArray ls = new JsonArray();
		for (List<EditorTexto.Letra> l : EditorTexto.renglones(this.texto.letras())) ls.add(EditorTexto.json(l));
		a.add("lineas", ls);
		return a;
	}
	private void mandar(String accion) {
		if (this.id == null) return;
		JsonObject o = new JsonObject();
		o.addProperty("a", accion);
		o.add("anuncio", anuncioJson());
		ar.tod5.menu.Conexion.accion(o.toString());
		this.sucio = false;
		this.graciaHasta = Util.getMillis() + 4000;   // hasta que el server lo devuelva en la lista
	}
	void guardarSiHaceFalta() { if (this.sucio && this.id != null) mandar("anuncio_guardar"); }
	private void avisar(String s) { this.aviso = s; this.avisoHasta = Util.getMillis() + 2500; this.avisoMal = false; }
	private void avisarMal(String s) { this.aviso = s; this.avisoHasta = Util.getMillis() + 6000; this.avisoMal = true; }

	// ---------- dibujar ----------
	void dibujar(GuiGraphics g, TabletDatos d, int x, int y, int cw, int ch, int mx, int my) {
		Font f = Minecraft.getInstance().font;
		if (this.confirmar != null && Util.getMillis() > this.confirmarHasta) this.confirmar = null;
		// si el que estaba abierto ya no existe (lo borro otro editor), se cierra
		if (this.id != null && !this.sucio && d.anuncios.stream().noneMatch(a -> a.id().equals(this.id)) && Util.getMillis() > this.graciaHasta) this.id = null;
		// retirar: recien cuando el server lo devuelve como borrador se dice que se retiro
		if (this.retirando != null) {
			final String rid = this.retirando;
			if (d.anuncios.stream().noneMatch(a -> a.id().equals(rid) && a.enviado())) { avisar("Volvió a borrador."); this.retirando = null; }
			else if (Util.getMillis() > this.retirandoHasta) { avisarMal(d.editor ? "No se retiró: el server no respondió." : "No se retiró: no sos editor."); this.retirando = null; }
		}

		if (!d.conAnuncios) {   // el plugin del server es viejo: no puede guardar ni mandar anuncios
			t.titulo(g, "Anuncios", x, y);
			g.drawString(f, "El plugin del server no tiene anuncios todavía.", x, y + 22, Tablet.AMARILLO_ADMIN, false);
			g.drawString(f, "Actualizalo: tod5-plugin-servidor 0.16 o más nuevo,", x, y + 34, Tablet.TEXTO, false);
			g.drawString(f, "en la carpeta plugins del server, y reinicialo.", x, y + 44, Tablet.TEXTO, false);
			return;
		}
		// ===== lista =====
		this.lw = 102; this.lx = x; this.ly = y + 16; this.lh = ch - 16;   // angosta: el editor necesita el ancho de un renglon del chat
		t.titulo(g, "Anuncios", x, y);
		t.boton(g, "+Nuevo", x + lw - f.width("+Nuevo") - 10, y - 1, false, mx, my, () -> nuevo(d));
		List<TabletDatos.Anuncio> orden = new ArrayList<>(d.anuncios);
		orden.sort(Comparator.comparingInt((TabletDatos.Anuncio a) -> a.grupo() <= 0 ? 10000 - a.grupo() : a.grupo()));   // dias, General, Momento truco
		g.enableScissor(lx, ly, lx + lw, ly + lh);
		int yy = ly - this.scroll; Integer grupoAnt = null;
		for (TabletDatos.Anuncio a : orden) {
			if (grupoAnt == null || grupoAnt != a.grupo()) { g.drawString(f, nombreGrupo(a.grupo()), lx + 1, yy + 2, Tablet.GRIS_TEXTO, false); yy += 12; grupoAnt = a.grupo(); }
			boolean sel = a.id().equals(this.id);
			int k = gajo(a.tipo());
			int alto = 22;
			g.fill(lx, yy, lx + lw, yy + alto, sel ? t.acento() : Tablet.LINEA);
			g.fill(lx + 1, yy + 1, lx + lw - 1, yy + alto - 1, sel ? 0xFF2A2610 : 0xFF141B18);
			g.fill(lx + 3, yy + 3, lx + 5, yy + alto - 3, fuerte(k));
			String tit = a.titulo().isBlank() ? TITULOS[k] : a.titulo();
			String corto = f.plainSubstrByWidth(tit, lw - 14);
			if (!corto.equals(tit)) corto = f.plainSubstrByWidth(tit, lw - 14 - f.width("…")) + "…";
			g.drawString(f, corto, lx + 8, yy + 3, fuerte(k), false);
			if (!corto.equals(tit) && mx >= lx && my >= Math.max(yy, ly) && mx < lx + lw && my < Math.min(yy + alto, ly + lh)) g.setTooltipForNextFrame(Component.literal(tit), mx, my);
			String est = a.enviado() ? "Enviado" : "Borrador";
			g.drawString(f, est, lx + 8, yy + 12, a.enviado() ? 0xFF6FCF7F : Tablet.GRIS_TEXTO, false);
			if (yy + alto > ly && yy < ly + lh) t.zona(lx, Math.max(yy, ly), lw, Math.min(yy + alto, ly + lh) - Math.max(yy, ly), () -> abrir(a));
			yy += alto + 3;
		}
		g.disableScissor();
		int total = yy + this.scroll - ly;
		this.scroll = Math.max(0, Math.min(this.scroll, total - lh));
		if (d.anuncios.isEmpty()) { g.drawString(f, "Todavía no hay", lx + 1, ly + 4, Tablet.GRIS_TEXTO, false); g.drawString(f, "anuncios.", lx + 1, ly + 14, Tablet.GRIS_TEXTO, false); }
		g.fill(x + lw + 6, y - 2, x + lw + 7, y + ch, Tablet.LINEA);

		// ===== editor =====
		int ex = x + lw + 14, ew = cw - lw - 14;
		if (this.id == null) {
			g.drawString(f, "Elegí un anuncio de la lista", ex, y + 20, Tablet.GRIS_TEXTO, false);
			g.drawString(f, "o creá uno con \"+ Nuevo\".", ex, y + 31, Tablet.GRIS_TEXTO, false);
			return;
		}
		int ey = y;
		// tipo: los 8 colores de la ruleta (el titulo cambia solo si no lo tocaste)
		g.drawString(f, "Tipo", ex, ey + 2, Tablet.GRIS_TEXTO, false);
		int cx = ex + 26;
		for (int k = 0; k < 8; k++) {
			final int kk = k;
			boolean sel = k == this.tipo;
			g.fill(cx, ey, cx + 13, ey + 13, sel ? 0xFFFFFFFF : 0xFF000000);
			g.fill(cx + 1, ey + 1, cx + 12, ey + 12, sel ? 0xFF000000 : 0xFF000000);
			g.fill(cx + 2, ey + 2, cx + 11, ey + 11, fuerte(k));
			t.zona(cx, ey, 13, 13, () -> {
				String act = this.titulo.plano().trim();
				if (act.isEmpty() || act.equals(TITULOS[this.tipo])) this.titulo.ponerPlano(TITULOS[kk]);
				this.tipo = kk; this.sucio = true;
			});
			if (mx >= cx && my >= ey && mx < cx + 13 && my < ey + 13) g.setTooltipForNextFrame(Component.literal(TITULOS[k]), mx, my);
			cx += 15;
		}
		// duplicar y borrar, arriba a la derecha
		boolean confBor = "borrar".equals(this.confirmar);
		String tb = confBor ? "¿Borrar?" : "Borrar";
		int bxx = ex + ew - f.width(tb) - 10;
		t.boton(g, tb, bxx, ey, confBor, mx, my, () -> {
			if (confBor) {
				JsonObject o = new JsonObject(); o.addProperty("a", "anuncio_borrar");
				JsonArray ids = new JsonArray(); ids.add(this.id); o.add("ids", ids);
				ar.tod5.menu.Conexion.accion(o.toString());
				this.id = null; this.sucio = false; this.confirmar = null;
			} else { this.confirmar = "borrar"; this.confirmarHasta = Util.getMillis() + 3000; }
		});
		bxx -= f.width("Duplicar") + 13;
		t.boton(g, "Duplicar", bxx, ey, false, mx, my, () -> {
			guardarSiHaceFalta();
			this.id = "a" + Long.toString(System.currentTimeMillis(), 36) + (int) (Math.random() * 1000);
			mandar("anuncio_guardar"); avisar("Copia creada.");
		});
		if (this.id == null) return;
		ey += 17;
		// grupo: < Dia N / General / Momento truco >
		g.drawString(f, "Va en", ex, ey + 3, Tablet.GRIS_TEXTO, false);
		int gx = ex + 30;
		gx += t.boton(g, "<", gx, ey, false, mx, my, () -> { this.grupo = Math.max(-1, this.grupo - 1); this.sucio = true; }) + 2;
		String ng = nombreGrupo(this.grupo);
		g.drawString(f, ng, gx + 4, ey + 3, Tablet.TEXTO, false);
		gx += Math.max(f.width("Momento truco"), f.width(ng)) + 8;
		t.boton(g, ">", gx, ey, false, mx, my, () -> { this.grupo = Math.min(99, this.grupo + 1); this.sucio = true; });
		String rt = "Ruleta: " + (this.ruleta ? "sí" : "no");
		t.boton(g, rt, ex + ew - f.width(rt) - 10, ey, this.ruleta, mx, my, () -> { this.ruleta = !this.ruleta; this.sucio = true; });
		ey += 17;
		// titulo
		g.drawString(f, "Título", ex, ey + 3, Tablet.GRIS_TEXTO, false);
		int tx = ex + 30, tw = ew - 30;
		caja(g, tx, ey, tw, 14, this.titulo.foco());
		String antes = this.titulo.plano();
		this.titulo.dibujar(g, tx + 4, ey + 3, tw - 8, 9, fuerte(this.tipo));
		if (!antes.equals(this.titulo.plano())) this.sucio = true;
		ey += 19;
		// barra de estilos: B I U S, colores y simbolos
		int bx = ex;
		bx += boton(g, "B", Style.EMPTY.withBold(true), bx, ey, this.texto.negrita(), mx, my, () -> { this.texto.alternar(0); this.sucio = true; }) + 2;
		bx += boton(g, "I", Style.EMPTY.withItalic(true), bx, ey, this.texto.cursiva(), mx, my, () -> { this.texto.alternar(1); this.sucio = true; }) + 2;
		bx += boton(g, "U", Style.EMPTY.withUnderlined(true), bx, ey, this.texto.subrayado(), mx, my, () -> { this.texto.alternar(2); this.sucio = true; }) + 2;
		bx += boton(g, "S", Style.EMPTY.withStrikethrough(true), bx, ey, this.texto.tachado(), mx, my, () -> { this.texto.alternar(3); this.sucio = true; }) + 5;
		int colSel = this.texto.colorSeleccion();
		for (int c : COLORES_TEXTO) {
			int muestra = c < 0 ? claro(this.tipo) : 0xFF000000 | c;
			boolean sel = c == colSel;
			g.fill(bx, ey + 1, bx + 11, ey + 12, sel ? 0xFFFFFFFF : 0xFF000000);
			g.fill(bx + 1, ey + 2, bx + 10, ey + 11, muestra);
			if (c < 0) { g.fill(bx + 1, ey + 10, bx + 10, ey + 11, 0xFF000000); }
			final int cc = c;
			t.zona(bx, ey + 1, 11, 11, () -> { this.texto.pintar(cc); this.sucio = true; });
			if (mx >= bx && my >= ey && mx < bx + 11 && my < ey + 12) g.setTooltipForNextFrame(Component.literal(c < 0 ? "Color normal del anuncio" : "Color"), mx, my);
			bx += 12;
		}
		bx += 3;
		int estrellaX = bx, estrellaY = ey;
		bx += t.boton(g, "★", bx, ey, this.verSimbolos, mx, my, () -> this.verSimbolos = !this.verSimbolos);
		ey += 16;
		// el texto
		int alto = 64;
		caja(g, ex, ey, ew, alto, this.texto.foco());
		int antesN = this.texto.letras().size(); String antesT = this.texto.plano();
		int rs = this.texto.dibujar(g, ex + 4, ey + 4, ew - 8, alto - 6, claro(this.tipo));
		if (antesN != this.texto.letras().size() || !antesT.equals(this.texto.plano())) this.sucio = true;
		if (this.texto.letras().isEmpty() && !this.texto.foco()) g.drawString(f, "Escribí el anuncio acá...", ex + 4, ey + 4, 0xFF5A6560, false);
		if (rs > 6) g.drawString(f, "(más abajo)", ex + ew - f.width("(más abajo)") - 3, ey + alto - 10, Tablet.GRIS_TEXTO, false);
		ey += alto + 4;
		// como sale en el chat
		int by = y + ch - 14;
		int py = ey + 10, ph = by - 4 - py;
		g.drawString(f, "Así sale en el chat", ex, ey, Tablet.GRIS_TEXTO, false);
		g.fill(ex, py, ex + ew, py + ph, 0xFF000000);
		g.enableScissor(ex, py, ex + ew, py + ph);
		// igual que en el chat: renglon vacio arriba, todo con dos espacios de sangria
		int k = this.tipo, ry = py + 3 + 9, rx = ex + 2 + f.width("  ");
		g.blit(RenderPipelines.GUI_TEXTURED, PIEDRAS, rx, ry - 1, k * 6, 0, 5, 9, 5, 9, 48, 9, 0xFFFFFFFF);
		String tit = this.titulo.plano().trim().isEmpty() ? TITULOS[k] : this.titulo.plano().trim();
		g.drawString(f, Component.literal(tit + ":").withStyle(Style.EMPTY.withBold(true).withColor(fuerte(k) & 0xFFFFFF)).getVisualOrderText(), rx + 9, ry, fuerte(k), true);
		ry += 18;   // renglon vacio
		for (List<EditorTexto.Letra> l : EditorTexto.renglones(this.texto.letras())) {
			if (ry > py + ph) break;
			g.drawString(f, EditorTexto.componente(l, 0, l.size(), claro(k)).getVisualOrderText(), rx, ry, claro(k), true);
			ry += 9;
		}
		g.disableScissor();

		// botones: Guardar (resaltado si hay cambios sin guardar), Probar (solo a vos) y Enviar a todos (con confirmacion)
		int bbx = ex;
		bbx += t.boton(g, "Guardar", bbx, by, this.sucio, mx, my, () -> { mandar("anuncio_guardar"); avisar("Guardado."); }) + 3;
		bbx += t.boton(g, "Probar", bbx, by, false, mx, my, () -> { mandar("anuncio_probar"); t.onClose(); }) + 3;
		boolean yaEnviado = d.anuncios.stream().anyMatch(a -> a.id().equals(this.id) && a.enviado());
		if (yaEnviado) {
			// ya salio: se puede retirar (sale de la tablet de los jugadores sin avisar a nadie y vuelve a ser borrador)
			boolean confRet = "retirar".equals(this.confirmar);
			String tr = this.retirando != null ? "Retirando…" : confRet ? "¿Retirar? Clic de nuevo" : "Retirar";
			t.boton(g, tr, bbx, by, confRet, mx, my, () -> {
				if (this.retirando != null) return;
				if (!d.pluginAlMenos("0.16")) { avisarMal("Para retirar hace falta el plugin 0.16 en el server."); return; }
				if (confRet) {
					guardarSiHaceFalta();
					this.retirando = this.id; this.retirandoHasta = Util.getMillis() + 4000;
					JsonObject o = new JsonObject(); o.addProperty("a", "anuncio_retirar");
					JsonArray ids = new JsonArray(); ids.add(this.id); o.add("ids", ids);
					ar.tod5.menu.Conexion.accion(o.toString());
					this.confirmar = null;
				} else { this.confirmar = "retirar"; this.confirmarHasta = Util.getMillis() + 3000; }
			});
			if (mx >= bbx && my >= by && mx < bbx + f.width("Retirar") + 10 && my < by + 13 && !confRet)
				g.setTooltipForNextFrame(Component.literal("Lo saca de la tablet de todos, sin avisar. Vuelve a borrador."), mx, my);
		} else {
			boolean confEnv = "enviar".equals(this.confirmar);
			t.boton(g, confEnv ? "¿Seguro? Clic de nuevo" : "Enviar a todos", bbx, by, confEnv, mx, my, () -> {
				if (confEnv) { mandar("anuncio_enviar"); this.confirmar = null; t.onClose(); }
				else { this.confirmar = "enviar"; this.confirmarHasta = Util.getMillis() + 3000; }
			});
		}
		if (this.aviso != null && Util.getMillis() < this.avisoHasta) {
			if (this.avisoMal) {   // los errores son largos: van en una franja arriba de los botones
				g.fill(ex, by - 15, ex + ew, by - 3, 0xFF3A1010);
				g.drawString(f, f.plainSubstrByWidth(this.aviso, ew - 8), ex + 4, by - 13, 0xFFFF7070, false);
			} else g.drawString(f, this.aviso, ex + ew - f.width(this.aviso), by + 3, t.acento(), false);
		}

		// los simbolos: un cartelito que se abre abajo de la estrella, encima de todo (no tapa nada cuando esta cerrado)
		this.simW = 0;
		if (this.verSimbolos) {
			int cols = 5, cel = 16;
			this.simW = cols * cel + 6; this.simH = ((SIMBOLOS.length + cols - 1) / cols) * cel + 6;
			this.simX = Math.min(estrellaX, ex + ew - this.simW); this.simY = estrellaY + 15;
			g.nextStratum();
			g.fill(this.simX - 1, this.simY - 1, this.simX + this.simW + 1, this.simY + this.simH + 1, t.acento());
			g.fill(this.simX, this.simY, this.simX + this.simW, this.simY + this.simH, 0xFF0B100E);
			for (int q = 0; q < SIMBOLOS.length; q++) {
				String s = SIMBOLOS[q]; int qx = this.simX + 3 + (q % cols) * cel, qy = this.simY + 3 + (q / cols) * cel;
				boolean enc = mx >= qx && my >= qy && mx < qx + cel && my < qy + cel;
				g.fill(qx, qy, qx + cel - 1, qy + cel - 1, enc ? 0xFF2A3230 : 0xFF161D1A);
				g.drawString(f, s, qx + (cel - 1 - f.width(s)) / 2 + 1, qy + 4, 0xFFFFFFFF, false);
				t.zona(qx, qy, cel - 1, cel - 1, () -> { this.texto.foco(true); this.texto.escribir(s); this.sucio = true; });
			}
		}
	}
	private void caja(GuiGraphics g, int x, int y, int w, int h, boolean foco) {
		g.fill(x, y, x + w, y + h, foco ? t.acento() : 0xFF3A4840);
		g.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFF070B09);
	}
	/** Boton chiquito con la letra en su estilo (B en negrita, I en cursiva...). */
	private int boton(GuiGraphics g, String s, Style st, int x, int y, boolean on, int mx, int my, Runnable r) {
		Font f = Minecraft.getInstance().font;
		int w = 13, h = 13;
		boolean enc = mx >= x && my >= y && mx < x + w && my < y + h;
		g.fill(x, y, x + w, y + h, on ? t.acento() : enc ? 0xFF8A948E : 0xFF3A4840);
		g.fill(x + 1, y + 1, x + w - 1, y + h - 1, on ? 0xFF2A2610 : 0xFF1A211D);
		Component c = Component.literal(s).withStyle(st);
		g.drawString(f, c.getVisualOrderText(), x + (w - f.width(c)) / 2 + 1, y + 3, on ? t.acento() : Tablet.TEXTO, false);
		t.zona(x, y, w, h, r);
		return w;
	}

	// ---------- teclado y mouse ----------
	boolean escribiendo() { return this.titulo.foco() || this.texto.foco(); }
	boolean tecla(KeyEvent e) {
		if (e.isEscape() && escribiendo()) { this.titulo.foco(false); this.texto.foco(false); return true; }
		if (this.titulo.foco() && e.isCycleFocus()) { this.titulo.foco(false); this.texto.foco(true); return true; }
		boolean r = this.titulo.tecla(e) || this.texto.tecla(e);
		if (r) this.sucio = true;
		return r || escribiendo();   // mientras escribis, ninguna tecla cierra la tablet
	}
	boolean caracter(CharacterEvent e) {
		boolean r = this.titulo.caracter(e) || this.texto.caracter(e);
		if (r) this.sucio = true;
		return r;
	}
	/** Clic: si cae en un cuadro de texto, lo agarra ese; si no, se les va el foco. */
	boolean clic(double mx, double my, boolean shift) {
		if (this.id == null) return false;
		if (this.simW > 0 && mx >= this.simX && my >= this.simY && mx < this.simX + this.simW && my < this.simY + this.simH) return false;   // es un simbolo
		if (this.texto.adentro(mx, my)) { this.titulo.foco(false); this.texto.clic(mx, my, shift); return true; }
		if (this.titulo.adentro(mx, my)) { this.texto.foco(false); this.titulo.clic(mx, my, shift); return true; }
		return false;
	}
	void sinFoco() { this.titulo.foco(false); this.texto.foco(false); }
	void arrastrar(double mx, double my) { this.texto.arrastrar(mx, my); this.titulo.arrastrar(mx, my); }
	void soltar() { this.texto.soltar(); this.titulo.soltar(); }
	boolean ruedita(double mx, double my, double dy) {
		if (mx >= lx && my >= ly && mx < lx + lw && my < ly + lh) { this.scroll = Math.max(0, this.scroll - (int) (dy * 12)); return true; }
		return false;
	}
}

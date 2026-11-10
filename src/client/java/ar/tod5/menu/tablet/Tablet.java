package ar.tod5.menu.tablet;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.util.Util;

/**
 * La tablet: se abre con la tecla (J por defecto) cuando no tenes ningun inventario abierto.
 * Estilo: marco de metal oscuro con luces verdes, pantalla oscura, pestañas como iconos grandes a la izquierda.
 * Secciones: Inicio, Novedades, Misiones, Crafteos y Equipo (solo si tenes equipo).
 * Por ahora con datos de ejemplo (TabletDatos); despues los manda el server.
 */
public class Tablet extends Screen {
	// colores: marco de metal oscuro (el del logo) con luces verdes; pantalla oscura adentro
	private static final int NEGRO = 0xFF000000, BLANCO = 0xFFFFFFFF;
	private static final int PANTALLA = 0xFF0B100E, LINEA = 0xFF26302B;
	private static final int SLOT = 0xFF19201C, SLOT_OSCURO = 0xFF050706, SLOT_CLARO = 0xFF34403A, SOMBRA = 0xFF3A443F;
	private static final int TEXTO = 0xFFD9E2DC, VERDE = 0xFF55FF55, AMARILLO = 0xFFFFE066, GRIS_TEXTO = 0xFF7D8A83;
	private static final int M_BORDE = 0xFF0A0B0C, M_LUZ = 0xFF8D949C, M_CLARO = 0xFF5E646B, M_BASE = 0xFF3C4147, M_OSCURO = 0xFF24272B;

	enum Seccion { INICIO("Inicio", Items.COMPASS), NOVEDADES("Novedades", Items.WRITABLE_BOOK), MISIONES("Misiones", Items.IRON_SWORD),
		CRAFTEOS("Crafteos", Items.CRAFTING_TABLE), EQUIPO("Equipo", Items.SHIELD);
		final String nombre; final ItemStack icono;
		Seccion(String n, net.minecraft.world.item.Item i) { nombre = n; icono = new ItemStack(i); } }

	private static Seccion ultima = Seccion.INICIO;   // al reabrir, vuelve a donde estabas

	private final TabletDatos datos = TabletDatos.ejemplo();
	private Seccion seccion = ultima;
	private int dia = datos.hoy;
	private String filtro = "todo";
	private String categoria = "todos";
	private int itemElegido = 0;
	private boolean verNotas = false;
	private final Set<String> reclamadas = new HashSet<>();

	// zonas para hacer clic (se arman cada cuadro al dibujar)
	private record Zona(int x, int y, int w, int h, Runnable accion) {
		boolean adentro(double mx, double my) { return mx >= x && my >= y && mx < x + w && my < y + h; }
	}
	private final List<Zona> zonas = new ArrayList<>();

	private int x0, y0, w, h;   // la tablet en pantalla

	public Tablet() { super(Component.literal("Tablet")); }

	@Override public boolean isPauseScreen() { return false; }

	@Override
	public void added() { super.added(); sonido("tablet_abrir"); }

	@Override
	public void removed() { ultima = this.seccion; sonido("tablet_cerrar"); super.removed(); }

	/** Sonidos propios de la tablet (abrir, cerrar, tocar). */
	private void sonido(String nombre) {
		this.minecraft.getSoundManager().play(new SimpleSoundInstance(net.minecraft.resources.Identifier.fromNamespaceAndPath("tod5", nombre),
			net.minecraft.sounds.SoundSource.MASTER, 0.8f, 1f, net.minecraft.util.RandomSource.create(), false, 0,
			net.minecraft.client.resources.sounds.SoundInstance.Attenuation.NONE, 0, 0, 0, true));
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (TeclaTablet.TECLA.matches(event)) { this.onClose(); return true; }   // la misma tecla la cierra
		return super.keyPressed(event);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		for (Zona z : List.copyOf(this.zonas)) {
			if (z.adentro(event.x(), event.y())) {
				sonido("tablet_tocar");
				z.accion().run();
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	// ---------- piezas ----------
	private static void relieve(GuiGraphicsExtractor g, int x, int y, int w, int h, int fondo, int claro, int oscuro) {
		g.fill(x, y, x + w, y + h, NEGRO);
		g.fill(x + 1, y + 1, x + w - 1, y + h - 1, fondo);
		g.fill(x + 1, y + 1, x + w - 1, y + 2, claro);
		g.fill(x + 1, y + 1, x + 2, y + h - 1, claro);
		g.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, oscuro);
		g.fill(x + w - 2, y + 1, x + w - 1, y + h - 1, oscuro);
	}
	/** Fila oscura con borde fino: para las tarjetas con texto. */
	private static void tarjeta(GuiGraphicsExtractor g, int x, int y, int w, int h) {
		g.fill(x, y, x + w, y + h, LINEA);
		g.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFF141B18);
		g.fill(x + 1, y + 1, x + w - 1, y + 2, 0xFF1D2622);
	}
	/** Casilla hundida (como las del inventario, pero oscura). */
	private static void hundido(GuiGraphicsExtractor g, int x, int y, int w, int h) {
		g.fill(x, y, x + w, y + h, SLOT);
		g.fill(x, y, x + w, y + 1, SLOT_OSCURO);
		g.fill(x, y, x + 1, y + h, SLOT_OSCURO);
		g.fill(x, y + h - 1, x + w, y + h, SLOT_CLARO);
		g.fill(x + w - 1, y, x + w, y + h, SLOT_CLARO);
	}
	/** Boton oscuro; borde verde si esta elegido o con el mouse encima. Devuelve su ancho. */
	private int boton(GuiGraphicsExtractor g, String t, int x, int y, boolean elegido, int mx, int my, Runnable accion) {
		int bw = this.font.width(t) + 10, bh = 13;
		boolean encima = mx >= x && my >= y && mx < x + bw && my < y + bh;
		g.fill(x, y, x + bw, y + bh, elegido ? 0xFF2F9E46 : encima ? 0xFF55FF55 : 0xFF3A4840);
		g.fill(x + 1, y + 1, x + bw - 1, y + bh - 1, elegido ? 0xFF143A1F : encima ? 0xFF1E2D24 : 0xFF1A211D);
		g.fill(x + 1, y + 1, x + bw - 1, y + 2, elegido ? 0xFF1F5A2E : 0xFF27312C);
		g.text(this.font, t, x + 5, y + 3, elegido || encima ? VERDE : TEXTO, true);
		this.zonas.add(new Zona(x, y, bw, bh, accion));
		return bw;
	}
	private void numerito(GuiGraphicsExtractor g, int n, int x, int y) {
		String s = String.valueOf(n);
		int bw = Math.max(10, this.font.width(s) + 5);
		int bh = 11;
		g.fill(x, y - 1, x + bw, y - 1 + bh, NEGRO);
		g.fill(x + 1, y, x + bw - 1, y - 2 + bh, VERDE);
		g.text(this.font, s, x + (bw - this.font.width(s)) / 2 + 1, y + 1, 0xFF0B2A0B, false);
	}

	// ---------- pantalla ----------
	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float a) {
		this.zonas.clear();
		if (this.seccion == Seccion.EQUIPO && this.datos.equipo == null) this.seccion = Seccion.INICIO;
		this.w = Math.min(470, this.width - 16);
		this.h = Math.min(290, this.height - 24);
		this.x0 = (this.width - this.w) / 2;
		this.y0 = (this.height - this.h) / 2 + 2;   // +2: lugar para la muesca de arriba

		float t = (Util.getMillis() % 100000L) / 1000f;
		marco(g, t);

		// la pantalla
		int sx = x0 + BORDE, sy = y0 + BORDE_ARRIBA, sw = w - 2 * BORDE, sh = h - BORDE - BORDE_ARRIBA;
		g.fill(sx - 1, sy - 1, sx + sw + 1, sy + sh + 1, NEGRO);
		g.fill(sx, sy, sx + sw, sy + sh, PANTALLA);

		// pestañas: iconos grandes a la izquierda
		int tx = sx + 5, ty = sy + 6, tam = 26;
		Seccion encima = null;
		for (Seccion s : Seccion.values()) {
			if (s == Seccion.EQUIPO && datos.equipo == null) continue;   // sin equipo, no aparece
			boolean act = s == this.seccion, sobre = mx >= tx && my >= ty && mx < tx + tam && my < ty + tam;
			if (sobre) encima = s;
			relieve(g, tx, ty, tam, tam, act ? 0xFF16301F : sobre ? 0xFF2A3230 : 0xFF1E2422, act ? 0xFF55FF55 : M_CLARO, act ? 0xFF1F7A33 : M_OSCURO);
			if (act) g.fill(tx - 3, ty + 6, tx - 1, ty + tam - 6, pulso(t, 0.6f, 1f));
			g.pose().pushMatrix(); g.pose().translate(tx + 3, ty + 3); g.pose().scale(1.25f, 1.25f);
			g.item(s.icono, 0, 0);
			g.pose().popMatrix();
			int badge = switch (s) {
				case NOVEDADES -> datos.novedadesNuevas();
				case MISIONES -> datos.diariaParaReclamar(reclamadas);
				case CRAFTEOS -> datos.crafteosNuevos();
				case EQUIPO -> datos.equipo.mensajesNuevos;
				default -> 0;
			};
			if (badge > 0) numerito(g, badge, tx + tam - 7, ty - 2);
			final Seccion destino = s;
			this.zonas.add(new Zona(tx, ty, tam, tam, () -> irA(destino)));
			ty += tam + 6;
		}
		int div = sx + tam + 10;
		g.fill(div, sy + 4, div + 1, sy + sh - 4, LINEA);

		int cx = div + 8, cy = sy + 8, cw = sx + sw - 8 - cx, ch = sh - 16;
		switch (this.seccion) {
			case INICIO -> inicio(g, cx, cy, cw, mx, my);
			case NOVEDADES -> novedades(g, cx, cy, cw, ch, mx, my);
			case MISIONES -> misiones(g, cx, cy, cw, mx, my);
			case CRAFTEOS -> crafteos(g, cx, cy, cw, mx, my);
			case EQUIPO -> equipo(g, cx, cy, cw, ch, mx, my);
		}
		super.extractRenderState(g, mx, my, a);
		if (encima != null) g.setTooltipForNextFrame(Component.literal(encima.nombre), mx, my);
	}

	private static final int BORDE = 9, BORDE_ARRIBA = 14;

	/** Verde que late despacito (para las luces del marco). */
	private static int pulso(float t, float min, float max) {
		float k = min + (max - min) * (0.5f + 0.5f * (float) Math.sin(t * 2.2));
		int gr = (int) (0xFF * k), r = (int) (0x55 * k), b = (int) (0x55 * k);
		return 0xFF000000 | (r << 16) | (gr << 8) | b;
	}

	/** El marco de metal: bisel, esquinas cortadas, tornillos, muesca arriba con el dia y luces verdes. */
	private void marco(GuiGraphicsExtractor g, float t) {
		int x = x0, y = y0, x2 = x0 + w, y2 = y0 + h, c = 4;   // c = cuanto se cortan las esquinas
		// silueta con esquinas cortadas (por escalones)
		for (int i = 0; i < h; i++) {
			int corte = i < c ? c - i : i >= h - c ? i - (h - c) + 1 : 0;
			g.fill(x + corte, y + i, x2 - corte, y + i + 1, M_BORDE);
		}
		for (int i = 1; i < h - 1; i++) {
			int corte = i < c ? c - i + 1 : i >= h - c - 1 ? i - (h - c - 1) + 2 : 1;
			int col = i < 3 ? M_LUZ : i < BORDE_ARRIBA - 2 || i > h - BORDE + 1 ? M_BASE : M_BASE;
			g.fill(x + corte, y + i, x2 - corte, y + i + 1, col);
		}
		// bisel: luz arriba e izquierda, sombra abajo y derecha
		g.fill(x + c + 1, y + 1, x2 - c - 1, y + 2, M_LUZ);
		g.fill(x + c + 1, y + 2, x2 - c - 1, y + 3, M_CLARO);
		g.fill(x + 1, y + c + 1, x + 2, y2 - c - 1, M_LUZ);
		g.fill(x + 2, y + c + 1, x + 3, y2 - c - 1, M_CLARO);
		g.fill(x + c + 1, y2 - 2, x2 - c - 1, y2 - 1, M_OSCURO);
		g.fill(x + c + 1, y2 - 3, x2 - c - 1, y2 - 2, 0xFF2E3237);
		g.fill(x2 - 2, y + c + 1, x2 - 1, y2 - c - 1, M_OSCURO);
		g.fill(x2 - 3, y + c + 1, x2 - 2, y2 - c - 1, 0xFF2E3237);
		// ranura interna alrededor de la pantalla
		int sx = x + BORDE, sy = y + BORDE_ARRIBA, sx2 = x2 - BORDE, sy2 = y2 - BORDE;
		g.fill(sx - 3, sy - 3, sx2 + 3, sy - 2, M_OSCURO);
		g.fill(sx - 3, sy - 3, sx - 2, sy2 + 3, M_OSCURO);
		g.fill(sx - 3, sy2 + 2, sx2 + 3, sy2 + 3, M_CLARO);
		g.fill(sx2 + 2, sy - 3, sx2 + 3, sy2 + 3, M_CLARO);
		// tornillos en las esquinas
		int[][] tor = { { x + 5, y + 5 }, { x2 - 8, y + 5 }, { x + 5, y2 - 8 }, { x2 - 8, y2 - 8 } };
		for (int[] p : tor) { g.fill(p[0], p[1], p[0] + 3, p[1] + 3, M_LUZ); g.fill(p[0] + 1, p[1] + 1, p[0] + 2, p[1] + 2, M_BORDE); }
		// lucecitas arriba a los costados (tipo "■■■ ■■")
		for (int k = 0; k < 3; k++) { led(g, x + 14 + k * 4, y + 6, 3, 2, t, k * 0.4f); led(g, x2 - 26 + k * 4, y + 6, 3, 2, t, k * 0.4f + 1); }
		// tiras verdes en los costados
		int mitad = (y + y2) / 2, largo = Math.min(60, h / 3);
		g.fill(x + 3, mitad - largo / 2 - 1, x + 6, mitad + largo / 2 + 1, M_BORDE);
		g.fill(x2 - 6, mitad - largo / 2 - 1, x2 - 3, mitad + largo / 2 + 1, M_BORDE);
		for (int i = 0; i < largo; i += 3) {
			led(g, x + 4, mitad - largo / 2 + i, 1, 2, t, i * 0.08f);
			led(g, x2 - 5, mitad - largo / 2 + i, 1, 2, t, i * 0.08f + 0.5f);
		}
		// muesca de arriba con el dia
		String dia = "DÍA " + datos.hoy;
		int mw = Math.max(70, this.font.width(dia) * 3 / 2 + 30), mx0 = (x + x2) / 2 - mw / 2, my0 = y - 4;
		for (int i = 0; i < 18; i++) {
			int corte = i < 4 ? 4 - i : i >= 14 ? i - 13 : 0;
			g.fill(mx0 + corte, my0 + i, mx0 + mw - corte, my0 + i + 1, M_BORDE);
			if (i > 0 && i < 17) g.fill(mx0 + corte + 1, my0 + i, mx0 + mw - corte - 1, my0 + i + 1, i < 3 ? M_LUZ : i > 14 ? M_OSCURO : M_BASE);
		}
		g.fill(mx0 + 6, my0 + 4, mx0 + mw - 6, my0 + 15, 0xFF07100A);
		g.pose().pushMatrix();
		g.pose().translate((x + x2) / 2f, my0 + 5);
		g.pose().scale(1.25f, 1.25f);
		g.text(this.font, dia, -this.font.width(dia) / 2, 0, pulso(t, 0.8f, 1f), true);
		g.pose().popMatrix();
		// luces abajo al centro
		int bw = 60, bx = (x + x2) / 2 - bw / 2;
		g.fill(bx - 1, y2 - 6, bx + bw + 1, y2 - 2, M_BORDE);
		for (int i = 0; i < bw; i += 4) led(g, bx + i + 1, y2 - 5, 2, 2, t, i * 0.05f);
	}

	private static void led(GuiGraphicsExtractor g, int x, int y, int lw, int lh, float t, float fase) {
		g.fill(x, y, x + lw, y + lh, pulso(t + fase, 0.35f, 1f));
	}

	private void irA(Seccion s) { this.seccion = s; this.dia = datos.hoy; }

	private void titulo(GuiGraphicsExtractor g, String t, int x, int y) {
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(1.25f, 1.25f);
		g.text(this.font, t, 0, 0, TEXTO, false);
		g.pose().popMatrix();
	}

	/** Fila de dias: los ultimos 7, arranca en hoy. Devuelve la altura usada. */
	private int filaDeDias(GuiGraphicsExtractor g, int x, int y, int maxX, int mx, int my, boolean puntitos) {
		int bx = x, by = y;
		for (int n = Math.max(1, datos.hoy - 6); n <= datos.hoy; n++) {
			String t = n == datos.hoy ? "Día " + n + " (hoy)" : "Día " + n;
			int bw = this.font.width(t) + 10;
			if (bx + bw > maxX) { bx = x; by += 15; }
			final int d = n;
			boton(g, t, bx, by, n == this.dia, mx, my, () -> this.dia = d);
			if (puntitos && datos.hayNuevoEn(n)) { g.fill(bx + bw - 5, by + 2, bx + bw - 2, by + 5, VERDE); }
			bx += bw + 2;
		}
		return by - y + 15;
	}

	// ---------- INICIO ----------
	private void inicio(GuiGraphicsExtractor g, int x, int y, int cw, int mx, int my) {
		titulo(g, "Hoy, día " + datos.hoy, x, y);
		int colW = (cw - 6) / 2, alto = 46;
		String[][] tarjetas = datos.resumen(reclamadas);
		Seccion[] destino = { Seccion.NOVEDADES, Seccion.MISIONES, Seccion.NOVEDADES, datos.equipo != null ? Seccion.EQUIPO : Seccion.CRAFTEOS };
		for (int i = 0; i < 4; i++) {
			int tx = x + (i % 2) * (colW + 6), ty = y + 18 + (i / 2) * (alto + 6);
			tarjeta(g, tx, ty, colW, alto);
			boolean encima = mx >= tx && my >= ty && mx < tx + colW && my < ty + alto;
			if (encima) g.fill(tx + 1, ty + 1, tx + colW - 1, ty + alto - 1, 0x33FFFFFF);
			g.text(this.font, tarjetas[i][0], tx + 6, ty + 5, 0xFFE0E0E0, true);
			g.pose().pushMatrix(); g.pose().translate(tx + 6, ty + 16); g.pose().scale(1.5f, 1.5f);
			g.text(this.font, tarjetas[i][1], 0, 0, VERDE, true);
			g.pose().popMatrix();
			String linea = tarjetas[i][2];
			if (this.font.width(linea) > colW - 12) linea = this.font.plainSubstrByWidth(linea, colW - 12 - this.font.width("...")) + "...";
			g.text(this.font, linea, tx + 6, ty + 34, BLANCO, true);
			final Seccion d = destino[i]; final boolean cambios = i == 2;
			this.zonas.add(new Zona(tx, ty, colW, alto, () -> { irA(d); if (cambios) this.filtro = "cambio"; }));
		}
	}

	// ---------- NOVEDADES ----------
	private void novedades(GuiGraphicsExtractor g, int x, int y, int cw, int ch, int mx, int my) {
		titulo(g, "Novedades", x, y);
		int fx = x + cw;
		String[][] fs = { { "cambio", "Cambios" }, { "aviso", "Avisos" }, { "todo", "Todo" } };
		for (String[] f : fs) { int bw = this.font.width(f[1]) + 10; fx -= bw + 2; boton(g, f[1], fx, y - 1, this.filtro.equals(f[0]), mx, my, () -> this.filtro = f[0]); }
		int yy = y + 16;
		yy += filaDeDias(g, x, yy, x + cw, mx, my, true) + 4;
		List<TabletDatos.Novedad> lista = datos.novedadesDe(this.dia, this.filtro);
		if (lista.isEmpty()) { g.text(this.font, "Ese día no hubo novedades de este tipo.", x, yy + 6, GRIS_TEXTO, false); return; }
		for (TabletDatos.Novedad n : lista) {
			List<FormattedCharSequence> renglones = this.font.split(Component.literal(n.texto()), cw - 60);
			int alto = 16 + renglones.size() * 10;
			if (yy + alto > y + ch) break;
			tarjeta(g, x, yy, cw, alto);
			boolean aviso = n.tipo().equals("aviso");
			String et = aviso ? "AVISO" : "CAMBIO";
			relieve(g, x + 4, yy + 4, 46, 11, aviso ? 0xFF2F6F2F : 0xFF6F5A1F, aviso ? 0xFF4F8F4F : 0xFF8F7A3F, aviso ? 0xFF1F4F1F : 0xFF4F3A0F);
			g.text(this.font, et, x + 4 + (46 - this.font.width(et)) / 2, yy + 6, aviso ? VERDE : AMARILLO, true);
			g.text(this.font, n.titulo(), x + 56, yy + 5, BLANCO, true);
			int ry = yy + 16;
			for (FormattedCharSequence r : renglones) { g.text(this.font, r, x + 56, ry, 0xFFECECEC, true); ry += 10; }
			yy += alto + 3;
		}
	}

	// ---------- MISIONES: la diaria arriba, las opcionales abajo ----------
	private void misiones(GuiGraphicsExtractor g, int x, int y, int cw, int mx, int my) {
		titulo(g, "Misiones", x, y);
		int yy = y + 16;
		yy += filaDeDias(g, x, yy, x + cw, mx, my, false) + 4;
		g.text(this.font, "MISIÓN DIARIA", x, yy, GRIS_TEXTO, false);
		yy += 11;
		TabletDatos.Mision d = datos.diarias.get(this.dia);
		if (d == null) { g.text(this.font, "Ese día no hubo misión diaria.", x, yy + 2, GRIS_TEXTO, false); yy += 14; }
		else {
			int alto = 34;
			tarjeta(g, x, yy, cw, alto);
			g.pose().pushMatrix(); g.pose().translate(x + 6, yy + 5); g.pose().scale(1.25f, 1.25f);
			g.text(this.font, d.titulo(), 0, 0, BLANCO, true);
			g.pose().popMatrix();
			int barW = cw - 150;
			g.fill(x + 6, yy + 21, x + 6 + barW, yy + 27, NEGRO);
			g.fill(x + 7, yy + 22, x + 5 + barW, yy + 26, SLOT_OSCURO);
			int lleno = (int) ((barW - 2) * Math.min(1, d.hecho() / (float) d.total()));
			g.fill(x + 7, yy + 22, x + 7 + lleno, yy + 26, VERDE);
			g.text(this.font, d.hecho() + " / " + d.total(), x + 10 + barW, yy + 20, BLANCO, true);
			g.text(this.font, d.premio(), x + cw - 70 - this.font.width(d.premio()) / 2 + 20, yy + 6, AMARILLO, true);
			boolean hecha = this.reclamadas.contains(d.id());
			if (hecha) g.text(this.font, "Reclamada", x + cw - 58, yy + 20, VERDE, true);
			else if (d.hecho() >= d.total()) boton(g, "Reclamar", x + cw - 58, yy + 18, false, mx, my, () -> this.reclamadas.add(d.id()));
			yy += alto + 6;
		}
		g.text(this.font, "MISIONES OPCIONALES", x, yy, GRIS_TEXTO, false);
		yy += 11;
		for (TabletDatos.Mision m : datos.opcionales) {
			tarjeta(g, x, yy, cw, 16);
			g.text(this.font, this.font.plainSubstrByWidth(m.titulo(), cw - 140), x + 5, yy + 4, BLANCO, true);
			String p = m.hecho() + " / " + m.total();
			g.text(this.font, p, x + cw - 120, yy + 4, BLANCO, true);
			g.text(this.font, m.premio(), x + cw - 5 - this.font.width(m.premio()), yy + 4, AMARILLO, true);
			yy += 18;
		}
	}

	// ---------- CRAFTEOS: los items como en el creativo ----------
	private void crafteos(GuiGraphicsExtractor g, int x, int y, int cw, int mx, int my) {
		titulo(g, "Crafteos", x, y);
		int fx = x + 70;
		String[][] cs = { { "todos", "Todos" }, { "general", "General" }, { "aether", "Aether" } };
		for (String[] c : cs) fx += boton(g, c[1], fx, y - 1, this.categoria.equals(c[0]), mx, my, () -> { this.categoria = c[0]; this.itemElegido = 0; }) + 2;

		List<TabletDatos.Crafteo> visibles = datos.crafteosDe(this.categoria);
		int gx = x, gy = y + 17, cols = 9, filas = 4, t = 18;
		g.fill(gx - 1, gy - 1, gx + cols * t + 1, gy + filas * t + 1, SLOT_OSCURO);
		TabletDatos.Crafteo hover = null;
		for (int i = 0; i < cols * filas; i++) {
			int sx = gx + (i % cols) * t, sy = gy + (i / cols) * t;
			hundido(g, sx, sy, t, t);
			if (i < visibles.size()) {
				TabletDatos.Crafteo c = visibles.get(i);
				if (i == this.itemElegido) { g.fill(sx, sy, sx + t, sy + t, VERDE); g.fill(sx + 1, sy + 1, sx + t - 1, sy + t - 1, 0xFF1F3A27); }
				g.item(c.icono(), sx + 1, sy + 1);
				if (c.nuevo()) g.fill(sx + t - 4, sy + 1, sx + t - 1, sy + 4, VERDE);
				boolean encima = mx >= sx && my >= sy && mx < sx + t && my < sy + t;
				if (encima) { g.fill(sx + 1, sy + 1, sx + t - 1, sy + t - 1, 0x50FFFFFF); hover = c; }
				final int k = i;
				this.zonas.add(new Zona(sx, sy, t, t, () -> this.itemElegido = k));
			}
		}

		// el crafteo del elegido, como una mesa de crafteo
		if (!visibles.isEmpty()) {
			TabletDatos.Crafteo c = visibles.get(Math.min(this.itemElegido, visibles.size() - 1));
			int rx = gx + cols * t + 14, ry = gy;
			g.text(this.font, c.nombre(), rx, ry, TEXTO, false);
			int mgx = rx, mgy = ry + 12;
			for (int i = 0; i < 9; i++) {
				int sx = mgx + (i % 3) * t, sy = mgy + (i / 3) * t;
				hundido(g, sx, sy, t, t);
				ItemStack st = c.grilla()[i];
				if (st != null && !st.isEmpty()) {
					g.item(st, sx + 1, sy + 1);
					if (mx >= sx && my >= sy && mx < sx + t && my < sy + t) g.setTooltipForNextFrame(st.getHoverName(), mx, my);
				}
			}
			// flecha
			int ax = mgx + 3 * t + 6, ay = mgy + t + 5;
			g.fill(ax, ay + 3, ax + 12, ay + 6, 0xFF5A6A62);
			for (int k = 0; k < 5; k++) g.fill(ax + 11 + k, ay + k, ax + 12 + k, ay + 9 - k, 0xFF5A6A62);
			// resultado
			int ox = ax + 22, oy = mgy + t - 4;
			hundido(g, ox, oy, 26, 26);
			g.item(c.icono(), ox + 5, oy + 5);
			if (c.cantidad() > 1) g.itemDecorations(this.font, c.icono(), ox + 5, oy + 5, String.valueOf(c.cantidad()));
			int ny = mgy + 3 * t + 6;
			g.text(this.font, "Desde el día " + c.dia(), rx, ny, GRIS_TEXTO, false);
			for (FormattedCharSequence r : this.font.split(Component.literal(c.nota()), x + cw - rx)) { ny += 10; g.text(this.font, r, rx, ny, TEXTO, false); }
		}
		if (hover != null) {
			List<FormattedCharSequence> tip = new ArrayList<>();
			tip.add(Component.literal(hover.nombre()).getVisualOrderText());
			if (hover.nuevo()) tip.add(Component.literal("§aNuevo · día " + hover.dia()).getVisualOrderText());
			g.setTooltipForNextFrame(tip, mx, my);
		}
	}

	// ---------- EQUIPO ----------
	private void equipo(GuiGraphicsExtractor g, int x, int y, int cw, int ch, int mx, int my) {
		TabletDatos.Equipo eq = datos.equipo;
		g.pose().pushMatrix(); g.pose().translate(x, y); g.pose().scale(1.25f, 1.25f);
		g.text(this.font, eq.nombre, 0, 0, eq.color, false);
		g.pose().popMatrix();
		int fx = x + cw;
		fx -= this.font.width("Notas") + 12; boton(g, "Notas", fx, y - 1, verNotas, mx, my, () -> verNotas = true);
		fx -= this.font.width("Chat") + 12; boton(g, "Chat", fx, y - 1, !verNotas, mx, my, () -> verNotas = false);

		int yy = y + 17, lw = 110;
		for (TabletDatos.Miembro m : eq.miembros) {
			hundido(g, x, yy, lw, 14);
			g.text(this.font, this.font.plainSubstrByWidth(m.nombre(), lw - 18), x + 4, yy + 3, m.conectado() ? BLANCO : GRIS_TEXTO, true);
			g.fill(x + lw - 9, yy + 4, x + lw - 3, yy + 10, NEGRO);
			g.fill(x + lw - 8, yy + 5, x + lw - 4, yy + 9, m.conectado() ? VERDE : SOMBRA);
			yy += 15;
		}

		int bx = x + lw + 6, bw = cw - lw - 6, bt = y + 17, bh = ch - 17;
		if (!verNotas) {
			// chat: fondo negro como el de Minecraft; los mensajes largos bajan de renglon
			g.fill(bx, bt, bx + bw, bt + bh, 0xFF000000);
			g.outline(bx, bt, bw, bh, SLOT_OSCURO);
			int caja = 16;
			List<FormattedCharSequence> renglones = new ArrayList<>();
			for (TabletDatos.Mensaje msj : eq.chat) {
				Component linea = Component.literal("§7" + msj.hora() + " §d" + msj.quien() + "§7: §f" + msj.texto());
				renglones.addAll(this.font.split(linea, bw - 10));
			}
			int maxRenglones = (bh - caja - 8) / 10;
			int desde = Math.max(0, renglones.size() - maxRenglones);
			int ry = bt + bh - caja - 6 - (renglones.size() - desde) * 10;
			for (int i = desde; i < renglones.size(); i++) { g.text(this.font, renglones.get(i), bx + 5, ry, BLANCO, true); ry += 10; }
			g.fill(bx + 3, bt + bh - caja - 2, bx + bw - 3, bt + bh - 3, 0xFF000000);
			g.outline(bx + 3, bt + bh - caja - 2, bw - 6, caja - 1, 0xFFA0A0A0);
			g.text(this.font, "Escribí al equipo... (máx. 120 letras)", bx + 7, bt + bh - caja + 2, 0xFF707070, false);
		} else {
			int ny = bt;
			for (TabletDatos.Nota n : eq.notas) {
				List<FormattedCharSequence> rs = this.font.split(Component.literal(n.texto()), bw - 12);
				int alto = 26 + rs.size() * 10;
				g.fill(bx, ny, bx + bw, ny + alto, 0xFF6B5A3A);
				g.fill(bx + 1, ny + 1, bx + bw - 1, ny + alto - 1, 0xFFE9E1C4);
				g.text(this.font, n.titulo(), bx + 6, ny + 5, 0xFF3B2F1C, false);
				int ry = ny + 16;
				for (FormattedCharSequence r : rs) { g.text(this.font, r, bx + 6, ry, 0xFF3B2F1C, false); ry += 10; }
				g.text(this.font, n.autor(), bx + 6, ry, 0xFF6B5A3A, false);
				ny += alto + 4;
			}
			boton(g, "+ Nueva nota", bx, ny, false, mx, my, () -> {});
		}
	}
}

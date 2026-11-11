package ar.tod5.menu.tablet;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.Util;

/**
 * Cuadro de texto de la tablet (para los anuncios). Cada letra guarda su propio estilo
 * (color, negrita, cursiva, subrayado, tachado), asi se pueden combinar como quieras (blanco + negrita, etc.).
 *
 * Los renglones se cortan solos a un ancho fijo (ANCHO): lo que se ve aca es exactamente como sale en el chat.
 * Enter = renglon nuevo a mano (para separar parrafos).
 * Teclas: flechas, Inicio/Fin, Shift para seleccionar, Ctrl+A/C/X/V, Ctrl+Z (deshacer), Ctrl+I/U (estilos; Ctrl+B no, es el narrador).
 */
public class EditorTexto {
	/** Ancho de los renglones en pixeles (el mismo que usa el server para los avisos simples). */
	public static final int ANCHO = 270;

	/** Una letra con su estilo. color -1 = el color normal del anuncio (el claro de su tipo). */
	public record Letra(char c, int color, boolean b, boolean i, boolean u, boolean s) {
		Letra con(char nc) { return new Letra(nc, color, b, i, u, s); }
		boolean mismoEstilo(Letra o) { return color == o.color && b == o.b && i == o.i && u == o.u && s == o.s; }
	}
	/** Un renglon: letras [desde, hasta) del texto; "fin" = donde termina lo visible (sin los espacios del final). */
	private record Renglon(int desde, int hasta, int fin) {}

	private final boolean multilinea;
	private final int maxLetras;
	private List<Letra> texto = new ArrayList<>();
	private final List<List<Letra>> historial = new ArrayList<>();
	private int cursor, ancla;            // ancla = donde empezo la seleccion (igual al cursor si no hay)
	private boolean foco;
	private long ultimoCambio;
	// estilo para lo que se escriba (si no hay nada seleccionado)
	private int colorActual = -1;
	private boolean bA, iA, uA, sA;
	private List<Renglon> renglones = List.of();
	private int ultX, ultY, ultAncho, ultAlto;
	private boolean arrastrando;

	public EditorTexto(boolean multilinea, int maxLetras) { this.multilinea = multilinea; this.maxLetras = maxLetras; }

	// ---------- texto ----------
	public void poner(List<Letra> l) { this.texto = new ArrayList<>(l); this.cursor = this.ancla = l.size(); this.historial.clear(); this.colorActual = -1; bA = iA = uA = sA = false; tomarEstilo(); }
	public void ponerPlano(String s) { List<Letra> l = new ArrayList<>(); for (char c : s.toCharArray()) l.add(new Letra(c, -1, false, false, false, false)); poner(l); }
	public List<Letra> letras() { return this.texto; }
	public String plano() { StringBuilder sb = new StringBuilder(); for (Letra l : texto) sb.append(l.c()); return sb.toString(); }
	public boolean foco() { return this.foco; }
	public void foco(boolean f) { this.foco = f; if (!f) this.arrastrando = false; }
	public boolean haySeleccion() { return this.cursor != this.ancla; }
	private int selIni() { return Math.min(cursor, ancla); }
	private int selFin() { return Math.max(cursor, ancla); }

	private void guardarHistorial() {
		this.historial.add(new ArrayList<>(this.texto));
		if (this.historial.size() > 60) this.historial.remove(0);
	}
	private void deshacer() {
		if (this.historial.isEmpty()) return;
		this.texto = this.historial.remove(this.historial.size() - 1);
		this.cursor = this.ancla = Math.min(this.cursor, this.texto.size());
	}

	private Letra nueva(char c) { return new Letra(c, this.colorActual, bA, iA, uA, sA); }
	/** Al mover el cursor, lo que escribas sigue con el estilo de la letra de atras (como en cualquier editor). */
	private void tomarEstilo() {
		if (haySeleccion() || this.cursor == 0) return;
		Letra l = this.texto.get(this.cursor - 1);
		if (l.c() == '\n') return;
		this.colorActual = l.color(); bA = l.b(); iA = l.i(); uA = l.u(); sA = l.s();
	}

	private void borrarSeleccion() {
		int a = selIni(), b = selFin();
		for (int k = a; k < b; k++) this.texto.remove(a);
		this.cursor = this.ancla = a;
	}

	public void escribir(String s) {
		guardarHistorial();
		if (haySeleccion()) borrarSeleccion();
		for (char c : s.toCharArray()) {
			if (c == '\r') continue;
			if (c == '\n' && !this.multilinea) continue;
			if (c == '\t') c = ' ';
			if (this.texto.size() >= this.maxLetras) break;
			if (c < 32 && c != '\n') continue;
			this.texto.add(this.cursor++, nueva(c));
		}
		this.ancla = this.cursor;
		this.ultimoCambio = Util.getMillis();
	}

	/** El estilo que muestran los botones: el de la seleccion (o el de lo que vas a escribir). */
	public boolean negrita() { return estiloSel(0); }
	public boolean cursiva() { return estiloSel(1); }
	public boolean subrayado() { return estiloSel(2); }
	public boolean tachado() { return estiloSel(3); }
	private boolean estiloSel(int k) {
		if (!haySeleccion()) return switch (k) { case 0 -> bA; case 1 -> iA; case 2 -> uA; default -> sA; };
		for (int p = selIni(); p < selFin(); p++) {
			Letra l = this.texto.get(p);
			boolean v = switch (k) { case 0 -> l.b(); case 1 -> l.i(); case 2 -> l.u(); default -> l.s(); };
			if (!v) return false;
		}
		return true;
	}

	/** Prende/apaga un estilo (0 negrita, 1 cursiva, 2 subrayado, 3 tachado) en lo seleccionado o para lo que sigue. */
	public void alternar(int k) {
		boolean v = !estiloSel(k);
		if (!haySeleccion()) {
			switch (k) { case 0 -> bA = v; case 1 -> iA = v; case 2 -> uA = v; default -> sA = v; }
			return;
		}
		guardarHistorial();
		for (int p = selIni(); p < selFin(); p++) {
			Letra l = this.texto.get(p);
			this.texto.set(p, new Letra(l.c(), l.color(), k == 0 ? v : l.b(), k == 1 ? v : l.i(), k == 2 ? v : l.u(), k == 3 ? v : l.s()));
		}
	}
	/** Pinta lo seleccionado (o lo que sigue). -1 = el color normal del anuncio. */
	public void pintar(int color) {
		if (!haySeleccion()) { this.colorActual = color; return; }
		guardarHistorial();
		for (int p = selIni(); p < selFin(); p++) { Letra l = this.texto.get(p); this.texto.set(p, new Letra(l.c(), color, l.b(), l.i(), l.u(), l.s())); }
	}
	public int colorSeleccion() {
		if (!haySeleccion()) return this.colorActual;
		int c = this.texto.get(selIni()).color();
		for (int p = selIni(); p < selFin(); p++) if (this.texto.get(p).color() != c) return -3;
		return c;
	}

	// ---------- teclado ----------
	public boolean tecla(KeyEvent e) {
		if (!this.foco) return false;
		boolean shift = e.hasShiftDown(), ctrl = e.hasControlDownWithQuirk();
		Minecraft mc = Minecraft.getInstance();
		if (e.isSelectAll()) { this.ancla = 0; this.cursor = this.texto.size(); return true; }
		if (e.isCopy()) { if (haySeleccion()) mc.keyboardHandler.setClipboard(planoEntre(selIni(), selFin())); return true; }
		if (e.isCut()) { if (haySeleccion()) { mc.keyboardHandler.setClipboard(planoEntre(selIni(), selFin())); guardarHistorial(); borrarSeleccion(); } return true; }
		if (e.isPaste()) { escribir(mc.keyboardHandler.getClipboard()); return true; }
		if (ctrl && e.key() == 90) { deshacer(); return true; }          // Ctrl+Z
		if (ctrl && e.key() == 73) { alternar(1); return true; }        // Ctrl+I
		if (ctrl && e.key() == 85) { alternar(2); return true; }        // Ctrl+U
		switch (e.input()) {
			case 42 -> {   // borrar para atras
				guardarHistorial();
				if (haySeleccion()) borrarSeleccion();
				else if (this.cursor > 0) { int desde = ctrl ? palabraAtras(this.cursor) : this.cursor - 1; for (int k = desde; k < this.cursor; k++) this.texto.remove(desde); this.cursor = this.ancla = desde; }
				return true;
			}
			case 76 -> {   // suprimir
				guardarHistorial();
				if (haySeleccion()) borrarSeleccion();
				else if (this.cursor < this.texto.size()) this.texto.remove(this.cursor);
				return true;
			}
			case 40, 88 -> { if (this.multilinea) { escribir("\n"); return true; } return false; }   // Enter
			case 80 -> { mover(ctrl ? palabraAtras(this.cursor) : (haySeleccion() && !shift ? selIni() : Math.max(0, this.cursor - 1)), shift); return true; }
			case 79 -> { mover(ctrl ? palabraAdelante(this.cursor) : (haySeleccion() && !shift ? selFin() : Math.min(this.texto.size(), this.cursor + 1)), shift); return true; }
			case 82 -> { mover(verticalmente(-1), shift); return true; }
			case 81 -> { mover(verticalmente(1), shift); return true; }
			case 74 -> { mover(ctrl ? 0 : renglonDe(this.cursor).desde(), shift); return true; }
			case 77 -> { mover(ctrl ? this.texto.size() : finDeRenglon(renglonDe(this.cursor)), shift); return true; }
			default -> { return false; }
		}
	}
	public boolean caracter(CharacterEvent e) {
		if (!this.foco || !e.isAllowedChatCharacter()) return false;
		escribir(e.codepointAsString());
		return true;
	}
	private String planoEntre(int a, int b) { StringBuilder sb = new StringBuilder(); for (int k = a; k < b; k++) sb.append(this.texto.get(k).c()); return sb.toString(); }
	private void mover(int a, boolean shift) { this.cursor = Math.max(0, Math.min(this.texto.size(), a)); if (!shift) this.ancla = this.cursor; this.ultimoCambio = Util.getMillis(); tomarEstilo(); }
	private int palabraAtras(int p) { while (p > 0 && this.texto.get(p - 1).c() == ' ') p--; while (p > 0 && this.texto.get(p - 1).c() != ' ' && this.texto.get(p - 1).c() != '\n') p--; return p; }
	private int palabraAdelante(int p) { int n = this.texto.size(); while (p < n && this.texto.get(p).c() != ' ' && this.texto.get(p).c() != '\n') p++; while (p < n && this.texto.get(p).c() == ' ') p++; return p; }

	// ---------- renglones ----------
	private static Font font() { return Minecraft.getInstance().font; }
	public static int ancho(Letra l) {
		int w = font().width(String.valueOf(l.c()));
		return w + (l.b() ? 1 : 0);
	}
	/** Corta en renglones: en los Enter y cuando una palabra no entra (una palabra mas larga que el renglon se parte). */
	private static List<Renglon> cortar(List<Letra> t, int max) {
		List<Renglon> out = new ArrayList<>();
		int n = t.size(), ini = 0;
		while (ini <= n) {
			int p = ini, w = 0, ultimoCorte = -1;
			boolean enter = false;
			while (p < n) {
				Letra l = t.get(p);
				if (l.c() == '\n') { enter = true; break; }
				int lw = ancho(l);
				if (l.c() != ' ' && w + lw > max) break;
				if (l.c() == ' ') ultimoCorte = p + 1;
				w += lw; p++;
			}
			if (p >= n || enter) {
				out.add(new Renglon(ini, p, finVisible(t, ini, p)));
				if (!enter) break;
				ini = p + 1;
				continue;
			}
			int corte = ultimoCorte > ini ? ultimoCorte : Math.max(p, ini + 1);
			out.add(new Renglon(ini, corte, finVisible(t, ini, corte)));
			ini = corte;
		}
		if (out.isEmpty()) out.add(new Renglon(0, 0, 0));
		return out;
	}
	private static int finVisible(List<Letra> t, int a, int b) { while (b > a && t.get(b - 1).c() == ' ') b--; return b; }

	private Renglon renglonDe(int pos) {
		List<Renglon> rs = this.renglones.isEmpty() ? cortar(this.texto, ANCHO) : this.renglones;
		for (int k = 0; k < rs.size(); k++) {
			Renglon r = rs.get(k);
			boolean ultimo = k == rs.size() - 1;
			if (pos >= r.desde() && (pos < r.hasta() || ultimo || (pos == r.hasta() && (r.hasta() >= this.texto.size() || this.texto.get(r.hasta()).c() == '\n')))) return r;
		}
		return rs.get(rs.size() - 1);
	}
	private int finDeRenglon(Renglon r) { return r.hasta() < this.texto.size() && this.texto.get(r.hasta() - (r.hasta() > r.desde() ? 1 : 0)).c() == ' ' ? r.hasta() - 1 : r.hasta(); }
	private int xEn(Renglon r, int pos) { int w = 0; for (int k = r.desde(); k < pos && k < r.hasta(); k++) w += ancho(this.texto.get(k)); return w; }
	private int posEn(Renglon r, int x) {
		int w = 0;
		for (int k = r.desde(); k < r.hasta(); k++) {
			if (this.texto.get(k).c() == '\n') return k;
			int lw = ancho(this.texto.get(k));
			if (x < w + lw / 2) return k;
			w += lw;
		}
		return r.hasta() < this.texto.size() && r.hasta() > r.desde() && this.texto.get(r.hasta() - 1).c() == ' ' ? r.hasta() - 1 : r.hasta();
	}
	private int verticalmente(int d) {
		List<Renglon> rs = cortar(this.texto, ANCHO);
		Renglon r = renglonDe(this.cursor);
		int k = rs.indexOf(r) + d;
		if (k < 0) return 0;
		if (k >= rs.size()) return this.texto.size();
		return posEn(rs.get(k), xEn(r, this.cursor));
	}

	/** Los renglones como pedacitos con estilo (lo que se manda al server). */
	public static List<List<Letra>> renglones(List<Letra> t) {
		List<List<Letra>> out = new ArrayList<>();
		for (Renglon r : cortar(t, ANCHO)) out.add(new ArrayList<>(t.subList(r.desde(), r.fin())));
		return out;
	}
	public static JsonArray json(List<Letra> letras) {
		JsonArray a = new JsonArray();
		StringBuilder sb = new StringBuilder();
		Letra est = null;
		for (Letra l : letras) {
			if (est != null && !est.mismoEstilo(l)) { a.add(trozo(sb.toString(), est)); sb.setLength(0); }
			est = l; sb.append(l.c());
		}
		if (est != null && sb.length() > 0) a.add(trozo(sb.toString(), est));
		return a;
	}
	private static JsonObject trozo(String t, Letra e) {
		JsonObject j = new JsonObject();
		j.addProperty("t", t);
		if (e.color() >= 0) j.addProperty("c", e.color());
		if (e.b()) j.addProperty("b", true);
		if (e.i()) j.addProperty("i", true);
		if (e.u()) j.addProperty("u", true);
		if (e.s()) j.addProperty("s", true);
		return j;
	}
	public static List<Letra> deJson(JsonArray a) {
		List<Letra> l = new ArrayList<>();
		if (a == null) return l;
		for (var e : a) {
			JsonObject t = e.getAsJsonObject();
			int c = t.has("c") ? t.get("c").getAsInt() : -1;
			boolean b = t.has("b") && t.get("b").getAsBoolean(), i = t.has("i") && t.get("i").getAsBoolean(),
				u = t.has("u") && t.get("u").getAsBoolean(), s = t.has("s") && t.get("s").getAsBoolean();
			for (char ch : t.get("t").getAsString().toCharArray()) l.add(new Letra(ch, c, b, i, u, s));
		}
		return l;
	}

	/** Un renglon como texto de Minecraft con sus estilos (para dibujarlo igual que en el chat). */
	public static MutableComponent componente(List<Letra> letras, int desde, int hasta, int colorNormal) {
		MutableComponent c = Component.empty();
		StringBuilder sb = new StringBuilder();
		Letra est = null;
		for (int k = desde; k < hasta; k++) {
			Letra l = letras.get(k);
			if (l.c() == '\n') continue;
			if (est != null && !est.mismoEstilo(l)) { c.append(Component.literal(sb.toString()).withStyle(estilo(est, colorNormal))); sb.setLength(0); }
			est = l; sb.append(l.c());
		}
		if (est != null && sb.length() > 0) c.append(Component.literal(sb.toString()).withStyle(estilo(est, colorNormal)));
		return c;
	}
	private static Style estilo(Letra l, int colorNormal) {
		return Style.EMPTY.withColor((l.color() >= 0 ? l.color() : colorNormal) & 0xFFFFFF).withBold(l.b()).withItalic(l.i()).withUnderlined(l.u()).withStrikethrough(l.s());
	}

	// ---------- dibujar y mouse ----------
	/** Dibuja el texto (con sus renglones cortados), la seleccion y el cursor. alto = cuantos px hay; devuelve los renglones. */
	public int dibujar(GuiGraphics g, int x, int y, int ancho, int alto, int colorNormal) {
		this.ultX = x; this.ultY = y; this.ultAncho = ancho; this.ultAlto = alto;
		Font f = font();
		this.renglones = this.multilinea ? cortar(this.texto, ANCHO) : List.of(new Renglon(0, this.texto.size(), this.texto.size()));
		int ry = y;
		for (Renglon r : this.renglones) {
			if (ry + 9 > y + alto) break;
			if (haySeleccion()) {
				int a = Math.max(selIni(), r.desde()), b = Math.min(selFin(), r.hasta());
				if (a < b) g.fill(x + xEn(r, a), ry - 1, x + xEn(r, b), ry + 9, 0xFF3C5A9A);
			}
			g.drawString(f, componente(this.texto, r.desde(), r.hasta(), colorNormal).getVisualOrderText(), x, ry, colorNormal, false);
			ry += 10;
		}
		if (this.foco && (Util.getMillis() - this.ultimoCambio) % 1000 < 600) {
			Renglon r = renglonDe(this.cursor);
			int k = this.renglones.indexOf(r);
			if (k >= 0) { int cx = x + xEn(r, this.cursor), cy = y + k * 10; if (cy + 9 <= y + alto) g.fill(cx, cy - 1, cx + 1, cy + 9, 0xFFE8E8E8); }
		}
		return this.renglones.size();
	}

	public boolean adentro(double mx, double my) { return mx >= ultX - 3 && my >= ultY - 3 && mx < ultX + ultAncho + 3 && my < ultY + ultAlto + 3; }
	private int posDelMouse(double mx, double my) {
		if (this.renglones.isEmpty()) return 0;
		int k = (int) Math.floor((my - ultY) / 10.0);
		k = Math.max(0, Math.min(this.renglones.size() - 1, k));
		return posEn(this.renglones.get(k), (int) (mx - ultX));
	}
	public void clic(double mx, double my, boolean shift) { this.foco = true; this.arrastrando = true; mover(posDelMouse(mx, my), shift); }
	public void arrastrar(double mx, double my) { if (this.arrastrando && this.foco) { this.cursor = posDelMouse(mx, my); } }
	public void soltar() { this.arrastrando = false; }
}

package ar.tod5.menu.tablet;

import java.util.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.client.renderer.RenderPipelines;
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
	static final int PANTALLA = 0xFF0B100E, LINEA = 0xFF26302B;
	private static final int SLOT = 0xFF19201C, SLOT_OSCURO = 0xFF050706, SLOT_CLARO = 0xFF34403A, SOMBRA = 0xFF3A443F;
	static final int TEXTO = 0xFFD9E2DC, VERDE = 0xFF55FF55, AMARILLO = 0xFFFFE066, GRIS_TEXTO = 0xFF7D8A83, AMARILLO_ADMIN = 0xFFFFD23C;
	private static final int M_BORDE = 0xFF0A0B0C, M_LUZ = 0xFF8D949C, M_CLARO = 0xFF5E646B, M_BASE = 0xFF3C4147, M_OSCURO = 0xFF24272B;

	enum Seccion { INICIO("Inicio", Items.COMPASS), NOVEDADES("Novedades", Items.WRITABLE_BOOK), MISIONES("Misiones", Items.IRON_SWORD),
		CRAFTEOS("Crafteos", Items.CRAFTING_TABLE), EQUIPO("Equipo", Items.SHIELD), RECOMPENSAS("Recompensas", Items.GOLD_NUGGET),
		ANUNCIOS("Anuncios", Items.BELL), ADMIN_CRAFTEOS("Crafteos", Items.CRAFTING_TABLE), ITEMS("Ítems", Items.NAME_TAG), ADMIN_MISIONES("Misiones", Items.IRON_SWORD), ADMIN_EQUIPOS("Equipos", Items.SHIELD);   // las del modo admin
		final String nombre; final ItemStack icono;
		Seccion(String n, net.minecraft.world.item.Item i) { nombre = n; icono = new ItemStack(i); } }

	private static Seccion ultima = Seccion.INICIO;   // al reabrir, vuelve a donde estabas
	/** Modo admin (solo editores): todo lo verde pasa a amarillo y las pestañas son las de editar. */
	private static boolean admin = false;
	private final PanelAnuncios anuncios = new PanelAnuncios(this);
	private final PanelCrafteos crafteosAdmin = new PanelCrafteos(this);
	private final PanelItems itemsAdmin = new PanelItems(this);
	private final PanelMisiones misionesAdmin = new PanelMisiones(this);
	private final PanelEquipos equiposAdmin = new PanelEquipos(this);
	private final PanelEquipo equipoJugador = new PanelEquipo(this);
	/** El panel del modo admin que se esta mostrando (null si no es una seccion de admin). */
	private Panel panel() {
		return switch (this.seccion) { case ANUNCIOS -> this.anuncios; case ADMIN_CRAFTEOS -> this.crafteosAdmin; case ITEMS -> this.itemsAdmin; case ADMIN_MISIONES -> this.misionesAdmin; case ADMIN_EQUIPOS -> this.equiposAdmin; case EQUIPO -> this.equipoJugador; default -> null; };
	}
	private boolean textoActivo;   // Minecraft solo manda letras si se le avisa que hay un cuadro de texto activo
	private static boolean esAdmin(Seccion s) { return s == Seccion.ANUNCIOS || s == Seccion.ADMIN_CRAFTEOS || s == Seccion.ITEMS || s == Seccion.ADMIN_MISIONES || s == Seccion.ADMIN_EQUIPOS; }
	/** El color de las luces: verde normalmente, amarillo en modo admin. */
	int acento() { return admin ? AMARILLO_ADMIN : VERDE; }
	void zona(int x, int y, int w, int h, Runnable r) { this.zonas.add(new Zona(x, y, w, h, r)); }
	/** X del boton chiquito de arriba de una lista (+Nuevo): a la derecha de la lista, pero nunca encima del titulo. */
	int alLadoDelTitulo(String titulo, String boton, int x, int lw) {
		int finTitulo = x + (int) Math.ceil(this.font.width(titulo) * 1.25f) + 4;
		return Math.max(finTitulo, x + lw - this.font.width(boton) - 10);
	}
	/** Corta un texto al ancho, con "…" si no entra. */
	static String cortar(net.minecraft.client.gui.Font f, String s, int ancho) {
		if (s == null) return "";
		if (f.width(s) <= ancho) return s;
		return f.plainSubstrByWidth(s, Math.max(0, ancho - f.width("…"))) + "…";
	}

	// ---------- la ventanita para elegir (encima de todo) ----------
	private Selector selector;
	void abrirSelector(Selector s) { this.selector = s; }
	void cerrarSelector() { this.selector = null; }

	/**
	 * El boton "Anuncio" de un crafteo o una mision: muestra el vinculado y al hacer clic abre la lista para elegir otro.
	 * tipo: "crafteo" o "mision"; clave: "c:<id>" o "m:<id>". Devuelve el ancho.
	 */
	int botonAnuncio(GuiGraphics g, TabletDatos d, String tipo, String id, int x, int y, int ancho, int mx, int my) {
		String clave = (tipo.equals("crafteo") ? "c:" : "m:") + id;
		TabletDatos.Anuncio a = d.anuncio(d.vinculo(clave));
		String txt = a == null ? "Ninguno · Vincular" : (a.titulo().isBlank() ? PanelAnuncios.TITULOS[PanelAnuncios.gajo(a.tipo())] : a.titulo()) + (a.enviado() ? " · enviado" : "");
		int bw = Math.min(ancho, this.font.width(txt) + 22);
		boolean sobre = mx >= x && my >= y && mx < x + bw && my < y + 13;
		g.fill(x, y, x + bw, y + 13, sobre ? acento() : a != null ? PanelAnuncios.fuerte(PanelAnuncios.gajo(a.tipo())) : 0xFF3A4840);
		g.fill(x + 1, y + 1, x + bw - 1, y + 12, sobre ? 0xFF2D2A1E : 0xFF1A211D);
		if (a != null) g.fill(x + 3, y + 3, x + 5, y + 10, PanelAnuncios.fuerte(PanelAnuncios.gajo(a.tipo())));
		g.drawString(this.font, cortar(this.font, txt, bw - 20), x + (a != null ? 8 : 5), y + 3, a == null ? GRIS_TEXTO : TEXTO, false);
		g.drawString(this.font, "▾", x + bw - 10, y + 3, sobre ? acento() : GRIS_TEXTO, false);
		if (sobre) g.setTooltipForNextFrame(Component.literal(a == null
			? "Elegí el anuncio que lo activa. Al activarlo sale el anuncio, y al mandar el anuncio se activa."
			: "Al activarlo sale este anuncio; al desactivarlo se retira. Mandar el anuncio también lo activa. Clic: cambiar"), mx, my);
		this.zonas.add(new Zona(x, y, bw, 13, () -> abrirSelector(selectorAnuncios(d, tipo, id, clave))));
		return bw;
	}
	private static String nombreGrupo(int g) { return g == -1 ? "Momento truco" : g == 0 ? "General" : "Día " + g; }
	private Selector selectorAnuncios(TabletDatos d, String tipo, String id, String clave) {
		List<Selector.Opcion> ops = new ArrayList<>();
		ops.add(new Selector.Opcion("", "Sin anuncio", "", 0, ""));
		List<TabletDatos.Anuncio> orden = new ArrayList<>(d.anuncios);
		orden.sort(java.util.Comparator.comparingInt((TabletDatos.Anuncio a) -> a.grupo() <= 0 ? 10000 - a.grupo() : a.grupo()));
		for (TabletDatos.Anuncio a : orden) {
			long otros = d.vinculos.entrySet().stream().filter(e -> e.getValue().equals(a.id()) && !e.getKey().equals(clave)).count();
			int k = PanelAnuncios.gajo(a.tipo());
			ops.add(new Selector.Opcion(a.id(), a.titulo().isBlank() ? PanelAnuncios.TITULOS[k] : a.titulo(),
				(a.enviado() ? "Enviado" : "Borrador") + (otros > 0 ? " · ya activa " + otros + (otros == 1 ? " cosa más" : " cosas más") : ""),
				PanelAnuncios.fuerte(k), nombreGrupo(a.grupo())));
		}
		return new Selector("Vincular a un anuncio", ops, d.vinculo(clave), "Todavía no hay anuncios. Creá uno en la pestaña Anuncios y después vinculalo acá.", elegido -> {
			if (elegido.isEmpty()) d.vinculos.remove(clave); else d.vinculos.put(clave, elegido);   // se ve al instante (el server lo confirma)
			com.google.gson.JsonObject o = new com.google.gson.JsonObject();
			o.addProperty("a", "vincular"); o.addProperty("tipo", tipo); o.addProperty("anuncio", elegido);
			com.google.gson.JsonArray ids = new com.google.gson.JsonArray(); ids.add(id); o.add("ids", ids);
			ar.tod5.menu.Conexion.accion(o.toString());
		});
	}

	private TabletDatos datos = TabletDatos.actual();   // se actualiza solo si el server manda algo nuevo
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
	public void removed() { this.selector = null; this.anuncios.guardarSiHaceFalta(); this.crafteosAdmin.guardarSiHaceFalta(); this.itemsAdmin.guardarSiHaceFalta(); this.misionesAdmin.guardarSiHaceFalta(); this.equiposAdmin.guardarSiHaceFalta(); this.equipoJugador.guardarSiHaceFalta(); ultima = this.seccion; Vistos.guardar(); sonido("tablet_cerrar"); super.removed(); }

	/** Sonidos propios de la tablet (abrir, cerrar, tocar). */
	private void sonido(String nombre) {
		this.minecraft.getSoundManager().play(new SimpleSoundInstance(net.minecraft.resources.Identifier.fromNamespaceAndPath("tod5", nombre),
			net.minecraft.sounds.SoundSource.MASTER, 0.8f, 1f, net.minecraft.util.RandomSource.create(), false, 0,
			net.minecraft.client.resources.sounds.SoundInstance.Attenuation.NONE, 0, 0, 0, true));
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (this.selector != null) { if (event.isEscape()) this.selector = null; else this.selector.tecla(event); return true; }
		if (panel() != null && panel().tecla(event)) return true;   // escribiendo un anuncio
		if (TeclaTablet.TECLA.matches(event) && !(panel() != null && panel().escribiendo())) { this.onClose(); return true; }   // la misma tecla la cierra
		return super.keyPressed(event);
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (this.selector != null) return this.selector.caracter(event);
		if (panel() != null && panel().caracter(event)) return true;
		return super.charTyped(event);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (panel() != null) panel().arrastrar(event.x(), event.y());
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (panel() != null) panel().soltar();
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double sx, double sy) {
		if (this.selector != null) { this.selector.ruedita(sy); return true; }
		if (panel() != null && panel().ruedita(mx, my, sy)) return true;
		if (this.seccion == Seccion.MISIONES) { this.scrollMisiones = Math.max(0, this.scrollMisiones - (int) (sy * 14)); return true; }
		return super.mouseScrolled(mx, my, sx, sy);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (this.selector == null && panel() != null && panel().clic(event.x(), event.y(), event.hasShiftDown())) return true;
		for (Zona z : List.copyOf(this.zonas)) {
			if (z.adentro(event.x(), event.y())) {
				sonido("tablet_tocar");
				z.accion().run();
				return true;
			}
		}
		if (panel() != null && this.selector == null) panel().sinFoco();
		return super.mouseClicked(event, doubleClick);
	}

	// ---------- piezas ----------
	private static void relieve(GuiGraphics g, int x, int y, int w, int h, int fondo, int claro, int oscuro) {
		g.fill(x, y, x + w, y + h, NEGRO);
		g.fill(x + 1, y + 1, x + w - 1, y + h - 1, fondo);
		g.fill(x + 1, y + 1, x + w - 1, y + 2, claro);
		g.fill(x + 1, y + 1, x + 2, y + h - 1, claro);
		g.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, oscuro);
		g.fill(x + w - 2, y + 1, x + w - 1, y + h - 1, oscuro);
	}
	/** Fila oscura con borde fino: para las tarjetas con texto. */
	private static void tarjeta(GuiGraphics g, int x, int y, int w, int h) {
		g.fill(x, y, x + w, y + h, LINEA);
		g.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFF141B18);
		g.fill(x + 1, y + 1, x + w - 1, y + 2, 0xFF1D2622);
	}
	/** Casilla hundida (como las del inventario, pero oscura). */
	private static void hundido(GuiGraphics g, int x, int y, int w, int h) {
		g.fill(x, y, x + w, y + h, SLOT);
		g.fill(x, y, x + w, y + 1, SLOT_OSCURO);
		g.fill(x, y, x + 1, y + h, SLOT_OSCURO);
		g.fill(x, y + h - 1, x + w, y + h, SLOT_CLARO);
		g.fill(x + w - 1, y, x + w, y + h, SLOT_CLARO);
	}
	/** Boton oscuro; borde verde si esta elegido o con el mouse encima. Devuelve su ancho. */
	int boton(GuiGraphics g, String t, int x, int y, boolean elegido, int mx, int my, Runnable accion) {
		int bw = this.font.width(t) + 10, bh = 13;
		boolean encima = mx >= x && my >= y && mx < x + bw && my < y + bh;
		g.fill(x, y, x + bw, y + bh, elegido ? (admin ? 0xFF9E862F : 0xFF2F9E46) : encima ? acento() : 0xFF3A4840);
		g.fill(x + 1, y + 1, x + bw - 1, y + bh - 1, elegido ? (admin ? 0xFF3A3214 : 0xFF143A1F) : encima ? (admin ? 0xFF2D2A1E : 0xFF1E2D24) : 0xFF1A211D);
		g.fill(x + 1, y + 1, x + bw - 1, y + 2, elegido ? (admin ? 0xFF5A4E1F : 0xFF1F5A2E) : 0xFF27312C);
		g.drawString(this.font, t, x + 5, y + 3, elegido || encima ? acento() : TEXTO, true);
		this.zonas.add(new Zona(x, y, bw, bh, accion));
		return bw;
	}
	private void numerito(GuiGraphics g, int n, int x, int y) {
		String s = String.valueOf(n);
		int bw = Math.max(10, this.font.width(s) + 5);
		int bh = 11;
		g.fill(x, y - 1, x + bw, y - 1 + bh, NEGRO);
		g.fill(x + 1, y, x + bw - 1, y - 2 + bh, VERDE);
		g.drawString(this.font, s, x + (bw - this.font.width(s)) / 2 + 1, y + 1, 0xFF0B2A0B, false);
	}

	// ---------- pantalla ----------
	@Override
	public void render(GuiGraphics g, int mx, int my, float a) {
		this.datos = TabletDatos.actual();
		if (!this.datos.editor) admin = false;
		boolean escribe = panel() != null && panel().escribiendo();
		if (escribe != this.textoActivo) {
			// en 1.21.11 las letras llegan solas (no hace falta avisar que hay un cuadro de texto)
			this.textoActivo = escribe;
		}
		if (admin != esAdmin(this.seccion)) this.seccion = admin ? Seccion.ANUNCIOS : Seccion.INICIO;
		this.zonas.clear();
		if (this.seccion == Seccion.EQUIPO && this.datos.equipo == null) this.seccion = Seccion.INICIO;
		if (this.seccion == Seccion.RECOMPENSAS && this.datos.recompensas.isEmpty()) this.seccion = Seccion.INICIO;
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
		this.encimaTexto = null;
		for (Seccion s : Seccion.values()) {
			boolean apagada = s == Seccion.EQUIPO && datos.equipo == null;   // sin equipo: en gris, no se puede tocar
			if (s == Seccion.RECOMPENSAS && datos.recompensas.isEmpty()) continue;   // solo si tenes algo para reclamar
			if (esAdmin(s) != admin) continue;                            // en modo admin, solo las de editar
			boolean act = s == this.seccion, sobre = mx >= tx && my >= ty && mx < tx + tam && my < ty + tam;
			if (sobre) { encima = s; if (apagada) encimaTexto = "No tienes equipo"; }
			if (apagada) {
				relieve(g, tx, ty, tam, tam, 0xFF141816, 0xFF2A302D, 0xFF101312);
				g.pose().pushMatrix(); g.pose().translate(tx + 3, ty + 3); g.pose().scale(1.25f, 1.25f);
				g.renderItem(s.icono, 0, 0);
				g.pose().popMatrix();
				g.fill(tx + 1, ty + 1, tx + tam - 1, ty + tam - 1, 0xA0101312);   // apagado
				ty += tam + 6;
				continue;
			}
			relieve(g, tx, ty, tam, tam, act ? (admin ? 0xFF302A16 : 0xFF16301F) : sobre ? 0xFF2A3230 : 0xFF1E2422, act ? acento() : M_CLARO, act ? (admin ? 0xFF7A661F : 0xFF1F7A33) : M_OSCURO);
			if (act) g.fill(tx - 3, ty + 6, tx - 1, ty + tam - 6, pulso(t, 0.6f, 1f));
			g.pose().pushMatrix(); g.pose().translate(tx + 3, ty + 3); g.pose().scale(1.25f, 1.25f);
			g.renderItem(s == Seccion.RECOMPENSAS ? moneda() : s.icono, 0, 0);
			g.pose().popMatrix();
			int badge = switch (s) {
				case NOVEDADES -> datos.novedadesNuevas();
				case MISIONES -> datos.misionesParaReclamar();
				case CRAFTEOS -> datos.crafteosNuevos();
				case EQUIPO -> datos.equipo == null ? 0 : datos.equipo.mensajesNuevos();
				case RECOMPENSAS -> datos.recompensas.size();
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
			case MISIONES -> misiones(g, cx, cy, cw, ch, mx, my);
			case CRAFTEOS -> crafteos(g, cx, cy, cw, mx, my);
			case RECOMPENSAS -> recompensas(g, cx, cy, cw, ch, mx, my);
			case EQUIPO, ANUNCIOS, ADMIN_CRAFTEOS, ITEMS, ADMIN_MISIONES, ADMIN_EQUIPOS -> {
				if (this.selector != null) panel().dibujar(g, datos, cx, cy, cw, ch, -10000, -10000);   // con la ventanita abierta, lo de atras no reacciona
				else panel().dibujar(g, datos, cx, cy, cw, ch, mx, my);
			}
		}
		// boton ADMIN en el marco (arriba a la derecha): solo lo ven los editores
		if (datos.editor) {
			String at = "ADMIN";
			int aw = this.font.width(at) + 8, ax = x0 + w - aw - 12, ay = y0 + 2;
			boolean sobre = mx >= ax && my >= ay && mx < ax + aw && my < ay + 11;
			g.fill(ax, ay, ax + aw, ay + 11, admin ? AMARILLO_ADMIN : sobre ? 0xFF8A948E : M_BORDE);
			g.fill(ax + 1, ay + 1, ax + aw - 1, ay + 10, admin ? 0xFF3A3214 : 0xFF14181A);
			g.drawString(this.font, at, ax + 4, ay + 2, admin ? AMARILLO_ADMIN : sobre ? TEXTO : GRIS_TEXTO, false);
			this.zonas.add(new Zona(ax, ay, aw, 11, () -> {
				if (panel() != null) panel().guardarSiHaceFalta();
				admin = !admin;
				this.seccion = admin ? Seccion.ANUNCIOS : Seccion.INICIO;
			}));
		}
		// debajo de la tablet, chiquito: las versiones (o el aviso de que son datos de ejemplo)
		{
			String mod = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("tod5").map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?");
			boolean real = datos.esDelServer();
			String vt = real ? "mod " + mod + " · plugin " + (datos.plugin.isEmpty() ? "viejo" : datos.plugin)
				: "mod " + mod + " · DATOS DE EJEMPLO: el server no mandó nada (¿falta el plugin de TOD5 o es viejo?)";
			g.pose().pushMatrix(); g.pose().translate(x0 + 6, y0 + h + 3); g.pose().scale(0.75f, 0.75f);
			g.drawString(this.font, vt, 0, 0, real ? 0xFF9AA39E : 0xFFFFD24A, true);
			g.pose().popMatrix();
		}
		if (this.selector != null) { this.zonas.clear(); this.selector.dibujar(g, this, sx, sy, sw, sh, mx, my); encima = null; }
		super.render(g, mx, my, a);
		if (encima != null) g.setTooltipForNextFrame(Component.literal(this.encimaTexto != null ? this.encimaTexto : encima.nombre), mx, my);
	}

	private String encimaTexto;
	private static final int BORDE = 9, BORDE_ARRIBA = 14;

	/** Verde que late despacito (para las luces del marco). */
	private static int pulso(float t, float min, float max) {
		float k = min + (max - min) * (0.5f + 0.5f * (float) Math.sin(t * 2.2));
		int gr = (int) ((admin ? 0xD2 : 0xFF) * k), r = (int) ((admin ? 0xFF : 0x55) * k), b = (int) ((admin ? 0x3C : 0x55) * k);
		return 0xFF000000 | (r << 16) | (gr << 8) | b;
	}

	/** El marco de metal: bisel, esquinas cortadas, tornillos, muesca arriba con el dia y luces verdes. */
	private void marco(GuiGraphics g, float t) {
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
		for (int k = 0; k < 3; k++) { led(g, x + 14 + k * 4, y + 6, 3, 2, t, k * 0.4f); if (!datos.editor) led(g, x2 - 26 + k * 4, y + 6, 3, 2, t, k * 0.4f + 1); }   // a la derecha va el boton ADMIN
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
		g.drawString(this.font, dia, -this.font.width(dia) / 2, 0, pulso(t, 0.8f, 1f), true);
		g.pose().popMatrix();
		// luces abajo al centro
		int bw = 60, bx = (x + x2) / 2 - bw / 2;
		g.fill(bx - 1, y2 - 6, bx + bw + 1, y2 - 2, M_BORDE);
		for (int i = 0; i < bw; i += 4) led(g, bx + i + 1, y2 - 5, 2, 2, t, i * 0.05f);
	}

	private static void led(GuiGraphics g, int x, int y, int lw, int lh, float t, float fase) {
		g.fill(x, y, x + lw, y + lh, pulso(t + fase, 0.35f, 1f));
	}

	private void irA(Seccion s) { this.seccion = s; this.dia = datos.hoy; this.scrollMisiones = 0; }

	void titulo(GuiGraphics g, String t, int x, int y) {
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(1.25f, 1.25f);
		g.drawString(this.font, t, 0, 0, TEXTO, false);
		g.pose().popMatrix();
	}

	/** Fila de dias: los ultimos 7, arranca en hoy. Devuelve la altura usada. */
	private int filaDeDias(GuiGraphics g, int x, int y, int maxX, int mx, int my, boolean puntitos) {
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
	private void inicio(GuiGraphics g, int x, int y, int cw, int mx, int my) {
		titulo(g, "Hoy, día " + datos.hoy, x, y);
		int colW = (cw - 6) / 2, alto = 46;
		String[][] tarjetas = datos.resumen();
		Seccion[] destino = { Seccion.NOVEDADES, Seccion.MISIONES, Seccion.NOVEDADES, datos.equipo != null ? Seccion.EQUIPO : Seccion.CRAFTEOS };
		for (int i = 0; i < 4; i++) {
			int tx = x + (i % 2) * (colW + 6), ty = y + 18 + (i / 2) * (alto + 6);
			tarjeta(g, tx, ty, colW, alto);
			boolean encima = mx >= tx && my >= ty && mx < tx + colW && my < ty + alto;
			if (encima) g.fill(tx + 1, ty + 1, tx + colW - 1, ty + alto - 1, 0x33FFFFFF);
			g.drawString(this.font, tarjetas[i][0], tx + 6, ty + 5, 0xFFE0E0E0, true);
			g.pose().pushMatrix(); g.pose().translate(tx + 6, ty + 16); g.pose().scale(1.5f, 1.5f);
			g.drawString(this.font, tarjetas[i][1], 0, 0, VERDE, true);
			g.pose().popMatrix();
			String linea = tarjetas[i][2];
			if (this.font.width(linea) > colW - 12) linea = this.font.plainSubstrByWidth(linea, colW - 12 - this.font.width("...")) + "...";
			g.drawString(this.font, linea, tx + 6, ty + 34, BLANCO, true);
			final Seccion d = destino[i]; final boolean cambios = i == 2;
			this.zonas.add(new Zona(tx, ty, colW, alto, () -> { irA(d); if (cambios) this.filtro = "cambio"; }));
		}
	}

	// ---------- NOVEDADES ----------
	private void novedades(GuiGraphics g, int x, int y, int cw, int ch, int mx, int my) {
		titulo(g, "Novedades", x, y);
		int fx = x + cw;
		String[][] fs = { { "cambio", "Cambios" }, { "aviso", "Avisos" }, { "todo", "Todo" } };
		for (String[] f : fs) { int bw = this.font.width(f[1]) + 10; fx -= bw + 2; boton(g, f[1], fx, y - 1, this.filtro.equals(f[0]), mx, my, () -> this.filtro = f[0]); }
		int yy = y + 16;
		yy += filaDeDias(g, x, yy, x + cw, mx, my, true) + 4;
		List<TabletDatos.Novedad> lista = datos.novedadesDe(this.dia, this.filtro);
		if (lista.isEmpty()) { g.drawString(this.font, "Ese día no hubo novedades de este tipo.", x, yy + 6, GRIS_TEXTO, false); return; }
		for (TabletDatos.Novedad n : lista) {
			boolean conTitulo = n.titulo() != null;
			List<FormattedCharSequence> renglones = this.font.split(Component.literal(n.texto()), cw - 66);
			int alto = (conTitulo ? 16 : 6) + renglones.size() * 10;
			if (yy + alto > y + ch) break;
			tarjeta(g, x, yy, cw, alto);
			int[] c = colorDe(n.tipo());
			String et = etiquetaDe(n.tipo());
			relieve(g, x + 4, yy + 4, 52, 11, c[1], c[2], c[3]);
			g.drawString(this.font, et, x + 4 + (52 - this.font.width(et)) / 2, yy + 6, c[0], true);
			if (n.nuevo()) g.fill(x + cw - 6, yy + 3, x + cw - 3, yy + 6, VERDE);
			int ry = yy + 5;
			if (conTitulo) { g.drawString(this.font, n.titulo(), x + 62, ry, c[0], true); ry += 11; }
			for (FormattedCharSequence r : renglones) { g.drawString(this.font, r, x + 62, ry, 0xFFECECEC, true); ry += 10; }
			Vistos.marcar("n:" + n.id());   // ya la vio
			yy += alto + 3;
		}
	}

	/** Color de cada tipo de novedad (los mismos de la ruleta): {texto, fondo, luz, sombra}. */
	static int[] colorDe(String tipo) {
		return switch (tipo) {
			case "cambio" -> new int[] { 0xFFFFB050, 0xFF5A3A0A, 0xFF7A5A2A, 0xFF3A2000 };
			case "mob" -> new int[] { 0xFFFFE63C, 0xFF5A520A, 0xFF7A722A, 0xFF3A3200 };
			case "dungeon" -> new int[] { 0xFF7FA0FF, 0xFF1A2A6A, 0xFF3A4A8A, 0xFF0A1A4A };
			case "diaria" -> new int[] { 0xFF55FF55, 0xFF1F5A1F, 0xFF3F7A3F, 0xFF0F3A0F };
			case "sancion" -> new int[] { 0xFFC88CFF, 0xFF3A1A5A, 0xFF5A3A7A, 0xFF22083A };
			case "cuy" -> new int[] { 0xFFFF8CD2, 0xFF5A1A44, 0xFF7A3A64, 0xFF3A0828 };
			case "truco" -> new int[] { 0xFFFF5A5A, 0xFF5A1414, 0xFF7A3434, 0xFF3A0606 };
			default -> new int[] { 0xFF55FFFF, 0xFF0F4A4A, 0xFF2F6A6A, 0xFF052A2A };   // aviso
		};
	}
	static String etiquetaDe(String tipo) {
		return switch (tipo) { case "cambio" -> "CAMBIO"; case "mob" -> "MOB"; case "dungeon" -> "DUNGEON"; case "diaria" -> "MISIÓN"; case "sancion" -> "SANCIÓN"; case "cuy" -> "MOMENTO CUY"; case "truco" -> "MOMENTO TRUCO"; default -> "AVISO"; };
	}

	// ---------- MISIONES: las diarias del dia arriba, las opcionales abajo ----------
	private final Set<String> abiertas = new HashSet<>();   // misiones con las recompensas desplegadas
	private int scrollMisiones;
	static final int CELESTE = 0xFF55FFFF;

	private void misiones(GuiGraphics g, int x, int y, int cw, int ch, int mx, int my) {
		titulo(g, "Misiones", x, y);
		int arriba = y + 16;
		arriba += filaDeDias(g, x, arriba, x + cw, mx, my, false) + 4;
		int abajo = y + ch;
		g.enableScissor(x, arriba, x + cw, abajo);
		int yy = arriba - this.scrollMisiones;
		g.drawString(this.font, "MISIÓN DIARIA", x, yy, GRIS_TEXTO, false);
		yy += 11;
		List<TabletDatos.Mision> diarias = datos.diariasDe(this.dia);
		if (diarias.isEmpty()) { g.drawString(this.font, "Ese día no hubo misión diaria.", x, yy + 2, GRIS_TEXTO, false); yy += 14; }
		for (TabletDatos.Mision m : diarias) yy += tarjetaMision(g, m, x, yy, cw, arriba, abajo, mx, my) + 5;
		yy += 2;
		g.drawString(this.font, "MISIONES OPCIONALES", x, yy, GRIS_TEXTO, false);
		yy += 11;
		List<TabletDatos.Mision> ops = datos.opcionales();
		if (ops.isEmpty()) { g.drawString(this.font, "Todavía no hay misiones opcionales.", x, yy + 2, GRIS_TEXTO, false); yy += 14; }
		for (TabletDatos.Mision m : ops) yy += tarjetaMision(g, m, x, yy, cw, arriba, abajo, mx, my) + 5;
		g.disableScissor();
		this.scrollMisiones = Math.max(0, Math.min(this.scrollMisiones, yy + this.scrollMisiones - abajo));
	}

	/** Una mision: titulo (y "DE EQUIPO" si lo es), barra, objetivos, recompensas y el boton de reclamar. Devuelve el alto. */
	private int tarjetaMision(GuiGraphics g, TabletDatos.Mision m, int x, int yy, int cw, int arriba, int abajo, int mx, int my) {
		int color = m.diaria() ? VERDE : CELESTE;
		int extra = this.abiertas.contains(m.id()) ? altoRecompensas(m, cw) : 0;
		int alto = 31 + m.objetivos().size() * 10 + 3 + extra;
		boolean visible = yy + alto > arriba && yy < abajo;
		tarjeta(g, x, yy, cw, alto);
		g.fill(x + 1, yy + 1, x + 3, yy + alto - 1, color);
		String tit = cortar(this.font, m.titulo(), cw - 175);
		g.drawString(this.font, tit, x + 7, yy + 5, BLANCO, true);
		if (m.equipo()) {   // solo se aclara cuando es de equipo
			int ex = x + 7 + this.font.width(tit) + 6, ew = this.font.width("DE EQUIPO") + 6;
			g.fill(ex, yy + 3, ex + ew, yy + 14, 0xFFC8A0FF);
			g.fill(ex + 1, yy + 4, ex + ew - 1, yy + 13, 0xFF241A33);
			g.drawString(this.font, "DE EQUIPO", ex + 3, yy + 5, 0xFFC8A0FF, false);
			if (visible && mx >= ex && my >= yy + 3 && mx < ex + ew && my < yy + 14) g.setTooltipForNextFrame(Component.literal("El progreso es de todo tu equipo. Cada uno reclama su recompensa."), mx, my);
		}
		// barra
		int barW = cw - 150, hecho = m.hecho(), total = m.total();
		g.fill(x + 7, yy + 18, x + 7 + barW, yy + 24, NEGRO);
		g.fill(x + 8, yy + 19, x + 6 + barW, yy + 23, SLOT_OSCURO);
		g.fill(x + 8, yy + 19, x + 8 + (int) ((barW - 2) * Math.min(1f, hecho / (float) total)), yy + 23, color);
		g.drawString(this.font, hecho + " / " + total, x + 11 + barW, yy + 17, BLANCO, true);
		// a la derecha: recompensas y reclamar (solo si la parte de arriba de la tarjeta se ve entera)
		if (yy + 2 >= arriba && yy + 30 <= abajo) {
			if (!m.recompensas().isEmpty()) botonRecompensas(g, m, x + cw - 4, yy + 3, mx, my);
			String est = datos.esDelServer() ? m.estado() : this.reclamadas.contains(m.id()) ? "reclamada" : m.estado();
			int rx = x + cw - 4;
			switch (est) {
				case "reclamada" -> g.drawString(this.font, "Reclamada", rx - this.font.width("Reclamada"), yy + 19, color, true);
				case "vencida" -> g.drawString(this.font, "Vencida", rx - this.font.width("Vencida"), yy + 19, GRIS_TEXTO, false);
				case "completa", "resto" -> {
					String bt = est.equals("resto") ? "Reclamar el resto" : "Reclamar";
					boton(g, bt, rx - this.font.width(bt) - 10, yy + 17, true, mx, my, () -> reclamarMision(m));
				}
				default -> {}
			}
		}
		// objetivos
		int oy = yy + 29;
		for (TabletDatos.Objetivo o : m.objetivos()) {
			boolean ok = o.cumplido();
			g.drawString(this.font, ok ? "✔" : "•", x + 8, oy, ok ? color : GRIS_TEXTO, false);
			String cuenta = o.cantidad() > 1 ? Math.min(o.hecho(), o.cantidad()) + "/" + o.cantidad() : "";
			g.drawString(this.font, this.font.substrByWidth(o.frase(), barW - 14).getString(), x + 18, oy, ok ? GRIS_TEXTO : TEXTO, false);
			if (!cuenta.isEmpty()) g.drawString(this.font, cuenta, x + 11 + barW, oy, ok ? color : TEXTO, false);   // en la columna del total (no pisa el boton)
			oy += 10;
		}
		if (extra > 0) dibujarRecompensas(g, m, x + 6, oy + 2, cw - 12, mx, my);
		return alto;
	}
	private void reclamarMision(TabletDatos.Mision m) {
		if (!datos.esDelServer()) { this.reclamadas.add(m.id()); return; }   // el ejemplo (sin server)
		com.google.gson.JsonObject o = new com.google.gson.JsonObject();
		o.addProperty("a", "mision_reclamar");
		com.google.gson.JsonArray ids = new com.google.gson.JsonArray(); ids.add(m.id()); o.add("ids", ids);
		ar.tod5.menu.Conexion.accion(o.toString());
	}

	/** "Ver recompensas" / "Ocultar": despliega la cajita con todas las recompensas. x = borde derecho. */
	private void botonRecompensas(GuiGraphics g, TabletDatos.Mision m, int derecha, int y, int mx, int my) {
		boolean abierta = this.abiertas.contains(m.id());
		String t = abierta ? "Ocultar" : "Ver recompensas";
		int bw = this.font.width(t) + 10;
		boton(g, t, derecha - bw, y, abierta, mx, my, () -> { if (!this.abiertas.remove(m.id())) this.abiertas.add(m.id()); });
	}

	private static final int CASILLA = 18;
	private int porFila(int ancho) { return Math.max(1, ancho / (CASILLA + 1)); }
	private int altoRecompensas(TabletDatos.Mision m, int cw) {
		int filas = (m.recompensas().size() + porFila(cw - 12) - 1) / porFila(cw - 12);
		return 6 + filas * (CASILLA + 1);
	}
	/** Las recompensas como casillas de inventario (con cantidad y el nombre al pasar el mouse). */
	private void dibujarRecompensas(GuiGraphics g, TabletDatos.Mision m, int x, int y, int ancho, int mx, int my) {
		g.fill(x, y, x + ancho, y + 1, LINEA);
		int pf = porFila(ancho), i = 0;
		for (ItemStack st : m.recompensas()) {
			int sx = x + (i % pf) * (CASILLA + 1), sy = y + 4 + (i / pf) * (CASILLA + 1);
			hundido(g, sx, sy, CASILLA, CASILLA);
			g.renderItem(st, sx + 1, sy + 1);
			if (st.getCount() > 1) g.renderItemDecorations(this.font, st, sx + 1, sy + 1, String.valueOf(st.getCount()));
			if (mx >= sx && my >= sy && mx < sx + CASILLA && my < sy + CASILLA)
				g.setTooltipForNextFrame(Component.literal(st.getCount() + " × ").append(st.getHoverName()), mx, my);
			i++;
		}
	}

	// ---------- RECOMPENSAS: la bandeja (zonas de monedas, desafios...). Las misiones tienen su propio Reclamar ----------
	private static ItemStack moneda;
	private static ItemStack moneda() { if (moneda == null) moneda = ar.tod5.menu.ItemsTOD.moneda("trucoin"); return moneda; }
	private static final int PREMIO = TabletHud.COLOR_PREMIO;

	private void recompensas(GuiGraphics g, int x, int y, int cw, int ch, int mx, int my) {
		titulo(g, "Recompensas", x, y);
		g.drawString(this.font, "Reclamalas cuando quieras: lo que no te entre queda guardado acá.", x, y + 14, GRIS_TEXTO, false);
		int yy = y + 28, fin = y + ch, mostrados = 0;
		for (TabletDatos.Paquete r : datos.recompensas) {
			int pf = porFila(cw - 12), filas = Math.max(1, (r.items().size() + pf - 1) / pf);
			int alto = 22 + filas * (CASILLA + 1) + 4;
			if (yy + alto > fin && mostrados > 0) {
				g.drawString(this.font, "y " + (datos.recompensas.size() - mostrados) + " más (reclamá las de arriba)", x + 2, yy + 2, GRIS_TEXTO, false);
				break;
			}
			g.fill(x, yy, x + cw, yy + alto, PREMIO);
			g.fill(x + 1, yy + 1, x + cw - 1, yy + alto - 1, 0xFF0F1B16);
			g.fill(x + 1, yy + 1, x + cw - 1, yy + 2, 0xFF1C3328);
			g.renderItem(moneda(), x + 4, yy + 3);
			g.drawString(this.font, cortar(this.font, r.titulo(), cw - 190), x + 23, yy + 7, PREMIO, true);
			int bx = x + cw - 5;
			String tTodo = "Reclamar todo", tAbrir = "Abrir";
			bx -= this.font.width(tTodo) + 10;
			final String id = r.id();
			boton(g, tTodo, bx, yy + 4, false, mx, my, () -> accionRecompensa("recompensa_todo", id, false));
			bx -= this.font.width(tAbrir) + 10 + 3;
			boton(g, tAbrir, bx, yy + 4, false, mx, my, () -> accionRecompensa("recompensa_abrir", id, true));
			casillas(g, r.items(), x + 6, yy + 21, cw - 12, mx, my);
			yy += alto + 5; mostrados++;
		}
	}
	private void accionRecompensa(String a, String id, boolean cerrar) {
		com.google.gson.JsonObject o = new com.google.gson.JsonObject();
		o.addProperty("a", a);
		com.google.gson.JsonArray ids = new com.google.gson.JsonArray(); ids.add(id); o.add("ids", ids);
		ar.tod5.menu.Conexion.accion(o.toString());
		if (cerrar) this.onClose();   // el server abre el cofre
	}
	/** Items como casillas de inventario (con cantidad y el nombre al pasar el mouse). */
	private void casillas(GuiGraphics g, List<ItemStack> items, int x, int y, int ancho, int mx, int my) {
		int pf = porFila(ancho), i = 0;
		for (ItemStack st : items) {
			int sx = x + (i % pf) * (CASILLA + 1), sy = y + (i / pf) * (CASILLA + 1);
			hundido(g, sx, sy, CASILLA, CASILLA);
			g.renderItem(st, sx + 1, sy + 1);
			if (st.getCount() > 1) g.renderItemDecorations(this.font, st, sx + 1, sy + 1, String.valueOf(st.getCount()));
			if (mx >= sx && my >= sy && mx < sx + CASILLA && my < sy + CASILLA)
				g.setTooltipForNextFrame(Component.literal(st.getCount() + " × ").append(st.getHoverName()), mx, my);
			i++;
		}
	}

	// ---------- CRAFTEOS: los items como en el creativo (solo lo que salio; los que tienen sombra, como silueta) ----------
	private final Map<Identifier, Boolean> tieneTextura = new HashMap<>();

	private void crafteos(GuiGraphics g, int x, int y, int cw, int mx, int my) {
		titulo(g, "Crafteos", x, y);
		// categorias: solo las que tienen algo (las crea el admin)
		List<String> cats = datos.categorias();
		if (!this.categoria.equals("todos") && !cats.contains(this.categoria)) this.categoria = "todos";
		int fx = x + 70;
		if (cats.size() > 1) {
			fx += boton(g, "Todos", fx, y - 1, this.categoria.equals("todos"), mx, my, () -> { this.categoria = "todos"; this.itemElegido = 0; }) + 2;
			for (String c : cats) {
				if (fx + this.font.width(c) + 10 > x + cw) break;
				fx += boton(g, c, fx, y - 1, this.categoria.equals(c), mx, my, () -> { this.categoria = c; this.itemElegido = 0; }) + 2;
			}
		}

		List<TabletDatos.Crafteo> visibles = datos.crafteosDe(this.categoria);
		int gx = x, gy = y + 17, cols = 9, filas = 4, t = 18;
		g.fill(gx - 1, gy - 1, gx + cols * t + 1, gy + filas * t + 1, SLOT_OSCURO);
		TabletDatos.Crafteo hover = null;
		for (int i = 0; i < cols * filas; i++) {
			int sx = gx + (i % cols) * t, sy = gy + (i / cols) * t;
			hundido(g, sx, sy, t, t);
			if (i >= visibles.size()) continue;
			TabletDatos.Crafteo c = visibles.get(i);
			boolean sombra = !c.activo();          // lo ve como silueta negra, sin nombre
			if (i == this.itemElegido && !sombra) { g.fill(sx, sy, sx + t, sy + t, VERDE); g.fill(sx + 1, sy + 1, sx + t - 1, sy + t - 1, 0xFF1F3A27); }
			if (sombra) silueta(g, c.icono(), sx + 1, sy + 1);
			else g.renderItem(c.icono(), sx + 1, sy + 1);
			if (c.nuevo()) g.fill(sx + t - 4, sy + 1, sx + t - 1, sy + 4, VERDE);
			boolean encima = mx >= sx && my >= sy && mx < sx + t && my < sy + t;
			if (sombra) continue;                  // la sombra no dice nada ni se puede tocar
			if (encima) { g.fill(sx + 1, sy + 1, sx + t - 1, sy + t - 1, 0x50FFFFFF); hover = c; Vistos.marcar("c:" + c.id()); }
			final int k = i;
			this.zonas.add(new Zona(sx, sy, t, t, () -> { this.itemElegido = k; Vistos.marcar("c:" + c.id()); }));
		}
		if (visibles.isEmpty()) g.drawString(this.font, "Todavía no hay crafteos.", gx + 4, gy + filas * t + 6, GRIS_TEXTO, false);

		// el crafteo elegido: la receta tal cual, en la estacion que corresponde
		TabletDatos.Crafteo c = visibles.isEmpty() ? null : visibles.get(Math.min(this.itemElegido, visibles.size() - 1));
		if (c != null && c.activo()) {
			int rx = gx + cols * t + 14, ry = gy;
			g.drawString(this.font, c.nombre(), rx, ry, TEXTO, false);
			g.renderItem(VistaReceta.iconoTipo(c.tipo()), x + cw - 16, ry - 4);
			if (mx >= x + cw - 16 && my >= ry - 4 && mx < x + cw && my < ry + 12) g.setTooltipForNextFrame(Component.literal(VistaReceta.nombreTipo(c.tipo())), mx, my);
			int alto = VistaReceta.dibujar(g, c, rx, ry + 14, mx, my);
			int ny = ry + 14 + alto + 6;
			g.drawString(this.font, VistaReceta.nombreTipo(c.tipo()) + " · desde el día " + c.dia(), rx, ny, GRIS_TEXTO, false);
			if (c.tipo().equals("mesa") && !c.forma()) { ny += 10; g.drawString(this.font, "En cualquier orden", rx, ny, GRIS_TEXTO, false); }
			for (FormattedCharSequence r : this.font.split(Component.literal(c.nota()), x + cw - rx)) { ny += 10; g.drawString(this.font, r, rx, ny, TEXTO, false); }
		}
		if (hover != null) {
			List<FormattedCharSequence> tip = new ArrayList<>();
			tip.add(Component.literal(hover.nombre()).getVisualOrderText());
			if (hover.nuevo()) tip.add(Component.literal("§aNuevo · día " + hover.dia()).getVisualOrderText());
			g.setTooltipForNextFrame(tip, mx, my);
		}
	}

	/** La silueta negra de un item (para los crafteos que todavia no salieron, si tienen sombra). */
	private void silueta(GuiGraphics g, ItemStack st, int x, int y) {
		Identifier key = BuiltInRegistries.ITEM.getKey(st.getItem());
		Identifier tex = Identifier.fromNamespaceAndPath(key.getNamespace(), "textures/item/" + key.getPath() + ".png");
		boolean hay = this.tieneTextura.computeIfAbsent(tex, k -> this.minecraft.getResourceManager().getResource(k).isPresent());
		if (hay) g.blit(RenderPipelines.GUI_TEXTURED, tex, x, y, 0, 0, 16, 16, 16, 16, 16, 16, 0xFF000000);
		else { g.fill(x + 2, y + 2, x + 14, y + 14, 0xFF000000); g.drawString(this.font, "?", x + 6, y + 4, 0xFF3A3A3A, false); }
	}

	/** Lo que hace el editor: se lo mando al server, que lo aplica y le manda a todos la tablet actualizada. */
	private void enviar(String accion, List<String> ids, Boolean v) {
		com.google.gson.JsonObject o = new com.google.gson.JsonObject();
		o.addProperty("a", accion);
		com.google.gson.JsonArray a = new com.google.gson.JsonArray();
		ids.forEach(a::add);
		o.add("ids", a);
		if (v != null) o.addProperty("v", v);
		ar.tod5.menu.Conexion.accion(o.toString());
	}

	// ---------- EQUIPO: ver PanelEquipo ----------
}

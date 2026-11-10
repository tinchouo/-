package ar.tod5.menu;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3x2fStack;

/**
 * El fondo del menu, dibujado plano (sin el ojo de pez del panorama de Minecraft) y animado:
 *   - nebulosa verde que se desliza despacio de costado
 *   - dos capas de estrellas a distinta velocidad (profundidad) que titilan
 *   - el agujero negro con el disco girando y el brillo latiendo
 *   - cada tanto una estrella fugaz verde
 * Todas las capas dan la vuelta exacta, no se nota donde empiezan.
 */
public final class Fondo {
	private static Identifier tex(String n) { return Identifier.fromNamespaceAndPath(TOD5Client.MOD_ID, "textures/gui/fondo/" + n + ".png"); }
	private static final Identifier NEBULOSA = tex("nebulosa"), ESTRELLAS_A = tex("estrellas_a"), ESTRELLAS_B = tex("estrellas_b"),
		AGUJERO = tex("agujero"), FUGAZ = tex("fugaz");

	private static final int NEB_W = 3072, NEB_H = 864, EST_W = 2048, EST_H = 576;
	private static final int AG_W = 640, AG_H = 256, AG_COLS = 6, AG_CUADROS = 48;
	private static final float AG_RADIO = 0.165f;     // radio del agujero respecto del alto del cuadro
	private static final float AG_FPS = 12f;

	// velocidades en pixeles de textura por segundo
	private static final float V_NEB = 5f, V_EST_A = 9f, V_EST_B = 20f;

	private Fondo() {}

	/** Reloj propio del fondo: sigue corriendo igual al pasar de un menu a otro (no arranca de cero). */
	private static long inicio = 0;
	public static float tiempo() {
		long ahora = net.minecraft.util.Util.getMillis();
		if (inicio == 0) inicio = ahora;
		return (ahora - inicio) / 1000f;
	}

	/** El mismo fondo para los otros menus (Opciones, etc.), sin el agujero negro. */
	public static void dibujarSinAgujero(GuiGraphicsExtractor g, int ancho, int alto) {
		float t = tiempo();
		capas(g, ancho, alto, t);
		g.fill(0, 0, ancho, alto, 0x8C000000);   // mas oscuro, para que se lean bien los botones
	}

	private static void capas(GuiGraphicsExtractor g, int ancho, int alto, float t) {
		g.fill(0, 0, ancho, alto, 0xFF000000);
		capa(g, NEBULOSA, NEB_W, NEB_H, t * V_NEB, ancho, alto, 0xFFFFFFFF);
		capa(g, ESTRELLAS_A, EST_W, EST_H, t * V_EST_A, ancho, alto, argb(0.75f + 0.25f * (float) Math.sin(t * 0.9), 0xFFFFFF));
		capa(g, ESTRELLAS_B, EST_W, EST_H, t * V_EST_B + 700, ancho, alto, argb(0.8f + 0.2f * (float) Math.sin(t * 1.7 + 1), 0xFFFFFF));
		fugaz(g, ancho, alto, t);
	}

	private static int argb(float a, int rgb) { return ((int) (Math.max(0, Math.min(1, a)) * 255) << 24) | (rgb & 0xFFFFFF); }

	/**
	 * Una capa que se repite de costado, escalada para ocupar todo el alto de la pantalla.
	 * Se dibujan copias enteras de la imagen corridas con posicion con decimales: el tamano no cambia nunca
	 * de un cuadro al otro, asi el movimiento es parejo (antes el redondeo hacia temblar las estrellas).
	 */
	private static void capa(GuiGraphicsExtractor g, Identifier t, int texW, int texH, float desplazamiento, int ancho, int alto, int color) {
		float s = alto / (float) texH;
		int w = Math.round(texW * s);                 // ancho de una copia en pantalla (fijo)
		float x0 = -((desplazamiento % texW) / texW) * w;
		Matrix3x2fStack p = g.pose();
		p.pushMatrix();
		p.translate(x0, 0);
		for (int x = 0; x0 + x < ancho; x += w)
			g.blit(RenderPipelines.GUI_TEXTURED, t, x, 0, 0, 0, w, alto, texW, texH, texW, texH, color);
		p.popMatrix();
	}

	/**
	 * @param cx,cy  centro del agujero negro en la pantalla
	 * @param diametro  diametro del agujero (sin el disco) en pixeles de pantalla
	 */
	public static void dibujar(GuiGraphicsExtractor g, int ancho, int alto, int cx, int cy, float diametro) {
		capas(g, ancho, alto, tiempo());
		agujero(g, cx, cy, diametro);
	}

	/** Solo el cielo (nebulosa, estrellas, fugaz), sin oscurecer ni agujero. */
	public static void cielo(GuiGraphicsExtractor g, int ancho, int alto) { capas(g, ancho, alto, tiempo()); }

	/** Solo el agujero negro, en cualquier tamaño (para el secreto lo agranda hasta tapar todo). */
	public static void agujero(GuiGraphicsExtractor g, int cx, int cy, float diametro) {
		float t = tiempo();
		// agujero negro: el disco gira (48 cuadros) y el brillo late despacio
		int ah = Math.round(diametro / (2 * AG_RADIO)), aw = ah * AG_W / AG_H;
		int cuadro = (int) (t * AG_FPS) % AG_CUADROS;
		float u = (cuadro % AG_COLS) * AG_W, v = (cuadro / AG_COLS) * AG_H;
		int texW = AG_W * AG_COLS, texH = AG_H * (AG_CUADROS / AG_COLS);
		g.blit(RenderPipelines.GUI_TEXTURED, AGUJERO, cx - aw / 2, cy - ah / 2, u, v, aw, ah, AG_W, AG_H, texW, texH, 0xFFFFFFFF);
		float late = 0.12f + 0.12f * (0.5f + 0.5f * (float) Math.sin(t * 1.4));
		g.blit(RenderPipelines.GUI_TEXTURED, AGUJERO, cx - aw / 2, cy - ah / 2, u, v, aw, ah, AG_W, AG_H, texW, texH, argb(late, 0xFFFFFF));
	}

	/** Cada 13 segundos, una estrella fugaz en un lugar distinto. */
	private static void fugaz(GuiGraphicsExtractor g, int ancho, int alto, float t) {
		final float ciclo = 13f, dura = 1.1f;
		int n = (int) (t / ciclo);
		float f = (t - n * ciclo - 4f) / dura;   // arranca 4 s despues de empezar cada ciclo
		if (f < 0 || f > 1) return;
		long h = n * 2654435761L;
		float x0 = ancho * (0.25f + 0.6f * ((h >>> 3) % 1000) / 1000f);
		float y0 = alto * (0.05f + 0.3f * ((h >>> 13) % 1000) / 1000f);
		float ang = (float) Math.toRadians(150 + ((h >>> 23) % 25));   // va hacia abajo a la izquierda
		float largo = alto * 0.5f, recorrido = alto * 0.55f;
		float x = x0 + (float) Math.cos(ang) * recorrido * f, y = y0 + (float) Math.sin(ang) * recorrido * f;
		float alfa = (float) Math.sin(Math.PI * f);
		Matrix3x2fStack p = g.pose();
		p.pushMatrix();
		p.translate(x, y);
		p.rotate(ang);
		// la textura tiene la punta brillante a la derecha: la dibujo terminando en (0,0)
		int lw = Math.round(largo * 0.35f), lh = Math.max(2, Math.round(alto / 180f));
		g.blit(RenderPipelines.GUI_TEXTURED, FUGAZ, -lw, -lh / 2, 0, 0, lw, lh, 256, 16, 256, 16, argb(alfa, 0xFFFFFF));
		p.popMatrix();
	}
}

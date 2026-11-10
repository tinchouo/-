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

	private static int argb(float a, int rgb) { return ((int) (Math.max(0, Math.min(1, a)) * 255) << 24) | (rgb & 0xFFFFFF); }

	/** Una capa que se repite de costado, escalada para ocupar todo el alto de la pantalla. */
	private static void capa(GuiGraphicsExtractor g, Identifier t, int texW, int texH, float desplazamiento, int ancho, int alto, int color) {
		float s = alto / (float) texH;
		int visible = (int) Math.ceil(ancho / s) + 1;
		float u0 = desplazamiento % texW;
		int parte1 = (int) Math.min(visible, texW - (int) u0);
		int w1 = Math.round(parte1 * s);
		g.blit(RenderPipelines.GUI_TEXTURED, t, 0, 0, u0, 0, w1, alto, parte1, texH, texW, texH, color);
		if (parte1 < visible) {
			int parte2 = visible - parte1;
			g.blit(RenderPipelines.GUI_TEXTURED, t, w1, 0, 0, 0, Math.round(parte2 * s), alto, parte2, texH, texW, texH, color);
		}
	}

	/**
	 * @param cx,cy  centro del agujero negro en la pantalla
	 * @param diametro  diametro del agujero (sin el disco) en pixeles de pantalla
	 */
	public static void dibujar(GuiGraphicsExtractor g, int ancho, int alto, float t, int cx, int cy, float diametro) {
		g.fill(0, 0, ancho, alto, 0xFF000000);
		capa(g, NEBULOSA, NEB_W, NEB_H, t * V_NEB, ancho, alto, 0xFFFFFFFF);
		capa(g, ESTRELLAS_A, EST_W, EST_H, t * V_EST_A, ancho, alto, argb(0.75f + 0.25f * (float) Math.sin(t * 0.9), 0xFFFFFF));
		capa(g, ESTRELLAS_B, EST_W, EST_H, t * V_EST_B + 700, ancho, alto, argb(0.8f + 0.2f * (float) Math.sin(t * 1.7 + 1), 0xFFFFFF));

		fugaz(g, ancho, alto, t);

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

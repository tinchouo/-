package ar.tod5.menu;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;

/**
 * La ruleta de los anuncios (como la de DeDSafio, con la onda de TOD5).
 * El server avisa "salio el color X"; el mod la muestra:
 *   aparece -> se prenden los colores -> gira 5 s (la flecha choca cada rayita) -> se pasa un poquito y vuelve
 *   al medio del gajo -> el color se contagia gajo por gajo -> se va.
 * Recien cuando termina, el server manda el mensaje al chat.
 * Cada sonido es un archivo aparte (assets/tod5/sounds/ruleta_*.ogg) para poder cambiarlos.
 * ruleta_tic es UNA nota: el juego le cambia el tono en cada golpe.
 */
public final class Ruleta {
	/** Colores en el orden de la rueda: diaria, aviso, dungeon, sancion, cuy, truco, cambio, mob. */
	public static final int[] COLORES = { 0x55FF55, 0x55FFFF, 0x4678FF, 0xB45AFF, 0xFF78C8, 0xFF3C3C, 0xFF9600, 0xFFE63C };

	private static Identifier tex(String n) { return Identifier.fromNamespaceAndPath(TOD5Client.MOD_ID, "textures/gui/ruleta/" + n + ".png"); }
	private static final Identifier GAJO = tex("ruleta_gajo"), ARO = tex("ruleta_aro"), BLANCA = tex("ruleta_blanca"),
		FLECHA = tex("ruleta_flecha"), GEMA = tex("ruleta_gema"), GEMA_ORO = tex("ruleta_gema_oro"), LUZ = tex("ruleta_luz");

	// tiempos (segundos)
	private static final float T_AP = 0.32f, T_ON = 0.30f, T0 = T_AP + T_ON * 0.6f, T_GIRO = 5.2f, T_VUELTA = 0.7f;
	private static final float T_STOP = T0 + T_GIRO + T_VUELTA;
	private static final float PASO = 0.06f, T_PROP0 = T_STOP + 0.08f, T_PROP1 = T_PROP0 + 8 * PASO, T_HOLD1 = T_PROP1 + 0.35f;
	private static final float T_FUERA = 0.42f, T_FIN = T_HOLD1 + T_FUERA;
	/** Cuanto dura todo (el server espera esto antes de mandar el mensaje). */
	public static final float DURACION = T_FIN;

	/** Cada golpe de la flecha es un "plin" en una nota distinta (escala pentatonica), como en DeDSafio. */
	private static final float[] NOTAS = { 0.70f, 0.79f, 0.89f, 1.0f, 1.12f, 1.19f, 1.33f, 1.5f };
	/** Gira rapido y parejo casi todo el tiempo, y frena recien al final (como la de DeDSafio). */
	private static final float PAREJO = 0.68f;

	private static long inicio = 0;
	private static int sel;
	private static float centro, fin;
	private static int cruces;
	private static boolean sonoElige, sonoSeVa;

	private Ruleta() {}

	public static void registrar() {
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(TOD5Client.MOD_ID, "ruleta"), (g, dt) -> dibujar(g));
	}

	/** Arranca la ruleta: va a frenar en ese color (0..7). */
	public static void empezar(int color) {
		sel = Math.floorMod(color, 8);
		centro = 10 * 360 + (sel + 0.5f) * 45f;
		fin = centro + 15f;                      // se pasa 15 grados del medio y vuelve
		inicio = Util.getMillis();
		cruces = 0; sonoElige = false; sonoSeVa = false;
		sonido("ruleta_aparece", 1f, 0.8f);
	}

	private static float giro(float t) {
		if (t < T0) return 0;
		if (t < T0 + T_GIRO) {
			// velocidad pareja hasta PAREJO, despues frena (v = v0 * (1-u)^2)
			float p = (t - T0) / T_GIRO, v0 = fin / (PAREJO + (1 - PAREJO) / 3f);
			if (p < PAREJO) return v0 * p;
			float u = (p - PAREJO) / (1 - PAREJO);
			return v0 * (PAREJO + (1 - PAREJO) * (1 - (float) Math.pow(1 - u, 3)) / 3f);
		}
		float q = Math.min(1, (t - T0 - T_GIRO) / T_VUELTA);   // vuelve al medio del gajo, con un rebotito
		return centro + (fin - centro) * (float) (Math.exp(-q * 5) * Math.cos(q * Math.PI * 1.5));
	}

	/** Vueltas por segundo en ese momento (para cuanto se dobla la flecha). */
	private static float velocidad(float t) {
		float p = (t - T0) / T_GIRO, v0 = fin / (PAREJO + (1 - PAREJO) / 3f) / T_GIRO / 360f;
		if (p < PAREJO) return v0;
		float u = Math.min(1, (p - PAREJO) / (1 - PAREJO));
		return v0 * (1 - u) * (1 - u);
	}

	private static void sonido(String n, float tono, float vol) {
		Minecraft.getInstance().getSoundManager().play(new SimpleSoundInstance(Identifier.fromNamespaceAndPath(TOD5Client.MOD_ID, n),
			SoundSource.MASTER, vol, tono, RandomSource.create(), false, 0, SoundInstance.Attenuation.NONE, 0, 0, 0, true));
	}

	private static float clamp01(float v) { return v < 0 ? 0 : Math.min(1, v); }
	private static int mezclar(int a, int b, float k) {
		k = clamp01(k);
		int r = (int) (((a >> 16) & 255) * (1 - k) + ((b >> 16) & 255) * k);
		int gg = (int) (((a >> 8) & 255) * (1 - k) + ((b >> 8) & 255) * k);
		int bb = (int) ((a & 255) * (1 - k) + (b & 255) * k);
		return (r << 16) | (gg << 8) | bb;
	}
	private static int oscurecer(int c, float k) { return mezclar(c, 0, 1 - k); }
	private static int argb(float a, int rgb) { return ((int) (clamp01(a) * 255) << 24) | (rgb & 0xFFFFFF); }

	private static void dibujar(GuiGraphicsExtractor g) {
		if (inicio == 0) return;
		float t = (Util.getMillis() - inicio) / 1000f;
		if (t > T_FIN + 0.1f) { inicio = 0; return; }
		Minecraft mc = Minecraft.getInstance();
		int w = mc.getWindow().getGuiScaledWidth(), h = mc.getWindow().getGuiScaledHeight();
		float giro = giro(t);

		// ---- sonidos al ritmo de la animacion ----
		if (t > T0 && t < T0 + T_GIRO) {
			int c = (int) (giro / 45f), nuevos = 0;
			while (cruces < c && nuevos < 2) { cruces++; nuevos++; sonido("ruleta_tic", NOTAS[(int) (Math.random() * NOTAS.length)], 0.55f); }
			cruces = c;
		}
		if (!sonoElige && t >= T_STOP) { sonoElige = true; sonido("ruleta_elige", 1f, 0.9f); }
		if (!sonoSeVa && t >= T_HOLD1) { sonoSeVa = true; sonido("ruleta_se_va", 1f, 0.8f); }

		// ---- tamaño y blanco (aparecer con rebote, golpe al frenar, salir) ----
		float esc = 1, blanco = 0, alfa = 1;
		if (t < T_AP) { float p = t / T_AP; esc = Math.max(0.02f, 1 + 2.9f * (float) Math.pow(p - 1, 3) + 1.9f * (float) Math.pow(p - 1, 2)); blanco = 1 - p; }
		float ts = t - T_STOP;
		if (ts >= 0 && ts < 0.4f) esc *= 1 + 0.07f * (float) (Math.exp(-ts * 9) * Math.cos(ts * 30));
		float tf = t - T_HOLD1;
		if (tf > 0) {
			float p = Math.min(1, tf / T_FUERA);
			esc *= p < 0.35f ? 1 + 0.08f * (float) Math.sin(p / 0.35f * Math.PI / 2) : 1.08f * (1 - (float) Math.pow((p - 0.35f) / 0.65f, 1.6));
			blanco = Math.max(blanco, Math.min(1, p * 1.6f));
			alfa = p < 0.85f ? 1 : (1 - p) / 0.15f;
		}

		float d = Math.min(h * 0.45f, 170f) * esc;
		float cx = w / 2f, cy = h * 0.40f;
		var pose = g.pose();

		// punto de luz al aparecer / al irse
		float luz = t < T_AP * 0.8f ? 1 - t / (T_AP * 0.8f) : (tf > T_FUERA * 0.6f ? Math.max(0, 1 - Math.abs(t - T_FIN) / 0.12f) : 0);
		if (luz > 0) { int s = (int) (Math.min(h * 0.45f, 170f) * 0.45f); g.blit(RenderPipelines.GUI_TEXTURED, LUZ, (int) cx - s / 2, (int) cy - s / 2, 0, 0, s, s, 128, 128, 128, 128, argb(luz * 0.9f, 0xFFFFFF)); }
		if (d < 2 || alfa <= 0) return;
		int di = Math.round(d);

		// ---- los 8 gajos: se prenden, giran, y al final se contagia el color ----
		float flashFreno = ts >= 0 && ts < 0.12f ? (1 - ts / 0.12f) * 0.8f : 0;
		for (int k = 0; k < 8; k++) {
			float on = clamp01((t - T_AP * 0.7f - k * T_ON / 8) / 0.08f);
			int col = mezclar(0x343B37, COLORES[k], on);
			int orden = Math.floorMod(sel - k, 8);                      // el elegido primero, despues el de la izquierda...
			float tk = t - T_PROP0 - orden * PASO;
			float flash = flashFreno;
			if (tk >= 0) { col = oscurecer(COLORES[sel], k % 2 == 0 ? 0.82f : 1f); flash = Math.max(flash, Math.max(0, 1 - tk / 0.22f) * 0.55f); }
			col = mezclar(col, 0xFFFFFF, flash);
			pose.pushMatrix();
			pose.translate(cx, cy);
			pose.rotate((float) Math.toRadians(k * 45f - giro));
			g.blit(RenderPipelines.GUI_TEXTURED, GAJO, -di / 2, -di / 2, 0, 0, di, di, 512, 512, 512, 512, argb(alfa, col));
			pose.popMatrix();
		}
		// aro y centro (quietos)
		g.blit(RenderPipelines.GUI_TEXTURED, ARO, Math.round(cx - d / 2), Math.round(cy - d / 2), 0, 0, di, di, 512, 512, 512, 512, argb(alfa, 0xFFFFFF));

		// ---- la flecha: entra en los colores, se dobla en cada rayita, y su luz muestra el color de abajo ----
		boolean eligio = t >= T_STOP;   // hasta que elige, la luz es de oro verde; despues, del color que salio
		float desde = 9;
		if (t > T0 && t < T0 + T_GIRO) {   // tiempo desde la ultima rayita (aproximado con la velocidad)
			float fase = (giro % 45f) / 45f;
			float vel = Math.max(0.05f, velocidad(t));
			desde = fase * 45f / (vel * 360f);
		}
		float defl = 0;
		if (t > T0 && t < T0 + T_GIRO) {
			float vel = velocidad(t);
			defl = -28f * (float) Math.exp(-desde / 0.05f) * Math.min(1, 0.3f + vel * 0.35f);
		}
		if (ts >= 0 && ts < 0.5f) defl = 8f * (float) (Math.exp(-ts * 8) * Math.sin(ts * 40));
		float pop = ts >= 0 && ts < 0.6f ? (float) Math.exp(-ts * 7) : 0;
		float fw = d * 0.16f * (1 + 0.25f * pop), fh = fw * 2, piv = fh * 26f / 240f;
		float py = cy - d / 2 - d * 0.035f + piv;
		pose.pushMatrix();
		pose.translate(cx, py);
		pose.rotate((float) Math.toRadians(defl));
		g.blit(RenderPipelines.GUI_TEXTURED, FLECHA, Math.round(-fw / 2), Math.round(-piv), 0, 0, Math.round(fw), Math.round(fh), 120, 240, 120, 240, argb(alfa, 0xFFFFFF));
		int gs = Math.round(fw * 0.30f * (1 + 0.3f * pop));
		if (eligio) g.blit(RenderPipelines.GUI_TEXTURED, GEMA, -gs / 2, -gs / 2, 0, 0, gs, gs, 64, 64, 64, 64, argb(alfa, mezclar(COLORES[sel], 0xFFFFFF, pop)));
		else g.blit(RenderPipelines.GUI_TEXTURED, GEMA_ORO, -gs / 2, -gs / 2, 0, 0, gs, gs, 64, 64, 64, 64, argb(alfa, 0xFFFFFF));
		pose.popMatrix();

		// ---- blanco al aparecer / al irse ----
		if (blanco > 0) g.blit(RenderPipelines.GUI_TEXTURED, BLANCA, Math.round(cx - d / 2), Math.round(cy - d / 2), 0, 0, di, di, 512, 512, 512, 512, argb(blanco * alfa, 0xFFFFFF));
	}
}

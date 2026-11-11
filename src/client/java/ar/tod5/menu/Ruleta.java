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
 * La ruleta de los anuncios (diseño B: aro de hierro con runas "TRUCOFDEATH·REVENANTS·", flecha rustica con gema de oro verde).
 * El server avisa "salio el color X"; el mod la muestra:
 *   aparece (se pone blanca y se le van los colores encima) -> se llenan los colores -> arranca despacio y acelera ->
 *   gira rapido (se ve borrosa) -> frena, se pasa al color siguiente y vuelve al medio del elegido ->
 *   el color se contagia gajo por gajo -> se va.
 * La animacion es SIEMPRE igual (mismas vueltas, mismos tiempos); solo cambia de donde arranca, para frenar en el color pedido.
 * Por eso el sonido es UN solo archivo (assets/tod5/sounds/ruleta.ogg) que va justo con la animacion:
 * el arpegio sigue a la ruleta como una cinta (frena -> mas lento y grave, vuelve -> al reves).
 * La flecha es un resorte de verdad que empujan los separadores (cuando va rapido no llega a bajar y tiembla).
 */
public final class Ruleta {
	/** Colores en el orden de la rueda: diaria, aviso, dungeon, sancion, cuy, truco, cambio, mob. */
	public static final int[] COLORES = { 0x55FF55, 0x55FFFF, 0x4678FF, 0xB45AFF, 0xFF78C8, 0xFF3C3C, 0xFF9600, 0xFFE63C };

	private static Identifier tex(String n) { return Identifier.fromNamespaceAndPath(TOD5Client.MOD_ID, "textures/gui/ruleta/" + n + ".png"); }
	private static final Identifier GAJO = tex("ruleta_gajo"), ARO = tex("ruleta_aro"), CENTRO = tex("ruleta_centro"), BLANCA = tex("ruleta_blanca"),
		FLECHA = tex("ruleta_flecha"), FLECHA_BLANCA = tex("ruleta_flecha_blanca"), GEMA = tex("ruleta_gema_oro"), LUZ = tex("ruleta_luz");

	// medidas de las texturas (todo se dibuja en "pixeles de textura" y se escala)
	private static final int S = 700, FLW = 154, FLH = 238;
	private static final float GEMA_X = 77f, GEMA_Y = 73.78f, GEMA_TAM = 55f;
	/** Donde gira la flecha, medido desde el centro de la ruleta. */
	private static final float PIVOTE_Y = -89.6f + GEMA_Y - S / 2f;

	// tiempos (segundos) - iguales a los del video aprobado (B10)
	private static final float T_AP = 0.32f, T_ON = 0.48f, ON_FIN = T_AP * 0.35f + T_ON * 7 / 8 + 0.08f, T0 = ON_FIN + 0.30f;
	private static final float T_GIRO = 6.0f, T_VUELTA = 1.05f, T_STOP = T0 + T_GIRO + T_VUELTA;
	private static final float PASO = 0.06f, T_PROP0 = T_STOP + 0.08f, T_HOLD1 = T_PROP0 + 8 * PASO + 0.35f;
	private static final float T_FUERA = 0.42f, T_FIN = T_HOLD1 + T_FUERA;
	/** Cuanto dura todo (el server espera esto antes de mandar el mensaje al chat). */
	public static final float DURACION = T_FIN;

	// el giro: arranca muy lento, acelera, va parejo y frena; siempre la misma distancia
	private static final float T_ACC = 1.9f, PAREJO = 0.70f;
	private static final float PASADA = 16f;                      // cuanto se mete en el color siguiente antes de volver
	private static final float DIST = 11 * 360 + 22.5f + PASADA;  // de donde arranca hasta donde se pasa
	private static final int N = 20000;
	private static final float[] ANG = new float[N + 1], VEL = new float[N + 1];
	private static final float V0;                                 // grados por segundo a toda velocidad
	static {
		for (int i = 0; i <= N; i++) {
			float t = T_GIRO * i / N, p = t / T_GIRO, a = Math.min(1, t / T_ACC);
			float acc = Math.min(1, (float) Math.pow(a, 2.2) * (3 - 2 * a));
			float fr = p < PAREJO ? 1 : (float) Math.pow(1 - Math.min(1, (p - PAREJO) / (1 - PAREJO)), 2);
			VEL[i] = acc * fr;
		}
		double s = 0; ANG[0] = 0;
		for (int i = 1; i <= N; i++) { s += (VEL[i] + VEL[i - 1]) / 2.0 * (T_GIRO / N); ANG[i] = (float) s; }
		V0 = (float) (DIST / s);
		for (int i = 0; i <= N; i++) ANG[i] *= V0;
	}

	// la flecha: resorte empujado por los separadores
	private static final float W_PEG = 9f, DT = 1f / 4000f, WN = (float) (2 * Math.PI * 9), ZT = 0.32f;
	private static final int HIST = 160;
	private static float simT, fd, fv;
	private static int seg;
	private static boolean izqOk, derOk;
	private static final float[] hist = new float[HIST];
	private static int histPos;

	private static long inicio = 0;
	private static int sel;
	private static float g0, extra;
	/** Donde sale (se fija al empezar, para que no salte si cambias la opcion en el medio). */
	private static Ajustes.PosRuleta pos = Ajustes.PosRuleta.CENTRO;
	/** Los colores de mas que gira (para arrancar en otro color) se agregan en la parte rapida, entre estos dos momentos. */
	private static final float EXTRA_DESDE = T_ACC, EXTRA_HASTA = T_GIRO * PAREJO;

	private Ruleta() {}

	public static void registrar() {
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(TOD5Client.MOD_ID, "ruleta"), (g, dt) -> dibujar(g));
	}

	/** Arranca la ruleta: va a frenar en ese color (0..7). */
	public static void empezar(int color) {
		pos = Ajustes.ruleta();
		if (pos == Ajustes.PosRuleta.APAGADA) return;   // el jugador la apago: solo le llega el chat
		sel = Math.floorMod(color, 8);
		// arranca en un color al azar (que no sea el que va a salir); esos colores de mas los gira en la parte rapida,
		// asi el arranque y el frenado son siempre iguales y el sonido va justo
		int m = 1 + (int) (Math.random() * 7);
		extra = m * 45f;
		g0 = (sel - m + 0.5f) * 45f;
		simT = 0; fd = 0; fv = 0; seg = (int) Math.floor(g0 / 45f); izqOk = derOk = true;
		java.util.Arrays.fill(hist, 0); histPos = 0;
		inicio = Util.getMillis();
		Minecraft.getInstance().getSoundManager().play(new SimpleSoundInstance(Identifier.fromNamespaceAndPath(TOD5Client.MOD_ID, "ruleta"),
			SoundSource.MASTER, 1f, 1f, RandomSource.create(), false, 0, SoundInstance.Attenuation.NONE, 0, 0, 0, true));
	}

	private static float tabla(float[] a, float t) {
		float x = Math.max(0, Math.min(N, t / T_GIRO * N));
		int i = Math.min(N - 1, (int) x);
		return a[i] + (a[i + 1] - a[i]) * (x - i);
	}

	/** Angulo de la ruleta (grados) en el segundo t. */
	private static float giro(float t) {
		if (t < T0) return g0;
		if (t < T0 + T_GIRO) {
			float e = Math.max(0, Math.min(1, (t - T0 - EXTRA_DESDE) / (EXTRA_HASTA - EXTRA_DESDE)));
			return g0 + tabla(ANG, t - T0) + extra * e * e * (3 - 2 * e);
		}
		float u = Math.min(1, (t - T0 - T_GIRO) / T_VUELTA), s = u * u * u * (u * (u * 6 - 15) + 10);
		return g0 + extra + DIST - (22.5f + PASADA) * s;      // la flecha la empuja para atras hasta el medio del color
	}

	private static float velRel(float t) { return t < T0 || t > T0 + T_GIRO ? 0 : tabla(VEL, t - T0); }

	/** Avanza la fisica de la flecha hasta el segundo t. */
	private static void moverFlecha(float t) {
		int pasos = 0;
		while (simT + DT <= t && pasos < 40000) {
			float gAnt = giro(simT);
			simT += DT; pasos++;
			float g = giro(simT);
			fv += (-WN * WN * fd - 2 * ZT * WN * fv) * DT; fd += fv * DT;
			if (g >= (seg + 1) * 45f) { seg++; izqOk = false; derOk = true; }
			else if (g < seg * 45f) { seg--; derOk = false; izqOk = true; }
			float rr = (seg + 1) * 45f - g, ll = g - seg * 45f;
			if (ll > W_PEG) izqOk = true;
			if (rr > W_PEG) derOk = true;
			float limDer = 1 - rr / W_PEG, limIzq = -(1 - ll / W_PEG);
			if (derOk && rr < W_PEG && fd < limDer) {
				float vp = (limDer - (1 - ((seg + 1) * 45f - gAnt) / W_PEG)) / DT; fd = limDer; fv = Math.max(fv, vp * 0.35f);
			}
			if (izqOk && ll < W_PEG && fd > limIzq) {
				float vp = (limIzq + (1 - (gAnt - seg * 45f) / W_PEG)) / DT; fd = limIzq; fv = Math.min(fv, vp * 0.35f);
			}
			if (fd > 1.35f) { fd = 1.35f; fv = -Math.abs(fv) * 0.3f; }
			if (fd < -1.35f) { fd = -1.35f; fv = Math.abs(fv) * 0.3f; }
			hist[histPos] = fd; histPos = (histPos + 1) % HIST;
		}
	}

	/** Como estaba la flecha hace "atras" pasos de fisica. */
	private static float flechaHace(int atras) { return hist[Math.floorMod(histPos - 1 - atras, HIST)]; }

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
		moverFlecha(t);

		// ---- tamaño y blanco (aparece con rebote, golpecito al elegir, se va) ----
		float esc = 1, blanco = 0, alfa = 1;
		if (t < T_AP) { float p = t / T_AP; esc = Math.max(0.02f, 1 + 2.9f * (float) Math.pow(p - 1, 3) + 1.9f * (float) Math.pow(p - 1, 2)); blanco = 1 - p; }
		float ts = t - T_STOP;
		if (ts >= 0 && ts < 0.4f) esc *= 1 + 0.06f * (float) (Math.exp(-ts * 9) * Math.cos(ts * 30));
		float tf = t - T_HOLD1;
		if (tf > 0) {
			float p = Math.min(1, tf / T_FUERA);
			esc *= p < 0.35f ? 1 + 0.08f * (float) Math.sin(p / 0.35f * Math.PI / 2) : 1.08f * (1 - (float) Math.pow((p - 0.35f) / 0.65f, 1.6));
			blanco = Math.max(blanco, Math.min(1, p * 1.6f));
			alfa = p < 0.85f ? 1 : (1 - p) / 0.15f;
		}

		// centro: la gema justo sobre la mira. Esquina: mas chica, abajo a la derecha, a la misma distancia de los dos bordes
		float d0, cx, cy;
		if (pos == Ajustes.PosRuleta.ESQUINA) {
			d0 = Math.min(h * 0.30f, 110f);
			float radio = d0 * 0.95f / 2f, margen = 8f;
			cx = w - margen - radio; cy = h - margen - radio;
		} else {
			d0 = Math.min(h * 0.45f, 170f);
			cx = w / 2f; cy = h / 2f;
		}
		float d = d0 * esc;
		var pose = g.pose();

		// punto de luz al aparecer / al irse
		float luz = t < T_AP * 0.8f ? 1 - t / (T_AP * 0.8f) : (tf > T_FUERA * 0.6f ? Math.max(0, 1 - Math.abs(t - T_FIN) / 0.12f) : 0);
		if (luz > 0) {
			pose.pushMatrix(); pose.translate(cx, cy); pose.scale(d0 * 0.4f / 128f, d0 * 0.4f / 128f);
			g.blit(RenderPipelines.GUI_TEXTURED, LUZ, -64, -64, 0, 0, 128, 128, 128, 128, 128, 128, argb(luz * 0.8f, 0xFFFFFF));
			pose.popMatrix();
		}
		if (d < 2 || alfa <= 0) return;
		float k = d / S;
		pose.pushMatrix();
		pose.translate(cx, cy);
		pose.scale(k, k);

		// ---- los 8 gajos: se prenden, giran (borrosos si va rapido), y al final se contagia el color ----
		int[] col = new int[8];
		float flashFreno = ts >= 0 && ts < 0.12f ? (1 - ts / 0.12f) * 0.8f : 0;
		for (int i = 0; i < 8; i++) {
			float on = clamp01((t - T_AP * 0.35f - i * T_ON / 8) / 0.08f);
			int c = mezclar(0x282C2A, COLORES[i], on);
			int orden = Math.floorMod(sel - i, 8);                      // el elegido primero, despues el de la izquierda...
			float tk = t - T_PROP0 - orden * PASO, flash = flashFreno;
			if (tk >= 0) { c = oscurecer(COLORES[sel], i % 2 == 0 ? 0.82f : 1f); flash = Math.max(flash, Math.max(0, 1 - tk / 0.22f) * 0.55f); }
			col[i] = mezclar(c, 0xFFFFFF, flash);
		}
		float abanico = Math.min(20f, V0 * velRel(t) / 60f);           // cuanto barre en un instante (se siguen distinguiendo los colores)
		int copias = abanico < 1.5f ? 1 : Math.max(7, Math.min(16, (int) (abanico / 2.5f)));
		for (int j = 0; j < copias; j++) {
			float a = copias == 1 ? giro : giro - abanico * (j / (float) (copias - 1) - 0.5f);
			float al = alfa / (j + 1);                                   // asi todas las copias pesan lo mismo
			for (int i = 0; i < 8; i++) {
				pose.pushMatrix();
				pose.rotate((float) Math.toRadians(i * 45f - a));
				g.blit(RenderPipelines.GUI_TEXTURED, GAJO, -S / 2, -S / 2, 0, 0, S, S, S, S, S, S, argb(al, col[i]));
				pose.popMatrix();
			}
		}
		// aro con runas y centro (quietos)
		g.blit(RenderPipelines.GUI_TEXTURED, ARO, -S / 2, -S / 2, 0, 0, S, S, S, S, S, S, argb(alfa, 0xFFFFFF));
		g.blit(RenderPipelines.GUI_TEXTURED, CENTRO, -S / 2, -S / 2, 0, 0, S, S, S, S, S, S, argb(alfa, 0xFFFFFF));
		if (blanco > 0) g.blit(RenderPipelines.GUI_TEXTURED, BLANCA, -S / 2, -S / 2, 0, 0, S, S, S, S, S, S, argb(blanco * alfa, 0xFFFFFF));

		// ---- la flecha: se dobla para la izquierda cuando la empujan; borrosa si tiembla rapido ----
		float pop = ts >= 0 && ts < 0.6f ? (float) Math.exp(-ts * 7) : 0;
		float[] muestras = new float[6];
		float min = 9, max = -9;
		for (int j = 0; j < 6; j++) { muestras[j] = flechaHace(j * 133 / 6); min = Math.min(min, muestras[j]); max = Math.max(max, muestras[j]); }
		int nf = (max - min) * 22 < 2 ? 1 : 6;
		for (int j = 0; j < nf; j++) {
			float defl = 22f * (nf == 1 ? fd : muestras[j]);
			pose.pushMatrix();
			pose.translate(0, PIVOTE_Y);
			pose.rotate((float) Math.toRadians(defl));
			pose.translate(-GEMA_X, -GEMA_Y);
			g.blit(RenderPipelines.GUI_TEXTURED, FLECHA, 0, 0, 0, 0, FLW, FLH, FLW, FLH, FLW, FLH, argb(alfa / (j + 1), 0xFFFFFF));
			pose.popMatrix();
		}
		// la gema de oro verde (no cambia de color; hace un "pop" al elegir)
		float gt = GEMA_TAM * (1 + 0.3f * pop);
		pose.pushMatrix();
		pose.translate(0, PIVOTE_Y);
		pose.scale(gt / 64f, gt / 64f);
		g.blit(RenderPipelines.GUI_TEXTURED, GEMA, -32, -32, 0, 0, 64, 64, 64, 64, 64, 64, argb(alfa, 0xFFFFFF));
		pose.popMatrix();
		// flecha y gema blancas al aparecer / irse
		if (blanco > 0) {
			pose.pushMatrix();
			pose.translate(0, PIVOTE_Y);
			pose.rotate((float) Math.toRadians(22f * fd));
			pose.translate(-GEMA_X, -GEMA_Y);
			g.blit(RenderPipelines.GUI_TEXTURED, FLECHA_BLANCA, 0, 0, 0, 0, FLW, FLH, FLW, FLH, FLW, FLH, argb(blanco * alfa, 0xFFFFFF));
			pose.popMatrix();
		}
		pose.popMatrix();
	}
}

package ar.tod5.menu;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Util;

/**
 * Al cerrar el juego desde el menu principal (boton Salir o la X de la ventana):
 * la musica baja de a poco, la pantalla se oscurece hasta quedar negra y recien ahi se cierra.
 * En un mundo o en otras pantallas se cierra normal.
 */
public final class Salida {
	public static final float DURA = 1.6f;   // segundos hasta cerrar
	private static long desde = 0;
	private static boolean permitido = false;

	private Salida() {}

	/** Lo llama el mixin de Minecraft.stop(): true = todavia no cerrar (arranca el fundido). */
	public static boolean frenar(Minecraft mc) {
		if (permitido || mc.level != null || !(mc.screen instanceof MenuPrincipal)) return false;
		if (desde == 0) {
			desde = Util.getMillis();
			MusicaMenu.fundir(0f, DURA - 0.3f);
		}
		return true;
	}

	/** Cuanto negro hay que dibujar encima (0..1). */
	public static float oscuro() {
		if (desde == 0) return 0;
		return Math.min(1f, (Util.getMillis() - desde) / 1000f / (DURA - 0.3f));
	}

	static void tick(Minecraft mc) {
		if (desde != 0 && !permitido && (Util.getMillis() - desde) / 1000f >= DURA) {
			permitido = true;
			mc.stop();
		}
	}
}

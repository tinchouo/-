package ar.tod5.menu;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * La musica del menu, tocada por el mod (no por el server ni por la musica de Minecraft).
 * Suena en loop sin cortes mientras estas en los menus, y se apaga sola cuando entras a un mundo o server.
 * Si Minecraft la corta (al recargar los paquetes de texturas, o al bajar el volumen a 0 y volver a subirlo),
 * vuelve a arrancar sola.
 */
public final class MusicaMenu {
	private static final Logger LOG = LoggerFactory.getLogger("TOD5");
	private static final Identifier SONIDO = Identifier.fromNamespaceAndPath(TOD5Client.MOD_ID, "menu");
	private static SoundInstance actual;
	private static boolean deberiaSonar;
	private static int espera, chequeo;

	private MusicaMenu() {}

	public static void iniciar() {
		deberiaSonar = true;
		tocar(Minecraft.getInstance());
	}

	private static void tocar(Minecraft mc) {
		if (actual != null && mc.getSoundManager().isActive(actual)) return;
		mc.getMusicManager().stopPlaying();
		actual = new SimpleSoundInstance(SONIDO, SoundSource.MUSIC, 1.0f, 1.0f, RandomSource.create(),
			true, 0, SoundInstance.Attenuation.NONE, 0, 0, 0, true);
		var res = mc.getSoundManager().play(actual);
		LOG.info("TOD5 musica del menu: {}", res);
		chequeo = 100;
	}

	public static void parar() {
		deberiaSonar = false;
		if (actual != null) Minecraft.getInstance().getSoundManager().stop(actual);
		actual = null;
	}

	public static void tick(Minecraft mc) {
		if (mc.level != null) {   // entraste a un mundo o server
			if (deberiaSonar || actual != null) parar();
			return;
		}
		if (chequeo > 0 && --chequeo == 0 && actual != null)
			LOG.info("TOD5 musica del menu sonando 5s despues: {}", mc.getSoundManager().isActive(actual));
		// si Minecraft la corto, la vuelvo a poner (una vez por segundo como mucho, y solo si se escucha algo)
		if (deberiaSonar && mc.gui.overlay() == null && ++espera >= 20) {
			espera = 0;
			boolean seEscucha = mc.options.getFinalSoundSourceVolume(SoundSource.MUSIC) > 0;
			if (seEscucha && (actual == null || !mc.getSoundManager().isActive(actual))) {
				LOG.info("TOD5 musica del menu: se habia cortado, la vuelvo a poner");
				actual = null;
				tocar(mc);
			}
		}
	}
}

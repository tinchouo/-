package ar.tod5.menu;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
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
	// volumen con fundido (para el secreto del agujero negro y al salir del juego)
	private static float volumen = 1f, objetivo = 1f, paso = 1f;

	/** Lleva el volumen de la musica a "a" (0..1) en esa cantidad de segundos. */
	public static void fundir(float a, float segundos) {
		objetivo = a;
		paso = segundos <= 0 ? 1f : Math.abs(a - volumen) / (segundos * 20f);
		if (paso <= 0) paso = 1f;
	}

	/** La musica como sonido que se actualiza cada tick: asi el volumen puede bajar y subir de a poco. */
	private static final class Pista extends AbstractTickableSoundInstance {
		Pista() {
			super(SoundEvent.createVariableRangeEvent(SONIDO), SoundSource.MUSIC, RandomSource.create());
			this.looping = true;
			this.delay = 0;
			this.volume = Math.max(0.001f, volumen);
			this.relative = true;
			this.attenuation = SoundInstance.Attenuation.NONE;
		}
		@Override public void tick() { this.volume = Math.max(0.001f, volumen); }
	}

	private MusicaMenu() {}

	public static void iniciar() {
		deberiaSonar = true;
		tocar(Minecraft.getInstance());
	}

	private static void tocar(Minecraft mc) {
		if (actual != null && mc.getSoundManager().isActive(actual)) return;
		mc.getMusicManager().stopPlaying();
		actual = new Pista();
		var res = mc.getSoundManager().play(actual);
		LOG.info("TOD5 musica del menu: {}", res);
		chequeo = 100;
	}

	public static void parar() {
		deberiaSonar = false;
		volumen = objetivo = 1f;
		if (actual != null) Minecraft.getInstance().getSoundManager().stop(actual);
		actual = null;
	}

	public static void tick(Minecraft mc) {
		if (volumen != objetivo) volumen = volumen < objetivo ? Math.min(objetivo, volumen + paso) : Math.max(objetivo, volumen - paso);
		Salida.tick(mc);
		// entraste a un mundo o server: se corta en el momento
		if (mc.level != null) {
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

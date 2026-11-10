package ar.tod5.menu;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

/**
 * La musica del menu, tocada por el mod (no por el server ni por la musica de Minecraft).
 * Suena en loop sin cortes mientras estas en los menus, y se apaga sola cuando entras a un mundo o server.
 * Usa el volumen de "Musica" de las opciones.
 */
public final class MusicaMenu {
	private static final Identifier SONIDO = Identifier.fromNamespaceAndPath(TOD5Client.MOD_ID, "menu");
	private static SoundInstance actual;

	private MusicaMenu() {}

	public static void iniciar() {
		Minecraft mc = Minecraft.getInstance();
		if (actual != null && mc.getSoundManager().isActive(actual)) return;
		mc.getMusicManager().stopPlaying();
		actual = new SimpleSoundInstance(SONIDO, SoundSource.MUSIC, 1.0f, 1.0f, RandomSource.create(),
			true, 0, SoundInstance.Attenuation.NONE, 0, 0, 0, true);
		mc.getSoundManager().play(actual);
	}

	public static void parar() {
		if (actual != null) Minecraft.getInstance().getSoundManager().stop(actual);
		actual = null;
	}

	/** Cada tick: si entraste a un mundo, se corta. */
	public static void tick(Minecraft mc) {
		if (actual != null && mc.level != null) parar();
	}
}

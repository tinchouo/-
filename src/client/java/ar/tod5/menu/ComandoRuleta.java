package ar.tod5.menu;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.network.chat.Component;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/**
 * Comando de PRUEBA: /ruleta [color]  (solo en tu pantalla, no manda nada al server ni al chat de los demas).
 * Anda en cualquier lado, incluso en un mundo de un jugador. Sin color: sale uno al azar.
 * Colores: verde, celeste, azul, morado, rosa, rojo, naranja, amarillo.
 */
public final class ComandoRuleta {
	private static final String[] NOMBRES = { "verde", "celeste", "azul", "morado", "rosa", "rojo", "naranja", "amarillo" };

	private ComandoRuleta() {}

	static int color(String s) {
		s = s.toLowerCase();
		for (int i = 0; i < NOMBRES.length; i++) if (NOMBRES[i].equals(s)) return i;
		return -1;
	}

	public static void registrar() {
		ClientCommandRegistrationCallback.EVENT.register((d, ctx) -> d.register(literal("ruleta")
			.executes(c -> { Ruleta.empezar((int) (Math.random() * 8)); return 1; })
			.then(argument("color", StringArgumentType.word())
				.suggests((c, b) -> { for (String n : NOMBRES) b.suggest(n); return b.buildFuture(); })
				.executes(c -> {
					int col = color(StringArgumentType.getString(c, "color"));
					if (col < 0) { c.getSource().sendError(Component.literal("Color desconocido. Probá: verde, celeste, azul, morado, rosa, rojo, naranja o amarillo.")); return 0; }
					Ruleta.empezar(col);
					return 1;
				}))));
	}
}

package ar.tod5.menu;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.network.chat.Component;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/**
 * Comando de PRUEBA: /ruleta [color]  (solo en tu pantalla, no manda nada al server ni al chat de los demas).
 * Anda en cualquier lado, incluso en un mundo de un jugador. Sin color: sale uno al azar.
 * Colores: verde/diaria, celeste/aviso, azul/dungeon, morado/sancion, rosa/cuy, rojo/truco, naranja/cambio, amarillo/mob.
 */
public final class ComandoRuleta {
	private static final String[][] NOMBRES = {
		{ "verde", "diaria" }, { "celeste", "aviso" }, { "azul", "dungeon" }, { "morado", "sancion" },
		{ "rosa", "cuy" }, { "rojo", "truco" }, { "naranja", "cambio" }, { "amarillo", "mob" } };

	private ComandoRuleta() {}

	static int color(String s) {
		s = s.toLowerCase().replace("ó", "o");
		for (int i = 0; i < NOMBRES.length; i++) for (String n : NOMBRES[i]) if (n.equals(s)) return i;
		try { int n = Integer.parseInt(s); if (n >= 0 && n < 8) return n; } catch (NumberFormatException ignored) {}
		return -1;
	}

	public static void registrar() {
		ClientCommandRegistrationCallback.EVENT.register((d, ctx) -> d.register(literal("ruleta")
			.executes(c -> { Ruleta.empezar((int) (Math.random() * 8)); return 1; })
			.then(argument("color", StringArgumentType.word())
				.suggests((c, b) -> { for (String[] n : NOMBRES) { b.suggest(n[0]); b.suggest(n[1]); } return b.buildFuture(); })
				.executes(c -> {
					int col = color(StringArgumentType.getString(c, "color"));
					if (col < 0) { c.getSource().sendError(Component.literal("Color desconocido. Probá: verde, celeste, azul, morado, rosa, rojo, naranja o amarillo.")); return 0; }
					Ruleta.empezar(col);
					return 1;
				}))));
	}
}

package ar.tod5.menu;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public class TOD5Client implements ClientModInitializer {
	public static final String MOD_ID = "tod5";

	/** Direccion por defecto del server (la que usan todos los jugadores si no hay archivo de config). */
	private static final String IP_POR_DEFECTO = "trucoofdeath4.holy.gg";

	/** IP del server para el boton Jugar: -Dtod5.ip=..., si no el archivo config/tod5.txt (primera linea), si no la de por defecto. */
	public static final String IP_SERVER = leerIp();

	private static String leerIp() {
		String p = System.getProperty("tod5.ip");
		if (p != null && !p.isBlank()) return p.trim();
		try {
			java.nio.file.Path f = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("tod5.txt");
			if (java.nio.file.Files.exists(f)) {
				for (String l : java.nio.file.Files.readAllLines(f)) if (!l.isBlank() && !l.startsWith("#")) return l.trim();
			}
		} catch (Exception ignorado) { }
		return IP_POR_DEFECTO;
	}

	@Override
	public void onInitializeClient() {
		ItemsTOD.registrar();
		ContadorDesafio.registrar();
		ClientTickEvents.END_CLIENT_TICK.register(MusicaMenu::tick);
		ClientTickEvents.END_CLIENT_TICK.register(GravedadCliente::tick);
		ClientTickEvents.END_CLIENT_TICK.register(SaltosCliente::tick);
		ClientTickEvents.START_CLIENT_TICK.register(PlataformasCliente::inicioTick);
		ClientTickEvents.END_CLIENT_TICK.register(PlataformasCliente::finTick);
		ClientTickEvents.END_CLIENT_TICK.register(RarezaPiso::tick);
		Conexion.registrar();
		Ruleta.registrar();
		ComandoRuleta.registrar();
		ar.tod5.menu.tablet.TabletHud.registrar();
		ar.tod5.menu.tablet.TeclaTablet.registrar();
		MochilasCliente.registrar();
	}
}

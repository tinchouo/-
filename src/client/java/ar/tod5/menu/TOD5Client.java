package ar.tod5.menu;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public class TOD5Client implements ClientModInitializer {
	public static final String MOD_ID = "tod5";

	/** IP del server para el boton Jugar (placeholder hasta que haya server). */
	public static final String IP_SERVER = System.getProperty("tod5.ip", "trucoofdeath4.holy.gg");

	@Override
	public void onInitializeClient() {
		ClientTickEvents.END_CLIENT_TICK.register(MusicaMenu::tick);
		Conexion.registrar();
	}
}

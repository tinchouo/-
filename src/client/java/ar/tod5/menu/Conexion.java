package ar.tod5.menu;

import java.util.stream.Collectors;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** La parte del mod que habla con el plugin del server: saludo con la lista de mods y la sala de espera. */
public final class Conexion {
	private static final Logger LOG = LoggerFactory.getLogger("TOD5");
	private Conexion() {}

	public static void registrar() {
		PayloadTypeRegistry.clientboundPlay().register(Red.SALA, Red.codec(Red.SALA));
		PayloadTypeRegistry.serverboundPlay().register(Red.HOLA, Red.codec(Red.HOLA));

		ClientPlayNetworking.registerGlobalReceiver(Red.SALA, (p, ctx) -> {
			String m = p.texto();
			LOG.info("TOD5 server dice: {}", m);
			if (m.startsWith("espera;")) {
				try { SalaEspera.esperando = Integer.parseInt(m.substring(7).trim()); } catch (NumberFormatException e) { SalaEspera.esperando = 0; }
				if (!(ctx.client().gui.screen() instanceof SalaEspera)) ctx.client().gui.setScreen(new SalaEspera());
			} else if (m.equals("abrir")) {
				SalaEspera.esperando = -1;
				if (ctx.client().gui.screen() instanceof SalaEspera) ctx.client().gui.setScreen(null);
			}
		});

		// al entrar a un server: le aviso que tengo el mod y que mods tengo (solo los "de arriba", no los que vienen adentro de otros)
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			SalaEspera.esperando = -1;
			String version = FabricLoader.getInstance().getModContainer(TOD5Client.MOD_ID).map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?");
			String mods = FabricLoader.getInstance().getAllMods().stream()
				.filter(m -> m.getContainingMod().isEmpty())
				.map(m -> m.getMetadata().getId())
				.sorted().collect(Collectors.joining(","));
			ClientPlayNetworking.send(new Red.Texto(Red.HOLA, version + ";" + mods));
			LOG.info("TOD5 saludo enviado: {} {}", version, mods);
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> SalaEspera.esperando = -1);
	}

	/** Cada tick: si el server dijo que estamos en la sala y alguien cerro la pantalla, la vuelvo a abrir. */
	public static void tick(Minecraft mc) {
		if (SalaEspera.esperando >= 0 && mc.level != null && !(mc.gui.screen() instanceof SalaEspera)) mc.gui.setScreen(new SalaEspera());
	}
}

package ar.tod5.menu;

import java.util.stream.Collectors;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** La parte del mod que habla con el plugin del server: al entrar le manda la version y la lista de mods. */
public final class Conexion {
	private static final Logger LOG = LoggerFactory.getLogger("TOD5");
	private Conexion() {}

	public static void registrar() {
		PayloadTypeRegistry.serverboundPlay().register(Red.HOLA, Red.codec(Red.HOLA));

		// al entrar a un server: le aviso que tengo el mod y que mods tengo (solo los "de arriba", no los que vienen adentro de otros)
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			String version = FabricLoader.getInstance().getModContainer(TOD5Client.MOD_ID).map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?");
			// los pedacitos de Fabric API (fabric-algo, hechos por FabricMC) cuentan como "fabric-api"
			String mods = FabricLoader.getInstance().getAllMods().stream()
				.filter(m -> m.getContainingMod().isEmpty())
				.map(m -> {
					var md = m.getMetadata();
					boolean deFabric = md.getId().startsWith("fabric-") && md.getAuthors().stream().anyMatch(a -> a.getName().equals("FabricMC"));
					return deFabric ? "fabric-api" : md.getId();
				})
				.distinct().sorted().collect(Collectors.joining(","));
			ClientPlayNetworking.send(new Red.Texto(Red.HOLA, version + ";" + mods));
			LOG.info("TOD5 saludo enviado: {} {}", version, mods);
		});
	}

}

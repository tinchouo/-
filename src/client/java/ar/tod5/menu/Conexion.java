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
		PayloadTypeRegistry.playC2S().register(Red.HOLA, Red.codec(Red.HOLA));
		PayloadTypeRegistry.playC2S().register(Red.ACCION, Red.codec(Red.ACCION));
		PayloadTypeRegistry.playS2C().register(Red.TABLET, Red.codec(Red.TABLET));
		PayloadTypeRegistry.playS2C().register(Red.RULETA, Red.codec(Red.RULETA));
		PayloadTypeRegistry.playS2C().register(Red.DESAFIO, Red.codec(Red.DESAFIO));
		ClientPlayNetworking.registerGlobalReceiver(Red.DESAFIO, (p, ctx) -> ctx.client().execute(() -> ContadorDesafio.recibir(p.texto())));
		ClientPlayNetworking.registerGlobalReceiver(Red.RULETA, (p, ctx) -> ctx.client().execute(() -> {
			try { Ruleta.empezar(Integer.parseInt(p.texto().trim())); LOG.info("TOD5 ruleta: color {}", p.texto().trim()); }
			catch (NumberFormatException e) { LOG.warn("TOD5 ruleta: no entendi '{}'", p.texto()); }
		}));

		// el server me manda lo que muestra la tablet (al entrar y cada vez que cambia algo)
		ClientPlayNetworking.registerGlobalReceiver(Red.TABLET, (p, ctx) -> ctx.client().execute(() -> ar.tod5.menu.tablet.TabletDatos.recibir(p.texto())));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> { ar.tod5.menu.tablet.TabletDatos.olvidar(); ContadorDesafio.recibir("nada"); }));

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

	/** Lo que hace un editor en la tablet: se lo mando al server (el server chequea que sea editor). */
	public static void accion(String json) {
		if (ClientPlayNetworking.canSend(Red.ACCION)) ClientPlayNetworking.send(new Red.Texto(Red.ACCION, json));
		else LOG.warn("TOD5 no puedo mandar la accion de la tablet (el server no tiene el plugin)");
	}
}

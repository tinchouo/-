package ar.tod5.menu;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Marca (tipo Fortnite/Peak): con el boton del medio del mouse (se cambia en Controles > TRUCOFDEATH) marcás donde mirás.
 * La ven vos y tu equipo por 5 segundos, a traves de las paredes. Medio segundo de espera entre marcas.
 */
public final class Marca {
	private Marca() {}
	public static KeyMapping MARCAR;
	private static long ultima;

	public static void registrar() {
		MARCAR = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.tod5.marcar", InputConstants.Type.MOUSE, 2, ar.tod5.menu.tablet.TeclaTablet.CATEGORIA));
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			while (MARCAR.consumeClick()) if (mc.player != null && mc.screen == null) marcar(mc);
		});
	}
	private static void marcar(Minecraft mc) {
		long ahora = System.currentTimeMillis();
		if (ahora - ultima < 500) return;
		HitResult h = mc.player.pick(128, 1f, false);
		if (h.getType() != HitResult.Type.BLOCK) return;
		ultima = ahora;
		Vec3 v = h.getLocation();
		JsonObject o = new JsonObject(); o.addProperty("a", "equipo_marca");
		o.addProperty("x", v.x); o.addProperty("y", v.y); o.addProperty("z", v.z);
		Conexion.accion(o.toString());
	}
}

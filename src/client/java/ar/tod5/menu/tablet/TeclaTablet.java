package ar.tod5.menu.tablet;

import ar.tod5.menu.mixin.KeyCategoryAccessor;
import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

/**
 * La tecla de la tablet (J por defecto). Aparece en Opciones > Controles, en la seccion "TRUCOFDEATH", arriba de todo.
 * Solo abre la tablet si estas en el mundo y no tenes ningun inventario abierto (ni cofres ni nada).
 */
public final class TeclaTablet {
	public static final KeyMapping.Category CATEGORIA = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("tod5", "trucofdeath"));
	public static KeyMapping TECLA;

	private TeclaTablet() {}

	public static void registrar() {
		// la seccion TRUCOFDEATH va primera en Controles
		List<KeyMapping.Category> orden = KeyCategoryAccessor.tod5$orden();
		orden.remove(CATEGORIA);
		orden.add(0, CATEGORIA);

		org.slf4j.LoggerFactory.getLogger("TOD5").info("TOD5 secciones de controles: {}", orden.stream().limit(3).map(c -> c.id().toString()).toList());
		TECLA = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.tod5.tablet", InputConstants.getKey("key.keyboard.j").getValue(), CATEGORIA));
		org.slf4j.LoggerFactory.getLogger("TOD5").info("TOD5 tecla de la tablet: {}", TECLA.saveString());
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			while (TECLA.consumeClick()) {
				boolean puede = mc.player != null && mc.level != null && mc.gui.screen() == null;
				if (Boolean.getBoolean("tod5.debugTeclas")) org.slf4j.LoggerFactory.getLogger("TOD5").info("TOD5 tecla tablet (abre: {}, pantalla: {})", puede, mc.gui.screen());
				if (puede) mc.gui.setScreen(new Tablet());
			}
		});
	}
}

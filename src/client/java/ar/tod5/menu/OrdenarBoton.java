package ar.tod5.menu;

import ar.tod5.menu.mixin.ContainerScreenAccessor;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen;
import net.minecraft.network.chat.Component;

/**
 * Boton "Ordenar" arriba a la derecha de los lugares donde se guardan cosas: cofres, barriles, cofre de ender, mochila (todos usan ContainerScreen),
 * shulkers y el inventario propio (sin la barra de abajo). Lo ordena el server (accion "ordenar"), solo al apretarlo.
 */
public final class OrdenarBoton {
	private OrdenarBoton() {}

	public static void registrar() {
		ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
			boolean inventario = screen instanceof InventoryScreen;
			if (!(screen instanceof ContainerScreen) && !(screen instanceof ShulkerBoxScreen) && !inventario) return;
			AbstractContainerScreen<?> s = (AbstractContainerScreen<?>) screen;
			Button b = Button.builder(Component.literal("⇅"), x -> {
				JsonObject o = new JsonObject(); o.addProperty("a", "ordenar"); o.addProperty("donde", inventario ? "inventario" : "arriba");
				Conexion.accion(o.toString());
			}).bounds(0, 0, 11, 11).tooltip(Tooltip.create(Component.literal(inventario ? "Ordenar el inventario" : "Ordenar"))).build();
			Screens.getButtons(screen).add(b);
			ScreenEvents.beforeRender(screen).register((sc, g, mx, my, d) -> {   // por si se mueve la pantalla (libro de recetas)
				ContainerScreenAccessor a = (ContainerScreenAccessor) s;
				b.setX(a.tod5$left() + a.tod5$ancho() - 7 - 11);
				b.setY(a.tod5$top() + (inventario ? 70 : 4));
			});
		});
	}
}

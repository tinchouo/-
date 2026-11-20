package ar.tod5.menu;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.world.item.Item;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * Aviso de durabilidad: en las pantallas de inventario, un item con 25% o menos de durabilidad late en rojo
 * (se tiñe el dibujo del item, no la casilla, para no confundirlo con el brillo de las rarezas). Cuanto menos le queda, mas fuerte y mas rapido.
 */
public final class AvisoDurabilidad {
	private AvisoDurabilidad() {}
	private static final Map<Item, Identifier> TEXTURAS = new HashMap<>();
	private static final Identifier SIN = Identifier.fromNamespaceAndPath("tod5", "nada");
	/** La textura plana del item (textures/item/<nombre>.png). Si no tiene (escudo, items 3D), no se tiñe. */
	private static Identifier textura(Minecraft mc, ItemStack it) {
		Identifier m = it.get(net.minecraft.core.component.DataComponents.ITEM_MODEL);
		if (m == null) m = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(it.getItem());
		Identifier t = Identifier.fromNamespaceAndPath(m.getNamespace(), "textures/item/" + m.getPath() + ".png");
		return mc.getResourceManager().getResource(t).isPresent() ? t : SIN;
	}

	public static void dibujar(GuiGraphics g, ItemStack it, int x, int y) {
		if (it.isEmpty() || !it.isDamageableItem() || it.getMaxDamage() <= 0) return;
		Minecraft mc = Minecraft.getInstance();
		if (!(mc.screen instanceof AbstractContainerScreen<?>)) return;
		float queda = 1f - (float) it.getDamageValue() / it.getMaxDamage();
		if (queda > 0.25f) return;
		float fuerza = Math.min(1f, (0.25f - queda) / 0.25f);           // 0 recien llega al 25%, 1 a punto de romperse
		float vel = 2.5f + 5f * fuerza;
		float latido = 0.5f + 0.5f * (float) Math.sin(net.minecraft.util.Util.getMillis() / 1000.0 * vel);
		float alfa = (0.15f + 0.45f * fuerza) * (0.45f + 0.55f * latido);
		Identifier tex = TEXTURAS.computeIfAbsent(it.getItem(), k -> textura(mc, it));
		if (tex == SIN) return;
		int a = (int) (Math.max(0f, Math.min(1f, alfa)) * 255);
		g.blit(RenderPipelines.GUI_TEXTURED, tex, x, y, 0f, 0f, 16, 16, 16, 16, (a << 24) | 0xFF2020);
	}
}

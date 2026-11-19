package ar.tod5.menu;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * Aviso de durabilidad: en las pantallas de inventario, un item con 25% o menos de durabilidad late en rojo
 * (se tiñe el dibujo del item, no la casilla, para no confundirlo con el brillo de las rarezas). Cuanto menos le queda, mas fuerte y mas rapido.
 */
public final class AvisoDurabilidad {
	private AvisoDurabilidad() {}
	private static final Map<Item, TextureAtlasSprite> DIBUJOS = new HashMap<>();
	private static final ItemStackRenderState ESTADO = new ItemStackRenderState();

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
		TextureAtlasSprite s = DIBUJOS.computeIfAbsent(it.getItem(), k -> {
			mc.getItemModelResolver().updateForTopItem(ESTADO, it, ItemDisplayContext.GUI, mc.level, mc.player, 0);
			return ESTADO.pickParticleIcon(RandomSource.create(0L));
		});
		if (s == null) return;
		int a = (int) (Math.max(0f, Math.min(1f, alfa)) * 255);
		g.blitSprite(RenderPipelines.GUI_TEXTURED, s, x, y, 16, 16, (a << 24) | 0xFF2020);
	}
}

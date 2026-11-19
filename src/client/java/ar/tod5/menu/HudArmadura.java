package ar.tod5.menu;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Calidad de vida: la armadura puesta (casco, pechera, pantalones, botas) en 4 casillas a la izquierda de la barra de abajo
 * (despues de la casilla de la mano secundaria) y la mochila puesta en una casilla a la derecha. Se apaga en Opciones > Trucofdeath.
 * Solo dibuja: no le pide nada al server.
 */
public final class HudArmadura {
	private HudArmadura() {}
	private static final Identifier BARRA = Identifier.withDefaultNamespace("hud/hotbar");
	private static final Identifier MANO_IZQ = Identifier.withDefaultNamespace("hud/hotbar_offhand_left"), MANO_DER = Identifier.withDefaultNamespace("hud/hotbar_offhand_right");
	private static final Identifier ESCUDO = Identifier.withDefaultNamespace("container/slot/shield");
	private static final Identifier[] VACIAS = {
		Identifier.withDefaultNamespace("container/slot/helmet"), Identifier.withDefaultNamespace("container/slot/chestplate"),
		Identifier.withDefaultNamespace("container/slot/leggings"), Identifier.withDefaultNamespace("container/slot/boots") };
	private static final EquipmentSlot[] PARTES = { EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET };

	public static void registrar() {
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(TOD5Client.MOD_ID, "armadura"), (g, dt) -> dibujar(g));
	}

	/** Un marco como el de la barra de Minecraft, de n casillas. */
	private static void marco(GuiGraphics g, int x, int y, int n) {
		int w = 1 + n * 20;
		g.blitSprite(RenderPipelines.GUI_TEXTURED, BARRA, 182, 22, 0, 0, x, y, w, 22);
		g.blitSprite(RenderPipelines.GUI_TEXTURED, BARRA, 182, 22, 181, 0, x + w, y, 1, 22);
	}

	private static void dibujar(GuiGraphics g) {
		Minecraft mc = Minecraft.getInstance();
		Player p = mc.player;
		if (p == null || !Ajustes.armadura() || mc.options.hideGui || p.isSpectator()) return;
		int centro = g.guiWidth() / 2, y = g.guiHeight() - 22;
		boolean secundariaIzq = p.getMainArm().getOpposite() == HumanoidArm.LEFT;
		// la mano secundaria siempre a la vista (Minecraft solo la dibuja cuando tiene algo)
		if (p.getOffhandItem().isEmpty()) {
			if (secundariaIzq) { g.blitSprite(RenderPipelines.GUI_TEXTURED, MANO_IZQ, centro - 91 - 29, g.guiHeight() - 23, 29, 24); g.blitSprite(RenderPipelines.GUI_TEXTURED, ESCUDO, centro - 91 - 26, g.guiHeight() - 19, 16, 16, 0x66FFFFFF); }
			else { g.blitSprite(RenderPipelines.GUI_TEXTURED, MANO_DER, centro + 91, g.guiHeight() - 23, 29, 24); g.blitSprite(RenderPipelines.GUI_TEXTURED, ESCUDO, centro + 91 + 10, g.guiHeight() - 19, 16, 16, 0x66FFFFFF); }
		}
		// la armadura: a la izquierda (dejando siempre el lugar de la mano secundaria si va de ese lado)
		int ax = centro - 91 - 6 - 82 - (secundariaIzq ? 29 : 0);
		marco(g, ax, y, 4);
		for (int i = 0; i < 4; i++) {
			ItemStack it = p.getItemBySlot(PARTES[i]);
			int ix = ax + 3 + i * 20, iy = y + 3;
			if (it.isEmpty()) g.blitSprite(RenderPipelines.GUI_TEXTURED, VACIAS[i], ix, iy, 16, 16, 0x66FFFFFF);
			else { g.renderItem(it, ix, iy); g.renderItemDecorations(mc.font, it, ix, iy); }
		}
		// la mochila: a la derecha
		int mx = centro + 91 + 6 + (secundariaIzq ? 0 : 29);
		marco(g, mx, y, 1);
		ItemStack m = MochilasCliente.puesta();
		if (m.isEmpty()) g.blit(RenderPipelines.GUI_TEXTURED, MochilasCliente.VACIA, mx + 3, y + 3, 0f, 0f, 16, 16, 16, 16, 0x66FFFFFF);
		else g.renderItem(m, mx + 3, y + 3);
		// con un inventario o cofre abierto, se oscurece igual que la barra de Minecraft
		if (mc.screen != null) {
			g.fill(ax, y, ax + 82, y + 22, 0xC0101010);
			g.fill(mx, y, mx + 22, y + 22, 0xC0101010);
			if (p.getOffhandItem().isEmpty()) { int ox = secundariaIzq ? centro - 91 - 29 : centro + 91; g.fill(ox, g.guiHeight() - 23, ox + 29, g.guiHeight() + 1, 0xC0101010); }
		}
	}
}

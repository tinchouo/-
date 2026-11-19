package ar.tod5.menu;

import ar.tod5.menu.mixin.ContainerScreenAccessor;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
import org.slf4j.LoggerFactory;

/**
 * Mochilas, del lado del mod:
 *  - teclas (Controles > TRUCOFDEATH): B abre la que tenés puesta; N te pone la que tenés en la mano (o la que tenés debajo del mouse
 *    en el inventario). Si ya tenías una puesta, se intercambian. Con la mano vacía (o una casilla vacía) te la sacás.
 *  - la casilla de la mochila en el inventario (al lado de la de la mano izquierda)
 *  - el color de la mochila de cada jugador, para dibujarla en su espalda
 */
public final class MochilasCliente {
	private MochilasCliente() {}
	public static KeyMapping ABRIR, PONER;
	private static ItemStack puesta = ItemStack.EMPTY;
	private static final Map<UUID, Integer> ESPALDAS = new HashMap<>();
	private static final Map<Integer, ItemStack> MODELOS = new HashMap<>();
	static final Identifier VACIA = Identifier.fromNamespaceAndPath("tod5", "textures/gui/mochila_vacia.png");
	/** Donde va la casilla dentro del inventario (al lado del muñeco, arriba de la de la mano izquierda). */
	public static final int CX = 77, CY = 44;

	public static void registrar() {
		ABRIR = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.tod5.mochila_abrir", InputConstants.getKey("key.keyboard.b").getValue(), ar.tod5.menu.tablet.TeclaTablet.CATEGORIA));
		PONER = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.tod5.mochila_poner", InputConstants.getKey("key.keyboard.n").getValue(), ar.tod5.menu.tablet.TeclaTablet.CATEGORIA));
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			while (ABRIR.consumeClick()) if (mc.player != null && mc.screen == null) accion("mochila_abrir", null);
			while (PONER.consumeClick()) if (mc.player != null && mc.screen == null) accion("mochila_poner", -1);
		});
		// con un inventario abierto: N sobre una casilla de tu inventario
		ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
			if (!(screen instanceof AbstractContainerScreen<?> cs)) return;
			ScreenKeyboardEvents.afterKeyPress(screen).register((s, ev) -> {
				if (!PONER.matches(ev)) return;
				Slot sl = ((ContainerScreenAccessor) cs).tod5$hovered();
				if (sl != null && sl.container instanceof Inventory) accion("mochila_poner", sl.getContainerSlot());
			});
		});
	}
	private static void accion(String a, Integer casilla) {
		JsonObject o = new JsonObject(); o.addProperty("a", a);
		if (casilla != null) o.addProperty("casilla", casilla);
		Conexion.accion(o.toString());
	}

	public static void recibir(String json) {
		try {
			JsonObject o = JsonParser.parseString(json).getAsJsonObject();
			puesta = ar.tod5.menu.tablet.TabletDatos.itemDeJson(o.get("puesta"));
			ESPALDAS.clear();
			for (var e : o.getAsJsonObject("espaldas").entrySet()) ESPALDAS.put(UUID.fromString(e.getKey()), e.getValue().getAsInt());
		} catch (Exception e) { LoggerFactory.getLogger("TOD5").warn("TOD5 mochilas: no entendí ({})", e.getMessage()); }
	}
	public static ItemStack puesta() { return puesta; }
	public static void limpiar() { puesta = ItemStack.EMPTY; ESPALDAS.clear(); }

	/** Lo que se dibuja en la espalda de ese jugador (vacio = no tiene). */
	public static ItemStack espalda(UUID u) {
		Integer c = ESPALDAS.get(u);
		if (c == null) return ItemStack.EMPTY;
		return MODELOS.computeIfAbsent(c, col -> {
			ItemStack it = new ItemStack(Items.LEATHER_HORSE_ARMOR);
			it.set(DataComponents.ITEM_MODEL, Identifier.fromNamespaceAndPath("tod5", "mochila_espalda"));
			it.set(DataComponents.DYED_COLOR, new DyedItemColor(col));
			return it;
		});
	}

	// ================= la casilla del inventario =================
	public static void dibujarCasilla(GuiGraphics g, int left, int top) {
		int x = left + CX, y = top + CY;
		g.fill(x - 1, y - 1, x + 17, y, 0xFF373737);
		g.fill(x - 1, y, x, y + 17, 0xFF373737);
		g.fill(x, y + 16, x + 17, y + 17, 0xFFFFFFFF);
		g.fill(x + 16, y, x + 17, y + 16, 0xFFFFFFFF);
		g.fill(x, y, x + 16, y + 16, 0xFF8B8B8B);
		if (puesta.isEmpty()) g.blit(RenderPipelines.GUI_TEXTURED, VACIA, x, y, 0f, 0f, 16, 16, 16, 16);
	}
	public static void dibujarEncima(GuiGraphics g, int left, int top, int mx, int my) {
		int x = left + CX, y = top + CY;
		Minecraft mc = Minecraft.getInstance();
		if (!puesta.isEmpty()) { g.renderItem(puesta, x, y); g.renderItemDecorations(mc.font, puesta, x, y); }
		if (adentro(left, top, mx, my)) {
			g.fill(x, y, x + 16, y + 16, 0x80FFFFFF);
			if (!puesta.isEmpty()) g.setTooltipForNextFrame(mc.font, puesta, mx, my);
			else g.setTooltipForNextFrame(mc.font, net.minecraft.network.chat.Component.literal("Mochila"), mx, my);
		}
	}
	public static boolean adentro(int left, int top, double mx, double my) { return mx >= left + CX - 1 && my >= top + CY - 1 && mx < left + CX + 17 && my < top + CY + 17; }
	public static void clicCasilla() { accion("mochila_casilla", null); }
}

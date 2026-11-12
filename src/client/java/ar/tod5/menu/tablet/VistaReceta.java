package ar.tod5.menu.tablet;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Dibuja la receta de un crafteo segun donde se hace (la usan la tablet normal y el modo admin):
 *   mesa      3x3 (con la cantidad de cada casilla) -> resultado
 *   hornos    ingrediente (con el fuego abajo) -> resultado
 *   herreria  plantilla + base + agregado -> resultado
 *   pociones  ingrediente arriba, pocion base abajo -> resultado
 */
final class VistaReceta {
	private VistaReceta() {}
	static final int T = 18;
	private static final int BORDE = 0xFF050706, FONDO = 0xFF19201C, LUZ = 0xFF34403A, FLECHA = 0xFF5A6A62;

	static String nombreTipo(String tipo) {
		return switch (tipo) {
			case "mesa" -> "Mesa de crafteo"; case "horno" -> "Horno"; case "fundicion" -> "Horno de fundición";
			case "ahumador" -> "Ahumador"; case "herreria" -> "Mesa de herrería"; case "pociones" -> "Soporte para pociones";
			default -> tipo;
		};
	}
	static ItemStack iconoTipo(String tipo) {
		return new ItemStack(switch (tipo) {
			case "horno" -> Items.FURNACE; case "fundicion" -> Items.BLAST_FURNACE; case "ahumador" -> Items.SMOKER;
			case "herreria" -> Items.SMITHING_TABLE; case "pociones" -> Items.BREWING_STAND; default -> Items.CRAFTING_TABLE;
		});
	}

	static void casilla(GuiGraphics g, int x, int y, int w, int h) {
		g.fill(x, y, x + w, y + h, BORDE);
		g.fill(x + 1, y + 1, x + w - 1, y + h - 1, FONDO);
		g.fill(x + w - 1, y + 1, x + w, y + h, LUZ);
		g.fill(x + 1, y + h - 1, x + w, y + h, LUZ);
	}
	private static void item(GuiGraphics g, ItemStack st, int x, int y, int mx, int my) {
		if (st == null || st.isEmpty()) return;
		Font f = Minecraft.getInstance().font;
		g.renderItem(st, x, y);
		if (st.getCount() > 1) g.renderItemDecorations(f, st, x, y, String.valueOf(st.getCount()));
		if (mx >= x - 1 && my >= y - 1 && mx < x + 17 && my < y + 17)
			g.setTooltipForNextFrame(st.getCount() > 1 ? Component.literal(st.getCount() + " × ").append(st.getHoverName()) : st.getHoverName(), mx, my);
	}
	private static void flecha(GuiGraphics g, int x, int y) {
		g.fill(x, y + 3, x + 12, y + 6, FLECHA);
		for (int k = 0; k < 5; k++) g.fill(x + 11 + k, y + k, x + 12 + k, y + 9 - k, FLECHA);
	}

	/** Dibuja la receta con la esquina arriba-izquierda en (x, y). Devuelve el alto que ocupa. */
	static int dibujar(GuiGraphics g, TabletDatos.Crafteo c, int x, int y, int mx, int my) {
		ItemStack[] e = c.entrada();
		ItemStack res = c.resultado() == null || c.resultado().isEmpty() ? c.icono() : c.resultado();
		switch (c.tipo()) {
			case "mesa" -> {
				for (int i = 0; i < 9; i++) { int sx = x + (i % 3) * T, sy = y + (i / 3) * T; casilla(g, sx, sy, T, T); item(g, e[i], sx + 1, sy + 1, mx, my); }
				int ax = x + 3 * T + 6, ay = y + T + 4; flecha(g, ax, ay);
				int ox = ax + 22, oy = y + T - 4; casilla(g, ox, oy, 26, 26); item(g, res, ox + 5, oy + 5, mx, my);
				return 3 * T;
			}
			case "herreria" -> {
				for (int i = 0; i < 3; i++) { int sx = x + i * T; casilla(g, sx, y, T, T); item(g, e[i], sx + 1, y + 1, mx, my); }
				int ax = x + 3 * T + 6, ay = y + 4; flecha(g, ax, ay);
				int ox = ax + 22, oy = y - 4; casilla(g, ox, oy, 26, 26); item(g, res, ox + 5, oy + 5, mx, my);
				return T + 4;
			}
			case "pociones" -> {
				casilla(g, x + T, y, T, T); item(g, e[0], x + T + 1, y + 1, mx, my);                 // ingrediente
				g.fill(x + T + T / 2, y + T, x + T + T / 2 + 1, y + T + 8, FLECHA);
				casilla(g, x + T, y + T + 8, T, T); item(g, e[1], x + T + 1, y + T + 9, mx, my);      // pocion base
				int ax = x + 3 * T + 6, ay = y + T + 4; flecha(g, ax, ay);
				int ox = ax + 22, oy = y + T - 4; casilla(g, ox, oy, 26, 26); item(g, res, ox + 5, oy + 5, mx, my);
				return 2 * T + 8;
			}
			default -> {   // hornos
				casilla(g, x + T, y, T, T); item(g, e[0], x + T + 1, y + 1, mx, my);
				// el fuego
				int fx = x + T + 4, fy = y + T + 3;
				g.fill(fx + 3, fy, fx + 7, fy + 9, 0xFFE07A2A); g.fill(fx + 1, fy + 4, fx + 9, fy + 9, 0xFFE07A2A); g.fill(fx + 3, fy + 5, fx + 7, fy + 9, 0xFFFFD050);
				int ax = x + 3 * T + 6, ay = y + 4; flecha(g, ax, ay);
				int ox = ax + 22, oy = y - 4; casilla(g, ox, oy, 26, 26); item(g, res, ox + 5, oy + 5, mx, my);
				return T + 14;
			}
		}
	}
	/** Ancho total que ocupa una receta (para acomodar lo de al lado). */
	static int ancho() { return 3 * T + 6 + 22 + 26; }
}

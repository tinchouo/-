package ar.tod5.menu;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/** Inventario, cofres, barra: el brillo de fondo detras de los items con rareza (crece con la rareza) y las chispitas del exotico. */
public final class RarezaSlot {
	private RarezaSlot() {}
	static final Identifier BRILLO = Identifier.fromNamespaceAndPath("tod5", "textures/efectos/rareza_brillo.png");

	private static int conAlfa(int rgb, float a) { return ((int) (Math.max(0, Math.min(1, a)) * 255) << 24) | (rgb & 0xFFFFFF); }

	/** Antes del item: el brillo. */
	public static void atras(GuiGraphics g, ItemStack it, int x, int y) {
		Rareza r = Rareza.rareza(it);
		if (r == null) return;
		float p = Rareza.pulso();
		int z = r.estrellas >= 5 ? 20 : 10 + r.estrellas * 2;
		dibujar(g, x + 8 - z / 2, y + 8 - z / 2, z, conAlfa(r.color, (r.estrellas >= 5 ? 0.95f : 0.8f) * p));
		if (r.estrellas >= 5) { int c = 10; dibujar(g, x + 8 - c / 2, y + 8 - c / 2, c, conAlfa(r.claro, 0.9f * p)); }
	}
	private static void dibujar(GuiGraphics g, int x, int y, int z, int color) {
		g.blit(RenderPipelines.GUI_TEXTURED, BRILLO, x, y, 0f, 0f, z, z, 32, 32, 32, 32, color);
	}

	/** Despues del item: las chispitas blancas del exotico (aparecen, suben un poquito y se van). */
	public static void adelante(GuiGraphics g, ItemStack it, int x, int y) {
		if (Rareza.rareza(it) != Rareza.EXOTICO || Ajustes.rendimiento()) return;
		float t = Rareza.segundos();
		for (int k = 0; k < 5; k++) {
			float per = 2.0f + 0.37f * k;
			float fase = (hash(x * 31 + y * 17 + k * 7) & 1023) / 1023f * per;
			float v = (t + fase) / per;
			int ciclo = (int) Math.floor(v);
			float pr = v - ciclo;
			int h = hash(x * 131 + y * 71 + k * 13 + ciclo * 977);
			int px = x + 2 + (h & 15) * 12 / 15, py = y + 13 - (int) (pr * 9) - ((h >> 4) & 3);
			float a = (float) Math.sin(pr * Math.PI);
			g.fill(px, py, px + 1, py + 1, conAlfa(0xF2FEFF, a));
		}
	}
	private static int hash(int n) { n ^= n >>> 16; n *= 0x7feb352d; n ^= n >>> 15; n *= 0x846ca68b; n ^= n >>> 16; return n & 0x7fffffff; }
}

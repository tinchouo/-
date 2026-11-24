package ar.tod5.menu;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;

/**
 * Lo visual del Purgatorio (el mundo "tod5_purgatorio" del plugin), todo del lado del mod:
 *  - mientras estas ahi: la pantalla un poco mas oscura y verde en los bordes y, cada tanto, bandas de corrupcion
 *  - el "texto del sistema" que manda el plugin (canal tod5:sistema): rojo, corrupto, en el medio de la pantalla
 * La roca verde con aristas es la textura de la pizarra reforzada (la trae el mod).
 */
public final class Purgatorio {
	private Purgatorio() {}
	private static String[] lineas = new String[0];
	private static long desde, hasta;
	private static long glitchHasta;
	private static final java.util.Random R = new java.util.Random();

	public static void sistema(String txt) {
		lineas = txt.split("\n");
		desde = Util.getMillis(); hasta = desde + 3000;
		glitchHasta = desde + 500;
	}
	static boolean adentro() {
		Minecraft mc = Minecraft.getInstance();
		return mc.level != null && mc.level.dimension().toString().contains("tod5_purgatorio");
	}

	public static void registrar() {
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(TOD5Client.MOD_ID, "purgatorio"), (g, dt) -> dibujar(g));
	}

	private static void dibujar(GuiGraphics g) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.options.hideGui) return;
		long ahora = Util.getMillis();
		int w = g.guiWidth(), h = g.guiHeight();
		if (adentro()) {
			// bordes oscuros y verdes
			int borde = Math.max(24, h / 5);
			g.fillGradient(0, 0, w, borde, 0xC0010805, 0x00010805);
			g.fillGradient(0, h - borde, w, h, 0x00010805, 0xC0010805);
			for (int i = 0; i < 6; i++) { int a = (int) (150 * (1 - i / 6f)); g.fill(i * borde / 6, 0, (i + 1) * borde / 6, h, (a << 24) | 0x010805); g.fill(w - (i + 1) * borde / 6, 0, w - i * borde / 6, h, (a << 24) | 0x010805); }
			g.fill(0, 0, w, h, 0x14123A26);
			// corrupcion: casi nunca, y de golpe
			if (!Ajustes.rendimiento() && R.nextInt(240) == 0) glitchHasta = ahora + 120 + R.nextInt(250);
		}
		if (ahora < glitchHasta) {
			for (int i = 0; i < 4 + R.nextInt(6); i++) {
				int y = R.nextInt(h), alto = 1 + R.nextInt(6), x = R.nextInt(w / 2);
				int[] colores = { 0x6000FFDC, 0x60FFDC00, 0x803DD68C, 0x70000000, 0x50FFFFFF };
				g.fill(x, y, x + w / 3 + R.nextInt(w / 2), y + alto, colores[R.nextInt(colores.length)]);
			}
		}
		// el texto del sistema
		if (ahora < hasta && lineas.length > 0) {
			Font f = mc.font;
			float esc = Math.max(1.5f, Math.min(3f, w / 260f));
			float alfa = Math.min(1f, Math.min((ahora - desde) / 150f, (hasta - ahora) / 400f));
			int a = (int) (alfa * 255) << 24;
			int y0 = (int) (h * 0.22f);
			for (int i = 0; i < lineas.length; i++) {
				String l = lineas[i];
				// a veces una letra se cambia por basura
				if (R.nextInt(14) == 0 && l.length() > 2) { char[] c = l.toCharArray(); c[R.nextInt(c.length)] = "#%&@$?/\\|[]".charAt(R.nextInt(11)); l = new String(c); }
				float lw = f.width(l) * esc;
				float x = (w - lw) / 2f + (R.nextInt(30) == 0 ? R.nextInt(7) - 3 : 0);
				float y = y0 + i * (f.lineHeight + 3) * esc;
				g.pose().pushMatrix(); g.pose().translate(x, y); g.pose().scale(esc, esc);
				g.drawString(f, l, -1, 0, a | 0x00FFDC, false);
				g.drawString(f, l, 1, 0, a | 0xFFDC00, false);
				g.drawString(f, l, 0, 0, a | 0xFF3B4E, false);
				g.pose().popMatrix();
			}
		}
	}
}

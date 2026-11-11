package ar.tod5.menu;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;

/**
 * El contador de un desafio de monedas, arriba al medio: la moneda verde y "3 / 8".
 * Lo manda el plugin (canal tod5:desafio). Al juntar una, el numero salta un poquito.
 * Al terminar queda unos segundos en dorado y se va.
 */
public final class ContadorDesafio {
	private ContadorDesafio() {}
	private static final Identifier MONEDA = Identifier.fromNamespaceAndPath(TOD5Client.MOD_ID, "textures/item/moneda_desafio.png");
	private static int juntadas, total;
	private static boolean activo, fin;
	private static long saltoDesde, finHasta;

	public static void recibir(String s) {
		String[] p = s.trim().split(";");
		if (p[0].equals("nada")) { activo = false; return; }
		try {
			if (p[0].equals("fin")) { fin = true; total = Integer.parseInt(p[1]); juntadas = total; finHasta = Util.getMillis() + 4000; }
			else { fin = false; int j = Integer.parseInt(p[0]); if (j != juntadas) saltoDesde = Util.getMillis(); juntadas = j; total = Integer.parseInt(p[1]); }
			activo = true;
		} catch (RuntimeException e) { activo = false; }
	}

	public static void registrar() {
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(TOD5Client.MOD_ID, "contador_desafio"), (g, dt) -> dibujar(g));
	}

	private static void dibujar(GuiGraphics g) {
		if (!activo) return;
		long ahora = Util.getMillis();
		if (fin && ahora > finHasta) { activo = false; return; }
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		Font f = mc.font;
		String t = juntadas + " / " + total;
		int w = 16 + 4 + f.width(t), x = (g.guiWidth() - w) / 2, y = 8;
		g.fill(x - 5, y - 4, x + w + 5, y + 20, 0x90000000);
		g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, MONEDA, x, y, 0, 0, 16, 16, 16, 16);
		float salto = Math.max(0, 1 - (ahora - saltoDesde) / 250f);   // el numero se agranda y vuelve
		float esc = 1 + 0.35f * salto;
		int color = fin ? 0xFFFFD84A : 0xFF7DFF9A;
		g.pose().pushMatrix();
		g.pose().translate(x + 20, y + 4.5f);
		g.pose().scale(esc, esc);
		g.drawString(f, t, 0, (int) (-4 * (esc - 1)), color, true);
		g.pose().popMatrix();
	}
}

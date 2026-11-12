package ar.tod5.menu;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;

/**
 * El contador de las monedas de una zona, a la izquierda de la pantalla (parecido al de Mario Galaxy):
 *   [moneda] × 03/08
 * Aparece cuando entras a una zona con monedas y se va al salir. Al juntar una, el numero salta.
 * Al terminar queda en dorado unos segundos y se va. Lo manda el plugin (canal tod5:desafio).
 */
public final class ContadorDesafio {
	private ContadorDesafio() {}
	private static final Identifier ICONO = Identifier.fromNamespaceAndPath(TOD5Client.MOD_ID, "textures/item/moneda_desafio_icono.png");
	private static int juntadas, total;
	private static boolean activo, fin;
	private static long saltoDesde, finHasta, aparecioEn;

	public static void recibir(String s) {
		String[] p = s.trim().split(";");
		if (p[0].equals("nada")) { activo = false; return; }
		try {
			if (p[0].equals("fin")) { fin = true; total = Integer.parseInt(p[1]); juntadas = total; finHasta = Util.getMillis() + 3500; saltoDesde = Util.getMillis(); }
			else {
				int j = Integer.parseInt(p[0]);
				if (!activo) aparecioEn = Util.getMillis();
				if (j != juntadas) saltoDesde = Util.getMillis();
				fin = false; juntadas = j; total = Integer.parseInt(p[1]);
			}
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
		if (mc.player == null || mc.options.hideGui) return;
		Font f = mc.font;
		int digitos = String.valueOf(total).length();
		String cuenta = String.format("%0" + digitos + "d", juntadas) + "/" + total;
		// entra deslizando desde la izquierda
		float entrada = Math.min(1f, (ahora - aparecioEn) / 250f);
		int x = (int) (-60 + 68 * (1 - (1 - entrada) * (1 - entrada))), y = g.guiHeight() / 2 - 40;
		g.blit(RenderPipelines.GUI_TEXTURED, ICONO, x, y, 0, 0, 16, 16, 16, 16);
		g.drawString(f, "×", x + 19, y + 5, 0xFFFFFFFF, true);
		float salto = Math.max(0, 1 - (ahora - saltoDesde) / 220f);
		float esc = 1.25f + 0.3f * salto;
		int color = fin ? 0xFFFFD84A : 0xFFFFFFFF;
		g.pose().pushMatrix();
		g.pose().translate(x + 27, y + 8 - 4.5f * esc);
		g.pose().scale(esc, esc);
		g.drawString(f, cuenta, 0, 0, color, true);
		g.pose().popMatrix();
	}
}

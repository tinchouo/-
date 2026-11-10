package ar.tod5.menu;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Boton con el estilo del logo: placa de hierro con borde verde. Al pasar el mouse se enciende en verde.
 * Cada boton es una imagen (con el texto ya dibujado): textures/gui/<estilo><nombre>.png y <estilo><nombre>_hover.png
 */
public class BotonTOD extends Button {
	private static final int TEX_W = 1000, TEX_H = 100;
	private Identifier normal, encima;

	public BotonTOD(String estilo, String nombre, String texto, int x, int y, int w, int h, OnPress accion) {
		super(x, y, w, h, Component.literal(texto), accion, DEFAULT_NARRATION);
		this.normal = Identifier.fromNamespaceAndPath(TOD5Client.MOD_ID, "textures/gui/" + estilo + nombre + ".png");
		this.encima = Identifier.fromNamespaceAndPath(TOD5Client.MOD_ID, "textures/gui/" + estilo + nombre + "_hover.png");
	}

	/** Cambia la imagen del boton (por ejemplo JUGAR -> EN ESPERA). */
	public void imagen(String estilo, String nombre) {
		this.normal = Identifier.fromNamespaceAndPath(TOD5Client.MOD_ID, "textures/gui/" + estilo + nombre + ".png");
		this.encima = Identifier.fromNamespaceAndPath(TOD5Client.MOD_ID, "textures/gui/" + estilo + nombre + "_hover.png");
	}

	@Override
	protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		Identifier tex = this.active && this.isHovered() ? this.encima : this.normal;
		int color = ((int) (Math.max(0, Math.min(1, this.alpha)) * 255) << 24) | 0xFFFFFF;
		g.blit(RenderPipelines.GUI_TEXTURED, tex, this.getX(), this.getY(), 0, 0, this.getWidth(), this.getHeight(),
			TEX_W, TEX_H, TEX_W, TEX_H, color);
	}
}

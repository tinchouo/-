package ar.tod5.menu;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

/**
 * La sala de espera: se muestra mientras el server esta cerrado. Tapa todo el juego con el fondo del menu,
 * dice cuantos jugadores estan esperando y se cierra sola cuando el server abre.
 */
public class SalaEspera extends Screen {
	/** Lo que mando el server la ultima vez. -1 = no estamos en la sala. */
	public static volatile int esperando = -1;

	private final long inicio = Util.getMillis();

	public SalaEspera() {
		super(Component.literal("Sala de espera"));
	}

	@Override
	protected void init() {
		this.addRenderableWidget(Button.builder(Component.literal("Salir del server"), b -> {
			esperando = -1;
			this.minecraft.disconnectFromWorld(Component.literal("Saliste de la sala de espera"));
		}).bounds(this.width / 2 - 75, this.height - 34, 150, 20).build());
	}

	@Override public boolean shouldCloseOnEsc() { return false; }
	@Override public boolean isPauseScreen() { return false; }
	@Override public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {}

	private static int argb(float a, int rgb) { return ((int) (Math.max(0, Math.min(1, a)) * 255) << 24) | (rgb & 0xFFFFFF); }

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		float t = (Util.getMillis() - this.inicio) / 1000f;
		int cy = this.height / 2 - 20;
		Fondo.dibujar(g, this.width, this.height, this.width / 2, cy, Math.min(this.height * 0.2f, this.width * 0.12f));

		int puntos = (int) (t * 2) % 4;
		String titulo = "SALA DE ESPERA" + ".".repeat(puntos);
		int y = (int) (cy + this.height * 0.2f);
		g.pose().pushMatrix();
		g.pose().translate(this.width / 2f, y);
		g.pose().scale(2f, 2f);
		g.text(this.font, titulo, -this.font.width("SALA DE ESPERA") / 2, 0, 0xFFDDEEE2);
		g.pose().popMatrix();

		int n = Math.max(esperando, 0);
		String cantidad = "Jugadores esperando: " + n;
		g.text(this.font, cantidad, this.width / 2 - this.font.width(cantidad) / 2, y + 26, 0xFF7DFFA0);
		String aviso = "El server todavia no abrio. Cuando abra, vas a entrar automaticamente.";
		g.text(this.font, aviso, this.width / 2 - this.font.width(aviso) / 2, y + 42, argb(0.55f + 0.25f * (float) Math.sin(t * 2), 0xFFFFFF));

		super.extractRenderState(g, mouseX, mouseY, a);
	}
}

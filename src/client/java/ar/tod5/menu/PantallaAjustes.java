package ar.tod5.menu;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Opciones > Trucofdeath...: los ajustes propios del mod (por ahora, donde sale la ruleta).
 * El boton lo agrega OpcionesMixin, abajo de los demas y en el medio.
 */
public class PantallaAjustes extends Screen {
	private final Screen anterior;

	public PantallaAjustes(Screen anterior) {
		super(Component.literal("TRUCOFDEATH"));
		this.anterior = anterior;
	}

	private Component textoRuleta() { return Component.literal("Ruleta de anuncios: " + Ajustes.ruleta().texto); }

	@Override
	protected void init() {
		int bw = 240, x = this.width / 2 - bw / 2, y = this.height / 2 - 24;
		this.addRenderableWidget(Button.builder(textoRuleta(), b -> { Ajustes.ruleta(Ajustes.ruleta().siguiente()); b.setMessage(textoRuleta()); })
			.bounds(x, y, bw, 20).build());
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
			.bounds(this.width / 2 - 100, this.height - 32, 200, 20).build());
	}

	@Override
	public void onClose() { this.minecraft.gui.setScreen(this.anterior); }

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		String t = "Opciones de Trucofdeath";
		g.text(this.font, t, (this.width - this.font.width(t)) / 2, 16, 0xFFFFFFFF, true);
		String ayuda = switch (Ajustes.ruleta()) {
			case CENTRO -> "La ruleta sale en el medio de la pantalla.";
			case ESQUINA -> "La ruleta sale más chica, abajo a la derecha.";
		};
		g.text(this.font, ayuda, (this.width - this.font.width(ayuda)) / 2, this.height / 2 + 2, 0xFFAAAAAA, false);
	}
}

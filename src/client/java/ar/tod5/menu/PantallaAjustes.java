package ar.tod5.menu;

import net.minecraft.client.gui.GuiGraphics;
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

	private Component textoRareza() { return Component.literal("Efectos de rareza: " + (Ajustes.rendimiento() ? "Rendimiento" : "Completos")); }
	private Component textoArmadura() { return Component.literal("Armadura en la barra: " + (Ajustes.armadura() ? "Sí" : "No")); }
	private Component textoRuleta() { return Component.literal("Ruleta de anuncios: " + Ajustes.ruleta().texto); }

	@Override
	protected void init() {
		int bw = 240, x = this.width / 2 - bw / 2, y = this.height / 2 - 24;
		this.addRenderableWidget(Button.builder(textoRuleta(), b -> { Ajustes.ruleta(Ajustes.ruleta().siguiente()); b.setMessage(textoRuleta()); })
			.bounds(x, y, bw, 20).build());
		this.addRenderableWidget(Button.builder(textoRareza(), b -> { Ajustes.rendimiento(!Ajustes.rendimiento()); b.setMessage(textoRareza()); })
			.bounds(x, y + 40, bw, 20).build());
		this.addRenderableWidget(Button.builder(textoArmadura(), b -> { Ajustes.armadura(!Ajustes.armadura()); b.setMessage(textoArmadura()); })
			.bounds(x, y + 80, bw, 20).build());
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
			.bounds(this.width / 2 - 100, this.height - 32, 200, 20).build());
	}

	@Override
	public void onClose() { this.minecraft.setScreen(this.anterior); }

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float a) {
		super.render(g, mouseX, mouseY, a);
		String t = "Opciones de Trucofdeath";
		g.drawString(this.font, t, (this.width - this.font.width(t)) / 2, 16, 0xFFFFFFFF, true);
		String ayuda = switch (Ajustes.ruleta()) {
			case CENTRO -> "La ruleta sale en el medio de la pantalla.";
			case ESQUINA -> "La ruleta sale más chica, abajo a la derecha.";
		};
		g.drawString(this.font, ayuda, (this.width - this.font.width(ayuda)) / 2, this.height / 2 + 2, 0xFFAAAAAA, false);
		String ayuda2 = Ajustes.rendimiento() ? "Sin animaciones ni partículas (para PC con pocos recursos)."
			: "Brillos animados y partículas en los ítems con rareza.";
		g.drawString(this.font, ayuda2, (this.width - this.font.width(ayuda2)) / 2, this.height / 2 + 42, 0xFFAAAAAA, false);
		String ayuda3 = Ajustes.armadura() ? "Tu armadura a la izquierda de la barra y la mochila a la derecha." : "No se muestra la armadura al lado de la barra.";
		g.drawString(this.font, ayuda3, (this.width - this.font.width(ayuda3)) / 2, this.height / 2 + 82, 0xFFAAAAAA, false);
	}
}

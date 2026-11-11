package ar.tod5.menu;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Opciones > TRUCOFDEATH...: los ajustes propios del mod (por ahora, donde sale la ruleta).
 * El boton va abajo de todo en Opciones, al lado de "Listo" (los dos del mismo ancho, simetricos).
 */
public class PantallaAjustes extends Screen {
	private final Screen anterior;

	public PantallaAjustes(Screen anterior) {
		super(Component.literal("TRUCOFDEATH"));
		this.anterior = anterior;
	}

	/** Agrega el boton "TRUCOFDEATH..." en Opciones (desde el menu y desde la pausa). */
	public static void registrar() {
		ScreenEvents.AFTER_INIT.register((mc, pantalla, w, h) -> {
			if (!(pantalla instanceof OptionsScreen)) return;
			for (AbstractWidget b : Screens.getWidgets(pantalla)) {
				if (!(b instanceof Button) || !b.getMessage().getString().equals(CommonComponents.GUI_DONE.getString())) continue;
				int x = b.getX(), y = b.getY(), ancho = b.getWidth(), mitad = (ancho - 4) / 2;
				b.setWidth(mitad);
				b.setX(x + ancho - mitad);
				Screens.getWidgets(pantalla).add(Button.builder(Component.literal("TRUCOFDEATH..."), boton -> mc.gui.setScreen(new PantallaAjustes(pantalla)))
					.bounds(x, y, mitad, b.getHeight()).build());
				break;
			}
		});
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
		String t = "Opciones de TRUCOFDEATH";
		g.text(this.font, t, (this.width - this.font.width(t)) / 2, 16, 0xFFFFFFFF, true);
		String ayuda = switch (Ajustes.ruleta()) {
			case CENTRO -> "La ruleta sale en el medio de la pantalla.";
			case ESQUINA -> "La ruleta sale más chica, abajo a la derecha.";
			case APAGADA -> "Sin ruleta: los anuncios salen solo en el chat.";
		};
		g.text(this.font, ayuda, (this.width - this.font.width(ayuda)) / 2, this.height / 2 + 2, 0xFFAAAAAA, false);
	}
}

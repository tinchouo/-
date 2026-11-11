package ar.tod5.menu.mixin;

import ar.tod5.menu.PantallaAjustes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Opciones: agrega "Trucofdeath..." abajo de los botones, solo y en el medio (ocupa las dos columnas). */
@Mixin(OptionsScreen.class)
public class OpcionesMixin {
	@ModifyArg(method = "init", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/layouts/HeaderAndFooterLayout;addToContents(Lnet/minecraft/client/gui/layouts/LayoutElement;)Lnet/minecraft/client/gui/layouts/LayoutElement;"))
	private LayoutElement tod5$boton(LayoutElement contenido) {
		if (contenido instanceof GridLayout grilla) {
			Screen opciones = (Screen) (Object) this;
			grilla.addChild(Button.builder(Component.literal("Trucofdeath..."), b -> Minecraft.getInstance().setScreen(new PantallaAjustes(opciones))).build(), 5, 0, 1, 2);
		}
		return contenido;
	}
}

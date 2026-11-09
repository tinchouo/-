package ar.tod5.menu.mixin;

import ar.tod5.menu.MenuPrincipal;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Cada vez que Minecraft va a abrir su menu principal, abre el de TOD5 en su lugar. */
@Mixin(Gui.class)
public class GuiMixin {
	@ModifyVariable(
		method = "setScreen",
		at = @At(value = "FIELD", target = "Lnet/minecraft/client/gui/Gui;screen:Lnet/minecraft/client/gui/screens/Screen;", opcode = org.objectweb.asm.Opcodes.PUTFIELD),
		argsOnly = true
	)
	private Screen tod5$reemplazarMenu(Screen screen) {
		if (screen != null && screen.getClass() == TitleScreen.class) return new MenuPrincipal();
		return screen;
	}
}

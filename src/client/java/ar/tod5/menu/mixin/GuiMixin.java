package ar.tod5.menu.mixin;

import ar.tod5.menu.MenuPrincipal;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import ar.tod5.menu.PantallaEntrar;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Cada vez que Minecraft va a abrir su menu principal (o la lista de servers), abre el de TOD5 en su lugar. */
@Mixin(Minecraft.class)
public class GuiMixin {
	private static boolean tieneMarca(Component c) {
		if ("tod5:preparacion".equals(c.getStyle().getInsertion())) return true;
		for (Component h : c.getSiblings()) if (tieneMarca(h)) return true;
		return false;
	}

	@ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true)
	private Screen tod5$reemplazarMenu(Screen screen) {
		if (screen != null && screen.getClass() == TitleScreen.class) return new MenuPrincipal();
		// no existe la lista de servers: al desconectarte (o si te echan y apretas "volver") vas al menu
		if (screen instanceof JoinMultiplayerScreen) return new MenuPrincipal();
		// el server nos rechazo por estar en preparacion (no somos OP): en vez del cartel de desconexion, a la cola
		if (screen instanceof DisconnectedScreen d && tieneMarca(((DisconnectedScreenAccessor) d).tod5$detalles().reason())) return PantallaEntrar.enCola();
		// "setScreen(null)" sin mundo cargado tambien termina en el menu principal
		if (screen == null && Minecraft.getInstance().level == null) return new MenuPrincipal();
		return screen;
	}
}

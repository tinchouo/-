package ar.tod5.menu.mixin;

import java.io.IOException;
import java.io.InputStream;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** La imagen de la pantalla de carga: en vez de "Mojang Studios", el logo de TRUCOFDEATH (sale del jar del mod). */
@Mixin(targets = "net.minecraft.client.gui.screens.LoadingOverlay$LogoTexture")
public class LogoTextureMixin {
	@Redirect(method = "loadContents", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/server/packs/resources/ResourceProvider;open(Lnet/minecraft/resources/Identifier;)Ljava/io/InputStream;"))
	private InputStream tod5$logo(ResourceProvider proveedor, Identifier id) throws IOException {
		InputStream propio = LogoTextureMixin.class.getResourceAsStream("/assets/tod5/textures/gui/carga.png");
		return propio != null ? propio : proveedor.open(id);
	}
}

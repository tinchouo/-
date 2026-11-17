package ar.tod5.menu.mixin;

import ar.tod5.menu.GravedadCliente;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.DisplayRenderer;
import net.minecraft.client.renderer.entity.state.DisplayEntityRenderState;
import net.minecraft.world.entity.Display;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Con la gravedad cambiada, lo que "siempre te mira" (las orbes) te mira de verdad: Minecraft lo gira con el yaw/pitch de la camara,
 * que no sabe que la camara esta girada (de costado). Se le suma el giro de mas que tiene la camara.
 */
@Mixin(DisplayRenderer.class)
public abstract class DisplayRendererMixin {
	@Inject(method = "calculateOrientation", at = @At("RETURN"))
	private void tod5$mirarDeVerdad(Display.RenderState rs, DisplayEntityRenderState st, Quaternionf q, CallbackInfoReturnable<Quaternionf> cir) {
		if (rs.billboardConstraints() != Display.BillboardConstraints.CENTER || GravedadCliente.normal()) return;
		Camera c = Minecraft.getInstance().gameRenderer.getMainCamera();
		float r = (float) (Math.PI / 180.0);
		// lo que Minecraft cree que es la camara vs. la camara de verdad: la diferencia se la aplico al dibujo
		Quaternionf cree = new Quaternionf().rotationYXZ((float) Math.PI - c.yRot() * r, -c.xRot() * r, 0f);
		Quaternionf extra = new Quaternionf(c.rotation()).mul(cree.conjugate());
		cir.getReturnValue().premul(extra);
	}
}

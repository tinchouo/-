package ar.tod5.menu.mixin;

import ar.tod5.menu.GravedadCliente;
import ar.tod5.menu.GravedadEstado;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Gravedad cambiada, como se ve:
 *  - para arriba: cabeza abajo (como un mob que se llama Dinnerbone)
 *  - de costado: acostado contra la pared (girado con el marco), mirando para donde mira dentro del marco
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
	@Inject(method = "isEntityUpsideDown", at = @At("HEAD"), cancellable = true)
	private void tod5$cabezaAbajo(LivingEntity e, CallbackInfoReturnable<Boolean> cir) {
		if (GravedadCliente.invertida(e)) cir.setReturnValue(true);
	}

	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V", at = @At("TAIL"))
	private void tod5$estado(LivingEntity e, LivingEntityRenderState s, float parcial, CallbackInfo ci) {
		GravedadCliente.G g = GravedadCliente.costado(e) ? GravedadCliente.de(e) : GravedadCliente.G.ABAJO;
		((GravedadEstado) s).tod5$gravedad(g);
		if (g.costado() && e != Minecraft.getInstance().player) {
			// los otros: el server tiene hacia donde mira en el mundo; lo paso al marco para dibujarlo
			float[] l = GravedadCliente.aMarco(g, e.getYHeadRot(), e.getXRot(parcial));
			s.bodyRot = l[0]; s.yRot = 0f; s.xRot = l[1];
		}
	}

	@Inject(method = "setupRotations", at = @At("HEAD"))
	private void tod5$costado(LivingEntityRenderState s, PoseStack pose, float cuerpo, float escala, CallbackInfo ci) {
		GravedadCliente.G g = ((GravedadEstado) s).tod5$gravedad();
		if (!g.costado()) return;
		float h = s.boundingBoxWidth / 2f / escala;
		pose.translate(g.x * h, h, g.z * h);   // los pies, contra la pared
		pose.mulPose(GravedadCliente.marcoDe(g));
	}
}

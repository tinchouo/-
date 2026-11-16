package ar.tod5.menu.mixin;

import ar.tod5.menu.GravedadCliente;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Gravedad dada vuelta: se ve cabeza abajo (como un mob que se llama Dinnerbone). */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
	@Inject(method = "isEntityUpsideDown", at = @At("HEAD"), cancellable = true)
	private void tod5$cabezaAbajo(LivingEntity e, CallbackInfoReturnable<Boolean> cir) {
		if (GravedadCliente.invertida(e)) cir.setReturnValue(true);
	}
}

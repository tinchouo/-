package ar.tod5.menu.mixin;

import ar.tod5.menu.GravedadCliente;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Gravedad dada vuelta: saltar desde el techo es saltar para abajo. */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
	@Shadow protected abstract float getJumpPower();

	@Inject(method = "jumpFromGround", at = @At("HEAD"), cancellable = true)
	private void tod5$saltar(CallbackInfo ci) {
		LivingEntity yo = (LivingEntity) (Object) this;
		if (!(yo instanceof LocalPlayer) || !GravedadCliente.invertida(yo)) return;
		ci.cancel();
		float f = this.getJumpPower();
		if (f <= 1.0E-5F) return;
		Vec3 v = yo.getDeltaMovement();
		yo.setDeltaMovement(v.x, Math.min(-f, v.y), v.z);
		if (yo.isSprinting()) {
			float g = yo.getYRot() * (float) (Math.PI / 180.0);
			yo.addDeltaMovement(new Vec3(-Mth.sin(g) * 0.2, 0.0, Mth.cos(g) * 0.2));
		}
	}
}

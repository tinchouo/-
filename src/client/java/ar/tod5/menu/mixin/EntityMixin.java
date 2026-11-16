package ar.tod5.menu.mixin;

import ar.tod5.menu.GravedadCliente;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Gravedad dada vuelta, para el jugador propio:
 *  - el techo cuenta como piso (para caminar a velocidad normal y poder saltar)
 *  - los ojos (lo que apunta la mira) quedan donde esta la camara
 */
@Mixin(Entity.class)
public abstract class EntityMixin {
	@Shadow public boolean verticalCollision;
	@Unique private double tod5$movY;

	@Inject(method = "move", at = @At("HEAD"))
	private void tod5$guardarMovimiento(MoverType tipo, Vec3 mov, CallbackInfo ci) { this.tod5$movY = mov.y; }

	@ModifyArg(method = "move", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;setOnGroundWithMovement(ZZLnet/minecraft/world/phys/Vec3;)V"), index = 0)
	private boolean tod5$techoEsPiso(boolean abajo) {
		if ((Object) this instanceof LocalPlayer p && GravedadCliente.invertida(p)) return this.verticalCollision && this.tod5$movY > 0.0;
		return abajo;
	}

	@Inject(method = "getEyePosition(F)Lnet/minecraft/world/phys/Vec3;", at = @At("RETURN"), cancellable = true)
	private void tod5$ojos(float parcial, CallbackInfoReturnable<Vec3> cir) {
		if ((Object) this instanceof LocalPlayer p && GravedadCliente.giro(parcial) > 0.0001f)
			cir.setReturnValue(cir.getReturnValue().add(0.0, GravedadCliente.bajarOjos(p, parcial), 0.0));
	}
}

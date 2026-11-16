package ar.tod5.menu.mixin;

import ar.tod5.menu.GravedadCliente;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
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
 * Gravedad cambiada (en el cliente):
 *  - para arriba: el techo cuenta como piso (para caminar a velocidad normal y poder saltar)
 *  - los ojos y lo que apunta la mira del jugador propio quedan donde esta la camara
 *  - de costado: la caja queda acostada contra la pared, y la pose es siempre parado/agachado (nunca nadando)
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
		if ((Object) this instanceof LocalPlayer p && !GravedadCliente.normal()) cir.setReturnValue(GravedadCliente.ojos(p, parcial));
	}

	@Inject(method = "getViewVector", at = @At("RETURN"), cancellable = true)
	private void tod5$mira(float parcial, CallbackInfoReturnable<Vec3> cir) {
		if ((Object) this instanceof LocalPlayer p && GravedadCliente.costado(p))
			cir.setReturnValue(GravedadCliente.mirada(GravedadCliente.de(p), p.getViewXRot(parcial), p.getViewYRot(parcial)));
	}

	@Inject(method = "makeBoundingBox(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/AABB;", at = @At("HEAD"), cancellable = true)
	private void tod5$caja(Vec3 pos, CallbackInfoReturnable<AABB> cir) {
		Entity e = (Entity) (Object) this;
		if (e instanceof Player && e.level() != null && e.level().isClientSide() && GravedadCliente.costado(e))
			cir.setReturnValue(GravedadCliente.caja(pos, GravedadCliente.de(e), e.getBbWidth(), e.getBbHeight()));
	}

	/** Cabeza abajo: al agacharse (o pararse) la caja se achica desde los pies, que estan arriba: se corre para que siga pegada al techo. */
	@Unique private float tod5$altoAntes;
	@Inject(method = "refreshDimensions", at = @At("HEAD"))
	private void tod5$altoAntes(CallbackInfo ci) { this.tod5$altoAntes = ((Entity) (Object) this).getBbHeight(); }
	@Inject(method = "refreshDimensions", at = @At("TAIL"))
	private void tod5$pegadoAlTecho(CallbackInfo ci) {
		if ((Object) this instanceof LocalPlayer p && GravedadCliente.invertida(p)) {
			double dh = this.tod5$altoAntes - p.getBbHeight();
			if (Math.abs(dh) > 1.0E-4) { p.setPos(p.getX(), p.getY() + dh, p.getZ()); p.yo += dh; p.yOld += dh; }
		}
	}

	@Inject(method = "getPose", at = @At("HEAD"), cancellable = true)
	private void tod5$pose(CallbackInfoReturnable<Pose> cir) {
		if ((Object) this instanceof Player p && GravedadCliente.costado(p) && p.level() != null && p.level().isClientSide())
			cir.setReturnValue(GravedadCliente.pose(p));
	}
}

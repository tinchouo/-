package ar.tod5.menu.mixin;

import ar.tod5.menu.GravedadCliente;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Gravedad de costado: el movimiento lo hace el mod (dentro del marco), y la pose no cambia por la caja acostada. */
@Mixin(Player.class)
public abstract class PlayerMixin {
	@Inject(method = "travel", at = @At("HEAD"), cancellable = true)
	private void tod5$viajar(Vec3 entrada, CallbackInfo ci) {
		if (GravedadCliente.viajar((Player) (Object) this, entrada)) ci.cancel();
	}

	@Inject(method = "updatePlayerPose", at = @At("HEAD"), cancellable = true)
	private void tod5$pose(CallbackInfo ci) {
		Player p = (Player) (Object) this;
		if (p.level().isClientSide() && GravedadCliente.costado(p)) { p.setPose(GravedadCliente.pose(p)); ci.cancel(); }
	}

	@Inject(method = "canPlayerFitWithinBlocksAndEntitiesWhen", at = @At("HEAD"), cancellable = true)
	private void tod5$entra(Pose pose, CallbackInfoReturnable<Boolean> cir) {
		Player p = (Player) (Object) this;
		if (p.level().isClientSide() && GravedadCliente.costado(p)) cir.setReturnValue(true);
	}

	/** Agachado en el borde no te frena de costado (eso mira el piso de abajo del mundo). */
	@Inject(method = "maybeBackOffFromEdge", at = @At("HEAD"), cancellable = true)
	private void tod5$borde(Vec3 mov, MoverType tipo, CallbackInfoReturnable<Vec3> cir) {
		Player p = (Player) (Object) this;
		if (p.level().isClientSide() && GravedadCliente.costado(p)) cir.setReturnValue(mov);
	}
}
